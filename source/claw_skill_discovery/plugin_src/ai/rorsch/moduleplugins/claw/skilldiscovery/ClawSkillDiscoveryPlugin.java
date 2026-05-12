package ai.rorsch.moduleplugins.claw.skilldiscovery;

import ai.rorsch.pandagenie.module.runtime.HtmlOutputHelper;
import ai.rorsch.pandagenie.module.runtime.ModulePlugin;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ClawSkillDiscoveryPlugin implements ModulePlugin {
    private static final String API_BASE = "https://topclawhubskills.com/api";

    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        JSONObject params = new JSONObject(emptyJson(paramsJson));
        try {
            if ("topDownloads".equals(action)) {
                return fetchList("topDownloads", "/top-downloads?limit=" + limit(params));
            }
            if ("certifiedSkills".equals(action)) {
                return fetchList("certifiedSkills", "/certified?limit=" + limit(params));
            }
            if ("newestSkills".equals(action)) {
                return fetchList("newestSkills", "/newest?limit=" + limit(params));
            }
            if ("searchSkills".equals(action)) {
                String query = params.optString("query", "").trim();
                if (query.isEmpty()) throw new IllegalArgumentException("query is required");
                return fetchList("searchSkills", "/search?q=" + URLEncoder.encode(query, "UTF-8") + "&limit=" + limit(params));
            }
            if ("openPage".equals(action)) {
                return new JSONObject().put("success", true).put("output", "{}").put("_openModule", true).toString();
            }
            return error("Unsupported action: " + action);
        } catch (Exception e) {
            return error(e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    private String fetchList(String action, String path) throws Exception {
        JSONObject root = new JSONObject(get(API_BASE + path));
        if (root.has("ok") && !root.optBoolean("ok", true)) {
            throw new IllegalStateException(root.optString("error", "ClawHub API request failed"));
        }
        JSONArray raw = firstArray(root);
        JSONArray items = new JSONArray();
        for (int i = 0; i < raw.length(); i++) {
            JSONObject item = raw.optJSONObject(i);
            if (item == null) continue;
            items.put(normalize(item));
        }
        JSONObject out = new JSONObject()
                .put("module", "claw_skill_discovery")
                .put("action", action)
                .put("count", items.length())
                .put("total", root.optInt("total", items.length()))
                .put("generatedAt", root.optString("generated_at", ""))
                .put("source", API_BASE)
                .put("items", items);
        return ok(out, formatText(out), formatHtml(out));
    }

    private JSONObject normalize(JSONObject item) throws Exception {
        String slug = firstNonEmpty(item.optString("slug", ""), item.optString("id", ""));
        String name = firstNonEmpty(item.optString("display_name", ""), firstNonEmpty(item.optString("name", ""), slug));
        String url = firstNonEmpty(item.optString("clawhub_url", ""), "https://clawhub.ai/skills/" + slug);
        return new JSONObject()
                .put("slug", slug)
                .put("name", name)
                .put("summary", item.optString("summary", item.optString("description", "")))
                .put("author", item.optString("owner_handle", item.optString("author", "")))
                .put("downloads", item.optInt("downloads", 0))
                .put("stars", item.optInt("stars", 0))
                .put("status", item.optString("status", item.optBoolean("is_certified", false) ? "certified" : ""))
                .put("certified", item.optBoolean("is_certified", false))
                .put("url", url);
    }

    private JSONArray firstArray(JSONObject root) {
        JSONArray arr = root.optJSONArray("data");
        if (arr != null) return arr;
        arr = root.optJSONArray("items");
        if (arr != null) return arr;
        arr = root.optJSONArray("results");
        return arr != null ? arr : new JSONArray();
    }

    private String get(String urlValue) throws Exception {
        URL url = new URL(urlValue);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("User-Agent", "PandaGenie-ClawSkillDiscovery/1.0");
        try (InputStream in = conn.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int total = 0;
            int n;
            while ((n = in.read(buf)) >= 0) {
                total += n;
                if (total > 768 * 1024) throw new IllegalStateException("response too large");
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
        }
    }

    private String formatText(JSONObject out) {
        StringBuilder sb = new StringBuilder("Claw skills: ").append(out.optInt("count"));
        JSONArray items = out.optJSONArray("items");
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                sb.append("\n").append(i + 1).append(". ")
                        .append(item.optString("name"))
                        .append(" - ")
                        .append(shortNumber(item.optInt("downloads")))
                        .append(" downloads");
            }
        }
        return sb.toString();
    }

    private String formatHtml(JSONObject out) {
        JSONArray items = out.optJSONArray("items");
        List<String[]> rows = new ArrayList<>();
        if (items != null) {
            for (int i = 0; i < items.length() && i < 12; i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                rows.add(new String[]{
                        item.optString("name"),
                        item.optString("author"),
                        shortNumber(item.optInt("downloads")),
                        item.optBoolean("certified", false) ? "OK" : item.optString("status", "-")
                });
            }
        }
        String body = HtmlOutputHelper.badge(out.optString("action"), "blue")
                + HtmlOutputHelper.keyValue(new String[][]{
                {"Count", String.valueOf(out.optInt("count"))},
                {"Source", out.optString("source")}
        })
                + HtmlOutputHelper.table(new String[]{"Skill", "Author", "Downloads", "Status"}, rows);
        return HtmlOutputHelper.card("CLAW", "Claw Skill Discovery", body);
    }

    private String shortNumber(int value) {
        if (value >= 1000000) return String.format(Locale.US, "%.1fM", value / 1000000.0);
        if (value >= 1000) return String.format(Locale.US, "%.1fK", value / 1000.0);
        return String.valueOf(value);
    }

    private int limit(JSONObject params) {
        return Math.max(1, Math.min(50, params.optInt("limit", 10)));
    }

    private String firstNonEmpty(String a, String b) {
        return a != null && !a.trim().isEmpty() ? a.trim() : b;
    }

    private String ok(JSONObject out, String text, String html) throws Exception {
        return new JSONObject()
                .put("success", true)
                .put("output", out.toString())
                .put("_displayText", text)
                .put("_displayHtml", html)
                .toString();
    }

    private String error(String message) throws Exception {
        return new JSONObject().put("success", false).put("error", message).toString();
    }

    private String emptyJson(String value) {
        return value == null || value.trim().isEmpty() ? "{}" : value;
    }
}

