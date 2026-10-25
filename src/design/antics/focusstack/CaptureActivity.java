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
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
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
 * Guided manual-focus bracket capture. Two ways to set the far/near points:
 * by default, "Focus Far"/"Focus Near" each autofocus-and-lock on whatever
 * the camera is pointed at and record the resulting lens distance (the
 * focus-lock state machine mirrors Google's Camera2Basic sample, the
 * canonical correct way to drive CONTROL_AF_TRIGGER); with the "Manual
 * Focus" toggle on, FocusBarView instead drives LENS_FOCUS_DISTANCE
 * directly as the user drags it (real-time focus-by-eye, no AF trigger
 * involved) and Far/Near just record wherever it currently sits. Either
 * way, "Capture N" shoots a manual-focus series (CONTROL_AF_MODE_OFF,
 * linear diopter steps) between those two points and returns the file
 * paths, in far-to-near order, for MainActivity to feed into
 * FocusStackBridge. Framework Camera2 only (no CameraX/androidx), matching
 * the rest of this tree.
 *
 * A lens selector cycles whole camera ids (not Pixel-style hidden physical
 * sub-lenses selectable via OutputConfiguration.setPhysicalCameraId, as
 * stereovision's StereoRig does — out of scope here, not because it's
 * broken).
 *
 * (A previous version of this comment blamed a black preview on
 * single-physical-id binding; that was wrong. The actual cause was
 * FocusBarView missing onMeasure — WRAP_CONTENT next to a weight=1 sibling
 * in a vertical LinearLayout let it claim the whole remaining height,
 * collapsing the TextureView to 0px and pushing every control below it
 * off-screen. Fixed in FocusBarView.onMeasure().)
 */
public class CaptureActivity extends Activity implements TextureView.SurfaceTextureListener {

    static final String EXTRA_RESULT_PATHS = "design.antics.focusstack.RESULT_PATHS";
    private static final String LOG_TAG = "focusstack_capture";

    private static final int CAMERA_PERMISSION_REQUEST = 10;
    private static final int DEFAULT_SHOTS = 8;
    private static final int MIN_SHOTS = 3;
    private static final int MAX_SHOTS = 20;
    private static final long AF_LOCK_TIMEOUT_MS = 2500;
    // LENS_FOCUS_DISTANCE changes aren't guaranteed to take effect by the
    // very next capture on every HAL; there's no portable "lens settled"
    // signal cheap enough to poll per-step, so a fixed delay after each
    // focus change is the pragmatic choice here. Not yet verified
    // on-device against real stacked output — if stacks come out soft,
    // check this first.
    private static final long FOCUS_SETTLE_MS = 180;

    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    private enum AfState { PREVIEW, WAITING_LOCK_FAR, WAITING_LOCK_NEAR }

    /** One whole camera id (back or front) that reports manual focus support. */
    private static final class CameraChoice {
        final String id;
        final String label;
        final float minFocusDistance; // diopters
        final int sensorOrientation;
        final CameraCharacteristics characteristics; // always the LOGICAL camera's, for stream config
        final Float zoomRatio; // null = don't set CONTROL_ZOOM_RATIO at all

        CameraChoice(String id, String label, float minFocusDistance, int sensorOrientation,
                     CameraCharacteristics characteristics, Float zoomRatio) {
            this.id = id;
            this.label = label;
            this.minFocusDistance = minFocusDistance;
            this.sensorOrientation = sensorOrientation;
            this.characteristics = characteristics;
            this.zoomRatio = zoomRatio;
        }
    }

    private TextureView textureView;
    private FocusBarView focusBar;
    private TextView status, shotsLabel;
    private Button cameraButton, manualFocusButton, farButton, nearButton, captureButton,
            minusButton, plusButton;

    private HandlerThread bgThread;
    private Handler bgHandler;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private CameraManager cameraManager;
    private List<CameraChoice> cameraChoices;
    private int selectedChoice;
    private String cameraId;
    private CameraCharacteristics characteristics;
    private int sensorOrientation;
    private float minFocusDistance; // diopters; 0 or absent = fixed-focus lens, unsupported
    private Float zoomRatio; // null = don't set CONTROL_ZOOM_RATIO

    private CameraDevice cameraDevice;
    private CameraCaptureSession session;
    private Surface previewSurface;
    private Size previewSize, stillSize;
    private ImageReader stillReader;

