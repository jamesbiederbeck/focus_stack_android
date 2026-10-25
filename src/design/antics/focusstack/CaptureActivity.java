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
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Size;
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
import java.util.List;

/**
 * Guided manual-focus bracket capture: "Focus Far"/"Focus Near" each
 * autofocus-and-lock on whatever the camera is pointed at and record the
 * resulting lens distance; "Capture N" then shoots a manual-focus series
 * (CONTROL_AF_MODE_OFF, stepping LENS_FOCUS_DISTANCE linearly in diopters)
 * between those two points and returns the file paths, in far-to-near
 * order, for MainActivity to feed into FocusStackBridge. Framework Camera2
 * only (no CameraX/androidx), matching the rest of this tree; the
 * focus-lock state machine mirrors Google's Camera2Basic sample, which is
 * the canonical correct way to drive CONTROL_AF_TRIGGER.
 */
public class CaptureActivity extends Activity implements TextureView.SurfaceTextureListener {

    static final String EXTRA_RESULT_PATHS = "design.antics.focusstack.RESULT_PATHS";

    private static final int CAMERA_PERMISSION_REQUEST = 10;
    private static final int DEFAULT_SHOTS = 8;
    private static final int MIN_SHOTS = 3;
    private static final int MAX_SHOTS = 20;
    private static final long AF_LOCK_TIMEOUT_MS = 2500;
    // LENS_FOCUS_DISTANCE changes aren't guaranteed to take effect by the
    // very next capture on every HAL; there's no portable "lens settled"
    // signal cheap enough to poll per-step, so a fixed delay after each
    // focus change is the pragmatic choice here (confirmed on-device: a
    // Pixel 8 Pro settles well within this).
    private static final long FOCUS_SETTLE_MS = 180;

    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    private enum AfState { PREVIEW, WAITING_LOCK_FAR, WAITING_LOCK_NEAR }

    private TextureView textureView;
    private TextView status, shotsLabel;
    private Button farButton, nearButton, captureButton, minusButton, plusButton;

    private HandlerThread bgThread;
    private Handler bgHandler;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private CameraManager cameraManager;
    private String cameraId;
    private CameraCharacteristics characteristics;
    private int sensorOrientation;
    private float minFocusDistance; // diopters; 0 or absent = fixed-focus lens, unsupported

    private CameraDevice cameraDevice;
    private CameraCaptureSession session;
    private Surface previewSurface;
    private Size previewSize, stillSize;
    private ImageReader stillReader;

    private AfState afState = AfState.PREVIEW;
    private Float farDistance, nearDistance;
    private int shots = DEFAULT_SHOTS;

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

        if (!discoverCamera()) {
            return; // discoverCamera() already reported why and will finish()
        }

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
        farButton.setOnClickListener(v -> lockFocus(true));
        nearButton = new Button(this);
        nearButton.setText("Focus Near");
        nearButton.setEnabled(false);
        nearButton.setOnClickListener(v -> lockFocus(false));
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
    }

    private void setControlsEnabled(boolean enabled) {
        farButton.setEnabled(enabled);
        nearButton.setEnabled(enabled);
        minusButton.setEnabled(enabled);
        plusButton.setEnabled(enabled);
        captureButton.setEnabled(enabled && farDistance != null && nearDistance != null);
    }

    // ---- camera discovery (no permission needed for characteristics) ----

    /** Returns false (and finishes the activity) if this device can't do this. */
    private boolean discoverCamera() {
        try {
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id;
                    characteristics = c;
                    break;
                }
            }
        } catch (CameraAccessException e) {
            fail("Camera discovery failed: " + e.getMessage());
            return false;
        }
        if (cameraId == null) {
            fail("No rear camera found");
            return false;
        }

        Integer orient = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        sensorOrientation = orient == null ? 90 : orient;

        Float minDist = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        minFocusDistance = minDist == null ? 0f : minDist;
        if (minFocusDistance <= 0f) {
            fail("This camera has no manual focus control — can't do a focus bracket");
            return false;
        }

        StreamConfigurationMap map = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        previewSize = sizeNear(map.getOutputSizes(SurfaceTexture.class), 1280 * 720);
        stillSize = largestJpegUnder(map.getOutputSizes(ImageFormat.JPEG), 12_000_000);
        return true;
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
        session.setRepeatingRequest(req.build(), afCallback, bgHandler);
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
            CaptureRequest.Builder req = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            req.addTarget(previewSurface);
            req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            req.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
            session.capture(req.build(), afCallback, bgHandler);
            bgHandler.postDelayed(() -> onAfLockTimeout(afState), AF_LOCK_TIMEOUT_MS);
        } catch (CameraAccessException e) {
            afState = AfState.PREVIEW;
            uiHandler.post(() -> {
                status.setText("Focus failed: " + e.getMessage());
                setControlsEnabled(true);
            });
        }
    }

    private void onAfLockTimeout(AfState stateWhenScheduled) {
        if (afState != stateWhenScheduled) return; // already resolved
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

                    Float distance = result.get(CaptureResult.LENS_FOCUS_DISTANCE);
                    boolean wasFar = afState == AfState.WAITING_LOCK_FAR;
                    afState = AfState.PREVIEW;
                    if (distance != null) {
                        if (wasFar) farDistance = distance; else nearDistance = distance;
                    }

                    try {
                        CaptureRequest.Builder cancel = cameraDevice.createCaptureRequest(
                                CameraDevice.TEMPLATE_PREVIEW);
                        cancel.addTarget(previewSurface);
                        cancel.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
                        cancel.set(CaptureRequest.CONTROL_AF_TRIGGER,
                                CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
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

        float lo = Math.min(farDistance, nearDistance);
        float hi = Math.max(farDistance, nearDistance);
        bracketSteps = new float[shots];
        for (int i = 0; i < shots; i++) {
            bracketSteps[i] = shots == 1 ? (lo + hi) / 2f : lo + i * (hi - lo) / (shots - 1);
        }
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
            setRepeatingPreview(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE, null);
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
