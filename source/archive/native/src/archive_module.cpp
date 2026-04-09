#include "archive_module.h"
#include "miniz.h"
#include "sandbox_guard.h"
#include <zlib.h>
#include <cstdio>
#include <cstring>
#include <cerrno>
#include <ctime>
#include <cinttypes>
#include <sys/stat.h>
#include <dirent.h>
#include <unistd.h>
#include <stdexcept>
#include <algorithm>
#include <android/log.h>

#define TAG "ArchiveModule"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace archive {

// --- Helpers ---

std::string ArchiveModule::getBaseName(const std::string& path) {
    size_t pos = path.find_last_of("/\\");
    return (pos != std::string::npos) ? path.substr(pos + 1) : path;
}

bool ArchiveModule::mkdirRecursive(const std::string& path) {
    std::string tmp = path;
    for (size_t i = 1; i < tmp.size(); i++) {
        if (tmp[i] == '/') {
            tmp[i] = '\0';
            mkdir(tmp.c_str(), 0755);
            tmp[i] = '/';
        }
    }
    return mkdir(tmp.c_str(), 0755) == 0 || errno == EEXIST;
}

void ArchiveModule::encryptDecryptBuffer(std::vector<uint8_t>& data, const std::string& password) {
    if (password.empty()) return;
    size_t keyLen = password.size();
    for (size_t i = 0; i < data.size(); i++) {
        data[i] ^= password[i % keyLen];
        data[i] ^= (uint8_t)((i * 7 + 13) & 0xFF);
    }
}

// --- ZIP ---

bool ArchiveModule::compressZip(const std::vector<std::string>& inputPaths,
                                const std::string& outputPath,
                                const std::string& password) {
    for (const auto& p : inputPaths) {
        if (!sandbox::SandboxGuard::checkRead(p)) return false;
    }
    if (!sandbox::SandboxGuard::checkWrite(outputPath)) return false;

    if (inputPaths.empty()) {
        LOGE("compressZip: no input paths provided");
        return false;
    }

    // Auto-create output directory
    std::string outDir = outputPath.substr(0, outputPath.find_last_of('/'));
    if (!outDir.empty()) mkdirRecursive(outDir);

    LOGI("compressZip: output=%s, %zu files", outputPath.c_str(), inputPaths.size());
    for (size_t i = 0; i < inputPaths.size(); i++) {
        LOGI("  input[%zu] = %s", i, inputPaths[i].c_str());
        FILE* test = fopen(inputPaths[i].c_str(), "rb");
        if (!test) {
            LOGE("  -> CANNOT open for reading (errno=%d: %s)", errno, strerror(errno));
            return false;
        }
        fclose(test);
    }

    mz_zip_archive zip;
    if (!mz_zip_writer_init_file(&zip, outputPath.c_str())) {
        LOGE("Failed to create zip file: %s (errno=%d: %s)", outputPath.c_str(), errno, strerror(errno));
        return false;
    }

    for (const auto& path : inputPaths) {
        std::string arcName = getBaseName(path);
        if (!mz_zip_writer_add_file(&zip, arcName.c_str(), path.c_str())) {
            LOGE("Failed to add file to zip: %s (errno=%d: %s)", path.c_str(), errno, strerror(errno));
            mz_zip_writer_end(&zip);
            unlink(outputPath.c_str());
            return false;
        }
    }

    mz_zip_writer_finalize_archive(&zip);
    mz_zip_writer_end(&zip);

    if (!password.empty()) {
        FILE* f = fopen(outputPath.c_str(), "rb");
        if (!f) return false;
        fseek(f, 0, SEEK_END);
        long sz = ftell(f);
        fseek(f, 0, SEEK_SET);
        std::vector<uint8_t> data(sz);
        fread(data.data(), 1, sz, f);
        fclose(f);

        encryptDecryptBuffer(data, password);

        f = fopen(outputPath.c_str(), "wb");
        if (!f) return false;
        uint8_t marker[] = {'E', 'N', 'C', 1};
        fwrite(marker, 1, 4, f);
        fwrite(data.data(), 1, data.size(), f);
        fclose(f);
    }

    LOGI("Zip created: %s (%zu files)", outputPath.c_str(), inputPaths.size());
    return true;
}

