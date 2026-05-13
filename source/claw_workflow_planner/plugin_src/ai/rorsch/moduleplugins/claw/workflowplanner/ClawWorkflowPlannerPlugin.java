package ai.rorsch.moduleplugins.claw.workflowplanner;

import ai.rorsch.pandagenie.module.runtime.HtmlOutputHelper;
import ai.rorsch.pandagenie.module.runtime.ModuleLlm;
import ai.rorsch.pandagenie.module.runtime.ModulePlugin;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ClawWorkflowPlannerPlugin implements ModulePlugin {
    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        JSONObject params = new JSONObject(emptyJson(paramsJson));
        try {
            if ("planWorkflow".equals(action)) return planWorkflow(context, params);
            if ("makeChecklist".equals(action)) return makeChecklist(params);
            if ("riskCheckPlan".equals(action)) return riskCheckPlan(params);
            if ("openPage".equals(action)) {
                return new JSONObject().put("success", true).put("output", "{}").put("_openModule", true).toString();
            }
            return error("Unsupported action: " + action);
        } catch (Exception e) {
            return error(e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    private String planWorkflow(Context context, JSONObject params) throws Exception {
        String goal = params.optString("goal", "").trim();
        if (goal.isEmpty()) return planWorkflowDemo(params);
        String constraints = params.optString("constraints", "").trim();
        String modules = params.optString("availableModules", "").trim();
        String language = params.optString("language", "zh").trim();
        int maxTokens = clamp(params.optInt("maxTokens", 768), 128, 1024);
        String prompt = "You are a mobile workflow planner inside PandaGenie.\n"
                + "Create a concise, executable workflow for a phone user. Do not claim any step has already run.\n"
                + "Use short plain-language lines, not a long paragraph. Include: goal summary, ordered steps, suggested PandaGenie modules/actions if helpful, checkpoints, and risks.\n"
                + "Avoid desktop-only shell/CLI assumptions unless the user explicitly asked for them.\n"
                + "Output language: " + language + "\n"
                + "Goal: " + goal + "\n"
                + (constraints.isEmpty() ? "" : "Constraints: " + constraints + "\n")
                + (modules.isEmpty() ? "" : "Available modules: " + modules + "\n");
        JSONObject request = new JSONObject()
                .put("action", "claw_workflow_planner.planWorkflow")
                .put("prompt", prompt)
                .put("temperature", 0.25)
                .put("maxTokens", maxTokens);
        JSONObject llm = new JSONObject(ModuleLlm.completeJson(context, request.toString()));
        if (!llm.optBoolean("success", false)) {
            throw new IllegalStateException(llm.optString("error", "LLM request failed"));
        }
        String plan = llm.optString("text", "").trim();
        JSONObject risks = localRiskCheck(plan);
        JSONObject out = new JSONObject()
                .put("module", "claw_workflow_planner")
                .put("goal", goal)
                .put("plan", plan)
                .put("risks", risks)
                .put("usage", llm.optJSONObject("usage"));
        return ok(out, formatPlanText(out), formatPlanHtml(out));
    }

    private String makeChecklist(JSONObject params) throws Exception {
        String planText = params.optString("planText", "").trim();
        boolean demo = planText.isEmpty();
        if (demo) {
            planText = "确认目标和输入资料\n选择合适模块执行\n检查输出结果\n更新本地文档";
        }
        JSONArray checklist = new JSONArray();
        String[] lines = planText.split("\\r?\\n");
        for (String raw : lines) {
            String line = raw.trim().replaceFirst("^[-*\\d.)\\s]+", "").trim();
            if (line.length() < 3) continue;
            checklist.put(new JSONObject().put("done", false).put("text", line));
        }
        JSONObject out = new JSONObject()
                .put("module", "claw_workflow_planner")
                .put("action", "makeChecklist")
                .put("demo", demo)
                .put("count", checklist.length())
                .put("checklist", checklist);
        return ok(out, checklistText(checklist), formatChecklistHtml(out));
    }

    private String riskCheckPlan(JSONObject params) throws Exception {
        String planText = params.optString("planText", "").trim();
        boolean demo = planText.isEmpty();
        if (demo) {
            planText = "读取文档，生成报告，不删除文件，不外发密钥，用户确认后再执行。";
        }
        JSONObject out = localRiskCheck(planText)
                .put("module", "claw_workflow_planner")
                .put("action", "riskCheckPlan")
                .put("demo", demo);
        boolean zh = isZh();
        return ok(out, (zh ? "风险等级：" : "Risk level: ") + levelName(out.optString("riskLevel"), zh), formatRiskHtml(out));
    }

    private String planWorkflowDemo(JSONObject params) throws Exception {
        String goal = "展示工作流规划器如何把目标拆成手机端可执行步骤";
        String plan = "1. 明确目标、输入资料和限制条件。\n"
                + "2. 选择合适的 PandaGenie 模块执行文档、检索或安全检查任务。\n"
                + "3. 每一步完成后查看结果卡片，必要时进入详情页确认。\n"
                + "4. 对涉及删除、转账、外发数据的动作先停下来二次确认。\n"
                + "5. 把最终结果同步到本地文档或模块市场记录。";
        JSONObject risks = localRiskCheck(plan);
        JSONObject out = new JSONObject()
                .put("module", "claw_workflow_planner")
                .put("demo", true)
                .put("goal", goal)
                .put("plan", plan)
                .put("risks", risks);
        return ok(out, formatPlanText(out), formatPlanHtml(out));
    }

    private JSONObject localRiskCheck(String text) throws Exception {
        String lower = text.toLowerCase(Locale.ROOT);
        JSONArray findings = new JSONArray();
        addIf(findings, lower, "destructive", "Deletes, wipes, formats, or resets data",
                "delete", "remove", "wipe", "format", "reset", "rm -rf", "删除", "清空", "格式化", "重置");
        addIf(findings, lower, "payment", "Payment, transfer, trading, or purchase action",
                "pay", "transfer", "trade", "buy", "sell", "withdraw", "付款", "转账", "交易", "购买", "提现");
        addIf(findings, lower, "secret", "Secret, password, token, or private key handling",
                "password", "token", "secret", "api key", "private key", "密码", "密钥", "令牌", "token");
        addIf(findings, lower, "external_send", "Sends data to external services or public channels",
                "send to", "upload", "webhook", "post to", "发送到", "上传", "外发", "公开发布");
        String level = findings.length() >= 3 ? "high" : findings.length() == 2 ? "medium" : findings.length() == 1 ? "low" : "clean";
        return new JSONObject()
                .put("riskLevel", level)
                .put("findingCount", findings.length())
                .put("findings", findings);
    }

    private void addIf(JSONArray findings, String lower, String category, String message, String... needles) throws Exception {
        for (String needle : needles) {
            if (lower.contains(needle.toLowerCase(Locale.ROOT))) {
                findings.put(new JSONObject().put("category", category).put("message", message).put("match", needle));
                return;
            }
        }
    }

    private String checklistText(JSONArray checklist) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < checklist.length(); i++) {
            JSONObject item = checklist.optJSONObject(i);
            if (item != null) sb.append("- [ ] ").append(item.optString("text")).append("\n");
        }
        return sb.toString().trim();
    }

    private String formatPlanHtml(JSONObject out) {
        boolean zh = isZh();
        JSONObject risks = out.optJSONObject("risks");
        String body = HtmlOutputHelper.badge(out.optBoolean("demo", false) ? (zh ? "示例" : "Demo") : "LLM", "blue")
                + HtmlOutputHelper.keyValue(new String[][]{
                {zh ? "目标" : "Goal", out.optString("goal")},
                {zh ? "风险" : "Risk", risks != null ? levelName(risks.optString("riskLevel", "-"), zh) : "-"}
        })
                + sectionHtml(zh ? "执行步骤" : "Steps", planItemsHtml(out.optString("plan"), zh))
                + calloutHtml(zh ? "提醒" : "Note",
                zh ? "涉及删除、付款、外发数据或账号操作时，执行前请再次确认。" :
                        "Confirm again before deletion, payment, data sharing, or account changes.",
                risks != null && !"clean".equals(risks.optString("riskLevel")) ? "warn" : "");
        return HtmlOutputHelper.card(zh ? "流" : "FL", zh ? "工作流规划" : "Workflow Plan", body);
    }

    private String formatPlanText(JSONObject out) {
        boolean zh = isZh();
        StringBuilder sb = new StringBuilder();
        sb.append(zh ? "工作流规划" : "Workflow plan").append("\n");
        sb.append(zh ? "目标：" : "Goal: ").append(out.optString("goal")).append("\n");
        JSONObject risks = out.optJSONObject("risks");
        if (risks != null) sb.append(zh ? "风险：" : "Risk: ").append(levelName(risks.optString("riskLevel"), zh)).append("\n");
        List<String> lines = cleanPlanLines(out.optString("plan"), 10);
        sb.append(zh ? "\n执行步骤：" : "\nSteps:");
        for (int i = 0; i < lines.size(); i++) sb.append("\n").append(i + 1).append(". ").append(lines.get(i));
        return sb.toString();
    }

    private String formatChecklistHtml(JSONObject out) {
        List<String[]> rows = new ArrayList<>();
        JSONArray checklist = out.optJSONArray("checklist");
        if (checklist != null) {
            for (int i = 0; i < checklist.length(); i++) {
                JSONObject item = checklist.optJSONObject(i);
                if (item != null) rows.add(new String[]{String.valueOf(i + 1), item.optString("text")});
            }
        }
        boolean zh = isZh();
        return HtmlOutputHelper.card(zh ? "清" : "FL", zh ? "任务检查清单" : "Workflow Checklist",
                HtmlOutputHelper.table(new String[]{zh ? "序号" : "#", zh ? "事项" : "Item"}, rows));
    }

    private String formatRiskHtml(JSONObject out) {
        boolean zh = isZh();
        StringBuilder items = new StringBuilder();
        JSONArray findings = out.optJSONArray("findings");
        if (findings != null) {
            for (int i = 0; i < findings.length(); i++) {
                JSONObject f = findings.optJSONObject(i);
                if (f != null) items.append(itemHtml(
                        categoryName(f.optString("category"), zh),
                        (zh ? "命中：" : "Matched: ") + f.optString("match"),
                        messageName(f.optString("category"), f.optString("message"), zh)));
            }
        }
        String body = HtmlOutputHelper.badge(levelName(out.optString("riskLevel"), zh), color(out.optString("riskLevel")))
                + sectionHtml(zh ? "风险点" : "Risk points",
                items.length() == 0 ? HtmlOutputHelper.muted(zh ? "没有发现明显风险。" : "No obvious risk found.") : items.toString());
        return HtmlOutputHelper.card(zh ? "险" : "FL", zh ? "工作流风险检查" : "Workflow Risk Check", body);
    }

    private String color(String level) {
        if ("high".equals(level)) return "red";
        if ("medium".equals(level)) return "orange";
        if ("low".equals(level)) return "blue";
        return "green";
    }

    private String planItemsHtml(String plan, boolean zh) {
        List<String> lines = cleanPlanLines(plan, 10);
        if (lines.isEmpty()) return HtmlOutputHelper.muted(zh ? "没有生成可展示步骤。" : "No steps generated.");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            sb.append(itemHtml((zh ? "步骤 " : "Step ") + (i + 1), "", lines.get(i)));
        }
        return sb.toString();
    }

    private List<String> cleanPlanLines(String plan, int limit) {
        List<String> lines = new ArrayList<>();
        if (plan == null) return lines;
        for (String raw : plan.split("\\r?\\n")) {
            String line = raw.trim()
                    .replaceFirst("^#{1,6}\\s*", "")
                    .replaceFirst("^[-*]\\s*", "")
                    .replaceFirst("^\\d+[.)、]\\s*", "")
                    .replace("**", "")
                    .replace("`", "")
                    .trim();
            if (line.isEmpty()) continue;
            if (line.length() > 180) line = line.substring(0, 177) + "...";
            lines.add(line);
            if (lines.size() >= limit) break;
        }
        if (lines.isEmpty() && plan.trim().length() > 0) {
            String text = plan.replace("**", "").replace("`", "").trim();
            lines.add(text.length() > 180 ? text.substring(0, 177) + "..." : text);
        }
        return lines;
    }

    private String levelName(String level, boolean zh) {
        if (!zh) return level;
        if ("high".equals(level)) return "高";
        if ("medium".equals(level)) return "中";
        if ("low".equals(level)) return "低";
        if ("clean".equals(level)) return "未发现";
        return level;
    }

    private String categoryName(String category, boolean zh) {
        if (!zh) return category;
        if ("destructive".equals(category)) return "删除/清空";
        if ("payment".equals(category)) return "付款/交易";
        if ("secret".equals(category)) return "密钥/密码";
        if ("external_send".equals(category)) return "外发数据";
        return category;
    }

    private String messageName(String category, String fallback, boolean zh) {
        if (!zh) return fallback;
        if ("destructive".equals(category)) return "计划里包含删除、清空、格式化或重置相关动作。";
        if ("payment".equals(category)) return "计划里包含付款、交易、购买或提现相关动作。";
        if ("secret".equals(category)) return "计划里涉及密码、Token、密钥或私钥。";
        if ("external_send".equals(category)) return "计划里包含上传、发送到外部服务或公开发布。";
        return fallback;
    }

    private boolean isZh() {
        return Locale.getDefault().getLanguage().startsWith("zh");
    }

    private String sectionHtml(String title, String bodyHtml) {
        return "<div class='pg-section'><div class='pg-section-title'>" + escHtml(title)
                + "</div>" + bodyHtml + "</div>";
    }

    private String calloutHtml(String title, String text, String type) {
        String cls = "pg-callout";
        if ("warn".equals(type)) cls += " warn";
        if ("err".equals(type)) cls += " err";
        return "<div class='" + cls + "'><div class='pg-section-title'>" + escHtml(title)
                + "</div><div>" + escHtml(text) + "</div></div>";
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

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
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
