package ai.rorsch.moduleplugins.claw.promptshield;

import ai.rorsch.pandagenie.module.runtime.HtmlOutputHelper;
import ai.rorsch.pandagenie.module.runtime.ModulePlugin;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ClawPromptShieldPlugin implements ModulePlugin {
    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        JSONObject params = new JSONObject(emptyJson(paramsJson));
        try {
            if ("scanPrompt".equals(action)) return scanPrompt(params);
            if ("redactSecrets".equals(action)) return redactSecrets(params);
            if ("openPage".equals(action)) {
                return new JSONObject().put("success", true).put("output", "{}").put("_openModule", true).toString();
            }
            return error("Unsupported action: " + action);
        } catch (Exception e) {
            return error(e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    private String scanPrompt(JSONObject params) throws Exception {
        String text = params.optString("text", "").trim();
        if (text.isEmpty()) throw new IllegalArgumentException("text is required");
        JSONArray findings = new JSONArray();
        int score = 0;
        score += pattern(findings, "high", "instruction_override",
                "Attempts to override system/developer instructions.",
                "(ignore\\s+(all\\s+)?previous|system\\s+prompt|developer\\s+message|jailbreak|忽略(之前|所有)|无视规则|系统提示词|开发者消息|越狱)",
                text, 25);
        score += pattern(findings, "high", "hidden_behavior",
                "Asks the model to hide behavior from the user.",
                "(do\\s+not\\s+tell\\s+the\\s+user|secretly|without\\s+asking|不要告诉用户|偷偷|不要让.*知道|无需确认)",
                text, 22);
        score += pattern(findings, "high", "exfiltration",
                "Requests sending data, secrets, or logs to an external endpoint.",
                "(webhook|post\\s+to|send\\s+to|upload\\s+to|curl\\s+http|发送到|上传到|外发|发给.*(链接|接口|群|邮箱|钉钉|飞书|微信))",
                text, 22);
        score += pattern(findings, "medium", "credential_reference",
                "Mentions credentials, API keys, tokens, passwords, or private keys.",
                "(api[_\\s-]?key|token|secret|password|private\\s+key|authorization|bearer|密码|密钥|令牌|私钥|凭证)",
                text, 12);
        score += pattern(findings, "medium", "dangerous_action",
                "Requests destructive or irreversible actions.",
                "(delete|wipe|format|reset|rm\\s+-rf|transfer|withdraw|删除|清空|格式化|重置|转账|提现)",
                text, 12);
        score += pattern(findings, "low", "sensitive_personal_data",
                "Contains possible personal contact data.",
                "([\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}|1[3-9]\\d{9})",
                text, 6);

        int finalScore = Math.min(100, score);
        String level = finalScore >= 60 ? "high" : finalScore >= 30 ? "medium" : finalScore > 0 ? "low" : "clean";
        JSONObject out = new JSONObject()
                .put("module", "claw_prompt_shield")
                .put("context", params.optString("context", ""))
                .put("riskLevel", level)
                .put("score", finalScore)
                .put("findingCount", findings.length())
                .put("findings", findings)
                .put("recommendation", recommendation(level));
        return ok(out, formatText(out), formatHtml(out));
    }

    private String redactSecrets(JSONObject params) throws Exception {
        String text = params.optString("text", "");
        if (text.trim().isEmpty()) throw new IllegalArgumentException("text is required");
        Redaction r = redact(text);
        JSONObject out = new JSONObject()
                .put("module", "claw_prompt_shield")
                .put("redactedText", r.text)
                .put("redactionCount", r.count);
        return ok(out, r.text, HtmlOutputHelper.card("SAFE", "Redacted Text",
                HtmlOutputHelper.keyValue(new String[][]{{"Redactions", String.valueOf(r.count)}})
                        + HtmlOutputHelper.p(r.text)));
    }

    private int pattern(JSONArray findings, String severity, String category, String message,
                        String regex, String text, int weight) throws Exception {
        Matcher m = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.MULTILINE).matcher(text);
        JSONArray samples = new JSONArray();
        int count = 0;
        while (m.find() && count < 20) {
            if (samples.length() < 3) samples.put(snippet(text, m.start(), m.end()));
            count++;
        }
        if (count == 0) return 0;
        findings.put(new JSONObject()
                .put("severity", severity)
                .put("category", category)
                .put("message", message)
                .put("count", count)
                .put("samples", samples));
        return weight + Math.min(weight, (count - 1) * Math.max(1, weight / 5));
    }

    private Redaction redact(String text) {
        Redaction r = new Redaction(text, 0);
        r = replace(r, "(?i)(bearer\\s+)[A-Za-z0-9._\\-]{16,}", "$1[REDACTED_TOKEN]");
        r = replace(r, "(?i)(api[_\\s-]?key\\s*[:=]\\s*)[A-Za-z0-9._\\-]{12,}", "$1[REDACTED_API_KEY]");
        r = replace(r, "(?i)(token\\s*[:=]\\s*)[A-Za-z0-9._\\-]{12,}", "$1[REDACTED_TOKEN]");
        r = replace(r, "(?i)(password\\s*[:=]\\s*)\\S{6,}", "$1[REDACTED_PASSWORD]");
        r = replace(r, "[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}", "[REDACTED_EMAIL]");
        r = replace(r, "\\b1[3-9]\\d{9}\\b", "[REDACTED_PHONE]");
        r = replace(r, "\\b[A-Za-z0-9_\\-]{24,}\\b", "[REDACTED_SECRET]");
        return r;
    }

    private Redaction replace(Redaction input, String regex, String replacement) {
        Matcher m = Pattern.compile(regex).matcher(input.text);
        int count = 0;
        while (m.find()) count++;
        if (count == 0) return input;
        return new Redaction(m.replaceAll(replacement), input.count + count);
    }

    private String recommendation(String level) {
        if ("high".equals(level)) return "Do not run or share this prompt before removing the risky instruction.";
        if ("medium".equals(level)) return "Review highlighted lines and redact secrets before use.";
        if ("low".equals(level)) return "Low-risk signals found. Check whether they are expected.";
        return "No prompt-security risk was detected by the local scanner.";
    }

    private String formatText(JSONObject out) {
        StringBuilder sb = new StringBuilder("Prompt risk: ")
                .append(out.optString("riskLevel"))
                .append(" (")
                .append(out.optInt("score"))
                .append("/100)");
        JSONArray findings = out.optJSONArray("findings");
        if (findings != null) {
            for (int i = 0; i < findings.length(); i++) {
                JSONObject f = findings.optJSONObject(i);
                if (f != null) sb.append("\n- ").append(f.optString("category")).append(": ").append(f.optString("message"));
            }
        }
        return sb.append("\n").append(out.optString("recommendation")).toString();
    }

    private String formatHtml(JSONObject out) {
        List<String[]> rows = new ArrayList<>();
        JSONArray findings = out.optJSONArray("findings");
        if (findings != null) {
            for (int i = 0; i < findings.length(); i++) {
                JSONObject f = findings.optJSONObject(i);
                if (f != null) rows.add(new String[]{f.optString("severity"), f.optString("category"), String.valueOf(f.optInt("count")), firstSample(f)});
            }
        }
        String body = HtmlOutputHelper.badge(out.optString("riskLevel"), color(out.optString("riskLevel")))
                + HtmlOutputHelper.keyValue(new String[][]{
                {"Score", out.optInt("score") + "/100"},
                {"Findings", String.valueOf(out.optInt("findingCount"))}
        })
                + HtmlOutputHelper.table(new String[]{"Severity", "Category", "Count", "Sample"}, rows)
                + HtmlOutputHelper.muted(out.optString("recommendation"));
        return HtmlOutputHelper.card("SAFE", "Claw Prompt Shield", body);
    }

    private String firstSample(JSONObject f) {
        JSONArray samples = f.optJSONArray("samples");
        return samples != null && samples.length() > 0 ? samples.optString(0) : "-";
    }

    private String snippet(String text, int start, int end) {
        int from = Math.max(0, start - 50);
        int to = Math.min(text.length(), end + 80);
        String s = text.substring(from, to).replaceAll("\\s+", " ").trim();
        return s.length() > 150 ? s.substring(0, 147) + "..." : s;
    }

    private String color(String level) {
        if ("high".equals(level)) return "red";
        if ("medium".equals(level)) return "orange";
        if ("low".equals(level)) return "blue";
        return "green";
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

    private static class Redaction {
        final String text;
        final int count;

        Redaction(String text, int count) {
            this.text = text;
            this.count = count;
        }
    }
}

