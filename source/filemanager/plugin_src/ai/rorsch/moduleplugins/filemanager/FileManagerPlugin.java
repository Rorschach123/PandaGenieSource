package ai.rorsch.moduleplugins.filemanager;

import android.content.Context;
import android.os.Environment;
import ai.rorsch.pandagenie.module.runtime.HtmlOutputHelper;
import ai.rorsch.pandagenie.module.runtime.ModulePlugin;
import ai.rorsch.pandagenie.nativelib.FileManagerLib;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * PandaGenie 文件管理器模块插件。
 * <p>
 * <b>模块用途：</b>通过 JNI 封装的原生库 {@link FileManagerLib} 对本地文件系统进行列举、创建、删除、复制、移动、重命名、
 * 搜索、读写文本及查询元数据等操作，供宿主应用中的 AI/任务流程调用。
 * </p>
 * <p>
 * <b>对外 API（通过 {@code action} 名称区分）：</b>
 * {@code listDirectory}、{@code createDirectory}、{@code deleteFile}、{@code deleteDirectory}、
 * {@code copyFile}、{@code moveFile}、{@code renameFile}、{@code getFileInfo}、{@code searchFiles}、
 * {@code readTextFile}、{@code writeTextFile}、{@code fileExists}、{@code getFileSize}。
 * </p>
 * <p>
 * 本类实现 {@link ModulePlugin}，由应用侧 {@code ModuleRuntime} 通过反射加载并调用 {@link #invoke}。
 * </p>
 */
public class FileManagerPlugin implements ModulePlugin {
    private static final int DISPLAY_LIST_MAX = 15;
    private static final int READ_TEXT_DISPLAY_MAX = 4000;
    private static final int PATH_MAX_DISPLAY = 45;
    private static final int BATCH_DISPLAY_MAX = 5;
    private static final SimpleDateFormat SDF = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

    private final FileManagerLib lib = new FileManagerLib();

    private static boolean isZh() {
        try {
            return Locale.getDefault().getLanguage().toLowerCase(Locale.ROOT).startsWith("zh");
        } catch (Exception e) {
            return false;
        }
    }

    private static String displayPath(String path) {
        String p = path.replace("/storage/emulated/0/", "/sdcard/")
                       .replace("/storage/emulated/0", "/sdcard");
        if (p.length() <= 20) return p;
        int keep = 8;
        return p.substring(0, keep) + "…" + p.substring(p.length() - keep);
    }

    private static String fullDisplayPath(String path) {
        return path.replace("/storage/emulated/0/", "/sdcard/")
                   .replace("/storage/emulated/0", "/sdcard");
    }

    /**
     * 模块统一入口：根据 {@code action} 分发到对应原生方法，并包装为 JSON 字符串返回。
     *
     * @param context    Android 上下文（部分 action 未使用，为接口统一保留）
     * @param action     操作名，与 switch 分支一一对应
     * @param paramsJson 该操作的 JSON 参数字符串；空或非法时按空对象 {@code {}} 解析
     * @return JSON 字符串，通常含 {@code success}、{@code output}，成功时可选 {@code _displayText} 供 UI 展示
     * @throws Exception 解析参数或构建 JSON 时的异常
     */
    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        JSONObject params = new JSONObject(emptyJson(paramsJson));
        switch (action) {
            case "listDirectory": {
                String path = resolveStoragePath(params.optString("path", ""));
                String raw = lib.nativeListDirectory(path);
                String filtered = filterListDirectory(raw);
                String display = formatListDirectoryDisplay(path, filtered);
                String html = formatListDirectoryHtml(path, filtered);
                return ok(filtered, display, null, html);
            }
            case "createDirectory": {
                String path = resolveStoragePath(params.optString("path", ""));
                boolean okb = lib.nativeCreateDirectory(path);
                return boolResult(okb, "createDirectory failed", okb ? "📁 " + (isZh() ? "目录已创建: " : "Directory created: ") + displayPath(path) : null);
            }
            case "deleteFile": {
                java.util.List<String> paths = extractPaths(params, "path", "src", "file");
                if (paths.size() <= 1) {
                    String path = resolveStoragePath(paths.isEmpty() ? "" : paths.get(0));
                    boolean okb = lib.nativeDeleteFile(path);
                    return boolResult(okb, "deleteFile failed: " + path, okb ? "🗑️ " + (isZh() ? "已删除: " : "Deleted: ") + displayPath(path) : null);
                }
                int ok2 = 0, fail2 = 0, dShown = 0;
                boolean zh = isZh();
                StringBuilder dsb = new StringBuilder();
                dsb.append("\ud83d\uddd1\ufe0f ").append(zh ? "\u6279\u91cf\u5220\u9664" : "Batch delete").append(" (").append(paths.size()).append(")\n");
                for (String rp : paths) {
                    String p = resolveStoragePath(rp);
                    if (new File(p).exists() && lib.nativeDeleteFile(p)) {
                        ok2++;
                        if (dShown < BATCH_DISPLAY_MAX) { dShown++; dsb.append("  \u2705 ").append(displayPath(p)).append("\n"); }
                    } else {
                        fail2++;
                        if (dShown < BATCH_DISPLAY_MAX) { dShown++; dsb.append("  \u274c ").append(displayPath(p)).append("\n"); }
                    }
                }
                int dTotal = ok2 + fail2;
                if (dTotal > BATCH_DISPLAY_MAX) {
                    dsb.append("  ... ").append(zh ? ("\u53ca\u5176\u4ed6 " + (dTotal - BATCH_DISPLAY_MAX) + " \u4e2a\u6587\u4ef6\uff0c\u8be6\u89c1\u4efb\u52a1\u8be6\u60c5\n") : ("and " + (dTotal - BATCH_DISPLAY_MAX) + " more, see task details\n"));
                }
                dsb.append("\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\n\u2705 ").append(ok2).append(zh ? " \u6210\u529f" : " ok");
                if (fail2 > 0) dsb.append("  \u274c ").append(fail2).append(zh ? " \u5931\u8d25" : " failed");
                return ok("deleted=" + ok2 + " failed=" + fail2, dsb.toString().trim());
            }
            case "deleteDirectory": {
                String path = resolveStoragePath(params.optString("path", ""));
                boolean okb = lib.nativeDeleteDirectory(path, params.optBoolean("recursive", false));
                return boolResult(okb, "deleteDirectory failed", okb ? "🗑️ " + (isZh() ? "已删除: " : "Deleted: ") + displayPath(path) : null);
            }
            case "copyFile":
                return handleCopyOrMove(params, false);
            case "moveFile":
                return handleCopyOrMove(params, true);
            case "renameFile": {
                String oldP = resolveStoragePath(params.optString("oldPath", ""));
                String newP = resolveStoragePath(params.optString("newPath", ""));
                boolean okb = lib.nativeRenameFile(oldP, newP);
                return boolResult(okb, "renameFile failed", okb ? formatRenameDisplay(oldP, newP) : null);
            }
            case "getFileInfo": {
                String raw = lib.nativeGetFileInfo(resolveStoragePath(params.optString("path", "")));
                String display = formatGetFileInfoDisplay(raw);
                String html = formatGetFileInfoHtml(raw);
                return ok(raw, display, null, html);
            }
            case "searchFiles": {
                String dir = params.optString("dir", "").trim();
                if (dir.isEmpty()) dir = "/sdcard";
                dir = resolveStoragePath(dir);
                String typeFilter = params.optString("type", "file").toLowerCase();
                String raw = lib.nativeSearchFiles(
                        dir,
                        params.optString("pattern", "*"),
                        parseBoolean(params.opt("recursive"), true)
                );
                String filtered = filterSearchResults(raw, typeFilter);
                String display = formatSearchFilesDisplay(filtered, typeFilter);
                String html = formatSearchFilesHtml(filtered);
                return ok(filtered, display, null, html);
            }
            case "readTextFile": {
                String raw = lib.nativeReadTextFile(resolveStoragePath(params.optString("path", "")));
                String safe = sanitize(raw);
                String codeBody = safe.length() > 20000 ? safe.substring(0, 20000) : safe;
                JSONArray rc = new JSONArray().put(richCode(codeBody, "text"));
                return ok(safe, formatReadTextFileDisplay(safe), rc);
            }
            case "writeTextFile": {
                String path = resolveStoragePath(params.optString("path", ""));
                boolean okb = lib.nativeWriteTextFile(path, params.optString("content", ""));
                JSONArray rc = okb ? new JSONArray().put(richFile(path, new File(path).getName())) : null;
                return boolResult(okb, "writeTextFile failed", okb ? "✅ " + (isZh() ? "已写入文件: " : "Written to file: ") + displayPath(path) : null, rc);
            }
            case "fileExists": {
                boolean ex = lib.nativeFileExists(resolveStoragePath(params.optString("path", "")));
                return ok(String.valueOf(ex), "📄 " + (isZh() ? "文件存在: " : "File exists: ") + (ex ? "✅" : "❌"));
            }
            case "getFileSize": {
                long sz = lib.nativeGetFileSize(resolveStoragePath(params.optString("path", "")));
                return ok(String.valueOf(sz), "📄 " + (isZh() ? "大小: " : "Size: ") + formatSizeBytes(sz));
            }
            default:
                return error("Unsupported action: " + action);
        }
    }

    /**
     * 从 JSON 中解析布尔值：支持 {@link Boolean} 或字符串 {@code "true"/"false"}。
     *
     * @param value    {@code JSONObject.opt(...)} 等得到的原始值，可为 null
     * @param fallback 无法解析时使用的默认值
     * @return 解析后的布尔结果
     */
    private boolean parseBoolean(Object value, boolean fallback) {
        if (value == null) return fallback;
        if (value instanceof Boolean) return (Boolean) value;
        return Boolean.parseBoolean(String.valueOf(value));
    }

    /**
     * 处理复制或移动：校验路径、若目标为目录则自动拼接源文件名、必要时创建父目录，再调用原生 {@code copy/move}。
     *
     * @param params JSON，需含源路径与目标路径（支持多组键名别名，见实现）
     * @param isMove {@code true} 为移动，{@code false} 为复制
     * @return 成功时 {@code success=true} 且带展示文案；失败时 {@code success=false} 与 {@code error} 说明原因
     * @throws Exception 构建响应 JSON 时可能抛出
     */
    private String handleCopyOrMove(JSONObject params, boolean isMove) throws Exception {
        String op = isMove ? "moveFile" : "copyFile";

        // Parse src — may be a single path string or a JSON array of paths
        java.util.List<String> srcPaths = extractPaths(params, "src", "source", "path");
        String dstRaw = cleanPath(params.optString("dst", params.optString("destination", params.optString("target", ""))));

        if (srcPaths.isEmpty()) return error(op + ": src is empty");
        if (dstRaw.isEmpty()) return error(op + ": dst is empty");

        String dst = resolveStoragePath(dstRaw);

        // Single file: original behavior
        if (srcPaths.size() == 1) {
            return doSingleCopyOrMove(resolveStoragePath(srcPaths.get(0)), dst, isMove, op);
        }

        // Batch: process each file, skip failures, collect results
        int success = 0, failed = 0, skipped = 0;
        int displayed = 0;
        StringBuilder sb = new StringBuilder();
        boolean zh = isZh();
        sb.append(isMove ? "\ud83d\udce6 " : "\ud83d\udccb ").append(zh ? "\u6279\u91cf" : "Batch ").append(isMove ? (zh ? "\u79fb\u52a8" : "Move") : (zh ? "\u590d\u5236" : "Copy"))
          .append(" (").append(srcPaths.size()).append(zh ? " \u4e2a\u6587\u4ef6)\n" : " files)\n");

        for (String rawSrc : srcPaths) {
            String src = resolveStoragePath(rawSrc);
            File srcFile = new File(src);
            if (isHiddenPath(src)) {
                skipped++;
                continue;
            }
            if (!srcFile.exists()) {
                skipped++;
                if (displayed < BATCH_DISPLAY_MAX) { displayed++; sb.append("  \u23ed ").append(displayPath(src)).append(zh ? " (\u4e0d\u5b58\u5728)\n" : " (not found)\n"); }
                continue;
            }
            if (srcFile.isDirectory()) {
                skipped++;
                continue;
            }
            try {
                File dstDir = new File(dst);
                if (!dstDir.exists()) dstDir.mkdirs();
                File dstFile = dstDir.isDirectory() ? new File(dstDir, srcFile.getName()) : dstDir;

                try {
                    if (srcFile.getCanonicalPath().equals(dstFile.getCanonicalPath())) {
                        skipped++;
                        continue;
                    }
                } catch (Exception ignored) {}

                File dstParent = dstFile.getParentFile();
                if (dstParent != null && !dstParent.exists()) dstParent.mkdirs();

                boolean result = isMove ? lib.nativeMoveFile(src, dstFile.getAbsolutePath())
                                        : lib.nativeCopyFile(src, dstFile.getAbsolutePath());
                if (result) {
                    success++;
                    if (displayed < BATCH_DISPLAY_MAX) { displayed++; sb.append("  \u2705 ").append(displayPath(src)).append("\n"); }
                } else {
                    failed++;
                    if (displayed < BATCH_DISPLAY_MAX) { displayed++; sb.append("  \u274c ").append(displayPath(src)).append("\n"); }
                }
            } catch (Exception e) {
                failed++;
                if (displayed < BATCH_DISPLAY_MAX) { displayed++; sb.append("  \u274c ").append(displayPath(src)).append(" (").append(e.getMessage()).append(")\n"); }
            }
        }
        int total = success + failed + skipped;
        if (total > BATCH_DISPLAY_MAX) {
            sb.append("  ... ").append(zh ? ("\u53ca\u5176\u4ed6 " + (total - BATCH_DISPLAY_MAX) + " \u4e2a\u6587\u4ef6\uff0c\u8be6\u89c1\u4efb\u52a1\u8be6\u60c5\n") : ("and " + (total - BATCH_DISPLAY_MAX) + " more, see task details\n"));
        }
        sb.append("\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\n")
          .append("\u2705 ").append(success).append(zh ? " \u6210\u529f" : " ok");
        if (failed > 0) sb.append("  \u274c ").append(failed).append(zh ? " \u5931\u8d25" : " failed");
        if (skipped > 0) sb.append("  \u23ed ").append(skipped).append(zh ? " \u8df3\u8fc7" : " skipped");

        JSONObject j = new JSONObject()
                .put("success", success > 0)
                .put("output", sanitize("moved=" + success + " failed=" + failed + " skipped=" + skipped))
                .put("_displayText", sanitize(sb.toString().trim()));
        return j.toString();
    }

    /**
     * Extract path(s) from params: handles single string, JSON array string, or JSONArray value.
     */
    private java.util.List<String> extractPaths(JSONObject params, String... keys) {
        java.util.List<String> result = new java.util.ArrayList<>();
        for (String key : keys) {
            Object val = params.opt(key);
            if (val == null) continue;
            if (val instanceof JSONArray) {
                JSONArray arr = (JSONArray) val;
                for (int i = 0; i < arr.length(); i++) result.add(cleanPath(arr.optString(i, "")));
                return result;
            }
            String raw = val.toString().trim();
            if (raw.isEmpty()) continue;
            // Try parsing as JSON array BEFORE cleanPath (which may strip brackets)
            if (raw.startsWith("[")) {
                try {
                    JSONArray arr = new JSONArray(raw);
                    for (int i = 0; i < arr.length(); i++) result.add(cleanPath(arr.optString(i, "")));
                    return result;
                } catch (Exception ignored) {}
            }
            String s = cleanPath(raw);
            if (s.isEmpty()) continue;
            result.add(s);
            return result;
        }
        return result;
    }

    /**
     * Strip decorative quotes/brackets that LLMs sometimes wrap around paths.
     */
    private static String cleanPath(String path) {
        if (path == null) return "";
        String p = path.trim();
        // Strip decorative quotes/brackets: Chinese 「」『』【】《》, full-width ""'', standard "'<>
        p = p.replaceAll("^[\u300c\u300e\u3010\u300a\u201c\u2018\"'<]+", "")
             .replaceAll("[\u300d\u300f\u3011\u300b\u201d\u2019\"'>]+$", "");
        return p.trim();
    }

    private String doSingleCopyOrMove(String src, String dst, boolean isMove, String op) throws Exception {
        if (src.isEmpty()) return error(op + ": src is empty");
        if (dst.isEmpty()) return error(op + ": dst is empty");

        File srcFile = new File(src);
        if (isHiddenPath(src)) {
            return ok("true", "\u23ed " + (isZh() ? "\u9690\u85cf\u6587\u4ef6\u5df2\u8df3\u8fc7" : "Hidden file skipped") + ": " + displayPath(src));
        }
        if (srcFile.isDirectory()) {
            return ok("true", "\u23ed " + (isZh() ? "\u76ee\u5f55\u5df2\u8df3\u8fc7" : "Directory skipped") + ": " + displayPath(src));
        }
        if (!srcFile.exists()) return error(op + ": source not found: " + src);

        File dstFile = new File(dst);
        if (dstFile.isDirectory()) {
            dstFile = new File(dstFile, srcFile.getName());
            dst = dstFile.getAbsolutePath();
        } else {
            String srcName = srcFile.getName();
            String dstName = dstFile.getName();
            if (srcName.equals(dstName)) {
                String dstParentPath = dstFile.getParent();
                String srcParentPath = srcFile.getParent();
                if (dstParentPath != null && srcParentPath != null) {
                    String srcParentName = new File(srcParentPath).getAbsolutePath();
                    if (dstParentPath.endsWith(srcParentName) && !dstParentPath.equals(srcParentName)) {
                        String realTargetDir = dstParentPath.substring(0, dstParentPath.length() - srcParentName.length());
                        if (realTargetDir.endsWith("/")) realTargetDir = realTargetDir.substring(0, realTargetDir.length() - 1);
                        dstFile = new File(realTargetDir, srcName);
                        dst = dstFile.getAbsolutePath();
                    }
                }
            }
        }

        if (isMove) {
            try {
                if (srcFile.getCanonicalPath().equals(dstFile.getCanonicalPath())) {
                    return ok("true", "⏭ " + (isZh() ? "源路径与目标路径相同，无需移动" : "Same path, skip") + "\n  " + srcFile.getCanonicalPath());
                }
            } catch (Exception ignored) {}
        }

        File dstParent = dstFile.getParentFile();
        if (dstParent != null && !dstParent.exists()) dstParent.mkdirs();

        boolean result = isMove ? lib.nativeMoveFile(src, dst) : lib.nativeCopyFile(src, dst);
        if (!result) {
            String detail = op + " failed: " + src + " -> " + dst;
            if (dstParent != null && !dstParent.canWrite()) detail += " (dst dir not writable)";
            if (!srcFile.canRead()) detail += " (src not readable)";
            return error(detail);
        }
        String disp = isMove ? formatMoveDisplay(src, dst) : formatCopyDisplay(src, dst);
        JSONArray rc = new JSONArray().put(richFile(dst, new File(dst).getName()));
        return ok(String.valueOf(result), disp, rc);
    }

    /**
     * 将空或仅空白参数的 JSON 规范化为 {@code "{}"}，避免 {@link JSONObject} 构造异常。
     *
     * @param value 调用方传入的 {@code paramsJson}
     * @return 非空则原样返回，否则返回 {@code "{}"}
     */
    private String emptyJson(String value) {
        return value == null || value.trim().isEmpty() ? "{}" : value;
    }

    /**
     * 构造仅含业务输出的成功响应（无 {@code _displayText}）。
     *
     * @param output 写入 {@code output} 字段的字符串，多为 JSON 或简单标量
     * @return 完整响应 JSON 字符串
     * @throws Exception JSON 构建异常
     */
    private String ok(String output) throws Exception {
        return ok(output, null, null);
    }

    /**
     * 构造成功响应：{@code success=true}，业务数据在 {@code output}，可选人类可读 {@code _displayText}。
     *
     * @param output      业务结果字符串
     * @param displayText 供界面展示的摘要，可为 null 或空（此时不写该字段）
     * @return JSON 字符串
     * @throws Exception JSON 构建异常
     */
    private String ok(String output, String displayText) throws Exception {
        return ok(output, displayText, null);
    }

    /**
     * 构造成功响应：可选 {@code _displayText} 与 {@code _richContent}。
     *
     * @param output       业务结果字符串
     * @param displayText  供界面展示的摘要，可为 null 或空（此时不写该字段）
     * @param richContent  富内容条目；null 或空则省略
     * @return JSON 字符串
     * @throws Exception JSON 构建异常
     */
    private String ok(String output, String displayText, JSONArray richContent) throws Exception {
        return ok(output, displayText, richContent, null);
    }

    private String ok(String output, String displayText, JSONArray richContent, String displayHtml) throws Exception {
        JSONObject j = new JSONObject().put("success", true).put("output", sanitize(output));
        if (displayText != null && !displayText.isEmpty()) j.put("_displayText", sanitize(displayText));
        if (richContent != null && richContent.length() > 0) j.put("_richContent", richContent);
        if (displayHtml != null && !displayHtml.isEmpty()) j.put("_displayHtml", displayHtml);
        return j.toString();
    }

    private String okHtml(String output, String displayText, String displayHtml) throws Exception {
        return ok(output, displayText, null, displayHtml);
    }

    private static JSONObject richFile(String path, String title) throws Exception {
        JSONObject rc = new JSONObject();
        rc.put("type", "file");
        rc.put("path", path);
        if (title != null && !title.isEmpty()) rc.put("title", title);
        return rc;
    }

    private static JSONObject richCode(String code, String language) throws Exception {
        JSONObject rc = new JSONObject();
        rc.put("type", "code");
        rc.put("code", code == null ? "" : code);
        rc.put("language", language != null ? language : "text");
        return rc;
    }

    /**
     * 布尔型操作统一响应：成功时可选附带展示文案；失败时写入 {@code error}。
     *
     * @param value               操作是否成功
     * @param errorMsg            失败时的错误信息
     * @param successDisplayText  成功时的展示文案，失败忽略
     * @return JSON 字符串
     * @throws Exception JSON 构建异常
     */
    private String boolResult(boolean value, String errorMsg, String successDisplayText) throws Exception {
        return boolResult(value, errorMsg, successDisplayText, null);
    }

    private String boolResult(boolean value, String errorMsg, String successDisplayText, JSONArray richContent) throws Exception {
        JSONObject json = new JSONObject().put("success", value).put("output", String.valueOf(value));
        if (!value) json.put("error", sanitize(errorMsg));
        else if (successDisplayText != null && !successDisplayText.isEmpty()) json.put("_displayText", sanitize(successDisplayText));
        if (value && richContent != null && richContent.length() > 0) json.put("_richContent", richContent);
        return json.toString();
    }

    /**
     * 将字节数格式化为 B/KB/MB/GB 便于阅读的字符串（用于目录列表等展示）。
     *
     * @param size 字节数
     * @return 本地化数字格式的体积字符串
     */
    private static String formatSizeBytes(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format(Locale.US, "%.1f KB", size / 1024.0);
        if (size < 1024L * 1024 * 1024) return String.format(Locale.US, "%.2f MB", size / (1024.0 * 1024));
        return String.format(Locale.US, "%.2f GB", size / (1024.0 * 1024 * 1024));
    }

    /**
     * 将 {@code nativeListDirectory} 返回的 JSON 数组格式化为带图标与条数限制的展示文本。
     *
     * @param dirPath 被列举的目录路径
     * @param rawJson 原生返回的 JSON 数组字符串
     * @return 多行展示字符串；解析失败时返回仅含标题的占位文本
     */
    private static String formatListDirectoryDisplay(String dirPath, String rawJson) {
        boolean zh = isZh();
        try {
            JSONArray arr = new JSONArray(rawJson);
            int n = arr.length();
            StringBuilder sb = new StringBuilder();
            sb.append("📂 ").append(zh ? "目录: " : "Directory: ").append(displayPath(dirPath))
              .append("\n━━━━━━━━━━━━━━\n")
              .append(n).append(zh ? " 项" : " items").append("\n");
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
            if (n > show) sb.append("… (+").append(n - show).append(zh ? " 更多)" : " more)");
            return sb.toString().trim();
        } catch (Exception ignored) {
            return "📂 " + (zh ? "目录: " : "Directory: ") + displayPath(dirPath) + "\n━━━━━━━━━━━━━━\n";
        }
    }

    /**
     * 重命名成功后的简短展示文案。
     *
     * @param oldPath 原路径
     * @param newPath 新路径
     * @return 多行展示字符串
     */
    private static String formatRenameDisplay(String oldPath, String newPath) {
        boolean zh = isZh();
        return "✏️ " + (zh ? "已重命名" : "Renamed")
                + "\n▸ " + (zh ? "原: " : "From: ") + displayPath(oldPath)
                + "\n▸ " + (zh ? "新: " : "To: ") + displayPath(newPath);
    }

    /**
     * 复制成功后的展示文案。
     *
     * @param src 源路径
     * @param dst 目标路径
     * @return 多行展示字符串
     */
    private static String formatCopyDisplay(String src, String dst) {
        boolean zh = isZh();
        return "📋 " + (zh ? "已复制" : "Copied")
                + "\n▸ " + (zh ? "来源: " : "From: ") + fullDisplayPath(src)
                + "\n▸ " + (zh ? "目标: " : "To: ") + fullDisplayPath(dst);
    }

    /**
     * 移动成功后的展示文案。
     *
     * @param src 源路径
     * @param dst 目标路径
     * @return 多行展示字符串
     */
    private static String formatMoveDisplay(String src, String dst) {
        boolean zh = isZh();
        return "📦 " + (zh ? "已移动" : "Moved")
                + "\n▸ " + (zh ? "来源: " : "From: ") + fullDisplayPath(src)
                + "\n▸ " + (zh ? "目标: " : "To: ") + fullDisplayPath(dst);
    }

    /**
     * 解析 {@code nativeGetFileInfo} 返回的 JSON，生成名称、大小、修改时间的摘要。
     *
     * @param rawJson 原生返回的 JSON 对象字符串
     * @return 展示用多行文本
     */
    private static String formatGetFileInfoDisplay(String rawJson) {
        boolean zh = isZh();
        try {
            JSONObject o = new JSONObject(rawJson);
            String name = o.optString("name", "");
            long sz = o.optLong("size", 0);
            long lm = o.optLong("lastModified", 0);
            String mod = lm > 0 ? SDF.format(new Date(lm)) : String.valueOf(lm);
            return "📄 " + (zh ? "文件信息" : "File Info") + "\n━━━━━━━━━━━━━━\n"
                    + "▸ " + (zh ? "名称: " : "Name: ") + name
                    + "\n▸ " + (zh ? "大小: " : "Size: ") + formatSizeBytes(sz)
                    + "\n▸ " + (zh ? "修改时间: " : "Modified: ") + mod;
        } catch (Exception ignored) {
            return "📄 " + (zh ? "文件信息" : "File Info") + "\n━━━━━━━━━━━━━━\n";
        }
    }

    /**
     * 将搜索结果 JSON 数组格式化为带序号的文件路径列表（条数受限）。
     *
     * @param rawJson 原生返回的路径 JSON 数组字符串
     * @return 展示文本
     */
    /**
     * Filter listDirectory results to exclude hidden entries (name starting with '.').
     */
    private static String filterListDirectory(String rawJson) {
        try {
            JSONArray arr = new JSONArray(rawJson);
            JSONArray out = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject e = arr.optJSONObject(i);
                if (e == null) continue;
                String name = e.optString("name", "");
                if (name.startsWith(".")) continue;
                out.put(e);
            }
            return out.toString();
        } catch (Exception ex) {
            return rawJson;
        }
    }

    /**
     * Check if a path contains any hidden component (directory or file starting with '.').
     */
    private static boolean isHiddenPath(String path) {
        if (path == null || path.isEmpty()) return false;
        String[] parts = path.split("/");
        for (String part : parts) {
            if (part.startsWith(".") && part.length() > 1) return true;
        }
        return false;
    }

    /**
     * Filter native search results by type and skip hidden paths.
     */
    private static String filterSearchResults(String rawJson, String typeFilter) {
        try {
            JSONArray arr = new JSONArray(rawJson);
            JSONArray out = new JSONArray();
            boolean wantFile = "file".equals(typeFilter);
            boolean wantAll = "all".equals(typeFilter);
            for (int i = 0; i < arr.length(); i++) {
                String p = arr.optString(i, "");
                if (p.isEmpty()) continue;
                if (isHiddenPath(p)) continue;
                if (wantAll) { out.put(p); continue; }
                File f = new File(p);
                if (wantFile && f.isFile()) out.put(p);
                else if (!wantFile && f.isDirectory()) out.put(p);
            }
            return out.toString();
        } catch (Exception e) {
            return rawJson;
        }
    }

    private String formatListDirectoryHtml(String path, String output) {
        try {
            boolean zh = isZh();
            JSONArray items = new JSONArray(output);
            int total = items.length();
            StringBuilder body = new StringBuilder();
            body.append(HtmlOutputHelper.muted(displayPath(path) + " — " + total + (zh ? " 项" : " items")));
            java.util.List<String[]> rows = new java.util.ArrayList<>();
            int limit = Math.min(items.length(), 25);
            for (int i = 0; i < limit; i++) {
                JSONObject item = items.optJSONObject(i);
                if (item != null) {
                    boolean isDir = item.optBoolean("isDirectory");
                    String icon = isDir ? "📁" : "📄";
                    String name = item.optString("name", "?");
                    String size;
                    if (isDir) {
                        size = "—";
                    } else {
                        String sf = item.optString("sizeFormatted", "");
                        size = !sf.isEmpty() ? sf : formatSizeBytes(item.optLong("size", 0));
                    }
                    rows.add(new String[]{icon + " " + name, size});
                }
            }
            body.append(HtmlOutputHelper.table(new String[]{zh ? "名称" : "Name", zh ? "大小" : "Size"}, rows));
            if (total > limit) {
                body.append(HtmlOutputHelper.muted("+" + (total - limit) + (zh ? " 项未显示" : " more")));
            }
            return HtmlOutputHelper.card("📂", zh ? "文件列表" : "Directory", body.toString());
        } catch (Exception e) {
            return "";
        }
    }

    private String formatGetFileInfoHtml(String output) {
        try {
            boolean zh = isZh();
            JSONObject j = new JSONObject(output);
            String name = j.optString("name", "—");
            String sizeStr = j.optString("sizeFormatted", "");
            if (sizeStr.isEmpty()) sizeStr = formatSizeBytes(j.optLong("size", 0));
            long lm = j.optLong("lastModified", 0);
            String modified;
            if (lm > 0) {
                modified = SDF.format(new Date(lm));
            } else {
                String ms = j.optString("lastModified", "");
                modified = ms.isEmpty() || "0".equals(ms) ? "—" : ms;
            }
            return HtmlOutputHelper.card("📄", name,
                    HtmlOutputHelper.keyValue(new String[][]{
                            {zh ? "路径" : "Path", displayPath(j.optString("path", ""))},
                            {zh ? "大小" : "Size", sizeStr},
                            {zh ? "修改时间" : "Modified", modified},
                            {zh ? "类型" : "Type", j.optBoolean("isDirectory") ? (zh ? "目录" : "Directory") : (zh ? "文件" : "File")},
                            {zh ? "可读" : "Readable", j.optBoolean("canRead") ? "✓" : "✗"},
                            {zh ? "可写" : "Writable", j.optBoolean("canWrite") ? "✓" : "✗"}
                    })
            );
        } catch (Exception e) {
            return "";
        }
    }

    private String formatSearchFilesHtml(String output) {
        try {
            boolean zh = isZh();
            JSONArray files = new JSONArray(output);
            int total = files.length();
            if (total == 0) return "";
            java.util.List<String[]> rows = new java.util.ArrayList<>();
            int limit = Math.min(files.length(), 20);
            for (int i = 0; i < limit; i++) {
                String path = files.optString(i, "");
                rows.add(new String[]{displayPath(path)});
            }
            String body = HtmlOutputHelper.table(new String[]{zh ? "文件" : "File"}, rows);
            if (total > limit) body += HtmlOutputHelper.muted("+" + (total - limit) + (zh ? " 个文件" : " more files"));
            return HtmlOutputHelper.card("🔍", (zh ? "搜索结果" : "Search Results") + " (" + total + ")", body);
        } catch (Exception e) {
            return "";
        }
    }

    private static String formatSearchFilesDisplay(String rawJson, String typeFilter) {
        boolean zh = isZh();
        String label;
        if ("dir".equals(typeFilter)) label = zh ? "目录" : "directories";
        else if ("all".equals(typeFilter)) label = zh ? "条目" : "entries";
        else label = zh ? "文件" : "files";
        try {
            JSONArray arr = new JSONArray(rawJson);
            int n = arr.length();
            StringBuilder sb = new StringBuilder();
            sb.append("🔍 ").append(zh ? "搜索结果" : "Search Results")
              .append("\n━━━━━━━━━━━━━━\n")
              .append(zh ? "找到 " : "Found ").append(n).append(" ").append(label).append("\n");
            int show = Math.min(DISPLAY_LIST_MAX, n);
            for (int i = 0; i < show; i++) {
                String p = arr.optString(i, "?");
                File f = new File(p);
                String icon = f.isDirectory() ? "📁" : "📄";
                sb.append(i + 1).append(". ").append(icon).append(" ").append(displayPath(p)).append("\n");
            }
            if (n > show) sb.append("… (+").append(n - show).append(zh ? " 更多)" : " more)");
            return sb.toString().trim();
        } catch (Exception ignored) {
            return "🔍 " + (zh ? "搜索结果" : "Search Results") + "\n━━━━━━━━━━━━━━\n";
        }
    }

    /**
     * 读取文本内容展示：超长时截断并加省略号，避免聊天/UI 刷屏。
     *
     * @param content 文件全文或 null
     * @return 带标题的展示块
     */
    private static String formatReadTextFileDisplay(String content) {
        String body = content == null ? "" : content;
        if (body.length() > READ_TEXT_DISPLAY_MAX) {
            body = body.substring(0, READ_TEXT_DISPLAY_MAX) + "…";
        }
        return "📄 " + (isZh() ? "文件内容" : "File Content") + "\n━━━━━━━━━━━━━━\n" + body;
    }

    /**
     * 统一错误响应格式。
     *
     * @param message 错误说明
     * @return {@code success=false} 的 JSON 字符串
     * @throws Exception JSON 构建异常
     */
    private String error(String message) throws Exception {
        return new JSONObject().put("success", false).put("error", sanitize(message)).toString();
    }

    /**
     * Sanitize a string for safe inclusion in JSON values:
     * strip null bytes, limit length, and ensure no unmatched surrogates.
     */
    private static String sanitize(String s) {
        if (s == null) return "";
        String clean = s.replace("\0", "");
        if (clean.length() > 50000) clean = clean.substring(0, 50000) + "…(truncated)";
        return clean;
    }

    private static String resolveStoragePath(String path) {
        if (path == null) return "";
        String canonical = cleanPath(path)
                .replace("\\/", "/")   // JSON-escaped slash
                .replace("\\", "/");   // Windows backslash
        // Collapse any double slashes (except protocol://)
        while (canonical.contains("//") && !canonical.contains("://")) {
            canonical = canonical.replace("//", "/");
        }
        if (canonical.isEmpty()) return "";
        if (canonical.equals("/sdcard") || canonical.startsWith("/sdcard/")) {
            String realRoot = Environment.getExternalStorageDirectory().getAbsolutePath();
            if (!realRoot.equals("/sdcard")) {
                return canonical.replaceFirst("/sdcard", realRoot);
            }
        }
        return canonical;
    }
}
