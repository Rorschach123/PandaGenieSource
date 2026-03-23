package ai.rorsch.moduleplugins.archive;

import android.content.Context;
import ai.rorsch.pandagenie.module.runtime.ModulePlugin;
import ai.rorsch.pandagenie.nativelib.ArchiveLib;
import org.json.JSONObject;

public class ArchivePlugin implements ModulePlugin {
    private final ArchiveLib lib = new ArchiveLib();

    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        JSONObject params = new JSONObject(emptyJson(paramsJson));
        switch (action) {
            case "decompressZip":
                return boolResult(lib.decompressZip(
                        params.optString("archivePath", params.optString("path", "")),
                        params.optString("outputDir", ""),
                        params.optString("password", "")
                ), "decompressZip failed");
            case "decompressTar":
                return boolResult(lib.decompressTar(
                        params.optString("archivePath", params.optString("path", "")),
                        params.optString("outputDir", "")
                ), "decompressTar failed");
            case "decompressTarGz":
                return boolResult(lib.decompressTarGz(
                        params.optString("archivePath", params.optString("path", "")),
                        params.optString("outputDir", "")
                ), "decompressTarGz failed");
            case "decompressGz":
                return boolResult(lib.decompressGz(
                        params.optString("archivePath", params.optString("path", "")),
                        params.optString("outputPath", "")
                ), "decompressGz failed");
            case "compressZip":
                return boolResult(lib.compressZip(
                        splitPaths(params.optString("inputPaths", params.optString("input", ""))),
                        params.optString("outputPath", params.optString("output", "")),
                        params.optString("password", "")
                ), "compressZip failed");
            case "compressTar":
                return boolResult(lib.compressTar(
                        splitPaths(params.optString("inputPaths", params.optString("input", ""))),
                        params.optString("outputPath", params.optString("output", ""))
                ), "compressTar failed");
            case "compressTarGz":
                return boolResult(lib.compressTarGz(
                        splitPaths(params.optString("inputPaths", params.optString("input", ""))),
                        params.optString("outputPath", params.optString("output", ""))
                ), "compressTarGz failed");
            case "compressGz":
                return boolResult(lib.compressGz(
                        params.optString("inputPath", params.optString("input", "")),
                        params.optString("outputPath", params.optString("output", ""))
                ), "compressGz failed");
            case "listContents":
                return ok(lib.listContents(params.optString("archivePath", params.optString("path", ""))));
            default:
                return error("Unsupported action: " + action);
        }
    }

    private String[] splitPaths(String raw) {
        if (raw == null || raw.trim().isEmpty()) return new String[0];
        String[] parts = raw.split(",");
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
        }
        return parts;
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
