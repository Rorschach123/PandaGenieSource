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
        if (goal.isEmpty()) throw new IllegalArgumentException("goal is required");
        String constraints = params.optString("constraints", "").trim();
        String modules = params.optString("availableModules", "").trim();
        String language = params.optString("language", "zh").trim();
        int maxTokens = clamp(params.optInt("maxTokens", 768), 128, 1024);
        String prompt = "You are a mobile workflow planner inside PandaGenie.\n"
                + "Create a concise, executable workflow for a phone user. Do not claim any step has already run.\n"
                + "Include: goal summary, ordered steps, suggested PandaGenie modules/actions if helpful, checkpoints, and risks.\n"
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
        return ok(out, plan, formatPlanHtml(out));
    }

    private String makeChecklist(JSONObject params) throws Exception {
        String planText = params.optString("planText", "").trim();
        if (planText.isEmpty()) throw new IllegalArgumentException("planText is required");
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
                .put("count", checklist.length())
                .put("checklist", checklist);
        return ok(out, checklistText(checklist), formatChecklistHtml(out));
    }

    private String riskCheckPlan(JSONObject params) throws Exception {
        String planText = params.optString("planText", "").trim();
        if (planText.isEmpty()) throw new IllegalArgumentException("planText is required");
        JSONObject out = localRiskCheck(planText)
                .put("module", "claw_workflow_planner")
                .put("action", "riskCheckPlan");
        return ok(out, "Risk level: " + out.optString("riskLevel"), formatRiskHtml(out));
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
        JSONObject risks = out.optJSONObject("risks");
        String body = HtmlOutputHelper.badge("LLM", "blue")
                + HtmlOutputHelper.keyValue(new String[][]{
                {"Goal", out.optString("goal")},
                {"Risk", risks != null ? risks.optString("riskLevel", "-") : "-"}
        })
                + HtmlOutputHelper.p(out.optString("plan"));
        return HtmlOutputHelper.card("FLOW", "Claw Workflow Planner", body);
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
        return HtmlOutputHelper.card("FLOW", "Workflow Checklist",
                HtmlOutputHelper.table(new String[]{"#", "Item"}, rows));
    }

    private String formatRiskHtml(JSONObject out) {
        List<String[]> rows = new ArrayList<>();
        JSONArray findings = out.optJSONArray("findings");
        if (findings != null) {
            for (int i = 0; i < findings.length(); i++) {
                JSONObject f = findings.optJSONObject(i);
                if (f != null) rows.add(new String[]{f.optString("category"), f.optString("match"), f.optString("message")});
            }
        }
        String body = HtmlOutputHelper.badge(out.optString("riskLevel"), color(out.optString("riskLevel")))
                + HtmlOutputHelper.table(new String[]{"Category", "Match", "Meaning"}, rows);
        return HtmlOutputHelper.card("FLOW", "Workflow Risk Check", body);
    }

    private String color(String level) {
        if ("high".equals(level)) return "red";
        if ("medium".equals(level)) return "orange";
        if ("low".equals(level)) return "blue";
        return "green";
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

