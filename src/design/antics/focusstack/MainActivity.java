package design.antics.focusstack;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Picks a set of images via the system photo picker, runs them through the
 * vendored focus-stack library (FocusStackBridge, see jni/), and saves the
 * merged result to MediaStore. Views are built in code, same reasoning as
 * every other Make-built app here: no R class for layouts, no androidx.
 */
public class MainActivity extends Activity {

    // Native focus-stack holds every input + several working copies in
    // memory per thread; this device's camera produces ~50MP photos (see
    // aphis' reference image), and stacking several of those uncapped is a
    // real LMK-kill risk, not a hypothetical one. Cap the long edge so a
    // typical stack stays well under a few hundred MB resident.
    private static final int MAX_MEGAPIXELS = 12_000_000;
    private static final int PICK_IMAGES_REQUEST = 1;
    private static final int CAPTURE_REQUEST = 2;
    private static final int CAMERA_PERMISSION_REQUEST = 3;

    private static final int PICK_IMAGES_MAX = 20;

    private TextView status;
    private ImageView preview;
    private ProgressBar progress;
    private Button pickButton, captureButton;

    private HandlerThread workerThread;
    private Handler workerHandler;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        workerThread = new HandlerThread("focus-stack-worker");
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(Color.BLACK);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Focus Stack");
        title.setTextColor(Color.WHITE);
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        pickButton = new Button(this);
        pickButton.setText("Select Photos");
        pickButton.setOnClickListener(v -> launchPicker());
        root.addView(pickButton);

        captureButton = new Button(this);
        captureButton.setText("Capture Stack with Camera");
        captureButton.setOnClickListener(v -> launchCapture());
        root.addView(captureButton);

        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        root.addView(progress);

