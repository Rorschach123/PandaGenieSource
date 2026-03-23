#include <jni.h>
#include <string>
#include <sstream>
#include "filemanager.h"

#define JNI_METHOD(ret, name) \
    extern "C" JNIEXPORT ret JNICALL Java_ai_rorsch_pandagenie_nativelib_FileManagerLib_##name

namespace {

std::string jstringToString(JNIEnv* env, jstring jstr) {
    if (!jstr) return "";
    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return result;
}

std::string escapeJson(const std::string& s) {
    std::string out;
    out.reserve(s.size() + 16);
    for (char c : s) {
        switch (c) {
            case '"':  out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\b': out += "\\b";  break;
            case '\f': out += "\\f";  break;
            case '\n': out += "\\n";  break;
            case '\r': out += "\\r";  break;
            case '\t': out += "\\t";  break;
            default:
                if (static_cast<unsigned char>(c) < 0x20) {
                    char buf[8];
                    snprintf(buf, sizeof(buf), "\\u%04x", static_cast<unsigned char>(c));
                    out += buf;
                } else {
                    out += c;
                }
                break;
        }
    }
    return out;
}

std::string fileInfoToJson(const filemgr::FileInfo& info) {
    std::ostringstream ss;
    ss << "{\"name\":\"" << escapeJson(info.name) << "\""
       << ",\"fullPath\":\"" << escapeJson(info.fullPath) << "\""
       << ",\"isDirectory\":" << (info.isDirectory ? "true" : "false")
       << ",\"size\":" << info.size
       << ",\"lastModified\":" << info.lastModified
       << "}";
    return ss.str();
}

void throwException(JNIEnv* env, const char* msg) {
    jclass cls = env->FindClass("java/lang/RuntimeException");
    if (cls) {
        env->ThrowNew(cls, msg);
    }
}

} // anonymous namespace

JNI_METHOD(jstring, nativeListDirectory)(JNIEnv* env, jobject, jstring jpath) {
    try {
        std::string path = jstringToString(env, jpath);
        auto entries = filemgr::FileManager::listDirectory(path);

        std::ostringstream ss;
        ss << "[";
        for (size_t i = 0; i < entries.size(); ++i) {
            if (i > 0) ss << ",";
            ss << fileInfoToJson(entries[i]);
        }
        ss << "]";

        return env->NewStringUTF(ss.str().c_str());
    } catch (const std::exception& e) {
        throwException(env, e.what());
        return nullptr;
    }
}

JNI_METHOD(jboolean, nativeCreateDirectory)(JNIEnv* env, jobject, jstring jpath) {
    try {
        std::string path = jstringToString(env, jpath);
        return static_cast<jboolean>(filemgr::FileManager::createDirectory(path));
    } catch (const std::exception& e) {
        throwException(env, e.what());
        return JNI_FALSE;
    }
}

JNI_METHOD(jboolean, nativeDeleteFile)(JNIEnv* env, jobject, jstring jpath) {
    try {
        std::string path = jstringToString(env, jpath);
        return static_cast<jboolean>(filemgr::FileManager::deleteFile(path));
    } catch (const std::exception& e) {
        throwException(env, e.what());
        return JNI_FALSE;
    }
}

JNI_METHOD(jboolean, nativeDeleteDirectory)(JNIEnv* env, jobject, jstring jpath, jboolean recursive) {
    try {
        std::string path = jstringToString(env, jpath);
        return static_cast<jboolean>(filemgr::FileManager::deleteDirectory(path, recursive));
    } catch (const std::exception& e) {
        throwException(env, e.what());
        return JNI_FALSE;
    }
}

JNI_METHOD(jboolean, nativeCopyFile)(JNIEnv* env, jobject, jstring jsrc, jstring jdst) {
    try {
        std::string src = jstringToString(env, jsrc);
        std::string dst = jstringToString(env, jdst);
        return static_cast<jboolean>(filemgr::FileManager::copyFile(src, dst));
    } catch (const std::exception& e) {
        throwException(env, e.what());
        return JNI_FALSE;
    }
}

JNI_METHOD(jboolean, nativeMoveFile)(JNIEnv* env, jobject, jstring jsrc, jstring jdst) {
    try {
        std::string src = jstringToString(env, jsrc);
        std::string dst = jstringToString(env, jdst);
        return static_cast<jboolean>(filemgr::FileManager::moveFile(src, dst));
    } catch (const std::exception& e) {
        throwException(env, e.what());
        return JNI_FALSE;
    }
}

JNI_METHOD(jboolean, nativeRenameFile)(JNIEnv* env, jobject, jstring jold, jstring jnew) {
    try {
        std::string oldPath = jstringToString(env, jold);
        std::string newPath = jstringToString(env, jnew);
        return static_cast<jboolean>(filemgr::FileManager::renameFile(oldPath, newPath));
    } catch (const std::exception& e) {
        throwException(env, e.what());
        return JNI_FALSE;
    }
}

JNI_METHOD(jstring, nativeGetFileInfo)(JNIEnv* env, jobject, jstring jpath) {
    try {
        std::string path = jstringToString(env, jpath);
        filemgr::FileInfo info = filemgr::FileManager::getFileInfo(path);
        std::string json = fileInfoToJson(info);
        return env->NewStringUTF(json.c_str());
    } catch (const std::exception& e) {
        throwException(env, e.what());
        return nullptr;
    }
}

JNI_METHOD(jstring, nativeSearchFiles)(JNIEnv* env, jobject, jstring jdir, jstring jpattern, jboolean recursive) {
    try {
        std::string dir = jstringToString(env, jdir);
        std::string pattern = jstringToString(env, jpattern);
        auto results = filemgr::FileManager::searchFiles(dir, pattern, recursive);

        std::ostringstream ss;
        ss << "[";
        for (size_t i = 0; i < results.size(); ++i) {
            if (i > 0) ss << ",";
            ss << "\"" << escapeJson(results[i]) << "\"";
        }
        ss << "]";

        return env->NewStringUTF(ss.str().c_str());
    } catch (const std::exception& e) {
        throwException(env, e.what());
        return nullptr;
    }
}

JNI_METHOD(jstring, nativeReadTextFile)(JNIEnv* env, jobject, jstring jpath) {
    try {
        std::string path = jstringToString(env, jpath);
        std::string content = filemgr::FileManager::readTextFile(path);
        return env->NewStringUTF(content.c_str());
    } catch (const std::exception& e) {
        throwException(env, e.what());
        return nullptr;
    }
}

JNI_METHOD(jboolean, nativeWriteTextFile)(JNIEnv* env, jobject, jstring jpath, jstring jcontent) {
    try {
        std::string path = jstringToString(env, jpath);
        std::string content = jstringToString(env, jcontent);
        return static_cast<jboolean>(filemgr::FileManager::writeTextFile(path, content));
    } catch (const std::exception& e) {
        throwException(env, e.what());
        return JNI_FALSE;
    }
}

JNI_METHOD(jboolean, nativeFileExists)(JNIEnv* env, jobject, jstring jpath) {
    try {
        std::string path = jstringToString(env, jpath);
        return static_cast<jboolean>(filemgr::FileManager::fileExists(path));
    } catch (const std::exception& e) {
        throwException(env, e.what());
        return JNI_FALSE;
    }
}

JNI_METHOD(jlong, nativeGetFileSize)(JNIEnv* env, jobject, jstring jpath) {
    try {
        std::string path = jstringToString(env, jpath);
        return static_cast<jlong>(filemgr::FileManager::getFileSize(path));
    } catch (const std::exception& e) {
        throwException(env, e.what());
        return -1;
    }
}
