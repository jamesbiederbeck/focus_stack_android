APP_ID = design.antics.focusstack

SRC := $(shell find src -name "*.java")

OUT = out
CLASSES = $(OUT)/classes
DEX = $(OUT)/classes.dex
RESZIP = $(OUT)/compiled_res.zip

UNSIGNED = unsigned.apk
ALIGNED = aligned.apk

# Plain '=' (not '?=') so a stale exported ANDROID_JAR can't drag the build
# below the target SDK. `make ANDROID_JAR=...` on the CLI still overrides.
# compileSdk 35 jar (latest installed), matching aphis/bugvision; runtime
# targetSdk stays 34.
ANDROID_JAR = /usr/lib/android-sdk/platforms/android-35/android.jar
AAPT2 = /usr/lib/android-sdk/build-tools/34.0.0/aapt2

MIN_SDK = 24
TARGET_SDK = 34

KEYSTORE = debug.keystore

# Hardcoded device addresses go stale (the tablet is on DHCP), so pick the
# first device adb already has. Override with `make run DEVICE=<ip>:<port>`
# or by exporting ANDROID_DEVICE.
ADB = ../platform-tools/adb
DEVICE ?= $(or $(ANDROID_DEVICE),$(shell $(ADB) devices | awk 'NR>1 && $$2=="device" {print $$1; exit}'))

# --- OpenCV (native C++ only — no org.opencv.android Java bindings needed;
# focus-stack is a pure-native library, so we link its static module libs
# straight into our .so via CMake's find_package(OpenCV), mirroring aphis'
# fetch/pin pattern but pulling native/jni + native/staticlibs +
# native/3rdparty instead of the Java sources aphis needs). -------------
OPENCV_VERSION = 4.11.0
OPENCV_SDK_URL = https://github.com/opencv/opencv/releases/download/$(OPENCV_VERSION)/opencv-$(OPENCV_VERSION)-android-sdk.zip
OPENCV_SDK_SHA256 = fab645f6b42e4f02ed43e57191cab37b00b861d13191afa34d68fc9f3d8ff763
NATIVE_ABI = arm64-v8a

TOOLS = tools
OPENCV_SDK_ZIP = $(TOOLS)/opencv-$(OPENCV_VERSION)-android-sdk.zip
OPENCV_EXTRACT_DIR = $(TOOLS)/opencv-android-sdk
OPENCV_JNI_DIR = $(OPENCV_EXTRACT_DIR)/OpenCV-android-sdk/sdk/native/jni

$(OPENCV_SDK_ZIP):
	mkdir -p $(TOOLS)
	curl -sL $(OPENCV_SDK_URL) -o $@
	echo "$(OPENCV_SDK_SHA256)  $@" | sha256sum -c -

# Extracts only the CMake package files + headers (native/jni), the
# per-module static libs (native/staticlibs) and their third-party static
# deps (native/3rdparty) for arm64-v8a — not the ~318MB full SDK (other
# ABIs, docs, samples). libopencv_java4.so IS pulled in despite never being
# linked or packaged: OpenCVModules.cmake unconditionally references it as
# an IMPORTED target file and errors at configure time if it's missing,
# even when find_package() only requests other components.
OPENCV_STAMP = $(TOOLS)/.opencv_extracted_stamp
$(OPENCV_STAMP): $(OPENCV_SDK_ZIP)
	rm -rf $(OPENCV_EXTRACT_DIR)
	mkdir -p $(OPENCV_EXTRACT_DIR)
	unzip -q $(OPENCV_SDK_ZIP) \
		'OpenCV-android-sdk/sdk/native/jni/*' \
		'OpenCV-android-sdk/sdk/native/staticlibs/$(NATIVE_ABI)/*' \
		'OpenCV-android-sdk/sdk/native/3rdparty/libs/$(NATIVE_ABI)/*' \
		'OpenCV-android-sdk/sdk/native/libs/$(NATIVE_ABI)/libopencv_java4.so' \
		-d $(OPENCV_EXTRACT_DIR)
	touch $@

# --- focus-stack (out-of-tree, not vendored/committed into this repo — a
# checkout of github.com/PetteriAimonen/focus-stack, mirrored at
# github.com/jamesbiederbeck/focus-stack-arm). Built as its own standalone
# static-lib CMake project first (jni/CMakeLists.txt then links the result
# via find_library, same shape as sharerouter's llama.cpp) rather than
# add_subdirectory()'d from jni/: focus-stack's CMakeLists.txt resolves its
# own sources via CMAKE_SOURCE_DIR, which add_subdirectory() leaves pointed
# at jni/ instead of focus-stack/, so it has to be the top-level project of
# its own cmake invocation. -----------------------------------------------
FOCUS_STACK_DIR ?= $(HOME)/code/focus-stack
FOCUS_STACK_BUILD_DIR = $(FOCUS_STACK_DIR)/build-android

NDK ?= /usr/lib/android-ndk
CMAKE ?= cmake
NINJA ?= ninja
JNI_DIR = jni
JNI_BUILD_DIR = $(JNI_DIR)/build
NATIVE_LIBS_DIR = $(OUT)/lib/$(NATIVE_ABI)
NATIVE_STAMP = $(OUT)/.native_libs_stamp
FOCUS_STACK_STAMP = $(OUT)/.focus_stack_lib_stamp

