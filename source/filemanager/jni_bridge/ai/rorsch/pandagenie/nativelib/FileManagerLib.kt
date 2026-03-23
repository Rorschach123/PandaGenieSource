package ai.rorsch.pandagenie.nativelib

class FileManagerLib {

    external fun nativeListDirectory(path: String): String
    external fun nativeCreateDirectory(path: String): Boolean
    external fun nativeDeleteFile(path: String): Boolean
    external fun nativeDeleteDirectory(path: String, recursive: Boolean): Boolean
    external fun nativeCopyFile(src: String, dst: String): Boolean
    external fun nativeMoveFile(src: String, dst: String): Boolean
    external fun nativeRenameFile(oldPath: String, newPath: String): Boolean
    external fun nativeGetFileInfo(path: String): String
    external fun nativeSearchFiles(dir: String, pattern: String, recursive: Boolean): String
    external fun nativeReadTextFile(path: String): String
    external fun nativeWriteTextFile(path: String, content: String): Boolean
    external fun nativeFileExists(path: String): Boolean
    external fun nativeGetFileSize(path: String): Long
}
