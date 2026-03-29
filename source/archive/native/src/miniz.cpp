/*
 * Minimal ZIP library implementation using zlib for deflate/inflate.
 * Handles ZIP format structures (local headers, central directory, EOCD).
 */
#include "miniz.h"
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <zlib.h>
#include <sys/stat.h>

#define ZIP_LOCAL_HEADER_SIG     0x04034b50
#define ZIP_CENTRAL_DIR_SIG      0x02014b50
#define ZIP_EOCD_SIG             0x06054b50
#define COMPRESSION_DEFLATE      8
#define COMPRESSION_STORE        0
#define BUF_SIZE                 (64 * 1024)

static uint32_t calc_crc32(const uint8_t* data, size_t len) {
    return (uint32_t)crc32(crc32(0L, Z_NULL, 0), data, (uInt)len);
}

static void write_u16(FILE* f, uint16_t v) { fwrite(&v, 2, 1, f); }
static void write_u32(FILE* f, uint32_t v) { fwrite(&v, 4, 1, f); }
static uint16_t read_u16(FILE* f) { uint16_t v; fread(&v, 2, 1, f); return v; }
static uint32_t read_u32(FILE* f) { uint32_t v; fread(&v, 4, 1, f); return v; }

/* ---- READER ---- */

int mz_zip_reader_init_file(mz_zip_archive* pZip, const char* pFilename) {
    memset(pZip, 0, sizeof(*pZip));
    pZip->file = fopen(pFilename, "rb");
    if (!pZip->file) return 0;

    pZip->entries = new std::vector<mz_zip_file_stat>();
    pZip->mode = 1;

    /* Find EOCD by scanning backwards */
    fseek(pZip->file, 0, SEEK_END);
    long fileSize = ftell(pZip->file);
    long searchStart = fileSize - 22;
    if (searchStart < 0) searchStart = 0;

    long eocdPos = -1;
    for (long pos = searchStart; pos >= 0 && pos >= fileSize - 65557; pos--) {
        fseek(pZip->file, pos, SEEK_SET);
        uint32_t sig = read_u32(pZip->file);
        if (sig == ZIP_EOCD_SIG) { eocdPos = pos; break; }
    }
    if (eocdPos < 0) { fclose(pZip->file); delete pZip->entries; return 0; }

    fseek(pZip->file, eocdPos + 10, SEEK_SET);
    uint16_t totalEntries = read_u16(pZip->file);
    uint32_t cdSize = read_u32(pZip->file);
    uint32_t cdOffset = read_u32(pZip->file);

    fseek(pZip->file, cdOffset, SEEK_SET);
    for (uint16_t i = 0; i < totalEntries; i++) {
        uint32_t sig = read_u32(pZip->file);
        if (sig != ZIP_CENTRAL_DIR_SIG) break;

        fseek(pZip->file, 4, SEEK_CUR); /* skip version made by, version needed */
        uint16_t gpFlags = read_u16(pZip->file);
        uint16_t compression = read_u16(pZip->file);
        fseek(pZip->file, 4, SEEK_CUR); /* skip time, date */
        uint32_t crc = read_u32(pZip->file);
        uint32_t compSize = read_u32(pZip->file);
        uint32_t uncompSize = read_u32(pZip->file);
        uint16_t nameLen = read_u16(pZip->file);
        uint16_t extraLen = read_u16(pZip->file);
        uint16_t commentLen = read_u16(pZip->file);
        fseek(pZip->file, 8, SEEK_CUR); /* skip disk#, attrs */
        uint32_t localOffset = read_u32(pZip->file);

        mz_zip_file_stat entry;
        memset(&entry, 0, sizeof(entry));
        size_t readLen = nameLen < 259 ? nameLen : 259;
        fread(entry.filename, 1, readLen, pZip->file);
        if (nameLen > readLen) fseek(pZip->file, nameLen - readLen, SEEK_CUR);
        entry.filename[readLen] = '\0';
        entry.uncomp_size = uncompSize;
        entry.comp_size = compSize;
        entry.crc32 = crc;
        entry.compression_method = compression;
        entry.local_header_offset = localOffset;
        entry.is_directory = (readLen > 0 && entry.filename[readLen-1] == '/') ? 1 : 0;

        pZip->entries->push_back(entry);
        fseek(pZip->file, extraLen + commentLen, SEEK_CUR);
    }
    pZip->num_files = pZip->entries->size();
    return 1;
}

