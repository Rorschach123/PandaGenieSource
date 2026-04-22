package ai.rorsch.pandagenie.module.runtime;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Per-module private storage API. Each module gets an isolated directory under the
 * app's internal storage ({@code <app_files>/module_sandbox/<moduleId>/}).
 * <p>
 * <b>Isolation guarantees:</b>
 * <ul>
 *   <li>Java: {@code context.getFilesDir()} already returns the module sandbox dir
 *       (via {@code SandboxedContext}). This helper provides convenience methods on top.</li>
 *   <li>Native: PLT-hooked libc calls (open, fopen, stat, etc.) only allow access to the
 *       current module's sandbox path; cross-module and app-private reads/writes are blocked.</li>
 * </ul>
 * <p>
 * <b>Usage:</b> Obtain an instance via {@link #from(Context)} inside your plugin's
 * {@code invoke()} method. The Context passed to your plugin is already sandboxed.
 * <p>
 * <b>Example:</b>
 * <pre>
 * public String invoke(Context ctx, String action, String params) {
 *     ModuleStorage storage = ModuleStorage.from(ctx);
 *     File dataFile = storage.getFile("config.json");
 *     storage.writeText("config.json", "{\"key\":\"value\"}");
 *     String content = storage.readText("config.json");
 *     // ...
 * }
 * </pre>
 */
public final class ModuleStorage {
    private final File rootDir;
    private final File cacheDir;

    private ModuleStorage(File rootDir, File cacheDir) {
        this.rootDir = rootDir;
        this.cacheDir = cacheDir;
    }

    /**
     * Create a ModuleStorage instance from the sandboxed Context.
     *
     * @param context The Context passed to your plugin's invoke() — already sandboxed to your module.
     * @return A ModuleStorage bound to this module's private directory.
     */
    public static ModuleStorage from(Context context) {
        File root = context.getFilesDir();
        File cache = context.getCacheDir();
        root.mkdirs();
        cache.mkdirs();
        return new ModuleStorage(root, cache);
    }

    /**
     * Get the root directory of this module's private storage.
     * All files you create here are invisible to other modules.
     */
    public File getRootDir() {
        return rootDir;
    }

    /**
     * Get the cache directory of this module's private storage.
     * Files here may be cleaned up by the system under storage pressure.
     */
    public File getCacheDir() {
        return cacheDir;
    }

    /**
     * Get a File reference for a relative path within this module's private storage.
     *
     * @param relativePath Path relative to the module's root dir (e.g. "data/config.json").
     *                     Parent directories are created automatically.
     * @return File object. The file may or may not exist.
     */
    public File getFile(String relativePath) {
        File f = new File(rootDir, relativePath);
        File parent = f.getParentFile();
        if (parent != null) parent.mkdirs();
        return f;
    }

    /**
     * Get a subdirectory within this module's private storage, creating it if needed.
     *
     * @param name Subdirectory name (e.g. "images", "db").
     * @return The directory File object, guaranteed to exist.
     */
    public File getDir(String name) {
        File dir = new File(rootDir, name);
        dir.mkdirs();
        return dir;
    }

    /**
     * Write text content to a file in this module's private storage.
     *
     * @param relativePath Path relative to the module's root dir.
     * @param content      Text content to write (UTF-8).
     * @throws IOException If writing fails.
     */
    public void writeText(String relativePath, String content) throws IOException {
        File f = getFile(relativePath);
        FileOutputStream fos = new FileOutputStream(f);
        try {
            fos.write(content.getBytes("UTF-8"));
        } finally {
            fos.close();
        }
    }

    /**
     * Read text content from a file in this module's private storage.
     *
     * @param relativePath Path relative to the module's root dir.
     * @return The file content as a UTF-8 string.
     * @throws IOException If reading fails or the file doesn't exist.
     */
    public String readText(String relativePath) throws IOException {
        File f = getFile(relativePath);
        if (!f.exists()) throw new FileNotFoundException(relativePath);
        FileInputStream fis = new FileInputStream(f);
        try {
            byte[] data = new byte[(int) f.length()];
            fis.read(data);
            return new String(data, "UTF-8");
        } finally {
            fis.close();
        }
    }

    /**
     * Check if a file exists in this module's private storage.
     *
     * @param relativePath Path relative to the module's root dir.
     * @return true if the file exists.
     */
    public boolean exists(String relativePath) {
        return new File(rootDir, relativePath).exists();
    }

    /**
     * Delete a file from this module's private storage.
     *
     * @param relativePath Path relative to the module's root dir.
     * @return true if the file was deleted successfully.
     */
    public boolean delete(String relativePath) {
        return new File(rootDir, relativePath).delete();
    }

    /**
     * List files in a subdirectory of this module's private storage.
     *
     * @param relativePath Relative path to a directory (use "" or "." for root).
     * @return Array of file names, or empty array if directory doesn't exist.
     */
    public String[] list(String relativePath) {
        File dir = (relativePath == null || relativePath.isEmpty() || ".".equals(relativePath))
                ? rootDir : new File(rootDir, relativePath);
        String[] names = dir.list();
        return names != null ? names : new String[0];
    }

    /**
     * Get the total size of all files in this module's private storage (bytes).
     */
    public long getUsedSpace() {
        return dirSize(rootDir) + dirSize(cacheDir);
    }

    private static long dirSize(File dir) {
        if (!dir.exists()) return 0;
        long size = 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        for (File f : files) {
            size += f.isFile() ? f.length() : dirSize(f);
        }
        return size;
    }
}
