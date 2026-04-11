package ai.rorsch.moduleplugins.file_stats;

import android.content.Context;
import android.webkit.MimeTypeMap;
import ai.rorsch.pandagenie.module.runtime.ModulePlugin;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.security.MessageDigest;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * PandaGenie 文件统计与校验模块插件。
 * <p>
 * <b>模块用途：</b>在本地文件系统上提供元信息读取、散列计算、两文件对比、校验和验证、目录体积累计与按扩展名分布、
 * 重复文件（按大小预筛选再 SHA-256）、大文件扫描、按文件名关键字搜索、文本统计及批量路径信息/哈希等能力。
 * </p>
 * <p>
 * <b>对外 API（{@code action}）：</b>{@code getFileInfo}、{@code getFileHash}、{@code compareFiles}、{@code verifyChecksum}、
 * {@code getDirStats}、{@code findDuplicates}、{@code findLargeFiles}、{@code searchByName}、{@code getTextStats}、{@code batchFileInfo}。
 * </p>
 * <p>
 * 实现 {@link ModulePlugin}，由 {@code ModuleRuntime} 反射加载；{@link Context} 当前未参与逻辑，为接口保留。
 * </p>
 */
public class FileStatsPlugin implements ModulePlugin {

    /** 展示用日期时间格式（默认 locale） */
    private static final SimpleDateFormat SDF = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
    /** 文件大小 human-readable 小数格式 */
    private static final DecimalFormat SIZE_FMT = new DecimalFormat("#,##0.##");
    /** 列表类 {@code _displayText} 最大条数 */
    private static final int DISPLAY_LIST_MAX = 20;

    private static boolean isZh() {
        try { return java.util.Locale.getDefault().getLanguage().toLowerCase(java.util.Locale.ROOT).startsWith("zh"); }
        catch (Exception e) { return false; }
    }
    private static String pgTable(String title, String[] headers, java.util.List<String[]> rows) {
        try {
            org.json.JSONObject t = new org.json.JSONObject();
            t.put("title", title);
            org.json.JSONArray h = new org.json.JSONArray();
            for (String hdr : headers) h.put(hdr);
            t.put("headers", h);
            org.json.JSONArray r = new org.json.JSONArray();
            for (String[] row : rows) { org.json.JSONArray a = new org.json.JSONArray(); for (String c : row) a.put(c); r.put(a); }
            t.put("rows", r);
            return "__pg_table__" + t.toString() + "__pg_table_end__";
        } catch (Exception e) { return title; }
    }

