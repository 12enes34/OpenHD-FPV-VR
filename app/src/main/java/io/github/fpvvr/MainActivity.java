package io.github.fpvvr;

import android.app.Activity;
import android.graphics.Color;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Locale;

/**
 * FPV VR: OpenHD video + FC OSD on a phone in VR goggles (Gear VR / Cardboard style).
 * Tap the screen to open / close the settings panel.
 */
public class MainActivity extends Activity implements VrRenderer.SurfaceListener {
    private Settings settings;
    private Telemetry telemetry;
    private VideoReceiver video;
    private VrRenderer renderer;
    private GLSurfaceView glView;
    private View panel;
    private FrameLayout root;
    private TextView info;
    private float pxPerMm;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        settings = new Settings(this);
        // testing aid: adb shell am start -n io.github.fpvvr/.MainActivity --es orientation reverse|normal
        String forced = getIntent().getStringExtra("orientation");
        if ("reverse".equals(forced)) setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE);
        else if ("normal".equals(forced)) setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        updateRotation();
        telemetry = new Telemetry(settings.telemetryPort);
        telemetry.start();

        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        pxPerMm = (dm.xdpi + dm.ydpi) / 2f / 25.4f;

        renderer = new VrRenderer(this, settings, telemetry, pxPerMm, this);
        glView = new GLSurfaceView(this);
        glView.setEGLContextClientVersion(2);
        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        glView.setOnClickListener(v -> togglePanel());
        glView.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> updateScreenCentre());

        root = new FrameLayout(this);
        root.addView(glView);
        panel = buildPanel();
        panel.setVisibility(View.GONE);
        root.addView(panel, new FrameLayout.LayoutParams(dp(420), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END));
        setContentView(root);
    }

    /** Called on the GL thread whenever a (new) video surface exists. */
    @Override
    public void onVideoSurface(Surface s) {
        runOnUiThread(() -> {
            if (video != null) video.stop();
            video = new VideoReceiver(s, settings.videoPort);
            renderer.video = video;
            video.start();
        });
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        updateRotation();
        glView.post(this::updateScreenCentre); // 90 <-> 270 moves the view without resizing it
    }

    /**
     * Tells the renderer where the middle of the physical screen is inside the GL view (the view can be
     * narrower than the screen, e.g. beside a camera cutout), and converts offsets saved by version 0.1,
     * which were measured from the middle of the view, once per rotation.
     */
    @SuppressWarnings("deprecation")
    private void updateScreenCentre() {
        if (glView.getWidth() == 0) return;
        int[] loc = new int[2];
        glView.getLocationOnScreen(loc);
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        float cx = dm.widthPixels / 2f - loc[0];
        float cyFromTop = dm.heightPixels / 2f - loc[1];
        renderer.setScreenCentre(cx, glView.getHeight() - cyFromTop);
        updateRotation();
        boolean legacy = settings.reversed ? settings.legacyCentreRev : settings.legacyCentre;
        if (legacy) {
            float dxMm = (cx - glView.getWidth() / 2f) / pxPerMm;
            settings.setXOffset(settings.xOffset() - dxMm);
            if (settings.reversed) settings.legacyCentreRev = false;
            else settings.legacyCentre = false;
            settings.save();
        }
    }

    /** The phone can sit in the goggles either way round; each way keeps its own horizontal offset. */
    @SuppressWarnings("deprecation")
    private void updateRotation() {
        settings.reversed = getWindowManager().getDefaultDisplay().getRotation() == Surface.ROTATION_270;
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateRotation();
        immersive();
        glView.onResume();
        glView.post(this::updateScreenCentre);
    }

    @Override
    protected void onPause() {
        glView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (video != null) video.stop();
        telemetry.stop();
        renderer.release();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) immersive();
    }

    private void immersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    // ------------------------------------------------------------------ settings panel

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void togglePanel() {
        boolean show = panel.getVisibility() != View.VISIBLE;
        if (show) { // rebuilt so the sliders show the values for the current rotation
            ViewGroup.LayoutParams lp = panel.getLayoutParams();
            root.removeView(panel);
            panel = buildPanel();
            root.addView(panel, lp);
        }
        panel.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) info.setText(networkInfo());
        else settings.save();
    }

    private interface FloatSetter {
        void set(float v);
    }

    private void addSlider(LinearLayout parent, String name, float min, float max, float value, String fmt,
                           FloatSetter setter) {
        TextView label = new TextView(this);
        label.setTextColor(Color.WHITE);
        SeekBar bar = new SeekBar(this);
        bar.setMax(1000);
        bar.setProgress(Math.round((value - min) / (max - min) * 1000));
        label.setText(name + ": " + String.format(Locale.US, fmt, value));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                float v = min + (max - min) * p / 1000f;
                setter.set(v);
                label.setText(name + ": " + String.format(Locale.US, fmt, v));
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                settings.save();
            }
        });
        parent.addView(label);
        parent.addView(bar);
    }

    private void addSwitch(LinearLayout parent, String name, boolean value, CompoundButton.OnCheckedChangeListener l) {
        Switch sw = new Switch(this);
        sw.setText(name);
        sw.setTextColor(Color.WHITE);
        sw.setChecked(value);
        sw.setOnCheckedChangeListener(l);
        parent.addView(sw);
    }

    private void addButton(LinearLayout parent, String name, Runnable action) {
        Button b = new Button(this);
        b.setText(name);
        b.setOnClickListener(v -> action.run());
        parent.addView(b);
    }

    private void applyPreset(Settings.Preset p) {
        settings.apply(p);
        settings.save();
        togglePanel(); // close and reopen so the sliders show the new values
        togglePanel();
    }

    private View buildPanel() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(12), dp(16), dp(12));

        TextView title = new TextView(this);
        title.setText(R.string.panel_title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        col.addView(title);

        // OpenHD ground only finds the phone while "USB tethering" is on (needed every time the cable is plugged in)
        Button tether = new Button(this);
        tether.setText(R.string.usb_tethering);
        tether.setOnClickListener(v -> {
            try {
                startActivity(new android.content.Intent("android.settings.TETHER_SETTINGS"));
            } catch (Exception e) {
                startActivity(new android.content.Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS));
            }
        });
        col.addView(tether);

        addSwitch(col, getString(R.string.vr_mode), settings.vr, (b, v) -> settings.vr = v);
        addSwitch(col, getString(R.string.grid), settings.grid, (b, v) -> settings.grid = v);
        addSwitch(col, getString(R.string.osd), settings.osd, (b, v) -> settings.osd = v);
        addSwitch(col, getString(R.string.stats), settings.stats, (b, v) -> settings.stats = v);
        addButton(col, getString(R.string.preset_gear), () -> applyPreset(Settings.Preset.GEAR_VR));
        addButton(col, getString(R.string.preset_cardboard), () -> applyPreset(Settings.Preset.CARDBOARD));
        addSlider(col, getString(R.string.size), 0.4f, 1.3f, settings.size, "%.2f", v -> settings.size = v);
        addSlider(col, getString(R.string.ipd), 35f, 75f, settings.ipd(), "%.1f", settings::setIpd);
        addSlider(col, getString(R.string.x_offset), -15f, 15f, settings.xOffset(), "%.1f", settings::setXOffset);
        addSlider(col, getString(R.string.y_offset), -12f, 12f, settings.yOffsetMm, "%.1f", v -> settings.yOffsetMm = v);
        addSlider(col, getString(R.string.k1), 0f, 0.8f, settings.k1, "%.3f", v -> settings.k1 = v);
        addSlider(col, getString(R.string.k2), 0f, 0.8f, settings.k2, "%.3f", v -> settings.k2 = v);
        addSlider(col, getString(R.string.osd_area), 0.5f, 1.0f, settings.osdScale, "%.2f", v -> settings.osdScale = v);
        addSlider(col, getString(R.string.chroma), 0f, 0.05f, settings.chroma, "%.3f", v -> settings.chroma = v);
        addButton(col, getString(R.string.reset), () -> {
            getSharedPreferences("fpvvr", MODE_PRIVATE).edit().clear().apply();
            recreate();
        });

        info = new TextView(this);
        info.setTextColor(Color.LTGRAY);
        info.setPadding(0, dp(8), 0, 0);
        col.addView(info);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.argb(220, 20, 20, 24));
        sv.addView(col);
        return sv;
    }

    private String networkInfo() {
        StringBuilder sb = new StringBuilder(getString(R.string.net_info, settings.videoPort, settings.telemetryPort))
                .append('\n');
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        sb.append(ni.getName()).append(": ").append(a.getHostAddress()).append('\n');
                    }
                }
            }
        } catch (Exception ignored) {
        }
        sb.append(getString(R.string.net_hint));
        return sb.toString();
    }
}
