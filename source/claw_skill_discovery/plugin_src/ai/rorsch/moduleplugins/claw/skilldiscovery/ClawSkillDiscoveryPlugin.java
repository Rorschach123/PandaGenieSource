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
                if (query.isEmpty()) query = "agent";
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
        JSONObject root;
        try {
            root = new JSONObject(get(API_BASE + path));
        } catch (Exception e) {
            root = fallbackRoot(action, e);
        }
        if (root.has("ok") && !root.optBoolean("ok", true)) {
            throw new IllegalStateException(root.optString("error", "Skill API request failed"));
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
                .put("fallback", root.optBoolean("fallback", false))
                .put("items", items);
        return ok(out, formatText(out), formatHtml(out));
    }

    private JSONObject fallbackRoot(String action, Exception error) throws Exception {
        JSONArray data = new JSONArray()
                .put(new JSONObject()
                        .put("slug", "security-audit")
                        .put("display_name", "Security Audit")
                        .put("summary", "Scan skills for risky commands, hidden instructions, and secret exfiltration.")
                        .put("owner_handle", "clawhub")
                        .put("downloads", 12000)
                        .put("stars", 320)
                        .put("is_certified", true))
                .put(new JSONObject()
                        .put("slug", "doc-updater")
                        .put("display_name", "Document Updater")
                        .put("summary", "Compare documentation versions and generate update plans.")
                        .put("owner_handle", "clawhub")
                        .put("downloads", 9800)
                        .put("stars", 210)
                        .put("is_certified", true))
                .put(new JSONObject()
                        .put("slug", "workflow-planner")
                        .put("display_name", "Workflow Planner")
                        .put("summary", "Break goals into executable agent workflows.")
                        .put("owner_handle", "clawhub")
                        .put("downloads", 7600)
                        .put("stars", 180)
                        .put("is_certified", false));
        return new JSONObject()
                .put("fallback", true)
                .put("action", action)
                .put("total", data.length())
                .put("generated_at", "offline")
                .put("error", error.getMessage())
                .put("data", data);
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
        boolean zh = isZh();
        StringBuilder sb = new StringBuilder(zh ? "技能推荐：" : "Skills: ").append(out.optInt("count"));
        JSONArray items = out.optJSONArray("items");
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                sb.append("\n").append(i + 1).append(". ")
                        .append(item.optString("name"))
                        .append(zh ? " · 下载 " : " - ")
                        .append(shortNumber(item.optInt("downloads")))
                        .append(zh ? " 次" : " downloads");
                String summary = item.optString("summary", "").trim();
                if (!summary.isEmpty()) sb.append("\n   ").append(summary);
            }
        }
        return sb.toString();
    }

    private String formatHtml(JSONObject out) {
        boolean zh = isZh();
        JSONArray items = out.optJSONArray("items");
        StringBuilder list = new StringBuilder();
        if (items != null) {
            for (int i = 0; i < items.length() && i < 12; i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                String meta = (zh ? "作者：" : "Author: ") + firstNonEmpty(item.optString("author"), "-")
                        + " · " + (zh ? "下载：" : "Downloads: ") + shortNumber(item.optInt("downloads"));
                if (item.optBoolean("certified", false)) meta += " · " + (zh ? "已认证" : "Certified");
                String summary = item.optString("summary", "").trim();
                list.append(itemHtml("#" + (i + 1) + " " + item.optString("name"), meta,
                        summary.isEmpty() ? (zh ? "暂无简介。" : "No summary.") : summary));
            }
        }
        String body = HtmlOutputHelper.badge(actionName(out.optString("action"), zh), "blue")
                + HtmlOutputHelper.keyValue(new String[][]{
                {zh ? "结果数量" : "Count", String.valueOf(out.optInt("count"))},
                {zh ? "数据来源" : "Source", out.optBoolean("fallback", false) ? (zh ? "离线示例数据" : "offline fallback") : "ClawHub"}
        })
                + sectionHtml(zh ? "推荐技能" : "Recommended skills",
                list.length() == 0 ? HtmlOutputHelper.muted(zh ? "没有找到技能。" : "No skills found.") : list.toString());
        return HtmlOutputHelper.card(zh ? "技" : "SK", zh ? "技能发现" : "Skill Discovery", body);
    }

    private boolean isZh() {
        return Locale.getDefault().getLanguage().startsWith("zh");
    }

    private String sectionHtml(String title, String bodyHtml) {
        return "<div class='pg-section'><div class='pg-section-title'>" + escHtml(title)
                + "</div>" + bodyHtml + "</div>";
    }

    private String itemHtml(String title, String meta, String text) {
        StringBuilder sb = new StringBuilder("<div class='pg-item'>");
        if (title != null && !title.isEmpty()) sb.append("<div class='pg-item-title'>").append(escHtml(title)).append("</div>");
        if (meta != null && !meta.isEmpty()) sb.append("<div class='pg-item-meta'>").append(escHtml(meta)).append("</div>");
        if (text != null && !text.isEmpty()) sb.append("<div>").append(escHtml(text)).append("</div>");
        sb.append("</div>");
        return sb.toString();
    }

    private String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private String actionName(String action, boolean zh) {
        if (!zh) return action;
        if ("topDownloads".equals(action)) return "热门下载";
        if ("certifiedSkills".equals(action)) return "认证技能";
        if ("newestSkills".equals(action)) return "最新技能";
        if ("searchSkills".equals(action)) return "搜索结果";
        return "技能发现";
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
