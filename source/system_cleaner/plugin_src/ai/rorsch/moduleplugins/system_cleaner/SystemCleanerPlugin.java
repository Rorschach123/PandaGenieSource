package ai.rorsch.moduleplugins.system_cleaner;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;

import ai.rorsch.pandagenie.module.runtime.HtmlOutputHelper;
import ai.rorsch.pandagenie.module.runtime.ModulePlugin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * PandaGenie 系统/存储清理模块插件。
 * <p>
 * <b>模块用途：</b>在外部存储上扫描「可清理」类别（临时文件、应用缓存、残留 APK、缩略图目录、空目录、日志等）并估算体积；
 * 按用户指定类别执行删除；查询内部/外部存储空间使用；在目录树中查找超过阈值的大文件。
 * </p>
 * <p>
 * <b>对外 API：</b>{@code scanJunk}、{@code cleanJunk}、{@code getStorageInfo}、{@code findLargeFiles}。
 * 参数与结果均为 JSON；成功响应含 {@code output} 与可选 {@code _displayText}。
 * </p>
 * <p>
 * 实现 {@link ModulePlugin}，由宿主 {@code ModuleRuntime} 反射调用；实际操作路径依赖 {@link Environment#getExternalStorageDirectory()} 等，
 * 需存储权限由宿主申请。
 * </p>
 */
public class SystemCleanerPlugin implements ModulePlugin {

    /** 将字节数格式化为 KB/MB/GB 时的十进制格式（最多一位小数）。 */
    private static final DecimalFormat SIZE_FMT = new DecimalFormat("#,##0.#");
    /** {@code findLargeFiles} 默认最多返回的条数上限。 */
    private static final int LARGE_FILES_DEFAULT_LIMIT = 100;

    private static boolean isZh() {
        try {
            return java.util.Locale.getDefault().getLanguage().toLowerCase(java.util.Locale.ROOT).startsWith("zh");
        } catch (Exception e) {
            return false;
        }
    }

    private static String pgTable(String title, String[] headers, java.util.List<String[]> rows) {
        try {
            org.json.JSONObject t = new org.json.JSONObject();
            t.put("title", title);
            org.json.JSONArray h = new org.json.JSONArray();
            for (String hdr : headers) h.put(hdr);
            t.put("headers", h);
            org.json.JSONArray r = new org.json.JSONArray();
            for (String[] row : rows) {
                org.json.JSONArray a = new org.json.JSONArray();
                for (String c : row) a.put(c);
                r.put(a);
            }
            t.put("rows", r);
            return "__pg_table__" + t.toString() + "__pg_table_end__";
        } catch (Exception e) {
            return title;
        }
    }

    /**
     * 模块入口：分发清理与存储相关动作。
     *
     * @param context    当前上下文（本实现未强依赖，保留与接口一致）
     * @param action     动作名见类说明
     * @param paramsJson 各动作 JSON 参数；空则 {@code {}}
     * @return 标准 JSON 响应字符串
     */
    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        JSONObject params = new JSONObject(emptyJson(paramsJson));
        switch (action) {
            case "scanJunk": {
                String out = scanJunk(params);
                return ok(out, formatScanJunkDisplay(out), formatScanJunkHtml(out));
            }
            case "cleanJunk": {
                String out = cleanJunk(params);
                return ok(out, formatCleanJunkDisplay(out), formatCleanJunkHtml(out));
            }
            case "getStorageInfo": {
                String out = getStorageInfo();
                return ok(out, formatStorageInfoDisplay(out), formatStorageInfoHtml(out));
            }
            case "findLargeFiles": {
                String out = findLargeFiles(params);
                return ok(out, formatFindLargeFilesDisplay(out), formatFindLargeFilesHtml(out));
            }
            case "openPage": {
                JSONObject r = new JSONObject();
                r.put("success", true);
                r.put("output", "{}");
                r.put("_openModule", true);
                r.put("_displayText", isZh() ? "正在打开系统清理..." : "Opening System Cleaner...");
                return r.toString();
            }
            default:
                return error("Unsupported action: " + action);
        }
    }

    /**
     * 扫描指定类别的垃圾/冗余文件体量（不删除）。
     *
     * @param params JSON：{@code categories} 为逗号分隔类别名；空则扫描全部默认类别（temp、cache、apk、thumbs、empty_dirs、logs）
     * @return 含各类别统计与 {@code totalSizeBytes} 的 JSON；外部存储不可用则 {@link #errJson}
     */
    private String scanJunk(JSONObject params) throws Exception {
        File root = Environment.getExternalStorageDirectory();
        if (root == null || !root.exists()) {
            return errJson("External storage not available");
        }
        Set<String> cats = parseCategories(params.optString("categories", ""), true);
        JSONObject categories = new JSONObject();
        long totalBytes = 0;

        if (cats.contains("temp")) {
            CategoryStat st = scanTemp(root);
            categories.put("temp", st.toJson());
            totalBytes += st.sizeBytes;
        }
        if (cats.contains("cache")) {
            CategoryStat st = scanCache(root);
            categories.put("cache", st.toJson());
            totalBytes += st.sizeBytes;
        }
        if (cats.contains("apk")) {
            CategoryStat st = scanApk(root);
            categories.put("apk", st.toJson());
            totalBytes += st.sizeBytes;
        }
        if (cats.contains("thumbs")) {
            CategoryStat st = scanThumbs(root);
            categories.put("thumbs", st.toJson());
            totalBytes += st.sizeBytes;
        }
        if (cats.contains("empty_dirs")) {
            CategoryStat st = scanEmptyDirs(root);
            categories.put("empty_dirs", st.toJson());
            totalBytes += st.sizeBytes;
        }
        if (cats.contains("logs")) {
            CategoryStat st = scanLogs(root);
            categories.put("logs", st.toJson());
            totalBytes += st.sizeBytes;
        }

        JSONObject result = new JSONObject();
        result.put("categories", categories);
        result.put("totalSizeBytes", totalBytes);
        result.put("totalSizeFormatted", formatSizeBytes(totalBytes));
        return result.toString();
    }

    /**
     * 按类别删除扫描到的可清理项（{@code empty_dirs} 会删空目录；其它以文件为主）。
     *
     * @param params JSON：{@code categories} 必填且非空，逗号分隔
     * @return 删除数量、释放字节、按类别汇总等；未指定类别则错误 JSON
     */
    private String cleanJunk(JSONObject params) throws Exception {
        File root = Environment.getExternalStorageDirectory();
        if (root == null || !root.exists()) {
            return errJson("External storage not available");
        }
        Set<String> cats = parseCategories(params.optString("categories", ""), false);
        if (cats.isEmpty()) {
            return errJson("No categories specified");
        }
        long freed = 0;
        int deletedFiles = 0;
        int deletedDirs = 0;
        JSONObject byCat = new JSONObject();

        if (cats.contains("temp")) {
            CleanResult cr = cleanTemp(root);
            byCat.put("temp", cr.toJson());
            freed += cr.bytes;
            deletedFiles += cr.files;
        }
        if (cats.contains("cache")) {
            CleanResult cr = cleanCache(root);
            byCat.put("cache", cr.toJson());
            freed += cr.bytes;
            deletedFiles += cr.files;
        }
        if (cats.contains("apk")) {
            CleanResult cr = cleanApk(root);
            byCat.put("apk", cr.toJson());
            freed += cr.bytes;
            deletedFiles += cr.files;
        }
        if (cats.contains("thumbs")) {
            CleanResult cr = cleanThumbs(root);
            byCat.put("thumbs", cr.toJson());
            freed += cr.bytes;
            deletedFiles += cr.files;
        }
        if (cats.contains("logs")) {
            CleanResult cr = cleanLogs(root);
            byCat.put("logs", cr.toJson());
            freed += cr.bytes;
            deletedFiles += cr.files;
        }
        if (cats.contains("empty_dirs")) {
            CleanResult cr = cleanEmptyDirs(root);
            byCat.put("empty_dirs", cr.toJson());
            freed += cr.bytes;
            deletedFiles += cr.files;
            deletedDirs += cr.dirs;
        }

        JSONObject result = new JSONObject();
        result.put("deletedFiles", deletedFiles);
        result.put("deletedDirs", deletedDirs);
        result.put("deletedItems", deletedFiles + deletedDirs);
        result.put("freedBytes", freed);
        result.put("freedFormatted", formatSizeBytes(freed));
        result.put("byCategory", byCat);
        return result.toString();
    }

    /**
     * 返回内部数据分区与主外部存储的 {@link StatFs} 统计（总量、已用、可用、百分比及 GB 文案）。
     *
     * @return JSON：{@code internal}、{@code externalPrimary}（含 {@code state} 挂载状态）；路径不存在则为 {@link JSONObject#NULL}
     */
    private String getStorageInfo() throws Exception {
        JSONObject root = new JSONObject();
        JSONObject internal = statFsToJson(Environment.getDataDirectory());
        root.put("internal", internal != null ? internal : JSONObject.NULL);
        File ext = Environment.getExternalStorageDirectory();
        JSONObject external = statFsToJson(ext);
        if (external != null) {
            external.put("state", Environment.getExternalStorageState());
        }
        root.put("externalPrimary", external != null ? external : JSONObject.NULL);
        return root.toString();
    }

    /**
     * 在指定目录（默认外部存储根）递归或非递归查找不小于 {@code minSizeMB} 的文件，按大小降序截取前 {@code limit} 条。
     *
     * @param params JSON：{@code minSizeMB} 默认 50；{@code path} 根目录；{@code recursive} 默认 true；{@code limit} 默认 {@value #LARGE_FILES_DEFAULT_LIMIT}
     * @return 含 {@code files} 数组与 {@code found}/{@code showing}；目录无效则 {@link #errJson}
     */
    private String findLargeFiles(JSONObject params) throws Exception {
        double minSizeMB = 50.0;
        if (params.has("minSizeMB")) {
            minSizeMB = params.optDouble("minSizeMB", 50.0);
        }
        String pathStr = params.optString("path", "").trim();
        if (pathStr.isEmpty()) {
            File ext = Environment.getExternalStorageDirectory();
            pathStr = ext != null ? ext.getAbsolutePath() : "/sdcard";
        }
        boolean recursive = params.optBoolean("recursive", true);
        int limit = params.optInt("limit", LARGE_FILES_DEFAULT_LIMIT);

        File dir = new File(pathStr);
        if (!dir.exists() || !dir.isDirectory()) {
            return errJson("Directory not found: " + pathStr);
        }

        long minBytes = (long) (minSizeMB * 1024L * 1024L);
        List<File> large = new ArrayList<>();
        collectLargeFiles(dir, recursive, minBytes, large);
        // 大文件优先展示，方便用户先处理最占空间项
        Collections.sort(large, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(b.length(), a.length());
            }
        });

        JSONArray arr = new JSONArray();
        int n = Math.min(limit, large.size());
        for (int i = 0; i < n; i++) {
            File f = large.get(i);
            JSONObject item = new JSONObject();
            item.put("path", f.getAbsolutePath());
            item.put("name", f.getName());
            item.put("size", f.length());
            item.put("sizeFormatted", formatSizeBytes(f.length()));
            arr.put(item);
        }

        JSONObject result = new JSONObject();
        result.put("directory", dir.getAbsolutePath());
        result.put("minSizeMB", minSizeMB);
        result.put("found", large.size());
        result.put("showing", n);
        result.put("files", arr);
        return result.toString();
    }

    // ---------- 扫描辅助：仅统计体积与数量，不修改文件系统 ----------

    /**
     * 扫描临时文件：{@code PandaGenie/temp} 递归 + 全盘 {@code .tmp}（排除已统计目录）。
     */
    private static CategoryStat scanTemp(File root) {
        CategoryStat st = new CategoryStat();
        File pandaTemp = new File(root, "PandaGenie/temp");
        accumulateFilesRecursive(pandaTemp, st);
        accumulateTmpFiles(root, pandaTemp, st);
        return st;
    }

    /**
     * 在 {@code root} 下递归查找扩展名为 {@code .tmp} 的文件并累计，跳过 {@code skipDir} 子树避免重复。
     */
    private static void accumulateTmpFiles(File root, File skipDir, CategoryStat st) {
        accumulateMatchingFiles(root, st, new FilePredicate() {
            @Override
            public boolean accept(File f) {
                if (!f.isFile()) return false;
                if (isUnderDirectory(f, skipDir)) return false;
                String n = f.getName().toLowerCase(Locale.ROOT);
                return n.endsWith(".tmp");
            }
        });
    }

    /**
     * 判断 {@code file} 是否位于 {@code dir} 目录树内（基于规范路径前缀）。
     */
    private static boolean isUnderDirectory(File file, File dir) {
        if (dir == null || !dir.exists()) return false;
        try {
            String fp = file.getCanonicalPath();
            String dp = dir.getCanonicalPath();
            return fp.startsWith(dp + File.separator) || fp.equals(dp);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 扫描 Android/data 下各应用外部缓存目录。
     */
    private static CategoryStat scanCache(File root) {
        CategoryStat st = new CategoryStat();
        File androidData = new File(root, "Android/data");
        File[] pkgs = androidData.listFiles();
        if (pkgs == null) return st;
        for (File pkg : pkgs) {
            if (!pkg.isDirectory()) continue;
            accumulateFilesRecursive(new File(pkg, "cache"), st);
        }
        return st;
    }

    /**
     * 扫描外部存储根目录下 APK 文件，以及 Downloads / Download 目录树内 APK。
     */
    private static CategoryStat scanApk(File root) {
        CategoryStat st = new CategoryStat();
        File[] rootFiles = root.listFiles();
        if (rootFiles != null) {
            for (File c : rootFiles) {
                if (c.isFile() && c.getName().toLowerCase(Locale.ROOT).endsWith(".apk")) {
                    st.addFile(c.length());
                }
            }
        }
        File download = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (download != null && download.exists()) {
            accumulateApkInTree(download, st);
        }
        File downloadZh = new File(root, "Download");
        if (downloadZh.exists() && !downloadZh.equals(download)) {
            accumulateApkInTree(downloadZh, st);
        }
        return st;
    }

    /** 递归累计某目录树中的 {@code .apk} 文件大小。 */
    private static void accumulateApkInTree(File dir, CategoryStat st) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File c : children) {
            if (c.isFile() && c.getName().toLowerCase(Locale.ROOT).endsWith(".apk")) {
                st.addFile(c.length());
            } else if (c.isDirectory()) {
                accumulateApkInTree(c, st);
            }
        }
    }

    /**
     * 扫描名为 {@code .thumbnails} 的目录及其内所有文件（系统/相册缩略图缓存常见位置）。
     */
    private static CategoryStat scanThumbs(File root) {
        CategoryStat st = new CategoryStat();
        accumulateThumbsDirs(root, st);
        return st;
    }

    /** 深度优先查找 {@code .thumbnails} 目录并递归累计其中文件。 */
    private static void accumulateThumbsDirs(File dir, CategoryStat st) {
        if (dir == null || !dir.isDirectory()) return;
        String name = dir.getName();
        if (".thumbnails".equals(name)) {
            accumulateFilesRecursive(dir, st);
            return;
        }
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) {
                accumulateThumbsDirs(k, st);
            }
        }
    }

    /**
     * 统计「叶子空目录」数量（不含根目录自身）；用于估算可合并删除的空文件夹规模。
     */
    private static CategoryStat scanEmptyDirs(File root) {
        CategoryStat st = new CategoryStat();
        countDirectEmptyDirs(root, root, st);
        return st;
    }

    /**
     * 递归后再次列出子项：若目录在递归处理后变为空且不是 {@code root}，则计为一个空目录。
     */
    private static void countDirectEmptyDirs(File root, File dir, CategoryStat st) {
        if (dir == null || !dir.isDirectory()) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        boolean hasSubDir = false;
        for (File k : kids) {
            if (k.isDirectory()) {
                hasSubDir = true;
                countDirectEmptyDirs(root, k, st);
            }
        }
        kids = dir.listFiles();
        if (kids != null && kids.length == 0 && !dir.equals(root)) {
            st.addEmptyDir();
        }
    }

    /** 递归查找扩展名为 {@code .log} 的日志文件。 */
    private static CategoryStat scanLogs(File root) {
        CategoryStat st = new CategoryStat();
        accumulateMatchingFiles(root, st, new FilePredicate() {
            @Override
            public boolean accept(File f) {
                if (!f.isFile()) return false;
                String n = f.getName().toLowerCase(Locale.ROOT);
                return n.endsWith(".log");
            }
        });
        return st;
    }

    /** 递归累计目录下所有普通文件的字节数与个数。 */
    private static void accumulateFilesRecursive(File dir, CategoryStat st) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isFile()) {
                st.addFile(k.length());
            } else if (k.isDirectory()) {
                accumulateFilesRecursive(k, st);
            }
        }
    }

    /** 遍历时判断是否应计入统计的文件谓词。 */
    private interface FilePredicate {
        boolean accept(File f);
    }

    /**
     * 递归遍历目录，对满足 {@code pred} 的文件调用 {@link CategoryStat#addFile(long)}。
     */
    private static void accumulateMatchingFiles(File dir, CategoryStat st, FilePredicate pred) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isFile()) {
                if (pred.accept(k)) st.addFile(k.length());
            } else if (k.isDirectory()) {
                accumulateMatchingFiles(k, st, pred);
            }
        }
    }

    /**
     * 收集不小于 {@code minBytes} 的文件路径到 {@code out}。
     *
     * @param dir       起始目录
     * @param recursive 是否进入子目录
     * @param minBytes  最小文件大小（字节）
     * @param out       输出列表（就地追加）
     */
    private static void collectLargeFiles(File dir, boolean recursive, long minBytes, List<File> out) {
        if (dir == null || !dir.isDirectory()) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isFile() && k.length() >= minBytes) {
                out.add(k);
            } else if (k.isDirectory() && recursive) {
                collectLargeFiles(k, true, minBytes, out);
            }
        }
    }

    // ---------- 清理辅助：会删除文件/空目录，调用前需用户授权 ----------

    /**
     * 删除 {@code PandaGenie/temp} 及外部存储上其它 {@code .tmp} 文件（排除已删目录内）。
     */
    private static CleanResult cleanTemp(File root) {
        CleanResult cr = new CleanResult();
        File pandaTemp = new File(root, "PandaGenie/temp");
        deleteFilesRecursive(pandaTemp, cr);
        deleteMatchingFiles(root, pandaTemp, cr, new FilePredicate() {
            @Override
            public boolean accept(File f) {
                if (!f.isFile()) return false;
                if (isUnderDirectory(f, pandaTemp)) return false;
                String n = f.getName().toLowerCase(Locale.ROOT);
                return n.endsWith(".tmp");
            }
        });
        return cr;
    }

    /** 清空各包 {@code Android/data/<pkg>/cache} 目录内容。 */
    private static CleanResult cleanCache(File root) {
        CleanResult cr = new CleanResult();
        File androidData = new File(root, "Android/data");
        File[] pkgs = androidData.listFiles();
        if (pkgs == null) return cr;
        for (File pkg : pkgs) {
            if (!pkg.isDirectory()) continue;
            deleteFilesRecursive(new File(pkg, "cache"), cr);
        }
        return cr;
    }

    /** 删除外部存储根目录 APK 与下载目录树中的 APK（与扫描范围一致）。 */
    private static CleanResult cleanApk(File root) {
        CleanResult cr = new CleanResult();
        File[] rootFiles = root.listFiles();
        if (rootFiles != null) {
            for (File c : rootFiles) {
                if (c.isFile() && c.getName().toLowerCase(Locale.ROOT).endsWith(".apk")) {
                    tryDeleteFile(c, cr);
                }
            }
        }
        File download = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (download != null && download.exists()) {
            deleteApkInTree(download, cr);
        }
        File downloadZh = new File(root, "Download");
        if (downloadZh.exists() && !downloadZh.equals(download)) {
            deleteApkInTree(downloadZh, cr);
        }
        return cr;
    }

    /** 递归删除目录树中的 APK 文件。 */
    private static void deleteApkInTree(File dir, CleanResult cr) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File c : children) {
            if (c.isFile() && c.getName().toLowerCase(Locale.ROOT).endsWith(".apk")) {
                tryDeleteFile(c, cr);
            } else if (c.isDirectory()) {
                deleteApkInTree(c, cr);
            }
        }
    }

    /** 删除所有名为 {@code .thumbnails} 的目录及其内容。 */
    private static CleanResult cleanThumbs(File root) {
        CleanResult cr = new CleanResult();
        deleteThumbsDirs(root, cr);
        return cr;
    }

    /** 深度优先定位 {@code .thumbnails} 并整树删除。 */
    private static void deleteThumbsDirs(File dir, CleanResult cr) {
        if (dir == null || !dir.isDirectory()) return;
        if (".thumbnails".equals(dir.getName())) {
            deleteFilesRecursive(dir, cr);
            return;
        }
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) {
                deleteThumbsDirs(k, cr);
            }
        }
    }

    /** 递归删除扩展名为 {@code .log} 的文件。 */
    private static CleanResult cleanLogs(File root) {
        CleanResult cr = new CleanResult();
        deleteMatchingFiles(root, null, cr, new FilePredicate() {
            @Override
            public boolean accept(File f) {
                if (!f.isFile()) return false;
                String n = f.getName().toLowerCase(Locale.ROOT);
                return n.endsWith(".log");
            }
        });
        return cr;
    }

    /**
     * 多轮扫除空目录：删除子空目录后父目录可能变空，故循环最多 64 次直至一轮无删除。
     */
    private static CleanResult cleanEmptyDirs(File root) {
        CleanResult cr = new CleanResult();
        int passes = 0;
        while (passes < 64) {
            int removed = sweepEmptyDirsOnce(root, root, cr);
            if (removed == 0) break;
            passes++;
        }
        return cr;
    }

    /**
     * 单次深度遍历：先处理子目录，再若当前目录为空且非根则删除。
     *
     * @return 本轮删除的空目录数量
     */
    private static int sweepEmptyDirsOnce(File root, File dir, CleanResult cr) {
        if (dir == null || !dir.isDirectory() || dir.equals(root)) {
            return sweepChildrenEmpty(root, dir, cr);
        }
        File[] kids = dir.listFiles();
        if (kids == null) return 0;
        int removed = 0;
        for (File k : kids) {
            if (k.isDirectory()) {
                removed += sweepEmptyDirsOnce(root, k, cr);
            }
        }
        kids = dir.listFiles();
        if (kids != null && kids.length == 0 && !dir.equals(root)) {
            if (dir.delete()) {
                cr.dirs++;
                removed++;
            }
        }
        return removed;
    }

    /**
     * 从根目录起仅对其直接子目录启动 {@link #sweepEmptyDirsOnce}（用于根节点特殊处理）。
     */
    private static int sweepChildrenEmpty(File root, File dir, CleanResult cr) {
        File[] kids = dir != null ? dir.listFiles() : null;
        if (kids == null) return 0;
        int removed = 0;
        for (File k : kids) {
            if (k.isDirectory()) {
                removed += sweepEmptyDirsOnce(root, k, cr);
            }
        }
        return removed;
    }

    /**
     * 递归删除文件；空目录在子项删尽后尝试删除自身（不统计为「文件」删除）。
     */
    private static void deleteFilesRecursive(File dir, CleanResult cr) {
        if (dir == null || !dir.exists()) return;
        if (dir.isFile()) {
            tryDeleteFile(dir, cr);
            return;
        }
        File[] kids = dir.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteFilesRecursive(k, cr);
            }
        }
        File[] after = dir.listFiles();
        if (dir.isDirectory() && (after == null || after.length == 0)) {
            dir.delete();
        }
    }

    /**
     * 递归删除满足 {@code pred} 的文件；{@code skipDir} 非空时跳过其下路径（避免重复删或误删）。
     */
    private static void deleteMatchingFiles(File dir, File skipDir, CleanResult cr, FilePredicate pred) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isFile()) {
                if (skipDir != null && isUnderDirectory(k, skipDir)) continue;
                if (pred.accept(k)) tryDeleteFile(k, cr);
            } else if (k.isDirectory()) {
                deleteMatchingFiles(k, skipDir, cr, pred);
            }
        }
    }

    /** 尝试删除单个文件，成功则增加计数与释放字节统计。 */
    private static void tryDeleteFile(File f, CleanResult cr) {
        if (f == null || !f.isFile()) return;
        long len = f.length();
        if (f.delete()) {
            cr.files++;
            cr.bytes += len;
        }
    }

    // ---------- JSON 与 StatFs 兼容封装 ----------

    /**
     * 将某挂载路径转为存储统计 JSON（带格式化 GB/TB 文案）。
     *
     * @param path 分区挂载点路径
     * @return JSON 或 null（路径无效）
     */
    private static JSONObject statFsToJson(File path) throws Exception {
        if (path == null || !path.exists()) return null;
        StatFs stat = new StatFs(path.getAbsolutePath());
        long blockSize = getBlockSizeCompat(stat);
        long total = getBlockCountCompat(stat) * blockSize;
        long avail = getAvailableBlocksCompat(stat) * blockSize;
        long used = total - avail;
        JSONObject o = new JSONObject();
        o.put("path", path.getAbsolutePath());
        o.put("totalBytes", total);
        o.put("availableBytes", avail);
        o.put("usedBytes", used);
        double usedPct = total > 0 ? Math.round(used * 10000.0 / total) / 100.0 : 0.0;
        o.put("usedPercent", usedPct);
        o.put("totalFormatted", formatStorageGb(total));
        o.put("usedFormatted", formatStorageGb(used));
        o.put("freeFormatted", formatStorageGb(avail));
        return o;
    }

    /** API 17+ 使用 {@link StatFs#getBlockSizeLong()}，否则回退旧 API。 */
    private static long getBlockSizeCompat(StatFs stat) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            return stat.getBlockSizeLong();
        }
        //noinspection deprecation
        return stat.getBlockSize();
    }

    /** 块总数的长整型兼容读取。 */
    private static long getBlockCountCompat(StatFs stat) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            return stat.getBlockCountLong();
        }
        //noinspection deprecation
        return stat.getBlockCount();
    }

    /** 可用块数的长整型兼容读取。 */
    private static long getAvailableBlocksCompat(StatFs stat) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            return stat.getAvailableBlocksLong();
        }
        //noinspection deprecation
        return stat.getAvailableBlocks();
    }

    /**
     * 将字节数转为易读 GB/TB 字符串（英文单位，US 区域格式）。
     */
    private static String formatStorageGb(long bytes) {
        if (bytes < 0) bytes = 0;
        double gb = bytes / (1024.0 * 1024.0 * 1024.0);
        if (gb < 1024) {
            return String.format(Locale.US, gb >= 100 ? "%.0f GB" : "%.1f GB", gb);
        }
        double tb = gb / 1024.0;
        return String.format(Locale.US, "%.2f TB", tb);
    }

    /**
     * 解析逗号分隔类别名（小写）；若 {@code csv} 空且 {@code defaultAll} 为 true 则返回全部默认类别集合。
     */
    private static Set<String> parseCategories(String csv, boolean defaultAll) {
        if (csv == null || csv.trim().isEmpty()) {
            if (defaultAll) {
                return new HashSet<>(Arrays.asList(
                        "temp", "cache", "apk", "thumbs", "empty_dirs", "logs"));
            }
            return new HashSet<>();
        }
        Set<String> s = new HashSet<>();
        for (String p : csv.split(",")) {
            String t = p.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty()) s.add(t);
        }
        return s;
    }

    /** 空参数字符串规范化为 {@code "{}"}。 */
    private static String emptyJson(String v) {
        return v == null || v.trim().isEmpty() ? "{}" : v;
    }

    /** 业务层错误描述 JSON（嵌入 {@code output} 时由展示函数识别 {@code error} 键）。 */
    private static String errJson(String msg) throws Exception {
        return new JSONObject().put("error", msg).toString();
    }

    /**
     * 成功响应，附带可选 {@code _displayText} 与 {@code _displayHtml}。
     *
     * @param output       业务 JSON 字符串
     * @param displayText  简短 UI 文案
     * @param displayHtml  可选 HTML5 迷你卡片
     */
    private String ok(String output, String displayText, String displayHtml) throws Exception {
        JSONObject r = new JSONObject().put("success", true).put("output", output);
        if (displayText != null && !displayText.isEmpty()) {
            r.put("_displayText", displayText);
        }
        if (displayHtml != null && !displayHtml.isEmpty()) {
            r.put("_displayHtml", displayHtml);
        }
        return r.toString();
    }

    /** 顶层失败响应。 */
    private String error(String message) throws Exception {
        return new JSONObject().put("success", false).put("error", message).toString();
    }

    /** 将字节转为 B/KB/MB/GB 简短字符串。 */
    private static String formatSizeBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return SIZE_FMT.format(bytes / 1024.0) + " KB";
        if (bytes < 1024L * 1024 * 1024) return SIZE_FMT.format(bytes / (1024.0 * 1024)) + " MB";
        return SIZE_FMT.format(bytes / (1024.0 * 1024 * 1024)) + " GB";
    }

    /** 安全解析 {@code output} 字符串为 {@link JSONObject}。 */
    private static JSONObject parseOutputJson(String output) {
        try {
            return new JSONObject(output);
        } catch (Exception e) {
            return null;
        }
    }

    private static String mdCell(String s) {
        if (s == null) return "";
        return s.replace("\r", "").replace("\n", " ").replace("|", "\\|");
    }

    /** {@code scanJunk} 结果的人类可读摘要。 */
    private static String formatScanJunkDisplay(String output) {
        boolean zh = isZh();
        JSONObject o = parseOutputJson(output);
        if (o == null) return zh ? "🔍 垃圾扫描结果\n" : "🔍 Junk Scan Results\n";
        if (o.has("error")) return "❌ " + o.optString("error");
        JSONObject cats = o.optJSONObject("categories");
        if (cats == null) return zh ? "🔍 垃圾扫描结果\n" : "🔍 Junk Scan Results\n";
        String title = zh ? "🔍 垃圾扫描结果" : "🔍 Junk Scan Results";
        String[] h1 = zh ? new String[]{"类别", "数量", "大小"} : new String[]{"Category", "Count", "Size"};
        List<String[]> rows1 = new ArrayList<>();
        appendScanCategoryRow(rows1, cats, "temp", zh ? "临时文件" : "Temp files");
        appendScanCategoryRow(rows1, cats, "cache", zh ? "缓存" : "Cache");
        appendScanCategoryRow(rows1, cats, "apk", "APK");
        appendScanCategoryRow(rows1, cats, "thumbs", zh ? "缩略图" : "Thumbs");
        appendScanCategoryRow(rows1, cats, "empty_dirs", zh ? "空目录" : "Empty dirs");
        appendScanCategoryRow(rows1, cats, "logs", zh ? "日志" : "Logs");
        String part1 = pgTable(title, h1, rows1);
        String[] h2 = zh ? new String[]{"项目", "值"} : new String[]{"Item", "Value"};
        List<String[]> rows2 = new ArrayList<>();
        rows2.add(new String[]{
                zh ? "可回收总计" : "Total reclaimable",
                mdCell(o.optString("totalSizeFormatted", formatSizeBytes(o.optLong("totalSizeBytes", 0))))
        });
        String part2 = pgTable(zh ? "汇总" : "Summary", h2, rows2);
        return (part1 + "\n\n" + part2).trim();
    }

    private static void appendScanCategoryRow(List<String[]> rows, JSONObject cats, String key, String label) {
        JSONObject c = cats.optJSONObject(key);
        if (c == null) return;
        int count = c.optInt("count", 0);
        String size = c.optString("sizeFormatted", formatSizeBytes(c.optLong("sizeBytes", 0)));
        rows.add(new String[]{mdCell(label), String.valueOf(count), mdCell(size)});
    }

    /** {@code cleanJunk} 完成后的删除项数与释放空间摘要。 */
    private static String formatCleanJunkDisplay(String output) {
        boolean zh = isZh();
        JSONObject o = parseOutputJson(output);
        if (o == null) return zh ? "🧹 清理完成\n" : "🧹 Cleanup Complete!\n";
        if (o.has("error")) return "❌ " + o.optString("error");
        int items = o.optInt("deletedItems", o.optInt("deletedFiles", 0));
        int files = o.optInt("deletedFiles", 0);
        int dirs = o.optInt("deletedDirs", 0);
        String title = zh ? "🧹 清理完成" : "🧹 Cleanup Complete!";
        String[] h1 = zh ? new String[]{"项目", "值"} : new String[]{"Item", "Value"};
        List<String[]> rows1 = new ArrayList<>();
        rows1.add(new String[]{zh ? "已移除项目总数" : "Total items removed", String.valueOf(items)});
        rows1.add(new String[]{zh ? "已删除文件" : "Files deleted", String.valueOf(files)});
        rows1.add(new String[]{zh ? "已删除目录" : "Dirs deleted", String.valueOf(dirs)});
        rows1.add(new String[]{
                zh ? "释放空间" : "Space freed",
                mdCell(o.optString("freedFormatted", formatSizeBytes(o.optLong("freedBytes", 0))))
        });
        String part1 = pgTable(title, h1, rows1);
        JSONObject byCat = o.optJSONObject("byCategory");
        if (byCat == null || byCat.length() == 0) {
            return part1.trim();
        }
        String[] h2 = zh
                ? new String[]{"类别", "已删除文件", "已删除目录", "释放空间"}
                : new String[]{"Category", "Files removed", "Dirs removed", "Space freed"};
        List<String[]> rows2 = new ArrayList<>();
        appendCleanCategoryRow(rows2, byCat, "temp", zh ? "临时文件" : "Temp");
        appendCleanCategoryRow(rows2, byCat, "cache", zh ? "缓存" : "Cache");
        appendCleanCategoryRow(rows2, byCat, "apk", "APK");
        appendCleanCategoryRow(rows2, byCat, "thumbs", zh ? "缩略图" : "Thumbs");
        appendCleanCategoryRow(rows2, byCat, "logs", zh ? "日志" : "Logs");
        appendCleanCategoryRow(rows2, byCat, "empty_dirs", zh ? "空目录" : "Empty dirs");
        String part2 = pgTable(zh ? "按类别" : "By category", h2, rows2);
        return (part1 + "\n\n" + part2).trim();
    }

    private static void appendCleanCategoryRow(List<String[]> rows, JSONObject byCat, String key, String label) {
        JSONObject c = byCat.optJSONObject(key);
        if (c == null) return;
        int f = c.optInt("filesDeleted", 0);
        int d = c.optInt("dirsDeleted", 0);
        String freed = c.optString("freedFormatted", formatSizeBytes(c.optLong("freedBytes", 0)));
        rows.add(new String[]{mdCell(label), String.valueOf(f), String.valueOf(d), mdCell(freed)});
    }

    /** 优先展示主外部存储的总用量/空闲；不可用时回退内部存储字段。 */
    private static String formatStorageInfoDisplay(String output) {
        boolean zh = isZh();
        JSONObject o = parseOutputJson(output);
        if (o == null) return zh ? "💾 存储信息\n" : "💾 Storage Info\n";
        if (o.has("error")) return "❌ " + o.optString("error");
        String title = zh ? "💾 存储信息" : "💾 Storage Info";
        String[] headers = zh
                ? new String[]{"分区", "路径", "总计", "已用", "已用 %", "可用"}
                : new String[]{"Partition", "Path", "Total", "Used", "Used %", "Free"};
        List<String[]> rows = new ArrayList<>();
        boolean any = false;
        JSONObject internal = o.optJSONObject("internal");
        if (internal != null && internal != JSONObject.NULL) {
            appendStorageRow(rows, zh ? "内部" : "Internal", internal, null, zh);
            any = true;
        }
        JSONObject ext = o.optJSONObject("externalPrimary");
        if (ext != null && ext != JSONObject.NULL) {
            appendStorageRow(rows, zh ? "外部（主）" : "External (primary)", ext, ext.optString("state", ""), zh);
            any = true;
        }
        if (!any) {
            String[] h0 = zh ? new String[]{"项目", "值"} : new String[]{"Item", "Value"};
            List<String[]> r0 = new ArrayList<>();
            r0.add(new String[]{zh ? "状态" : "Status", "—"});
            return pgTable(title, h0, r0).trim();
        }
        return pgTable(title, headers, rows).trim();
    }

    private static void appendStorageRow(List<String[]> rows, String label, JSONObject part, String state, boolean zh) {
        String path = mdCell(part.optString("path", "—"));
        String note = label;
        if (state != null && !state.isEmpty()) {
            note = label + (zh ? "（" + state + "）" : " (" + state + ")");
        }
        rows.add(new String[]{
                mdCell(note),
                path,
                mdCell(part.optString("totalFormatted", "—")),
                mdCell(part.optString("usedFormatted", "—")),
                String.format(Locale.US, "%.1f%%", part.optDouble("usedPercent", 0)),
                mdCell(part.optString("freeFormatted", "—"))
        });
    }

    /** 列出前至多 20 个大文件名与大小，超出部分用省略提示。 */
    private static String formatFindLargeFilesDisplay(String output) {
        boolean zh = isZh();
        JSONObject o = parseOutputJson(output);
        if (o == null) return zh ? "📦 大文件\n" : "📦 Large Files\n";
        if (o.has("error")) return "❌ " + o.optString("error");
        JSONArray files = o.optJSONArray("files");
        if (files == null) return zh ? "📦 大文件\n" : "📦 Large Files\n";
        String banner = zh ? "📦 大文件" : "📦 Large Files";
        String headerLine = zh
                ? ("目录: " + mdCell(o.optString("directory", "")) + "，最小 "
                + o.optDouble("minSizeMB", 0) + " MB，找到 "
                + o.optInt("found", 0) + "，显示 " + o.optInt("showing", 0))
                : ("Directory: " + mdCell(o.optString("directory", "")) + ", min "
                + o.optDouble("minSizeMB", 0) + " MB, found "
                + o.optInt("found", 0) + ", showing " + o.optInt("showing", 0));
        String[] headers = zh ? new String[]{"名称", "大小", "路径"} : new String[]{"Name", "Size", "Path"};
        List<String[]> rows = new ArrayList<>();
        int show = Math.min(20, files.length());
        for (int i = 0; i < show; i++) {
            JSONObject f = files.optJSONObject(i);
            if (f == null) continue;
            rows.add(new String[]{
                    mdCell(f.optString("name", "?")),
                    mdCell(f.optString("sizeFormatted", "")),
                    mdCell(f.optString("path", ""))
            });
        }
        String table = pgTable(zh ? "列表" : "List", headers, rows);
        StringBuilder sb = new StringBuilder();
        sb.append(banner).append("\n").append(headerLine).append("\n\n").append(table);
        if (files.length() > show) {
            sb.append("\n\n… (+").append(files.length() - show).append(zh ? " 更多)" : " more)");
        }
        return sb.toString().trim();
    }

    private static String formatScanJunkHtml(String output) {
        boolean zh = isZh();
        JSONObject o = parseOutputJson(output);
        if (o == null) {
            return "";
        }
        if (o.has("error")) {
            return HtmlOutputHelper.card("🔍", zh ? "垃圾扫描" : "Junk Scan",
                    HtmlOutputHelper.errorBadge() + HtmlOutputHelper.p(o.optString("error")));
        }
        JSONObject cats = o.optJSONObject("categories");
        if (cats == null) {
            return "";
        }
        String[] h1 = zh ? new String[]{"类别", "数量", "大小"} : new String[]{"Category", "Count", "Size"};
        List<String[]> rows1 = new ArrayList<>();
        appendScanCategoryRow(rows1, cats, "temp", zh ? "临时文件" : "Temp files");
        appendScanCategoryRow(rows1, cats, "cache", zh ? "缓存" : "Cache");
        appendScanCategoryRow(rows1, cats, "apk", "APK");
        appendScanCategoryRow(rows1, cats, "thumbs", zh ? "缩略图" : "Thumbs");
        appendScanCategoryRow(rows1, cats, "empty_dirs", zh ? "空目录" : "Empty dirs");
        appendScanCategoryRow(rows1, cats, "logs", zh ? "日志" : "Logs");
        String totalFmt = o.optString("totalSizeFormatted", formatSizeBytes(o.optLong("totalSizeBytes", 0)));
        String totalLabel = zh ? "可回收总计" : "Total reclaimable";
        StringBuilder body = new StringBuilder();
        body.append(HtmlOutputHelper.table(h1, rows1));
        body.append(HtmlOutputHelper.metricGrid(new String[][]{{totalFmt, totalLabel}}));
        return HtmlOutputHelper.card("🔍", zh ? "垃圾扫描结果" : "Junk Scan Results", body.toString());
    }

    private static String formatCleanJunkHtml(String output) {
        boolean zh = isZh();
        JSONObject o = parseOutputJson(output);
        if (o == null) {
            return "";
        }
        if (o.has("error")) {
            return HtmlOutputHelper.card("🧹", zh ? "清理" : "Cleanup",
                    HtmlOutputHelper.errorBadge() + HtmlOutputHelper.p(o.optString("error")));
        }
        int items = o.optInt("deletedItems", o.optInt("deletedFiles", 0));
        String freed = o.optString("freedFormatted", formatSizeBytes(o.optLong("freedBytes", 0)));
        String title = zh ? "清理完成" : "Cleanup Complete";
        String body = HtmlOutputHelper.successBadge()
                + HtmlOutputHelper.metricGrid(new String[][]{
                {freed, zh ? "释放空间" : "Space freed"},
                {String.valueOf(items), zh ? "已移除项目" : "Items removed"}
        });
        return HtmlOutputHelper.card("🧹", title, body);
    }

    private static String gaugeColorForUsedPercent(double pct) {
        if (pct >= 90.0) {
            return "#F44336";
        }
        if (pct >= 70.0) {
            return "#FF9800";
        }
        return "#4CAF50";
    }

    private static String formatStorageInfoHtml(String output) {
        boolean zh = isZh();
        JSONObject o = parseOutputJson(output);
        if (o == null) {
            return "";
        }
        if (o.has("error")) {
            return HtmlOutputHelper.card("💾", zh ? "存储" : "Storage",
                    HtmlOutputHelper.errorBadge() + HtmlOutputHelper.p(o.optString("error")));
        }
        JSONObject part = o.optJSONObject("externalPrimary");
        if (part == null || part == JSONObject.NULL) {
            part = o.optJSONObject("internal");
        }
        if (part == null || part == JSONObject.NULL) {
            return HtmlOutputHelper.card("💾", zh ? "存储信息" : "Storage Info",
                    HtmlOutputHelper.muted("—"));
        }
        double pct = part.optDouble("usedPercent", 0);
        int gaugePct = (int) Math.round(Math.max(0, Math.min(100, pct)));
        String total = part.optString("totalFormatted", "—");
        String used = part.optString("usedFormatted", "—");
        String free = part.optString("freeFormatted", "—");
        String title = zh ? "存储信息" : "Storage Info";
        String labelTotal = zh ? "总计" : "Total";
        String labelUsed = zh ? "已用" : "Used";
        String labelFree = zh ? "可用" : "Free";
        String labelUse = zh ? "已用占比" : "Used";
        StringBuilder body = new StringBuilder();
        body.append(HtmlOutputHelper.metricGrid(new String[][]{
                {total, labelTotal},
                {used, labelUsed},
                {free, labelFree}
        }));
        body.append(HtmlOutputHelper.muted(labelUse + ": " + String.format(Locale.US, "%.1f%%", pct)));
        body.append(HtmlOutputHelper.gauge(gaugePct, gaugeColorForUsedPercent(pct)));
        return HtmlOutputHelper.card("💾", title, body.toString());
    }

    private static String formatFindLargeFilesHtml(String output) {
        boolean zh = isZh();
        JSONObject o = parseOutputJson(output);
        if (o == null) {
            return "";
        }
        if (o.has("error")) {
            return HtmlOutputHelper.card("📦", zh ? "大文件" : "Large Files",
                    HtmlOutputHelper.errorBadge() + HtmlOutputHelper.p(o.optString("error")));
        }
        JSONArray files = o.optJSONArray("files");
        if (files == null) {
            return "";
        }
        String[] headers = zh ? new String[]{"名称", "大小", "路径"} : new String[]{"Name", "Size", "Path"};
        List<String[]> rows = new ArrayList<>();
        for (int i = 0; i < files.length(); i++) {
            JSONObject f = files.optJSONObject(i);
            if (f == null) {
                continue;
            }
            rows.add(new String[]{
                    f.optString("name", "?"),
                    f.optString("sizeFormatted", ""),
                    f.optString("path", "")
            });
        }
        String dir = o.optString("directory", "");
        String banner = (zh ? "目录: " : "Dir: ") + dir + " · "
                + (zh ? "最小 " : "min ") + o.optDouble("minSizeMB", 0) + " MB · "
                + (zh ? "找到 " : "found ") + o.optInt("found", 0) + " · "
                + (zh ? "显示 " : "showing ") + o.optInt("showing", rows.size());
        String body = HtmlOutputHelper.muted(banner) + HtmlOutputHelper.table(headers, rows);
        return HtmlOutputHelper.card("📦", zh ? "大文件" : "Large Files", body);
    }

    /** 扫描阶段某一类别的文件数与总字节（空目录仅计 count）。 */
    private static final class CategoryStat {
        int count;
        long sizeBytes;

        void addFile(long len) {
            count++;
            sizeBytes += len;
        }

        /** 空目录计件（无字节增量）。 */
        void addEmptyDir() {
            count++;
        }

        /** 转为写入结果 JSON 的统计对象。 */
        JSONObject toJson() throws Exception {
            JSONObject j = new JSONObject();
            j.put("count", count);
            j.put("sizeBytes", sizeBytes);
            j.put("sizeFormatted", formatSizeBytes(sizeBytes));
            return j;
        }
    }

    /** 清理阶段累计删除的文件数、目录数与释放字节。 */
    private static final class CleanResult {
        int files;
        int dirs;
        long bytes;

        /** 转为按类别汇总中的子 JSON。 */
        JSONObject toJson() throws Exception {
            JSONObject j = new JSONObject();
            j.put("filesDeleted", files);
            j.put("dirsDeleted", dirs);
            j.put("freedBytes", bytes);
            j.put("freedFormatted", formatSizeBytes(bytes));
            return j;
        }
    }
}
