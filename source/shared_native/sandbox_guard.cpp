#include "sandbox_guard.h"
#include <mutex>
#include <algorithm>
#include <android/log.h>

#define SG_TAG "SandboxGuard"
#define SG_LOGW(...) __android_log_print(ANDROID_LOG_WARN, SG_TAG, __VA_ARGS__)

namespace sandbox {

static std::mutex sMutex;
static std::vector<std::string> sReadPaths;
static std::vector<std::string> sWritePaths;
static bool sConfigured = false;

std::string SandboxGuard::normalizePath(const std::string& path) {
    std::string p = path;
    for (auto& c : p) {
        if (c == '\\') c = '/';
    }
    while (p.size() > 1 && p.back() == '/') {
        p.pop_back();
    }
    // Resolve /sdcard -> /storage/emulated/0 for consistent matching
    const std::string sdcard = "/sdcard";
    const std::string real = "/storage/emulated/0";
    if (p == sdcard || (p.size() > sdcard.size() && p.compare(0, sdcard.size(), sdcard) == 0 && p[sdcard.size()] == '/')) {
        p = real + p.substr(sdcard.size());
    }
    return p;
}

bool SandboxGuard::isPathAllowed(const std::vector<std::string>& allowedPrefixes,
                                  const std::string& path) {
    if (allowedPrefixes.empty()) return false;

    std::string normalized = normalizePath(path);

    for (const auto& prefix : allowedPrefixes) {
        if (prefix == "/") return true;

        std::string np = normalizePath(prefix);
        // Check if normalized path starts with the allowed prefix
        if (normalized.size() >= np.size() &&
            normalized.compare(0, np.size(), np) == 0 &&
            (normalized.size() == np.size() || normalized[np.size()] == '/')) {
            return true;
        }
    }
    return false;
}

void SandboxGuard::configure(const std::vector<std::string>& readPaths,
                              const std::vector<std::string>& writePaths) {
    std::lock_guard<std::mutex> lock(sMutex);
    sReadPaths = readPaths;
    sWritePaths = writePaths;
    sConfigured = true;
}

void SandboxGuard::clear() {
    std::lock_guard<std::mutex> lock(sMutex);
    sReadPaths.clear();
    sWritePaths.clear();
    sConfigured = false;
}

bool SandboxGuard::isConfigured() {
    std::lock_guard<std::mutex> lock(sMutex);
    return sConfigured;
}

bool SandboxGuard::checkRead(const std::string& path) {
    std::lock_guard<std::mutex> lock(sMutex);
    if (!sConfigured) return true; // not configured = no restriction (backward compat)

    // Read is allowed if path is in readPaths OR writePaths
    if (isPathAllowed(sReadPaths, path) || isPathAllowed(sWritePaths, path)) {
        return true;
    }

    SG_LOGW("READ BLOCKED: %s", path.c_str());
    return false;
}

bool SandboxGuard::checkWrite(const std::string& path) {
    std::lock_guard<std::mutex> lock(sMutex);
    if (!sConfigured) return true;

    if (isPathAllowed(sWritePaths, path)) {
        return true;
    }

    SG_LOGW("WRITE BLOCKED: %s", path.c_str());
    return false;
}

} // namespace sandbox
