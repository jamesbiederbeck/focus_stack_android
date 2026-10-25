# Focus Stack

Picks a focus-bracketed set of photos from the system photo picker, runs
them through [focus-stack](https://github.com/PetteriAimonen/focus-stack)
(vendored out-of-tree as a native JNI library — see `CLAUDE.md`), and saves
the merged, all-in-focus result to `Pictures/FocusStack`.

Built with Make — no Gradle, no IDE. Package `design.antics.focusstack`,
compileSdk 35 / minSdk 24 / targetSdk 34.

## Dependencies

```
sudo apt install \
  google-android-platform-35-installer \
  google-android-build-tools-34.0.0-installer \
  openjdk-17-jdk \
  apksigner \
  zipalign
```

Also needs a local checkout of
[focus-stack](https://github.com/PetteriAimonen/focus-stack) (default
`~/code/focus-stack`, override with `FOCUS_STACK_DIR=`), the Android NDK
(`/usr/lib/android-ndk`), and `cmake`/`ninja` on `PATH`.

`adb` comes from `../platform-tools/adb`.

## Build

```bash
make keystore              # one-time: generate debug.keystore
make                       # build signed APK (aligned.apk)
make install               # build + adb install
make run                   # install + launch
make logcat                # tail this app's log
make clean
```

`DEVICE` auto-detects the first device `adb` sees. Override with
`make run DEVICE=<ip>:<port>` or by exporting `ANDROID_DEVICE`.