bool ArchiveModule::decompressZip(const std::string& archivePath,
                                  const std::string& outputDir,
                                  const std::string& password) {
    if (!sandbox::SandboxGuard::checkRead(archivePath)) return false;
    if (!sandbox::SandboxGuard::checkWrite(outputDir)) return false;

    LOGI("decompressZip: archive=%s output=%s", archivePath.c_str(), outputDir.c_str());

    FILE* testFile = fopen(archivePath.c_str(), "rb");
    if (!testFile) {
        LOGE("Cannot open archive file: %s (errno=%d)", archivePath.c_str(), errno);
        return false;
    }
    fclose(testFile);

    std::string actualPath = archivePath;

    if (!password.empty()) {
        FILE* f = fopen(archivePath.c_str(), "rb");
        if (!f) return false;
        uint8_t marker[4];
        fread(marker, 1, 4, f);
        bool encrypted = (marker[0] == 'E' && marker[1] == 'N' && marker[2] == 'C' && marker[3] == 1);
        if (encrypted) {
            fseek(f, 0, SEEK_END);
            long sz = ftell(f) - 4;
            fseek(f, 4, SEEK_SET);
            std::vector<uint8_t> data(sz);
            fread(data.data(), 1, sz, f);
            fclose(f);
            encryptDecryptBuffer(data, password);
            actualPath = archivePath + ".tmp";
            f = fopen(actualPath.c_str(), "wb");
            fwrite(data.data(), 1, data.size(), f);
            fclose(f);
        } else {
            fclose(f);
        }
    }

    mz_zip_archive zip;
    if (!mz_zip_reader_init_file(&zip, actualPath.c_str())) {
        LOGE("Failed to parse zip central directory: %s", actualPath.c_str());
        return false;
    }

    bool mkdirOk = mkdirRecursive(outputDir);
    LOGI("mkdirRecursive(%s) = %s", outputDir.c_str(), mkdirOk ? "ok" : "fail");

    uint32_t numFiles = mz_zip_reader_get_num_files(&zip);
    LOGI("Zip contains %u entries, extracting to: %s", numFiles, outputDir.c_str());

    if (numFiles == 0) {
        LOGE("Zip has 0 entries - possibly failed to parse central directory");
        mz_zip_reader_end(&zip);
        return false;
    }

    int extractedCount = 0;
    int failedCount = 0;
    for (uint32_t i = 0; i < numFiles; i++) {
        mz_zip_file_stat stat;
        if (!mz_zip_reader_file_stat(&zip, i, &stat)) {
            LOGE("Failed to stat entry %u", i);
            failedCount++;
            continue;
        }

        std::string outPath = outputDir;
        if (!outPath.empty() && outPath.back() != '/') outPath += '/';
        outPath += stat.filename;

        LOGI("  [%u] name='%s' comp=%" PRIu64 " uncomp=%" PRIu64 " method=%u isDir=%d",
             i, stat.filename, stat.comp_size, stat.uncomp_size, stat.compression_method, stat.is_directory);

        if (stat.is_directory) {
            mkdirRecursive(outPath);
        } else {
            std::string dir = outPath.substr(0, outPath.find_last_of('/'));
            if (!dir.empty()) mkdirRecursive(dir);
            int ok = mz_zip_reader_extract_to_file(&zip, i, outPath.c_str());
            if (ok) {
                extractedCount++;
                LOGI("  -> extracted OK: %s", outPath.c_str());
            } else {
                failedCount++;
                LOGE("  -> FAILED to extract: %s (errno=%d)", outPath.c_str(), errno);
            }
        }
    }

    mz_zip_reader_end(&zip);
    if (actualPath != archivePath) unlink(actualPath.c_str());

    LOGI("Zip extraction done: %d extracted, %d failed, %u total", extractedCount, failedCount, numFiles);
    return extractedCount > 0 && failedCount == 0;
}

// --- TAR ---

// TAR header: 512 bytes, USTAR format
struct TarHeader {
    char name[100];
    char mode[8];
    char uid[8];
    char gid[8];
    char size[12];
    char mtime[12];
    char checksum[8];
    char typeflag;
    char linkname[100];
    char magic[6];
    char version[2];
    char uname[32];
    char gname[32];
    char devmajor[8];
    char devminor[8];
    char prefix[155];
    char padding[12];
};

static void octalToStr(char* buf, int len, uint64_t val) {
    snprintf(buf, len, "%0*llo", len - 1, (unsigned long long)val);
}

static uint64_t octalFromStr(const char* buf, int len) {
    uint64_t val = 0;
    for (int i = 0; i < len && buf[i] != '\0' && buf[i] != ' '; i++) {
        if (buf[i] >= '0' && buf[i] <= '7') val = val * 8 + (buf[i] - '0');
    }
    return val;
}