    /**
     * 根据 {@code action} 调用对应实现，统一包装为 {@code success/output/_displayText} 或错误 JSON。
     *
     * @param context    Android 上下文（本插件未使用）
     * @param action     操作名
     * @param paramsJson 各操作所需 JSON 参数
     * @return 响应 JSON 字符串
     * @throws Exception IO、摘要算法或 JSON 异常
     */
    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        JSONObject params = new JSONObject(emptyJson(paramsJson));
        switch (action) {
            case "getFileInfo": {
                String out = getFileInfo(params);
                return ok(out, formatStatsGetFileInfoDisplay(out));
            }
            case "getFileHash": {
                String out = getFileHash(params);
                return ok(out, formatGetFileHashDisplay(out));
            }
            case "compareFiles": {
                String out = compareFiles(params);
                return ok(out, formatCompareFilesDisplay(out));
            }
            case "verifyChecksum": {
                String out = verifyChecksum(params);
                return ok(out, formatVerifyChecksumDisplay(out));
            }
            case "getDirStats": {
                String out = getDirStats(params);
                return ok(out, formatGetDirStatsDisplay(out));
            }
            case "findDuplicates": {
                String out = findDuplicates(params);
                return ok(out, formatFindDuplicatesDisplay(out));
            }
            case "findLargeFiles": {
                String out = findLargeFiles(params);
                return ok(out, formatFindLargeFilesDisplay(out));
            }
            case "searchByName": {
                String out = searchByName(params);
                return ok(out, formatSearchByNameDisplay(out));
            }
            case "getTextStats": {
                String out = getTextStats(params);
                return ok(out, formatGetTextStatsDisplay(out));
            }
            case "batchFileInfo": {
                String out = batchFileInfo(params);
                return ok(out, formatBatchFileInfoDisplay(out));
            }
            default:
                return error("Unsupported action: " + action);
        }
    }

    // ==================== getFileInfo ====================

    /**
     * 返回单个文件或目录的路径、大小、时间、权限、扩展名、MIME 及目录子项数量等元信息。
     *
     * @param params {@code path} 必填，为绝对或相对路径
     * @return 成功为信息 JSON；失败为带 {@code error} 的 JSON 字符串
     * @throws Exception JSON 异常
     */
    private String getFileInfo(JSONObject params) throws Exception {
        String path = params.optString("path", "").trim();
        if (path.isEmpty()) return errJson("Missing parameter: path");
        File f = new File(path);
        if (!f.exists()) return errJson("File not found: " + path);

        JSONObject info = buildFileInfo(f);
        return info.toString();
    }

    /**
     * 由 {@link File} 对象组装标准信息字段。
     *
     * @param f 已存在的文件或目录
     * @return JSON 对象
     * @throws Exception JSON 异常
     */
    private JSONObject buildFileInfo(File f) throws Exception {
        JSONObject info = new JSONObject();
        info.put("path", f.getAbsolutePath());
        info.put("name", f.getName());
        info.put("isDirectory", f.isDirectory());
        info.put("isFile", f.isFile());
        info.put("size", f.length());
        info.put("sizeFormatted", formatSize(f.length()));
        info.put("lastModified", SDF.format(new Date(f.lastModified())));
        info.put("lastModifiedTs", f.lastModified());
        info.put("canRead", f.canRead());
        info.put("canWrite", f.canWrite());
        info.put("canExecute", f.canExecute());
        info.put("isHidden", f.isHidden());
        info.put("parent", f.getParent() != null ? f.getParent() : "");

        String ext = getExtension(f.getName());
        info.put("extension", ext);
        info.put("mimeType", getMimeType(ext));

        if (f.isDirectory()) {
            File[] children = f.listFiles();
            info.put("childCount", children != null ? children.length : 0);
        }

        return info;
    }

    // ==================== getFileHash ====================

    /**
     * 计算单个文件的密码学散列（支持 MD5、SHA-1、SHA-256，默认 SHA-256）。
     *
     * @param params {@code path}、可选 {@code algorithm}
     * @return 含 path、algorithm、hash、size 等字段的 JSON 字符串；目录或非文件报错
     * @throws Exception 摘要或 IO 异常
     */
    private String getFileHash(JSONObject params) throws Exception {
        String path = params.optString("path", "").trim();
        String algo = normalizeAlgo(params.optString("algorithm", "SHA-256"));
        if (path.isEmpty()) return errJson("Missing parameter: path");
        File f = new File(path);
        if (!f.exists()) return errJson("File not found: " + path);
        if (f.isDirectory()) return errJson("Cannot hash a directory: " + path);

        String hash = computeHash(f, algo);

        JSONObject result = new JSONObject();
        result.put("path", f.getAbsolutePath());
        result.put("name", f.getName());
        result.put("algorithm", algo);
        result.put("hash", hash);
        result.put("size", f.length());
        result.put("sizeFormatted", formatSize(f.length()));
        return result.toString();
    }

    // ==================== compareFiles ====================

    /**
     * 比较两文件是否内容一致：先比长度，相同再计算 SHA-256 比对。
     *
     * @param params {@code path1}、{@code path2}
     * @return JSON：{@code identical}、{@code reason}、长度与 hash 等
     * @throws Exception 散列或 JSON 异常
     */
    private String compareFiles(JSONObject params) throws Exception {
        String path1 = params.optString("path1", "").trim();
        String path2 = params.optString("path2", "").trim();
        if (path1.isEmpty() || path2.isEmpty()) return errJson("Missing parameter: path1 and path2 are required");

        File f1 = new File(path1);
        File f2 = new File(path2);
        if (!f1.exists()) return errJson("File not found: " + path1);
        if (!f2.exists()) return errJson("File not found: " + path2);

        JSONObject result = new JSONObject();
        result.put("file1", f1.getAbsolutePath());
        result.put("file2", f2.getAbsolutePath());
        result.put("size1", f1.length());
        result.put("size2", f2.length());
        result.put("size1Formatted", formatSize(f1.length()));
        result.put("size2Formatted", formatSize(f2.length()));

        if (f1.length() != f2.length()) {
            result.put("identical", false);
            result.put("reason", "Size differs: " + formatSize(f1.length()) + " vs " + formatSize(f2.length()));
            return result.toString();
        }

        // 长度相同仍可能内容不同，进一步用 SHA-256 确认
        String hash1 = computeHash(f1, "SHA-256");
        String hash2 = computeHash(f2, "SHA-256");

        result.put("hash1", hash1);
        result.put("hash2", hash2);
        result.put("identical", hash1.equals(hash2));
        if (!hash1.equals(hash2)) {
            result.put("reason", "Same size but different content (SHA-256 mismatch)");
        } else {
            result.put("reason", "Files are identical");
        }
        return result.toString();
    }

    // ==================== verifyChecksum ====================

    /**
     * 计算文件散列并与期望值忽略大小写比较。
     *
     * @param params {@code path}、{@code expectedHash}、可选 {@code algorithm}
     * @return JSON：{@code verified}、{@code actualHash}、{@code message}
     * @throws Exception 摘要异常
     */
    private String verifyChecksum(JSONObject params) throws Exception {
        String path = params.optString("path", "").trim();
        String expected = params.optString("expectedHash", "").trim();
        String algo = normalizeAlgo(params.optString("algorithm", "SHA-256"));
        if (path.isEmpty()) return errJson("Missing parameter: path");
        if (expected.isEmpty()) return errJson("Missing parameter: expectedHash");

        File f = new File(path);
        if (!f.exists()) return errJson("File not found: " + path);

        String actual = computeHash(f, algo);
        boolean match = actual.equalsIgnoreCase(expected);

        JSONObject result = new JSONObject();
        result.put("path", f.getAbsolutePath());
        result.put("algorithm", algo);
        result.put("expectedHash", expected);
        result.put("actualHash", actual);
        result.put("verified", match);
        result.put("message", match ? "Checksum matches" : "Checksum MISMATCH");
        return result.toString();
    }

    // ==================== getDirStats ====================

    /**
     * 递归或仅当前层统计目录下文件总大小、文件数、子目录数，并按扩展名聚合占用（按总大小降序输出）。
     *
     * @param params {@code path}、{@code recursive} 默认 true
     * @return JSON 统计结果
     * @throws Exception JSON 异常
     */
    private String getDirStats(JSONObject params) throws Exception {
        String path = params.optString("path", "").trim();
        boolean recursive = params.optBoolean("recursive", true);
        if (path.isEmpty()) return errJson("Missing parameter: path");

        File dir = new File(path);
        if (!dir.exists()) return errJson("Directory not found: " + path);
        if (!dir.isDirectory()) return errJson("Not a directory: " + path);

        long[] counts = new long[3]; // [totalSize, fileCount, dirCount]
        Map<String, long[]> extMap = new HashMap<>(); // ext -> [count, totalSize]
        collectDirStats(dir, recursive, counts, extMap);

        JSONObject result = new JSONObject();
        result.put("path", dir.getAbsolutePath());
        result.put("totalSize", counts[0]);
        result.put("totalSizeFormatted", formatSize(counts[0]));
        result.put("fileCount", counts[1]);
        result.put("directoryCount", counts[2]);

        List<Map.Entry<String, long[]>> sorted = new ArrayList<>(extMap.entrySet());
        Collections.sort(sorted, new Comparator<Map.Entry<String, long[]>>() {
            @Override
            public int compare(Map.Entry<String, long[]> a, Map.Entry<String, long[]> b) {
                return Long.compare(b.getValue()[1], a.getValue()[1]);
            }
        });

        JSONArray breakdown = new JSONArray();
        for (Map.Entry<String, long[]> entry : sorted) {
            JSONObject item = new JSONObject();
            item.put("extension", entry.getKey());
            item.put("count", entry.getValue()[0]);
            item.put("totalSize", entry.getValue()[1]);
            item.put("totalSizeFormatted", formatSize(entry.getValue()[1]));
            breakdown.put(item);
        }
        result.put("extensionBreakdown", breakdown);
        return result.toString();
    }

    /**
     * 深度优先遍历目录：累加 {@code counts}（总字节、文件数、目录数）与按扩展名聚合。
     *
     * @param dir       当前目录
     * @param recursive 是否进入子目录
     * @param counts    长度 3：totalSize, fileCount, dirCount
     * @param extMap    扩展名 → [个数, 该扩展名总字节]
     */
    private void collectDirStats(File dir, boolean recursive, long[] counts, Map<String, long[]> extMap) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isFile()) {
                counts[0] += child.length();
                counts[1]++;
                String ext = getExtension(child.getName()).toLowerCase(Locale.ROOT);
                if (ext.isEmpty()) ext = "(no extension)";
                long[] val = extMap.get(ext);
                if (val == null) { val = new long[]{0, 0}; extMap.put(ext, val); }
                val[0]++;
                val[1] += child.length();
            } else if (child.isDirectory()) {
                counts[2]++;
                if (recursive) collectDirStats(child, true, counts, extMap);
            }
        }
    }

    // ==================== findDuplicates ====================

    /**
     * 在目录内查找内容完全相同的文件组：先按文件大小分组，仅对大小相同且数量≥2 的文件计算 SHA-256，再合并同 hash 路径。
     *
     * @param params {@code path}、{@code recursive}、{@code minSize} 默认 1024 字节，过滤极小文件
     * @return JSON：重复组列表、扫描文件数、浪费空间估算等
     * @throws Exception 散列或 JSON 异常
     */
    private String findDuplicates(JSONObject params) throws Exception {
        String path = params.optString("path", "").trim();
        boolean recursive = params.optBoolean("recursive", true);
        long minSize = params.optLong("minSize", 1024);
        if (path.isEmpty()) return errJson("Missing parameter: path");

        File dir = new File(path);
        if (!dir.exists() || !dir.isDirectory()) return errJson("Directory not found: " + path);

        Map<Long, List<File>> sizeGroups = new HashMap<>();
        collectFilesBySize(dir, recursive, minSize, sizeGroups);

        Map<String, List<String>> hashGroups = new HashMap<>();
        int scanned = 0;
        for (List<File> group : sizeGroups.values()) {
            if (group.size() < 2) continue;
            for (File f : group) {
                String hash = computeHash(f, "SHA-256");
                List<String> list = hashGroups.get(hash);
                if (list == null) { list = new ArrayList<>(); hashGroups.put(hash, list); }
                list.add(f.getAbsolutePath());
                scanned++;
            }
        }

        JSONArray duplicates = new JSONArray();
        long wastedSpace = 0;
        for (Map.Entry<String, List<String>> entry : hashGroups.entrySet()) {
            if (entry.getValue().size() < 2) continue;
            JSONObject group = new JSONObject();
            group.put("hash", entry.getKey());
            File sample = new File(entry.getValue().get(0));
            group.put("fileSize", sample.length());
            group.put("fileSizeFormatted", formatSize(sample.length()));
            group.put("count", entry.getValue().size());
            JSONArray paths = new JSONArray();
            for (String p : entry.getValue()) paths.put(p);
            group.put("files", paths);
            duplicates.put(group);
            wastedSpace += sample.length() * (entry.getValue().size() - 1);
        }

        JSONObject result = new JSONObject();
        result.put("directory", dir.getAbsolutePath());
        result.put("duplicateGroups", duplicates.length());
        result.put("filesScanned", scanned);
        result.put("wastedSpace", wastedSpace);
        result.put("wastedSpaceFormatted", formatSize(wastedSpace));
        result.put("duplicates", duplicates);
        return result.toString();
    }

    /**
     * 将满足最小大小的文件按「文件大小」分桶，为后续哈希去重做准备。
     *
     * @param dir       根目录
     * @param recursive 是否递归
     * @param minSize   最小字节数
     * @param map       文件大小 → 该大小下所有 {@link File}
     */
    private void collectFilesBySize(File dir, boolean recursive, long minSize, Map<Long, List<File>> map) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isFile() && child.length() >= minSize) {
                long size = child.length();
                List<File> list = map.get(size);
                if (list == null) { list = new ArrayList<>(); map.put(size, list); }
                list.add(child);
            } else if (child.isDirectory() && recursive) {
                collectFilesBySize(child, true, minSize, map);
            }
        }
    }

    // ==================== findLargeFiles ====================

    /**
     * 在目录中找出不低于指定 MB 阈值的文件，按大小降序，返回前 {@code limit} 条。
     *
     * @param params {@code path}、{@code minSizeMB} 默认 100、{@code recursive}、{@code limit} 默认 50
     * @return JSON：{@code files} 数组等
     * @throws Exception JSON 异常
     */
    private String findLargeFiles(JSONObject params) throws Exception {
        String path = params.optString("path", "").trim();
        double minSizeMB = params.optDouble("minSizeMB", 100);
        boolean recursive = params.optBoolean("recursive", true);
        int limit = params.optInt("limit", 50);
        if (path.isEmpty()) return errJson("Missing parameter: path");

        File dir = new File(path);
        if (!dir.exists() || !dir.isDirectory()) return errJson("Directory not found: " + path);

        long minBytes = (long) (minSizeMB * 1024 * 1024);
        List<File> largeFiles = new ArrayList<>();
        collectLargeFiles(dir, recursive, minBytes, largeFiles);

        Collections.sort(largeFiles, new Comparator<File>() {
            @Override
            public int compare(File a, File b) { return Long.compare(b.length(), a.length()); }
        });

        JSONArray arr = new JSONArray();
        int count = Math.min(limit, largeFiles.size());
        for (int i = 0; i < count; i++) {
            File f = largeFiles.get(i);
            JSONObject item = new JSONObject();
            item.put("path", f.getAbsolutePath());
            item.put("name", f.getName());
            item.put("size", f.length());
            item.put("sizeFormatted", formatSize(f.length()));
            item.put("lastModified", SDF.format(new Date(f.lastModified())));
            arr.put(item);
        }

        JSONObject result = new JSONObject();
        result.put("directory", dir.getAbsolutePath());
        result.put("minSizeMB", minSizeMB);
        result.put("found", largeFiles.size());
        result.put("showing", count);
        result.put("files", arr);
        return result.toString();
    }

    /**
     * 收集所有不低于 {@code minBytes} 的文件路径（递归可选）。
     *
     * @param dir       目录
     * @param recursive 是否递归
     * @param minBytes  最小字节
     * @param out       输出列表
     */
    private void collectLargeFiles(File dir, boolean recursive, long minBytes, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isFile() && child.length() >= minBytes) out.add(child);
            else if (child.isDirectory() && recursive) collectLargeFiles(child, true, minBytes, out);
        }
    }

    // ==================== searchByName ====================

    /**
     * 在目录树中按「文件名包含任一关键字」搜索（关键字逗号分隔，大小写不敏感），结果数受 {@code limit} 限制。
     *
     * @param params {@code path}、{@code keywords}、{@code recursive}、{@code limit} 默认 100
     * @return JSON：匹配文件列表
     * @throws Exception JSON 异常
     */
    private String searchByName(JSONObject params) throws Exception {
        String path = params.optString("path", "").trim();
        String keywordsStr = params.optString("keywords", "").trim();
        boolean recursive = params.optBoolean("recursive", true);
        int limit = params.optInt("limit", 100);
        if (path.isEmpty()) return errJson("Missing parameter: path");
        if (keywordsStr.isEmpty()) return errJson("Missing parameter: keywords");

        File dir = new File(path);
        if (!dir.exists() || !dir.isDirectory()) return errJson("Directory not found: " + path);

        String[] keywords = keywordsStr.split(",");
        for (int i = 0; i < keywords.length; i++) keywords[i] = keywords[i].trim().toLowerCase(Locale.ROOT);

        List<File> matches = new ArrayList<>();
        searchByNameRecursive(dir, keywords, recursive, limit, matches);

        JSONArray arr = new JSONArray();
        for (File f : matches) {
            JSONObject item = new JSONObject();
            item.put("path", f.getAbsolutePath());
            item.put("name", f.getName());
            item.put("isDirectory", f.isDirectory());
            item.put("size", f.length());
            item.put("sizeFormatted", formatSize(f.length()));
            item.put("lastModified", SDF.format(new Date(f.lastModified())));
            arr.put(item);
        }

        JSONObject result = new JSONObject();
        result.put("directory", dir.getAbsolutePath());
        result.put("keywords", keywordsStr);
        result.put("found", matches.size());
        result.put("files", arr);
        return result.toString();
    }

    /**
     * 递归遍历：子项文件名包含任一 keyword 则加入 {@code out}，达到 limit 提前停止。
     *
     * @param dir       当前目录
     * @param keywords  已小写化的关键字数组
     * @param recursive 是否进入子目录
     * @param limit     最多收集条数
     * @param out       结果列表
     */
    private void searchByNameRecursive(File dir, String[] keywords, boolean recursive, int limit, List<File> out) {
        if (out.size() >= limit) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (out.size() >= limit) return;
            String nameLower = child.getName().toLowerCase(Locale.ROOT);
            boolean match = false;
            for (String kw : keywords) {
                if (!kw.isEmpty() && nameLower.contains(kw)) { match = true; break; }
            }
            if (match) out.add(child);
            if (child.isDirectory() && recursive) searchByNameRecursive(child, keywords, recursive, limit, out);
        }
    }

    // ==================== getTextStats ====================

    /**
     * 按 UTF-8 逐行读取文本文件，统计总行数、空行、词数（空白分隔）及字符数（含换行近似）。
     *
     * @param params {@code path} 指向普通文件
     * @return JSON 统计字段
     * @throws Exception IO 异常
     */
    private String getTextStats(JSONObject params) throws Exception {
        String path = params.optString("path", "").trim();
        if (path.isEmpty()) return errJson("Missing parameter: path");
        File f = new File(path);
        if (!f.exists()) return errJson("File not found: " + path);
        if (f.isDirectory()) return errJson("Not a file: " + path);

        long lines = 0, blankLines = 0, words = 0, chars = 0;
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                lines++;
                // readLine 不含换行，这里 +1 近似计入换行符
                chars += line.length() + 1;
                if (line.trim().isEmpty()) {
                    blankLines++;
                } else {
                    String[] tokens = line.trim().split("\\s+");
                    words += tokens.length;
                }
            }
        } finally {
            if (reader != null) try { reader.close(); } catch (Exception ignored) {}
        }

        JSONObject result = new JSONObject();
        result.put("path", f.getAbsolutePath());
        result.put("name", f.getName());
        result.put("size", f.length());
        result.put("sizeFormatted", formatSize(f.length()));
        result.put("totalLines", lines);
        result.put("nonBlankLines", lines - blankLines);
        result.put("blankLines", blankLines);
        result.put("words", words);
        result.put("characters", chars);
        return result.toString();
    }

    // ==================== batchFileInfo ====================

    /**
     * 批量处理逗号分隔的路径列表：存在则输出元信息，文件额外计算指定算法散列。
     *
     * @param params {@code paths} 逗号分隔；{@code algorithm} 默认 SHA-256
     * @return JSON：{@code files} 数组、{@code count}
     * @throws Exception 摘要异常
     */
    private String batchFileInfo(JSONObject params) throws Exception {
        String pathsStr = params.optString("paths", "").trim();
        String algo = normalizeAlgo(params.optString("algorithm", "SHA-256"));
        if (pathsStr.isEmpty()) return errJson("Missing parameter: paths");

        String[] paths = pathsStr.split(",");
        JSONArray arr = new JSONArray();
        for (String p : paths) {
            p = p.trim();
            if (p.isEmpty()) continue;
            File f = new File(p);
            JSONObject item = new JSONObject();
            item.put("path", p);
            if (!f.exists()) {
                item.put("exists", false);
            } else {
                item.put("exists", true);
                item.put("name", f.getName());
                item.put("size", f.length());
                item.put("sizeFormatted", formatSize(f.length()));
                item.put("lastModified", SDF.format(new Date(f.lastModified())));
                item.put("isDirectory", f.isDirectory());
                if (f.isFile()) {
                    item.put("hash", computeHash(f, algo));
                    item.put("algorithm", algo);
                }
            }
            arr.put(item);
        }

        JSONObject result = new JSONObject();
        result.put("count", arr.length());
        result.put("algorithm", algo);
        result.put("files", arr);
        return result.toString();
    }

    // ==================== Utility methods ====================

    /**
     * 流式读取文件并更新 {@link MessageDigest}，返回小写十六进制字符串。
     *
     * @param f         待哈希文件
     * @param algorithm 如 MD5、SHA-256
     * @return 十六进制摘要
     * @throws Exception 算法不支持或 IO 异常
     */
    private String computeHash(File f, String algorithm) throws Exception {
        MessageDigest md = MessageDigest.getInstance(algorithm);
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(f);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = fis.read(buffer)) != -1) {
                md.update(buffer, 0, read);
            }
        } finally {
            if (fis != null) try { fis.close(); } catch (Exception ignored) {}
        }
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    /**
     * 将调用方传入的算法名规范为 JVM 支持的 {@link MessageDigest} 名称。
     *
     * @param input 用户输入，如 sha256、SHA-1
     * @return MD5、SHA-1 或 SHA-256
     */
    private String normalizeAlgo(String input) {
        if (input == null) return "SHA-256";
        String upper = input.trim().toUpperCase(Locale.ROOT).replace(" ", "");
        if (upper.equals("MD5")) return "MD5";
        if (upper.equals("SHA1") || upper.equals("SHA-1")) return "SHA-1";
        return "SHA-256";
    }

    /**
     * @param name 文件名
     * @return 不含点的扩展名，无扩展名返回空串
     */
    private String getExtension(String name) {
        int dot = name.lastIndexOf('.');
        return (dot >= 0 && dot < name.length() - 1) ? name.substring(dot + 1) : "";
    }

    /**
     * @param ext 扩展名（无点）
     * @return MIME 类型，无法识别时为 {@code application/octet-stream}
     */
    private String getMimeType(String ext) {
        if (ext.isEmpty()) return "application/octet-stream";
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase(Locale.ROOT));
        return mime != null ? mime : "application/octet-stream";
    }

    /**
     * @param bytes 字节数
     * @return 带 KB/MB/GB 后缀的简短字符串
     */
    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return SIZE_FMT.format(bytes / 1024.0) + " KB";
        if (bytes < 1024L * 1024 * 1024) return SIZE_FMT.format(bytes / (1024.0 * 1024)) + " MB";
        return SIZE_FMT.format(bytes / (1024.0 * 1024 * 1024)) + " GB";
    }

    /**
     * 将空参数规范为 {@code "{}"}（单行实现，供 {@link #invoke} 使用）。
     *
     * @param v 原始 JSON 字符串
     * @return 非空则原样返回
     */
    private String emptyJson(String v) { return v == null || v.trim().isEmpty() ? "{}" : v; }

    /**
     * 业务层局部错误：仅含 {@code error} 字段（再由 {@link #ok} 外层包装 success）。
     *
     * @param msg 错误说明
     * @return JSON 字符串
     * @throws Exception JSON 异常
     */
    private String errJson(String msg) throws Exception {
        return new JSONObject().put("error", msg).toString();
    }

    /**
     * @param output 业务输出 JSON 字符串
     * @return 成功包装，无展示文案
     * @throws Exception JSON 异常
     */
    private String ok(String output) throws Exception {
        return ok(output, null);
    }

    /**
     * @param output      业务输出
     * @param displayText {@code _displayText}，null 或空则不写入
     * @return 标准成功响应
     * @throws Exception JSON 异常
     */
    private String ok(String output, String displayText) throws Exception {
        JSONObject r = new JSONObject().put("success", true).put("output", output);
        if (displayText != null && !displayText.isEmpty()) r.put("_displayText", displayText);
        return r.toString();
    }

    /**
     * 顶层失败：{@code success=false}。
     *
     * @param message 错误信息（如未知 action）
     * @return JSON 字符串
     * @throws Exception JSON 异常
     */
    private String error(String message) throws Exception {
        return new JSONObject().put("success", false).put("error", message).toString();
    }

    /**
     * 将业务输出字符串安全解析为 {@link JSONObject}，供展示格式化使用。
     *
     * @param output {@code output} 字段内容
     * @return 解析成功返回对象，失败返回 null
     */
    private static JSONObject parseOutputJson(String output) {
        try {
            return new JSONObject(output);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 将 {@code getFileInfo} 的 output 格式化为简短展示块。
     *
     * @param output 业务 JSON 字符串
     * @return 展示文本
     */
    private static String formatStatsGetFileInfoDisplay(String output) {
        boolean zh = isZh();
        String title = zh ? "文件信息" : "File Info";
        String[] h = new String[]{zh ? "项目" : "Item", zh ? "值" : "Value"};
        JSONObject o = parseOutputJson(output);
        if (o == null) {
            return "📄 " + title + "\n\n" + pgTable(title, h, new ArrayList<String[]>());
        }
        if (o.has("error")) {
            List<String[]> rows = new ArrayList<>();
            rows.add(new String[]{zh ? "错误" : "Error", o.optString("error", "—")});
            return "📄 " + title + "\n\n" + pgTable(title, h, rows);
        }
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{zh ? "路径" : "Path", o.optString("path", "—")});
        rows.add(new String[]{zh ? "名称" : "Name", o.optString("name", "—")});
        boolean isDir = o.optBoolean("isDirectory", false);
        boolean isFile = o.optBoolean("isFile", false);
        rows.add(new String[]{zh ? "类型" : "Type",
                isDir ? (zh ? "目录" : "Directory") : (isFile ? (zh ? "文件" : "File") : "—")});
        rows.add(new String[]{zh ? "大小" : "Size", o.optString("sizeFormatted", String.valueOf(o.optLong("size", 0)))});
        rows.add(new String[]{zh ? "修改时间" : "Modified", o.optString("lastModified", "—")});
        rows.add(new String[]{zh ? "可读" : "Readable", o.optBoolean("canRead", false) ? (zh ? "是" : "Yes") : (zh ? "否" : "No")});
        rows.add(new String[]{zh ? "可写" : "Writable", o.optBoolean("canWrite", false) ? (zh ? "是" : "Yes") : (zh ? "否" : "No")});
        rows.add(new String[]{zh ? "可执行" : "Executable", o.optBoolean("canExecute", false) ? (zh ? "是" : "Yes") : (zh ? "否" : "No")});
        rows.add(new String[]{zh ? "隐藏" : "Hidden", o.optBoolean("isHidden", false) ? (zh ? "是" : "Yes") : (zh ? "否" : "No")});
        String parent = o.optString("parent", "");
        rows.add(new String[]{zh ? "父目录" : "Parent", parent.isEmpty() ? "—" : parent});
        String ext = o.optString("extension", "");
        rows.add(new String[]{zh ? "扩展名" : "Extension", ext.isEmpty() ? "—" : ext});
        rows.add(new String[]{"MIME", o.optString("mimeType", "—")});
        if (o.optBoolean("isDirectory", false) && o.has("childCount")) {
            rows.add(new String[]{zh ? "子项数" : "Child count", String.valueOf(o.optInt("childCount", 0))});
        }
        return "📄 " + title + "\n\n" + pgTable(title, h, rows);
    }

    /**
     * 文件哈希结果展示。
     *
     * @param output 业务 JSON
     * @return 展示文本
     */
    private static String formatGetFileHashDisplay(String output) {
        boolean zh = isZh();
        String title = zh ? "文件哈希" : "File Hash";
        String[] h = new String[]{zh ? "项目" : "Item", zh ? "值" : "Value"};
        JSONObject o = parseOutputJson(output);
        if (o == null) {
            return "🔐 " + title + "\n\n" + pgTable(title, h, new ArrayList<String[]>());
        }
        if (o.has("error")) {
            List<String[]> rows = new ArrayList<>();
            rows.add(new String[]{zh ? "错误" : "Error", o.optString("error", "—")});
            return "🔐 " + title + "\n\n" + pgTable(title, h, rows);
        }
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{zh ? "路径" : "Path", o.optString("path", "—")});
        rows.add(new String[]{zh ? "名称" : "Name", o.optString("name", "—")});
        rows.add(new String[]{zh ? "算法" : "Algorithm", o.optString("algorithm", "—")});
        rows.add(new String[]{zh ? "哈希" : "Hash", o.optString("hash", "—")});
        rows.add(new String[]{zh ? "大小" : "Size", o.optString("sizeFormatted", String.valueOf(o.optLong("size", 0)))});
        return "🔐 " + title + "\n\n" + pgTable(title, h, rows);
    }

    /**
     * 两文件是否一致的极简展示。
     *
     * @param output 业务 JSON
     * @return 展示文本
     */
    private static String formatCompareFilesDisplay(String output) {
        boolean zh = isZh();
        String title = zh ? "文件比较" : "File Comparison";
        String[] h = new String[]{zh ? "项目" : "Item", zh ? "值" : "Value"};
        JSONObject o = parseOutputJson(output);
        if (o == null) {
            return "🔍 " + title + "\n\n" + pgTable(title, h, new ArrayList<String[]>());
        }
        if (o.has("error")) {
            List<String[]> rows = new ArrayList<>();
            rows.add(new String[]{zh ? "错误" : "Error", o.optString("error", "—")});
            return "🔍 " + title + "\n\n" + pgTable(title, h, rows);
        }
        boolean same = o.optBoolean("identical", false);
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{zh ? "相同" : "Identical", same ? (zh ? "✅ 是" : "✅ Yes") : (zh ? "❌ 否" : "❌ No")});
        rows.add(new String[]{zh ? "原因" : "Reason", o.optString("reason", "—")});
        rows.add(new String[]{zh ? "文件 1" : "File 1", o.optString("file1", "—")});
        rows.add(new String[]{zh ? "文件 2" : "File 2", o.optString("file2", "—")});
        rows.add(new String[]{zh ? "大小 1" : "Size 1", o.optString("size1Formatted", String.valueOf(o.optLong("size1", 0)))});
        rows.add(new String[]{zh ? "大小 2" : "Size 2", o.optString("size2Formatted", String.valueOf(o.optLong("size2", 0)))});
        if (o.has("hash1")) {
            rows.add(new String[]{zh ? "SHA-256（文件 1）" : "SHA-256 (file 1)", o.optString("hash1", "—")});
        }
        if (o.has("hash2")) {
            rows.add(new String[]{zh ? "SHA-256（文件 2）" : "SHA-256 (file 2)", o.optString("hash2", "—")});
        }
        return "🔍 " + title + "\n\n" + pgTable(title, h, rows);
    }

    /**
     * 校验和验证结果一行展示。
     *
     * @param output 业务 JSON
     * @return 展示文本
     */
    private static String formatVerifyChecksumDisplay(String output) {
        boolean zh = isZh();
        String title = zh ? "校验和验证" : "Verify Checksum";
        String[] h = new String[]{zh ? "项目" : "Item", zh ? "值" : "Value"};
        JSONObject o = parseOutputJson(output);
        if (o == null) {
            return "";
        }
        if (o.has("error")) {
            List<String[]> rows = new ArrayList<>();
            rows.add(new String[]{zh ? "错误" : "Error", o.optString("error", "—")});
            return "🔐 " + title + "\n\n" + pgTable(title, h, rows);
        }
        boolean ok = o.optBoolean("verified", false);
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{zh ? "已验证" : "Verified", ok ? (zh ? "✅ 是" : "✅ Yes") : (zh ? "❌ 否" : "❌ No")});
        rows.add(new String[]{zh ? "路径" : "Path", o.optString("path", "—")});
        rows.add(new String[]{zh ? "算法" : "Algorithm", o.optString("algorithm", "—")});
        rows.add(new String[]{zh ? "期望值" : "Expected", o.optString("expectedHash", "—")});
        rows.add(new String[]{zh ? "实际值" : "Actual", o.optString("actualHash", "—")});
        rows.add(new String[]{zh ? "消息" : "Message", o.optString("message", "—")});
        return "🔐 " + title + "\n\n" + pgTable(title, h, rows);
    }

    /**
     * 目录统计摘要展示。
     *
     * @param output 业务 JSON
     * @return 展示文本
     */
    private static String formatGetDirStatsDisplay(String output) {
        boolean zh = isZh();
        String title = zh ? "目录统计" : "Directory Stats";
        String[] h2 = new String[]{zh ? "项目" : "Item", zh ? "值" : "Value"};
        JSONObject o = parseOutputJson(output);
        if (o == null) {
            return "📊 " + title + "\n\n" + pgTable(title, h2, new ArrayList<String[]>());
        }
        if (o.has("error")) {
            List<String[]> rows = new ArrayList<>();
            rows.add(new String[]{zh ? "错误" : "Error", o.optString("error", "—")});
            return "📊 " + title + "\n\n" + pgTable(title, h2, rows);
        }
        List<String[]> summaryRows = new ArrayList<>();
        summaryRows.add(new String[]{zh ? "路径" : "Path", o.optString("path", "—")});
        summaryRows.add(new String[]{zh ? "文件" : "Files", String.valueOf(o.optLong("fileCount", 0))});
        summaryRows.add(new String[]{zh ? "子目录" : "Directories", String.valueOf(o.optLong("directoryCount", 0))});
        summaryRows.add(new String[]{zh ? "总大小" : "Total size", o.optString("totalSizeFormatted", "—")});
        String sub = zh ? "按扩展名" : "By extension";
        String[] h3 = new String[]{zh ? "扩展名" : "Extension", zh ? "数量" : "Count", zh ? "总大小" : "Total size"};
        List<String[]> extRows = new ArrayList<>();
        JSONArray breakdown = o.optJSONArray("extensionBreakdown");
        if (breakdown != null) {
            int show = Math.min(DISPLAY_LIST_MAX, breakdown.length());
            for (int i = 0; i < show; i++) {
                JSONObject row = breakdown.optJSONObject(i);
                if (row == null) {
                    continue;
                }
                extRows.add(new String[]{
                        row.optString("extension", "—"),
                        String.valueOf(row.optLong("count", 0)),
                        row.optString("totalSizeFormatted", "—")});
            }
            if (breakdown.length() > show) {
                extRows.add(new String[]{"…", "+" + (breakdown.length() - show) + (zh ? " 更多" : " more"), "—"});
            }
        }
        return "📊 " + title + "\n\n" + pgTable(title, h2, summaryRows) + "\n\n" + sub + "\n\n" + pgTable(sub, h3, extRows);
    }

    /**
     * 重复文件分组展示（组数与每组首条路径，条数受 {@link #DISPLAY_LIST_MAX} 限制）。
     *
     * @param output 业务 JSON
     * @return 展示文本
     */
    private static String formatFindDuplicatesDisplay(String output) {
        boolean zh = isZh();
        String title = zh ? "重复文件" : "Duplicate Files";
        String[] h2 = new String[]{zh ? "项目" : "Item", zh ? "值" : "Value"};
        JSONObject o = parseOutputJson(output);
        if (o == null) {
            return "🔁 " + title + "\n\n" + pgTable(title, h2, new ArrayList<String[]>());
        }
        if (o.has("error")) {
            List<String[]> rows = new ArrayList<>();
            rows.add(new String[]{zh ? "错误" : "Error", o.optString("error", "—")});
            return "🔁 " + title + "\n\n" + pgTable(title, h2, rows);
        }
        int groupCount = o.optInt("duplicateGroups", 0);
        List<String[]> summaryRows = new ArrayList<>();
        summaryRows.add(new String[]{zh ? "目录" : "Directory", o.optString("directory", "—")});
        summaryRows.add(new String[]{zh ? "重复组" : "Duplicate groups", String.valueOf(groupCount)});
        summaryRows.add(new String[]{zh ? "扫描文件" : "Files scanned", String.valueOf(o.optLong("filesScanned", 0))});
        summaryRows.add(new String[]{zh ? "浪费空间" : "Wasted space", o.optString("wastedSpaceFormatted", "—")});
        String sub = zh ? "分组列表" : "Group list";
        String[] h3 = new String[]{"#", zh ? "数量" : "Count", zh ? "大小" : "Size", zh ? "示例路径" : "Example path"};
        List<String[]> detailRows = new ArrayList<>();
        JSONArray dups = o.optJSONArray("duplicates");
        if (dups != null) {
            int showG = Math.min(DISPLAY_LIST_MAX, dups.length());
            for (int g = 0; g < showG; g++) {
                JSONObject grp = dups.optJSONObject(g);
                if (grp == null) {
                    continue;
                }
                int cnt = grp.optInt("count", 0);
                String sz = grp.optString("fileSizeFormatted", "—");
                JSONArray files = grp.optJSONArray("files");
                String first = (files != null && files.length() > 0) ? files.optString(0, "") : "—";
                detailRows.add(new String[]{String.valueOf(g + 1), String.valueOf(cnt), sz, first});
            }
            if (dups.length() > showG) {
                detailRows.add(new String[]{"…", "+" + (dups.length() - showG) + (zh ? " 组" : " groups"), "—", "—"});
            }
        }
        return "🔁 " + title + "\n\n" + pgTable(title, h2, summaryRows) + "\n\n" + sub + "\n\n" + pgTable(sub, h3, detailRows);
    }

    /**
     * 大文件列表展示。
     *
     * @param output 业务 JSON
     * @return 展示文本
     */
    private static String formatFindLargeFilesDisplay(String output) {
        boolean zh = isZh();
        String title = zh ? "大文件" : "Large Files";
        String[] h2 = new String[]{zh ? "项目" : "Item", zh ? "值" : "Value"};
        JSONObject o = parseOutputJson(output);
        if (o == null) {
            String[] h3 = new String[]{zh ? "名称" : "Name", zh ? "大小" : "Size", zh ? "修改时间" : "Modified"};
            return "📦 " + title + "\n\n" + pgTable(title, h3, new ArrayList<String[]>());
        }
        if (o.has("error")) {
            List<String[]> rows = new ArrayList<>();
            rows.add(new String[]{zh ? "错误" : "Error", o.optString("error", "—")});
            return "📦 " + title + "\n\n" + pgTable(title, h2, rows);
        }
        List<String[]> summaryRows = new ArrayList<>();
        summaryRows.add(new String[]{zh ? "目录" : "Directory", o.optString("directory", "—")});
        summaryRows.add(new String[]{zh ? "最小大小 (MB)" : "Min size (MB)", String.valueOf(o.optDouble("minSizeMB", 0))});
        summaryRows.add(new String[]{zh ? "找到" : "Found", String.valueOf(o.optLong("found", 0))});
        summaryRows.add(new String[]{zh ? "显示" : "Showing", String.valueOf(o.optLong("showing", 0))});
        String sub = zh ? "文件列表" : "Files";
        String[] h3 = new String[]{zh ? "名称" : "Name", zh ? "大小" : "Size", zh ? "修改时间" : "Modified"};
        List<String[]> fileRows = new ArrayList<>();
        JSONArray files = o.optJSONArray("files");
        if (files != null) {
            int show = Math.min(DISPLAY_LIST_MAX, files.length());
            for (int i = 0; i < show; i++) {
                JSONObject f = files.optJSONObject(i);
                if (f == null) {
                    continue;
                }
                fileRows.add(new String[]{
                        f.optString("name", "?"),
                        f.optString("sizeFormatted", "—"),
                        f.optString("lastModified", "—")});
            }
            if (files.length() > show) {
                fileRows.add(new String[]{"…", "+" + (files.length() - show) + (zh ? " 更多" : " more"), "—"});
            }
        }
        return "📦 " + title + "\n\n" + pgTable(title, h2, summaryRows) + "\n\n" + sub + "\n\n" + pgTable(sub, h3, fileRows);
    }

    /**
     * 按文件名搜索结果的展示。
     *
     * @param output 业务 JSON
     * @return 展示文本
     */
    private static String formatSearchByNameDisplay(String output) {
        boolean zh = isZh();
        String title = zh ? "找到文件" : "Files Found";
        String[] h2 = new String[]{zh ? "项目" : "Item", zh ? "值" : "Value"};
        JSONObject o = parseOutputJson(output);
        if (o == null) {
            String[] h3 = new String[]{
                    zh ? "名称" : "Name", zh ? "路径" : "Path", zh ? "类型" : "Type",
                    zh ? "大小" : "Size", zh ? "修改时间" : "Modified"};
            return "🔍 " + title + "\n\n" + pgTable(title, h3, new ArrayList<String[]>());
        }
        if (o.has("error")) {
            List<String[]> rows = new ArrayList<>();
            rows.add(new String[]{zh ? "错误" : "Error", o.optString("error", "—")});
            return "🔍 " + title + "\n\n" + pgTable(title, h2, rows);
        }
        int found = o.optInt("found", 0);
        List<String[]> summaryRows = new ArrayList<>();
        summaryRows.add(new String[]{zh ? "目录" : "Directory", o.optString("directory", "—")});
        summaryRows.add(new String[]{zh ? "关键词" : "Keywords", o.optString("keywords", "—")});
        summaryRows.add(new String[]{zh ? "匹配" : "Matches", String.valueOf(found)});
        String sub = zh ? "结果" : "Results";
        String[] h3 = new String[]{
                zh ? "名称" : "Name", zh ? "路径" : "Path", zh ? "类型" : "Type",
                zh ? "大小" : "Size", zh ? "修改时间" : "Modified"};
        List<String[]> fileRows = new ArrayList<>();
        JSONArray files = o.optJSONArray("files");
        if (files != null) {
            int show = Math.min(DISPLAY_LIST_MAX, files.length());
            for (int i = 0; i < show; i++) {
                JSONObject f = files.optJSONObject(i);
                if (f == null) {
                    continue;
                }
                String type = f.optBoolean("isDirectory", false)
                        ? (zh ? "目录" : "Dir")
                        : (zh ? "文件" : "File");
                fileRows.add(new String[]{
                        f.optString("name", "?"),
                        f.optString("path", "—"),
                        type,
                        f.optString("sizeFormatted", "—"),
                        f.optString("lastModified", "—")});
            }
            if (files.length() > show) {
                fileRows.add(new String[]{"…", "+" + (files.length() - show) + (zh ? " 更多" : " more"), "—", "—", "—"});
            }
        }
        return "🔍 " + title + "\n\n" + pgTable(title, h2, summaryRows) + "\n\n" + sub + "\n\n" + pgTable(sub, h3, fileRows);
    }

    /**
     * 文本统计结果展示。
     *
     * @param output 业务 JSON
     * @return 展示文本
     */
    private static String formatGetTextStatsDisplay(String output) {
        boolean zh = isZh();
        String title = zh ? "文本统计" : "Text Stats";
        String[] h = new String[]{zh ? "项目" : "Item", zh ? "值" : "Value"};
        JSONObject o = parseOutputJson(output);
        if (o == null) {
            return "📝 " + title + "\n\n" + pgTable(title, h, new ArrayList<String[]>());
        }
        if (o.has("error")) {
            List<String[]> rows = new ArrayList<>();
            rows.add(new String[]{zh ? "错误" : "Error", o.optString("error", "—")});
            return "📝 " + title + "\n\n" + pgTable(title, h, rows);
        }
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{zh ? "路径" : "Path", o.optString("path", "—")});
        rows.add(new String[]{zh ? "名称" : "Name", o.optString("name", "—")});
        rows.add(new String[]{zh ? "文件大小" : "File size", o.optString("sizeFormatted", String.valueOf(o.optLong("size", 0)))});
        rows.add(new String[]{zh ? "总行数" : "Total lines", String.valueOf(o.optLong("totalLines", 0))});
        rows.add(new String[]{zh ? "非空行" : "Non-blank lines", String.valueOf(o.optLong("nonBlankLines", 0))});
        rows.add(new String[]{zh ? "空行" : "Blank lines", String.valueOf(o.optLong("blankLines", 0))});
        rows.add(new String[]{zh ? "词数" : "Words", String.valueOf(o.optLong("words", 0))});
        rows.add(new String[]{zh ? "字符" : "Characters", String.valueOf(o.optLong("characters", 0))});
        return "📝 " + title + "\n\n" + pgTable(title, h, rows);
    }

    /**
     * 批量文件信息处理结果的一句话摘要。
     *
     * @param output 业务 JSON
     * @return 展示文本
     */
    private static String formatBatchFileInfoDisplay(String output) {
        boolean zh = isZh();
        String title = zh ? "批量文件信息" : "Batch File Info";
        String[] h2 = new String[]{zh ? "项目" : "Item", zh ? "值" : "Value"};
        JSONObject o = parseOutputJson(output);
        if (o == null) {
            String[] h3 = new String[]{
                    zh ? "路径" : "Path", zh ? "存在" : "Exists", zh ? "详情" : "Details"};
            return "📋 " + title + "\n\n" + pgTable(title, h3, new ArrayList<String[]>());
        }
        if (o.has("error")) {
            List<String[]> rows = new ArrayList<>();
            rows.add(new String[]{zh ? "错误" : "Error", o.optString("error", "—")});
            return "📋 " + title + "\n\n" + pgTable(title, h2, rows);
        }
        String algo = o.optString("algorithm", "—");
        List<String[]> summaryRows = new ArrayList<>();
        summaryRows.add(new String[]{zh ? "数量" : "Count", String.valueOf(o.optInt("count", 0))});
        summaryRows.add(new String[]{zh ? "算法" : "Algorithm", algo});
        String sub = zh ? "文件" : "Files";
        String[] h3 = new String[]{
                zh ? "路径" : "Path", zh ? "存在" : "Exists", zh ? "名称" : "Name",
                zh ? "大小" : "Size", zh ? "修改时间" : "Modified", zh ? "哈希" : "Hash"};
        List<String[]> fileRows = new ArrayList<>();
        JSONArray files = o.optJSONArray("files");
        if (files != null) {
            int show = Math.min(DISPLAY_LIST_MAX, files.length());
            for (int i = 0; i < show; i++) {
                JSONObject it = files.optJSONObject(i);
                if (it == null) {
                    continue;
                }
                String path = it.optString("path", "—");
                boolean ex = it.optBoolean("exists", false);
                if (!ex) {
                    fileRows.add(new String[]{path, zh ? "否" : "No", "—", "—", "—", "—"});
                    continue;
                }
                String name = it.optString("name", "—");
                String size = it.optString("sizeFormatted", String.valueOf(it.optLong("size", 0)));
                String mod = it.optString("lastModified", "—");
                String hash = it.optBoolean("isDirectory", false) ? "—" : it.optString("hash", "—");
                fileRows.add(new String[]{path, zh ? "是" : "Yes", name, size, mod, hash});
            }
            if (files.length() > show) {
                fileRows.add(new String[]{"…", "+" + (files.length() - show) + (zh ? " 更多" : " more"), "—", "—", "—", "—"});
            }
        }
        return "📋 " + title + "\n\n" + pgTable(title, h2, summaryRows) + "\n\n" + sub + "\n\n" + pgTable(sub, h3, fileRows);
    }
}
