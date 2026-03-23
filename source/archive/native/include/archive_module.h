#pragma once
#include <string>
#include <vector>
#include <cstdint>

namespace archive {

struct ArchiveEntry {
    std::string name;
    uint64_t size;
    uint64_t compressedSize;
    bool isDirectory;
};

class ArchiveModule {
public:
    // ZIP operations (with optional password encryption)
    static bool compressZip(const std::vector<std::string>& inputPaths,
                            const std::string& outputPath,
                            const std::string& password = "");
    static bool decompressZip(const std::string& archivePath,
                              const std::string& outputDir,
                              const std::string& password = "");

    // TAR operations
    static bool compressTar(const std::vector<std::string>& inputPaths,
                            const std::string& outputPath);
    static bool decompressTar(const std::string& archivePath,
                              const std::string& outputDir);

    // GZ operations (single file)
    static bool compressGz(const std::string& inputPath,
                           const std::string& outputPath);
    static bool decompressGz(const std::string& archivePath,
                             const std::string& outputPath);

    // TAR.GZ combined
    static bool compressTarGz(const std::vector<std::string>& inputPaths,
                              const std::string& outputPath);
    static bool decompressTarGz(const std::string& archivePath,
                                const std::string& outputDir);

    // List contents of archive
    static std::vector<ArchiveEntry> listContents(const std::string& archivePath);

private:
    static void encryptDecryptBuffer(std::vector<uint8_t>& data, const std::string& password);
    static bool addFileToTar(FILE* tarFile, const std::string& filePath, const std::string& arcName);
    static std::string getBaseName(const std::string& path);
    static bool mkdirRecursive(const std::string& path);
};

} // namespace archive