static uint32_t tarChecksum(const TarHeader* h) {
    const uint8_t* p = (const uint8_t*)h;
    uint32_t sum = 0;
    for (int i = 0; i < 512; i++) {
        sum += (i >= 148 && i < 156) ? ' ' : p[i];
    }
    return sum;
}

bool ArchiveModule::addFileToTar(FILE* tarFile, const std::string& filePath, const std::string& arcName) {
    struct stat st;
    if (stat(filePath.c_str(), &st) != 0) return false;

    TarHeader hdr;
    memset(&hdr, 0, sizeof(hdr));
    strncpy(hdr.name, arcName.c_str(), 99);
    octalToStr(hdr.mode, sizeof(hdr.mode), 0644);
    octalToStr(hdr.uid, sizeof(hdr.uid), 0);
    octalToStr(hdr.gid, sizeof(hdr.gid), 0);
    octalToStr(hdr.size, sizeof(hdr.size), st.st_size);
    octalToStr(hdr.mtime, sizeof(hdr.mtime), st.st_mtime);
    hdr.typeflag = '0'; // regular file
    memcpy(hdr.magic, "ustar", 5);
    hdr.magic[5] = '\0';
    hdr.version[0] = '0';
    hdr.version[1] = '0';

    memset(hdr.checksum, ' ', 8);
    uint32_t cksum = tarChecksum(&hdr);
    snprintf(hdr.checksum, sizeof(hdr.checksum), "%06o", cksum);
    hdr.checksum[7] = ' ';

    fwrite(&hdr, 1, 512, tarFile);

    FILE* src = fopen(filePath.c_str(), "rb");
    if (!src) return false;
    char buf[512];
    size_t remaining = st.st_size;
    while (remaining > 0) {
        memset(buf, 0, 512);
        size_t toRead = remaining > 512 ? 512 : remaining;
        fread(buf, 1, toRead, src);
        fwrite(buf, 1, 512, tarFile);
        remaining -= toRead;
    }
    fclose(src);
    return true;
}

bool ArchiveModule::compressTar(const std::vector<std::string>& inputPaths,
                                const std::string& outputPath) {
    for (const auto& p : inputPaths) {
        if (!sandbox::SandboxGuard::checkRead(p)) return false;
    }
    if (!sandbox::SandboxGuard::checkWrite(outputPath)) return false;

    FILE* tarFile = fopen(outputPath.c_str(), "wb");
    if (!tarFile) return false;

    for (const auto& path : inputPaths) {
        if (!addFileToTar(tarFile, path, getBaseName(path))) {
            fclose(tarFile);
            return false;
        }
    }

    char zeros[1024] = {};
    fwrite(zeros, 1, 1024, tarFile);
    fclose(tarFile);
    LOGI("Tar created: %s", outputPath.c_str());
    return true;
}

bool ArchiveModule::decompressTar(const std::string& archivePath,
                                  const std::string& outputDir) {
    if (!sandbox::SandboxGuard::checkRead(archivePath)) return false;
    if (!sandbox::SandboxGuard::checkWrite(outputDir)) return false;

    FILE* f = fopen(archivePath.c_str(), "rb");
    if (!f) return false;
    mkdirRecursive(outputDir);

    TarHeader hdr;
    while (fread(&hdr, 1, 512, f) == 512) {
        if (hdr.name[0] == '\0') break;

        uint64_t fileSize = octalFromStr(hdr.size, 12);
        std::string name(hdr.name);
        std::string outPath = outputDir + "/" + name;

        if (hdr.typeflag == '5') {
            mkdirRecursive(outPath);
        } else {
            std::string dir = outPath.substr(0, outPath.find_last_of('/'));
            if (!dir.empty()) mkdirRecursive(dir);

            FILE* out = fopen(outPath.c_str(), "wb");
            if (out) {
                char buf[512];
                uint64_t remaining = fileSize;
                while (remaining > 0) {
                    if (fread(buf, 1, 512, f) != 512) break;
                    size_t toWrite = remaining > 512 ? 512 : (size_t)remaining;
                    fwrite(buf, 1, toWrite, out);
                    remaining -= toWrite;
                }
                fclose(out);
            } else {
                uint64_t blocks = (fileSize + 511) / 512;
                fseek(f, blocks * 512, SEEK_CUR);
            }
        }
    }
    fclose(f);
    LOGI("Tar extracted to: %s", outputDir.c_str());
    return true;
}

