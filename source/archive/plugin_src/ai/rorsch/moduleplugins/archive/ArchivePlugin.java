package ai.rorsch.moduleplugins.archive;

import android.content.Context;
import android.os.Environment;
import ai.rorsch.pandagenie.module.runtime.ModulePlugin;
import ai.rorsch.pandagenie.nativelib.ArchiveLib;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.Locale;

/**
 * 压缩与解压模块插件：对 ZIP、TAR、TAR.GZ、GZ 等格式进行打包、解包及列出归档内容。
 * <p>
 * <b>模块用途：</b>在沙箱/模块运行时中提供文件归档相关能力，底层通过 {@link ArchiveLib} 调用 Native 实现。
 * </p>
 * <p>
 * <b>提供的 API（{@code action}）：</b>
 * {@code decompressZip}、{@code decompressTar}、{@code decompressTarGz}、{@code decompressGz}、
 * {@code compressZip}、{@code compressTar}、{@code compressTarGz}、{@code compressGz}、{@code listContents}。
 * 参数与路径均通过 {@code paramsJson} 传递（如 {@code archivePath}/{@code path}、{@code outputDir}、{@code password} 等）。
 * </p>
 * <p>
 * <b>加载方式：</b>由 {@code ModuleRuntime} 通过反射加载本类并实现 {@link ModulePlugin} 后调用 {@link #invoke}。
 * </p>
 */
public class ArchivePlugin implements ModulePlugin {
    /** 原生归档库封装，执行实际的压缩/解压/列举逻辑 */
    private final ArchiveLib lib = new ArchiveLib();

