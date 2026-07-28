#include "ds_state.h"

#include <jni.h>
#include <lsplant.hpp>

namespace {

void ThrowIllegalState(JNIEnv* env, const char* message) {
    if (env == nullptr) return;
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls != nullptr) {
        env->ThrowNew(cls, message);
    }
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    (void)vm;
    DS_LOGW("audit:ds_java_hooks JNI_OnLoad lazy LSPlant init");
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_devicespooflab_hooks_xposed_XposedBridge_nativeHookMethod(
        JNIEnv* env, jclass /*cls*/, jobject target, jobject hooker,
        jobject callback) {
    if (target == nullptr || hooker == nullptr || callback == nullptr) {
        DS_LOGW("nativeHookMethod: null argument");
        return nullptr;
    }
    if (!ds::InitLSPlant(env)) {
        DS_LOGW("nativeHookMethod: LSPlant init failed");
        ThrowIllegalState(env, "LSPlant init failed");
        return nullptr;
    }
    jobject backup = lsplant::Hook(env, target, hooker, callback);
    if (backup == nullptr) {
        DS_LOGW("nativeHookMethod: lsplant::Hook returned null");
        ThrowIllegalState(env, "LSPlant hook returned null");
        return nullptr;
    }
    return backup;
}
