#ifndef SANDBOX_GUARD_H
#define SANDBOX_GUARD_H

#include <string>
#include <vector>

namespace sandbox {

class SandboxGuard {
public:
    static void configure(const std::vector<std::string>& readPaths,
                          const std::vector<std::string>& writePaths);
    static void clear();

    static bool checkRead(const std::string& path);
    static bool checkWrite(const std::string& path);

    static bool isConfigured();

private:
    static std::string normalizePath(const std::string& path);
    static bool isPathAllowed(const std::vector<std::string>& allowedPrefixes,
                              const std::string& path);
};

} // namespace sandbox

#endif // SANDBOX_GUARD_H
