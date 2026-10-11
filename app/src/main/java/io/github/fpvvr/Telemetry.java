package io.github.fpvvr;

import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;

/**
 * Receives the MAVLink telemetry OpenHD forwards to the phone (UDP 14550): standard messages
 * (HEARTBEAT, SYS_STATUS, ATTITUDE, VFR_HUD, ...) and the FC's whole OSD screen, which
 * fc_osd_bridge.py sends inside ENCAPSULATED_DATA frames tagged "OSD1".
 */
public final class Telemetry {
    private static final String TAG = "FpvVr.Tele";

    /** One run of OSD characters. */
    public static final class Cell {
        public final int row, col;
        public final byte[] text;

        Cell(int row, int col, byte[] text) {
            this.row = row;
            this.col = col;
            this.text = text;
        }
    }

    // ---- state (read by the OSD painter)
    public volatile long lastMsgMs, lastFcMs, lastOsdMs, lastAttMs;
    public volatile boolean armed;
    public volatile int autopilot, vehicleType, customMode;
    public volatile float voltage = -1, roll, pitch, altitude, groundSpeed;
    public volatile int batteryPct = -1, rssi = -1;
    public volatile String statusText = "";
    public volatile long statusTextMs;
    public volatile List<Cell> osdCells = new ArrayList<>();
    public volatile int version;      // bumped on every change that affects the OSD

    private final int port;
    private volatile boolean running;
    private Thread thread;
    private DatagramSocket socket;
    private List<Cell> pendingOsd;
    private int pendingId = -1;
    private int fcSys = -1;

    public Telemetry(int port) {
        this.port = port;
    }

    public void start() {
        running = true;
        thread = new Thread(this::loop, "telemetry-rx");
        thread.start();
    }

    public void stop() {
        running = false;
        if (socket != null) socket.close();
        try {
            if (thread != null) thread.join(500);
        } catch (InterruptedException ignored) {
        }
    }

    private void loop() {
        byte[] buf = new byte[65536];
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
        try {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.setSoTimeout(500);
            socket.bind(new InetSocketAddress(port));
        } catch (Exception e) {
            Log.e(TAG, "cannot open UDP " + port, e);
            return;
        }
        Log.i(TAG, "listening on UDP " + port);
        while (running) {
            try {
                socket.receive(pkt);
            } catch (SocketTimeoutException e) {
                continue;
            } catch (Exception e) {
                if (running) Log.w(TAG, "receive", e);
                break;
            }
            try {
                parse(buf, pkt.getLength());
            } catch (Exception e) {
                Log.w(TAG, "parse", e);
            }
        }
    }

    // ------------------------------------------------------------------ MAVLink framing

    private static int crcExtra(int msgid) {
        switch (msgid) {
            case 0: return 50;    // HEARTBEAT
            case 1: return 124;   // SYS_STATUS
            case 30: return 39;   // ATTITUDE
            case 33: return 104;  // GLOBAL_POSITION_INT
            case 65: return 118;  // RC_CHANNELS
            case 74: return 20;   // VFR_HUD
            case 109: return 185; // RADIO_STATUS
            case 131: return 223; // ENCAPSULATED_DATA
            case 147: return 154; // BATTERY_STATUS
            case 253: return 83;  // STATUSTEXT
            default: return -1;
        }
    }

    private static int x25(byte[] b, int off, int n, int crc) {
        for (int i = 0; i < n; i++) crc = x25b(b[off + i] & 0xFF, crc);
        return crc;
    }

    private static int x25b(int v, int crc) {
        int tmp = v ^ (crc & 0xFF);
        tmp = (tmp ^ (tmp << 4)) & 0xFF;
        return ((crc >> 8) ^ (tmp << 8) ^ (tmp << 3) ^ (tmp >> 4)) & 0xFFFF;
    }

    /** A UDP datagram may hold several MAVLink frames (v1 0xFE or v2 0xFD). */
    void parse(byte[] b, int len) {
        int i = 0;
        while (i < len) {
            int stx = b[i] & 0xFF;
            if (stx == 0xFE && i + 8 <= len) {
                int plen = b[i + 1] & 0xFF;
                int flen = 6 + plen + 2;
                if (i + flen > len) return;
                int msgid = b[i + 5] & 0xFF;
                int extra = crcExtra(msgid);
                if (extra >= 0) {
                    int crc = x25b(extra, x25(b, i + 1, 5 + plen, 0xFFFF));
                    int got = (b[i + 6 + plen] & 0xFF) | ((b[i + 7 + plen] & 0xFF) << 8);
                    if (crc == got) handle(b[i + 3] & 0xFF, b[i + 4] & 0xFF, msgid, b, i + 6, plen);
                }
                i += flen;
            } else if (stx == 0xFD && i + 12 <= len) {
                int plen = b[i + 1] & 0xFF;
                int incompat = b[i + 2] & 0xFF;
                int flen = 10 + plen + 2 + ((incompat & 1) != 0 ? 13 : 0);
                if (i + flen > len) return;
                int msgid = (b[i + 7] & 0xFF) | ((b[i + 8] & 0xFF) << 8) | ((b[i + 9] & 0xFF) << 16);
                int extra = crcExtra(msgid);
                if (extra >= 0) {
                    int crc = x25b(extra, x25(b, i + 1, 9 + plen, 0xFFFF));
                    int got = (b[i + 10 + plen] & 0xFF) | ((b[i + 11 + plen] & 0xFF) << 8);
                    if (crc == got) handle(b[i + 5] & 0xFF, b[i + 6] & 0xFF, msgid, b, i + 10, plen);
                }
                i += flen;
            } else {
                i++;
            }
        }
    }