uint32_t mz_zip_reader_get_num_files(mz_zip_archive* pZip) {
    return pZip->num_files;
}

int mz_zip_reader_file_stat(mz_zip_archive* pZip, uint32_t idx, mz_zip_file_stat* pStat) {
    if (idx >= pZip->num_files) return 0;
    *pStat = (*pZip->entries)[idx];
    return 1;
}

int mz_zip_reader_extract_to_file(mz_zip_archive* pZip, uint32_t idx, const char* pDst) {
    if (idx >= pZip->num_files) return 0;
    mz_zip_file_stat* entry = &(*pZip->entries)[idx];
    if (entry->is_directory) return 1;

    fseek(pZip->file, entry->local_header_offset, SEEK_SET);
    uint32_t sig = read_u32(pZip->file);
    if (sig != ZIP_LOCAL_HEADER_SIG) return 0;

    /* Local file header: skip version(2) + flags(2) + method(2) + time(2) + date(2) +
       crc(4) + comp_size(4) + uncomp_size(4) = 22 bytes, then nameLen(2) + extraLen(2) */
    fseek(pZip->file, 22, SEEK_CUR);
    uint16_t nameLen = read_u16(pZip->file);
    uint16_t extraLen = read_u16(pZip->file);
    fseek(pZip->file, nameLen + extraLen, SEEK_CUR);

    FILE* dst = fopen(pDst, "wb");
    if (!dst) return 0;

    if (entry->compression_method == COMPRESSION_STORE) {
        uint64_t remaining = entry->comp_size;
        uint8_t buf[BUF_SIZE];
        while (remaining > 0) {
            size_t toRead = remaining > BUF_SIZE ? BUF_SIZE : (size_t)remaining;
            size_t got = fread(buf, 1, toRead, pZip->file);
            if (got == 0) break;
            fwrite(buf, 1, got, dst);
            remaining -= got;
        }
    } else if (entry->compression_method == COMPRESSION_DEFLATE && entry->comp_size > 0) {
        z_stream strm = {};
        if (inflateInit2(&strm, -MAX_WBITS) != Z_OK) {
            fclose(dst);
            return 0;
        }

        uint8_t inBuf[BUF_SIZE];
        uint8_t outBuf[BUF_SIZE];
        uint64_t compRemaining = entry->comp_size;
        int ret = Z_OK;

        while (ret != Z_STREAM_END) {
            if (strm.avail_in == 0 && compRemaining > 0) {
                size_t toRead = compRemaining > BUF_SIZE ? BUF_SIZE : (size_t)compRemaining;
                size_t got = fread(inBuf, 1, toRead, pZip->file);
                if (got == 0) break;
                strm.next_in = inBuf;
                strm.avail_in = (uInt)got;
                compRemaining -= got;
            }
            if (strm.avail_in == 0 && compRemaining == 0 && ret != Z_STREAM_END) {
                break;
            }
            strm.next_out = outBuf;
            strm.avail_out = BUF_SIZE;
            ret = inflate(&strm, Z_NO_FLUSH);
            if (ret != Z_OK && ret != Z_STREAM_END && ret != Z_BUF_ERROR) {
                inflateEnd(&strm);
                fclose(dst);
                return 0;
            }
            size_t have = BUF_SIZE - strm.avail_out;
            if (have > 0) fwrite(outBuf, 1, have, dst);
        }
        inflateEnd(&strm);
        if (ret != Z_STREAM_END) {
            fclose(dst);
            return 0;
        }
    } else if (entry->comp_size > 0) {
        uint64_t remaining = entry->comp_size;
        uint8_t buf[BUF_SIZE];
        while (remaining > 0) {
            size_t toRead = remaining > BUF_SIZE ? BUF_SIZE : (size_t)remaining;
            size_t got = fread(buf, 1, toRead, pZip->file);
            if (got == 0) break;
            fwrite(buf, 1, got, dst);
            remaining -= got;
        }
    }

    fclose(dst);
    return 1;
}