        status = new TextView(this);
        status.setText("Pick a focus-bracketed set of photos, in order.");
        status.setTextColor(Color.LTGRAY);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, pad, 0, pad);
        root.addView(status);

        preview = new ImageView(this);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        preview.setLayoutParams(previewParams);
        preview.setAdjustViewBounds(true);
        root.addView(preview);

        setContentView(root);
        hideSystemUI();
    }

    private void launchPicker() {
        Intent intent;
        if (Build.VERSION.SDK_INT >= 33) {
            int max = Math.min(PICK_IMAGES_MAX, MediaStore.getPickImagesMaxLimit());
            intent = new Intent(MediaStore.ACTION_PICK_IMAGES);
            intent.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, max);
        } else {
            intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("image/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
        }
        startActivityForResult(intent, PICK_IMAGES_REQUEST);
    }

    private void launchCapture() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
            return;
        }
        startActivityForResult(new Intent(this, CaptureActivity.class), CAPTURE_REQUEST);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == CAMERA_PERMISSION_REQUEST
                && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            startActivityForResult(new Intent(this, CaptureActivity.class), CAPTURE_REQUEST);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (data == null) {
            return;
        }

        if (requestCode == CAPTURE_REQUEST) {
            if (resultCode != RESULT_OK) return;
            List<String> paths = data.getStringArrayListExtra(CaptureActivity.EXTRA_RESULT_PATHS);
            if (paths == null || paths.size() < 2) {
                Toast.makeText(this, "Capture did not produce enough shots", Toast.LENGTH_SHORT).show();
                return;
            }
            List<Uri> uris = new ArrayList<>();
            for (String path : paths) {
                uris.add(Uri.fromFile(new File(path)));
            }
            runStack(uris);
            return;
        }

        if (requestCode != PICK_IMAGES_REQUEST) {
            return;
        }

        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            int count = data.getClipData().getItemCount();
            for (int i = 0; i < count; i++) {
                uris.add(data.getClipData().getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }

        if (uris.size() < 2) {
            Toast.makeText(this, "Pick at least 2 photos to stack", Toast.LENGTH_SHORT).show();
            return;
        }

        runStack(uris);
    }

    private void runStack(List<Uri> uris) {
        setBusy(true, "Preparing " + uris.size() + " photos…");
        pickButton.setEnabled(false);
        captureButton.setEnabled(false);

        workerHandler.post(() -> {
            try {
                File cacheDir = new File(getCacheDir(), "stack_input");
                deleteRecursive(cacheDir);
                cacheDir.mkdirs();

                List<String> inputPaths = new ArrayList<>();
                for (int i = 0; i < uris.size(); i++) {
                    File dest = new File(cacheDir, String.format("%03d.jpg", i));
                    copyDownscaled(uris.get(i), dest);
                    inputPaths.add(dest.getAbsolutePath());
                }

                uiHandler.post(() -> setBusy(true, "Stacking " + inputPaths.size() + " photos…"));

                File outFile = new File(getCacheDir(), "focus_stack_result.jpg");
                String[] inputs = inputPaths.toArray(new String[0]);
                // OpenCL on mobile GPUs is a reliability liability (driver
                // bugs, no meaningful speedup for this workload at this
                // image size) rather than a real win, so it's always off.
                String error = FocusStackBridge.nativeStack(inputs, outFile.getAbsolutePath(), true);

                if (error != null) {
                    uiHandler.post(() -> onStackFailed(error));
                    return;
                }

                Uri savedUri = saveToMediaStore(outFile);
                uiHandler.post(() -> onStackSucceeded(outFile, savedUri));
            } catch (IOException e) {
                uiHandler.post(() -> onStackFailed(e.getMessage()));
            }
        });
    }

    private void onStackSucceeded(File outFile, Uri savedUri) {
        setBusy(false, savedUri != null
                ? "Saved to Pictures/FocusStack"
                : "Stacked, but saving to gallery failed");
        pickButton.setEnabled(true);
        captureButton.setEnabled(true);
        preview.setImageBitmap(BitmapFactory.decodeFile(outFile.getAbsolutePath()));
    }

    private void onStackFailed(String message) {
        setBusy(false, "Failed: " + message);
        pickButton.setEnabled(true);
        captureButton.setEnabled(true);
    }

    private void setBusy(boolean busy, String message) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        status.setText(message);
    }

    /**
     * Native focus-stack loads each input straight off disk by path (it
     * can't resolve a content:// URI), so every picked image has to be
     * materialized into a real file first. Downscaling here, rather than
     * copying the original bytes, is what actually bounds the native
     * pipeline's memory use (see MAX_MEGAPIXELS) and also normalizes EXIF
     * rotation, which focus-stack's plain cv::imread does not apply.
     */
    private void copyDownscaled(Uri uri, File dest) throws IOException {
        int[] size = decodeBounds(uri);
        int sampleSize = computeSampleSize(size[0], size[1], MAX_MEGAPIXELS);

        Bitmap bitmap;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleSize;
            bitmap = BitmapFactory.decodeStream(in, null, opts);
        }
        if (bitmap == null) {
            throw new IOException("Could not decode " + uri);
        }

        int rotation = readExifRotation(uri);
        if (rotation != 0) {
            Matrix matrix = new Matrix();
            matrix.postRotate(rotation);
            Bitmap rotated = Bitmap.createBitmap(
                    bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            bitmap.recycle();
            bitmap = rotated;
        }

        try (OutputStream out = new FileOutputStream(dest)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out);
        } finally {
            bitmap.recycle();
        }
    }

    private int[] decodeBounds(Uri uri) throws IOException {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, opts);
        }
        return new int[]{opts.outWidth, opts.outHeight};
    }

    private static int computeSampleSize(int width, int height, int maxPixels) {
        int sample = 1;
        while ((width / sample) * (long) (height / sample) > maxPixels) {
            sample *= 2;
        }
        return sample;
    }

    private int readExifRotation(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            ExifInterface exif = new ExifInterface(in);
            int orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90: return 90;
                case ExifInterface.ORIENTATION_ROTATE_180: return 180;
                case ExifInterface.ORIENTATION_ROTATE_270: return 270;
                default: return 0;
            }
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * MediaStore's RELATIVE_PATH insert path needs no storage permission
     * on API 29+. Below that, scoped storage didn't exist yet and this
     * would need WRITE_EXTERNAL_STORAGE plus a legacy File path; this app
     * targets the devices in CLAUDE.md's fleet, which all run well past
     * API 29, so that path is intentionally not implemented.
     */
    private Uri saveToMediaStore(File source) {
        if (Build.VERSION.SDK_INT < 29) {
            return null;
        }
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, "focus_stack_" + System.currentTimeMillis() + ".jpg");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/FocusStack");

        Uri collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri item = getContentResolver().insert(collection, values);
        if (item == null) {
            return null;
        }
        try (InputStream in = new java.io.FileInputStream(source);
             OutputStream out = getContentResolver().openOutputStream(item)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return item;
        } catch (IOException e) {
            getContentResolver().delete(item, null, null);
            return null;
        }
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        f.delete();
    }

    private void hideSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
        );
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUI();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        workerThread.quitSafely();
    }
}
