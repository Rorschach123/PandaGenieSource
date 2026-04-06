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

public class FileStatsPlugin implements ModulePlugin {

    private static final SimpleDateFormat SDF = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
    private static final DecimalFormat SIZE_FMT = new DecimalFormat("#,##0.##");
    private static final int DISPLAY_LIST_MAX = 20;

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

    private String getFileInfo(JSONObject params) throws Exception {
        String path = params.optString("path", "").trim();
        if (path.isEmpty()) return errJson("Missing parameter: path");
        File f = new File(path);
        if (!f.exists()) return errJson("File not found: " + path);

        JSONObject info = buildFileInfo(f);
        return info.toString();
    }

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

    private void collectLargeFiles(File dir, boolean recursive, long minBytes, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isFile() && child.length() >= minBytes) out.add(child);
            else if (child.isDirectory() && recursive) collectLargeFiles(child, true, minBytes, out);
        }
    }

    // ==================== searchByName ====================

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

    private String normalizeAlgo(String input) {
        if (input == null) return "SHA-256";
        String upper = input.trim().toUpperCase(Locale.ROOT).replace(" ", "");
        if (upper.equals("MD5")) return "MD5";
        if (upper.equals("SHA1") || upper.equals("SHA-1")) return "SHA-1";
        return "SHA-256";
    }

    private String getExtension(String name) {
        int dot = name.lastIndexOf('.');
        return (dot >= 0 && dot < name.length() - 1) ? name.substring(dot + 1) : "";
    }

    private String getMimeType(String ext) {
        if (ext.isEmpty()) return "application/octet-stream";
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase(Locale.ROOT));
        return mime != null ? mime : "application/octet-stream";
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return SIZE_FMT.format(bytes / 1024.0) + " KB";
        if (bytes < 1024L * 1024 * 1024) return SIZE_FMT.format(bytes / (1024.0 * 1024)) + " MB";
        return SIZE_FMT.format(bytes / (1024.0 * 1024 * 1024)) + " GB";
    }

    private String emptyJson(String v) { return v == null || v.trim().isEmpty() ? "{}" : v; }

    private String errJson(String msg) throws Exception {
        return new JSONObject().put("error", msg).toString();
    }

    private String ok(String output) throws Exception {
        return ok(output, null);
    }

    private String ok(String output, String displayText) throws Exception {
        JSONObject r = new JSONObject().put("success", true).put("output", output);
        if (displayText != null && !displayText.isEmpty()) r.put("_displayText", displayText);
        return r.toString();
    }

    private String error(String message) throws Exception {
        return new JSONObject().put("success", false).put("error", message).toString();
    }

    private static JSONObject parseOutputJson(String output) {
        try {
            return new JSONObject(output);
        } catch (Exception e) {
            return null;
        }
    }

    private static String formatStatsGetFileInfoDisplay(String output) {
        JSONObject o = parseOutputJson(output);
        if (o == null) return "📄 File Info\n━━━━━━━━━━━━━━\n";
        if (o.has("error")) return "❌ " + o.optString("error");
        return "📄 File Info\n━━━━━━━━━━━━━━\n▸ Name: " + o.optString("name", "")
                + "\n▸ Size: " + o.optString("sizeFormatted", String.valueOf(o.optLong("size", 0)))
                + "\n▸ Modified: " + o.optString("lastModified", "");
    }

    private static String formatGetFileHashDisplay(String output) {
        JSONObject o = parseOutputJson(output);
        if (o == null) return "🔐 File Hash\n━━━━━━━━━━━━━━\n";
        if (o.has("error")) return "❌ " + o.optString("error");
        return "🔐 File Hash\n━━━━━━━━━━━━━━\n▸ Algorithm: " + o.optString("algorithm", "")
                + "\n▸ Hash: " + o.optString("hash", "");
    }

    private static String formatCompareFilesDisplay(String output) {
        JSONObject o = parseOutputJson(output);
        if (o == null) return "🔍 File Comparison\n━━━━━━━━━━━━━━\n";
        if (o.has("error")) return "❌ " + o.optString("error");
        boolean same = o.optBoolean("identical", false);
        return "🔍 File Comparison\n━━━━━━━━━━━━━━\n▸ Identical: " + (same ? "✅" : "❌");
    }

    private static String formatVerifyChecksumDisplay(String output) {
        JSONObject o = parseOutputJson(output);
        if (o == null) return "";
        if (o.has("error")) return "❌ " + o.optString("error");
        boolean ok = o.optBoolean("verified", false);
        return ok ? "✅ Checksum verified" : "❌ Mismatch";
    }

    private static String formatGetDirStatsDisplay(String output) {
        JSONObject o = parseOutputJson(output);
        if (o == null) return "📊 Directory Stats\n━━━━━━━━━━━━━━\n";
        if (o.has("error")) return "❌ " + o.optString("error");
        return "📊 Directory Stats\n━━━━━━━━━━━━━━\n▸ Files: " + o.optLong("fileCount", 0)
                + "\n▸ Total Size: " + o.optString("totalSizeFormatted", "");
    }

    private static String formatFindDuplicatesDisplay(String output) {
        JSONObject o = parseOutputJson(output);
        if (o == null) return "🔁 Duplicate Files\n━━━━━━━━━━━━━━\n";
        if (o.has("error")) return "❌ " + o.optString("error");
        int groups = o.optInt("duplicateGroups", 0);
        StringBuilder sb = new StringBuilder();
        sb.append("🔁 Duplicate Files\n━━━━━━━━━━━━━━\nFound ").append(groups).append(" groups\n");
        JSONArray dups = o.optJSONArray("duplicates");
        if (dups == null) return sb.toString().trim();
        int showG = Math.min(DISPLAY_LIST_MAX, dups.length());
        for (int g = 0; g < showG; g++) {
            JSONObject grp = dups.optJSONObject(g);
            if (grp == null) continue;
            int cnt = grp.optInt("count", 0);
            String sz = grp.optString("fileSizeFormatted", "");
            JSONArray files = grp.optJSONArray("files");
            String first = (files != null && files.length() > 0) ? files.optString(0, "") : "";
            sb.append("▸ ").append(cnt).append("× ").append(sz);
            if (!first.isEmpty()) sb.append("\n   ").append(first);
            sb.append("\n");
        }
        if (dups.length() > showG) sb.append("… (+").append(dups.length() - showG).append(" more groups)");
        return sb.toString().trim();
    }

    private static String formatFindLargeFilesDisplay(String output) {
        JSONObject o = parseOutputJson(output);
        if (o == null) return "📦 Large Files\n━━━━━━━━━━━━━━\n";
        if (o.has("error")) return "❌ " + o.optString("error");
        JSONArray files = o.optJSONArray("files");
        StringBuilder sb = new StringBuilder("📦 Large Files\n━━━━━━━━━━━━━━\n");
        if (files == null) return sb.toString().trim();
        int show = Math.min(DISPLAY_LIST_MAX, files.length());
        for (int i = 0; i < show; i++) {
            JSONObject f = files.optJSONObject(i);
            if (f == null) continue;
            sb.append(i + 1).append(". ").append(f.optString("name", "?"))
                    .append(" (").append(f.optString("sizeFormatted", "")).append(")\n");
        }
        if (files.length() > show) sb.append("… (+").append(files.length() - show).append(" more)");
        return sb.toString().trim();
    }

    private static String formatSearchByNameDisplay(String output) {
        JSONObject o = parseOutputJson(output);
        if (o == null) return "🔍 Files Found\n━━━━━━━━━━━━━━\n";
        if (o.has("error")) return "❌ " + o.optString("error");
        int found = o.optInt("found", 0);
        JSONArray files = o.optJSONArray("files");
        StringBuilder sb = new StringBuilder();
        sb.append("🔍 Files Found\n━━━━━━━━━━━━━━\n").append(found).append(" matches\n");
        if (files == null) return sb.toString().trim();
        int show = Math.min(DISPLAY_LIST_MAX, files.length());
        for (int i = 0; i < show; i++) {
            JSONObject f = files.optJSONObject(i);
            String p = f != null ? f.optString("path", "?") : "?";
            sb.append(i + 1).append(". ").append(p).append("\n");
        }
        if (files.length() > show) sb.append("… (+").append(files.length() - show).append(" more)");
        return sb.toString().trim();
    }

    private static String formatGetTextStatsDisplay(String output) {
        JSONObject o = parseOutputJson(output);
        if (o == null) return "📝 Text Stats\n━━━━━━━━━━━━━━\n";
        if (o.has("error")) return "❌ " + o.optString("error");
        return "📝 Text Stats\n━━━━━━━━━━━━━━\n▸ Lines: " + o.optLong("totalLines", 0)
                + "\n▸ Words: " + o.optLong("words", 0)
                + "\n▸ Chars: " + o.optLong("characters", 0);
    }

    private static String formatBatchFileInfoDisplay(String output) {
        JSONObject o = parseOutputJson(output);
        if (o == null) return "📋 Batch File Info\n━━━━━━━━━━━━━━\n";
        if (o.has("error")) return "❌ " + o.optString("error");
        return "📋 Batch File Info\n━━━━━━━━━━━━━━\n" + o.optInt("count", 0) + " files analyzed";
    }
}