int mz_zip_reader_end(mz_zip_archive* pZip) {
    if (pZip->file) fclose(pZip->file);
    if (pZip->entries) delete pZip->entries;
    memset(pZip, 0, sizeof(*pZip));
    return 1;
}

/* ---- WRITER ---- */

int mz_zip_writer_init_file(mz_zip_archive* pZip, const char* pFilename) {
    memset(pZip, 0, sizeof(*pZip));
    pZip->file = fopen(pFilename, "wb");
    if (!pZip->file) return 0;
    pZip->entries = new std::vector<mz_zip_file_stat>();
    pZip->mode = 2;
    return 1;
}

int mz_zip_writer_add_file(mz_zip_archive* pZip, const char* pArcName, const char* pSrcFile) {
    FILE* src = fopen(pSrcFile, "rb");
    if (!src) return 0;

    fseek(src, 0, SEEK_END);
    long srcSize = ftell(src);
    fseek(src, 0, SEEK_SET);

    uint16_t nameLen = (uint16_t)strlen(pArcName);
    uint32_t localOffset = (uint32_t)ftell(pZip->file);

    /* CRC32 pass — stream through the file without loading it all */
    uint32_t fileCrc = crc32(0L, Z_NULL, 0);
    {
        uint8_t buf[BUF_SIZE];
        size_t n;
        while ((n = fread(buf, 1, BUF_SIZE, src)) > 0) {
            fileCrc = crc32(fileCrc, buf, (uInt)n);
        }
        fseek(src, 0, SEEK_SET);
    }

    /* Try deflate compression into a temp file to avoid large memory allocs */
    uint32_t compSize = 0;
    uint16_t method = COMPRESSION_STORE;
    FILE* tmpFile = NULL;
    char tmpPath[512];
    snprintf(tmpPath, sizeof(tmpPath), "%s.tmp_deflate", pSrcFile);

    if (srcSize > 0) {
        tmpFile = fopen(tmpPath, "w+b");
        if (tmpFile) {
            z_stream strm = {};
            if (deflateInit2(&strm, Z_DEFAULT_COMPRESSION, Z_DEFLATED, -MAX_WBITS, 8, Z_DEFAULT_STRATEGY) == Z_OK) {
                uint8_t inBuf[BUF_SIZE];
                uint8_t outBuf[BUF_SIZE];
                int flush;
                do {
                    size_t n = fread(inBuf, 1, BUF_SIZE, src);
                    strm.next_in = inBuf;
                    strm.avail_in = (uInt)n;
                    flush = feof(src) ? Z_FINISH : Z_NO_FLUSH;
                    do {
                        strm.next_out = outBuf;
                        strm.avail_out = BUF_SIZE;
                        deflate(&strm, flush);
                        size_t have = BUF_SIZE - strm.avail_out;
                        if (have > 0) fwrite(outBuf, 1, have, tmpFile);
                    } while (strm.avail_out == 0);
                } while (flush != Z_FINISH);
                compSize = (uint32_t)strm.total_out;
                deflateEnd(&strm);

                if (compSize < (uint32_t)srcSize) {
                    method = COMPRESSION_DEFLATE;
                } else {
                    compSize = (uint32_t)srcSize;
                    method = COMPRESSION_STORE;
                }
            } else {
                compSize = (uint32_t)srcSize;
            }
        } else {
            compSize = (uint32_t)srcSize;
        }
    }

    /* Write local file header */
    write_u32(pZip->file, ZIP_LOCAL_HEADER_SIG);
    write_u16(pZip->file, 20);
    write_u16(pZip->file, 0);
    write_u16(pZip->file, method);
    write_u16(pZip->file, 0);
    write_u16(pZip->file, 0);
    write_u32(pZip->file, fileCrc);
    write_u32(pZip->file, compSize);
    write_u32(pZip->file, (uint32_t)srcSize);
    write_u16(pZip->file, nameLen);
    write_u16(pZip->file, 0);
    fwrite(pArcName, 1, nameLen, pZip->file);

    /* Write file data */
    if (method == COMPRESSION_DEFLATE && tmpFile) {
        fseek(tmpFile, 0, SEEK_SET);
        uint8_t buf[BUF_SIZE];
        uint32_t remaining = compSize;
        while (remaining > 0) {
            size_t toRead = remaining > BUF_SIZE ? BUF_SIZE : remaining;
            size_t got = fread(buf, 1, toRead, tmpFile);
            if (got == 0) break;
            fwrite(buf, 1, got, pZip->file);
            remaining -= (uint32_t)got;
        }
    } else {
        fseek(src, 0, SEEK_SET);
        uint8_t buf[BUF_SIZE];
        long remaining = srcSize;
        while (remaining > 0) {
            size_t toRead = remaining > BUF_SIZE ? BUF_SIZE : (size_t)remaining;
            size_t got = fread(buf, 1, toRead, src);
            if (got == 0) break;
            fwrite(buf, 1, got, pZip->file);
            remaining -= (long)got;
        }
    }

    fclose(src);
    if (tmpFile) {
        fclose(tmpFile);
        unlink(tmpPath);
    }

    /* Record entry for central directory */
    mz_zip_file_stat entry;
    memset(&entry, 0, sizeof(entry));
    strncpy(entry.filename, pArcName, 259);
    entry.uncomp_size = srcSize;
    entry.comp_size = compSize;
    entry.crc32 = fileCrc;
    entry.local_header_offset = localOffset;
    entry.compression_method = method;
    entry.is_directory = 0;
    pZip->entries->push_back(entry);
    pZip->num_files++;

    return 1;
}

