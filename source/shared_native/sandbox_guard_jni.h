#ifndef SANDBOX_GUARD_JNI_H
#define SANDBOX_GUARD_JNI_H

#include <jni.h>
#include <string>
#include <vector>
#include "sandbox_guard.h"

namespace sandbox_jni {

inline std::vector<std::string> toStringVec(JNIEnv* env, jobjectArray arr) {
    std::vector<std::string> result;
    if (!arr) return result;
    int len = env->GetArrayLength(arr);
    result.reserve(len);
    for (int i = 0; i < len; i++) {
        auto js = (jstring)env->GetObjectArrayElement(arr, i);
        if (js) {
            const char* chars = env->GetStringUTFChars(js, nullptr);
            result.emplace_back(chars);
            env->ReleaseStringUTFChars(js, chars);
        }
    }
    return result;
}

inline void configureSandbox(JNIEnv* env, jobjectArray readPaths, jobjectArray writePaths) {
    sandbox::SandboxGuard::configure(toStringVec(env, readPaths), toStringVec(env, writePaths));
}

inline void clearSandbox() {
    sandbox::SandboxGuard::clear();
}

} // namespace sandbox_jni

#endif // SANDBOX_GUARD_JNI_H
