package ai.rorsch.moduleplugins.filemanager;

import android.content.Context;
import ai.rorsch.pandagenie.module.runtime.ModulePlugin;
import ai.rorsch.pandagenie.nativelib.FileManagerLib;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class FileManagerPlugin implements ModulePlugin {
    private static final int DISPLAY_LIST_MAX = 20;
    private static final int READ_TEXT_DISPLAY_MAX = 2000;
    private static final SimpleDateFormat SDF = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

    private final FileManagerLib lib = new FileManagerLib();

    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        JSONObject params = new JSONObject(emptyJson(paramsJson));
        switch (action) {
            case "listDirectory": {
                String path = params.optString("path", "");
                String raw = lib.nativeListDirectory(path);
                return ok(raw, formatListDirectoryDisplay(path, raw));
            }
            case "createDirectory": {
                String path = params.optString("path", "");
                boolean okb = lib.nativeCreateDirectory(path);
                return boolResult(okb, "createDirectory failed", okb ? "📁 Directory created: " + path : null);
            }
            case "deleteFile": {
                String path = params.optString("path", "");
                boolean okb = lib.nativeDeleteFile(path);
                return boolResult(okb, "deleteFile failed", okb ? "🗑️ Deleted: " + path : null);
            }
            case "deleteDirectory": {
                String path = params.optString("path", "");
                boolean okb = lib.nativeDeleteDirectory(path, params.optBoolean("recursive", false));
                return boolResult(okb, "deleteDirectory failed", okb ? "🗑️ Deleted: " + path : null);
            }
            case "copyFile":
                return handleCopyOrMove(params, false);
            case "moveFile":
                return handleCopyOrMove(params, true);
            case "renameFile": {
                String oldP = params.optString("oldPath", "");
                String newP = params.optString("newPath", "");
                boolean okb = lib.nativeRenameFile(oldP, newP);
                return boolResult(okb, "renameFile failed", okb ? formatRenameDisplay(oldP, newP) : null);
            }
            case "getFileInfo": {
                String raw = lib.nativeGetFileInfo(params.optString("path", ""));
                return ok(raw, formatGetFileInfoDisplay(raw));
            }
            case "searchFiles": {
                String raw = lib.nativeSearchFiles(
                        params.optString("dir", ""),
                        params.optString("pattern", "*"),
                        parseBoolean(params.opt("recursive"), true)
                );
                return ok(raw, formatSearchFilesDisplay(raw));
            }
            case "readTextFile": {
                String raw = lib.nativeReadTextFile(params.optString("path", ""));
                return ok(raw, formatReadTextFileDisplay(raw));
            }
            case "writeTextFile": {
                String path = params.optString("path", "");
                boolean okb = lib.nativeWriteTextFile(path, params.optString("content", ""));
                return boolResult(okb, "writeTextFile failed", okb ? "✅ Written to file: " + path : null);
            }
            case "fileExists": {
                boolean ex = lib.nativeFileExists(params.optString("path", ""));
                return ok(String.valueOf(ex), "📄 File exists: " + (ex ? "✅" : "❌"));
            }
            case "getFileSize": {
                long sz = lib.nativeGetFileSize(params.optString("path", ""));
                return ok(String.valueOf(sz), "📄 Size: " + sz + " bytes");
            }
            default:
                return error("Unsupported action: " + action);
        }
    }

    private boolean parseBoolean(Object value, boolean fallback) {
        if (value == null) return fallback;
        if (value instanceof Boolean) return (Boolean) value;
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private String handleCopyOrMove(JSONObject params, boolean isMove) throws Exception {
        String src = params.optString("src", params.optString("source", params.optString("path", "")));
        String dst = params.optString("dst", params.optString("destination", params.optString("target", "")));
        String op = isMove ? "moveFile" : "copyFile";

        if (src.isEmpty()) return error(op + ": src is empty");
        if (dst.isEmpty()) return error(op + ": dst is empty");

        File srcFile = new File(src);
        if (!srcFile.exists()) return error(op + ": source not found: " + src);

        File dstFile = new File(dst);
        if (dstFile.isDirectory()) {
            dstFile = new File(dstFile, srcFile.getName());
            dst = dstFile.getAbsolutePath();
        }

        File dstParent = dstFile.getParentFile();
        if (dstParent != null && !dstParent.exists()) {
            dstParent.mkdirs();
        }

        boolean result;
        if (isMove) {
            result = lib.nativeMoveFile(src, dst);
        } else {
            result = lib.nativeCopyFile(src, dst);
        }

        if (!result) {
            String detail = op + " failed: " + src + " -> " + dst;
            if (dstParent != null && !dstParent.canWrite())
                detail += " (dst dir not writable)";
            if (!srcFile.canRead())
                detail += " (src not readable)";
            return error(detail);
        }
        String disp = isMove ? formatMoveDisplay(src, dst) : formatCopyDisplay(src, dst);
        return ok(String.valueOf(result), disp);
    }

    private String emptyJson(String value) {
        return value == null || value.trim().isEmpty() ? "{}" : value;
    }

    private String ok(String output) throws Exception {
        return ok(output, null);
    }

    private String ok(String output, String displayText) throws Exception {
        JSONObject j = new JSONObject().put("success", true).put("output", output);
        if (displayText != null && !displayText.isEmpty()) j.put("_displayText", displayText);
        return j.toString();
    }

    private String boolResult(boolean value, String errorMsg, String successDisplayText) throws Exception {
        JSONObject json = new JSONObject().put("success", value).put("output", String.valueOf(value));
        if (!value) json.put("error", errorMsg);
        else if (successDisplayText != null && !successDisplayText.isEmpty()) json.put("_displayText", successDisplayText);
        return json.toString();
    }

    private static String formatSizeBytes(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format(Locale.US, "%.1f KB", size / 1024.0);
        if (size < 1024L * 1024 * 1024) return String.format(Locale.US, "%.2f MB", size / (1024.0 * 1024));
        return String.format(Locale.US, "%.2f GB", size / (1024.0 * 1024 * 1024));
    }

    private static String formatListDirectoryDisplay(String dirPath, String rawJson) {
        try {
            JSONArray arr = new JSONArray(rawJson);
            int n = arr.length();
            StringBuilder sb = new StringBuilder();
            sb.append("📂 Directory: ").append(dirPath).append("\n━━━━━━━━━━━━━━\n").append(n).append(" items\n");
            int show = Math.min(DISPLAY_LIST_MAX, n);
            for (int i = 0; i < show; i++) {
                JSONObject e = arr.optJSONObject(i);
                if (e == null) continue;
                String name = e.optString("name", "?");
                boolean isDir = e.optBoolean("isDirectory", false);
                long sz = e.optLong("size", 0);
                if (isDir) sb.append("📁 ").append(name).append("/\n");
                else sb.append("📄 ").append(name).append(" (").append(formatSizeBytes(sz)).append(")\n");
            }
            if (n > show) sb.append("… (+").append(n - show).append(" more)");
            return sb.toString().trim();
        } catch (Exception ignored) {
            return "📂 Directory: " + dirPath + "\n━━━━━━━━━━━━━━\n";
        }
    }

    private static String formatRenameDisplay(String oldPath, String newPath) {
        return "✏️ Renamed\n▸ From: " + oldPath + "\n▸ To: " + newPath;
    }

    private static String formatCopyDisplay(String src, String dst) {
        return "📋 Copied\n▸ From: " + src + "\n▸ To: " + dst;
    }

    private static String formatMoveDisplay(String src, String dst) {
        return "📦 Moved\n▸ From: " + src + "\n▸ To: " + dst;
    }

    private static String formatGetFileInfoDisplay(String rawJson) {
        try {
            JSONObject o = new JSONObject(rawJson);
            String name = o.optString("name", "");
            long sz = o.optLong("size", 0);
            long lm = o.optLong("lastModified", 0);
            String mod = lm > 0 ? SDF.format(new Date(lm)) : String.valueOf(lm);
            return "📄 File Info\n━━━━━━━━━━━━━━\n▸ Name: " + name
                    + "\n▸ Size: " + formatSizeBytes(sz)
                    + "\n▸ Modified: " + mod;
        } catch (Exception ignored) {
            return "📄 File Info\n━━━━━━━━━━━━━━\n";
        }
    }

    private static String formatSearchFilesDisplay(String rawJson) {
        try {
            JSONArray arr = new JSONArray(rawJson);
            int n = arr.length();
            StringBuilder sb = new StringBuilder();
            sb.append("🔍 Search Results\n━━━━━━━━━━━━━━\nFound ").append(n).append(" files\n");
            int show = Math.min(DISPLAY_LIST_MAX, n);
            for (int i = 0; i < show; i++) {
                sb.append(i + 1).append(". ").append(arr.optString(i, "?")).append("\n");
            }
            if (n > show) sb.append("… (+").append(n - show).append(" more)");
            return sb.toString().trim();
        } catch (Exception ignored) {
            return "🔍 Search Results\n━━━━━━━━━━━━━━\n";
        }
    }

    private static String formatReadTextFileDisplay(String content) {
        String body = content == null ? "" : content;
        if (body.length() > READ_TEXT_DISPLAY_MAX) {
            body = body.substring(0, READ_TEXT_DISPLAY_MAX) + "…";
        }
        return "📄 File Content\n━━━━━━━━━━━━━━\n" + body;
    }

    private String error(String message) throws Exception {
        return new JSONObject().put("success", false).put("error", message).toString();
    }
}
