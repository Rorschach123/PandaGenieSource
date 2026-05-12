package ai.rorsch.moduleplugins.claw.skillvetter;

import ai.rorsch.pandagenie.module.runtime.HtmlOutputHelper;
import ai.rorsch.pandagenie.module.runtime.ModulePlugin;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ClawSkillVetterPlugin implements ModulePlugin {
    private static final int MAX_BYTES = 512 * 1024;

    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        JSONObject params = new JSONObject(emptyJson(paramsJson));
        try {
            if ("vetText".equals(action) || "extractFindings".equals(action)) {
                String text = firstNonEmpty(params.optString("text", ""), params.optString("skillText", ""));
                return vet("text", params.optString("name", "inline text"), text);
            }
            if ("vetFile".equals(action)) {
                String path = params.optString("path", "").trim();
                if (path.isEmpty()) throw new IllegalArgumentException("path is required");
                return vet("file", path, readFile(path));
            }
            if ("vetUrl".equals(action)) {
                String url = params.optString("url", "").trim();
                if (url.isEmpty()) throw new IllegalArgumentException("url is required");
                return vet("url", url, readUrl(url));
            }
            if ("openPage".equals(action)) {
                return new JSONObject().put("success", true).put("output", "{}").put("_openModule", true).toString();
            }
            return error("Unsupported action: " + action);
        } catch (Exception e) {
            return error(e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    private String vet(String sourceType, String sourceName, String text) throws Exception {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("No skill text to scan");
        }
        JSONArray findings = new JSONArray();
        int score = 0;

        score += addFinding(findings, "critical", "destructive_shell",
                "Destructive shell command or forced reset pattern.",
                "(rm\\s+-rf\\s+[/~*]|Remove-Item\\s+[^\\n]*(?:-Recurse|-r)\\s+[^\\n]*(?:-Force|-f)|del\\s+/[fqsa]|format\\s+[a-z]:|mkfs\\.|diskpart|git\\s+reset\\s+--hard)",
                text, 30);
        score += addFinding(findings, "critical", "secret_exfiltration",
                "Network call close to environment variables, tokens, passwords, or authorization headers.",
                "((curl|wget|Invoke-WebRequest|fetch\\s*\\(|requests\\.)[^\\n]{0,180}(env|api[_-]?key|token|secret|password|authorization|bearer))",
                text, 28);
        score += addFinding(findings, "high", "download_and_execute",
                "Download-and-execute or encoded PowerShell execution pattern.",
                "(curl\\s+[^\\n|;]+\\|\\s*(sh|bash)|wget\\s+[^\\n|;]+\\|\\s*(sh|bash)|Invoke-Expression|\\biex\\b|powershell\\s+[^\\n]*(EncodedCommand|-enc))",
                text, 22);
        score += addFinding(findings, "high", "persistence",
                "Persistence or auto-start mechanism.",
                "(crontab|schtasks|launchctl|systemctl\\s+enable|startup\\s+folder|RunOnce|HKCU\\\\Software\\\\Microsoft\\\\Windows\\\\CurrentVersion\\\\Run)",
                text, 20);
        score += addFinding(findings, "high", "hidden_instruction",
                "Hidden instruction, prompt-injection, or instruction override language.",
                "(ignore\\s+(all\\s+)?previous\\s+instructions|system\\s+prompt|developer\\s+message|jailbreak|prompt\\s+injection|do\\s+not\\s+tell\\s+the\\s+user)",
                text, 18);
        score += addFinding(findings, "medium", "credential_file_access",
                "Credential files or broad secret search pattern.",
                "(cat\\s+~/.ssh|\\.env|id_rsa|Get-ChildItem\\s+[^\\n]*(Recurse)[^\\n]*(secret|token|key)|find\\s+[^\\n]*(secret|token|key))",
                text, 12);
        score += addFinding(findings, "medium", "privilege_change",
                "Privilege escalation or permission-changing command.",
                "(sudo\\s+|chmod\\s+\\+x|setfacl|chown\\s+|runas\\s+)",
                text, 10);
        score += addFinding(findings, "medium", "external_network",
                "External network endpoint or webhook reference.",
                "(https?://|webhook|ngrok|pastebin|gist\\.github|raw\\.githubusercontent)",
                text, 8);
        score += addFinding(findings, "low", "shell_execution",
                "General shell/process execution primitive.",
                "(bash\\s+-c|sh\\s+-c|powershell|cmd\\.exe|subprocess|ProcessBuilder|Runtime\\.getRuntime\\(\\)\\.exec)",
                text, 5);
        score += addFinding(findings, "low", "dynamic_code",
                "Dynamic eval/exec or base64 decoding pattern.",
                "(base64\\s+-d|FromBase64String|eval\\s*\\(|exec\\s*\\()",
                text, 5);

        int finalScore = Math.min(100, score);
        String level = riskLevel(finalScore, findings.length());
        JSONObject out = new JSONObject()
                .put("module", "claw_skill_vetter")
                .put("sourceType", sourceType)
                .put("sourceName", sourceName)
                .put("bytesScanned", text.getBytes(StandardCharsets.UTF_8).length)
                .put("riskLevel", level)
                .put("score", finalScore)
                .put("findingCount", findings.length())
                .put("findings", findings)
                .put("recommendation", recommendation(level, findings.length()));
        return ok(out, formatText(out), formatHtml(out));
    }

    private int addFinding(JSONArray findings, String severity, String category, String message,
                           String regex, String text, int weight) throws Exception {
        Matcher matcher = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.MULTILINE).matcher(text);
        JSONArray samples = new JSONArray();
        int count = 0;
        while (matcher.find() && count < 20) {
            if (samples.length() < 3) {
                samples.put(new JSONObject()
                        .put("line", lineOf(text, matcher.start()))
                        .put("snippet", snippet(text, matcher.start(), matcher.end())));
            }
            count++;
        }
        if (count == 0) return 0;

        findings.put(new JSONObject()
                .put("severity", severity)
                .put("category", category)
                .put("message", message)
                .put("count", count)
                .put("samples", samples));
        int bonus = Math.min(weight, Math.max(0, count - 1) * Math.max(1, weight / 5));
        return weight + bonus;
    }

    private String readFile(String path) throws Exception {
        File file = new File(path);
        if (!file.exists() || !file.isFile()) throw new IllegalArgumentException("File not found: " + path);
        try (InputStream in = new FileInputStream(file)) {
            return new String(readLimited(in), StandardCharsets.UTF_8);
        }
    }

    private String readUrl(String value) throws Exception {
        URL url = new URL(value);
        String protocol = url.getProtocol().toLowerCase(Locale.ROOT);
        if (!"http".equals(protocol) && !"https".equals(protocol)) {
            throw new IllegalArgumentException("Only http/https URLs are supported");
        }
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("User-Agent", "PandaGenie-ClawSkillVetter/1.0");
        try (InputStream in = conn.getInputStream()) {
            return new String(readLimited(in), StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
        }
    }

    private byte[] readLimited(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buf)) >= 0) {
            if (total + n > MAX_BYTES) {
                out.write(buf, 0, MAX_BYTES - total);
                break;
            }
            out.write(buf, 0, n);
            total += n;
        }
        return out.toByteArray();
    }

    private String riskLevel(int score, int findingCount) {
        if (score >= 70) return "critical";
        if (score >= 40) return "high";
        if (score >= 18) return "medium";
        if (findingCount > 0) return "low";
        return "clean";
    }

    private String recommendation(String level, int findingCount) {
        if ("critical".equals(level)) return "Do not install or run this skill until the critical findings are removed and reviewed.";
        if ("high".equals(level)) return "Review manually before running. Remove auto-execution, persistence, and secret-handling behavior.";
        if ("medium".equals(level)) return "Review the highlighted lines and keep only behavior required for the skill's stated purpose.";
        if (findingCount > 0) return "Low-risk signals found. Check whether each match is expected.";
        return "No risky pattern was detected by the local scanner.";
    }

    private String formatText(JSONObject out) {
        StringBuilder sb = new StringBuilder();
        sb.append("Claw Skill Vetter: ")
                .append(out.optString("riskLevel"))
                .append(" (")
                .append(out.optInt("score"))
                .append("/100), findings: ")
                .append(out.optInt("findingCount"));
        JSONArray findings = out.optJSONArray("findings");
        if (findings != null) {
            for (int i = 0; i < findings.length(); i++) {
                JSONObject f = findings.optJSONObject(i);
                if (f == null) continue;
                sb.append("\n- ")
                        .append(f.optString("severity"))
                        .append(" / ")
                        .append(f.optString("category"))
                        .append(": ")
                        .append(f.optString("message"));
            }
        }
        sb.append("\n").append(out.optString("recommendation"));
        return sb.toString();
    }

    private String formatHtml(JSONObject out) {
        JSONArray findings = out.optJSONArray("findings");
        String level = out.optString("riskLevel", "clean");
        String body = HtmlOutputHelper.badge(level, colorFor(level))
                + HtmlOutputHelper.keyValue(new String[][]{
                {"Source", out.optString("sourceName", "-")},
                {"Score", out.optInt("score") + "/100"},
                {"Findings", String.valueOf(out.optInt("findingCount"))},
                {"Bytes", String.valueOf(out.optInt("bytesScanned"))}
        });
        if (findings == null || findings.length() == 0) {
            body += HtmlOutputHelper.muted(out.optString("recommendation"));
            return HtmlOutputHelper.card("SEC", "Claw Skill Vetter", body);
        }

        List<String[]> rows = new ArrayList<>();
        for (int i = 0; i < findings.length(); i++) {
            JSONObject f = findings.optJSONObject(i);
            if (f == null) continue;
            rows.add(new String[]{
                    f.optString("severity"),
                    f.optString("category"),
                    String.valueOf(f.optInt("count")),
                    sampleText(f.optJSONArray("samples"))
            });
        }
        body += HtmlOutputHelper.table(new String[]{"Severity", "Category", "Count", "Sample"},
                rows);
        body += HtmlOutputHelper.muted(out.optString("recommendation"));
        return HtmlOutputHelper.card("SEC", "Claw Skill Vetter", body);
    }

    private String sampleText(JSONArray samples) {
        if (samples == null || samples.length() == 0) return "-";
        JSONObject s = samples.optJSONObject(0);
        if (s == null) return "-";
        return "L" + s.optInt("line") + ": " + s.optString("snippet");
    }

    private String colorFor(String level) {
        if ("critical".equals(level) || "high".equals(level)) return "red";
        if ("medium".equals(level)) return "orange";
        if ("low".equals(level)) return "blue";
        return "green";
    }

    private int lineOf(String text, int pos) {
        int line = 1;
        int end = Math.min(pos, text.length());
        for (int i = 0; i < end; i++) {
            if (text.charAt(i) == '\n') line++;
        }
        return line;
    }

    private String snippet(String text, int start, int end) {
        int from = Math.max(0, start - 60);
        int to = Math.min(text.length(), end + 100);
        String s = text.substring(from, to).replaceAll("\\s+", " ").trim();
        if (s.length() > 180) s = s.substring(0, 177) + "...";
        return s;
    }

    private String firstNonEmpty(String a, String b) {
        return a != null && !a.trim().isEmpty() ? a : (b == null ? "" : b);
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
