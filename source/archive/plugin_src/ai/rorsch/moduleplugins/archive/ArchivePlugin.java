package ai.rorsch.moduleplugins.archive;

import android.content.Context;
import ai.rorsch.pandagenie.module.runtime.ModulePlugin;
import ai.rorsch.pandagenie.nativelib.ArchiveLib;
import org.json.JSONObject;
import java.io.File;

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
                ), "decompressZip");
            case "decompressTar":
                return boolResult(lib.decompressTar(
                        params.optString("archivePath", params.optString("path", "")),
                        params.optString("outputDir", "")
                ), "decompressTar");
            case "decompressTarGz":
                return boolResult(lib.decompressTarGz(
                        params.optString("archivePath", params.optString("path", "")),
                        params.optString("outputDir", "")
                ), "decompressTarGz");
            case "decompressGz":
                return boolResult(lib.decompressGz(
                        params.optString("archivePath", params.optString("path", "")),
                        params.optString("outputPath", "")
                ), "decompressGz");
            case "compressZip": {
                String[] inputs = splitPaths(params.optString("inputPaths", params.optString("input", "")));
                String output = params.optString("outputPath", params.optString("output", ""));
                String pwd = params.optString("password", "");
                String preCheck = validateCompressArgs(inputs, output);
                if (preCheck != null) return error(preCheck);
                ensureParentDir(output);
                return boolResult(lib.compressZip(inputs, output, pwd), "compressZip");
            }
            case "compressTar": {
                String[] inputs = splitPaths(params.optString("inputPaths", params.optString("input", "")));
                String output = params.optString("outputPath", params.optString("output", ""));
                String preCheck = validateCompressArgs(inputs, output);
                if (preCheck != null) return error(preCheck);
                ensureParentDir(output);
                return boolResult(lib.compressTar(inputs, output), "compressTar");
            }
            case "compressTarGz": {
                String[] inputs = splitPaths(params.optString("inputPaths", params.optString("input", "")));
                String output = params.optString("outputPath", params.optString("output", ""));
                String preCheck = validateCompressArgs(inputs, output);
                if (preCheck != null) return error(preCheck);
                ensureParentDir(output);
                return boolResult(lib.compressTarGz(inputs, output), "compressTarGz");
            }
            case "compressGz": {
                String input = params.optString("inputPath", params.optString("input", ""));
                String output = params.optString("outputPath", params.optString("output", ""));
                if (input.isEmpty()) return error("inputPath is empty");
                if (output.isEmpty()) return error("outputPath is empty");
                if (!new File(input).exists()) return error("File not found: " + input);
                ensureParentDir(output);
                return boolResult(lib.compressGz(input, output), "compressGz");
            }
            case "listContents":
                return ok(lib.listContents(params.optString("archivePath", params.optString("path", ""))));
            default:
                return error("Unsupported action: " + action);
        }
    }

    private String validateCompressArgs(String[] inputs, String output) {
        if (inputs.length == 0) return "inputPaths is empty";
        if (output.isEmpty()) return "outputPath is empty";
        for (String p : inputs) {
            if (!new File(p).exists()) return "File not found: " + p;
            if (!new File(p).canRead()) return "Cannot read file: " + p;
        }
        return null;
    }

    private void ensureParentDir(String path) {
        File parent = new File(path).getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
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

    private String boolResult(boolean value, String action) throws Exception {
        JSONObject json = new JSONObject().put("success", value).put("output", String.valueOf(value));
        if (!value) json.put("error", action + " failed in native code (check logcat tag=ArchiveModule for details)");
        return json.toString();
    }

    private String error(String message) throws Exception {
        return new JSONObject().put("success", false).put("error", message).toString();
    }
}
