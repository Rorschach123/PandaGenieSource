package ai.rorsch.pandagenie.nativelib;

public class FileManagerLib {

    static {
        System.loadLibrary("filemanager");
    }

    public native String nativeListDirectory(String path);
    public native boolean nativeCreateDirectory(String path);
    public native boolean nativeDeleteFile(String path);
    public native boolean nativeDeleteDirectory(String path, boolean recursive);
    public native boolean nativeCopyFile(String src, String dst);
    public native boolean nativeMoveFile(String src, String dst);
    public native boolean nativeRenameFile(String oldPath, String newPath);
    public native String nativeGetFileInfo(String path);
    public native String nativeSearchFiles(String dir, String pattern, boolean recursive);
    public native String nativeReadTextFile(String path);
    public native boolean nativeWriteTextFile(String path, String content);
    public native boolean nativeFileExists(String path);
    public native long nativeGetFileSize(String path);
}