    // ------------------------------------------------------------------ messages

    /** Little-endian field access on a (possibly zero-truncated, MAVLink 2) payload. */
    private static final class P {
        final byte[] b;
        final int off, len;

        P(byte[] b, int off, int len) {
            this.b = b;
            this.off = off;
            this.len = len;
        }

        int u8(int o) {
            return o < len ? b[off + o] & 0xFF : 0;
        }

        int u16(int o) {
            return u8(o) | (u8(o + 1) << 8);
        }

        int s16(int o) {
            return (short) u16(o);
        }

        long u32(int o) {
            return (u16(o) | ((long) u16(o + 2) << 16)) & 0xFFFFFFFFL;
        }

        int s32(int o) {
            return (int) u32(o);
        }

        float f32(int o) {
            return Float.intBitsToFloat(s32(o));
        }
    }

    private void handle(int sys, int comp, int msgid, byte[] b, int off, int len) {
        // OpenHD's own air (100) / ground (101) systems are not the FC
        boolean fromFc = sys != 100 && sys != 101;
        long now = System.currentTimeMillis();
        lastMsgMs = now;
        P p = new P(b, off, len);
        switch (msgid) {
            case 0: // HEARTBEAT: custom_mode u32, type u8, autopilot u8, base_mode u8
                if (!fromFc || p.u8(5) == 8 /* MAV_AUTOPILOT_INVALID */) return;
                if (fcSys < 0 && comp == 1) fcSys = sys;
                if (sys != fcSys) return;
                lastFcMs = now;
                boolean a = (p.u8(6) & 0x80) != 0;
                int mode = (int) p.u32(0);
                if (a != armed || mode != customMode) {
                    armed = a;
                    customMode = mode;
                    version++;
                }
                vehicleType = p.u8(4);
                autopilot = p.u8(5);
                break;
            case 1: // SYS_STATUS: voltage_battery u16 @14 (mV), battery_remaining s8 @30
                if (!fromFc) return;
                int mv = p.u16(14);
                if (mv > 0 && mv != 0xFFFF) setVoltage(mv / 1000f);
                int pct = (byte) p.u8(30);
                if (pct != batteryPct) {
                    batteryPct = pct;
                    version++;
                }
                break;
            case 30: // ATTITUDE: roll f32 @4, pitch f32 @8 (rad)
                if (!fromFc) return;
                roll = (float) Math.toDegrees(p.f32(4));
                pitch = (float) Math.toDegrees(p.f32(8));
                lastAttMs = now;
                version++;
                break;
            case 74: // VFR_HUD: groundspeed f32 @4, alt f32 @8
                if (!fromFc) return;
                groundSpeed = p.f32(4);
                altitude = p.f32(8);
                version++;
                break;
            case 109: // RADIO_STATUS: rssi u8 @4
                rssi = p.u8(4);
                break;
            case 253: { // STATUSTEXT: severity u8, text char[50]
                if (!fromFc) return;
                StringBuilder sb = new StringBuilder();
                for (int k = 1; k < 51 && k < len; k++) {
                    int c = p.u8(k);
                    if (c == 0) break;
                    sb.append((char) c);
                }
                statusText = sb.toString();
                statusTextMs = now;
                version++;
                break;
            }
            case 131: // ENCAPSULATED_DATA: seqnr u16, data[253]
                handleOsd(p);
                break;
            default:
                break;
        }
    }

    private void setVoltage(float v) {
        if (Math.abs(v - voltage) >= 0.01f) {
            voltage = v;
            version++;
        }
    }

    /** "OSD1" frames from fc_osd_bridge: flags (1 = first, 2 = last), frame id, then (row, col, n, text). */
    private void handleOsd(P p) {
        int d = 2; // skip seqnr
        if (p.u8(d) != 'O' || p.u8(d + 1) != 'S' || p.u8(d + 2) != 'D' || p.u8(d + 3) != '1') return;
        int flags = p.u8(d + 4), fid = p.u8(d + 5);
        if ((flags & 1) != 0) {
            pendingOsd = new ArrayList<>();
            pendingId = fid;
        }
        if (pendingOsd == null || fid != pendingId) return;
        int i = d + 6;
        int end = 2 + 253;
        while (i + 3 <= end) {
            int r = p.u8(i), c = p.u8(i + 1), n = p.u8(i + 2);
            if (r == 0 && c == 0 && n == 0) break;
            byte[] t = new byte[n];
            for (int k = 0; k < n; k++) t[k] = (byte) p.u8(i + 3 + k);
            pendingOsd.add(new Cell(r, c, t));
            i += 3 + n;
        }
        if ((flags & 2) != 0) {
            osdCells = pendingOsd;
            pendingOsd = null;
            lastOsdMs = System.currentTimeMillis();
            version++;
        }
    }
}