// --- GZ ---

bool ArchiveModule::compressGz(const std::string& inputPath, const std::string& outputPath) {
    if (!sandbox::SandboxGuard::checkRead(inputPath)) return false;
    if (!sandbox::SandboxGuard::checkWrite(outputPath)) return false;

    FILE* src = fopen(inputPath.c_str(), "rb");
    if (!src) return false;

    gzFile gz = gzopen(outputPath.c_str(), "wb9");
    if (!gz) { fclose(src); return false; }

    char buf[8192];
    size_t n;
    while ((n = fread(buf, 1, sizeof(buf), src)) > 0) {
        gzwrite(gz, buf, n);
    }
    fclose(src);
    gzclose(gz);
    LOGI("Gz compressed: %s -> %s", inputPath.c_str(), outputPath.c_str());
    return true;
}

bool ArchiveModule::decompressGz(const std::string& archivePath, const std::string& outputPath) {
    if (!sandbox::SandboxGuard::checkRead(archivePath)) return false;
    if (!sandbox::SandboxGuard::checkWrite(outputPath)) return false;

    gzFile gz = gzopen(archivePath.c_str(), "rb");
    if (!gz) return false;

    FILE* dst = fopen(outputPath.c_str(), "wb");
    if (!dst) { gzclose(gz); return false; }

    char buf[8192];
    int n;
    while ((n = gzread(gz, buf, sizeof(buf))) > 0) {
        fwrite(buf, 1, n, dst);
    }
    fclose(dst);
    gzclose(gz);
    LOGI("Gz decompressed: %s -> %s", archivePath.c_str(), outputPath.c_str());
    return true;
}

// --- TAR.GZ ---

bool ArchiveModule::compressTarGz(const std::vector<std::string>& inputPaths,
                                  const std::string& outputPath) {
    for (const auto& p : inputPaths) {
        if (!sandbox::SandboxGuard::checkRead(p)) return false;
    }
    if (!sandbox::SandboxGuard::checkWrite(outputPath)) return false;

    std::string tmpTar = outputPath + ".tmp.tar";
    if (!compressTar(inputPaths, tmpTar)) return false;
    bool ok = compressGz(tmpTar, outputPath);
    unlink(tmpTar.c_str());
    return ok;
}

bool ArchiveModule::decompressTarGz(const std::string& archivePath,
                                    const std::string& outputDir) {
    if (!sandbox::SandboxGuard::checkRead(archivePath)) return false;
    if (!sandbox::SandboxGuard::checkWrite(outputDir)) return false;

    std::string tmpTar = archivePath + ".tmp.tar";
    if (!decompressGz(archivePath, tmpTar)) return false;
    bool ok = decompressTar(tmpTar, outputDir);
    unlink(tmpTar.c_str());
    return ok;
}

// --- List contents ---

std::vector<ArchiveEntry> ArchiveModule::listContents(const std::string& archivePath) {
    std::vector<ArchiveEntry> entries;
    if (!sandbox::SandboxGuard::checkRead(archivePath)) return entries;

    std::string ext = archivePath.substr(archivePath.find_last_of('.') + 1);
    std::transform(ext.begin(), ext.end(), ext.begin(), ::tolower);

    if (ext == "zip") {
        mz_zip_archive zip;
        if (mz_zip_reader_init_file(&zip, archivePath.c_str())) {
            for (uint32_t i = 0; i < mz_zip_reader_get_num_files(&zip); i++) {
                mz_zip_file_stat stat;
                mz_zip_reader_file_stat(&zip, i, &stat);
                entries.push_back({stat.filename, stat.uncomp_size, stat.comp_size, stat.is_directory != 0});
            }
            mz_zip_reader_end(&zip);
        }
    } else if (ext == "tar") {
        FILE* f = fopen(archivePath.c_str(), "rb");
        if (f) {
            TarHeader hdr;
            while (fread(&hdr, 1, 512, f) == 512 && hdr.name[0] != '\0') {
                uint64_t sz = octalFromStr(hdr.size, 12);
                entries.push_back({hdr.name, sz, sz, hdr.typeflag == '5'});
                uint64_t blocks = (sz + 511) / 512;
                fseek(f, blocks * 512, SEEK_CUR);
            }
            fclose(f);
        }
    }

    return entries;
}

} // namespace archive
