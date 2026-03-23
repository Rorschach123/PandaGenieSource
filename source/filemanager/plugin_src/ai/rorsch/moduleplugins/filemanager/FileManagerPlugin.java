package ai.rorsch.moduleplugins.filemanager;

import android.content.Context;
import ai.rorsch.pandagenie.module.runtime.ModulePlugin;
import ai.rorsch.pandagenie.nativelib.FileManagerLib;
import org.json.JSONObject;
import java.io.File;

public class FileManagerPlugin implements ModulePlugin {
    private final FileManagerLib lib = new FileManagerLib();

    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        JSONObject params = new JSONObject(emptyJson(paramsJson));
        switch (action) {
            case "listDirectory":
                return ok(lib.nativeListDirectory(params.optString("path", "")));
            case "createDirectory":
                return boolResult(lib.nativeCreateDirectory(params.optString("path", "")), "createDirectory failed");
            case "deleteFile":
                return boolResult(lib.nativeDeleteFile(params.optString("path", "")), "deleteFile failed");
            case "deleteDirectory":
                return boolResult(lib.nativeDeleteDirectory(params.optString("path", ""), params.optBoolean("recursive", false)), "deleteDirectory failed");
            case "copyFile":
                return handleCopyOrMove(params, false);
            case "moveFile":
                return handleCopyOrMove(params, true);
            case "renameFile":
                return boolResult(lib.nativeRenameFile(params.optString("oldPath", ""), params.optString("newPath", "")), "renameFile failed");
            case "getFileInfo":
                return ok(lib.nativeGetFileInfo(params.optString("path", "")));
            case "searchFiles":
                return ok(lib.nativeSearchFiles(
                        params.optString("dir", ""),
                        params.optString("pattern", "*"),
                        parseBoolean(params.opt("recursive"), true)
                ));
            case "readTextFile":
                return ok(lib.nativeReadTextFile(params.optString("path", "")));
            case "writeTextFile":
                return boolResult(lib.nativeWriteTextFile(params.optString("path", ""), params.optString("content", "")), "writeTextFile failed");
            case "fileExists":
                return ok(String.valueOf(lib.nativeFileExists(params.optString("path", ""))));
            case "getFileSize":
                return ok(String.valueOf(lib.nativeGetFileSize(params.optString("path", ""))));
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
        return ok(String.valueOf(result));
    }

    private String emptyJson(String value) {
        return value == null || value.trim().isEmpty() ? "{}" : value;
    }

    private String ok(String output) throws Exception {
        return new JSONObject().put("success", true).put("output", output).toString();
    }

    private String boolResult(boolean value, String error) throws Exception {
        JSONObject json = new JSONObject().put("success", value).put("output", String.valueOf(value));
        if (!value) json.put("error", error);
        return json.toString();
    }

    private String error(String message) throws Exception {
        return new JSONObject().put("success", false).put("error", message).toString();
    }
}
