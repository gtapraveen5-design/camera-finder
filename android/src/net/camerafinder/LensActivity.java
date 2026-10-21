package net.camerafinder;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Lens finder: rear camera + flashlight. A camera lens is a tiny curved mirror,
 * so the flashlight bounces straight back from it as a small, steady bright dot.
 * The red filter darkens everything else so those dots pop, and the app circles
 * small bright points that stay put across several frames.
 *
 * IR mode: no flashlight, no filter. Many phone cameras can see infrared, so the
 * IR LEDs of night-vision cameras show up as glowing purple/white dots in the dark.
 */
public class LensActivity extends Activity implements TextureView.SurfaceTextureListener {

    static final String EXTRA_MODE = "mode";   // "lens" or "ir"
    private static final int REQ_CAMERA = 2;
    private static final int BRIGHT = 235;      // seed threshold (0-255, brightest channel)
    private static final int GROW = 200;        // blob growth threshold

    private FrameLayout root;
    private TextureView texture;
    private Overlay overlay;
    private TextView status;
    private Button torchBtn, filterBtn, flipBtn;

    private HandlerThread camThread;
    private Handler camHandler;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private volatile CameraDevice device;
    private volatile CameraCaptureSession session;
    private CaptureRequest.Builder request;
    private Size previewSize;
    private boolean opening, hasFlash, continuousAf;

