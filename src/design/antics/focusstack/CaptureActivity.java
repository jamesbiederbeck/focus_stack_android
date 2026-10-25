package design.antics.focusstack;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Size;
import android.util.SizeF;
import android.util.SparseIntArray;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Guided manual-focus bracket capture. A {@link FocusBarView} drives
 * LENS_FOCUS_DISTANCE directly while the user drags it (real-time
 * focus-by-eye on the live preview — no autofocus in the loop, which is
 * what made the old CONTROL_AF_TRIGGER-based "Focus Far"/"Focus Near"
 * buttons time out on some devices); "Set Far"/"Set Near" just record
 * wherever the bar currently is, and "Capture N" shoots a manual-focus
 * series between those two points. A lens selector cycles through every
 * camera id *and*, where the device exposes one (Pixel-style logical
 * multi-camera — see stereovision's StereoRig/CLAUDE.md for how this was
 * discovered), its physical sub-lenses via
 * {@link OutputConfiguration#setPhysicalCameraId}, since macro-capable
 * lenses are often hidden behind a single logical id. Framework Camera2
 * only, no CameraX/androidx.
 */
public class CaptureActivity extends Activity implements TextureView.SurfaceTextureListener {

    static final String EXTRA_RESULT_PATHS = "design.antics.focusstack.RESULT_PATHS";

    private static final int CAMERA_PERMISSION_REQUEST = 10;
    private static final int DEFAULT_SHOTS = 8;
    private static final int MIN_SHOTS = 3;
    private static final int MAX_SHOTS = 20;
    private static final long AUTOFOCUS_ASSIST_TIMEOUT_MS = 1200;
    // LENS_FOCUS_DISTANCE changes aren't guaranteed to take effect by the
    // very next capture on every HAL; there's no portable "lens settled"
    // signal cheap enough to poll per-step, so a fixed delay after each
    // focus change is the pragmatic choice here (confirmed on-device: a
    // Pixel 8 Pro settles well within this).
    private static final long FOCUS_SETTLE_MS = 180;
    // Manual-focus preview updates are pushed on every drag event; throttle
    // how often that actually hits the camera HAL so a fast drag doesn't
    // flood setRepeatingRequest().
    private static final long FOCUS_PREVIEW_THROTTLE_MS = 50;

    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    /** One selectable lens: either a whole logical camera id, or a physical sub-lens of one. */
    private static final class CameraChoice {
        final String logicalId;
        final String physicalId; // null unless this is a physical sub-lens
        final String label;
        final float minFocusDistance; // diopters
        final int sensorOrientation;
        final CameraCharacteristics characteristics; // the physical one if physicalId != null

        CameraChoice(String logicalId, String physicalId, String label,
                     float minFocusDistance, int sensorOrientation,
                     CameraCharacteristics characteristics) {
            this.logicalId = logicalId;
            this.physicalId = physicalId;
            this.label = label;
            this.minFocusDistance = minFocusDistance;
            this.sensorOrientation = sensorOrientation;
            this.characteristics = characteristics;
        }
    }

    private TextureView textureView;
    private FocusBarView focusBar;
    private TextView status, shotsLabel;
    private Button cameraButton, autofocusButton, farButton, nearButton, captureButton,
            minusButton, plusButton;

    private HandlerThread bgThread;
    private Handler bgHandler;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private CameraManager cameraManager;
    private List<CameraChoice> cameraChoices;
    private int selectedChoice;

    private CameraDevice cameraDevice;
    private CameraCaptureSession session;
    private Surface previewSurface;
    private Size previewSize, stillSize;
    private ImageReader stillReader;

    private Float farDistance, nearDistance;
    private int shots = DEFAULT_SHOTS;
    private boolean autofocusing;
    private long lastFocusPushMs;

    private boolean capturing;
    private float[] bracketSteps;
    private int bracketIndex;
    private List<String> capturedPaths;
    private File captureDir;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
        buildUi();

        if (!discoverCameras()) {
            return; // already reported why and finish()ed
        }
        applyChoiceToUi();

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
        }
    }

    // ---- UI -------------------------------------------------------------

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        textureView = new TextureView(this);
        textureView.setSurfaceTextureListener(this);
        root.addView(textureView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        cameraButton = new Button(this);
        cameraButton.setOnClickListener(v -> cycleCamera());
        root.addView(cameraButton);

        focusBar = new FocusBarView(this);
        focusBar.setListener(this::onFocusBarDragged);
        root.addView(focusBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        status = new TextView(this);
        status.setText("Drag the bar to focus, then set the far and near points.");
        status.setGravity(Gravity.CENTER);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        status.setPadding(pad, pad, pad, pad);
        root.addView(status);

        LinearLayout focusRow = new LinearLayout(this);
        focusRow.setOrientation(LinearLayout.HORIZONTAL);
        autofocusButton = new Button(this);
        autofocusButton.setText("Autofocus");
        autofocusButton.setOnClickListener(v -> autofocusAssist());
        farButton = new Button(this);
        farButton.setText("Set Far");
        farButton.setOnClickListener(v -> setBound(true));
        nearButton = new Button(this);
        nearButton.setText("Set Near");
        nearButton.setOnClickListener(v -> setBound(false));
        for (Button b : new Button[]{autofocusButton, farButton, nearButton}) {
            focusRow.addView(b, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        root.addView(focusRow);

        LinearLayout shotsRow = new LinearLayout(this);
        shotsRow.setOrientation(LinearLayout.HORIZONTAL);
        shotsRow.setGravity(Gravity.CENTER_VERTICAL);
        minusButton = new Button(this);
        minusButton.setText("-");
        minusButton.setOnClickListener(v -> setShots(shots - 1));
        shotsLabel = new TextView(this);
        shotsLabel.setGravity(Gravity.CENTER);
        shotsLabel.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        plusButton = new Button(this);
        plusButton.setText("+");
        plusButton.setOnClickListener(v -> setShots(shots + 1));
        shotsRow.addView(minusButton);
        shotsRow.addView(shotsLabel);
        shotsRow.addView(plusButton);
        root.addView(shotsRow);
        setShots(shots);

        captureButton = new Button(this);
        captureButton.setText("Capture Stack");
        captureButton.setEnabled(false);
        captureButton.setOnClickListener(v -> startBracketCapture());
        root.addView(captureButton);

        setContentView(root);
    }

    private void setShots(int n) {
        shots = Math.max(MIN_SHOTS, Math.min(MAX_SHOTS, n));
        shotsLabel.setText(shots + " shots");
        updateIntervalPreview();
    }

    private void setControlsEnabled(boolean enabled) {
        cameraButton.setEnabled(enabled && cameraChoices.size() > 1);
        autofocusButton.setEnabled(enabled);
        farButton.setEnabled(enabled);
        nearButton.setEnabled(enabled);
        minusButton.setEnabled(enabled);
        plusButton.setEnabled(enabled);
        captureButton.setEnabled(enabled && farDistance != null && nearDistance != null);
    }

    private void updateIntervalPreview() {
        if (farDistance == null || nearDistance == null) {
            focusBar.setIntervalSteps(null);
            return;
        }
        focusBar.setIntervalSteps(computeBracketSteps());
    }

    private float[] computeBracketSteps() {
        float lo = Math.min(farDistance, nearDistance);
        float hi = Math.max(farDistance, nearDistance);
        float[] steps = new float[shots];
        for (int i = 0; i < shots; i++) {
            steps[i] = shots == 1 ? (lo + hi) / 2f : lo + i * (hi - lo) / (shots - 1);
        }
        return steps;
    }

    // ---- camera + lens discovery (no permission needed for characteristics) --

    /** Returns false (and finishes the activity) if no usable lens exists. */
    private boolean discoverCameras() {
        cameraChoices = new ArrayList<>();
        try {
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                String facingLabel = facing != null && facing == CameraCharacteristics.LENS_FACING_BACK
                        ? "Back" : facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT
                        ? "Front" : "External";

                addChoiceIfUsable(id, null, facingLabel, c);

                Set<String> physIds = Build.VERSION.SDK_INT >= 28 ? c.getPhysicalCameraIds() : null;
                if (physIds != null && physIds.size() > 1) {
                    for (String pid : physIds) {
                        try {
                            CameraCharacteristics pc = cameraManager.getCameraCharacteristics(pid);
                            addChoiceIfUsable(id, pid, facingLabel + " lens " + pid, pc);
                        } catch (Exception ignored) {
                            // Hidden physical ids sometimes refuse a direct characteristics
                            // query even though they're valid as an output target.
                        }
                    }
                }
            }
        } catch (CameraAccessException e) {
            fail("Camera discovery failed: " + e.getMessage());
            return false;
        }

        if (cameraChoices.isEmpty()) {
            fail("No camera here supports manual focus — can't do a focus bracket");
            return false;
        }
        return true;
    }

    private void addChoiceIfUsable(String logicalId, String physicalId, String labelPrefix,
                                    CameraCharacteristics c) {
        Float minDist = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        if (minDist == null || minDist <= 0f) return; // fixed-focus lens
        Integer orient = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
        String label = String.format("%s (%.0f°, %.1fD)", labelPrefix, horizontalFovDeg(c), minDist);
        cameraChoices.add(new CameraChoice(logicalId, physicalId, label, minDist,
                orient == null ? 90 : orient, c));
    }

    private static float horizontalFovDeg(CameraCharacteristics c) {
        SizeF physical = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        float[] focals = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        if (physical == null || focals == null || focals.length == 0) return 60f;
        float focal = focals[0];
        for (float f : focals) if (f > 0f && f < focal) focal = f;
        if (focal <= 0f) return 60f;
        return (float) Math.toDegrees(2.0 * Math.atan(physical.getWidth() / (2.0 * focal)));
    }

    private void cycleCamera() {
        if (cameraChoices.size() < 2 || capturing) return;
        selectedChoice = (selectedChoice + 1) % cameraChoices.size();
        farDistance = null;
        nearDistance = null;
        focusBar.setFar(null);
        focusBar.setNear(null);
        updateIntervalPreview();
        closeCamera();
        applyChoiceToUi();
        maybeOpenCamera();
    }

    private void applyChoiceToUi() {
        CameraChoice choice = cameraChoices.get(selectedChoice);
        cameraButton.setText("Lens: " + choice.label);
        cameraButton.setEnabled(cameraChoices.size() > 1);
        focusBar.setRange(0f, choice.minFocusDistance);
        focusBar.setCurrent(0f);

        StreamConfigurationMap map = choice.characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        previewSize = sizeNear(map.getOutputSizes(SurfaceTexture.class), 1280 * 720);
        stillSize = largestJpegUnder(map.getOutputSizes(ImageFormat.JPEG), 12_000_000);
    }

    private static Size sizeNear(Size[] sizes, int targetArea) {
        Size best = sizes[0];
        long bestDiff = Long.MAX_VALUE;
        for (Size s : sizes) {
            long diff = Math.abs((long) s.getWidth() * s.getHeight() - targetArea);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = s;
            }
        }
        return best;
    }

    /** Largest JPEG size at or under maxArea, or the smallest available if all exceed it. */
    private static Size largestJpegUnder(Size[] sizes, int maxArea) {
        Size bestUnder = null;
        Size smallest = sizes[0];
        for (Size s : sizes) {
            long area = (long) s.getWidth() * s.getHeight();
            if (area < (long) smallest.getWidth() * smallest.getHeight()) smallest = s;
            if (area <= maxArea && (bestUnder == null
                    || area > (long) bestUnder.getWidth() * bestUnder.getHeight())) {
                bestUnder = s;
            }
        }
        return bestUnder != null ? bestUnder : smallest;
    }

    private void fail(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        setResult(RESULT_CANCELED);
        finish();
    }

    // ---- permission -------------------------------------------------------

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != CAMERA_PERMISSION_REQUEST) return;
        if (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED) {
            fail("Camera permission denied");
            return;
        }
        maybeOpenCamera();
    }

    // ---- lifecycle / camera open-close -------------------------------------

    @Override
    protected void onPause() {
        super.onPause();
        closeCamera();
        stopBackgroundThread();
    }

    @Override
    protected void onResume() {
        super.onResume();
        startBackgroundThread();
        if (textureView.isAvailable()) {
            maybeOpenCamera();
        }
    }

    private void startBackgroundThread() {
        bgThread = new HandlerThread("focus-stack-capture");
        bgThread.start();
        bgHandler = new Handler(bgThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (bgThread == null) return;
        bgThread.quitSafely();
        try {
            bgThread.join();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        bgThread = null;
        bgHandler = null;
    }

    private void maybeOpenCamera() {
        if (cameraDevice != null || cameraChoices == null || bgHandler == null) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        if (!textureView.isAvailable()) return;

        SurfaceTexture texture = textureView.getSurfaceTexture();
        texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
        previewSurface = new Surface(texture);

        stillReader = ImageReader.newInstance(
                stillSize.getWidth(), stillSize.getHeight(), ImageFormat.JPEG, 2);
        stillReader.setOnImageAvailableListener(this::onStillImageAvailable, bgHandler);

        String logicalId = cameraChoices.get(selectedChoice).logicalId;
        try {
            //noinspection MissingPermission - checked above
            cameraManager.openCamera(logicalId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice cam) {
                    cameraDevice = cam;
                    startPreviewSession();
                }

                @Override
                public void onDisconnected(CameraDevice cam) {
                    cam.close();
                    if (cameraDevice == cam) cameraDevice = null;
                }

                @Override
                public void onError(CameraDevice cam, int error) {
                    cam.close();
                    if (cameraDevice == cam) cameraDevice = null;
                    uiHandler.post(() -> fail("Camera error " + error));
                }
            }, bgHandler);
        } catch (CameraAccessException e) {
            fail("Open camera failed: " + e.getMessage());
        }
    }

    private void closeCamera() {
        if (session != null) { session.close(); session = null; }
        if (cameraDevice != null) { cameraDevice.close(); cameraDevice = null; }
        if (stillReader != null) { stillReader.close(); stillReader = null; }
        if (previewSurface != null) { previewSurface.release(); previewSurface = null; }
    }

    private void startPreviewSession() {
        CameraChoice choice = cameraChoices.get(selectedChoice);
        OutputConfiguration previewCfg = new OutputConfiguration(previewSurface);
        OutputConfiguration stillCfg = new OutputConfiguration(stillReader.getSurface());
        if (choice.physicalId != null) {
            previewCfg.setPhysicalCameraId(choice.physicalId);
            stillCfg.setPhysicalCameraId(choice.physicalId);
        }
        try {
            cameraDevice.createCaptureSessionByOutputConfigurations(
                    Arrays.asList(previewCfg, stillCfg),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession s) {
                            if (cameraDevice == null) return;
                            session = s;
                            try {
                                pushManualFocus(focusBar.getCurrent());
                                uiHandler.post(() -> setControlsEnabled(true));
                            } catch (CameraAccessException e) {
                                uiHandler.post(() -> fail("Preview request failed: " + e.getMessage()));
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession s) {
                            uiHandler.post(() -> fail("Camera session refused"));
                        }
                    }, bgHandler);
        } catch (CameraAccessException e) {
            fail("Create session failed: " + e.getMessage());
        }
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        maybeOpenCamera();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {}

    // ---- manual focus (the bar is authoritative; no AF in this path) ------

    private void onFocusBarDragged(float distance) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastFocusPushMs < FOCUS_PREVIEW_THROTTLE_MS) return;
        lastFocusPushMs = now;
        bgHandler.post(() -> {
            try {
                pushManualFocus(distance);
            } catch (CameraAccessException ignored) {
            }
        });
    }

    private void pushManualFocus(float distance) throws CameraAccessException {
        if (session == null || capturing) return;
        CaptureRequest.Builder req = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        req.addTarget(previewSurface);
        req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
        req.set(CaptureRequest.LENS_FOCUS_DISTANCE, distance);
        session.setRepeatingRequest(req.build(), null, bgHandler);
    }

    private void setBound(boolean isFar) {
        float distance = focusBar.getCurrent();
        if (isFar) {
            farDistance = distance;
            focusBar.setFar(distance);
        } else {
            nearDistance = distance;
            focusBar.setNear(distance);
        }
        updateIntervalPreview();
        captureButton.setEnabled(farDistance != null && nearDistance != null);
        status.setText(String.format("%s set to %.2f D", isFar ? "Far" : "Near", distance));
    }

    /**
     * Best-effort assist, not a dependency: one AF trigger with a short
     * timeout that, on success, only moves the live focus-bar position —
     * it never blocks Set Far/Set Near/Capture, and always resolves (no
     * state the UI can get stuck in), unlike the old blocking AF-lock flow.
     */
    private void autofocusAssist() {
        if (session == null || capturing || autofocusing) return;
        autofocusing = true;
        status.setText("Autofocusing…");
        try {
            CaptureRequest.Builder req = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            req.addTarget(previewSurface);
            req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            req.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
            session.capture(req.build(), autofocusCallback, bgHandler);
            bgHandler.postDelayed(this::onAutofocusTimeout, AUTOFOCUS_ASSIST_TIMEOUT_MS);
        } catch (CameraAccessException e) {
            autofocusing = false;
            status.setText("Autofocus failed: " + e.getMessage());
        }
    }

    private void onAutofocusTimeout() {
        if (!autofocusing) return;
        resolveAutofocus(null);
        uiHandler.post(() -> status.setText("Autofocus timed out — drag the bar manually"));
    }

    private void resolveAutofocus(Float distance) {
        autofocusing = false;
        try {
            pushManualFocus(focusBar.getCurrent());
        } catch (CameraAccessException ignored) {
        }
        if (distance != null) {
            uiHandler.post(() -> {
                focusBar.setCurrent(distance);
                status.setText(String.format("Autofocus landed at %.2f D", distance));
            });
            bgHandler.post(() -> {
                try {
                    pushManualFocus(distance);
                } catch (CameraAccessException ignored) {
                }
            });
        }
    }

    private final CameraCaptureSession.CaptureCallback autofocusCallback =
            new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult result) {
                    if (!autofocusing) return;
                    Integer af = result.get(CaptureResult.CONTROL_AF_STATE);
                    if (af == null) return;
                    boolean locked = af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                            || af == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED;
                    if (!locked) return;
                    Float distance = result.get(CaptureResult.LENS_FOCUS_DISTANCE);
                    resolveAutofocus(distance);
                }

                @Override
                public void onCaptureFailed(CameraCaptureSession s, CaptureRequest r, CaptureFailure failure) {
                    if (!autofocusing) return;
                    resolveAutofocus(null);
                }
            };

    // ---- bracket capture ----------------------------------------------------

    private void startBracketCapture() {
        if (farDistance == null || nearDistance == null || capturing || session == null) return;
        capturing = true;
        setControlsEnabled(false);

        bracketSteps = computeBracketSteps();
        bracketIndex = 0;
        capturedPaths = new ArrayList<>();
        captureDir = new File(getCacheDir(), "capture_output");
        deleteRecursive(captureDir);
        captureDir.mkdirs();

        status.setText("Capturing 1/" + shots);
        bgHandler.post(this::captureNextStep);
    }

    private void captureNextStep() {
        if (bracketIndex >= bracketSteps.length) {
            finishCapture();
            return;
        }
        float distance = bracketSteps[bracketIndex];
        try {
            CaptureRequest.Builder req = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            req.addTarget(previewSurface);
            req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            req.set(CaptureRequest.LENS_FOCUS_DISTANCE, distance);
            session.setRepeatingRequest(req.build(), null, bgHandler);
        } catch (CameraAccessException e) {
            abortCapture("Focus step failed: " + e.getMessage());
            return;
        }
        uiHandler.post(() -> focusBar.setCurrent(distance));
        bgHandler.postDelayed(() -> captureStillAt(distance), FOCUS_SETTLE_MS);
    }

    private void captureStillAt(float distance) {
        try {
            CaptureRequest.Builder req = cameraDevice.createCaptureRequest(
                    CameraDevice.TEMPLATE_STILL_CAPTURE);
            req.addTarget(stillReader.getSurface());
            req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            req.set(CaptureRequest.LENS_FOCUS_DISTANCE, distance);
            req.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation());
            session.capture(req.build(), null, bgHandler);
        } catch (CameraAccessException e) {
            abortCapture("Still capture failed: " + e.getMessage());
        }
    }

    private void onStillImageAvailable(ImageReader reader) {
        Image image = reader.acquireNextImage();
        if (image == null) return;
        try {
            File dest = new File(captureDir, String.format("%03d.jpg", bracketIndex));
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            try (FileOutputStream out = new FileOutputStream(dest)) {
                out.write(bytes);
            }
            capturedPaths.add(dest.getAbsolutePath());
        } catch (IOException e) {
            abortCapture("Saving shot " + bracketIndex + " failed: " + e.getMessage());
            return;
        } finally {
            image.close();
        }

        bracketIndex++;
        if (bracketIndex < bracketSteps.length) {
            int shown = bracketIndex + 1;
            uiHandler.post(() -> status.setText("Capturing " + shown + "/" + shots));
        }
        bgHandler.post(this::captureNextStep);
    }

    private void abortCapture(String message) {
        capturing = false;
        uiHandler.post(() -> {
            status.setText(message);
            setControlsEnabled(true);
        });
    }

    private void finishCapture() {
        capturing = false;
        try {
            pushManualFocus(focusBar.getCurrent());
        } catch (CameraAccessException ignored) {
        }
        List<String> result = capturedPaths;
        uiHandler.post(() -> {
            Intent data = new Intent();
            data.putStringArrayListExtra(EXTRA_RESULT_PATHS, new ArrayList<>(result));
            setResult(RESULT_OK, data);
            finish();
        });
    }

    private int jpegOrientation() {
        int sensorOrientation = cameraChoices.get(selectedChoice).sensorOrientation;
        int deviceRotation = getWindowManager().getDefaultDisplay().getRotation();
        return (ORIENTATIONS.get(deviceRotation) + sensorOrientation + 270) % 360;
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        f.delete();
    }
}