int mz_zip_writer_finalize_archive(mz_zip_archive* pZip) {
    uint32_t cdOffset = (uint32_t)ftell(pZip->file);

    /* Write central directory */
    for (uint32_t i = 0; i < pZip->num_files; i++) {
        mz_zip_file_stat* e = &(*pZip->entries)[i];
        uint16_t nameLen = strlen(e->filename);
        uint16_t method = e->compression_method;

        write_u32(pZip->file, ZIP_CENTRAL_DIR_SIG);
        write_u16(pZip->file, 20);       /* version made by */
        write_u16(pZip->file, 20);       /* version needed */
        write_u16(pZip->file, 0);        /* flags */
        write_u16(pZip->file, method);
        write_u16(pZip->file, 0);        /* mod time */
        write_u16(pZip->file, 0);        /* mod date */
        write_u32(pZip->file, e->crc32);
        write_u32(pZip->file, (uint32_t)e->comp_size);
        write_u32(pZip->file, (uint32_t)e->uncomp_size);
        write_u16(pZip->file, nameLen);
        write_u16(pZip->file, 0);        /* extra len */
        write_u16(pZip->file, 0);        /* comment len */
        write_u16(pZip->file, 0);        /* disk number */
        write_u16(pZip->file, 0);        /* internal attrs */
        write_u32(pZip->file, 0);        /* external attrs */
        write_u32(pZip->file, e->local_header_offset);
        fwrite(e->filename, 1, nameLen, pZip->file);
    }

    uint32_t cdSize = (uint32_t)ftell(pZip->file) - cdOffset;

    /* Write EOCD */
    write_u32(pZip->file, ZIP_EOCD_SIG);
    write_u16(pZip->file, 0);            /* disk number */
    write_u16(pZip->file, 0);            /* cd disk number */
    write_u16(pZip->file, pZip->num_files);
    write_u16(pZip->file, pZip->num_files);
    write_u32(pZip->file, cdSize);
    write_u32(pZip->file, cdOffset);
    write_u16(pZip->file, 0);            /* comment length */

    return 1;
}

int mz_zip_writer_end(mz_zip_archive* pZip) {
    if (pZip->file) fclose(pZip->file);
    if (pZip->entries) delete pZip->entries;
    memset(pZip, 0, sizeof(*pZip));
    return 1;
}
