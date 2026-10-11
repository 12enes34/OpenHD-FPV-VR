package io.github.fpvvr;

import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * One full-screen pass: for every screen pixel it finds the eye (left/right half), applies the
 * inverse lens distortion around that eye's lens centre, and samples the video + OSD textures
 * (with a small per-colour scale against the lenses' colour fringes). Flat mode just fits 16:9.
 */
public final class VrRenderer implements GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {
    private static final String TAG = "FpvVr.GL";

    public interface SurfaceListener {
        void onVideoSurface(Surface s);
    }

    private static final String VS =
            "attribute vec2 aPos;\n" +
            "void main(){ gl_Position = vec4(aPos, 0.0, 1.0); }\n";

    private static final String FS =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision highp float;\n" +
            "uniform samplerExternalOES uVideo;\n" +
            "uniform sampler2D uOsd;\n" +
            "uniform mat4 uTex;\n" +
            "uniform vec2 uScreen;\n" +      // screen size in px
            "uniform vec2 uLeftC, uRightC;\n" + // lens centres in px
            "uniform float uRadius;\n" +     // px per lens unit
            "uniform vec2 uK;\n" +           // k1, k2
            "uniform float uChroma;\n" +
            "uniform float uOsdScale;\n" +   // OSD drawn into the central part of the picture
            "uniform vec2 uHalf;\n" +        // virtual screen half size in lens units
            "uniform float uVr, uGrid, uHasVideo;\n" +
            "float gEye = 0.0;\n" +          // 0 = left eye, 1 = right eye (calibration colours)
            "vec2 toUv(vec2 q){ return q / uHalf * 0.5 + 0.5; }\n" +
            "bool inside(vec2 uv){ return uv.x >= 0.0 && uv.x <= 1.0 && uv.y >= 0.0 && uv.y <= 1.0; }\n" +
            "vec4 scene(vec2 uv){\n" +
            "  vec4 c;\n" +
            "  if (uGrid > 0.5) {\n" +
            "    vec2 g = abs(fract(uv * vec2(16.0, 9.0) + 0.5) - 0.5);\n" +
            "    float l = step(min(g.x, g.y), 0.04);\n" +
            "    float centre = step(length((uv - 0.5) * vec2(16.0, 9.0)), 0.25);\n" +
            "    vec3 dot = uVr > 0.5 ? mix(vec3(1.0, 0.15, 0.15), vec3(0.2, 0.5, 1.0), gEye) : vec3(1.0, 1.0, 0.2);\n" +
            "    c = vec4(mix(vec3(0.08), vec3(1.0, 1.0, 0.2), l), 1.0);\n" +
            "    c.rgb = mix(c.rgb, dot, centre);\n" +
            "  } else if (uHasVideo > 0.5) {\n" +
            "    c = texture2D(uVideo, (uTex * vec4(uv, 0.0, 1.0)).xy);\n" +
            "  } else {\n" +
            "    c = vec4(0.05, 0.05, 0.08, 1.0);\n" +
            "  }\n" +
            "  vec2 ou = (uv - 0.5) / uOsdScale + 0.5;\n" +
            "  vec4 o = inside(ou) ? texture2D(uOsd, vec2(ou.x, 1.0 - ou.y)) : vec4(0.0);\n" + // bitmap rows start at the top
            "  return vec4(c.rgb * (1.0 - o.a) + o.rgb, 1.0);\n" +      // bitmap is premultiplied
            "}\n" +
            "void main(){\n" +
            "  vec2 px = gl_FragCoord.xy;\n" +
            "  if (uVr < 0.5) {\n" +
            "    float w = min(uScreen.x, uScreen.y * 16.0 / 9.0);\n" +
            "    float h = w * 9.0 / 16.0;\n" +
            "    vec2 uv = (px - (uScreen - vec2(w, h)) * 0.5) / vec2(w, h);\n" +
            "    gl_FragColor = inside(uv) ? scene(uv) : vec4(0.0, 0.0, 0.0, 1.0);\n" +
            "    return;\n" +
            "  }\n" +
            "  gEye = px.x < (uLeftC.x + uRightC.x) * 0.5 ? 0.0 : 1.0;\n" +
            "  vec2 c = gEye < 0.5 ? uLeftC : uRightC;\n" +
            "  vec2 p = (px - c) / uRadius;\n" +
            "  float r2 = dot(p, p);\n" +
            "  vec2 q = p * (1.0 + uK.x * r2 + uK.y * r2 * r2);\n" +
            "  vec2 uvG = toUv(q);\n" +
            "  if (!inside(uvG)) { gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }\n" +
            "  if (uChroma <= 0.0) { gl_FragColor = scene(uvG); return; }\n" +
            "  vec2 uvR = toUv(q * (1.0 - uChroma));\n" +
            "  vec2 uvB = toUv(q * (1.0 + uChroma));\n" +
            "  gl_FragColor = vec4(scene(uvR).r, scene(uvG).g, scene(uvB).b, 1.0);\n" +
            "}\n";

    private final Settings settings;
    private final Telemetry telemetry;
    private final OsdPainter osd;
    private final SurfaceListener listener;
    private final float pxPerMm;
    public volatile VideoReceiver video;

    private SurfaceTexture surfaceTexture;
    private volatile boolean frameAvailable;
    private boolean hasVideo;
    private int program, videoTex, osdTex;
    private int width, height;
    private final float[] texMatrix = new float[16];
    private FloatBuffer quad;
    private int osdVersion = -1;
    private long osdPaintMs;
    private boolean osdFirst = true;
    // middle of the physical screen in this view's GL coordinates (-1 = unknown -> view middle)
    private volatile float screenCx = -1, screenCy = -1;

    public void setScreenCentre(float x, float yGl) {
        screenCx = x;
        screenCy = yGl;
    }

    public VrRenderer(android.content.Context ctx, Settings settings, Telemetry telemetry, float pxPerMm,
                      SurfaceListener listener) {
        this.osd = new OsdPainter(ctx);
        this.settings = settings;
        this.telemetry = telemetry;
        this.pxPerMm = pxPerMm;
        this.listener = listener;
    }

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        program = link(VS, FS);
        int[] t = new int[2];
        GLES20.glGenTextures(2, t, 0);
        videoTex = t[0];
        osdTex = t[1];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTex);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, osdTex);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        osdFirst = true;

        quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(new float[]{-1, -1, 1, -1, -1, 1, 1, 1}).position(0);

        surfaceTexture = new SurfaceTexture(videoTex);
        surfaceTexture.setOnFrameAvailableListener(this);
        listener.onVideoSurface(new Surface(surfaceTexture));
    }

    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        frameAvailable = true;
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int w, int h) {
        width = w;
        height = h;
        GLES20.glViewport(0, 0, w, h);
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        if (frameAvailable) {
            frameAvailable = false;
            surfaceTexture.updateTexImage();
            surfaceTexture.getTransformMatrix(texMatrix);
            hasVideo = true;
        }
        VideoReceiver v = video;
        long now = System.currentTimeMillis();
        if (v == null || now - v.lastFrameMs > 1500) hasVideo = false;

        // the OSD bitmap only changes with the telemetry (max ~15 Hz), plus 1 Hz for the stats / timeouts
        if (telemetry.version != osdVersion || now - osdPaintMs > 1000) {
            if (now - osdPaintMs > 60 || osdFirst) {
                osdVersion = telemetry.version;
                osdPaintMs = now;
                osd.paint(telemetry, v, settings, now);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, osdTex);
                if (osdFirst) {
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, osd.bitmap(), 0);
                    osdFirst = false;
                } else {
                    GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, osd.bitmap());
                }
            }
        }

        GLES20.glUseProgram(program);
        int aPos = GLES20.glGetAttribLocation(program, "aPos");
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTex);
        GLES20.glUniform1i(loc("uVideo"), 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, osdTex);
        GLES20.glUniform1i(loc("uOsd"), 1);
        GLES20.glUniformMatrix4fv(loc("uTex"), 1, false, texMatrix, 0);

        // lens geometry: centres ipd/2 left and right of the middle of the PHYSICAL screen (the view can be
        // smaller, e.g. next to a camera cutout), radius = half an eye's width
        float half = settings.ipd() * pxPerMm / 2f;
        float cx = (screenCx >= 0 ? screenCx : width / 2f) + settings.xOffset() * pxPerMm;
        float cy = (screenCy >= 0 ? screenCy : height / 2f) + settings.yOffsetMm * pxPerMm;
        float radius = width / 4f;
        GLES20.glUniform2f(loc("uScreen"), width, height);
        GLES20.glUniform2f(loc("uLeftC"), cx - half, cy);
        GLES20.glUniform2f(loc("uRightC"), cx + half, cy);
        GLES20.glUniform1f(loc("uRadius"), radius);
        GLES20.glUniform2f(loc("uK"), settings.k1, settings.k2);
        GLES20.glUniform1f(loc("uChroma"), settings.chroma);
        GLES20.glUniform1f(loc("uOsdScale"), settings.osdScale);
        GLES20.glUniform2f(loc("uHalf"), settings.size, settings.size * 9f / 16f);
        GLES20.glUniform1f(loc("uVr"), settings.vr ? 1f : 0f);
        GLES20.glUniform1f(loc("uGrid"), settings.grid ? 1f : 0f);
        GLES20.glUniform1f(loc("uHasVideo"), hasVideo ? 1f : 0f);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    private int loc(String name) {
        return GLES20.glGetUniformLocation(program, name);
    }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            Log.e(TAG, "shader: " + GLES20.glGetShaderInfoLog(s));
            throw new RuntimeException("shader compile failed: " + GLES20.glGetShaderInfoLog(s));
        }
        return s;
    }

    private static int link(String vs, String fs) {
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs));
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs));
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("link failed: " + GLES20.glGetProgramInfoLog(p));
        return p;
    }

    public void release() {
        if (surfaceTexture != null) surfaceTexture.release();
    }
}
