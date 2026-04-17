package ai.rorsch.moduleplugins.filemanager;

import android.content.Context;
import android.os.Environment;
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
    private static final int DISPLAY_LIST_MAX = 20;
    private static final int READ_TEXT_DISPLAY_MAX = 2000;
    private static final int PATH_MAX_DISPLAY = 45;
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
                return ok(raw, formatListDirectoryDisplay(path, raw));
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
                int ok2 = 0, fail2 = 0;
                boolean zh = isZh();
                StringBuilder dsb = new StringBuilder();
                dsb.append("🗑️ ").append(zh ? "批量删除" : "Batch delete").append(" (").append(paths.size()).append(")\n");
                for (String rp : paths) {
                    String p = resolveStoragePath(rp);
                    if (new File(p).exists() && lib.nativeDeleteFile(p)) { ok2++; dsb.append("  ✅ ").append(displayPath(p)).append("\n"); }
                    else { fail2++; dsb.append("  ❌ ").append(displayPath(p)).append("\n"); }
                }
                dsb.append("━━━━━━━━━━\n✅ ").append(ok2).append(zh ? " 成功" : " ok");
                if (fail2 > 0) dsb.append("  ❌ ").append(fail2).append(zh ? " 失败" : " failed");
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
                return ok(raw, formatGetFileInfoDisplay(raw));
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
                return ok(filtered, formatSearchFilesDisplay(filtered, typeFilter));
            }
            case "readTextFile": {
                String raw = lib.nativeReadTextFile(resolveStoragePath(params.optString("path", "")));
                String safe = sanitize(raw);
                String codeBody = safe.length() > 5000 ? safe.substring(0, 5000) : safe;
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
        StringBuilder sb = new StringBuilder();
        boolean zh = isZh();
        sb.append(isMove ? "📦 " : "📋 ").append(zh ? "批量" : "Batch ").append(isMove ? (zh ? "移动" : "Move") : (zh ? "复制" : "Copy"))
          .append(" (").append(srcPaths.size()).append(zh ? " 个文件)\n" : " files)\n");

        for (String rawSrc : srcPaths) {
            String src = resolveStoragePath(rawSrc);
            File srcFile = new File(src);
            if (!srcFile.exists()) {
                skipped++;
                sb.append("  ⏭ ").append(displayPath(src)).append(zh ? " (不存在)\n" : " (not found)\n");
                continue;
            }
            if (srcFile.isDirectory()) {
                skipped++;
                sb.append("  ⏭ ").append(displayPath(src)).append(zh ? " (是目录)\n" : " (is directory)\n");
                continue;
            }
            try {
                File dstDir = new File(dst);
                if (!dstDir.exists()) dstDir.mkdirs();
                File dstFile = dstDir.isDirectory() ? new File(dstDir, srcFile.getName()) : dstDir;

                // Skip if source is already in the target directory
                try {
                    if (srcFile.getCanonicalPath().equals(dstFile.getCanonicalPath())) {
                        skipped++;
                        sb.append("  ⏭ ").append(displayPath(src)).append(zh ? " (已在目标目录)\n" : " (already in target)\n");
                        continue;
                    }
                } catch (Exception ignored) {}

                File dstParent = dstFile.getParentFile();
                if (dstParent != null && !dstParent.exists()) dstParent.mkdirs();

                boolean result = isMove ? lib.nativeMoveFile(src, dstFile.getAbsolutePath())
                                        : lib.nativeCopyFile(src, dstFile.getAbsolutePath());
                if (result) {
                    success++;
                    sb.append("  ✅ ").append(displayPath(src)).append("\n");
                } else {
                    failed++;
                    sb.append("  ❌ ").append(displayPath(src)).append("\n");
                }
            } catch (Exception e) {
                failed++;
                sb.append("  ❌ ").append(displayPath(src)).append(" (").append(e.getMessage()).append(")\n");
            }
        }
        sb.append("━━━━━━━━━━\n")
          .append("✅ ").append(success).append(zh ? " 成功" : " ok");
        if (failed > 0) sb.append("  ❌ ").append(failed).append(zh ? " 失败" : " failed");
        if (skipped > 0) sb.append("  ⏭ ").append(skipped).append(zh ? " 跳过" : " skipped");

        boolean allOk = failed == 0 && skipped == 0;
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
            String s = cleanPath(val.toString());
            if (s.isEmpty()) continue;
            if (s.startsWith("[")) {
                try {
                    JSONArray arr = new JSONArray(s);
                    for (int i = 0; i < arr.length(); i++) result.add(cleanPath(arr.optString(i, "")));
                    return result;
                } catch (Exception ignored) {}
            }
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
        JSONObject j = new JSONObject().put("success", true).put("output", sanitize(output));
        if (displayText != null && !displayText.isEmpty()) j.put("_displayText", sanitize(displayText));
        if (richContent != null && richContent.length() > 0) j.put("_richContent", richContent);
        return j.toString();
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
     * Filter native search results by type: "file" (default), "dir", or "all".
     */
    private static String filterSearchResults(String rawJson, String typeFilter) {
        if ("all".equals(typeFilter)) return rawJson;
        try {
            JSONArray arr = new JSONArray(rawJson);
            JSONArray out = new JSONArray();
            boolean wantFile = "file".equals(typeFilter);
            for (int i = 0; i < arr.length(); i++) {
                String p = arr.optString(i, "");
                if (p.isEmpty()) continue;
                File f = new File(p);
                if (wantFile && f.isFile()) out.put(p);
                else if (!wantFile && f.isDirectory()) out.put(p);
            }
            return out.toString();
        } catch (Exception e) {
            return rawJson;
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
        if (clean.length() > 10000) clean = clean.substring(0, 10000) + "…(truncated)";
        return clean;
    }

    private static String resolveStoragePath(String path) {
        if (path == null) return "";
        String canonical = cleanPath(path).replace("\\", "/");
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
