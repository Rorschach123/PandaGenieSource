#pragma once
/*
 * Minimal ZIP archive library built on top of zlib deflate/inflate.
 * Implements ZIP local file header, central directory, and EOCD structures.
 */

#include <cstdint>
#include <cstdio>
#include <vector>
#include <string>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    char filename[260];
    uint64_t uncomp_size;
    uint64_t comp_size;
    uint32_t crc32;
    uint16_t is_directory;
    uint16_t compression_method;
    uint32_t local_header_offset;
} mz_zip_file_stat;

typedef struct {
    FILE* file;
    uint32_t num_files;
    std::vector<mz_zip_file_stat>* entries;
    int mode; // 0=closed, 1=reader, 2=writer
} mz_zip_archive;

// Reader
int mz_zip_reader_init_file(mz_zip_archive* pZip, const char* pFilename);
uint32_t mz_zip_reader_get_num_files(mz_zip_archive* pZip);
int mz_zip_reader_file_stat(mz_zip_archive* pZip, uint32_t file_index, mz_zip_file_stat* pStat);
int mz_zip_reader_extract_to_file(mz_zip_archive* pZip, uint32_t file_index, const char* pDst_filename);
int mz_zip_reader_end(mz_zip_archive* pZip);

// Writer
int mz_zip_writer_init_file(mz_zip_archive* pZip, const char* pFilename);
int mz_zip_writer_add_file(mz_zip_archive* pZip, const char* pArchive_name, const char* pSrc_filename);
int mz_zip_writer_finalize_archive(mz_zip_archive* pZip);
int mz_zip_writer_end(mz_zip_archive* pZip);

#ifdef __cplusplus
}
#endif