    private boolean irMode, useFront, torch, filter;
    private final List<float[]> tracks = new ArrayList<>();   // {x, y, hits, misses}

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        irMode = "ir".equals(getIntent().getStringExtra(EXTRA_MODE));
        torch = !irMode;
        filter = !irMode;
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        camThread = new HandlerThread("lens-camera");
        camThread.start();
        camHandler = new Handler(camThread.getLooper());
        if (texture.isAvailable()) openCameraChecked();
        ui.postDelayed(detect, 500);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(detect);
        closeCamera();
        if (camThread != null) { camThread.quitSafely(); camThread = null; camHandler = null; }
        super.onPause();
    }

    @Override
    public void onBackPressed() { finish(); }

    // ------------------------------------------------------------------ UI

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        texture = new TextureView(this);
        texture.setSurfaceTextureListener(this);
        root.addView(texture, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        overlay = new Overlay(this);
        root.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setPadding(dp(10), dp(28), dp(10), dp(8));
        top.setBackgroundColor(0x99000000);
        torchBtn = button("", v -> { torch = !torch; applyRequestAsync(); refreshButtons(); });
        filterBtn = button("", v -> { filter = !filter; applyFilter(); refreshButtons(); });
        flipBtn = button("Flip camera", v -> flip());
        Button close = button("Close", v -> finish());
        top.addView(torchBtn, weight());
        top.addView(filterBtn, weight());
        top.addView(flipBtn, weight());
        top.addView(close, weight());
        root.addView(top, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(16), dp(12), dp(16), dp(22));
        bottom.setBackgroundColor(0xB3000000);
        status = text(16, true);
        status.setText("Starting camera…");
        TextView hint = text(13, false);
        hint.setText(irMode
                ? "Infrared check: turn the lights off and slowly scan the room. Night-vision cameras "
                  + "show as small glowing purple/white dots. Test first: point a TV remote at the phone "
                  + "and press a button - if you see its LED flash on screen, this camera sees IR. "
                  + "If not, tap Flip camera (selfie cameras often see IR better)."
                : "Turn the lights off. Hold the phone at eye level and slowly sweep the room, "
                  + "including smoke detectors, clocks, chargers, vents and shelves. A hidden lens "
                  + "shows as a tiny bright dot that stays put as you move. Circled dots are possible "
                  + "lens reflections - walk closer and look at the object.");
        bottom.addView(status);
        bottom.addView(hint);
        root.addView(bottom, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        setContentView(root);
        applyFilter();
        refreshButtons();
    }

    private void refreshButtons() {
        torchBtn.setText(torch ? "Light: on" : "Light: off");
        torchBtn.setEnabled(hasFlash || device == null);
        torchBtn.setAlpha(hasFlash || device == null ? 1f : 0.4f);
        filterBtn.setText(filter ? "Filter: on" : "Filter: off");
    }

    /** Red, high-contrast view: mid-tones go dark, small bright reflections stay bright. */
    private void applyFilter() {
        if (!filter) { texture.setLayerPaint(null); return; }
        float a = 1.7f, off = -95f;
        ColorMatrix cm = new ColorMatrix(new float[]{
                0.299f * a, 0.587f * a, 0.114f * a, 0, off,
                0.299f * 0.35f, 0.587f * 0.35f, 0.114f * 0.35f, 0, -30,
                0.299f * 0.35f, 0.587f * 0.35f, 0.114f * 0.35f, 0, -30,
                0, 0, 0, 1, 0});
        Paint p = new Paint();
        p.setColorFilter(new ColorMatrixColorFilter(cm));
        texture.setLayerPaint(p);
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x33FFFFFF);
        bg.setCornerRadius(dp(10));
        b.setBackground(bg);
        b.setPadding(dp(4), dp(8), dp(4), dp(8));
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(3), 0, dp(3), 0);
        return lp;
    }

    private TextView text(int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setTextColor(bold ? Color.WHITE : 0xFFB8C2D0);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setPadding(0, 0, 0, dp(6));
        return t;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void setStatus(final String s) { ui.post(() -> status.setText(s)); }

    // ------------------------------------------------------------------ camera

    private void openCameraChecked() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }
        openCamera();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code != REQ_CAMERA) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            if (texture.isAvailable() && camHandler != null) openCamera();
        } else {
            setStatus("Camera permission is needed for the lens finder. Nothing is recorded or saved.");
        }
    }

    private void openCamera() {
        if (device != null || opening || camHandler == null) return;
        CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            String id = null;
            CameraCharacteristics chosen = null;
            int want = useFront ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK;
            for (String cid : cm.getCameraIdList()) {
                CameraCharacteristics cc = cm.getCameraCharacteristics(cid);
                Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == want) { id = cid; chosen = cc; break; }
            }
            if (id == null) { setStatus("No " + (useFront ? "front" : "rear") + " camera found."); return; }

            Boolean flash = chosen.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            hasFlash = flash != null && flash;
            continuousAf = false;
            int[] af = chosen.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            if (af != null) for (int m : af) if (m == CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_PICTURE) continuousAf = true;

            StreamConfigurationMap map = chosen.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            previewSize = choosePreview(map == null ? null : map.getOutputSizes(SurfaceTexture.class));
            root.post(this::fitTexture);
            refreshButtons();

            opening = true;
            cm.openCamera(id, stateCallback, camHandler);
        } catch (CameraAccessException | SecurityException | IllegalArgumentException e) {
            opening = false;
            setStatus("Could not open the camera: " + e.getMessage());
        }
    }

    private static Size choosePreview(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return new Size(1280, 720);
        Size best = null;
        for (Size s : sizes) {
            if (s.getWidth() > 1920 || s.getHeight() > 1080) continue;
            if (best == null || (long) s.getWidth() * s.getHeight() > (long) best.getWidth() * best.getHeight()) best = s;
        }
        return best != null ? best : sizes[sizes.length - 1];
    }

    /** Center-crop the (landscape) camera stream into the portrait screen without stretching. */
    private void fitTexture() {
        int rw = root.getWidth(), rh = root.getHeight();
        if (rw == 0 || rh == 0 || previewSize == null) { root.post(this::fitTexture); return; }
        float pw = previewSize.getHeight(), ph = previewSize.getWidth();   // rotated to portrait
        float scale = Math.max(rw / pw, rh / ph);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                Math.round(pw * scale), Math.round(ph * scale), Gravity.CENTER);
        texture.setLayoutParams(lp);
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override public void onOpened(CameraDevice d) { device = d; opening = false; startPreview(); }
        @Override public void onDisconnected(CameraDevice d) { d.close(); device = null; opening = false; }
        @Override public void onError(CameraDevice d, int error) {
            d.close(); device = null; opening = false;
            setStatus("Camera error (" + error + "). Close other camera apps and try again.");
        }
    };

    private void startPreview() {
        try {
            SurfaceTexture st = texture.getSurfaceTexture();
            CameraDevice d = device;
            if (st == null || d == null) return;
            st.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface surface = new Surface(st);
            request = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            request.addTarget(surface);
            d.createCaptureSession(Collections.singletonList(surface), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) {
                    if (device == null) return;
                    session = s;
                    applyRequest();
                    ui.post(LensActivity.this::refreshButtons);
                    setStatus(irMode ? "Looking for infrared lights…" : "Sweeping for lens reflections…");
                }
                @Override public void onConfigureFailed(CameraCaptureSession s) {
                    setStatus("Could not start the camera preview.");
                }
            }, camHandler);
        } catch (CameraAccessException | IllegalStateException e) {
            setStatus("Could not start the camera preview: " + e.getMessage());
        }
    }

    private void applyRequestAsync() {
        Handler h = camHandler;
        if (h != null) h.post(this::applyRequest);
    }

    private void applyRequest() {
        CameraCaptureSession s = session;
        CaptureRequest.Builder r = request;
        if (s == null || r == null) return;
        try {
            r.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            if (continuousAf) r.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            r.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            r.set(CaptureRequest.FLASH_MODE, (torch && hasFlash)
                    ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
            s.setRepeatingRequest(r.build(), null, camHandler);
        } catch (CameraAccessException | IllegalStateException ignore) {
        }
    }

    private void flip() {
        closeCamera();
        useFront = !useFront;
        tracks.clear();
        overlay.setMarks(Collections.<float[]>emptyList());
        setStatus("Switching camera…");
        if (texture.isAvailable()) openCameraChecked();
    }

    private void closeCamera() {
        try { if (session != null) session.close(); } catch (Exception ignore) {}
        session = null;
        try { if (device != null) device.close(); } catch (Exception ignore) {}
        device = null;
        opening = false;
    }

    // ------------------------------------------------------------------ glint detection

    private final Runnable detect = new Runnable() {
        @Override public void run() {
            try { detectOnce(); } catch (Exception ignore) {}
            ui.postDelayed(this, 250);
        }
    };

    private void detectOnce() {
        if (device == null || session == null || !texture.isAvailable()) return;
        int vw = texture.getWidth(), vh = texture.getHeight();
        if (vw == 0 || vh == 0) return;
        int bw = 200, bh = Math.max(1, Math.round(vh * (bw / (float) vw)));
        Bitmap bmp = texture.getBitmap(bw, bh);
        if (bmp == null) return;
        int[] px = new int[bw * bh];
        bmp.getPixels(px, 0, bw, 0, 0, bw, bh);
        bmp.recycle();

        List<float[]> spots = findGlints(px, bw, bh);
        if (spots == null) {
            tracks.clear();
            overlay.setMarks(Collections.<float[]>emptyList());
            status.setText("Too bright here - turn the lights off or point away from lamps/windows.");
            return;
        }

        // Track spots across frames: real lens glints are steady, random sparkles flicker.
        boolean[] matched = new boolean[tracks.size()];
        for (float[] s : spots) {
            int best = -1; float bestD = 7f;
            for (int i = 0; i < matched.length; i++) {   // only tracks from earlier frames
                float[] t = tracks.get(i);
                float d = (float) Math.hypot(t[0] - s[0], t[1] - s[1]);
                if (d < bestD && !matched[i]) { bestD = d; best = i; }
            }
            if (best >= 0) {
                float[] t = tracks.get(best);
                t[0] = s[0]; t[1] = s[1]; t[2] += 1; t[3] = 0; matched[best] = true;
                if (t[2] == 5) buzz();
            } else {
                tracks.add(new float[]{s[0], s[1], 1, 0});
            }
        }
        for (int i = tracks.size() - 1; i >= 0; i--) {
            if (i < matched.length && !matched[i]) {
                float[] t = tracks.get(i);
                if (++t[3] > 2) tracks.remove(i);
            }
        }

        float sx = vw / (float) bw, sy = vh / (float) bh;
        float ox = texture.getLeft(), oy = texture.getTop();
        List<float[]> marks = new ArrayList<>();
        int steady = 0;
        for (float[] t : tracks) {
            if (t[2] < 2) continue;
            boolean strong = t[2] >= 5;
            if (strong) steady++;
            marks.add(new float[]{ox + t[0] * sx, oy + t[1] * sy, strong ? 1 : 0});
        }
        overlay.setMarks(marks);
        if (marks.isEmpty()) {
            status.setText(irMode ? "No infrared lights seen yet - keep scanning slowly."
                                  : "No lens reflections yet - keep sweeping slowly.");
        } else {
            status.setText(steady > 0
                    ? steady + " steady bright point" + (steady == 1 ? "" : "s") + " - go closer and inspect the circled spot"
                    : marks.size() + " possible point" + (marks.size() == 1 ? "" : "s") + " - hold still to confirm");
        }
    }

    /** @return centroids {x, y, size} of small isolated bright blobs, or null if the scene is too bright. */
    private static List<float[]> findGlints(int[] px, int w, int h) {
        int n = w * h;
        int[] lum = new int[n];
        int bright = 0;
        for (int i = 0; i < n; i++) {
            int c = px[i];
            int v = Math.max((c >> 16) & 255, Math.max((c >> 8) & 255, c & 255));
            lum[i] = v;
            if (v >= BRIGHT) bright++;
        }
        if (bright > n * 0.02f) return null;

        boolean[] seen = new boolean[n];
        int[] stack = new int[n];
        int maxSize = Math.max(4, n / 600);
        List<float[]> out = new ArrayList<>();
        for (int i = 0; i < n && out.size() < 12; i++) {
            if (seen[i] || lum[i] < BRIGHT) continue;
            int sp = 0, size = 0;
            long sumX = 0, sumY = 0;
            int minX = w, maxX = 0, minY = h, maxY = 0;
            stack[sp++] = i; seen[i] = true;
            while (sp > 0) {
                int p = stack[--sp];
                int x = p % w, y = p / w;
                size++; sumX += x; sumY += y;
                if (x < minX) minX = x; if (x > maxX) maxX = x;
                if (y < minY) minY = y; if (y > maxY) maxY = y;
                if (size > maxSize * 4) continue;  // big light source: stop growing
                int[] nb = {p - 1, p + 1, p - w, p + w};
                for (int k = 0; k < 4; k++) {
                    int q = nb[k];
                    if (q < 0 || q >= n || seen[q]) continue;
                    if ((k == 0 && x == 0) || (k == 1 && x == w - 1)) continue;
                    if (lum[q] >= GROW) { seen[q] = true; stack[sp++] = q; }
                }
            }
            if (size > maxSize) continue;
            // Must be isolated: the ring around it should be clearly darker.
            int pad = 3, ringSum = 0, ringCount = 0;
            int x0 = Math.max(0, minX - pad), x1 = Math.min(w - 1, maxX + pad);
            int y0 = Math.max(0, minY - pad), y1 = Math.min(h - 1, maxY + pad);
            for (int x = x0; x <= x1; x++) { ringSum += lum[y0 * w + x] + lum[y1 * w + x]; ringCount += 2; }
            for (int y = y0 + 1; y < y1; y++) { ringSum += lum[y * w + x0] + lum[y * w + x1]; ringCount += 2; }
            if (ringCount == 0 || ringSum / ringCount > 150) continue;
            out.add(new float[]{sumX / (float) size, sumY / (float) size, size});
        }
        return out;
    }

    private void buzz() {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) v.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Exception ignore) {}
    }

    // ------------------------------------------------------------------ TextureView

    @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        if (camHandler != null) openCameraChecked();
    }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {}
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { return true; }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}

    // ------------------------------------------------------------------ overlay

    private static final class Overlay extends View {
        private List<float[]> marks = new ArrayList<>();
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint cross = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;

        Overlay(Context c) {
            super(c);
            density = c.getResources().getDisplayMetrics().density;
            ring.setStyle(Paint.Style.STROKE);
            cross.setColor(0x66FFFFFF);
            cross.setStrokeWidth(1.5f * density);
        }

        void setMarks(List<float[]> m) { marks = m; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = 14 * density;
            c.drawLine(cx - r, cy, cx + r, cy, cross);
            c.drawLine(cx, cy - r, cx, cy + r, cross);
            for (float[] m : marks) {
                boolean strong = m[2] > 0;
                ring.setColor(strong ? 0xFFFF3B3B : 0xFFFFC23B);
                ring.setStrokeWidth((strong ? 3.5f : 2f) * density);
                c.drawCircle(m[0], m[1], (strong ? 22 : 16) * density, ring);
            }
        }
    }
}
