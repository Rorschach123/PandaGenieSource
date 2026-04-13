package ai.rorsch.moduleplugins.link_parser;

import android.content.Context;
import ai.rorsch.pandagenie.module.runtime.ModulePlugin;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLPeerUnverifiedException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;

public class LinkParserPlugin implements ModulePlugin {

    private static final int CONNECT_TIMEOUT = 10_000;
    private static final int READ_TIMEOUT = 15_000;
    private static final int MAX_BODY_SIZE = 2 * 1024 * 1024; // 2 MB
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    private static final Set<String> DOWNLOAD_EXTS = new HashSet<>(Arrays.asList(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "zip", "rar", "7z", "gz", "tar",
        "apk", "exe", "dmg", "iso", "mp3", "mp4", "avi", "mkv", "mov", "flac", "wav",
        "csv", "txt", "json", "xml", "epub", "mobi"
    ));

    private static final Set<String> IMAGE_EXTS = new HashSet<>(Arrays.asList(
        "jpg", "jpeg", "png", "gif", "webp", "svg", "bmp", "ico", "tiff"
    ));

    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        JSONObject params = (paramsJson != null && !paramsJson.isEmpty())
                ? new JSONObject(paramsJson) : new JSONObject();

        switch (action) {
            case "parseUrl":       return parseUrl(params);
            case "getHeaders":     return getHeaders(params);
            case "extractImages":  return extractImages(params);
            case "extractLinks":   return extractLinks(params);
            case "extractDownloads": return extractDownloads(params);
            case "extractText":    return extractText(params);
            case "checkUrl":       return checkUrl(params);
            case "extractMeta":    return extractMeta(params);
            default:
                return error("Unknown action: " + action);
        }
    }

    // ── parseUrl: full page analysis ──

    private String parseUrl(JSONObject params) {
        String url = params.optString("url", "").trim();
        if (url.isEmpty()) return error("Missing parameter: url");
        url = normalizeUrl(url);

        try {
            FetchResult fetch = fetchUrl(url, true);
            JSONObject result = new JSONObject();
            result.put("url", fetch.finalUrl);
            result.put("statusCode", fetch.statusCode);
            result.put("contentType", fetch.contentType);
            result.put("contentLength", fetch.contentLength);
            result.put("responseTimeMs", fetch.responseTimeMs);

            if (fetch.redirectChain.size() > 1) {
                result.put("redirectChain", new JSONArray(fetch.redirectChain));
            }

            if (fetch.isHtml()) {
                String html = fetch.body;
                JSONObject meta = extractMetaFromHtml(html, fetch.finalUrl);
                result.put("title", meta.optString("title", ""));
                result.put("description", meta.optString("description", ""));
                result.put("favicon", meta.optString("favicon", ""));

                JSONObject og = meta.optJSONObject("openGraph");
                if (og != null && og.length() > 0) result.put("openGraph", og);

                JSONArray images = extractImagesFromHtml(html, fetch.finalUrl);
                result.put("images", images);
                result.put("imageCount", images.length());

                JSONObject links = extractLinksFromHtml(html, fetch.finalUrl);
                result.put("internalLinks", links.optJSONArray("internal"));
                result.put("externalLinks", links.optJSONArray("external"));

                JSONArray downloads = extractDownloadsFromHtml(html, fetch.finalUrl);
                result.put("downloads", downloads);

                JSONArray feeds = extractFeeds(html, fetch.finalUrl);
                if (feeds.length() > 0) result.put("feeds", feeds);

                String text = extractPlainText(html, 2000);
                result.put("textPreview", text);

            } else if (fetch.isImage()) {
                result.put("type", "image");
                result.put("imageUrl", fetch.finalUrl);
            } else if (fetch.isDownloadable()) {
                result.put("type", "downloadable_file");
                result.put("fileName", guessFileName(fetch.finalUrl, fetch.headers));
                result.put("fileSize", fetch.contentLength);
            } else {
                result.put("type", fetch.contentType);
                if (fetch.body != null && !fetch.body.isEmpty()) {
                    String preview = fetch.body.length() > 2000 ? fetch.body.substring(0, 2000) + "..." : fetch.body;
                    result.put("textPreview", preview);
                }
            }

            String display = buildParseDisplay(result);
            return ok(result.toString(), display);

        } catch (Exception e) {
            return error("Failed to parse URL: " + e.getMessage());
        }
    }

    // ── getHeaders ──

    private String getHeaders(JSONObject params) {
        String url = params.optString("url", "").trim();
        if (url.isEmpty()) return error("Missing parameter: url");
        url = normalizeUrl(url);

        try {
            FetchResult fetch = fetchUrl(url, false);
            JSONObject result = new JSONObject();
            result.put("url", fetch.finalUrl);
            result.put("statusCode", fetch.statusCode);
            result.put("responseTimeMs", fetch.responseTimeMs);

            JSONObject headersJson = new JSONObject();
            for (Map.Entry<String, List<String>> entry : fetch.headers.entrySet()) {
                if (entry.getKey() != null) {
                    headersJson.put(entry.getKey(), entry.getValue().size() == 1
                            ? entry.getValue().get(0) : new JSONArray(entry.getValue()));
                }
            }
            result.put("headers", headersJson);

            if (fetch.redirectChain.size() > 1) {
                result.put("redirectChain", new JSONArray(fetch.redirectChain));
            }

            StringBuilder sb = new StringBuilder();
            sb.append("🔗 ").append(fetch.finalUrl).append("\n");
            sb.append("📊 Status: ").append(fetch.statusCode).append(" | ⏱ ").append(fetch.responseTimeMs).append("ms\n\n");
            for (Map.Entry<String, List<String>> entry : fetch.headers.entrySet()) {
                if (entry.getKey() != null) {
                    sb.append(entry.getKey()).append(": ").append(String.join(", ", entry.getValue())).append("\n");
                }
            }
            return ok(result.toString(), sb.toString().trim());

        } catch (Exception e) {
            return error("Failed to get headers: " + e.getMessage());
        }
    }

    // ── extractImages ──

    private String extractImages(JSONObject params) {
        String url = params.optString("url", "").trim();
        if (url.isEmpty()) return error("Missing parameter: url");
        url = normalizeUrl(url);

        try {
            FetchResult fetch = fetchUrl(url, true);
            if (!fetch.isHtml()) return error("URL is not an HTML page (Content-Type: " + fetch.contentType + ")");

            JSONArray images = extractImagesFromHtml(fetch.body, fetch.finalUrl);
            JSONObject result = new JSONObject();
            result.put("url", fetch.finalUrl);
            result.put("images", images);
            result.put("count", images.length());

            StringBuilder sb = new StringBuilder();
            sb.append("🖼 Found ").append(images.length()).append(" images\n\n");
            int limit = Math.min(images.length(), 20);
            for (int i = 0; i < limit; i++) {
                JSONObject img = images.getJSONObject(i);
                sb.append(i + 1).append(". ");
                String alt = img.optString("alt", "");
                if (!alt.isEmpty()) sb.append("[").append(alt).append("] ");
                sb.append(img.getString("src")).append("\n");
            }
            if (images.length() > 20) sb.append("... and ").append(images.length() - 20).append(" more");

            return ok(result.toString(), sb.toString().trim());

        } catch (Exception e) {
            return error("Failed to extract images: " + e.getMessage());
        }
    }

    // ── extractLinks ──

    private String extractLinks(JSONObject params) {
        String url = params.optString("url", "").trim();
        if (url.isEmpty()) return error("Missing parameter: url");
        url = normalizeUrl(url);

        try {
            FetchResult fetch = fetchUrl(url, true);
            if (!fetch.isHtml()) return error("URL is not an HTML page");

            JSONObject links = extractLinksFromHtml(fetch.body, fetch.finalUrl);
            JSONObject result = new JSONObject();
            result.put("url", fetch.finalUrl);
            result.put("internal", links.optJSONArray("internal"));
            result.put("external", links.optJSONArray("external"));
            result.put("internalCount", links.optJSONArray("internal").length());
            result.put("externalCount", links.optJSONArray("external").length());

            JSONArray internal = links.optJSONArray("internal");
            JSONArray external = links.optJSONArray("external");
            StringBuilder sb = new StringBuilder();
            sb.append("🔗 Internal links: ").append(internal.length()).append(" | External: ").append(external.length()).append("\n\n");

            if (internal.length() > 0) {
                sb.append("📌 Internal:\n");
                int limit = Math.min(internal.length(), 15);
                for (int i = 0; i < limit; i++) {
                    JSONObject l = internal.getJSONObject(i);
                    sb.append("  ").append(l.optString("text", "")).append(" → ").append(l.getString("href")).append("\n");
                }
                if (internal.length() > 15) sb.append("  ... +").append(internal.length() - 15).append(" more\n");
            }
            if (external.length() > 0) {
                sb.append("\n🌐 External:\n");
                int limit = Math.min(external.length(), 15);
                for (int i = 0; i < limit; i++) {
                    JSONObject l = external.getJSONObject(i);
                    sb.append("  ").append(l.optString("text", "")).append(" → ").append(l.getString("href")).append("\n");
                }
                if (external.length() > 15) sb.append("  ... +").append(external.length() - 15).append(" more\n");
            }

            return ok(result.toString(), sb.toString().trim());

        } catch (Exception e) {
            return error("Failed to extract links: " + e.getMessage());
        }
    }

    // ── extractDownloads ──

    private String extractDownloads(JSONObject params) {
        String url = params.optString("url", "").trim();
        if (url.isEmpty()) return error("Missing parameter: url");
        url = normalizeUrl(url);

        try {
            FetchResult fetch = fetchUrl(url, true);
            if (!fetch.isHtml()) {
                if (fetch.isDownloadable()) {
                    JSONArray arr = new JSONArray();
                    JSONObject file = new JSONObject();
                    file.put("url", fetch.finalUrl);
                    file.put("fileName", guessFileName(fetch.finalUrl, fetch.headers));
                    file.put("fileSize", fetch.contentLength);
                    file.put("contentType", fetch.contentType);
                    arr.put(file);
                    return ok(new JSONObject().put("downloads", arr).put("count", 1).toString(),
                            "📥 Direct download: " + file.getString("fileName") + " (" + formatSize(fetch.contentLength) + ")");
                }
                return error("URL is not an HTML page and not a downloadable file");
            }

            JSONArray downloads = extractDownloadsFromHtml(fetch.body, fetch.finalUrl);
            JSONObject result = new JSONObject();
            result.put("url", fetch.finalUrl);
            result.put("downloads", downloads);
            result.put("count", downloads.length());

            StringBuilder sb = new StringBuilder();
            sb.append("📥 Found ").append(downloads.length()).append(" downloadable files\n\n");
            for (int i = 0; i < downloads.length(); i++) {
                JSONObject d = downloads.getJSONObject(i);
                sb.append(i + 1).append(". ").append(d.optString("fileName", "unknown"));
                String ext = d.optString("extension", "");
                if (!ext.isEmpty()) sb.append(" [").append(ext.toUpperCase()).append("]");
                sb.append("\n   ").append(d.getString("url")).append("\n");
            }
            if (downloads.length() == 0) sb.append("No downloadable files detected on this page.");

            return ok(result.toString(), sb.toString().trim());

        } catch (Exception e) {
            return error("Failed to extract downloads: " + e.getMessage());
        }
    }

    // ── extractText ──

    private String extractText(JSONObject params) {
        String url = params.optString("url", "").trim();
        if (url.isEmpty()) return error("Missing parameter: url");
        url = normalizeUrl(url);
        int maxLen = params.optInt("maxLength", 5000);
        if (maxLen <= 0) maxLen = 5000;

        try {
            FetchResult fetch = fetchUrl(url, true);
            if (!fetch.isHtml()) return error("URL is not an HTML page");

            String text = extractPlainText(fetch.body, maxLen);
            JSONObject result = new JSONObject();
            result.put("url", fetch.finalUrl);
            result.put("text", text);
            result.put("length", text.length());

            return ok(result.toString(), "📄 Extracted " + text.length() + " chars\n\n" + text);

        } catch (Exception e) {
            return error("Failed to extract text: " + e.getMessage());
        }
    }

    // ── checkUrl ──

    private String checkUrl(JSONObject params) {
        String url = params.optString("url", "").trim();
        if (url.isEmpty()) return error("Missing parameter: url");
        url = normalizeUrl(url);

        try {
            long start = System.currentTimeMillis();
            HttpURLConnection conn = openConnection(url);
            conn.setRequestMethod("HEAD");
            conn.setInstanceFollowRedirects(false);
            conn.connect();

            JSONObject result = new JSONObject();
            result.put("url", url);
            result.put("statusCode", conn.getResponseCode());
            result.put("statusMessage", conn.getResponseMessage());
            result.put("responseTimeMs", System.currentTimeMillis() - start);
            result.put("contentType", conn.getContentType());
            result.put("contentLength", conn.getContentLengthLong());
            result.put("server", conn.getHeaderField("Server"));

            // Redirect chain
            List<String> chain = new ArrayList<>();
            chain.add(url);
            HttpURLConnection c = conn;
            int maxRedirects = 10;
            while (c.getResponseCode() / 100 == 3 && maxRedirects-- > 0) {
                String loc = c.getHeaderField("Location");
                if (loc == null) break;
                loc = resolveUrl(chain.get(chain.size() - 1), loc);
                chain.add(loc);
                c.disconnect();
                c = openConnection(loc);
                c.setRequestMethod("HEAD");
                c.setInstanceFollowRedirects(false);
                c.connect();
            }
            result.put("finalUrl", chain.get(chain.size() - 1));
            result.put("finalStatusCode", c.getResponseCode());
            if (chain.size() > 1) result.put("redirectChain", new JSONArray(chain));

            // SSL info
            if (c instanceof HttpsURLConnection) {
                try {
                    Certificate[] certs = ((HttpsURLConnection) c).getServerCertificates();
                    if (certs.length > 0 && certs[0] instanceof X509Certificate) {
                        X509Certificate x509 = (X509Certificate) certs[0];
                        JSONObject ssl = new JSONObject();
                        ssl.put("subject", x509.getSubjectDN().getName());
                        ssl.put("issuer", x509.getIssuerDN().getName());
                        ssl.put("validFrom", x509.getNotBefore().toString());
                        ssl.put("validUntil", x509.getNotAfter().toString());
                        ssl.put("serialNumber", x509.getSerialNumber().toString(16));
                        result.put("ssl", ssl);
                    }
                } catch (SSLPeerUnverifiedException ignored) {}
            }
            c.disconnect();

            StringBuilder sb = new StringBuilder();
            sb.append("🔍 URL Check: ").append(url).append("\n");
            sb.append("📊 Status: ").append(result.getInt("finalStatusCode"));
            sb.append(" | ⏱ ").append(result.getLong("responseTimeMs")).append("ms\n");
            if (chain.size() > 1) {
                sb.append("↪ Redirects: ").append(chain.size() - 1).append(" (→ ").append(chain.get(chain.size() - 1)).append(")\n");
            }
            if (result.has("ssl")) {
                JSONObject ssl = result.getJSONObject("ssl");
                sb.append("🔒 SSL: ").append(ssl.optString("subject", "")).append("\n");
                sb.append("   Valid: ").append(ssl.optString("validFrom", "")).append(" ~ ").append(ssl.optString("validUntil", "")).append("\n");
            }
            return ok(result.toString(), sb.toString().trim());

        } catch (Exception e) {
            return error("URL check failed: " + e.getMessage());
        }
    }

    // ── extractMeta ──

    private String extractMeta(JSONObject params) {
        String url = params.optString("url", "").trim();
        if (url.isEmpty()) return error("Missing parameter: url");
        url = normalizeUrl(url);

        try {
            FetchResult fetch = fetchUrl(url, true);
            if (!fetch.isHtml()) return error("URL is not an HTML page");

            JSONObject meta = extractMetaFromHtml(fetch.body, fetch.finalUrl);
            JSONArray feeds = extractFeeds(fetch.body, fetch.finalUrl);
            if (feeds.length() > 0) meta.put("feeds", feeds);
            meta.put("url", fetch.finalUrl);

            StringBuilder sb = new StringBuilder();
            sb.append("📋 Metadata for: ").append(fetch.finalUrl).append("\n\n");
            sb.append("📌 Title: ").append(meta.optString("title", "N/A")).append("\n");
            sb.append("📝 Description: ").append(meta.optString("description", "N/A")).append("\n");
            String favicon = meta.optString("favicon", "");
            if (!favicon.isEmpty()) sb.append("🔖 Favicon: ").append(favicon).append("\n");
            String canonical = meta.optString("canonical", "");
            if (!canonical.isEmpty()) sb.append("🔗 Canonical: ").append(canonical).append("\n");
            String lang = meta.optString("language", "");
            if (!lang.isEmpty()) sb.append("🌐 Language: ").append(lang).append("\n");

            JSONObject og = meta.optJSONObject("openGraph");
            if (og != null && og.length() > 0) {
                sb.append("\n── Open Graph ──\n");
                Iterator<String> keys = og.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    sb.append("  og:").append(k).append(": ").append(og.optString(k, "")).append("\n");
                }
            }
            JSONObject tc = meta.optJSONObject("twitterCard");
            if (tc != null && tc.length() > 0) {
                sb.append("\n── Twitter Card ──\n");
                Iterator<String> keys = tc.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    sb.append("  twitter:").append(k).append(": ").append(tc.optString(k, "")).append("\n");
                }
            }
            if (feeds.length() > 0) {
                sb.append("\n── Feeds ──\n");
                for (int i = 0; i < feeds.length(); i++) {
                    JSONObject f = feeds.getJSONObject(i);
                    sb.append("  ").append(f.optString("type", "")).append(": ").append(f.optString("href", "")).append("\n");
                }
            }

            return ok(meta.toString(), sb.toString().trim());

        } catch (Exception e) {
            return error("Failed to extract metadata: " + e.getMessage());
        }
    }

    // ════════════════════════════════════════════
    //  HTTP fetching
    // ════════════════════════════════════════════

    private static class FetchResult {
        String finalUrl;
        int statusCode;
        String contentType = "";
        long contentLength = -1;
        long responseTimeMs;
        String body;
        Map<String, List<String>> headers;
        List<String> redirectChain = new ArrayList<>();

        boolean isHtml() {
            return contentType != null && contentType.contains("text/html");
        }
        boolean isImage() {
            return contentType != null && contentType.startsWith("image/");
        }
        boolean isDownloadable() {
            if (contentType == null) return false;
            if (contentType.contains("application/octet-stream")) return true;
            if (contentType.contains("application/pdf")) return true;
            if (contentType.contains("application/zip")) return true;
            String disp = null;
            if (headers != null) {
                List<String> vals = headers.get("Content-Disposition");
                if (vals == null) vals = headers.get("content-disposition");
                if (vals != null && !vals.isEmpty()) disp = vals.get(0);
            }
            return disp != null && disp.contains("attachment");
        }
    }

    private FetchResult fetchUrl(String url, boolean readBody) throws IOException {
        long start = System.currentTimeMillis();
        FetchResult result = new FetchResult();
        result.redirectChain.add(url);

        HttpURLConnection conn = openConnection(url);
        conn.setRequestMethod("GET");
        conn.setInstanceFollowRedirects(false);
        conn.connect();

        int maxRedirects = 10;
        while (conn.getResponseCode() / 100 == 3 && maxRedirects-- > 0) {
            String loc = conn.getHeaderField("Location");
            if (loc == null) break;
            loc = resolveUrl(result.redirectChain.get(result.redirectChain.size() - 1), loc);
            result.redirectChain.add(loc);
            conn.disconnect();
            conn = openConnection(loc);
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(false);
            conn.connect();
        }

        result.finalUrl = result.redirectChain.get(result.redirectChain.size() - 1);
        result.statusCode = conn.getResponseCode();
        result.contentType = conn.getContentType();
        result.contentLength = conn.getContentLengthLong();
        result.headers = conn.getHeaderFields();
        result.responseTimeMs = System.currentTimeMillis() - start;

        if (readBody && result.statusCode >= 200 && result.statusCode < 400) {
            Charset charset = detectCharset(result.contentType);
            InputStream is = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int totalRead = 0;
            int n;
            while ((n = is.read(buf)) != -1 && totalRead < MAX_BODY_SIZE) {
                bos.write(buf, 0, n);
                totalRead += n;
            }
            is.close();

            byte[] bodyBytes = bos.toByteArray();
            // Re-detect charset from HTML meta if needed
            String bodyStr = new String(bodyBytes, charset);
            Charset metaCharset = detectCharsetFromHtml(bodyStr);
            if (metaCharset != null && !metaCharset.equals(charset)) {
                bodyStr = new String(bodyBytes, metaCharset);
            }
            result.body = bodyStr;
        }

        conn.disconnect();
        return result;
    }

    private HttpURLConnection openConnection(String url) throws IOException {
        URL u = new URL(url);
        HttpURLConnection conn = (HttpURLConnection) u.openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        conn.setRequestProperty("Accept-Encoding", "identity");
        return conn;
    }

    private Charset detectCharset(String contentType) {
        if (contentType != null) {
            Matcher m = Pattern.compile("charset=([\\w-]+)", Pattern.CASE_INSENSITIVE).matcher(contentType);
            if (m.find()) {
                try { return Charset.forName(m.group(1)); } catch (Exception ignored) {}
            }
        }
        return StandardCharsets.UTF_8;
    }

    private Charset detectCharsetFromHtml(String html) {
        Matcher m = Pattern.compile("<meta[^>]+charset=[\"']?([\\w-]+)", Pattern.CASE_INSENSITIVE).matcher(html);
        if (m.find()) {
            try { return Charset.forName(m.group(1)); } catch (Exception ignored) {}
        }
        m = Pattern.compile("<meta[^>]+content=[\"'][^\"']*charset=([\\w-]+)", Pattern.CASE_INSENSITIVE).matcher(html);
        if (m.find()) {
            try { return Charset.forName(m.group(1)); } catch (Exception ignored) {}
        }
        return null;
    }

    // ════════════════════════════════════════════
    //  HTML parsing helpers
    // ════════════════════════════════════════════

    private JSONObject extractMetaFromHtml(String html, String baseUrl) {
        JSONObject meta = new JSONObject();
        try {
            // Title
            Matcher m = Pattern.compile("<title[^>]*>([^<]+)</title>", Pattern.CASE_INSENSITIVE).matcher(html);
            if (m.find()) meta.put("title", decodeHtmlEntities(m.group(1).trim()));

            // Meta description
            m = Pattern.compile("<meta[^>]+name=[\"']description[\"'][^>]+content=[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE).matcher(html);
            if (m.find()) meta.put("description", decodeHtmlEntities(m.group(1).trim()));
            if (!meta.has("description")) {
                m = Pattern.compile("<meta[^>]+content=[\"']([^\"']*)[\"'][^>]+name=[\"']description[\"']", Pattern.CASE_INSENSITIVE).matcher(html);
                if (m.find()) meta.put("description", decodeHtmlEntities(m.group(1).trim()));
            }

            // Canonical
            m = Pattern.compile("<link[^>]+rel=[\"']canonical[\"'][^>]+href=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html);
            if (m.find()) meta.put("canonical", resolveUrl(baseUrl, m.group(1)));

            // Language
            m = Pattern.compile("<html[^>]+lang=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html);
            if (m.find()) meta.put("language", m.group(1));

            // Favicon
            m = Pattern.compile("<link[^>]+rel=[\"'](?:shortcut )?icon[\"'][^>]+href=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html);
            if (m.find()) meta.put("favicon", resolveUrl(baseUrl, m.group(1)));
            if (!meta.has("favicon")) {
                m = Pattern.compile("<link[^>]+href=[\"']([^\"']+)[\"'][^>]+rel=[\"'](?:shortcut )?icon[\"']", Pattern.CASE_INSENSITIVE).matcher(html);
                if (m.find()) meta.put("favicon", resolveUrl(baseUrl, m.group(1)));
            }

            // Open Graph
            JSONObject og = new JSONObject();
            m = Pattern.compile("<meta[^>]+property=[\"']og:([^\"']+)[\"'][^>]+content=[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE).matcher(html);
            while (m.find()) og.put(m.group(1), decodeHtmlEntities(m.group(2)));
            m = Pattern.compile("<meta[^>]+content=[\"']([^\"']*)[\"'][^>]+property=[\"']og:([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html);
            while (m.find()) og.put(m.group(2), decodeHtmlEntities(m.group(1)));
            if (og.length() > 0) meta.put("openGraph", og);

            // Twitter Card
            JSONObject tc = new JSONObject();
            m = Pattern.compile("<meta[^>]+name=[\"']twitter:([^\"']+)[\"'][^>]+content=[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE).matcher(html);
            while (m.find()) tc.put(m.group(1), decodeHtmlEntities(m.group(2)));
            m = Pattern.compile("<meta[^>]+content=[\"']([^\"']*)[\"'][^>]+name=[\"']twitter:([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html);
            while (m.find()) tc.put(m.group(2), decodeHtmlEntities(m.group(1)));
            if (tc.length() > 0) meta.put("twitterCard", tc);

            // Keywords
            m = Pattern.compile("<meta[^>]+name=[\"']keywords[\"'][^>]+content=[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE).matcher(html);
            if (m.find()) meta.put("keywords", m.group(1).trim());

        } catch (Exception ignored) {}
        return meta;
    }

    private JSONArray extractImagesFromHtml(String html, String baseUrl) {
        JSONArray images = new JSONArray();
        Set<String> seen = new HashSet<>();
        try {
            // <img> tags
            Matcher m = Pattern.compile("<img[^>]+src=[\"']([^\"']+)[\"']([^>]*)", Pattern.CASE_INSENSITIVE).matcher(html);
            while (m.find()) {
                String src = resolveUrl(baseUrl, m.group(1));
                if (seen.add(src)) {
                    JSONObject img = new JSONObject();
                    img.put("src", src);
                    String rest = m.group(2);
                    Matcher altM = Pattern.compile("alt=[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE).matcher(rest);
                    if (altM.find()) img.put("alt", decodeHtmlEntities(altM.group(1)));
                    Matcher wM = Pattern.compile("width=[\"']?(\\d+)", Pattern.CASE_INSENSITIVE).matcher(rest);
                    if (wM.find()) img.put("width", Integer.parseInt(wM.group(1)));
                    Matcher hM = Pattern.compile("height=[\"']?(\\d+)", Pattern.CASE_INSENSITIVE).matcher(rest);
                    if (hM.find()) img.put("height", Integer.parseInt(hM.group(1)));
                    images.put(img);
                }
            }
            // og:image
            m = Pattern.compile("<meta[^>]+(?:property=[\"']og:image[\"'][^>]+content=[\"']([^\"']+)[\"']|content=[\"']([^\"']+)[\"'][^>]+property=[\"']og:image[\"'])", Pattern.CASE_INSENSITIVE).matcher(html);
            while (m.find()) {
                String src = m.group(1) != null ? m.group(1) : m.group(2);
                src = resolveUrl(baseUrl, src);
                if (seen.add(src)) {
                    JSONObject img = new JSONObject();
                    img.put("src", src);
                    img.put("source", "og:image");
                    images.put(img);
                }
            }
        } catch (Exception ignored) {}
        return images;
    }

    private JSONObject extractLinksFromHtml(String html, String baseUrl) {
        JSONObject result = new JSONObject();
        JSONArray internal = new JSONArray();
        JSONArray external = new JSONArray();
        Set<String> seen = new HashSet<>();
        try {
            String baseHost = new URL(baseUrl).getHost();
            Matcher m = Pattern.compile("<a[^>]+href=[\"']([^\"'#]+)[\"']([^>]*)", Pattern.CASE_INSENSITIVE).matcher(html);
            while (m.find()) {
                String href = m.group(1).trim();
                if (href.startsWith("javascript:") || href.startsWith("mailto:") || href.startsWith("tel:")) continue;
                href = resolveUrl(baseUrl, href);
                if (!seen.add(href)) continue;

                String text = "";
                Matcher textM = Pattern.compile("title=[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE).matcher(m.group(2));
                if (textM.find()) text = decodeHtmlEntities(textM.group(1));

                JSONObject link = new JSONObject();
                link.put("href", href);
                if (!text.isEmpty()) link.put("text", text);

                try {
                    String linkHost = new URL(href).getHost();
                    if (linkHost.equals(baseHost) || linkHost.endsWith("." + baseHost)) {
                        internal.put(link);
                    } else {
                        external.put(link);
                    }
                } catch (Exception e) {
                    internal.put(link);
                }
            }
            result.put("internal", internal);
            result.put("external", external);
        } catch (Exception ignored) {
            try {
                result.put("internal", internal);
                result.put("external", external);
            } catch (Exception ignored2) {}
        }
        return result;
    }

    private JSONArray extractDownloadsFromHtml(String html, String baseUrl) {
        JSONArray downloads = new JSONArray();
        Set<String> seen = new HashSet<>();
        try {
            // Links with download extensions
            Matcher m = Pattern.compile("href=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html);
            while (m.find()) {
                String href = m.group(1).trim();
                String ext = getExtension(href);
                if (DOWNLOAD_EXTS.contains(ext)) {
                    String resolved = resolveUrl(baseUrl, href);
                    if (seen.add(resolved)) {
                        JSONObject d = new JSONObject();
                        d.put("url", resolved);
                        d.put("fileName", guessFileNameFromUrl(resolved));
                        d.put("extension", ext);
                        downloads.put(d);
                    }
                }
            }
            // <a download="..."> attributes
            m = Pattern.compile("<a[^>]+download(?:=[\"']([^\"']*)[\"'])?[^>]+href=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html);
            while (m.find()) {
                String resolved = resolveUrl(baseUrl, m.group(2));
                if (seen.add(resolved)) {
                    JSONObject d = new JSONObject();
                    d.put("url", resolved);
                    String name = m.group(1);
                    d.put("fileName", (name != null && !name.isEmpty()) ? name : guessFileNameFromUrl(resolved));
                    d.put("extension", getExtension(resolved));
                    downloads.put(d);
                }
            }
        } catch (Exception ignored) {}
        return downloads;
    }

    private JSONArray extractFeeds(String html, String baseUrl) {
        JSONArray feeds = new JSONArray();
        try {
            Matcher m = Pattern.compile("<link[^>]+type=[\"'](application/(?:rss|atom)\\+xml)[\"'][^>]+href=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html);
            while (m.find()) {
                JSONObject f = new JSONObject();
                f.put("type", m.group(1));
                f.put("href", resolveUrl(baseUrl, m.group(2)));
                feeds.put(f);
            }
            m = Pattern.compile("<link[^>]+href=[\"']([^\"']+)[\"'][^>]+type=[\"'](application/(?:rss|atom)\\+xml)[\"']", Pattern.CASE_INSENSITIVE).matcher(html);
            while (m.find()) {
                JSONObject f = new JSONObject();
                f.put("type", m.group(2));
                f.put("href", resolveUrl(baseUrl, m.group(1)));
                feeds.put(f);
            }
        } catch (Exception ignored) {}
        return feeds;
    }

    private String extractPlainText(String html, int maxLen) {
        // Remove script, style, noscript blocks
        String cleaned = html.replaceAll("(?is)<(script|style|noscript)[^>]*>.*?</\\1>", "");
        // Remove all tags
        cleaned = cleaned.replaceAll("<[^>]+>", " ");
        // Decode entities
        cleaned = decodeHtmlEntities(cleaned);
        // Normalize whitespace
        cleaned = cleaned.replaceAll("[ \\t]+", " ").replaceAll("\\n\\s*\\n+", "\n\n").trim();
        if (cleaned.length() > maxLen) cleaned = cleaned.substring(0, maxLen) + "...";
        return cleaned;
    }

    // ════════════════════════════════════════════
    //  Utility
    // ════════════════════════════════════════════

    private String normalizeUrl(String url) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }
        return url;
    }

    private String resolveUrl(String base, String relative) {
        try {
            return new URL(new URL(base), relative).toString();
        } catch (Exception e) {
            return relative;
        }
    }

    private String getExtension(String url) {
        try {
            String path = new URL(normalizeUrl(url)).getPath().toLowerCase();
            int dot = path.lastIndexOf('.');
            if (dot >= 0 && dot < path.length() - 1) {
                String ext = path.substring(dot + 1);
                if (ext.length() <= 10) return ext;
            }
        } catch (Exception ignored) {}
        return "";
    }

    private String guessFileName(String url, Map<String, List<String>> headers) {
        // From Content-Disposition
        if (headers != null) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase("Content-Disposition")) {
                    for (String v : e.getValue()) {
                        Matcher m = Pattern.compile("filename=[\"']?([^\"';\\s]+)", Pattern.CASE_INSENSITIVE).matcher(v);
                        if (m.find()) return m.group(1);
                    }
                }
            }
        }
        return guessFileNameFromUrl(url);
    }

    private String guessFileNameFromUrl(String url) {
        try {
            String path = new URL(url).getPath();
            int slash = path.lastIndexOf('/');
            if (slash >= 0 && slash < path.length() - 1) {
                return URLDecoder.decode(path.substring(slash + 1), "UTF-8");
            }
        } catch (Exception ignored) {}
        return "unknown";
    }

    private String decodeHtmlEntities(String text) {
        if (text == null) return "";
        String result = text
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
            .replace("&#x27;", "'").replace("&nbsp;", " ");
        // Decode numeric entities like &#123;
        StringBuffer sb = new StringBuffer();
        Matcher m = Pattern.compile("&#(\\d+);").matcher(result);
        while (m.find()) {
            try {
                int code = Integer.parseInt(m.group(1));
                m.appendReplacement(sb, String.valueOf((char) code));
            } catch (Exception e) {
                m.appendReplacement(sb, m.group(0));
            }
        }
        m.appendTail(sb);
        return sb.toString().trim();
    }

    private String formatSize(long bytes) {
        if (bytes < 0) return "unknown";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private String buildParseDisplay(JSONObject result) {
        StringBuilder sb = new StringBuilder();
        try {
            sb.append("🔗 ").append(result.optString("url", "")).append("\n");
            sb.append("📊 Status: ").append(result.optInt("statusCode")).append(" | ⏱ ").append(result.optLong("responseTimeMs")).append("ms\n");
            sb.append("📄 Type: ").append(result.optString("contentType", "unknown")).append("\n\n");

            String title = result.optString("title", "");
            if (!title.isEmpty()) sb.append("📌 ").append(title).append("\n");
            String desc = result.optString("description", "");
            if (!desc.isEmpty()) sb.append("📝 ").append(desc).append("\n");

            int imgCount = result.optInt("imageCount", 0);
            if (imgCount > 0) sb.append("\n🖼 Images: ").append(imgCount);

            JSONArray internal = result.optJSONArray("internalLinks");
            JSONArray external = result.optJSONArray("externalLinks");
            if (internal != null || external != null) {
                sb.append("\n🔗 Links: ");
                if (internal != null) sb.append(internal.length()).append(" internal");
                if (internal != null && external != null) sb.append(", ");
                if (external != null) sb.append(external.length()).append(" external");
            }

            JSONArray downloads = result.optJSONArray("downloads");
            if (downloads != null && downloads.length() > 0) {
                sb.append("\n\n📥 Downloads (").append(downloads.length()).append("):\n");
                for (int i = 0; i < Math.min(downloads.length(), 10); i++) {
                    JSONObject d = downloads.getJSONObject(i);
                    sb.append("  ").append(i + 1).append(". ").append(d.optString("fileName", "")).append("\n");
                }
            }

            String preview = result.optString("textPreview", "");
            if (!preview.isEmpty()) {
                sb.append("\n\n── Preview ──\n").append(preview.length() > 500 ? preview.substring(0, 500) + "..." : preview);
            }

        } catch (Exception ignored) {}
        return sb.toString().trim();
    }

    // ── JSON response helpers ──

    private static String ok(String output, String displayText) {
        try {
            JSONObject j = new JSONObject();
            j.put("success", true);
            j.put("output", output);
            j.put("_displayText", displayText);
            return j.toString();
        } catch (Exception e) {
            return "{\"success\":true,\"output\":\"\"}";
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
