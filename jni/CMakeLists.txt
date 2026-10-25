cmake_minimum_required(VERSION 3.22)
project(focusstack_jni)

# Path to a focus-stack checkout (github.com/PetteriAimonen/focus-stack,
# locally mirrored/pushed via github.com/jamesbiederbeck/focus-stack-arm)
# that has already been configured/built for arm64-v8a into
# ${FOCUS_STACK_DIR}/build-android (see the Makefile's $(FOCUS_STACK_STAMP)
# target). We link against its prebuilt libfocus-stack_lib.a rather than
# add_subdirectory()-ing the source: focus-stack's own CMakeLists.txt
# derives its SRC_DIR from CMAKE_SOURCE_DIR (the top-level project's source
# dir), which add_subdirectory() leaves pointed at this directory instead
# of focus-stack's own — the file-not-found errors that produces are a
# property of that CMakeLists.txt, not something to patch in a repo we
# don't own, so a separate out-of-tree build (mirroring sharerouter's
# llama.cpp precedent) sidesteps it entirely.
if(NOT DEFINED FOCUS_STACK_DIR)
    set(FOCUS_STACK_DIR "$ENV{HOME}/code/focus-stack")
endif()
set(FOCUS_STACK_BUILD_DIR "${FOCUS_STACK_DIR}/build-android")

find_package(OpenCV REQUIRED COMPONENTS core imgproc photo video imgcodecs)

add_library(focusstack_jni SHARED focusstack_jni.cpp)

target_include_directories(focusstack_jni PRIVATE
    "${FOCUS_STACK_DIR}/src"
)

find_library(FOCUS_STACK_LIB focus-stack_lib
    PATHS "${FOCUS_STACK_BUILD_DIR}"
    NO_DEFAULT_PATH NO_CMAKE_FIND_ROOT_PATH)
if(NOT FOCUS_STACK_LIB)
    message(FATAL_ERROR "libfocus-stack_lib.a not found in ${FOCUS_STACK_BUILD_DIR} — build focus-stack for arm64-v8a first")
endif()

target_link_libraries(focusstack_jni PRIVATE "${FOCUS_STACK_LIB}" ${OpenCV_LIBS} log)
