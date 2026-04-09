#include <jni.h>
#include <string>
#include <vector>
#include <sstream>
#include "archive_module.h"
#include "sandbox_guard_jni.h"

#define JNI_METHOD(ret, name) \
    extern "C" JNIEXPORT ret JNICALL Java_ai_rorsch_pandagenie_nativelib_ArchiveLib_##name

static std::string jstringToString(JNIEnv* env, jstring jstr) {
    if (!jstr) return "";
    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return result;
}

static std::vector<std::string> jarrayToVector(JNIEnv* env, jobjectArray arr) {
    std::vector<std::string> result;
    if (!arr) return result;
    int len = env->GetArrayLength(arr);
    for (int i = 0; i < len; i++) {
        auto js = (jstring)env->GetObjectArrayElement(arr, i);
        result.push_back(jstringToString(env, js));
    }
    return result;
}

static std::string escapeJson(const std::string& s) {
    std::string out;
    for (char c : s) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '/': out += "\\/"; break;
            default: out += c;
        }
    }
    return out;
}

JNI_METHOD(jboolean, compressZip)(JNIEnv* env, jobject, jobjectArray inputPaths, jstring outputPath, jstring password) {
    try {
        auto paths = jarrayToVector(env, inputPaths);
        auto out = jstringToString(env, outputPath);
        auto pwd = jstringToString(env, password);
        return archive::ArchiveModule::compressZip(paths, out, pwd) ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception& e) {
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), e.what());
        return JNI_FALSE;
    }
}

JNI_METHOD(jboolean, decompressZip)(JNIEnv* env, jobject, jstring archivePath, jstring outputDir, jstring password) {
    try {
        auto arc = jstringToString(env, archivePath);
        auto out = jstringToString(env, outputDir);
        auto pwd = jstringToString(env, password);
        return archive::ArchiveModule::decompressZip(arc, out, pwd) ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception& e) {
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), e.what());
        return JNI_FALSE;
    }
}

JNI_METHOD(jboolean, compressTar)(JNIEnv* env, jobject, jobjectArray inputPaths, jstring outputPath) {
    try {
        auto paths = jarrayToVector(env, inputPaths);
        auto out = jstringToString(env, outputPath);
        return archive::ArchiveModule::compressTar(paths, out) ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception& e) {
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), e.what());
        return JNI_FALSE;
    }
}

JNI_METHOD(jboolean, decompressTar)(JNIEnv* env, jobject, jstring archivePath, jstring outputDir) {
    try {
        return archive::ArchiveModule::decompressTar(
            jstringToString(env, archivePath), jstringToString(env, outputDir)) ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception& e) {
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), e.what());
        return JNI_FALSE;
    }
}

JNI_METHOD(jboolean, compressGz)(JNIEnv* env, jobject, jstring inputPath, jstring outputPath) {
    try {
        return archive::ArchiveModule::compressGz(
            jstringToString(env, inputPath), jstringToString(env, outputPath)) ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception& e) {
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), e.what());
        return JNI_FALSE;
    }
}

JNI_METHOD(jboolean, decompressGz)(JNIEnv* env, jobject, jstring archivePath, jstring outputPath) {
    try {
        return archive::ArchiveModule::decompressGz(
            jstringToString(env, archivePath), jstringToString(env, outputPath)) ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception& e) {
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), e.what());
        return JNI_FALSE;
    }
}

JNI_METHOD(jboolean, compressTarGz)(JNIEnv* env, jobject, jobjectArray inputPaths, jstring outputPath) {
    try {
        auto paths = jarrayToVector(env, inputPaths);
        return archive::ArchiveModule::compressTarGz(paths, jstringToString(env, outputPath)) ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception& e) {
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), e.what());
        return JNI_FALSE;
    }
}

JNI_METHOD(jboolean, decompressTarGz)(JNIEnv* env, jobject, jstring archivePath, jstring outputDir) {
    try {
        return archive::ArchiveModule::decompressTarGz(
            jstringToString(env, archivePath), jstringToString(env, outputDir)) ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception& e) {
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), e.what());
        return JNI_FALSE;
    }
}

JNI_METHOD(jstring, listContents)(JNIEnv* env, jobject, jstring archivePath) {
    try {
        auto entries = archive::ArchiveModule::listContents(jstringToString(env, archivePath));
        std::ostringstream json;
        json << "[";
        for (size_t i = 0; i < entries.size(); i++) {
            if (i > 0) json << ",";
            json << "{\"name\":\"" << escapeJson(entries[i].name) << "\""
                 << ",\"size\":" << entries[i].size
                 << ",\"compressedSize\":" << entries[i].compressedSize
                 << ",\"isDirectory\":" << (entries[i].isDirectory ? "true" : "false")
                 << "}";
        }
        json << "]";
        return env->NewStringUTF(json.str().c_str());
    } catch (const std::exception& e) {
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), e.what());
        return nullptr;
    }
}

JNI_METHOD(void, nativeConfigureSandbox)(JNIEnv* env, jclass, jobjectArray readPaths, jobjectArray writePaths) {
    sandbox_jni::configureSandbox(env, readPaths, writePaths);
}

JNI_METHOD(void, nativeClearSandbox)(JNIEnv* env, jclass) {
    sandbox_jni::clearSandbox();
}
