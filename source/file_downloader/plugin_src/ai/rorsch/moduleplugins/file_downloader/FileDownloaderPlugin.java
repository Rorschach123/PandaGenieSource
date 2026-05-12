package ai.rorsch.moduleplugins.file_downloader;

import android.content.Context;

import ai.rorsch.pandagenie.module.runtime.HtmlOutputHelper;
import ai.rorsch.pandagenie.module.runtime.ModulePlugin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FileDownloaderPlugin implements ModulePlugin {
    private static final int CONNECT_TIMEOUT_MS = 12_000;
    private static final int READ_TIMEOUT_MS = 20_000;
    private static final int MAX_HTML_BYTES = 2 * 1024 * 1024;
    private static final int MAX_CANDIDATES = 80;
    private static final long DEFAULT_MAX_BYTES = 100L * 1024L * 1024L;
    private static final String DEFAULT_OUTPUT_DIR = "/sdcard/PandaGenie/downloads";
    private static final String USER_AGENT = "PandaGenie/1.0 Android FileDownloader";

    private static final Set<String> COMMON_DOWNLOAD_EXTS = new HashSet<>(Arrays.asList(
            "apk", "apks", "xapk", "aab",
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "zip", "rar", "7z", "tar", "gz", "tgz", "bz2",
            "txt", "csv", "json", "xml", "md", "log",
            "epub", "mobi",
            "mp3", "m4a", "wav", "flac", "ogg",
            "mp4", "mkv", "mov", "avi", "webm",
            "jpg", "jpeg", "png", "webp", "gif"
    ));
    private static final Set<String> DEFAULT_ANALYZE_EXTS = new HashSet<>(Arrays.asList(
            "apk", "apks", "xapk", "aab",
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "zip", "rar", "7z", "tar", "gz", "tgz",
            "txt", "csv", "json", "xml", "md", "epub",
            "mp3", "m4a", "wav", "flac", "mp4", "mkv", "mov", "avi"
    ));
    private static final Set<String> DEFAULT_KEYWORDS = new HashSet<>(Arrays.asList(
            "download", "downloads", "release", "asset", "file", "attachment",
            "安装包", "下载", "附件", "文件", "文档", "说明书", "压缩包"
    ));

    private static final Pattern TAG_URL_ATTR = Pattern.compile(
            "(?is)\\b(?:href|src|data-href|data-url|data-download|download-url)\\s*=\\s*([\"'])(.*?)\\1"
    );
    private static final Pattern ANCHOR_TAG = Pattern.compile("(?is)<a\\b([^>]*)>(.*?)</a>");
    private static final Pattern TITLE_TAG = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");

    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        try {
            JSONObject params = new JSONObject(emptyJson(paramsJson));
            switch (action) {
                case "analyzeDownloadCandidates":
                    return analyzeDownloadCandidates(params);
                case "downloadFile":
                    return downloadFile(params);
                case "downloadBestCandidate":
                    return downloadBestCandidate(params);
                case "getDownloadInfo":
                    return getDownloadInfo(params);
                case "batchDownload":
                    return batchDownload(params);
                case "openPage":
                    return openPage();
                default:
                    return error("Unsupported action: " + action);
            }
        } catch (Exception e) {
            return error(messageOf(e));
        }
    }

    private String analyzeDownloadCandidates(JSONObject params) throws Exception {
        JSONObject analyzed = analyzeInternal(params, true);
        JSONArray candidates = analyzed.optJSONArray("candidates");
        String display = formatCandidatesDisplay(analyzed);
        return ok(analyzed, display, formatCandidatesHtml(analyzed, candidates));
    }

    private String getDownloadInfo(JSONObject params) throws Exception {
        String url = normalizeUrl(required(params, "url"));
        ProbeResult probe = probe(url);
        JSONObject result = probe.toJson();
        result.put("fileName", guessFileName(probe.finalUrl, probe.contentDisposition));
        result.put("extension", extensionOf(result.optString("fileName", "")));
        result.put("downloadable", isDownloadable(result.optString("fileName", ""), probe.contentType, probe.contentDisposition));
        return ok(result, formatInfoDisplay(result), formatInfoHtml(result));
    }

    private String downloadFile(JSONObject params) throws Exception {
        String url = normalizeUrl(required(params, "url"));
        DownloadResult result = downloadInternal(
                url,
                params.optString("outputDir", DEFAULT_OUTPUT_DIR),
                params.optString("fileName", ""),
                params.optBoolean("overwrite", false),
                parseMaxBytes(params)
        );
        return ok(result.toJson(), formatDownloadDisplay(result.toJson()), formatDownloadHtml(result.toJson()));
    }

    private String downloadBestCandidate(JSONObject params) throws Exception {
        JSONObject analyzed = analyzeInternal(params, true);
        JSONArray candidates = analyzed.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) {
            return error(isZh() ? "没有找到可下载候选链接" : "No downloadable candidates found");
        }

        JSONObject chosen = null;
        for (int i = 0; i < candidates.length(); i++) {
            JSONObject c = candidates.getJSONObject(i);
            if (chosen == null) chosen = c;
            if (c.optBoolean("reachable", false) && c.optBoolean("downloadable", false)) {
                chosen = c;
                break;
            }
        }
        if (chosen == null) return error(isZh() ? "没有可用候选" : "No candidate available");
        int status = chosen.optInt("statusCode", 0);
        if (status >= 400) {
            return error((isZh() ? "最佳候选不可访问，状态码：" : "Best candidate is not reachable, status: ") + status);
        }

        String preferredName = params.optString("fileName", "");
        if (preferredName.indexOf('.') < 0) preferredName = "";
        DownloadResult downloaded = downloadInternal(
                chosen.optString("finalUrl", chosen.optString("url")),
                params.optString("outputDir", DEFAULT_OUTPUT_DIR),
                preferredName,
                params.optBoolean("overwrite", false),
                parseMaxBytes(params)
        );
        JSONObject result = downloaded.toJson();
        result.put("selectedCandidate", chosen);
        result.put("candidateCount", candidates.length());
        return ok(result, formatDownloadDisplay(result), formatDownloadHtml(result));
    }

    private String batchDownload(JSONObject params) throws Exception {
        JSONArray urls = params.optJSONArray("urls");
        if (urls == null || urls.length() == 0) return error("Missing parameter: urls");
        int limit = Math.min(urls.length(), 10);
        JSONArray results = new JSONArray();
        int success = 0;
        for (int i = 0; i < limit; i++) {
            String url = normalizeUrl(urls.optString(i, ""));
            try {
                DownloadResult r = downloadInternal(
                        url,
                        params.optString("outputDir", DEFAULT_OUTPUT_DIR),
                        "",
                        params.optBoolean("overwrite", false),
                        parseMaxBytes(params)
                );
                JSONObject item = r.toJson();
                item.put("success", true);
                results.put(item);
                success++;
            } catch (Exception e) {
                JSONObject item = new JSONObject();
                item.put("success", false);
                item.put("url", url);
                item.put("error", messageOf(e));
                results.put(item);
            }
        }
        JSONObject out = new JSONObject();
        out.put("successCount", success);
        out.put("total", limit);
        out.put("results", results);
        return ok(out, formatBatchDisplay(out), formatBatchHtml(out));
    }

    private JSONObject analyzeInternal(JSONObject params, boolean allowProbe) throws Exception {
        String inputUrl = normalizeUrl(required(params, "url"));
        List<String> extensions = parseStringList(params.opt("extensions"));
        boolean hasExplicitExt = !extensions.isEmpty();
        if (extensions.isEmpty()) extensions = new ArrayList<>(DEFAULT_ANALYZE_EXTS);
        List<String> keywords = parseStringList(params.opt("keywords"));
        Set<String> keywordSet = new HashSet<>(DEFAULT_KEYWORDS);
        for (String k : keywords) keywordSet.add(k.toLowerCase(Locale.ROOT));
        int maxCandidates = Math.max(1, Math.min(params.optInt("maxCandidates", 20), MAX_CANDIDATES));
        boolean probeLinks = allowProbe && params.optBoolean("probe", true);
        String fileName = params.optString("fileName", "").trim();

        LinkedHashMap<String, JSONObject> candidates = new LinkedHashMap<>();
        ProbeResult inputProbe = probe(inputUrl);
        addCandidate(candidates, inputProbe.finalUrl, "input", "", false, extensions, keywordSet, hasExplicitExt);

        String baseUrl = inputProbe.finalUrl;
        boolean isHtml = inputProbe.contentType != null && inputProbe.contentType.toLowerCase(Locale.ROOT).contains("text/html");
        String pageTitle = "";
        int extractedLinkCount = 0;
        if (isHtml && inputProbe.statusCode >= 200 && inputProbe.statusCode < 400) {
            String html = fetchText(inputProbe.finalUrl, MAX_HTML_BYTES);
            pageTitle = extractTitle(html);
            extractedLinkCount = extractLinksFromHtml(html, baseUrl, candidates, extensions, keywordSet, hasExplicitExt);
        }
        constructLikelyUrls(baseUrl, fileName, extensions, keywords, candidates, keywordSet);

        JSONArray arr = new JSONArray();
        for (JSONObject c : candidates.values()) {
            if (probeLinks) {
                try {
                    ProbeResult p = probe(c.optString("url"));
                    c.put("finalUrl", p.finalUrl);
                    c.put("statusCode", p.statusCode);
                    c.put("contentType", nullToEmpty(p.contentType));
                    c.put("fileSize", p.contentLength);
                    c.put("reachable", p.statusCode >= 200 && p.statusCode < 400);
                    String finalName = guessFileName(p.finalUrl, p.contentDisposition);
                    if (!finalName.isEmpty()) {
                        c.put("fileName", finalName);
                        c.put("extension", extensionOf(finalName));
                    }
                    boolean downloadable = isDownloadable(c.optString("fileName", ""), p.contentType, p.contentDisposition);
                    c.put("downloadable", downloadable);
                    c.put("score", c.optInt("score", 0) + scoreProbe(p, downloadable));
                } catch (Exception e) {
                    c.put("reachable", false);
                    c.put("probeError", messageOf(e));
                }
            }
            arr.put(c);
        }
        arr = sortAndLimit(arr, maxCandidates);

        JSONObject out = new JSONObject();
        out.put("url", inputUrl);
        out.put("finalUrl", inputProbe.finalUrl);
        out.put("statusCode", inputProbe.statusCode);
        out.put("contentType", nullToEmpty(inputProbe.contentType));
        out.put("pageTitle", pageTitle);
        out.put("extractedLinkCount", extractedLinkCount);
        out.put("constructed", countConstructed(arr));
        out.put("count", arr.length());
        out.put("candidates", arr);
        out.put("nextStep", isZh()
                ? "如果用户已明确要下载，选择 score 最高且 reachable=true 的候选调用 downloadFile；候选不唯一时先让用户确认。"
                : "If the user clearly wants a download, call downloadFile with the highest-score reachable candidate; ask for confirmation when candidates are ambiguous.");
        return out;
    }

    private int extractLinksFromHtml(
            String html,
            String baseUrl,
            LinkedHashMap<String, JSONObject> candidates,
            List<String> extensions,
            Set<String> keywords,
            boolean hasExplicitExt
    ) throws Exception {
        int count = 0;
        Matcher anchors = ANCHOR_TAG.matcher(html);
        while (anchors.find()) {
            String attrs = anchors.group(1);
            String text = cleanText(stripTags(anchors.group(2)));
            Matcher attr = TAG_URL_ATTR.matcher(attrs);
            while (attr.find()) {
                String raw = htmlDecode(attr.group(2));
                String resolved = resolveUrl(baseUrl, raw);
                count++;
                addCandidate(candidates, resolved, "html_link", text, false, extensions, keywords, hasExplicitExt);
            }
        }
        Matcher attrs = TAG_URL_ATTR.matcher(html);
        while (attrs.find()) {
            String raw = htmlDecode(attrs.group(2));
            String resolved = resolveUrl(baseUrl, raw);
            count++;
            addCandidate(candidates, resolved, "html_attr", "", false, extensions, keywords, hasExplicitExt);
        }
        return count;
    }

    private void constructLikelyUrls(
            String baseUrl,
            String fileName,
            List<String> extensions,
            List<String> keywords,
            LinkedHashMap<String, JSONObject> candidates,
            Set<String> keywordSet
    ) throws Exception {
        String baseDir = baseDirectory(baseUrl);
        if (!fileName.isEmpty()) {
            String clean = sanitizeRelativeName(fileName);
            if (clean.contains(".")) {
                addCandidate(candidates, resolveUrl(baseDir, clean), "constructed_filename", fileName, true, extensions, keywordSet, true);
            } else {
                for (String ext : extensions) {
                    addCandidate(candidates, resolveUrl(baseDir, clean + "." + ext), "constructed_filename_ext", fileName, true, extensions, keywordSet, true);
                }
            }
        }

        String stem = stemFromUrl(baseUrl);
        if (!stem.isEmpty() && extensionOf(stem).isEmpty()) {
            for (String ext : extensions) {
                addCandidate(candidates, resolveUrl(baseDir, stem + "." + ext), "constructed_url_stem", stem, true, extensions, keywordSet, true);
            }
        }

        for (String k : keywords) {
            String clean = sanitizeRelativeName(k);
            if (clean.length() < 2 || clean.contains("/") || clean.contains("\\")) continue;
            if (clean.contains(".")) {
                addCandidate(candidates, resolveUrl(baseDir, clean), "constructed_keyword", k, true, extensions, keywordSet, true);
            } else {
                for (String ext : extensions) {
                    addCandidate(candidates, resolveUrl(baseDir, clean + "." + ext), "constructed_keyword_ext", k, true, extensions, keywordSet, true);
                }
            }
        }
    }

    private void addCandidate(
            LinkedHashMap<String, JSONObject> map,
            String url,
            String source,
            String text,
            boolean constructed,
            List<String> extensions,
            Set<String> keywords,
            boolean hasExplicitExt
    ) throws Exception {
        if (url == null || url.trim().isEmpty()) return;
        url = normalizeUrl(url);
        if (!isHttpUrl(url)) return;
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.startsWith("javascript:") || lower.startsWith("mailto:") || lower.startsWith("tel:")) return;

        String fileName = guessFileName(url, "");
        String ext = extensionOf(fileName);
        boolean extMatch = !ext.isEmpty() && extensions.contains(ext.toLowerCase(Locale.ROOT));
        boolean keywordMatch = containsKeyword(url + " " + text, keywords);
        boolean include = constructed || extMatch || keywordMatch || (!hasExplicitExt && isKnownDownloadExt(ext));
        if (!include) return;

        JSONObject existing = map.get(url);
        if (existing == null) {
            existing = new JSONObject();
            existing.put("url", url);
            existing.put("finalUrl", url);
            existing.put("fileName", fileName);
            existing.put("extension", ext);
            existing.put("source", source);
            existing.put("text", text);
            existing.put("constructed", constructed);
            existing.put("downloadable", extMatch || isKnownDownloadExt(ext));
            existing.put("score", 0);
            map.put(url, existing);
        }
        int score = existing.optInt("score", 0);
        if (extMatch) score += 35;
        if (isKnownDownloadExt(ext)) score += 20;
        if (keywordMatch) score += 15;
        if (constructed) score += 8;
        if ("input".equals(source)) score += 6;
        if (text != null && text.length() > 0) existing.put("text", text);
        existing.put("score", score);
    }

    private DownloadResult downloadInternal(String url, String outputDir, String fileName, boolean overwrite, long maxBytes) throws Exception {
        if (!isHttpUrl(url)) throw new IllegalArgumentException("Only http/https URLs are supported");
        OpenResult opened = openFollowing(url, "GET", false);
        HttpURLConnection conn = opened.connection;
        int status = conn.getResponseCode();
        if (status < 200 || status >= 400) {
            conn.disconnect();
            throw new IllegalStateException("HTTP " + status + " " + nullToEmpty(conn.getResponseMessage()));
        }
        long contentLength = conn.getContentLengthLong();
        if (maxBytes > 0 && contentLength > maxBytes) {
            conn.disconnect();
            throw new IllegalStateException("File is larger than maxBytes: " + contentLength);
        }
        String finalUrl = opened.finalUrl;
        String safeName = sanitizeFileName(fileName);
        if (safeName.isEmpty()) safeName = sanitizeFileName(guessFileName(finalUrl, conn.getHeaderField("Content-Disposition")));
        if (safeName.isEmpty()) safeName = "download_" + System.currentTimeMillis() + ".bin";

        File dir = new File(outputDir == null || outputDir.trim().isEmpty() ? DEFAULT_OUTPUT_DIR : outputDir.trim());
        if (!dir.exists() && !dir.mkdirs()) {
            conn.disconnect();
            throw new IllegalStateException("Unable to create output directory: " + dir.getAbsolutePath());
        }
        File target = uniqueTargetFile(dir, safeName, overwrite);
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        long written = 0;
        byte[] buffer = new byte[32 * 1024];
        try (InputStream in = new BufferedInputStream(conn.getInputStream());
             BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
            int n;
            while ((n = in.read(buffer)) != -1) {
                written += n;
                if (maxBytes > 0 && written > maxBytes) {
                    out.close();
                    target.delete();
                    throw new IllegalStateException("Download exceeded maxBytes: " + maxBytes);
                }
                sha.update(buffer, 0, n);
                out.write(buffer, 0, n);
            }
        } finally {
            conn.disconnect();
        }
        return new DownloadResult(url, finalUrl, target, written, conn.getContentType(), bytesToHex(sha.digest()), opened.redirectChain);
    }

    private ProbeResult probe(String url) throws Exception {
        try {
            OpenResult opened = openFollowing(url, "HEAD", false);
            HttpURLConnection conn = opened.connection;
            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_BAD_METHOD || code == HttpURLConnection.HTTP_FORBIDDEN) {
                conn.disconnect();
                return probeWithRangeGet(url);
            }
            ProbeResult r = ProbeResult.from(opened, conn);
            conn.disconnect();
            return r;
        } catch (Exception ignored) {
            return probeWithRangeGet(url);
        }
    }

    private ProbeResult probeWithRangeGet(String url) throws Exception {
        OpenResult opened = openFollowing(url, "GET", true);
        HttpURLConnection conn = opened.connection;
        ProbeResult r = ProbeResult.from(opened, conn);
        try {
            InputStream in = conn.getInputStream();
            byte[] tmp = new byte[1];
            in.read(tmp);
            in.close();
        } catch (Exception ignored) {
        } finally {
            conn.disconnect();
        }
        return r;
    }

    private OpenResult openFollowing(String url, String method, boolean rangeProbe) throws Exception {
        String current = normalizeUrl(url);
        List<String> chain = new ArrayList<>();
        chain.add(current);
        for (int i = 0; i < 10; i++) {
            HttpURLConnection conn = openConnection(current);
            conn.setRequestMethod(method);
            conn.setInstanceFollowRedirects(false);
            if (rangeProbe) conn.setRequestProperty("Range", "bytes=0-0");
            conn.connect();
            int code = conn.getResponseCode();
            if (code / 100 == 3) {
                String loc = conn.getHeaderField("Location");
                if (loc == null || loc.trim().isEmpty()) {
                    return new OpenResult(current, chain, conn);
                }
                conn.disconnect();
                current = resolveUrl(current, loc);
                chain.add(current);
                continue;
            }
            return new OpenResult(current, chain, conn);
        }
        throw new IllegalStateException("Too many redirects");
    }

    private HttpURLConnection openConnection(String url) throws Exception {
        URL u = new URL(normalizeUrl(url));
        HttpURLConnection conn = (HttpURLConnection) u.openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Accept", "*/*");
        return conn;
    }

    private String fetchText(String url, int maxBytes) throws Exception {
        OpenResult opened = openFollowing(url, "GET", false);
        HttpURLConnection conn = opened.connection;
        if (conn.getResponseCode() < 200 || conn.getResponseCode() >= 400) {
            conn.disconnect();
            throw new IllegalStateException("HTTP " + conn.getResponseCode());
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        try (InputStream in = new BufferedInputStream(conn.getInputStream())) {
            int n;
            while ((n = in.read(buffer)) != -1 && total < maxBytes) {
                int allowed = Math.min(n, maxBytes - total);
                bos.write(buffer, 0, allowed);
                total += allowed;
            }
        } finally {
            conn.disconnect();
        }
        return bos.toString(StandardCharsets.UTF_8.name());
    }

    private JSONArray sortAndLimit(JSONArray arr, int limit) throws Exception {
        List<JSONObject> list = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) list.add(arr.getJSONObject(i));
        Collections.sort(list, new Comparator<JSONObject>() {
            @Override public int compare(JSONObject a, JSONObject b) {
                int s = Integer.compare(b.optInt("score", 0), a.optInt("score", 0));
                if (s != 0) return s;
                return Boolean.compare(b.optBoolean("reachable", false), a.optBoolean("reachable", false));
            }
        });
        JSONArray out = new JSONArray();
        for (int i = 0; i < Math.min(limit, list.size()); i++) out.put(list.get(i));
        return out;
    }

    private static class OpenResult {
        final String finalUrl;
        final List<String> redirectChain;
        final HttpURLConnection connection;
        OpenResult(String finalUrl, List<String> redirectChain, HttpURLConnection connection) {
            this.finalUrl = finalUrl;
            this.redirectChain = redirectChain;
            this.connection = connection;
        }
    }

    private static class ProbeResult {
        String finalUrl;
        int statusCode;
        String statusMessage;
        String contentType;
        String contentDisposition;
        long contentLength;
        List<String> redirectChain;

        static ProbeResult from(OpenResult opened, HttpURLConnection conn) throws Exception {
            ProbeResult r = new ProbeResult();
            r.finalUrl = opened.finalUrl;
            r.redirectChain = opened.redirectChain;
            r.statusCode = conn.getResponseCode();
            r.statusMessage = conn.getResponseMessage();
            r.contentType = conn.getContentType();
            r.contentDisposition = conn.getHeaderField("Content-Disposition");
            r.contentLength = conn.getContentLengthLong();
            return r;
        }

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("url", redirectChain != null && !redirectChain.isEmpty() ? redirectChain.get(0) : finalUrl);
            o.put("finalUrl", finalUrl);
            o.put("statusCode", statusCode);
            o.put("statusMessage", nullToEmpty(statusMessage));
            o.put("contentType", nullToEmpty(contentType));
            o.put("contentDisposition", nullToEmpty(contentDisposition));
            o.put("fileSize", contentLength);
            if (redirectChain != null && redirectChain.size() > 1) o.put("redirectChain", new JSONArray(redirectChain));
            return o;
        }
    }

    private static class DownloadResult {
        final String url;
        final String finalUrl;
        final File file;
        final long bytes;
        final String contentType;
        final String sha256;
        final List<String> redirectChain;
        DownloadResult(String url, String finalUrl, File file, long bytes, String contentType, String sha256, List<String> redirectChain) {
            this.url = url;
            this.finalUrl = finalUrl;
            this.file = file;
            this.bytes = bytes;
            this.contentType = contentType;
            this.sha256 = sha256;
            this.redirectChain = redirectChain;
        }
        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("url", url);
            o.put("finalUrl", finalUrl);
            o.put("fileName", file.getName());
            o.put("path", file.getAbsolutePath());
            o.put("sizeBytes", bytes);
            o.put("sizeFormatted", formatSize(bytes));
            o.put("contentType", nullToEmpty(contentType));
            o.put("sha256", sha256);
            if (redirectChain != null && redirectChain.size() > 1) o.put("redirectChain", new JSONArray(redirectChain));
            return o;
        }
    }

    private String formatCandidatesDisplay(JSONObject out) throws Exception {
        JSONArray arr = out.optJSONArray("candidates");
        StringBuilder sb = new StringBuilder();
        sb.append(isZh() ? "📥 下载候选分析" : "📥 Download candidate analysis").append("\n");
        sb.append("URL: ").append(out.optString("finalUrl", "")).append("\n");
        sb.append(isZh() ? "候选数量: " : "Candidates: ").append(arr == null ? 0 : arr.length()).append("\n\n");
        if (arr != null) {
            for (int i = 0; i < Math.min(arr.length(), 8); i++) {
                JSONObject c = arr.getJSONObject(i);
                sb.append(i + 1).append(". ").append(c.optString("fileName", "download"));
                if (c.optBoolean("reachable", false)) sb.append(" ✓");
                sb.append("  score=").append(c.optInt("score", 0)).append("\n");
                sb.append("   ").append(c.optString("finalUrl", c.optString("url"))).append("\n");
                long size = c.optLong("fileSize", -1);
                if (size > 0) sb.append("   ").append(formatSize(size)).append(" · ").append(c.optString("contentType", "")).append("\n");
            }
        }
        sb.append("\n").append(out.optString("nextStep", ""));
        return sb.toString().trim();
    }

    private String formatDownloadDisplay(JSONObject out) {
        StringBuilder sb = new StringBuilder();
        sb.append(isZh() ? "✅ 下载完成" : "✅ Download complete").append("\n");
        sb.append(isZh() ? "文件: " : "File: ").append(out.optString("fileName", "")).append("\n");
        sb.append(isZh() ? "大小: " : "Size: ").append(out.optString("sizeFormatted", "")).append("\n");
        sb.append(isZh() ? "路径: " : "Path: ").append(out.optString("path", "")).append("\n");
        sb.append("SHA-256: ").append(out.optString("sha256", ""));
        return sb.toString();
    }

    private String formatInfoDisplay(JSONObject out) {
        return (isZh() ? "🔎 链接信息\n" : "🔎 Link info\n")
                + "URL: " + out.optString("finalUrl", "") + "\n"
                + "HTTP: " + out.optInt("statusCode", 0) + "\n"
                + "Type: " + out.optString("contentType", "") + "\n"
                + "Size: " + formatSize(out.optLong("fileSize", -1)) + "\n"
                + "File: " + out.optString("fileName", "");
    }

    private String formatBatchDisplay(JSONObject out) throws Exception {
        return (isZh() ? "批量下载完成: " : "Batch download complete: ")
                + out.optInt("successCount", 0) + "/" + out.optInt("total", 0);
    }

    private String formatCandidatesHtml(JSONObject out, JSONArray arr) throws Exception {
        List<String[]> rows = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < Math.min(arr.length(), 10); i++) {
                JSONObject c = arr.getJSONObject(i);
                rows.add(new String[]{
                        c.optString("fileName", "download"),
                        c.optBoolean("reachable", false) ? "OK" : "-",
                        c.optString("extension", ""),
                        formatSize(c.optLong("fileSize", -1))
                });
            }
        }
        String body = HtmlOutputHelper.metricGrid(new String[][]{
                {String.valueOf(out.optInt("count", 0)), isZh() ? "候选" : "Candidates"},
                {String.valueOf(out.optInt("constructed", 0)), isZh() ? "构造" : "Constructed"},
                {String.valueOf(out.optInt("statusCode", 0)), "HTTP"}
        });
        body += HtmlOutputHelper.keyValue(new String[][]{{"URL", out.optString("finalUrl", "")}});
        if (!rows.isEmpty()) {
            body += HtmlOutputHelper.table(
                    new String[]{isZh() ? "文件" : "File", isZh() ? "可访问" : "Reachable", "Ext", isZh() ? "大小" : "Size"},
                    rows
            );
        }
        body += HtmlOutputHelper.muted(out.optString("nextStep", ""));
        return HtmlOutputHelper.card("📥", isZh() ? "下载候选" : "Download candidates", body);
    }

    private String formatDownloadHtml(JSONObject out) {
        String body = HtmlOutputHelper.successBadge();
        body += HtmlOutputHelper.keyValue(new String[][]{
                {isZh() ? "文件" : "File", out.optString("fileName", "")},
                {isZh() ? "大小" : "Size", out.optString("sizeFormatted", "")},
                {isZh() ? "路径" : "Path", out.optString("path", "")},
                {"SHA-256", out.optString("sha256", "")}
        });
        return HtmlOutputHelper.card("✅", isZh() ? "下载完成" : "Download complete", body);
    }

    private String formatInfoHtml(JSONObject out) {
        String body = HtmlOutputHelper.keyValue(new String[][]{
                {"HTTP", String.valueOf(out.optInt("statusCode", 0))},
                {"Content-Type", out.optString("contentType", "")},
                {isZh() ? "文件名" : "File", out.optString("fileName", "")},
                {isZh() ? "大小" : "Size", formatSize(out.optLong("fileSize", -1))},
                {"URL", out.optString("finalUrl", "")}
        });
        return HtmlOutputHelper.card("🔎", isZh() ? "链接信息" : "Link info", body);
    }

    private String formatBatchHtml(JSONObject out) throws Exception {
        JSONArray arr = out.optJSONArray("results");
        List<String[]> rows = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject r = arr.getJSONObject(i);
                rows.add(new String[]{
                        r.optBoolean("success", false) ? "OK" : "FAIL",
                        r.optString("fileName", r.optString("url", "")),
                        r.optString("sizeFormatted", r.optString("error", ""))
                });
            }
        }
        String body = HtmlOutputHelper.metricGrid(new String[][]{
                {out.optInt("successCount", 0) + "/" + out.optInt("total", 0), isZh() ? "成功" : "Success"}
        }) + HtmlOutputHelper.table(new String[]{"Status", isZh() ? "文件" : "File", isZh() ? "结果" : "Result"}, rows);
        return HtmlOutputHelper.card("📦", isZh() ? "批量下载" : "Batch download", body);
    }

    private String openPage() throws Exception {
        JSONObject r = new JSONObject();
        r.put("success", true);
        r.put("output", "{}");
        r.put("_openModule", true);
        r.put("_displayText", isZh() ? "正在打开文件下载器..." : "Opening File Downloader...");
        return r.toString();
    }

    private static long parseMaxBytes(JSONObject params) {
        if (!params.has("maxBytes")) return DEFAULT_MAX_BYTES;
        long v = params.optLong("maxBytes", DEFAULT_MAX_BYTES);
        if (v == 0) return 0;
        if (v < 0) return DEFAULT_MAX_BYTES;
        return v;
    }

    private static List<String> parseStringList(Object value) throws Exception {
        List<String> out = new ArrayList<>();
        if (value == null || value == JSONObject.NULL) return out;
        if (value instanceof JSONArray) {
            JSONArray arr = (JSONArray) value;
            for (int i = 0; i < arr.length(); i++) addListItem(out, arr.optString(i, ""));
            return out;
        }
        String s = String.valueOf(value).trim();
        if (s.isEmpty()) return out;
        for (String part : s.split("[,，\\s]+")) addListItem(out, part);
        return out;
    }

    private static void addListItem(List<String> out, String item) {
        item = item == null ? "" : item.trim().toLowerCase(Locale.ROOT);
        if (item.startsWith(".")) item = item.substring(1);
        if (!item.isEmpty() && !out.contains(item)) out.add(item);
    }

    private static boolean containsKeyword(String text, Set<String> keywords) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        for (String k : keywords) {
            if (!k.isEmpty() && lower.contains(k.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static int scoreProbe(ProbeResult p, boolean downloadable) {
        int s = 0;
        if (p.statusCode >= 200 && p.statusCode < 400) s += 40;
        if (downloadable) s += 35;
        if (p.contentLength > 0) s += 8;
        if (p.contentDisposition != null && p.contentDisposition.toLowerCase(Locale.ROOT).contains("attachment")) s += 20;
        return s;
    }

    private static int countConstructed(JSONArray arr) throws Exception {
        int count = 0;
        for (int i = 0; i < arr.length(); i++) if (arr.getJSONObject(i).optBoolean("constructed", false)) count++;
        return count;
    }

    private static boolean isDownloadable(String fileName, String contentType, String disposition) {
        String ext = extensionOf(fileName);
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        String cd = disposition == null ? "" : disposition.toLowerCase(Locale.ROOT);
        return isKnownDownloadExt(ext)
                || cd.contains("attachment")
                || ct.contains("application/octet-stream")
                || ct.contains("application/pdf")
                || ct.contains("application/zip")
                || ct.contains("application/vnd.")
                || ct.startsWith("audio/")
                || ct.startsWith("video/");
    }

    private static boolean isKnownDownloadExt(String ext) {
        return ext != null && COMMON_DOWNLOAD_EXTS.contains(ext.toLowerCase(Locale.ROOT));
    }

    private static String guessFileName(String url, String disposition) {
        String byDisposition = fileNameFromDisposition(disposition);
        if (!byDisposition.isEmpty()) return byDisposition;
        try {
            String path = new URL(url).getPath();
            int slash = path.lastIndexOf('/');
            String name = slash >= 0 ? path.substring(slash + 1) : path;
            name = URLDecoder.decode(name, "UTF-8");
            if (!name.trim().isEmpty()) return sanitizeFileName(name);
        } catch (Exception ignored) {
        }
        return "download.bin";
    }

    private static String fileNameFromDisposition(String disposition) {
        if (disposition == null) return "";
        Matcher star = Pattern.compile("(?i)filename\\*\\s*=\\s*UTF-8''([^;]+)").matcher(disposition);
        if (star.find()) {
            try {
                return sanitizeFileName(URLDecoder.decode(star.group(1).trim(), "UTF-8"));
            } catch (Exception ignored) {}
        }
        Matcher normal = Pattern.compile("(?i)filename\\s*=\\s*\"?([^\";]+)\"?").matcher(disposition);
        if (normal.find()) return sanitizeFileName(normal.group(1).trim());
        return "";
    }

    private static String extensionOf(String fileNameOrUrl) {
        if (fileNameOrUrl == null) return "";
        String s = fileNameOrUrl;
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        int h = s.indexOf('#');
        if (h >= 0) s = s.substring(0, h);
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (slash >= 0) s = s.substring(slash + 1);
        int dot = s.lastIndexOf('.');
        if (dot < 0 || dot == s.length() - 1) return "";
        String ext = s.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ext.length() > 12 ? "" : ext;
    }

    private static String baseDirectory(String url) throws Exception {
        URL u = new URL(url);
        String s = u.toString();
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        int h = s.indexOf('#');
        if (h >= 0) s = s.substring(0, h);
        int slash = s.lastIndexOf('/');
        if (slash < "https://x/".length()) return s + "/";
        return s.substring(0, slash + 1);
    }

    private static String stemFromUrl(String url) {
        try {
            String path = new URL(url).getPath();
            if (path == null || path.endsWith("/")) return "";
            String name = path.substring(path.lastIndexOf('/') + 1);
            name = URLDecoder.decode(name, "UTF-8");
            int dot = name.lastIndexOf('.');
            if (dot > 0) name = name.substring(0, dot);
            return sanitizeRelativeName(name);
        } catch (Exception e) {
            return "";
        }
    }

    private static File uniqueTargetFile(File dir, String fileName, boolean overwrite) {
        File target = new File(dir, fileName);
        if (overwrite || !target.exists()) return target;
        String base = fileName;
        String ext = "";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            base = fileName.substring(0, dot);
            ext = fileName.substring(dot);
        }
        for (int i = 1; i < 1000; i++) {
            File f = new File(dir, base + " (" + i + ")" + ext);
            if (!f.exists()) return f;
        }
        return new File(dir, base + "_" + System.currentTimeMillis() + ext);
    }

    private static String sanitizeFileName(String name) {
        if (name == null) return "";
        name = name.trim();
        name = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        while (name.contains("..")) name = name.replace("..", ".");
        if (name.length() > 160) name = name.substring(0, 160);
        return name;
    }

    private static String sanitizeRelativeName(String name) {
        name = sanitizeFileName(name);
        name = name.replace(" ", "%20");
        return name;
    }

    private static String normalizeUrl(String url) {
        url = url == null ? "" : url.trim();
        if (url.isEmpty()) return "";
        if (!url.matches("(?i)^https?://.*")) url = "https://" + url;
        return url;
    }

    private static boolean isHttpUrl(String url) {
        return url != null && url.matches("(?i)^https?://.+");
    }

    private static String resolveUrl(String base, String href) throws Exception {
        href = href == null ? "" : href.trim();
        if (href.isEmpty()) return "";
        if (href.startsWith("//")) {
            URL b = new URL(base);
            return b.getProtocol() + ":" + href;
        }
        return new URL(new URL(base), href).toString();
    }

    private static String extractTitle(String html) {
        Matcher m = TITLE_TAG.matcher(html == null ? "" : html);
        if (!m.find()) return "";
        return cleanText(stripTags(m.group(1)));
    }

    private static String stripTags(String html) {
        return html == null ? "" : html.replaceAll("(?is)<script.*?</script>", " ")
                .replaceAll("(?is)<style.*?</style>", " ")
                .replaceAll("(?is)<[^>]+>", " ");
    }

    private static String cleanText(String text) {
        return htmlDecode(text).replaceAll("\\s+", " ").trim();
    }

    private static String htmlDecode(String s) {
        if (s == null) return "";
        return s.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ");
    }

    private static String required(JSONObject params, String key) {
        String v = params.optString(key, "").trim();
        if (v.isEmpty()) throw new IllegalArgumentException("Missing parameter: " + key);
        return v;
    }

    private static String emptyJson(String s) {
        return s == null || s.trim().isEmpty() ? "{}" : s;
    }

    private static boolean isZh() {
        try {
            return Locale.getDefault().getLanguage().toLowerCase(Locale.ROOT).startsWith("zh");
        } catch (Exception e) {
            return false;
        }
    }

    private static String formatSize(long bytes) {
        if (bytes < 0) return "-";
        if (bytes < 1024) return bytes + " B";
        double v = bytes;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int idx = 0;
        while (v >= 1024 && idx < units.length - 1) {
            v /= 1024;
            idx++;
        }
        return String.format(Locale.US, "%.1f %s", v, units[idx]);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format(Locale.US, "%02x", b & 0xff));
        return sb.toString().toUpperCase(Locale.ROOT);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String messageOf(Throwable e) {
        String msg = e.getMessage();
        return msg == null || msg.trim().isEmpty() ? e.getClass().getSimpleName() : msg;
    }

    private static String ok(JSONObject output, String displayText, String displayHtml) {
        try {
            JSONObject j = new JSONObject();
            j.put("success", true);
            j.put("output", output.toString());
            j.put("_displayText", displayText);
            if (displayHtml != null && !displayHtml.isEmpty()) j.put("_displayHtml", displayHtml);
            return j.toString();
        } catch (Exception e) {
            return "{\"success\":true,\"output\":\"{}\"}";
        }
    }

    private static String error(String msg) {
        try {
            JSONObject j = new JSONObject();
            j.put("success", false);
            j.put("error", msg);
            j.put("_displayText", "❌ " + msg);
            return j.toString();
        } catch (Exception e) {
            return "{\"success\":false,\"error\":\"" + msg.replace("\"", "'") + "\"}";
        }
    }
}