    private AfState afState = AfState.PREVIEW;
    private Runnable afTimeoutRunnable;
    private Float farDistance, nearDistance;
    private int shots = DEFAULT_SHOTS;
    private boolean manualFocus;
    private long lastManualFocusPushMs;

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
            return; // discoverCameras() already reported why and will finish()
        }
        applyChoice(0);

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
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        textureView.setLayoutParams(previewParams);
        root.addView(textureView);

        cameraButton = new Button(this);
        cameraButton.setOnClickListener(v -> cycleCamera());
        root.addView(cameraButton);

        focusBar = new FocusBarView(this);
        focusBar.setListener(this::onFocusBarDragged);
        root.addView(focusBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        manualFocusButton = new Button(this);
        manualFocusButton.setOnClickListener(v -> toggleManualFocus());
        root.addView(manualFocusButton);
        updateManualFocusButtonText();

        status = new TextView(this);
        status.setText("Point the camera, then set the far and near focus points.");
        status.setGravity(Gravity.CENTER);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        status.setPadding(pad, pad, pad, pad);
        root.addView(status);

        LinearLayout focusRow = new LinearLayout(this);
        focusRow.setOrientation(LinearLayout.HORIZONTAL);
        farButton = new Button(this);
        farButton.setText("Focus Far");
        farButton.setEnabled(false);
        farButton.setOnClickListener(v -> onFarOrNearPressed(true));
        nearButton = new Button(this);
        nearButton.setText("Focus Near");
        nearButton.setEnabled(false);
        nearButton.setOnClickListener(v -> onFarOrNearPressed(false));
        focusRow.addView(farButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        focusRow.addView(nearButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
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

    private void setControlsEnabled(boolean enabled) {
        cameraButton.setEnabled(enabled && cameraChoices.size() > 1);
        manualFocusButton.setEnabled(enabled);
        farButton.setEnabled(enabled);
        nearButton.setEnabled(enabled);
        minusButton.setEnabled(enabled);
        plusButton.setEnabled(enabled);
        captureButton.setEnabled(enabled && farDistance != null && nearDistance != null);
    }

    private void updateManualFocusButtonText() {
        manualFocusButton.setText("Manual Focus: " + (manualFocus ? "On" : "Off"));
    }

    /**
     * With manual focus off (default), Far/Near drive the AF-lock flow as
     * before. With it on, the bar is already live-focusing the preview as
     * the user drags it, so Far/Near just record wherever it currently
     * sits — no AF trigger involved.
     */
    private void onFarOrNearPressed(boolean isFar) {
        if (manualFocus) {
            recordBound(isFar, focusBar.getCurrent());
        } else {
            lockFocus(isFar);
        }
    }

    private void recordBound(boolean isFar, float distance) {
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

    private void toggleManualFocus() {
        if (session == null || capturing) return;
        manualFocus = !manualFocus;
        updateManualFocusButtonText();
        focusBar.setInteractive(manualFocus);

        if (manualFocus) {
            // Cancel any AF lock in flight — manual mode takes over the
            // same repeating request AF-lock was using.
            if (afState != AfState.PREVIEW) {
                bgHandler.removeCallbacks(afTimeoutRunnable);
                afState = AfState.PREVIEW;
            }
            status.setText("Manual focus on — drag the bar, then Far/Near");
            bgHandler.post(() -> {
                try {
                    setRepeatingPreview(CaptureRequest.CONTROL_AF_MODE_OFF, focusBar.getCurrent());
                } catch (CameraAccessException ignored) {
                }
            });
        } else {
            status.setText("Manual focus off — Far/Near autofocus again");
            bgHandler.post(() -> {
                try {
                    setRepeatingPreview(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE, null);
                } catch (CameraAccessException ignored) {
                }
            });
        }
    }

    private void onFocusBarDragged(float distance) {
        if (!manualFocus || session == null || capturing) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastManualFocusPushMs < 50) return;
        lastManualFocusPushMs = now;
        bgHandler.post(() -> {
            try {
                setRepeatingPreview(CaptureRequest.CONTROL_AF_MODE_OFF, distance);
            } catch (CameraAccessException ignored) {
            }
        });
    }

    // ---- camera discovery (no permission needed for characteristics) ----

    /**
     * Enumerates every whole camera id with manual focus support (back ids
     * before front, matching the original back-camera-only pick as the
     * default). Deliberately id-level only — see the class javadoc for why
     * Pixel's hidden physical sub-lenses aren't offered here.
     * Returns false (and finishes the activity) if none qualify.
     */
    private boolean discoverCameras() {
        cameraChoices = new ArrayList<>();
        try {
            List<CameraChoice> back = new ArrayList<>();
            List<CameraChoice> front = new ArrayList<>();
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
                Float minDist = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                boolean isBack = facing != null && facing == CameraCharacteristics.LENS_FACING_BACK;
                Integer orient = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
                float fov = horizontalFovDeg(c);
                int[] afModes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
                Set<String> physIds = Build.VERSION.SDK_INT >= 28
                        ? c.getPhysicalCameraIds() : null;

                boolean usable = minDist != null && minDist > 0f;
                Log.i(LOG_TAG, String.format(
                        "camera id=%s facing=%s fov=%.1f° minFocusDistance=%s sensorOrientation=%s "
                                + "afModes=%s physicalIds=%s -> %s",
                        id, isBack ? "BACK" : (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT ? "FRONT" : "EXTERNAL"),
                        fov, minDist, orient, Arrays.toString(afModes), physIds,
                        usable ? "usable (manual focus)" : "SKIPPED (fixed focus / no LENS_INFO_MINIMUM_FOCUS_DISTANCE)"));

                // Hidden physical sub-lenses (Pixel-style logical multi-camera) aren't
                // offered as separate choices (see class javadoc), but log their own
                // FOV/focus range too since that's exactly what a "why doesn't the
                // ultrawide focus" report needs to see.
                if (physIds != null) {
                    for (String pid : physIds) {
                        try {
                            CameraCharacteristics pc = cameraManager.getCameraCharacteristics(pid);
                            Float pMinDist = pc.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
                            Log.i(LOG_TAG, String.format(
                                    "  physical id=%s (of logical %s) fov=%.1f° minFocusDistance=%s",
                                    pid, id, horizontalFovDeg(pc), pMinDist));
                        } catch (Exception e) {
                            Log.i(LOG_TAG, "  physical id=" + pid + ": characteristics refused (" + e + ")");
                        }
                    }
                }

                if (isBack && physIds != null && !physIds.isEmpty()) {
                    // Pixel-style logical multi-camera: its own reported FOV
                    // and LENS_INFO_MINIMUM_FOCUS_DISTANCE describe whatever
                    // physical lens is active at zoom=1x (the main lens here
                    // — confirmed on-device: id 0's own values matched id 2's
                    // exactly). Manual focus set on the logical camera can't
                    // reach a lens it never engages, which is why ultrawide
                    // focus "didn't work": the app never asked for it. Lenses
                    // are selected on a logical multi-camera via
                    // CONTROL_ZOOM_RATIO (API 30+), not by camera id, so each
                    // distinct physical lens becomes its own zoom-ratio preset.
                    back.addAll(buildZoomPresets(id, c, fov));
                } else if (usable) {
                    String label = String.format("%s (%.0f°, %.1fD)",
                            isBack ? "Back" : "Front", fov, minDist);
                    CameraChoice choice = new CameraChoice(
                            id, label, minDist, orient == null ? 90 : orient, c, null);
                    (isBack ? back : front).add(choice);
                }
            }
            cameraChoices.addAll(back);
            cameraChoices.addAll(front);
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

    /**
     * One CameraChoice per distinct physical lens behind a logical back
     * camera, each carrying the CONTROL_ZOOM_RATIO that engages it. Falls
     * back to the logical camera's own single (main-lens) range if zoom
     * ratio isn't supported (pre-API 30) or every physical id turns out to
     * be an unusable duplicate/fixed-focus lens.
     */
    private List<CameraChoice> buildZoomPresets(String logicalId, CameraCharacteristics logical, float logicalFov) {
        List<CameraChoice> presets = new ArrayList<>();
        android.util.Range<Float> zoomRange = Build.VERSION.SDK_INT >= 30
                ? logical.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) : null;

        if (zoomRange != null) {
            // Reference lens = whichever physical id's FOV is closest to the
            // logical camera's own default (zoom=1x) FOV.
            float mainFov = logicalFov;
            float bestDiff = Float.MAX_VALUE;
            for (String pid : logical.getPhysicalCameraIds()) {
                try {
                    float pFov = horizontalFovDeg(cameraManager.getCameraCharacteristics(pid));
                    float diff = Math.abs(pFov - logicalFov);
                    if (diff < bestDiff) {
                        bestDiff = diff;
                        mainFov = pFov;
                    }
                } catch (Exception ignored) {
                }
            }

            for (String pid : logical.getPhysicalCameraIds()) {
                CameraCharacteristics pc;
                Float pMinDist;
                try {
                    pc = cameraManager.getCameraCharacteristics(pid);
                    pMinDist = pc.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
                } catch (Exception e) {
                    continue;
                }
                if (pMinDist == null || pMinDist <= 0f) continue; // fixed-focus physical lens

                float pFov = horizontalFovDeg(pc);
                // Multi-resolution clone ids report near-identical optics to
                // a lens already kept (confirmed on-device: ids 5/6 matched
                // 2/4's fov and minFocusDistance exactly) — skip those.
                boolean duplicate = false;
                for (CameraChoice kept : presets) {
                    if (Math.abs(kept.minFocusDistance - pMinDist) < 0.5f) {
                        duplicate = true;
                        break;
                    }
                }
                if (duplicate) continue;

                float ratio = (float) (Math.tan(Math.toRadians(mainFov / 2.0))
                        / Math.tan(Math.toRadians(pFov / 2.0)));
                ratio = Math.max(zoomRange.getLower(), Math.min(zoomRange.getUpper(), ratio));

                String label = String.format("Back ×%.1f (%.0f°, %.1fD)", ratio, pFov, pMinDist);
                presets.add(new CameraChoice(logicalId, label, pMinDist,
                        logical.get(CameraCharacteristics.SENSOR_ORIENTATION) == null
                                ? 90 : logical.get(CameraCharacteristics.SENSOR_ORIENTATION),
                        logical, ratio));
            }
        }

        if (presets.isEmpty()) {
            Float minDist = logical.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
            if (minDist != null && minDist > 0f) {
                Integer orient = logical.get(CameraCharacteristics.SENSOR_ORIENTATION);
                String label = String.format("Back (%.0f°, %.1fD)", logicalFov, minDist);
                presets.add(new CameraChoice(logicalId, label, minDist,
                        orient == null ? 90 : orient, logical, null));
            }
        }
        return presets;
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
        closeCamera();
        applyChoice((selectedChoice + 1) % cameraChoices.size());
        maybeOpenCamera();
    }

    private void applyChoice(int index) {
        selectedChoice = index;
        CameraChoice choice = cameraChoices.get(index);
        cameraId = choice.id;
        characteristics = choice.characteristics;
        sensorOrientation = choice.sensorOrientation;
        minFocusDistance = choice.minFocusDistance;
        zoomRatio = choice.zoomRatio;

        cameraButton.setText("Lens: " + choice.label);
        cameraButton.setEnabled(cameraChoices.size() > 1);

        farDistance = null;
        nearDistance = null;
        manualFocus = false;
        updateManualFocusButtonText();
        focusBar.setInteractive(false);
        focusBar.setRange(0f, minFocusDistance);
        focusBar.setCurrent(0f);
        focusBar.setFar(null);
        focusBar.setNear(null);
        focusBar.setIntervalSteps(null);
        captureButton.setEnabled(false);

        StreamConfigurationMap map = characteristics.get(
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
        if (cameraDevice != null || cameraId == null || bgHandler == null) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        if (!textureView.isAvailable()) return;

        SurfaceTexture texture = textureView.getSurfaceTexture();
        texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
        previewSurface = new Surface(texture);

        stillReader = ImageReader.newInstance(
                stillSize.getWidth(), stillSize.getHeight(), ImageFormat.JPEG, 2);
        stillReader.setOnImageAvailableListener(this::onStillImageAvailable, bgHandler);

        try {
            //noinspection MissingPermission - checked above
            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
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
        try {
            cameraDevice.createCaptureSession(
                    java.util.Arrays.asList(previewSurface, stillReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession s) {
                            if (cameraDevice == null) return;
                            session = s;
                            try {
                                setRepeatingPreview(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE, null);
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

    private void setRepeatingPreview(int afMode, Float focusDistance) throws CameraAccessException {
        CaptureRequest.Builder req = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        req.addTarget(previewSurface);
        req.set(CaptureRequest.CONTROL_AF_MODE, afMode);
        if (focusDistance != null) {
            req.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance);
        }
        applyZoomRatio(req);
        session.setRepeatingRequest(req.build(), afCallback, bgHandler);
    }

    /** Selects which physical lens a logical multi-camera engages (API 30+ only). */
    private void applyZoomRatio(CaptureRequest.Builder builder) {
        if (zoomRatio != null) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio);
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

    // ---- focus lock (mirrors Google's Camera2Basic lockFocus() pattern) ---

    private void lockFocus(boolean isFar) {
        if (session == null || capturing || afState != AfState.PREVIEW) return;
        afState = isFar ? AfState.WAITING_LOCK_FAR : AfState.WAITING_LOCK_NEAR;
        setControlsEnabled(false);
        status.setText("Focusing (" + (isFar ? "far" : "near") + ")…");
        try {
            // The repeating request has to switch to AF_MODE_AUTO *before*
            // the trigger goes out, not just on the one-shot trigger
            // request itself — leaving the repeating request on
            // CONTINUOUS_PICTURE while the trigger uses AUTO resets the AF
            // state machine on the next repeating frame, so CONTROL_AF_STATE
            // never reaches a locked value. This was the actual cause of
            // the original "autofocus times out" report, not a timeout
            // that was too short.
            setRepeatingPreview(CaptureRequest.CONTROL_AF_MODE_AUTO, null);
            CaptureRequest.Builder req = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            req.addTarget(previewSurface);
            req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            req.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
            applyZoomRatio(req);
            session.capture(req.build(), afCallback, bgHandler);
            afTimeoutRunnable = this::onAfLockTimeout;
            bgHandler.postDelayed(afTimeoutRunnable, AF_LOCK_TIMEOUT_MS);
        } catch (CameraAccessException e) {
            afState = AfState.PREVIEW;
            uiHandler.post(() -> {
                status.setText("Focus failed: " + e.getMessage());
                setControlsEnabled(true);
            });
        }
    }

    private void onAfLockTimeout() {
        // afTimeoutRunnable is cancelled via removeCallbacks() as soon as
        // afCallback resolves the lock, but guard anyway: a prior version
        // captured afState into the Runnable's closure at schedule time
        // and compared against the (always-current) field, so this check
        // was always false and every lock attempt — including successful
        // ones — got overwritten with a bogus timeout message 2.5s later.
        if (afState == AfState.PREVIEW) return;
        afState = AfState.PREVIEW;
        try {
            setRepeatingPreview(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE, null);
        } catch (CameraAccessException ignored) {
        }
        uiHandler.post(() -> {
            status.setText("Autofocus timed out, try again");
            setControlsEnabled(true);
        });
    }

    private final CameraCaptureSession.CaptureCallback afCallback =
            new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult result) {
                    if (afState == AfState.PREVIEW) return;
                    Integer af = result.get(CaptureResult.CONTROL_AF_STATE);
                    if (af == null) return;
                    boolean locked = af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                            || af == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED;
                    if (!locked) return;

                    bgHandler.removeCallbacks(afTimeoutRunnable);
                    Float distance = result.get(CaptureResult.LENS_FOCUS_DISTANCE);
                    boolean wasFar = afState == AfState.WAITING_LOCK_FAR;
                    afState = AfState.PREVIEW;
                    if (distance != null) {
                        if (wasFar) farDistance = distance; else nearDistance = distance;
                        uiHandler.post(() -> {
                            focusBar.setCurrent(distance);
                            if (wasFar) focusBar.setFar(distance); else focusBar.setNear(distance);
                            updateIntervalPreview();
                        });
                    }

                    try {
                        CaptureRequest.Builder cancel = cameraDevice.createCaptureRequest(
                                CameraDevice.TEMPLATE_PREVIEW);
                        cancel.addTarget(previewSurface);
                        cancel.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
                        cancel.set(CaptureRequest.CONTROL_AF_TRIGGER,
                                CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
                        applyZoomRatio(cancel);
                        s.capture(cancel.build(), null, bgHandler);
                        setRepeatingPreview(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE, null);
                    } catch (CameraAccessException ignored) {
                    }

                    uiHandler.post(() -> {
                        String label = wasFar ? "Far" : "Near";
                        status.setText(distance != null
                                ? String.format("%s focus set (%.2f D)", label, distance)
                                : label + " focus locked");
                        setControlsEnabled(true);
                    });
                }

                @Override
                public void onCaptureFailed(CameraCaptureSession s, CaptureRequest r, CaptureFailure failure) {
                    if (afState == AfState.PREVIEW) return;
                    bgHandler.removeCallbacks(afTimeoutRunnable);
                    afState = AfState.PREVIEW;
                    uiHandler.post(() -> {
                        status.setText("Autofocus capture failed, try again");
                        setControlsEnabled(true);
                    });
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
            setRepeatingPreview(CaptureRequest.CONTROL_AF_MODE_OFF, distance);
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
            applyZoomRatio(req);
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
            if (manualFocus) {
                setRepeatingPreview(CaptureRequest.CONTROL_AF_MODE_OFF, focusBar.getCurrent());
            } else {
                setRepeatingPreview(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE, null);
            }
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
