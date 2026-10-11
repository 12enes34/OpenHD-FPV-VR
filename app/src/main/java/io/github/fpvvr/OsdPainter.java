package io.github.fpvvr;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Typeface;

import java.util.List;

/**
 * Draws the OSD into a transparent 16:9 bitmap that the renderer lays over the video
 * (so it gets the same lens correction and sits inside the picture, like DJI goggles).
 */
public final class OsdPainter {
    public static final int W = 1280, H = 720;

    private final Bitmap bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
    private final Canvas canvas = new Canvas(bitmap);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int maxCol, maxRow;

    private final android.content.res.Resources res;

    public OsdPainter(android.content.Context ctx) {
        res = ctx.getResources();
        Typeface tf = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);
        fill.setTypeface(tf);
        fill.setColor(Color.WHITE);
        stroke.setTypeface(tf);
        stroke.setColor(Color.argb(230, 0, 0, 0));
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeCap(Paint.Cap.ROUND);
    }

    public Bitmap bitmap() {
        return bitmap;
    }

    /** Betaflight OSD font glyphs -> printable characters (same mapping as the ground overlay). */
    private static char glyph(int b) {
        if (b >= 0x90 && b <= 0x97) return '▮'; // battery
        switch (b) {
            case 0x06: return 'V';
            case 0x9A: return 'A';
            case 0x07: return 'h';
            case 0x0C: return 'm';
            case 0x0F: return 'f';
            case 0x7E: return '~';
            case 0x2D: return '-';
            case 0x9B:
            case 0x9C: return '◷'; // timer
            case 0x01: return '↑';
            case 0x03: return '↓';
            default: return (b >= 32 && b < 127) ? (char) b : ' ';
        }
    }

    private static String modeName(int autopilot, int mode) {
        if (autopilot == 3 || autopilot == 0) { // ArduPilot-style copter modes (fc_osd_bridge uses these)
            switch (mode) {
                case 0: return "STAB";
                case 1: return "ACRO";
                case 2: return "ALT HOLD";
                case 3: return "AUTO";
                case 4: return "GUIDED";
                case 5: return "LOITER";
                case 6: return "RTL";
                case 9: return "LAND";
                case 13: return "SPORT";
                case 14: return "FLIP";
                case 16: return "POSHOLD";
                default: return "MODE " + mode;
            }
        }
        return "MODE " + mode;
    }

    private void text(String s, float x, float y, float size, Paint.Align align, int color) {
        fill.setTextSize(size);
        stroke.setTextSize(size);
        stroke.setStrokeWidth(Math.max(3f, size / 7f));
        fill.setTextAlign(align);
        stroke.setTextAlign(align);
        fill.setColor(color);
        canvas.drawText(s, x, y, stroke);
        canvas.drawText(s, x, y, fill);
    }

    /** Repaints the whole OSD. */
    public void paint(Telemetry t, VideoReceiver v, Settings s, long now) {
        canvas.drawColor(0, PorterDuff.Mode.CLEAR);
        boolean videoOk = v != null && now - v.lastFrameMs < 1500;
        if (s.osd) {
            List<Telemetry.Cell> cells = t.osdCells;
            if (now - t.lastOsdMs < 3000 && !cells.isEmpty()) {
                paintFcOsd(cells);
            } else if (now - t.lastFcMs < 3000) {
                paintBasicHud(t, now);
            }
            if (now - t.statusTextMs < 5000 && !t.statusText.isEmpty()) {
                text(t.statusText, W / 2f, H * 0.80f, 34, Paint.Align.CENTER, Color.YELLOW);
            }
        }
        if (!videoOk && !s.grid) {
            text(res.getString(R.string.no_video), W / 2f, H * 0.45f, 64, Paint.Align.CENTER, Color.rgb(255, 80, 80));
            if (usbTetherUp(now)) {
                text(res.getString(R.string.tether_on, s.videoPort), W / 2f, H * 0.53f, 28,
                        Paint.Align.CENTER, Color.LTGRAY);
            } else {
                text(res.getString(R.string.tether_off), W / 2f, H * 0.53f, 28,
                        Paint.Align.CENTER, Color.YELLOW);
            }
        }
        if (now - t.lastMsgMs > 3000) {
            text(res.getString(R.string.no_telemetry, s.telemetryPort), W / 2f, H * 0.62f, 26, Paint.Align.CENTER,
                    Color.LTGRAY);
        }
        if (s.stats && v != null) {
            String st = String.format(java.util.Locale.US, "%dx%d  %d fps  %.1f Mbit/s  dec %.0f ms  lost %d",
                    v.width, v.height, v.fps, v.mbps, v.decodeMs, v.lostFrames);
            text(st, W / 2f, H - 12, 22, Paint.Align.CENTER, Color.GREEN);
        }
    }

    private long tetherCheckMs;
    private boolean tetherUp;

    /** True while the phone shares its connection over USB (interface rndis0 / ncm0 / usb0 is up). */
    public boolean usbTetherUp(long now) {
        if (now - tetherCheckMs > 2000) {
            tetherCheckMs = now;
            tetherUp = false;
            try {
                for (java.net.NetworkInterface ni : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                    String n = ni.getName();
                    if (ni.isUp() && (n.startsWith("rndis") || n.startsWith("ncm") || n.startsWith("usb"))) {
                        tetherUp = true;
                        break;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return tetherUp;
    }

    private void paintFcOsd(List<Telemetry.Cell> cells) {
        for (Telemetry.Cell c : cells) {
            maxCol = Math.max(maxCol, c.col + c.text.length);
            maxRow = Math.max(maxRow, c.row + 1);
        }
        boolean hd = maxCol > 30 || maxRow > 16;
        int cols = hd ? 53 : 30, rows = hd ? 20 : 16;
        float cw = (float) W / cols, ch = (float) H / rows;
        float size = Math.min(ch * 0.8f, cw * 1.55f);
        fill.setTextSize(size);
        Paint.FontMetrics fm = fill.getFontMetrics();
        float base = (ch - fm.ascent - fm.descent) / 2f;
        for (Telemetry.Cell c : cells) {
            for (int k = 0; k < c.text.length; k++) {
                char g = glyph(c.text[k] & 0xFF);
                if (g == ' ') continue;
                text(String.valueOf(g), (c.col + k + 0.5f) * cw, c.row * ch + base, size, Paint.Align.CENTER,
                        Color.WHITE);
            }
        }
    }

    /** For FCs without the OSD frames (e.g. ArduPilot): a few big items + a horizon line. */
    private void paintBasicHud(Telemetry t, long now) {
        if (t.voltage > 0) {
            text(String.format(java.util.Locale.US, "%.2fV", t.voltage), 40, H - 40, 40, Paint.Align.LEFT,
                    Color.WHITE);
        }
        text(modeName(t.autopilot, t.customMode), W - 40, H - 40, 40, Paint.Align.RIGHT, Color.WHITE);
        text(t.armed ? "ARMED" : "DISARMED", W / 2f, 60, 40, Paint.Align.CENTER,
                t.armed ? Color.rgb(255, 90, 90) : Color.WHITE);
        if (now - t.lastAttMs < 2000) {
            float cx = W / 2f, cy = H / 2f + t.pitch * 6f;
            double a = Math.toRadians(-t.roll);
            float dx = (float) Math.cos(a) * 220, dy = (float) Math.sin(a) * 220;
            line.setStrokeWidth(9);
            line.setColor(Color.argb(200, 0, 0, 0));
            canvas.drawLine(cx - dx, cy - dy, cx + dx, cy + dy, line);
            line.setStrokeWidth(4);
            line.setColor(Color.WHITE);
            canvas.drawLine(cx - dx, cy - dy, cx + dx, cy + dy, line);
            canvas.drawCircle(W / 2f, H / 2f, 6, line);
        }
    }
}
