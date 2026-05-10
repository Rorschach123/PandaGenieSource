package ai.rorsch.moduleplugins.storage_radar_report;

import ai.rorsch.pandagenie.module.runtime.HtmlOutputHelper;
import ai.rorsch.pandagenie.module.runtime.ModuleLlm;
import ai.rorsch.pandagenie.module.runtime.ModulePlugin;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class StorageRadarReportPlugin implements ModulePlugin {
    private static final int MAX_FILES = 30000;

    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        JSONObject params = new JSONObject(emptyJson(paramsJson));
        try {
            if ("scanStorageRadar".equals(action)) return scanStorageRadar(params, false);
            if ("generateCleanupPlan".equals(action)) return scanStorageRadar(params, true);
            if ("generateSmartCleanupAdvice".equals(action)) return generateSmartCleanupAdvice(context, params);
            if ("openPage".equals(action)) {
                return new JSONObject().put("success", true).put("output", "{}").put("_openModule", true).toString();
            }
            return error("Unsupported action: " + action);
        } catch (Exception e) {
            return error(e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    private String scanStorageRadar(JSONObject params, boolean cleanupPlan) throws Exception {
        String path = params.optString("path", "/storage/emulated/0").trim();
        if (path.isEmpty()) path = "/storage/emulated/0";
        int limit = Math.max(5, Math.min(100, params.optInt("limit", 20)));
        int maxDepth = Math.max(1, Math.min(12, params.optInt("maxDepth", 8)));
        long targetFree = Math.max(128, params.optLong("targetFreeMB", 1024)) * 1024L * 1024L;

        File root = new File(path);
        if (!root.exists() || !root.canRead()) {
            throw new IllegalArgumentException("目录不可读：" + path);
        }

        ScanState state = new ScanState(limit);
        scan(root, 0, maxDepth, state);
        JSONObject report = buildReport(root, state, limit, cleanupPlan, targetFree);
        return ok(report, formatText(report), formatHtml(report));
    }

    private String generateSmartCleanupAdvice(Context context, JSONObject params) throws Exception {
        JSONObject scanResponse = new JSONObject(scanStorageRadar(params, true));
        JSONObject report = new JSONObject(scanResponse.optString("output", "{}"));
        String language = params.optString("language", "zh").trim();
        String focus = params.optString("focus", "").trim();
        int maxTokens = Math.max(128, Math.min(1024, params.optInt("maxTokens", 768)));

        String prompt = "你是 PandaGenie 的手机存储清理顾问。请根据下面的扫描报告，给普通用户一份安全、可执行的清理建议。"
                + "\n要求："
                + "\n1. 不要建议直接删除不确定文件，优先提示用户人工确认。"
                + "\n2. 说明优先级、预计收益和风险。"
                + "\n3. 用简洁分点输出，避免技术黑话。"
                + "\n4. 如果报告数据不足，说明需要重新扫描或扩大授权范围。"
                + (focus.isEmpty() ? "" : "\n用户关注：" + focus)
                + "\n输出语言：" + language
                + "\n\n可读报告：\n" + formatText(report)
                + "\n\n结构化摘要：\n" + compactJson(report, 12000);
        JSONObject request = new JSONObject()
                .put("action", "storage_radar_report.generateSmartCleanupAdvice")
                .put("prompt", prompt)
                .put("temperature", 0.2)
                .put("maxTokens", maxTokens);
        JSONObject llm = new JSONObject(ModuleLlm.completeJson(context, request.toString()));
        if (!llm.optBoolean("success", false)) {
            throw new IllegalStateException(llm.optString("error", "LLM request failed"));
        }

        String advice = llm.optString("text", "").trim();
        JSONObject out = new JSONObject();
        out.put("root", report.optString("root"));
        out.put("scannedSize", report.optString("scannedSize"));
        out.put("fileCount", report.optInt("fileCount"));
        out.put("plannedFreeBytes", report.optLong("plannedFreeBytes"));
        out.put("plannedFreeSize", formatBytes(report.optLong("plannedFreeBytes")));
        out.put("scanReport", report);
        out.put("advice", advice);
        out.put("source", "llm");

        String display = "✨ 智能清理建议\n" + advice
                + "\n\n扫描目录：" + report.optString("root")
                + "\n已统计：" + report.optString("scannedSize");
        String html = HtmlOutputHelper.card("✨", "智能清理建议",
                HtmlOutputHelper.badge("LLM", "blue")
                        + HtmlOutputHelper.keyValue(new String[][]{
                        {"扫描目录", report.optString("root")},
                        {"已统计", report.optString("scannedSize")},
                        {"计划释放", formatBytes(report.optLong("plannedFreeBytes"))}
                })
                        + HtmlOutputHelper.p(advice)
                        + HtmlOutputHelper.muted("仅生成建议，不直接删除文件。"));
        return ok(out, display, html);
    }

    private void scan(File file, int depth, int maxDepth, ScanState state) {
        if (file == null || state.fileCount >= MAX_FILES) return;
        try {
            if (file.isHidden() && depth > 0) return;
            if (file.isFile()) {
                state.fileCount++;
                long size = file.length();
                state.totalBytes += size;
                String category = category(file);
                addCategory(state, category, size);
                FileRecord rec = new FileRecord(file.getAbsolutePath(), file.getName(), size, file.lastModified(), category);
                state.largeFiles.add(rec);
                if ("apk".equals(category)) state.apks.add(rec);
                if ("archive".equals(category)) state.archives.add(rec);
                if ("video".equals(category)) state.videos.add(rec);
                if (isOld(file)) state.oldFiles.add(rec);
                String dupKey = size + ":" + extension(file.getName()).toLowerCase(Locale.ROOT);
                if (size >= 1024 * 1024) {
                    List<FileRecord> group = state.duplicateCandidates.get(dupKey);
                    if (group == null) {
                        group = new ArrayList<FileRecord>();
                        state.duplicateCandidates.put(dupKey, group);
                    }
                    group.add(rec);
                }
                String p = file.getAbsolutePath().replace("\\", "/").toLowerCase(Locale.ROOT);
                if (p.contains("/download/")) state.downloadBytes += size;
                if (p.contains("/tencent/micromsg/") || p.contains("/wechat/")) state.wechatBytes += size;
                return;
            }
            if (!file.isDirectory() || depth > maxDepth) return;
            if (depth > 0) state.dirCount++;
            File[] children = file.listFiles();
            if (children == null) return;
            for (File child : children) scan(child, depth + 1, maxDepth, state);
        } catch (Exception ignored) {}
    }

    private JSONObject buildReport(File root, ScanState state, int limit, boolean cleanupPlan, long targetFreeBytes) throws Exception {
        sortRecords(state.largeFiles);
        sortRecords(state.apks);
        sortRecords(state.archives);
        sortRecords(state.videos);
        sortRecords(state.oldFiles);

        JSONArray duplicateGroups = new JSONArray();
        long duplicateWaste = 0;
        for (Map.Entry<String, List<FileRecord>> e : state.duplicateCandidates.entrySet()) {
            List<FileRecord> group = e.getValue();
            if (group.size() < 2) continue;
            sortRecords(group);
            long groupWaste = 0;
            JSONArray files = new JSONArray();
            for (int i = 0; i < group.size() && i < 6; i++) {
                files.put(group.get(i).toJson());
                if (i > 0) groupWaste += group.get(i).size;
            }
            duplicateWaste += groupWaste;
            duplicateGroups.put(new JSONObject()
                    .put("sameSizeAndType", e.getKey())
                    .put("count", group.size())
                    .put("possibleWasteBytes", groupWaste)
                    .put("files", files));
            if (duplicateGroups.length() >= 20) break;
        }

        JSONArray cleanup = new JSONArray();
        long planned = 0;
        if (cleanupPlan) {
            planned += addPlan(cleanup, "APK 安装包", state.apks, targetFreeBytes - planned);
            if (planned < targetFreeBytes) planned += addPlan(cleanup, "压缩包", state.archives, targetFreeBytes - planned);
            if (planned < targetFreeBytes) planned += addPlan(cleanup, "长期未动大文件", state.oldFiles, targetFreeBytes - planned);
        }

        JSONObject out = new JSONObject();
        out.put("root", root.getAbsolutePath());
        out.put("fileCount", state.fileCount);
        out.put("dirCount", state.dirCount);
        out.put("scannedBytes", state.totalBytes);
        out.put("scannedSize", formatBytes(state.totalBytes));
        out.put("categorySummary", categoriesJson(state));
        out.put("largestFiles", recordsJson(state.largeFiles, limit));
        out.put("videos", recordsJson(state.videos, Math.min(limit, 20)));
        out.put("apkInstallers", recordsJson(state.apks, Math.min(limit, 20)));
        out.put("archives", recordsJson(state.archives, Math.min(limit, 20)));
        out.put("oldFiles", recordsJson(state.oldFiles, Math.min(limit, 20)));
        out.put("duplicateCandidates", duplicateGroups);
        out.put("duplicatePossibleWasteBytes", duplicateWaste);
        out.put("downloadBytes", state.downloadBytes);
        out.put("wechatBytes", state.wechatBytes);
        out.put("cleanupPlan", cleanup);
        out.put("plannedFreeBytes", planned);
        out.put("coreJudgement", judgement(state, duplicateWaste, planned, cleanupPlan));
        return out;
    }

    private long addPlan(JSONArray cleanup, String reason, List<FileRecord> files, long remainBytes) throws Exception {
        if (remainBytes <= 0) return 0;
        long total = 0;
        for (int i = 0; i < files.size() && cleanup.length() < 30 && total < remainBytes; i++) {
            FileRecord f = files.get(i);
            cleanup.put(f.toJson().put("reason", reason));
            total += f.size;
        }
        return total;
    }

    private String judgement(ScanState s, long dupWaste, long planned, boolean cleanupPlan) {
        String top = topCategoryName(s);
        if (cleanupPlan) {
            return "建议先处理 " + top + "、APK/压缩包和长期未动大文件，计划可释放约 " + formatBytes(planned);
        }
        if (s.totalBytes == 0) return "未扫描到可统计文件，建议检查目录权限";
        if (dupWaste >= 1024L * 1024L * 1024L) return "发现较多疑似重复占用，建议先人工核对重复组";
        return "空间主要集中在 " + top + "，建议按报告从大文件开始确认";
    }

    private String formatText(JSONObject r) {
        StringBuilder sb = new StringBuilder();
        sb.append("📡 PandaGenie 存储雷达报告\n");
        sb.append("核心判断：").append(r.optString("coreJudgement")).append("\n\n");
        sb.append("扫描目录：").append(r.optString("root")).append("\n");
        sb.append("文件数量：").append(r.optInt("fileCount")).append("，目录数量：").append(r.optInt("dirCount")).append("\n");
        sb.append("已统计体积：").append(r.optString("scannedSize")).append("\n");
        sb.append("下载目录：").append(formatBytes(r.optLong("downloadBytes"))).append("\n");
        sb.append("微信相关目录：").append(formatBytes(r.optLong("wechatBytes"))).append("\n");
        sb.append("疑似重复可节省：").append(formatBytes(r.optLong("duplicatePossibleWasteBytes"))).append("\n\n");
        sb.append("最大文件 Top 5：\n");
        JSONArray large = r.optJSONArray("largestFiles");
        for (int i = 0; large != null && i < Math.min(5, large.length()); i++) {
            JSONObject f = large.optJSONObject(i);
            if (f != null) sb.append("· ").append(f.optString("size")).append("  ").append(f.optString("name")).append("\n");
        }
        JSONArray plan = r.optJSONArray("cleanupPlan");
        if (plan != null && plan.length() > 0) {
            sb.append("\n清理计划：预计释放 ").append(formatBytes(r.optLong("plannedFreeBytes"))).append("\n");
            for (int i = 0; i < Math.min(5, plan.length()); i++) {
                JSONObject f = plan.optJSONObject(i);
                if (f != null) sb.append("· ").append(f.optString("reason")).append("：").append(f.optString("size")).append("  ").append(f.optString("name")).append("\n");
            }
        }
        sb.append("\n注意：本模块只生成计划，不直接删除文件。\n由 PandaGenie 生成 · 开源免费 Android AI 助手");
        return sb.toString();
    }

    private String formatHtml(JSONObject r) {
        String body = HtmlOutputHelper.badge("空间去向", "blue")
                + HtmlOutputHelper.metricGrid(new String[][]{
                {r.optString("scannedSize"), "统计体积"},
                {String.valueOf(r.optInt("fileCount")), "文件数"},
                {formatBytes(r.optLong("downloadBytes")), "下载目录"},
                {formatBytes(r.optLong("duplicatePossibleWasteBytes")), "疑似重复"}
        });
        body += HtmlOutputHelper.keyValue(new String[][]{
                {"核心判断", r.optString("coreJudgement")},
                {"扫描目录", r.optString("root")},
                {"微信相关目录", formatBytes(r.optLong("wechatBytes"))},
                {"计划释放", formatBytes(r.optLong("plannedFreeBytes"))}
        });
        List<String[]> rows = new ArrayList<String[]>();
        JSONArray large = r.optJSONArray("largestFiles");
        for (int i = 0; large != null && i < Math.min(8, large.length()); i++) {
            JSONObject f = large.optJSONObject(i);
            if (f != null) rows.add(new String[]{f.optString("size"), f.optString("category"), f.optString("name")});
        }
        body += HtmlOutputHelper.table(new String[]{"大小", "类型", "文件"}, rows);
        body += HtmlOutputHelper.muted("本报告只生成清理建议，不直接删除文件。由 PandaGenie 生成。");
        return HtmlOutputHelper.card("📡", "存储雷达报告", body);
    }

    private JSONArray categoriesJson(ScanState s) throws Exception {
        JSONArray arr = new JSONArray();
        for (Map.Entry<String, Long> e : s.categoryBytes.entrySet()) {
            arr.put(new JSONObject()
                    .put("category", e.getKey())
                    .put("bytes", e.getValue())
                    .put("size", formatBytes(e.getValue()))
                    .put("count", s.categoryCounts.containsKey(e.getKey()) ? s.categoryCounts.get(e.getKey()).intValue() : 0));
        }
        return arr;
    }

    private JSONArray recordsJson(List<FileRecord> list, int limit) throws Exception {
        JSONArray arr = new JSONArray();
        for (int i = 0; i < list.size() && i < limit; i++) arr.put(list.get(i).toJson());
        return arr;
    }

    private void addCategory(ScanState s, String category, long size) {
        Long oldBytes = s.categoryBytes.get(category);
        Integer oldCount = s.categoryCounts.get(category);
        s.categoryBytes.put(category, (oldBytes == null ? 0 : oldBytes) + size);
        s.categoryCounts.put(category, (oldCount == null ? 0 : oldCount) + 1);
    }

    private String topCategoryName(ScanState s) {
        String top = "其他文件";
        long max = -1;
        for (Map.Entry<String, Long> e : s.categoryBytes.entrySet()) {
            if (e.getValue() > max) {
                max = e.getValue();
                top = label(e.getKey());
            }
        }
        return top;
    }

    private void sortRecords(List<FileRecord> records) {
        Collections.sort(records, new Comparator<FileRecord>() {
            @Override public int compare(FileRecord a, FileRecord b) {
                return Long.compare(b.size, a.size);
            }
        });
    }

    private boolean isOld(File file) {
        return System.currentTimeMillis() - file.lastModified() > 90L * 24L * 3600L * 1000L && file.length() >= 50L * 1024L * 1024L;
    }

    private String category(File file) {
        String ext = extension(file.getName()).toLowerCase(Locale.ROOT);
        if (matches(ext, "mp4,mkv,mov,avi,webm,3gp,m4v")) return "video";
        if (matches(ext, "jpg,jpeg,png,webp,gif,heic,bmp")) return "image";
        if (matches(ext, "mp3,m4a,flac,wav,ogg,aac")) return "audio";
        if (matches(ext, "apk,apks,xapk")) return "apk";
        if (matches(ext, "zip,rar,7z,gz,tar,bz2,xz")) return "archive";
        if (matches(ext, "pdf,doc,docx,xls,xlsx,ppt,pptx,txt,md,epub")) return "document";
        return "other";
    }

    private String label(String category) {
        if ("video".equals(category)) return "视频";
        if ("image".equals(category)) return "图片";
        if ("audio".equals(category)) return "音频";
        if ("apk".equals(category)) return "APK 安装包";
        if ("archive".equals(category)) return "压缩包";
        if ("document".equals(category)) return "文档";
        return "其他文件";
    }

    private boolean matches(String ext, String csv) {
        return ("," + csv + ",").contains("," + ext + ",");
    }

    private String extension(String name) {
        int idx = name.lastIndexOf('.');
        return idx >= 0 ? name.substring(idx + 1) : "";
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb);
        double gb = mb / 1024.0;
        if (gb < 1024) return String.format(Locale.US, "%.2f GB", gb);
        return String.format(Locale.US, "%.2f TB", gb / 1024.0);
    }

    private String compactJson(JSONObject obj, int maxLen) {
        String text = obj == null ? "{}" : obj.toString();
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }

    private String emptyJson(String v) {
        return v == null || v.trim().isEmpty() ? "{}" : v;
    }

    private String ok(JSONObject output, String displayText, String displayHtml) throws Exception {
        JSONObject r = new JSONObject().put("success", true).put("output", output.toString());
        if (displayText != null) r.put("_displayText", displayText);
        if (displayHtml != null) r.put("_displayHtml", displayHtml);
        return r.toString();
    }

    private String error(String msg) throws Exception {
        return new JSONObject().put("success", false).put("error", msg).toString();
    }

    private static class ScanState {
        final int limit;
        int fileCount;
        int dirCount;
        long totalBytes;
        long downloadBytes;
        long wechatBytes;
        final Map<String, Long> categoryBytes = new HashMap<String, Long>();
        final Map<String, Integer> categoryCounts = new HashMap<String, Integer>();
        final Map<String, List<FileRecord>> duplicateCandidates = new HashMap<String, List<FileRecord>>();
        final List<FileRecord> largeFiles = new ArrayList<FileRecord>();
        final List<FileRecord> apks = new ArrayList<FileRecord>();
        final List<FileRecord> archives = new ArrayList<FileRecord>();
        final List<FileRecord> videos = new ArrayList<FileRecord>();
        final List<FileRecord> oldFiles = new ArrayList<FileRecord>();
        ScanState(int limit) { this.limit = limit; }
    }

    private static class FileRecord {
        final String path;
        final String name;
        final long size;
        final long modified;
        final String category;
        FileRecord(String path, String name, long size, long modified, String category) {
            this.path = path;
            this.name = name;
            this.size = size;
            this.modified = modified;
            this.category = category;
        }
        JSONObject toJson() throws Exception {
            return new JSONObject()
                    .put("path", path)
                    .put("name", name)
                    .put("bytes", size)
                    .put("size", formatBytes(size))
                    .put("modifiedAt", modified)
                    .put("category", category);
        }
    }
}
