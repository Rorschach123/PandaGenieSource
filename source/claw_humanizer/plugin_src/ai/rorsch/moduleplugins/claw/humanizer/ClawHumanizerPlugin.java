package ai.rorsch.moduleplugins.claw.humanizer;

import ai.rorsch.pandagenie.module.runtime.HtmlOutputHelper;
import ai.rorsch.pandagenie.module.runtime.ModuleLlm;
import ai.rorsch.pandagenie.module.runtime.ModulePlugin;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ClawHumanizerPlugin implements ModulePlugin {
    private static final int MAX_INPUT_CHARS = 12000;

    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        JSONObject params = new JSONObject(emptyJson(paramsJson));
        try {
            if ("humanizeText".equals(action) || "makeNatural".equals(action)) {
                if ("makeNatural".equals(action) && !params.has("style")) params.put("style", "natural");
                return humanize(context, params);
            }
            if ("scoreText".equals(action)) return score(params);
            if ("openPage".equals(action)) {
                return new JSONObject().put("success", true).put("output", "{}").put("_openModule", true).toString();
            }
            return error("Unsupported action: " + action);
        } catch (Exception e) {
            return error(e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    private String humanize(Context context, JSONObject params) throws Exception {
        String text = params.optString("text", "").trim();
        if (text.isEmpty()) throw new IllegalArgumentException("text is required");
        if (text.length() > MAX_INPUT_CHARS) text = text.substring(0, MAX_INPUT_CHARS);

        String style = firstNonEmpty(params.optString("style", ""), "natural");
        String audience = params.optString("audience", "").trim();
        String language = firstNonEmpty(params.optString("language", ""), "zh");
        boolean preserveMeaning = !params.has("preserveMeaning") || params.optBoolean("preserveMeaning", true);
        int maxTokens = clamp(params.optInt("maxTokens", 768), 128, 1024);

        JSONObject before = analyze(text);
        String prompt = "Rewrite the text below so it sounds natural, specific, and human-written on a mobile screen.\n"
                + "Return only the rewritten text. Do not add a preface, explanation, markdown fence, or extra title.\n"
                + "Style: " + style + "\n"
                + "Output language: " + language + "\n"
                + (audience.isEmpty() ? "" : "Audience: " + audience + "\n")
                + (preserveMeaning ? "Preserve the original meaning, facts, numbers, names, and intent.\n" : "")
                + "Avoid generic AI wording, empty transitions, over-formal phrasing, and exaggerated claims.\n"
                + "Keep paragraphs short enough for phone reading.\n\n"
                + "Text:\n" + text;

        JSONObject request = new JSONObject()
                .put("action", "claw_humanizer.humanizeText")
                .put("prompt", prompt)
                .put("temperature", 0.35)
                .put("maxTokens", maxTokens);
        JSONObject llm = new JSONObject(ModuleLlm.completeJson(context, request.toString()));
        if (!llm.optBoolean("success", false)) {
            throw new IllegalStateException(llm.optString("error", "LLM request failed"));
        }
        String rewritten = llm.optString("text", "").trim();
        if (rewritten.isEmpty()) throw new IllegalStateException("LLM returned empty text");

        JSONObject after = analyze(rewritten);
        JSONObject out = new JSONObject()
                .put("module", "claw_humanizer")
                .put("style", style)
                .put("audience", audience)
                .put("language", language)
                .put("preserveMeaning", preserveMeaning)
                .put("originalText", text)
                .put("rewrittenText", rewritten)
                .put("before", before)
                .put("after", after)
                .put("usage", llm.optJSONObject("usage"));
        return ok(out, rewritten, formatRewriteHtml(out));
    }

    private String score(JSONObject params) throws Exception {
        String text = params.optString("text", "").trim();
        if (text.isEmpty()) throw new IllegalArgumentException("text is required");
        JSONObject out = analyze(text)
                .put("module", "claw_humanizer")
                .put("text", text);
        return ok(out, formatScoreText(out), formatScoreHtml(out));
    }

    private JSONObject analyze(String text) throws Exception {
        String normalized = text == null ? "" : text.trim();
        int chars = normalized.length();
        int sentences = countSentences(normalized);
        int words = countWords(normalized);
        int longSentences = countLongSentences(normalized);
        JSONArray markers = templatedMarkers(normalized);

        int penalty = 0;
        if (sentences > 0) {
            int avg = chars / Math.max(1, sentences);
            if (avg > 90) penalty += 20;
            else if (avg > 60) penalty += 10;
        }
        penalty += Math.min(25, longSentences * 7);
        penalty += Math.min(30, markers.length() * 8);
        if (normalized.contains("!!") || normalized.contains("！！")) penalty += 6;
        if (normalized.length() > 0 && normalized.split("\\n\\s*\\n").length <= 1 && chars > 700) penalty += 10;
        int naturalness = Math.max(0, 100 - penalty);

        return new JSONObject()
                .put("chars", chars)
                .put("words", words)
                .put("sentences", sentences)
                .put("longSentences", longSentences)
                .put("templatedMarkers", markers)
                .put("naturalnessScore", naturalness)
                .put("readability", label(naturalness));
    }

    private int countSentences(String text) {
        if (text.isEmpty()) return 0;
        String[] parts = text.split("[.!?。！？]+");
        int count = 0;
        for (String p : parts) if (!p.trim().isEmpty()) count++;
        return Math.max(1, count);
    }

    private int countWords(String text) {
        if (text.isEmpty()) return 0;
        String compact = text.replaceAll("[\\p{Punct}，。！？、；：]", " ").trim();
        if (compact.isEmpty()) return 0;
        int whitespaceWords = compact.split("\\s+").length;
        int cjk = 0;
        for (int i = 0; i < compact.length(); i++) {
            char c = compact.charAt(i);
            if ((c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3040 && c <= 0x30FF) || (c >= 0xAC00 && c <= 0xD7AF)) {
                cjk++;
            }
        }
        return Math.max(whitespaceWords, cjk);
    }

    private int countLongSentences(String text) {
        String[] parts = text.split("[.!?。！？]+");
        int count = 0;
        for (String p : parts) {
            if (p.trim().length() > 110) count++;
        }
        return count;
    }

    private JSONArray templatedMarkers(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        String[] markers = new String[]{
                "as an ai", "in conclusion", "it is worth noting", "it should be noted",
                "overall,", "综上所述", "值得注意的是", "需要注意的是", "作为一个ai", "作为 AI",
                "毋庸置疑", "众所周知", "在当今快节奏"
        };
        JSONArray arr = new JSONArray();
        for (String marker : markers) {
            if (lower.contains(marker.toLowerCase(Locale.ROOT))) arr.put(marker);
        }
        return arr;
    }

    private String label(int score) {
        if (score >= 85) return "natural";
        if (score >= 70) return "mostly_natural";
        if (score >= 50) return "needs_polish";
        return "stiff_or_templated";
    }

    private String formatRewriteHtml(JSONObject out) {
        JSONObject before = out.optJSONObject("before");
        JSONObject after = out.optJSONObject("after");
        String body = HtmlOutputHelper.badge("LLM", "blue")
                + HtmlOutputHelper.keyValue(new String[][]{
                {"Style", out.optString("style", "natural")},
                {"Language", out.optString("language", "zh")},
                {"Before", before != null ? before.optInt("naturalnessScore") + "/100" : "-"},
                {"After", after != null ? after.optInt("naturalnessScore") + "/100" : "-"}
        })
                + HtmlOutputHelper.p(out.optString("rewrittenText", ""));
        return HtmlOutputHelper.card("TXT", "Claw Humanizer", body);
    }

    private String formatScoreHtml(JSONObject out) {
        String body = HtmlOutputHelper.badge(out.optString("readability", "-"), colorFor(out.optInt("naturalnessScore")))
                + HtmlOutputHelper.keyValue(new String[][]{
                {"Score", out.optInt("naturalnessScore") + "/100"},
                {"Chars", String.valueOf(out.optInt("chars"))},
                {"Sentences", String.valueOf(out.optInt("sentences"))},
                {"Long sentences", String.valueOf(out.optInt("longSentences"))},
                {"Template markers", String.valueOf(out.optJSONArray("templatedMarkers") != null ? out.optJSONArray("templatedMarkers").length() : 0)}
        });
        return HtmlOutputHelper.card("TXT", "Claw Humanizer Score", body);
    }

    private String formatScoreText(JSONObject out) {
        List<String> markers = new ArrayList<>();
        JSONArray arr = out.optJSONArray("templatedMarkers");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) markers.add(arr.optString(i));
        }
        return "Naturalness: " + out.optInt("naturalnessScore") + "/100"
                + "\nReadability: " + out.optString("readability")
                + "\nLong sentences: " + out.optInt("longSentences")
                + "\nTemplate markers: " + (markers.isEmpty() ? "-" : join(markers, ", "));
    }

    private String colorFor(int score) {
        if (score >= 85) return "green";
        if (score >= 70) return "blue";
        if (score >= 50) return "orange";
        return "red";
    }

    private String join(List<String> values, String separator) {
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (sb.length() > 0) sb.append(separator);
            sb.append(value);
        }
        return sb.toString();
    }

    private String firstNonEmpty(String a, String b) {
        return a != null && !a.trim().isEmpty() ? a.trim() : b;
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

