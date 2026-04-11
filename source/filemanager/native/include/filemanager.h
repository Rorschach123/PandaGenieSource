#ifndef FILEMANAGER_H
#define FILEMANAGER_H

#include <string>
#include <vector>
#include <cstdint>

namespace filemgr {

struct FileInfo {
    std::string name;
    std::string fullPath;
    bool isDirectory;
    int64_t size;
    int64_t lastModified;
};

class FileManager {
public:
    static std::vector<FileInfo> listDirectory(const std::string& path);
    static bool createDirectory(const std::string& path);
    static bool deleteFile(const std::string& path);
    static bool deleteDirectory(const std::string& path, bool recursive);
    static bool copyFile(const std::string& src, const std::string& dst);
    static bool moveFile(const std::string& src, const std::string& dst);
    static bool renameFile(const std::string& oldPath, const std::string& newPath);
    static FileInfo getFileInfo(const std::string& path);
    static std::vector<std::string> searchFiles(const std::string& dir,
                                                const std::string& pattern,
                                                bool recursive);
    static std::string readTextFile(const std::string& path);
    static bool writeTextFile(const std::string& path, const std::string& content);
    static bool fileExists(const std::string& path);
    static long long getFileSize(const std::string& path);

private:
    static bool wildcardMatch(const std::string& text, const std::string& pattern);
    static bool multiPatternMatch(const std::string& text, const std::string& pattern);
    static void searchFilesRecursive(const std::string& dir,
                                     const std::string& pattern,
                                     std::vector<std::string>& results,
                                     int depth);
    static bool deleteDirectoryRecursive(const std::string& path, int depth);
};

} // namespace filemgr

#endif // FILEMANAGER_H
