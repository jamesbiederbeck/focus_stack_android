# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A thin Android UI (system photo picker in, `Pictures/FocusStack` out) around
the real work, which is entirely done by a vendored, out-of-tree native
library: [focus-stack](https://github.com/PetteriAimonen/focus-stack)
(locally `~/code/focus-stack`, mirrored/CI-built at
[focus-stack-arm](https://github.com/jamesbiederbeck/focus-stack-arm)).
Forks the Make-only, plain-Android-SDK shell pattern from `aphis`/`bugvision`,
but the native dependency here is a vendored *application* library (like
sharerouter's llama.cpp), not a prebuilt SDK (like aphis's OpenCV Java
bindings) — this app needs both at once, for different reasons.

## Build system

```bash
make keystore                    # one-time: generate debug.keystore
make                              # build signed APK (aligned.apk)
make install DEVICE=<ip>:<port>   # build + adb install
make run DEVICE=<ip>:<port>       # install + launch
make clean                        # removes out/, *.apk, jni/build/ (leaves tools/ cache)
```

`ANDROID_JAR` = android-35 (compileSdk), `AAPT2` = build-tools 34.0.0,
min-sdk 24 / target-sdk 34 — same split as aphis (compile against the latest
installed platform, run against the targetSdk the manifest declares).

### The two native builds

Getting focus-stack's C++ into the APK is a two-stage CMake build, not one:

1. **`$(FOCUS_STACK_STAMP)`** builds focus-stack itself, out-of-tree, as its
   own standalone CMake project rooted at `$(FOCUS_STACK_DIR)` (default
   `~/code/focus-stack`), producing `build-android/libfocus-stack_lib.a`.
   This *cannot* be collapsed into one `add_subdirectory()`'d build from
   `jni/CMakeLists.txt`: focus-stack's own `CMakeLists.txt` resolves its
   sources via `${CMAKE_SOURCE_DIR}/src`, and `CMAKE_SOURCE_DIR` is fixed at
   the *top-level* project's source dir for the whole configure — inside an
   `add_subdirectory()` call that's still `jni/`, not `focus-stack/`, so it
   tries to compile `jni/src/main.cc` and fails with "No SOURCES given to
   target". That's a property of a CMakeLists.txt we don't own (and
   shouldn't patch for one consumer), not something fixable from our side,
   so focus-stack gets its own top-level `cmake -S $(FOCUS_STACK_DIR)`
   invocation instead — mirroring how sharerouter builds llama.cpp as a
   fully separate out-of-tree step, for the same underlying reason (a
   dependency that assumes it's the top of its own build).
2. **`$(NATIVE_STAMP)`** then configures `jni/` (a *second*, independent
   CMake project) and links `focusstack_jni.cpp` against that prebuilt
   `.a` via `find_library(... PATHS ${FOCUS_STACK_BUILD_DIR})` — the same
   shape as `sharerouter/jni/CMakeLists.txt`'s `find_library(LLAMA_LIB ...)`,
   not an `add_subdirectory()`.

Both stages pass the same `-DOpenCV_DIR=` (OpenCV's own `CMakeLists.txt`
also calls `find_package(OpenCV REQUIRED)`, so this app's OpenCV fetch has
to satisfy both the vendored library and our shim).

### OpenCV — native-only, no Java bindings, no `.so` to package

Unlike aphis (which needs `org.opencv.android`'s Java classes and so
compiles OpenCV's Java sources + packages `libopencv_java5.so`), this app's
C++ never touches Java at all — `FocusStack` is called directly from JNI.
So instead of aphis's approach, the Makefile fetches OpenCV's **static**
module libs (`native/staticlibs/<abi>/*.a` + their `native/3rdparty/libs/<abi>/*.a`
transitive deps) and links them straight into `libfocusstack_jni.so` via
CMake's `find_package(OpenCV REQUIRED COMPONENTS core imgproc photo video
imgcodecs)` — nothing OpenCV-related ships as a separate `.so` in the APK.

One gotcha: OpenCV Android SDK's `OpenCVModules.cmake` unconditionally
references `libopencv_java4.so` as an `IMPORTED` target file and **errors at
configure time if it's missing**, even though `find_package(... COMPONENTS
...)` never asked for the `opencv_java` module. The Makefile's extract step
pulls that one `.so` in (22MB, cached in `tools/` only) purely to satisfy
that check — it is never copied into `$(NATIVE_LIBS_DIR)` or zipped into the
APK.

### `ANDROID_STL=c++_static`, not `c++_shared`

aphis and sharerouter both need `c++_shared` because they link a *prebuilt*
shared `.so` (OpenCV's `libopencv_java5.so`, llama.cpp's `libllama.so`) that
was itself built against `c++_shared`, and mixing STLs across `.so`
boundaries is undefined/crashes. Here, both dependencies are static
archives (`libfocus-stack_lib.a`, OpenCV's static module libs) linked
straight into our one `.so`, so the whole graph can use `c++_static` and
there is **no `libc++_shared.so` to pull from the NDK sysroot and package**
— `readelf -d libfocusstack_jni.so` shows only system libs
(`liblog`/`libandroid`/`libc`/`libdl`/`libm`/`libz`). Confirmed on-device by
inspecting the built `.so`'s `NEEDED` entries before packaging — don't
reintroduce `c++_shared` without re-checking this.

### `FocusStackBridge` / `focusstack_jni.cpp`

`FocusStack::run()` swallows its own error message (it's a convenience
wrapper that discards `wait_done()`'s `errmsg` out-param), so the JNI shim
replicates `run()`'s body manually
(`reset(); start(); do_final_merge(); reset(true); wait_done(status, errmsg)`)
instead of calling `run()` directly, purely to get `errmsg` back to Java.
`nativeStack()` returns `null` on success or the error string on failure.

OpenCL is always disabled (`set_disable_opencl(true)`) — mobile GPU OpenCL
drivers are a reliability liability for this workload's image sizes, not a
real speedup, and desktop-focused OpenCL kernels are the kind of thing that
silently misbehaves on a phone GPU.

### Memory: downscaling happens in Java, before the native call

This device's camera produces ~50MP photos (same reference image aphis
tunes against). Native focus-stack keeps every input plus several working
copies in memory at once; feeding it a handful of 50MP frames uncapped is a
real LMK-kill risk. `MainActivity.copyDownscaled()` downsamples every picked
image to `MAX_MEGAPIXELS` (12MP) via `BitmapFactory.Options.inSampleSize`
*before* writing it to the cache file focus-stack reads — native code never
sees the original resolution. This also applies EXIF rotation (focus-stack's
plain `cv::imread()` doesn't), since a misrotated frame breaks alignment.

### Picker and save path assume a modern device

Uses `MediaStore.ACTION_PICK_IMAGES` (API 33+) with a fallback to
`ACTION_OPEN_DOCUMENT` + `EXTRA_ALLOW_MULTIPLE` below that, and saves via
`MediaStore` insert with `RELATIVE_PATH` (needs no storage permission on API
29+, but is simply not implemented below API 29 — no legacy
`WRITE_EXTERNAL_STORAGE` fallback). Every device in this fleet runs well
past API 29; `minSdk 24` is kept for consistency with the rest of the repo,
not because this app is meaningfully tested that low.

## Versioning

`versionName` comes from the `VERSION` file (bumped by hand, e.g. `0.1.0`);
`versionCode` is `git rev-list --count HEAD`, which only ever increases.
`make install-hooks` installs `hooks/post-commit` into `.git/hooks/`
(plain `.git/hooks/` isn't tracked by git, so this is a one-time step after
cloning) — it tags `HEAD` as `v<VERSION>` whenever a commit changes the
`VERSION` file, so a release build's `versionName` and its git tag can't
drift apart. A pre-commit hook can't do this tagging: a tag has to point at
a commit that already exists.

## Updating the vendored focus-stack checkout

`FOCUS_STACK_DIR` (default `~/code/focus-stack`) is a git checkout of
`github.com/PetteriAimonen/focus-stack` (`origin`), with a second remote
`arm` pointing at the fork `github.com/jamesbiederbeck/focus-stack-arm` (its
own CI builds Linux x86_64/ARM64 `.deb`/AppImage binaries — unrelated to
this Android app, which builds focus-stack as a *static lib* straight from
source). Pull `origin` and rebuild (`rm -rf build-android && make`) to pick
up upstream changes; nothing here pins a specific focus-stack commit.