all: $(ALIGNED)

$(DEX): $(SRC)
	rm -rf $(CLASSES)
	mkdir -p $(CLASSES)
	javac -source 8 -target 8 -classpath $(ANDROID_JAR) -d $(CLASSES) $(SRC)
	d8 --lib $(ANDROID_JAR) --output $(OUT) $$(find $(CLASSES) -name "*.class")

$(RESZIP): $(shell find res -type f)
	mkdir -p $(OUT)
	$(AAPT2) compile --dir res -o $(RESZIP)

# Builds focus-stack itself for arm64-v8a, out-of-tree (mirrors
# sharerouter's "build llama.cpp for arm64-v8a" step). Not part of this
# repo's own build graph beyond this one invocation — a stale checkout at
# $(FOCUS_STACK_DIR) is the contributor's responsibility to update, same as
# LLAMA_CPP_DIR.
$(FOCUS_STACK_STAMP): $(OPENCV_STAMP)
	mkdir -p $(OUT)
	$(CMAKE) -B $(FOCUS_STACK_BUILD_DIR) -G Ninja -S $(FOCUS_STACK_DIR) \
		-DCMAKE_TOOLCHAIN_FILE=$(NDK)/build/cmake/android.toolchain.cmake \
		-DANDROID_ABI=$(NATIVE_ABI) \
		-DANDROID_PLATFORM=android-24 \
		-DANDROID_STL=c++_static \
		-DCMAKE_BUILD_TYPE=Release \
		-DBUILD_TESTS=OFF \
		-DOpenCV_DIR=$(abspath $(OPENCV_JNI_DIR))
	$(NINJA) -C $(FOCUS_STACK_BUILD_DIR) focus-stack_lib
	touch $@

# c++_static (not c++_shared, unlike sharerouter's llama.cpp) since
# everything we link — OpenCV's static module libs and focus-stack_lib —
# is static too: the whole dependency graph is self-contained inside
# libfocusstack_jni.so, so there is no libc++_shared.so to separately pull
# from the NDK sysroot and package, unlike aphis/sharerouter.
$(NATIVE_STAMP): $(JNI_DIR)/focusstack_jni.cpp $(JNI_DIR)/CMakeLists.txt $(FOCUS_STACK_STAMP)
	$(CMAKE) -B $(JNI_BUILD_DIR) -G Ninja -S $(JNI_DIR) \
		-DCMAKE_TOOLCHAIN_FILE=$(NDK)/build/cmake/android.toolchain.cmake \
		-DANDROID_ABI=$(NATIVE_ABI) \
		-DANDROID_PLATFORM=android-24 \
		-DANDROID_STL=c++_static \
		-DCMAKE_BUILD_TYPE=Release \
		-DOpenCV_DIR=$(abspath $(OPENCV_JNI_DIR)) \
		-DFOCUS_STACK_DIR=$(FOCUS_STACK_DIR)
	$(NINJA) -C $(JNI_BUILD_DIR)
	mkdir -p $(NATIVE_LIBS_DIR)
	cp $(JNI_BUILD_DIR)/libfocusstack_jni.so $(NATIVE_LIBS_DIR)/
	$(NDK)/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip $(NATIVE_LIBS_DIR)/*.so
	touch $@

$(UNSIGNED): $(DEX) $(RESZIP) $(NATIVE_STAMP) AndroidManifest.xml
	$(AAPT2) link \
		-I $(ANDROID_JAR) \
		--manifest AndroidManifest.xml \
		--min-sdk-version $(MIN_SDK) \
		--target-sdk-version $(TARGET_SDK) \
		$(RESZIP) \
		-o $(UNSIGNED)
	zip -j -u $(UNSIGNED) $(DEX)
	(cd $(OUT) && zip -u ../$(UNSIGNED) lib/$(NATIVE_ABI)/*.so)

# -f overwrites an existing aligned.apk; without it a rebuild fails once one
# exists.
$(ALIGNED): $(UNSIGNED)
	zipalign -f -p 4 $(UNSIGNED) $(ALIGNED)
	apksigner sign \
		--ks $(KEYSTORE) \
		--ks-pass pass:android \
		--key-pass pass:android \
		$(ALIGNED)

install: $(ALIGNED)
	@test -n "$(DEVICE)" || { echo "No device: connect one, or pass DEVICE=<ip>:<port>"; exit 1; }
	$(ADB) connect $(DEVICE) || true
	$(ADB) -s $(DEVICE) install -r $(ALIGNED)

run: install
	$(ADB) -s $(DEVICE) shell am start -n $(APP_ID)/.MainActivity

keystore:
	keytool -genkeypair \
		-keystore $(KEYSTORE) \
		-alias androiddebugkey \
		-keyalg RSA \
		-keysize 2048 \
		-validity 10000 \
		-storepass android \
		-keypass android \
		-dname "CN=Android Debug,O=Android,C=US"

logcat:
	$(ADB) -s $(DEVICE) logcat --pid=$$($(ADB) -s $(DEVICE) shell pidof -s $(APP_ID))

clean:
	rm -rf out *.apk *.apk.idsig $(JNI_BUILD_DIR)

.PHONY: all install run keystore logcat clean
