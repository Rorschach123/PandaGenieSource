#include "filemanager.h"

#include <dirent.h>
#include <sys/stat.h>
#include <unistd.h>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <sstream>
#include <algorithm>
#include <cerrno>
#include <android/log.h>

#define LOG_TAG "FileManagerNative"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

static constexpr size_t MAX_SEARCH_RESULTS = 5000;
static constexpr int MAX_RECURSION_DEPTH = 20;

namespace filemgr {

static char toLowerChar(char c) {
    return (c >= 'A' && c <= 'Z') ? (c + ('a' - 'A')) : c;
}

bool FileManager::wildcardMatch(const std::string& text, const std::string& pattern) {
    size_t ti = 0, pi = 0;
    size_t starIdx = std::string::npos;
    size_t matchIdx = 0;

    while (ti < text.size()) {
        if (pi < pattern.size() && (pattern[pi] == '?' || toLowerChar(pattern[pi]) == toLowerChar(text[ti]))) {
            ++ti;
            ++pi;
        } else if (pi < pattern.size() && pattern[pi] == '*') {
            starIdx = pi;
            matchIdx = ti;
            ++pi;
        } else if (starIdx != std::string::npos) {
            pi = starIdx + 1;
            ++matchIdx;
            ti = matchIdx;
        } else {
            return false;
        }
    }

    while (pi < pattern.size() && pattern[pi] == '*') {
        ++pi;
    }

    return pi == pattern.size();
}

bool FileManager::multiPatternMatch(const std::string& text, const std::string& pattern) {
    if (pattern.find(',') == std::string::npos) {
        return wildcardMatch(text, pattern);
    }
    std::string p = pattern;
    size_t pos = 0;
    while (pos < p.size()) {
        size_t comma = p.find(',', pos);
        std::string sub = (comma == std::string::npos)
            ? p.substr(pos) : p.substr(pos, comma - pos);
        while (!sub.empty() && sub.front() == ' ') sub.erase(sub.begin());
        while (!sub.empty() && sub.back() == ' ') sub.pop_back();
        if (!sub.empty() && wildcardMatch(text, sub)) return true;
        if (comma == std::string::npos) break;
        pos = comma + 1;
    }
    return false;
}

void FileManager::searchFilesRecursive(const std::string& dir,
                                       const std::string& pattern,
                                       std::vector<std::string>& results,
                                       int depth) {
    if (results.size() >= MAX_SEARCH_RESULTS) return;
    if (depth >= MAX_RECURSION_DEPTH) return;

    DIR* d = opendir(dir.c_str());
    if (!d) {
        if (depth < 3) {
            LOGW("opendir failed: %s errno=%d (%s)", dir.c_str(), errno, strerror(errno));
        }
        return;
    }

    struct dirent* entry;
    while ((entry = readdir(d)) != nullptr && results.size() < MAX_SEARCH_RESULTS) {
        std::string name = entry->d_name;
        if (name == "." || name == "..") continue;

        std::string fullPath = dir;
        if (!fullPath.empty() && fullPath.back() != '/') fullPath += '/';
        fullPath += name;

        struct stat st;
        if (stat(fullPath.c_str(), &st) != 0) continue;

        if (multiPatternMatch(name, pattern)) {
            results.push_back(fullPath);
        }

        if (S_ISDIR(st.st_mode)) {
            searchFilesRecursive(fullPath, pattern, results, depth + 1);
        }
    }

    closedir(d);
}

bool FileManager::deleteDirectoryRecursive(const std::string& path, int depth) {
    if (depth >= MAX_RECURSION_DEPTH) {
        LOGW("deleteDirectoryRecursive: max depth reached at %s", path.c_str());
        return false;
    }

    DIR* d = opendir(path.c_str());
    if (!d) return false;

    struct dirent* entry;
    bool ok = true;

    while ((entry = readdir(d)) != nullptr) {
        std::string name = entry->d_name;
        if (name == "." || name == "..") continue;

        std::string fullPath = path;
        if (!fullPath.empty() && fullPath.back() != '/') fullPath += '/';
        fullPath += name;

        struct stat st;
        if (stat(fullPath.c_str(), &st) != 0) {
            ok = false;
            continue;
        }

        if (S_ISDIR(st.st_mode)) {
            if (!deleteDirectoryRecursive(fullPath, depth + 1)) ok = false;
        } else {
            if (::remove(fullPath.c_str()) != 0) ok = false;
        }
    }

    closedir(d);

    if (ok) {
        ok = (rmdir(path.c_str()) == 0);
    }

    return ok;
}

std::vector<FileInfo> FileManager::listDirectory(const std::string& path) {
    std::vector<FileInfo> entries;

    DIR* d = opendir(path.c_str());
    if (!d) return entries;

    struct dirent* entry;
    while ((entry = readdir(d)) != nullptr) {
        std::string name = entry->d_name;
        if (name == "." || name == "..") continue;

        std::string fullPath = path;
        if (!fullPath.empty() && fullPath.back() != '/') fullPath += '/';
        fullPath += name;

        struct stat st;
        if (stat(fullPath.c_str(), &st) != 0) continue;

        FileInfo info;
        info.name = name;
        info.fullPath = fullPath;
        info.isDirectory = S_ISDIR(st.st_mode);
        info.size = static_cast<int64_t>(st.st_size);
        info.lastModified = static_cast<int64_t>(st.st_mtime);
        entries.push_back(info);
    }

    closedir(d);
    return entries;
}

bool FileManager::createDirectory(const std::string& path) {
    return mkdir(path.c_str(), 0755) == 0 || errno == EEXIST;
}

bool FileManager::deleteFile(const std::string& path) {
    return ::remove(path.c_str()) == 0;
}

bool FileManager::deleteDirectory(const std::string& path, bool recursive) {
    if (recursive) {
        return deleteDirectoryRecursive(path, 0);
    }
    return rmdir(path.c_str()) == 0;
}

bool FileManager::copyFile(const std::string& src, const std::string& dst) {
    FILE* in = fopen(src.c_str(), "rb");
    if (!in) return false;

    FILE* out = fopen(dst.c_str(), "wb");
    if (!out) {
        fclose(in);
        return false;
    }

    static constexpr size_t BUF_SIZE = 8192;
    char buf[BUF_SIZE];
    size_t bytesRead;
    bool ok = true;

    while ((bytesRead = fread(buf, 1, BUF_SIZE, in)) > 0) {
        if (fwrite(buf, 1, bytesRead, out) != bytesRead) {
            ok = false;
            break;
        }
    }

    if (ferror(in)) ok = false;

    fclose(in);
    fclose(out);

    if (!ok) {
        ::remove(dst.c_str());
    }

    return ok;
}

bool FileManager::moveFile(const std::string& src, const std::string& dst) {
    if (::rename(src.c_str(), dst.c_str()) == 0) {
        return true;
    }
    if (!copyFile(src, dst)) {
        return false;
    }
    ::remove(src.c_str());
    return true;
}

bool FileManager::renameFile(const std::string& oldPath, const std::string& newPath) {
    return ::rename(oldPath.c_str(), newPath.c_str()) == 0;
}

FileInfo FileManager::getFileInfo(const std::string& path) {
    FileInfo info;
    info.size = 0;
    info.lastModified = 0;
    info.isDirectory = false;

    struct stat st;
    if (stat(path.c_str(), &st) != 0) {
        return info;
    }

    size_t sep = path.find_last_of('/');
    if (sep != std::string::npos) {
        info.name = path.substr(sep + 1);
    } else {
        info.name = path;
    }

    info.fullPath = path;
    info.isDirectory = S_ISDIR(st.st_mode);
    info.size = static_cast<int64_t>(st.st_size);
    info.lastModified = static_cast<int64_t>(st.st_mtime);

    return info;
}

std::vector<std::string> FileManager::searchFiles(const std::string& dir,
                                                   const std::string& pattern,
                                                   bool recursive) {
    std::vector<std::string> results;

    if (recursive) {
        searchFilesRecursive(dir, pattern, results, 0);
        return results;
    }

    DIR* d = opendir(dir.c_str());
    if (!d) return results;

    struct dirent* entry;
    while ((entry = readdir(d)) != nullptr) {
        std::string name = entry->d_name;
        if (name == "." || name == "..") continue;

        if (multiPatternMatch(name, pattern)) {
            std::string fullPath = dir;
            if (!fullPath.empty() && fullPath.back() != '/') fullPath += '/';
            fullPath += name;
            results.push_back(fullPath);
        }
    }

    closedir(d);
    return results;
}

std::string FileManager::readTextFile(const std::string& path) {
    std::ifstream file(path, std::ios::in);
    if (!file.is_open()) return "";

    std::ostringstream ss;
    ss << file.rdbuf();
    return ss.str();
}

bool FileManager::writeTextFile(const std::string& path, const std::string& content) {
    std::ofstream file(path, std::ios::out | std::ios::trunc);
    if (!file.is_open()) return false;

    file << content;
    return file.good();
}

bool FileManager::fileExists(const std::string& path) {
    struct stat st;
    return stat(path.c_str(), &st) == 0;
}

long long FileManager::getFileSize(const std::string& path) {
    struct stat st;
    if (stat(path.c_str(), &st) != 0) return -1;
    return static_cast<long long>(st.st_size);
}

} // namespace filemgr
