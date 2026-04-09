package ai.rorsch.pandagenie.nativelib;

public class ArchiveLib {

    static {
        System.loadLibrary("archive_module");
    }

    public native boolean compressZip(String[] inputPaths, String outputPath, String password);
    public native boolean decompressZip(String archivePath, String outputDir, String password);
    public native boolean compressTar(String[] inputPaths, String outputPath);
    public native boolean decompressTar(String archivePath, String outputDir);
    public native boolean compressGz(String inputPath, String outputPath);
    public native boolean decompressGz(String archivePath, String outputPath);
    public native boolean compressTarGz(String[] inputPaths, String outputPath);
    public native boolean decompressTarGz(String archivePath, String outputDir);
    public native String listContents(String archivePath);

    public static native void nativeConfigureSandbox(String[] readPaths, String[] writePaths);
    public static native void nativeClearSandbox();
}