    /**
     * 根据 {@code action} 分发到对应的压缩或解压操作。
     *
     * @param context    Android 上下文（本模块主要使用文件路径，部分场景保留接口一致性）
     * @param action     操作名，见类说明中的 API 列表
     * @param paramsJson JSON 参数字符串；空则按 {@code {}} 解析
     * @return JSON 字符串：通常含 {@code success}、{@code output}，失败时含 {@code error}；成功时可能含 {@code _displayText}
     * @throws Exception JSON 解析或序列化等异常
     */
    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        // 将参数解析为 JSONObject，空字符串视为空对象避免解析异常
        JSONObject params = new JSONObject(emptyJson(paramsJson));
        switch (action) {
            case "decompressZip": {
                String outDir = resolveStoragePath(params.optString("outputDir", ""));
                boolean ok = lib.decompressZip(
                        resolveStoragePath(params.optString("archivePath", params.optString("path", ""))),
                        outDir,
                        params.optString("password", "")
                );
                return boolResult(ok, "decompressZip", ok ? formatDecompressDisplay(outDir, false) : null);
            }
            case "decompressTar": {
                String outDir = resolveStoragePath(params.optString("outputDir", ""));
                boolean ok = lib.decompressTar(
                        resolveStoragePath(params.optString("archivePath", params.optString("path", ""))),
                        outDir
                );
                return boolResult(ok, "decompressTar", ok ? formatDecompressDisplay(outDir, false) : null);
            }
            case "decompressTarGz": {
                String outDir = resolveStoragePath(params.optString("outputDir", ""));
                boolean ok = lib.decompressTarGz(
                        resolveStoragePath(params.optString("archivePath", params.optString("path", ""))),
                        outDir
                );
                return boolResult(ok, "decompressTarGz", ok ? formatDecompressDisplay(outDir, false) : null);
            }
            case "decompressGz": {
                String outPath = resolveStoragePath(params.optString("outputPath", ""));
                boolean ok = lib.decompressGz(
                        resolveStoragePath(params.optString("archivePath", params.optString("path", ""))),
                        outPath
                );
                return boolResult(ok, "decompressGz", ok ? formatDecompressDisplay(outPath, true) : null);
            }
            case "compressZip": {
                String[] inputs = extractPaths(params, "inputPaths", "input");
                for (int i = 0; i < inputs.length; i++) inputs[i] = resolveStoragePath(inputs[i]);
                String output = resolveStoragePath(params.optString("outputPath", params.optString("output", "")));
                String pwd = params.optString("password", "");
                String preCheck = validateCompressArgs(inputs, output);
                if (preCheck != null) return error(preCheck);
                ensureParentDir(output);
                boolean ok = lib.compressZip(inputs, output, pwd);
                JSONArray rc = null;
                if (ok) {
                    rc = new JSONArray();
                    rc.put(richFile(output, null, "application/zip"));
                }
                return boolResult(ok, "compressZip", ok ? formatCompressDisplay(output) : null, rc);
            }
            case "compressTar": {
                String[] inputs = extractPaths(params, "inputPaths", "input");
                for (int i = 0; i < inputs.length; i++) inputs[i] = resolveStoragePath(inputs[i]);
                String output = resolveStoragePath(params.optString("outputPath", params.optString("output", "")));
                String preCheck = validateCompressArgs(inputs, output);
                if (preCheck != null) return error(preCheck);
                ensureParentDir(output);
                boolean ok = lib.compressTar(inputs, output);
                JSONArray rc = null;
                if (ok) {
                    rc = new JSONArray();
                    rc.put(richFile(output, null, "application/x-tar"));
                }
                return boolResult(ok, "compressTar", ok ? formatCompressDisplay(output) : null, rc);
            }
            case "compressTarGz": {
                String[] inputs = extractPaths(params, "inputPaths", "input");
                for (int i = 0; i < inputs.length; i++) inputs[i] = resolveStoragePath(inputs[i]);
                String output = resolveStoragePath(params.optString("outputPath", params.optString("output", "")));
                String preCheck = validateCompressArgs(inputs, output);
                if (preCheck != null) return error(preCheck);
                ensureParentDir(output);
                boolean ok = lib.compressTarGz(inputs, output);
                JSONArray rc = null;
                if (ok) {
                    rc = new JSONArray();
                    rc.put(richFile(output, null, "application/gzip"));
                }
                return boolResult(ok, "compressTarGz", ok ? formatCompressDisplay(output) : null, rc);
            }
            case "compressGz": {
                String input = resolveStoragePath(params.optString("inputPath", params.optString("input", "")));
                String output = resolveStoragePath(params.optString("outputPath", params.optString("output", "")));
                if (input.isEmpty()) return error("inputPath is empty");
                if (output.isEmpty()) return error("outputPath is empty");
                if (!new File(input).exists()) return error("File not found: " + input);
                ensureParentDir(output);
                boolean ok = lib.compressGz(input, output);
                JSONArray rc = null;
                if (ok) {
                    rc = new JSONArray();
                    rc.put(richFile(output, null, "application/gzip"));
                }
                return boolResult(ok, "compressGz", ok ? formatCompressDisplay(output) : null, rc);
            }
            case "listContents": {
                String raw = lib.listContents(resolveStoragePath(params.optString("archivePath", params.optString("path", ""))));
                return ok(raw, formatListContentsDisplay(raw));
            }
            default:
                return error("Unsupported action: " + action);
        }
    }

    /**
     * 校验压缩操作的输入文件列表与输出路径是否合法。
     *
     * @param inputs 待打包的本地文件路径数组
     * @param output 输出归档文件路径
     * @return 若校验失败返回英文错误说明字符串；全部通过返回 {@code null}
     */
    private String validateCompressArgs(String[] inputs, String output) {
        if (inputs.length == 0) return "inputPaths is empty";
        if (output.isEmpty()) return "outputPath is empty";
        for (String p : inputs) {
            // 每个待打包路径必须存在且可读，避免原生层出现难排查错误
            if (!new File(p).exists()) return "File not found: " + p;
            if (!new File(p).canRead()) return "Cannot read file: " + p;
        }
        return null;
    }

    /**
     * 确保输出文件父目录存在，不存在则递归创建。
     *
     * @param path 文件完整路径（将取其父目录）
     */
    private void ensureParentDir(String path) {
        File parent = new File(path).getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
    }

    /**
     * 从 params 中提取路径数组，优先尝试 JSON 数组类型，回退到字符串拆分。
     */
    private String[] extractPaths(JSONObject params, String... keys) {
        for (String key : keys) {
            if (!params.has(key)) continue;
            Object val = params.opt(key);
            if (val instanceof JSONArray) {
                JSONArray arr = (JSONArray) val;
                String[] result = new String[arr.length()];
                for (int i = 0; i < arr.length(); i++) {
                    result[i] = arr.optString(i, "").trim();
                }
                return result;
            }
            String str = params.optString(key, "").trim();
            if (!str.isEmpty()) return splitPaths(str);
        }
        return new String[0];
    }

    /**
     * 将路径参数拆分为数组。支持两种格式：
     * 1. JSON 数组字符串: ["/path/a", "/path/b"]
     * 2. 逗号分隔字符串: /path/a,/path/b
     */
    private String[] splitPaths(String raw) {
        if (raw == null || raw.trim().isEmpty()) return new String[0];
        String trimmed = raw.trim();
        if (trimmed.startsWith("[")) {
            try {
                JSONArray arr = new JSONArray(trimmed);
                String[] result = new String[arr.length()];
                for (int i = 0; i < arr.length(); i++) {
                    result[i] = arr.getString(i).trim();
                }
                return result;
            } catch (Exception ignored) {
                // Not valid JSON array, strip brackets and fall through
                if (trimmed.endsWith("]")) {
                    trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
                }
            }
        }
        // Strip surrounding quotes from individual paths
        String[] parts = trimmed.split(",");
        java.util.List<String> cleaned = new java.util.ArrayList<>();
        for (String p : parts) {
            String s = p.trim();
            if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
                s = s.substring(1, s.length() - 1).trim();
            }
            if (!s.isEmpty()) cleaned.add(s);
        }
        return cleaned.toArray(new String[0]);
    }

    /**
     * 将 null 或空白参数字符串规范为 JSON 空对象字面量，便于 {@link JSONObject} 构造。
     *
     * @param value 调用方传入的 paramsJson
     * @return 非空则原样返回，否则返回 {@code "{}"}
     */
    private String emptyJson(String value) {
        return value == null || value.trim().isEmpty() ? "{}" : value;
    }

    /** 列表类展示时最多展示的条目数，超出部分以省略提示 */
    private static final int DISPLAY_LIST_MAX = 20;

    private static boolean isZh() {
        try {
            return Locale.getDefault().getLanguage().toLowerCase(Locale.ROOT).startsWith("zh");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 构造仅含成功标志与输出字段的 JSON（无展示文案）。
     *
     * @param output 放入 {@code output} 字段的字符串
     * @return JSON 字符串
     * @throws Exception JSON 构造异常
     */
    private String ok(String output) throws Exception {
        return ok(output, null);
    }

    /**
     * 构造成功响应 JSON，可选附带界面展示用 {@code _displayText}。
     *
     * @param output       业务输出字符串
     * @param displayText  可选的人类可读摘要；null 或空则不写入
     * @return JSON 字符串
     * @throws Exception JSON 构造异常
     */
    private String ok(String output, String displayText) throws Exception {
        JSONObject j = new JSONObject().put("success", true).put("output", output);
        if (displayText != null && !displayText.isEmpty()) j.put("_displayText", displayText);
        return j.toString();
    }

    /**
     * 根据布尔结果构造 JSON：成功时可带展示文案，失败时写入原生层失败提示。
     *
     * @param value               原生调用是否成功
     * @param action              当前操作名，用于失败时的错误信息前缀
     * @param successDisplayText  成功时的展示文案；失败时忽略
     * @return JSON 字符串，含 {@code success} 与 {@code output}（布尔字符串形式）
     * @throws Exception JSON 构造异常
     */
    private String boolResult(boolean value, String action, String successDisplayText) throws Exception {
        return boolResult(value, action, successDisplayText, null);
    }

    /**
     * 根据布尔结果构造 JSON：成功时可带展示文案与结构化 {@code _richContent}，失败时写入原生层失败提示。
     *
     * @param value               原生调用是否成功
     * @param action              当前操作名，用于失败时的错误信息前缀
     * @param successDisplayText  成功时的展示文案；失败时忽略
     * @param richContent         成功时的富内容数组；失败或非空时仅在成功时写入
     * @return JSON 字符串，含 {@code success} 与 {@code output}（布尔字符串形式）
     * @throws Exception JSON 构造异常
     */
    private String boolResult(boolean value, String action, String successDisplayText, JSONArray richContent) throws Exception {
        JSONObject json = new JSONObject().put("success", value).put("output", String.valueOf(value));
        if (!value) {
            json.put("error", action + " failed in native code (check logcat tag=ArchiveModule for details)");
        } else if (successDisplayText != null && !successDisplayText.isEmpty()) {
            json.put("_displayText", successDisplayText);
        }
        if (value && richContent != null && richContent.length() > 0) {
            json.put("_richContent", richContent);
        }
        return json.toString();
    }

    private static JSONObject richFile(String path, String title, String mimeType) throws Exception {
        JSONObject rc = new JSONObject();
        rc.put("type", "file");
        rc.put("path", path);
        if (title != null) rc.put("title", title);
        if (mimeType != null) rc.put("mimeType", mimeType);
        File f = new File(path);
        if (f.exists()) rc.put("size", f.length());
        return rc;
    }

    /**
     * 统计解压结果路径下的文件数量（目录递归或单文件）。
     *
     * @param root              解压输出根路径（目录或文件）
     * @param singleOutputFile  是否为“单文件输出”（如 gzip 解压）
     * @return 识别到的文件个数
     */
    private static int countExtractedFiles(File root, boolean singleOutputFile) {
        if (singleOutputFile) {
            return root.exists() && root.isFile() ? 1 : 0;
        }
        return countFilesUnderDir(root);
    }

    /**
     * 递归统计目录下所有文件（不含目录本身）数量。
     *
     * @param dir 目录
     * @return 文件总数；目录无效时返回 0
     */
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

    /**
     * 格式化解压成功的界面展示文案。
     *
     * @param outputPath 输出目录或输出文件路径
     * @param singleFile 是否为单文件输出模式
     * @return 多行文本，含提取文件数与路径
     */
    private static String formatDecompressDisplay(String outputPath, boolean singleFile) {
        int n = countExtractedFiles(new File(outputPath), singleFile);
        if (isZh()) {
            return "📦 已解压\n━━━━━━━━━━━━━━\n▸ " + n + " 个文件\n▸ 输出: " + outputPath;
        }
        return "📦 Extracted\n━━━━━━━━━━━━━━\n▸ Files: " + n + " extracted\n▸ Output: " + outputPath;
    }

    /**
     * 格式化压缩成功的界面展示文案（含输出路径与人类可读大小）。
     *
     * @param outputPath 生成的归档文件路径
     * @return 多行展示文本
     */
    private static String formatCompressDisplay(String outputPath) {
        long len = new File(outputPath).length();
        double mb = len / (1024.0 * 1024.0);
        String sizeStr = len < 1024 * 1024
                ? String.format(Locale.US, "%.2f KB", len / 1024.0)
                : String.format(Locale.US, "%.2f MB", mb);
        if (isZh()) {
            return "📦 已压缩\n━━━━━━━━━━━━━━\n▸ 输出: " + outputPath + "\n▸ 大小: " + sizeStr;
        }
        return "📦 Compressed\n━━━━━━━━━━━━━━\n▸ Output: " + outputPath + "\n▸ Size: " + sizeStr;
    }

    /**
     * 将 {@code listContents} 返回的 JSON 数组解析为人类可读的归档内容列表（截断展示）。
     *
     * @param rawJson 原生层返回的 JSON 数组字符串
     * @return 格式化后的展示文本；解析失败时返回简短标题
     */
    private static String formatListContentsDisplay(String rawJson) {
        try {
            JSONArray arr = new JSONArray(rawJson);
            int total = arr.length();
            StringBuilder sb = new StringBuilder();
            if (isZh()) {
                sb.append("📦 压缩包内容\n━━━━━━━━━━━━━━\n").append(total).append(" 个文件\n");
            } else {
                sb.append("📦 Archive Contents\n━━━━━━━━━━━━━━\n").append(total).append(" files\n");
            }
            int show = Math.min(DISPLAY_LIST_MAX, total);
            for (int i = 0; i < show; i++) {
                JSONObject e = arr.optJSONObject(i);
                String name = e != null ? e.optString("name", "?") : "?";
                sb.append(i + 1).append(". ").append(name).append("\n");
            }
            if (total > show) {
                if (isZh()) {
                    sb.append("… (+").append(total - show).append(" 更多)");
                } else {
                    sb.append("… (+").append(total - show).append(" more)");
                }
            }
            return sb.toString().trim();
        } catch (Exception ignored) {
            return isZh() ? "📦 压缩包内容\n━━━━━━━━━━━━━━\n" : "📦 Archive Contents\n━━━━━━━━━━━━━━\n";
        }
    }

    /**
     * 构造标准失败响应 JSON。
     *
     * @param message 错误说明
     * @return 含 {@code success:false} 与 {@code error} 的 JSON 字符串
     * @throws Exception JSON 构造异常
     */
    private String error(String message) throws Exception {
        return new JSONObject().put("success", false).put("error", message).toString();
    }

    private static String resolveStoragePath(String path) {
        String canonical = path.replace("\\", "/");
        if (canonical.equals("/sdcard") || canonical.startsWith("/sdcard/")) {
            String realRoot = Environment.getExternalStorageDirectory().getAbsolutePath();
            if (!realRoot.equals("/sdcard")) {
                return canonical.replaceFirst("/sdcard", realRoot);
            }
        }
        return path;
    }
}
