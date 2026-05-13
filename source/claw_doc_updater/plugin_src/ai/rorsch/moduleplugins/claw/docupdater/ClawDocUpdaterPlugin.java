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
        boolean demo = oldText.trim().isEmpty() && newText.trim().isEmpty();
        if (demo) {
            oldText = "# PandaGenie Module\n\n- Install modules manually\n- Read docs on desktop\n";
            newText = "# PandaGenie Module\n\n- Install and update modules from Module Market\n- Read docs on mobile\n- Print generated reports as HTML\n";
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
                .put("demo", demo)
                .put("title", title)
                .put("oldLineCount", oldLines.size())
                .put("newLineCount", newLines.size())
                .put("addedCount", added.length())
                .put("removedCount", removed.length())
                .put("added", added)
                .put("removed", removed)
                .put("headings", headings)
                .put("markdown", markdown);
        return ok(out, formatText(out), formatHtml(out));
    }

    private String generateUpdatePlan(JSONObject params) throws Exception {
        String notes = params.optString("notes", "").trim();
        boolean demo = notes.isEmpty();
        if (demo) {
            notes = "模块市场新增技能工具\n移动端报告 HTML 需要优化打印和窄屏展示\n补充开发者签名映射和发布记录";
        }
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
                .put("demo", demo)
                .put("docType", docType)
                .put("audience", audience)
                .put("notes", bullets)
                .put("plan", plan)
                .put("markdown", planMarkdown(docType, bullets, plan));
        return ok(out, formatText(out), formatHtml(out));
    }

    private String extractTodos(JSONObject params) throws Exception {
        String text = params.optString("text", "").trim();
        boolean demo = text.isEmpty();
        if (demo) {
            text = "TODO: 补充模块市场截图\nFIXME: 更新旧版安装说明\n待办：验证打印 HTML 在小屏上的布局";
        }
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
                .put("demo", demo)
                .put("count", todos.length())
                .put("todos", todos);
        return ok(out, formatText(out), formatHtml(out));
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
        boolean zh = isZh();
        String action = out.optString("action", "doc");
        String body = HtmlOutputHelper.badge(actionName(action, zh), "blue");
        if ("compareDocs".equals(action)) {
            body += HtmlOutputHelper.metricGrid(new String[][]{
                    {String.valueOf(out.optInt("addedCount")), zh ? "新增线索" : "Added"},
                    {String.valueOf(out.optInt("removedCount")), zh ? "删除线索" : "Removed"},
                    {String.valueOf(out.optJSONArray("headings") != null ? out.optJSONArray("headings").length() : 0), zh ? "新版标题" : "Headings"}
            });
            body += listSection(zh ? "新增内容" : "Added content", out.optJSONArray("added"), zh ? "没有新增内容" : "No added content", 5);
            body += listSection(zh ? "移除内容" : "Removed content", out.optJSONArray("removed"), zh ? "没有移除内容" : "No removed content", 5);
        } else if ("extractTodos".equals(action)) {
            JSONArray todos = out.optJSONArray("todos");
            StringBuilder items = new StringBuilder();
            if (todos != null) {
                for (int i = 0; i < todos.length(); i++) {
                    JSONObject t = todos.optJSONObject(i);
                    if (t != null) {
                        items.append(itemHtml(
                                (zh ? "第 " : "Line ") + t.optInt("line") + (zh ? " 行" : ""),
                                "",
                                t.optString("text")));
                    }
                }
            }
            body += sectionHtml(zh ? "待处理事项" : "Todo items",
                    items.length() == 0 ? HtmlOutputHelper.muted(zh ? "没有发现待办。" : "No todo item found.") : items.toString());
        } else {
            body += listSection(zh ? "输入要点" : "Input notes", out.optJSONArray("notes"), zh ? "没有输入要点" : "No notes", 6);
            body += listSection(zh ? "建议步骤" : "Suggested steps", out.optJSONArray("plan"), zh ? "没有生成步骤" : "No steps", 8);
        }
        return HtmlOutputHelper.card(zh ? "文" : "DOC", zh ? "文档更新助手" : "Document Update Helper", body);
    }

    private String formatText(JSONObject out) {
        boolean zh = isZh();
        String action = out.optString("action", "doc");
        StringBuilder sb = new StringBuilder();
        if ("compareDocs".equals(action)) {
            sb.append(zh ? "文档变更摘要" : "Document change summary").append("\n")
                    .append(zh ? "新增：" : "Added: ").append(out.optInt("addedCount"))
                    .append(zh ? " 条，删除：" : ", Removed: ").append(out.optInt("removedCount"))
                    .append(zh ? " 条，标题：" : ", Headings: ")
                    .append(out.optJSONArray("headings") != null ? out.optJSONArray("headings").length() : 0);
            appendTextList(sb, zh ? "\n\n新增内容" : "\n\nAdded content", out.optJSONArray("added"), 5);
            appendTextList(sb, zh ? "\n移除内容" : "\nRemoved content", out.optJSONArray("removed"), 5);
        } else if ("extractTodos".equals(action)) {
            sb.append(zh ? "待办提取结果：" : "Todo extraction: ").append(out.optInt("count")).append(zh ? " 条" : " item(s)");
            JSONArray todos = out.optJSONArray("todos");
            if (todos != null) {
                for (int i = 0; i < todos.length() && i < 8; i++) {
                    JSONObject t = todos.optJSONObject(i);
                    if (t != null) sb.append("\n").append(i + 1).append(". ")
                            .append(zh ? "第 " : "Line ").append(t.optInt("line"))
                            .append(zh ? " 行：" : ": ").append(t.optString("text"));
                }
            }
        } else {
            sb.append(zh ? "文档更新计划" : "Document update plan");
            appendTextList(sb, zh ? "\n\n输入要点" : "\n\nInput notes", out.optJSONArray("notes"), 6);
            appendTextList(sb, zh ? "\n建议步骤" : "\nSuggested steps", out.optJSONArray("plan"), 8);
        }
        return sb.toString();
    }

    private String listSection(String title, JSONArray arr, String emptyText, int limit) {
        StringBuilder sb = new StringBuilder();
        if (arr != null) {
            for (int i = 0; i < arr.length() && i < limit; i++) {
                String item = arr.optString(i, "").trim();
                if (!item.isEmpty()) sb.append(itemHtml(String.valueOf(i + 1), "", item));
            }
        }
        return sectionHtml(title, sb.length() == 0 ? HtmlOutputHelper.muted(emptyText) : sb.toString());
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

    private void appendTextList(StringBuilder sb, String title, JSONArray arr, int limit) {
        sb.append(title).append("：");
        if (arr == null || arr.length() == 0) {
            sb.append("\n- 无");
            return;
        }
        for (int i = 0; i < arr.length() && i < limit; i++) sb.append("\n").append(i + 1).append(". ").append(arr.optString(i));
    }

    private String actionName(String action, boolean zh) {
        if (!zh) return action;
        if ("compareDocs".equals(action)) return "变更对比";
        if ("extractTodos".equals(action)) return "待办提取";
        if ("generateUpdatePlan".equals(action)) return "更新计划";
        return "文档处理";
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
