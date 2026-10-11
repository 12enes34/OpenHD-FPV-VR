package io.github.fpvvr;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Receives the OpenHD video (RTP / H.264 over UDP, port 5600 by default), rebuilds access units and
 * decodes them with the phone's hardware decoder straight onto a Surface (low-latency mode).
 */
public final class VideoReceiver {
    private static final String TAG = "FpvVr.Video";
    private static final byte[] START = {0, 0, 0, 1};

    private final Surface surface;
    private final int port;
    private volatile boolean running;
    private Thread rxThread, outThread;
    private DatagramSocket socket;
    private MediaCodec codec;
    private byte[] sps, pps;

    // RTP reassembly
    private final ByteArrayOutputStream au = new ByteArrayOutputStream(256 * 1024);
    private final ByteArrayOutputStream fu = new ByteArrayOutputStream(256 * 1024);
    private boolean fuActive;
    private int lastSeq = -1;
    private boolean auBroken;

    // statistics (read by the OSD)
    public volatile long lastFrameMs;      // last decoded frame (System.currentTimeMillis)
    public volatile int fps;
    public volatile float mbps;
    public volatile float decodeMs;
    public volatile int width, height;
    public volatile int lostFrames;        // frames with missing RTP packets (link quality)
    private int frameCount;
    private long byteCount, statStart = System.currentTimeMillis();

    public VideoReceiver(Surface surface, int port) {
        this.surface = surface;
        this.port = port;
    }

    public void start() {
        running = true;
        rxThread = new Thread(this::rxLoop, "video-rx");
        rxThread.setPriority(Thread.MAX_PRIORITY);
        rxThread.start();
    }

    public void stop() {
        running = false;
        if (socket != null) socket.close();
        try {
            if (rxThread != null) rxThread.join(500);
            if (outThread != null) outThread.join(500);
        } catch (InterruptedException ignored) {
        }
        releaseCodec();
    }

    // ------------------------------------------------------------------ network / RTP

    private void rxLoop() {
        byte[] buf = new byte[65536];
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
        try {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.setReceiveBufferSize(1 << 20);
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
            byteCount += pkt.getLength();
            try {
                handleRtp(buf, pkt.getLength());
            } catch (Exception e) {
                Log.w(TAG, "bad packet", e);
                resetAssembly();
            }
        }
    }

    private void resetAssembly() {
        au.reset();
        fu.reset();
        fuActive = false;
        auBroken = false;
    }

    private void handleRtp(byte[] b, int len) {
        if (len < 12 || (b[0] & 0xC0) != 0x80) return;
        int cc = b[0] & 0x0F;
        boolean ext = (b[0] & 0x10) != 0;
        boolean pad = (b[0] & 0x20) != 0;
        boolean marker = (b[1] & 0x80) != 0;
        int seq = ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
        int off = 12 + cc * 4;
        if (ext) {
            if (len < off + 4) return;
            off += 4 + (((b[off + 2] & 0xFF) << 8 | (b[off + 3] & 0xFF)) * 4);
        }
        if (pad) len -= b[len - 1] & 0xFF;
        if (off >= len) return;

        if (lastSeq >= 0 && seq != ((lastSeq + 1) & 0xFFFF)) {
            auBroken = true;   // lost packet: this frame is damaged
            fuActive = false;
            fu.reset();
        }
        lastSeq = seq;

        int nalType = b[off] & 0x1F;
        if (nalType >= 1 && nalType <= 23) {
            addNal(b, off, len - off);
        } else if (nalType == 24) { // STAP-A
            int p = off + 1;
            while (p + 2 <= len) {
                int n = ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
                p += 2;
                if (n == 0 || p + n > len) break;
                addNal(b, p, n);
                p += n;
            }
        } else if (nalType == 28 && len - off > 2) { // FU-A
            int fuHeader = b[off + 1] & 0xFF;
            if ((fuHeader & 0x80) != 0) {
                fu.reset();
                fu.write((b[off] & 0xE0) | (fuHeader & 0x1F));
                fuActive = true;
            }
            if (fuActive) {
                fu.write(b, off + 2, len - off - 2);
                if ((fuHeader & 0x40) != 0) {
                    byte[] nal = fu.toByteArray();
                    addNal(nal, 0, nal.length);
                    fuActive = false;
                    fu.reset();
                }
            }
        }
        if (marker) flushAccessUnit();
    }

