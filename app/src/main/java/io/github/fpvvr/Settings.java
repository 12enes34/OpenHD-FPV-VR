package io.github.fpvvr;

import android.content.Context;
import android.content.SharedPreferences;

/** User settings, kept in SharedPreferences. Read by the GL thread, written by the UI thread. */
public final class Settings {
    public volatile boolean vr = true;           // side-by-side with lens correction, else flat
    public volatile float size = 0.72f;          // virtual screen half width, in lens radius units
    public volatile float yOffsetMm = 0f;        // move both eye images up (+) / down (-)
    public volatile float k1 = 0.22f, k2 = 0.24f; // barrel pre-distortion
    public volatile float chroma = 0.015f;       // lateral chromatic aberration correction
    public volatile float osdScale = 0.75f;      // OSD squeezed into the centre (corners stay readable in VR)
    public volatile boolean grid = false;        // calibration grid instead of the video
    public volatile boolean osd = true;
    public volatile boolean stats = false;
    public volatile int videoPort = 5600, telemetryPort = 14550;

    // A phone can sit in the goggles either way round and need not seat identically both ways, so the
    // eye spacing and the horizontal offset are kept per rotation ("Rev" = rotation 270).
    public volatile float ipdMm = 62f, ipdMmRev = 62f;          // distance between the two eye images
    public volatile float xOffsetMm = 0f, xOffsetMmRev = 0f;    // move both eye images right (+) / left (-)
    public volatile boolean reversed;            // current screen rotation is 270 deg (not saved)

    // Versions before 0.2 measured the offsets from the middle of the app's view instead of the middle of
    // the physical screen. Such offsets are converted once per rotation, the first time it is laid out.
    public volatile boolean legacyCentre, legacyCentreRev;

    /** Ready-made lens settings. */
    public enum Preset {
        GEAR_VR(62f, 0.22f, 0.24f, 0.015f, 0.72f),
        CARDBOARD(64f, 0.34f, 0.55f, 0.010f, 0.68f);

        final float ipd, k1, k2, chroma, size;

        Preset(float ipd, float k1, float k2, float chroma, float size) {
            this.ipd = ipd;
            this.k1 = k1;
            this.k2 = k2;
            this.chroma = chroma;
            this.size = size;
        }
    }

    public void apply(Preset p) {
        vr = true;
        ipdMm = ipdMmRev = p.ipd;
        xOffsetMm = xOffsetMmRev = 0f;
        yOffsetMm = 0f;
        k1 = p.k1;
        k2 = p.k2;
        chroma = p.chroma;
        size = p.size;
        legacyCentre = legacyCentreRev = false;
    }

    public float ipd() {
        return reversed ? ipdMmRev : ipdMm;
    }

    public void setIpd(float v) {
        if (reversed) ipdMmRev = v;
        else ipdMm = v;
    }

    public float xOffset() {
        return reversed ? xOffsetMmRev : xOffsetMm;
    }

    public void setXOffset(float v) {
        if (reversed) xOffsetMmRev = v;
        else xOffsetMm = v;
    }

    private final SharedPreferences prefs;

    public Settings(Context ctx) {
        prefs = ctx.getSharedPreferences("fpvvr", Context.MODE_PRIVATE);
        boolean old = prefs.contains("ipdMm") && prefs.getInt("geometry", 1) < 2;
        legacyCentre = prefs.getBoolean("legacyCentre", old);
        legacyCentreRev = prefs.getBoolean("legacyCentreRev", old);
        vr = prefs.getBoolean("vr", vr);
        size = prefs.getFloat("size", size);
        ipdMm = prefs.getFloat("ipdMm", ipdMm);
        ipdMmRev = prefs.getFloat("ipdMmRev", old ? ipdMm : ipdMmRev);
        yOffsetMm = prefs.getFloat("yOffsetMm", yOffsetMm);
        xOffsetMm = prefs.getFloat("xOffsetMm", xOffsetMm);
        xOffsetMmRev = prefs.getFloat("xOffsetMmRev", old ? xOffsetMm : xOffsetMmRev);
        grid = prefs.getBoolean("grid", grid);
        k1 = prefs.getFloat("k1", k1);
        k2 = prefs.getFloat("k2", k2);
        chroma = prefs.getFloat("chroma", chroma);
        osdScale = prefs.getFloat("osdScale", osdScale);
        osd = prefs.getBoolean("osd", osd);
        stats = prefs.getBoolean("stats", stats);
        videoPort = prefs.getInt("videoPort", videoPort);
        telemetryPort = prefs.getInt("telemetryPort", telemetryPort);
    }

    public void save() {
        prefs.edit().putInt("geometry", 2).putBoolean("vr", vr).putFloat("size", size)
                .putFloat("ipdMm", ipdMm).putFloat("ipdMmRev", ipdMmRev).putFloat("yOffsetMm", yOffsetMm)
                .putFloat("xOffsetMm", xOffsetMm).putFloat("xOffsetMmRev", xOffsetMmRev)
                .putBoolean("legacyCentre", legacyCentre).putBoolean("legacyCentreRev", legacyCentreRev)
                .putBoolean("grid", grid).putFloat("k1", k1).putFloat("k2", k2).putFloat("chroma", chroma)
                .putFloat("osdScale", osdScale).putBoolean("osd", osd).putBoolean("stats", stats)
                .putInt("videoPort", videoPort).putInt("telemetryPort", telemetryPort).apply();
    }
}
