// JNI bridge between design.antics.focusstack.FocusStackBridge (Java) and
// the vendored focus-stack library (focusstack::FocusStack). focus-stack's
// own CMakeLists.txt builds focus-stack_lib with main.cc excluded, so this
// shim talks to its C++ API (focusstack.hh) directly rather than
// reimplementing CLI argument parsing.

#include <android/log.h>
#include <jni.h>
#include <string>
#include <vector>

#include "focusstack.hh"

#define LOG_TAG "focusstack_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using focusstack::FocusStack;

namespace {

std::string jstringToStd(JNIEnv *env, jstring s) {
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(s, chars);
    return result;
}

std::vector<std::string> jstringArrayToStd(JNIEnv *env, jobjectArray arr) {
    std::vector<std::string> result;
    jsize n = env->GetArrayLength(arr);
    result.reserve(n);
    for (jsize i = 0; i < n; i++) {
        jstring s = static_cast<jstring>(env->GetObjectArrayElement(arr, i));
        result.push_back(jstringToStd(env, s));
        env->DeleteLocalRef(s);
    }
    return result;
}

}  // namespace

// Returns null on success, or an error message string on failure.
extern "C" JNIEXPORT jstring JNICALL
Java_design_antics_focusstack_FocusStackBridge_nativeStack(
        JNIEnv *env, jclass, jobjectArray jInputPaths, jstring jOutputPath,
        jboolean jDisableOpenCl) {

    std::vector<std::string> inputs = jstringArrayToStd(env, jInputPaths);
    std::string output = jstringToStd(env, jOutputPath);

    LOGI("nativeStack: %zu inputs -> %s", inputs.size(), output.c_str());

    FocusStack stack;
    stack.set_log_callback([](FocusStack::log_level_t level, std::string msg) {
        if (level >= FocusStack::LOG_ERROR) {
            LOGE("%s", msg.c_str());
        } else {
            LOGI("%s", msg.c_str());
        }
    });

    stack.set_inputs(inputs);
    stack.set_output(output);
    stack.set_disable_opencl(jDisableOpenCl);

    stack.reset();
    stack.start();
    stack.do_final_merge();
    stack.reset(true);

    bool status = false;
    std::string errmsg;
    stack.wait_done(status, errmsg, -1);

    if (status) {
        return nullptr;
    }
    LOGE("stack failed: %s", errmsg.c_str());
    return env->NewStringUTF(errmsg.empty() ? "focus-stack failed" : errmsg.c_str());
}