    private void addNal(byte[] b, int off, int n) {
        int type = b[off] & 0x1F;
        if (type == 7) {
            byte[] s = Arrays.copyOfRange(b, off, off + n);
            if (sps != null && !Arrays.equals(sps, s) && codec != null) {
                Log.i(TAG, "SPS changed -> restarting decoder");
                releaseCodec();
            }
            sps = s;
        } else if (type == 8) {
            pps = Arrays.copyOfRange(b, off, off + n);
        }
        au.write(START, 0, 4);
        au.write(b, off, n);
    }

    private void flushAccessUnit() {
        byte[] data = au.toByteArray();
        boolean broken = auBroken;
        au.reset();
        auBroken = false;
        if (data.length == 0) return;
        if (codec == null && !startCodec()) return;
        if (broken && !containsIdr(data)) lostFrames++; // still decoded: the decoder conceals the damage
        try {
            int idx = codec.dequeueInputBuffer(20000);
            if (idx < 0) return; // decoder busy: drop rather than build up latency
            ByteBuffer in = codec.getInputBuffer(idx);
            in.clear();
            if (data.length > in.capacity()) {
                codec.queueInputBuffer(idx, 0, 0, 0, 0);
                return;
            }
            in.put(data);
            codec.queueInputBuffer(idx, 0, data.length, System.nanoTime() / 1000, 0);
        } catch (Exception e) {
            Log.w(TAG, "decoder input failed -> restart", e);
            releaseCodec();
        }
    }

    private static boolean containsIdr(byte[] d) {
        for (int i = 0; i + 4 < d.length; i++) {
            if (d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 0 && d[i + 3] == 1 && (d[i + 4] & 0x1F) == 5) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ decoder

    private boolean startCodec() {
        if (sps == null || pps == null) return false;
        try {
            MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720);
            f.setByteBuffer("csd-0", ByteBuffer.wrap(concat(START, sps)));
            f.setByteBuffer("csd-1", ByteBuffer.wrap(concat(START, pps)));
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 << 20);
            f.setInteger(MediaFormat.KEY_PRIORITY, 0); // realtime
            if (Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            // vendor low-latency switches (ignored by decoders that don't know them)
            f.setInteger("vendor.rtc-ext-dec-low-latency.enable", 1);
            f.setInteger("vendor.qti-ext-dec-low-latency.enable", 1);
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            codec.configure(f, surface, null, 0);
            codec.start();
            Log.i(TAG, "decoder started: " + codec.getName());
            final MediaCodec c = codec;
            outThread = new Thread(() -> outLoop(c), "video-out");
            outThread.setPriority(Thread.MAX_PRIORITY);
            outThread.start();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "decoder start failed", e);
            releaseCodec();
            return false;
        }
    }

    private void outLoop(MediaCodec c) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (running && c == codec) {
            int idx;
            try {
                idx = c.dequeueOutputBuffer(info, 20000);
            } catch (Exception e) {
                break;
            }
            if (idx >= 0) {
                float ms = (System.nanoTime() / 1000 - info.presentationTimeUs) / 1000f;
                decodeMs = decodeMs * 0.9f + ms * 0.1f;
                try {
                    c.releaseOutputBuffer(idx, true);
                } catch (Exception e) {
                    break;
                }
                lastFrameMs = System.currentTimeMillis();
                frameCount++;
                long now = System.currentTimeMillis();
                if (now - statStart >= 1000) {
                    fps = (int) (frameCount * 1000 / (now - statStart));
                    mbps = byteCount * 8f / 1000f / (now - statStart);
                    frameCount = 0;
                    byteCount = 0;
                    statStart = now;
                }
            } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat of = c.getOutputFormat();
                width = of.getInteger(MediaFormat.KEY_WIDTH);
                height = of.getInteger(MediaFormat.KEY_HEIGHT);
                Log.i(TAG, "video " + width + "x" + height);
            }
        }
    }

    private synchronized void releaseCodec() {
        MediaCodec c = codec;
        codec = null;
        if (c != null) {
            try {
                c.stop();
            } catch (Exception ignored) {
            }
            c.release();
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }
}
