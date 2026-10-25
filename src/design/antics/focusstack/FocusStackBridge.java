package design.antics.focusstack;

/**
 * Thin JNI wrapper around the vendored focus-stack library (focusstack_jni.cpp).
 * Loading the library here, rather than in Application/MainActivity, keeps a
 * broken or missing .so isolated to the one feature that needs it.
 */
final class FocusStackBridge {

    static {
        System.loadLibrary("focusstack_jni");
    }

    private FocusStackBridge() {}

    /**
     * Blocking call — run on a background thread. inputPaths must be
     * ordered front-to-back (or back-to-front) through the focus stack,
     * not an arbitrary order; focus-stack aligns each image against its
     * neighbour. Returns null on success, or an error message on failure.
     */
    static native String nativeStack(String[] inputPaths, String outputPath, boolean disableOpenCl);
}
