package ai.rorsch.moduleplugins.archive;

import android.content.Context;
import ai.rorsch.pandagenie.module.runtime.ModulePlugin;
import ai.rorsch.pandagenie.nativelib.ArchiveLib;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.Locale;

public class ArchivePlugin implements ModulePlugin {
    private final ArchiveLib lib = new ArchiveLib();

    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        JSONObject params = new JSONObject(emptyJson(paramsJson));
        switch (action) {
            case "decompressZip": {
                String outDir = params.optString("outputDir", "");
                boolean ok = lib.decompressZip(
                        params.optString("archivePath", params.optString("path", "")),
                        outDir,
                        params.optString("password", "")
                );
                return boolResult(ok, "decompressZip", ok ? formatDecompressDisplay(outDir, false) : null);
            }
            case "decompressTar": {
                String outDir = params.optString("outputDir", "");
                boolean ok = lib.decompressTar(
                        params.optString("archivePath", params.optString("path", "")),
                        outDir
                );
                return boolResult(ok, "decompressTar", ok ? formatDecompressDisplay(outDir, false) : null);
            }
            case "decompressTarGz": {
                String outDir = params.optString("outputDir", "");
                boolean ok = lib.decompressTarGz(
                        params.optString("archivePath", params.optString("path", "")),
                        outDir
                );
                return boolResult(ok, "decompressTarGz", ok ? formatDecompressDisplay(outDir, false) : null);
            }
            case "decompressGz": {
                String outPath = params.optString("outputPath", "");
                boolean ok = lib.decompressGz(
                        params.optString("archivePath", params.optString("path", "")),
                        outPath
                );
                return boolResult(ok, "decompressGz", ok ? formatDecompressDisplay(outPath, true) : null);
            }
            case "compressZip": {
                String[] inputs = splitPaths(params.optString("inputPaths", params.optString("input", "")));
                String output = params.optString("outputPath", params.optString("output", ""));
                String pwd = params.optString("password", "");
                String preCheck = validateCompressArgs(inputs, output);
                if (preCheck != null) return error(preCheck);
                ensureParentDir(output);
                boolean ok = lib.compressZip(inputs, output, pwd);
                return boolResult(ok, "compressZip", ok ? formatCompressDisplay(output) : null);
            }
            case "compressTar": {
                String[] inputs = splitPaths(params.optString("inputPaths", params.optString("input", "")));
                String output = params.optString("outputPath", params.optString("output", ""));
                String preCheck = validateCompressArgs(inputs, output);
                if (preCheck != null) return error(preCheck);
                ensureParentDir(output);
                boolean ok = lib.compressTar(inputs, output);
                return boolResult(ok, "compressTar", ok ? formatCompressDisplay(output) : null);
            }
            case "compressTarGz": {
                String[] inputs = splitPaths(params.optString("inputPaths", params.optString("input", "")));
                String output = params.optString("outputPath", params.optString("output", ""));
                String preCheck = validateCompressArgs(inputs, output);
                if (preCheck != null) return error(preCheck);
                ensureParentDir(output);
                boolean ok = lib.compressTarGz(inputs, output);
                return boolResult(ok, "compressTarGz", ok ? formatCompressDisplay(output) : null);
            }
            case "compressGz": {
                String input = params.optString("inputPath", params.optString("input", ""));
                String output = params.optString("outputPath", params.optString("output", ""));
                if (input.isEmpty()) return error("inputPath is empty");
                if (output.isEmpty()) return error("outputPath is empty");
                if (!new File(input).exists()) return error("File not found: " + input);
                ensureParentDir(output);
                boolean ok = lib.compressGz(input, output);
                return boolResult(ok, "compressGz", ok ? formatCompressDisplay(output) : null);
            }
            case "listContents": {
                String raw = lib.listContents(params.optString("archivePath", params.optString("path", "")));
                return ok(raw, formatListContentsDisplay(raw));
            }
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

    private static final int DISPLAY_LIST_MAX = 20;

    private String ok(String output) throws Exception {
        return ok(output, null);
    }

    private String ok(String output, String displayText) throws Exception {
        JSONObject j = new JSONObject().put("success", true).put("output", output);
        if (displayText != null && !displayText.isEmpty()) j.put("_displayText", displayText);
        return j.toString();
    }

    private String boolResult(boolean value, String action, String successDisplayText) throws Exception {
        JSONObject json = new JSONObject().put("success", value).put("output", String.valueOf(value));
        if (!value) {
            json.put("error", action + " failed in native code (check logcat tag=ArchiveModule for details)");
        } else if (successDisplayText != null && !successDisplayText.isEmpty()) {
            json.put("_displayText", successDisplayText);
        }
        return json.toString();
    }

    private static int countExtractedFiles(File root, boolean singleOutputFile) {
        if (singleOutputFile) {
            return root.exists() && root.isFile() ? 1 : 0;
        }
        return countFilesUnderDir(root);
    }

    private static int countFilesUnderDir(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return 0;
        int n = 0;
        File[] children = dir.listFiles();
        if (children == null) return 0;
        for (File c : children) {
            if (c.isFile()) n++;
            else if (c.isDirectory()) n += countFilesUnderDir(c);
        }
        return n;
    }

    private static String formatDecompressDisplay(String outputPath, boolean singleFile) {
        int n = countExtractedFiles(new File(outputPath), singleFile);
        return "📦 Extracted\n━━━━━━━━━━━━━━\n▸ Files: " + n + " extracted\n▸ Output: " + outputPath;
    }

    private static String formatCompressDisplay(String outputPath) {
        long len = new File(outputPath).length();
        double mb = len / (1024.0 * 1024.0);
        String sizeStr = len < 1024 * 1024
                ? String.format(Locale.US, "%.2f KB", len / 1024.0)
                : String.format(Locale.US, "%.2f MB", mb);
        return "📦 Compressed\n━━━━━━━━━━━━━━\n▸ Output: " + outputPath + "\n▸ Size: " + sizeStr;
    }

    private static String formatListContentsDisplay(String rawJson) {
        try {
            JSONArray arr = new JSONArray(rawJson);
            int total = arr.length();
            StringBuilder sb = new StringBuilder();
            sb.append("📦 Archive Contents\n━━━━━━━━━━━━━━\n").append(total).append(" files\n");
            int show = Math.min(DISPLAY_LIST_MAX, total);
            for (int i = 0; i < show; i++) {
                JSONObject e = arr.optJSONObject(i);
                String name = e != null ? e.optString("name", "?") : "?";
                sb.append(i + 1).append(". ").append(name).append("\n");
            }
            if (total > show) sb.append("… (+").append(total - show).append(" more)");
            return sb.toString().trim();
        } catch (Exception ignored) {
            return "📦 Archive Contents\n━━━━━━━━━━━━━━\n";
        }
    }

    private String error(String message) throws Exception {
        return new JSONObject().put("success", false).put("error", message).toString();
    }
}
