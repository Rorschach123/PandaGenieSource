package ai.rorsch.pandagenie.nativelib

class ArchiveLib {

    external fun compressZip(inputPaths: Array<String>, outputPath: String, password: String): Boolean
    external fun decompressZip(archivePath: String, outputDir: String, password: String): Boolean
    external fun compressTar(inputPaths: Array<String>, outputPath: String): Boolean
    external fun decompressTar(archivePath: String, outputDir: String): Boolean
    external fun compressGz(inputPath: String, outputPath: String): Boolean
    external fun decompressGz(archivePath: String, outputPath: String): Boolean
    external fun compressTarGz(inputPaths: Array<String>, outputPath: String): Boolean
    external fun decompressTarGz(archivePath: String, outputDir: String): Boolean
    external fun listContents(archivePath: String): String
}
