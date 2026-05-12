package ai.rorsch.moduleplugins.claw.docupdater;

import ai.rorsch.pandagenie.module.runtime.HtmlOutputHelper;
import ai.rorsch.pandagenie.module.runtime.ModulePlugin;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class ClawDocUpdaterPlugin implements ModulePlugin {
    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        JSONObject params = new JSONObject(emptyJson(paramsJson));
        try {
            if ("compareDocs".equals(action)) return compareDocs(params);
            if ("generateUpdatePlan".equals(action)) return generateUpdatePlan(params);
            if ("extractTodos".equals(action)) return extractTodos(params);
            if ("openPage".equals(action)) {
                return new JSONObject().put("success", true).put("output", "{}").put("_openModule", true).toString();
            }
            return error("Unsupported action: " + action);
        } catch (Exception e) {
            return error(e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    private String compareDocs(JSONObject params) throws Exception {
        String oldText = params.optString("oldText", "");
        String newText = params.optString("newText", "");
        if (oldText.trim().isEmpty() || newText.trim().isEmpty()) {
            throw new IllegalArgumentException("oldText and newText are required");
        }
        String title = params.optString("title", "Document").trim();
        Set<String> oldLines = normalizedLines(oldText);
        Set<String> newLines = normalizedLines(newText);

        JSONArray added = new JSONArray();
        JSONArray removed = new JSONArray();
        for (String line : newLines) if (!oldLines.contains(line)) added.put(line);
        for (String line : oldLines) if (!newLines.contains(line)) removed.put(line);

        JSONArray headings = headings(newText);
        String markdown = buildChangelog(title, added, removed, headings);
        JSONObject out = new JSONObject()
                .put("module", "claw_doc_updater")
                .put("action", "compareDocs")
                .put("title", title)
                .put("oldLineCount", oldLines.size())
                .put("newLineCount", newLines.size())
                .put("addedCount", added.length())
                .put("removedCount", removed.length())
                .put("added", added)
                .put("removed", removed)
                .put("headings", headings)
                .put("markdown", markdown);
        return ok(out, markdown, formatHtml(out));
    }

    private String generateUpdatePlan(JSONObject params) throws Exception {
        String notes = params.optString("notes", "").trim();
        if (notes.isEmpty()) throw new IllegalArgumentException("notes is required");
        String audience = params.optString("audience", "普通用户").trim();
        String docType = params.optString("docType", "README/帮助文档").trim();
        JSONArray bullets = meaningfulLines(notes, 20);
        JSONArray plan = new JSONArray()
                .put("确认变更范围和影响面")
                .put("更新 " + docType + " 的功能说明、限制条件和示例")
                .put("补充面向" + audience + "的注意事项")
                .put("检查版本号、截图、链接和命令是否仍然有效")
                .put("最后做一次移动端短屏阅读检查");
        JSONObject out = new JSONObject()
                .put("module", "claw_doc_updater")
                .put("action", "generateUpdatePlan")
                .put("docType", docType)
                .put("audience", audience)
                .put("notes", bullets)
                .put("plan", plan)
                .put("markdown", planMarkdown(docType, bullets, plan));
        return ok(out, out.optString("markdown"), formatHtml(out));
    }

    private String extractTodos(JSONObject params) throws Exception {
        String text = params.optString("text", "").trim();
        if (text.isEmpty()) throw new IllegalArgumentException("text is required");
        JSONArray todos = new JSONArray();
        String[] lines = text.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("todo") || lower.contains("fixme") || line.contains("待办")
                    || line.contains("待补充") || line.contains("未完成") || line.contains("后续")) {
                todos.put(new JSONObject().put("line", i + 1).put("text", line));
            }
        }
        JSONObject out = new JSONObject()
                .put("module", "claw_doc_updater")
                .put("action", "extractTodos")
                .put("count", todos.length())
                .put("todos", todos);
        return ok(out, "Found " + todos.length() + " todo item(s)", formatHtml(out));
    }

    private Set<String> normalizedLines(String text) {
        Set<String> set = new LinkedHashSet<>();
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim().replaceAll("\\s+", " ");
            if (line.length() >= 3) set.add(line);
        }
        return set;
    }

    private JSONArray headings(String text) {
        JSONArray arr = new JSONArray();
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.startsWith("#")) arr.put(line.replaceFirst("^#+\\s*", ""));
        }
        return arr;
    }

    private JSONArray meaningfulLines(String text, int limit) {
        JSONArray arr = new JSONArray();
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim().replaceFirst("^[-*]\\s*", "");
            if (line.length() >= 3) arr.put(line);
            if (arr.length() >= limit) break;
        }
        if (arr.length() == 0) arr.put(text);
        return arr;
    }

    private String buildChangelog(String title, JSONArray added, JSONArray removed, JSONArray headings) {
        StringBuilder sb = new StringBuilder("# ").append(title).append(" 更新摘要\n\n");
        sb.append("- 新增线索：").append(added.length()).append("\n");
        sb.append("- 删除线索：").append(removed.length()).append("\n");
        sb.append("- 新版标题数：").append(headings.length()).append("\n\n");
        appendList(sb, "新增内容", added);
        appendList(sb, "移除内容", removed);
        return sb.toString();
    }

    private String planMarkdown(String docType, JSONArray notes, JSONArray plan) {
        StringBuilder sb = new StringBuilder("# ").append(docType).append(" 更新计划\n\n");
        appendList(sb, "输入要点", notes);
        appendList(sb, "执行步骤", plan);
        return sb.toString();
    }

    private void appendList(StringBuilder sb, String title, JSONArray arr) {
        sb.append("## ").append(title).append("\n");
        if (arr.length() == 0) {
            sb.append("- 无\n\n");
            return;
        }
        for (int i = 0; i < arr.length() && i < 12; i++) sb.append("- ").append(arr.optString(i)).append("\n");
        sb.append("\n");
    }

    private String formatHtml(JSONObject out) {
        String action = out.optString("action", "doc");
        String body = HtmlOutputHelper.badge(action, "blue");
        if ("compareDocs".equals(action)) {
            body += HtmlOutputHelper.keyValue(new String[][]{
                    {"Added", String.valueOf(out.optInt("addedCount"))},
                    {"Removed", String.valueOf(out.optInt("removedCount"))},
                    {"Headings", String.valueOf(out.optJSONArray("headings") != null ? out.optJSONArray("headings").length() : 0)}
            });
            body += HtmlOutputHelper.p(out.optString("markdown"));
        } else if ("extractTodos".equals(action)) {
            List<String[]> rows = new ArrayList<>();
            JSONArray todos = out.optJSONArray("todos");
            if (todos != null) {
                for (int i = 0; i < todos.length(); i++) {
                    JSONObject t = todos.optJSONObject(i);
                    if (t != null) rows.add(new String[]{String.valueOf(t.optInt("line")), t.optString("text")});
                }
            }
            body += HtmlOutputHelper.table(new String[]{"Line", "Todo"}, rows);
        } else {
            body += HtmlOutputHelper.p(out.optString("markdown"));
        }
        return HtmlOutputHelper.card("DOC", "Claw Doc Updater", body);
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

