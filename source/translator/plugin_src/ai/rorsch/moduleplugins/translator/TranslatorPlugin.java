package ai.rorsch.moduleplugins.translator;

import ai.rorsch.pandagenie.module.runtime.HtmlOutputHelper;
import ai.rorsch.pandagenie.module.runtime.ModuleLlm;
import ai.rorsch.pandagenie.module.runtime.ModulePlugin;
import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class TranslatorPlugin implements ModulePlugin {

    private static final String API_URL = "https://api.mymemory.translated.net/get";
    private static final int TIMEOUT = 10_000;
    private static final int MAX_TEXT_LENGTH = 500;

    private static final Map<String, String[]> LANGUAGES = new LinkedHashMap<>();
    static {
        LANGUAGES.put("zh", new String[]{"中文", "Chinese"});
        LANGUAGES.put("en", new String[]{"英语", "English"});
        LANGUAGES.put("ja", new String[]{"日语", "Japanese"});
        LANGUAGES.put("ko", new String[]{"韩语", "Korean"});
        LANGUAGES.put("fr", new String[]{"法语", "French"});
        LANGUAGES.put("de", new String[]{"德语", "German"});
        LANGUAGES.put("es", new String[]{"西班牙语", "Spanish"});
        LANGUAGES.put("pt", new String[]{"葡萄牙语", "Portuguese"});
        LANGUAGES.put("ru", new String[]{"俄语", "Russian"});
        LANGUAGES.put("it", new String[]{"意大利语", "Italian"});
        LANGUAGES.put("ar", new String[]{"阿拉伯语", "Arabic"});
        LANGUAGES.put("th", new String[]{"泰语", "Thai"});
        LANGUAGES.put("vi", new String[]{"越南语", "Vietnamese"});
        LANGUAGES.put("nl", new String[]{"荷兰语", "Dutch"});
        LANGUAGES.put("pl", new String[]{"波兰语", "Polish"});
        LANGUAGES.put("tr", new String[]{"土耳其语", "Turkish"});
    }

    private static boolean isZh() {
        return Locale.getDefault().getLanguage().toLowerCase(Locale.ROOT).startsWith("zh");
    }

    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        try {
            JSONObject params = new JSONObject(paramsJson == null || paramsJson.trim().isEmpty() ? "{}" : paramsJson);
            switch (action) {
                case "translate":              return translate(context, params);
                case "translateWithLlm":       return translateWithLlm(context, params);
                case "detectLanguage":         return detectLanguage(params);
                case "getSupportedLanguages":  return getSupportedLanguages();
                case "openPage": {
                    JSONObject r = new JSONObject();
                    r.put("success", true);
                    r.put("output", "{}");
                    r.put("_openModule", true);
                    r.put("_displayText", isZh() ? "正在打开翻译助手..." : "Opening Translator...");
                    return r.toString();
                }
                default:
                    return error("Unsupported action: " + action);
            }
        } catch (Exception e) {
            String msg = e.getMessage();
            return error(msg != null && !msg.isEmpty() ? msg : e.getClass().getSimpleName());
        }
    }

    private String translate(Context context, JSONObject params) throws Exception {
        String text = params.optString("text", "").trim();
        if (text.isEmpty()) throw new IllegalArgumentException(isZh() ? "请提供要翻译的文本" : "text is required");

        text = extractJsonTextContent(text);

        if (text.length() > MAX_TEXT_LENGTH) {
            text = text.substring(0, MAX_TEXT_LENGTH);
        }

        String from = params.optString("from", "").trim().toLowerCase(Locale.ROOT);
        String toRawOriginal = params.optString("to", "").trim();

        if (from.isEmpty()) {
            from = detectLangCode(text);
        }

        if (shouldUseLlmForTarget(toRawOriginal, from)) {
            return translateWithLlm(context, params);
        }

        // Support batch: "to" can be comma-separated, e.g. "en,ja,ko" or "all"
        String[] targets = parseTargetLanguages(toRawOriginal.toLowerCase(Locale.ROOT), from);

        if (targets.length == 1) {
            return translateSingle(text, from, targets[0]);
        }
        return translateBatch(text, from, targets);
    }

    private String translateWithLlm(Context context, JSONObject params) throws Exception {
        String text = params.optString("text", "").trim();
        if (text.isEmpty()) throw new IllegalArgumentException(isZh() ? "请提供要翻译的文本" : "text is required");
        text = extractJsonTextContent(text);

        String from = params.optString("from", "").trim().toLowerCase(Locale.ROOT);
        if (from.isEmpty() || "auto".equals(from)) from = detectLangCode(text);
        String toRaw = params.optString("to", "").trim();
        TargetLanguage target = resolveLlmTarget(toRaw, from);
        String to = target.code;
        String tone = params.optString("tone", "").trim();
        String instruction = params.optString("instruction", "").trim();
        int maxInputChars = Math.max(500, Math.min(params.optInt("maxInputChars", 8000), 12000));
        String clipped = text.length() > maxInputChars ? text.substring(0, maxInputChars) : text;

        String fromName = getLangName(from);
        String toName = target.displayName;
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are PandaGenie Translator. Translate the source text accurately and naturally.\n")
                .append("Source language: ").append(fromName).append(" (").append(from).append(")\n")
                .append("Target language: ").append(target.promptName).append(" (").append(to).append(")\n")
                .append("Preserve names, numbers, URLs, code, and file paths. Return only the translated text.\n");
        if (target.llmOnly) {
            prompt.append("The requested target is a regional, dialect, or non-standard language label. Follow that requested variety instead of falling back to a generic language.\n");
        }
        if (!tone.isEmpty()) prompt.append("Tone/style: ").append(tone).append("\n");
        if (!instruction.isEmpty()) prompt.append("Extra instruction: ").append(instruction).append("\n");
        prompt.append("\nSOURCE TEXT:\n").append(clipped);

        int maxTokens = Math.max(128, Math.min(params.optInt("maxTokens", 1024), 1024));
        JSONObject request = new JSONObject()
                .put("action", "translator.translateWithLlm")
                .put("prompt", prompt.toString())
                .put("temperature", 0.2)
                .put("maxTokens", maxTokens);
        JSONObject resp = new JSONObject(ModuleLlm.completeJson(context, request.toString()));
        if (!resp.optBoolean("success", false)) {
            String msg = resp.optString("error", isZh() ? "智能翻译失败" : "LLM translation failed");
            throw new IllegalStateException(msg);
        }
        String translated = resp.optString("text", "").trim();

        JSONObject out = new JSONObject();
        out.put("originalText", text);
        out.put("translatedText", translated);
        out.put("from", from);
        out.put("to", to);
        out.put("fromName", fromName);
        out.put("toName", toName);
        out.put("source", "llm");
        out.put("inputChars", text.length());
        out.put("usedChars", clipped.length());
        out.put("truncated", clipped.length() < text.length());
        if (!tone.isEmpty()) out.put("tone", tone);
        if (!instruction.isEmpty()) out.put("instruction", instruction);

        String display = (isZh() ? "智能翻译完成" : "AI translation complete")
                + "\n" + fromName + " → " + toName
                + (out.optBoolean("truncated") ? "\n" + (isZh() ? "已按最大长度截取后翻译" : "Input was truncated before translation") : "")
                + "\n\n" + translated;
        String displayHtml = formatLlmTranslateHtml(text, translated, fromName, toName, out.optBoolean("truncated"));
        return successResponse(out, display, displayHtml);
    }

    private static final class TargetLanguage {
        final String code;
        final String displayName;
        final String promptName;
        final boolean llmOnly;

        TargetLanguage(String code, String displayName, String promptName, boolean llmOnly) {
            this.code = code;
            this.displayName = displayName;
            this.promptName = promptName;
            this.llmOnly = llmOnly;
        }
    }

    private boolean shouldUseLlmForTarget(String raw, String from) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty() || value.equalsIgnoreCase("all") || value.equals("*")) return false;
        String[] parts = value.split("[,;\\s/|]+");
        for (String part : parts) {
            if (part.trim().isEmpty()) continue;
            if (normalizeDialectLanguageCode(part) != null) return true;
            String direct = normalizeDirectLanguageCode(part);
            if (direct == null || !LANGUAGES.containsKey(direct)) return true;
        }
        return false;
    }

    private TargetLanguage resolveLlmTarget(String raw, String from) {
        String value = raw == null ? "" : raw.trim();
        String dialect = normalizeDialectLanguageCode(value);
        if (dialect != null) return dialectTarget(dialect, value);

        String direct = normalizeDirectLanguageCode(value);
        if (direct != null && LANGUAGES.containsKey(direct) && !direct.equals(from)) {
            String[] names = LANGUAGES.get(direct);
            String display = isZh() ? names[0] : names[1];
            return new TargetLanguage(direct, display, names[0] + " / " + names[1], false);
        }

        if (!value.isEmpty() && !value.equalsIgnoreCase("all") && !value.equals("*")) {
            return new TargetLanguage(value, value, value, true);
        }

        String fallback = from.equals("en") ? "zh" : "en";
        String[] names = LANGUAGES.get(fallback);
        String display = isZh() ? names[0] : names[1];
        return new TargetLanguage(fallback, display, names[0] + " / " + names[1], false);
    }

    private TargetLanguage dialectTarget(String code, String raw) {
        switch (code) {
            case "es-AR": return namedDialect(code, "阿根廷西班牙语", "Argentine Spanish");
            case "es-419": return namedDialect(code, "拉丁美洲西班牙语", "Latin American Spanish");
            case "es-MX": return namedDialect(code, "墨西哥西班牙语", "Mexican Spanish");
            case "pt-BR": return namedDialect(code, "巴西葡萄牙语", "Brazilian Portuguese");
            case "pt-PT": return namedDialect(code, "欧洲葡萄牙语", "European Portuguese");
            case "zh-Hant": return namedDialect(code, "繁体中文", "Traditional Chinese");
            case "zh-TW": return namedDialect(code, "台湾中文", "Taiwan Mandarin");
            case "yue": return namedDialect(code, "粤语", "Cantonese");
            case "en-US": return namedDialect(code, "美式英语", "American English");
            case "en-GB": return namedDialect(code, "英式英语", "British English");
            default: return new TargetLanguage(code, raw, raw, true);
        }
    }

    private TargetLanguage namedDialect(String code, String zhName, String enName) {
        return new TargetLanguage(code, isZh() ? zhName : enName, zhName + " / " + enName, true);
    }

    private String normalizeDialectLanguageCode(String raw) {
        if (raw == null) return null;
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        String compact = compactLanguageToken(raw);
        if (compact.isEmpty()) return null;
        if (compact.equals("esar") || compact.contains("argentin") || compact.contains("阿根廷")) return "es-AR";
        if (compact.equals("es419") || compact.contains("latinamericanspanish") || compact.contains("latamspanish")
                || compact.contains("拉美西班牙语") || compact.contains("拉丁美洲西班牙语")) return "es-419";
        if (compact.equals("esmx") || compact.contains("mexicanspanish") || compact.contains("墨西哥西班牙语")) return "es-MX";
        if (compact.equals("ptbr") || compact.contains("brazilianportuguese") || compact.contains("巴西葡萄牙语")) return "pt-BR";
        if (compact.equals("ptpt") || compact.contains("europeanportuguese") || compact.contains("葡萄牙葡萄牙语") || compact.contains("欧洲葡萄牙语")) return "pt-PT";
        if (compact.equals("zhhant") || compact.contains("traditionalchinese") || compact.contains("繁体中文") || compact.contains("繁體中文")) return "zh-Hant";
        if (compact.equals("zhtw") || compact.contains("taiwanmandarin") || compact.contains("taiwanchinese") || compact.contains("台湾中文") || compact.contains("臺灣中文")) return "zh-TW";
        if (compact.equals("yue") || compact.contains("cantonese") || compact.contains("粤语") || compact.contains("粵語") || compact.contains("广东话") || compact.contains("廣東話")) return "yue";
        if (compact.equals("enus") || compact.contains("americanenglish") || compact.contains("美式英语") || compact.contains("美式英文")) return "en-US";
        if (compact.equals("engb") || compact.contains("britishenglish") || compact.contains("英式英语") || compact.contains("英式英文")) return "en-GB";
        if (lower.contains("rioplatense")) return "es-AR";
        return null;
    }

    private String normalizeDirectLanguageCode(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.isEmpty()) return null;
        String lower = value.toLowerCase(Locale.ROOT);
        if (LANGUAGES.containsKey(lower)) return lower;
        for (Map.Entry<String, String[]> entry : LANGUAGES.entrySet()) {
            String[] names = entry.getValue();
            if (value.equals(names[0]) || lower.equals(names[1].toLowerCase(Locale.ROOT))) return entry.getKey();
        }
        switch (compactLanguageToken(value)) {
            case "cn":
            case "zhcn":
            case "chinese":
            case "mandarin":
            case "中文":
            case "汉语":
            case "普通话":
                return "zh";
            case "english":
            case "英文":
            case "英语":
                return "en";
            case "japanese":
            case "日文":
            case "日语":
                return "ja";
            case "korean":
            case "韩文":
            case "韩语":
                return "ko";
            case "french":
            case "法文":
            case "法语":
                return "fr";
            case "german":
            case "德文":
            case "德语":
                return "de";
            case "spanish":
            case "espanol":
            case "español":
            case "西语":
            case "西班牙语":
                return "es";
            case "portuguese":
            case "葡语":
            case "葡萄牙语":
                return "pt";
            case "russian":
            case "俄文":
            case "俄语":
                return "ru";
            case "italian":
            case "意大利语":
                return "it";
            case "arabic":
            case "阿拉伯语":
                return "ar";
            case "thai":
            case "泰语":
                return "th";
            case "vietnamese":
            case "越南语":
                return "vi";
            case "dutch":
            case "荷兰语":
                return "nl";
            case "polish":
            case "波兰语":
                return "pl";
            case "turkish":
            case "土耳其语":
                return "tr";
            default:
                return null;
        }
    }

    private static String compactLanguageToken(String raw) {
        return raw.trim()
                .toLowerCase(Locale.ROOT)
                .replace("-", "")
                .replace("_", "")
                .replace(" ", "")
                .replace("（", "")
                .replace("）", "")
                .replace("(", "")
                .replace(")", "");
    }

    private String[] parseTargetLanguages(String toRaw, String from) {
        if (toRaw.isEmpty()) {
            return new String[]{ from.equals("en") ? "zh" : "en" };
        }
        if (toRaw.equals("all") || toRaw.equals("*")) {
            java.util.List<String> all = new java.util.ArrayList<>();
            for (String code : LANGUAGES.keySet()) {
                if (!code.equals(from)) all.add(code);
            }
            return all.toArray(new String[0]);
        }
        // Split by comma, semicolon, space, slash, or pipe
        String[] parts = toRaw.split("[,;\\s/|]+");
        java.util.List<String> valid = new java.util.ArrayList<>();
        for (String p : parts) {
            String code = normalizeDirectLanguageCode(p.trim());
            if (code != null && !code.isEmpty() && LANGUAGES.containsKey(code) && !code.equals(from)) {
                valid.add(code);
            }
        }
        if (valid.isEmpty()) {
            return new String[]{ from.equals("en") ? "zh" : "en" };
        }
        return valid.toArray(new String[0]);
    }

    private String translateSingle(String text, String from, String to) throws Exception {
        String langPair = from + "|" + to;
        String url = API_URL + "?q=" + URLEncoder.encode(text, "UTF-8")
                + "&langpair=" + URLEncoder.encode(langPair, "UTF-8");

        JSONObject resp = new JSONObject(httpGet(url));
        JSONObject respData = resp.optJSONObject("responseData");
        if (respData == null) throw new IOException("API returned no data");

        String translated = respData.optString("translatedText", "");
        double matchScore = respData.optDouble("match", 0);

        if (translated.isEmpty() || translated.equalsIgnoreCase(text)) {
            String status = resp.optString("responseStatus", "");
            if (!"200".equals(status)) {
                throw new IOException("Translation failed: " + resp.optString("responseDetails", "unknown error"));
            }
        }

        String fromName = getLangName(from);
        String toName = getLangName(to);

        JSONObject out = new JSONObject();
        out.put("originalText", text);
        out.put("translatedText", translated);
        out.put("from", from);
        out.put("to", to);
        out.put("fromName", fromName);
        out.put("toName", toName);
        out.put("matchScore", matchScore);

        String display;
        if (isZh()) {
            display = "\uD83C\uDF10 翻译完成\n\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\n"
                    + "\u25B8 " + fromName + " \u2192 " + toName + "\n"
                    + "\u25B8 原文: " + (text.length() > 100 ? text.substring(0, 100) + "\u2026" : text) + "\n"
                    + "\u25B8 译文: " + translated;
        } else {
            display = "\uD83C\uDF10 Translation Complete\n\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\n"
                    + "\u25B8 " + fromName + " \u2192 " + toName + "\n"
                    + "\u25B8 Source: " + (text.length() > 100 ? text.substring(0, 100) + "\u2026" : text) + "\n"
                    + "\u25B8 Translation: " + translated;
        }

        String displayHtml = formatTranslateSingleHtml(text, translated, fromName, toName, matchScore);
        return successResponse(out, display, displayHtml);
    }

    private String translateBatch(String text, String from, String[] targets) throws Exception {
        String fromName = getLangName(from);
        JSONArray results = new JSONArray();
        StringBuilder display = new StringBuilder();
        if (isZh()) {
            display.append("\uD83C\uDF10 多语言翻译\n\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\n");
            display.append("\u25B8 原文 (").append(fromName).append("): ").append(text.length() > 60 ? text.substring(0, 60) + "\u2026" : text).append("\n\n");
        } else {
            display.append("\uD83C\uDF10 Multi-language Translation\n\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\n");
            display.append("\u25B8 Source (").append(fromName).append("): ").append(text.length() > 60 ? text.substring(0, 60) + "\u2026" : text).append("\n\n");
        }

        int success = 0, failed = 0;
        for (String to : targets) {
            try {
                String langPair = from + "|" + to;
                String url = API_URL + "?q=" + URLEncoder.encode(text, "UTF-8")
                        + "&langpair=" + URLEncoder.encode(langPair, "UTF-8");
                JSONObject resp = new JSONObject(httpGet(url));
                JSONObject respData = resp.optJSONObject("responseData");
                if (respData == null) {
                    failed++;
                    continue;
                }
                String translated = respData.optString("translatedText", "");
                if (translated.isEmpty()) {
                    failed++;
                    continue;
                }
                String toName = getLangName(to);
                JSONObject item = new JSONObject();
                item.put("to", to);
                item.put("toName", toName);
                item.put("translatedText", translated);
                results.put(item);
                display.append("\u25B8 ").append(toName).append(" (").append(to).append("): ").append(translated).append("\n");
                success++;
            } catch (Exception e) {
                failed++;
            }
        }

        if (failed > 0) {
            display.append("\n").append(isZh() ? "(\u26A0 " + failed + " 种语言翻译失败)" : "(\u26A0 " + failed + " language(s) failed)");
        }

        JSONObject out = new JSONObject();
        out.put("originalText", text);
        out.put("from", from);
        out.put("translations", results);
        out.put("successCount", success);
        out.put("failedCount", failed);

        String displayHtml = formatTranslateBatchHtml(text, fromName, results, success, failed);
        return successResponse(out, display.toString().trim(), displayHtml, success > 0);
    }

    private String detectLanguage(JSONObject params) throws Exception {
        String text = params.optString("text", "").trim();
        if (text.isEmpty()) throw new IllegalArgumentException(isZh() ? "请提供文本" : "text is required");

        String detected = detectLangCode(text);
        String langName = getLangName(detected);

        JSONObject out = new JSONObject();
        out.put("language", detected);
        out.put("languageName", langName);
        out.put("text", text.length() > 100 ? text.substring(0, 100) : text);

        String display;
        if (isZh()) {
            display = "\uD83D\uDD0D 语言检测: " + langName + " (" + detected + ")";
        } else {
            display = "\uD83D\uDD0D Language Detected: " + langName + " (" + detected + ")";
        }

        String displayHtml = formatDetectLanguageHtml(detected, langName, text);
        return successResponse(out, display, displayHtml);
    }

    private String getSupportedLanguages() throws Exception {
        JSONArray arr = new JSONArray();
        for (Map.Entry<String, String[]> e : LANGUAGES.entrySet()) {
            JSONObject lang = new JSONObject();
            lang.put("code", e.getKey());
            lang.put("name_zh", e.getValue()[0]);
            lang.put("name_en", e.getValue()[1]);
            arr.put(lang);
        }
        JSONObject out = new JSONObject();
        out.put("languages", arr);
        out.put("count", arr.length());

        StringBuilder sb = new StringBuilder();
        sb.append(isZh() ? "\uD83C\uDF10 支持的语言 (" + arr.length() + ")\n" : "\uD83C\uDF10 Supported Languages (" + arr.length() + ")\n");
        for (Map.Entry<String, String[]> e : LANGUAGES.entrySet()) {
            sb.append("\u25B8 ").append(e.getKey()).append(" - ").append(e.getValue()[0]).append(" / ").append(e.getValue()[1]).append("\n");
        }

        String displayHtml = formatSupportedLanguagesHtml();
        return successResponse(out, sb.toString().trim(), displayHtml);
    }

    private static String successResponse(JSONObject output, String displayText, String displayHtml) throws Exception {
        return successResponse(output, displayText, displayHtml, true);
    }

    private static String successResponse(JSONObject output, String displayText, String displayHtml, boolean success) throws Exception {
        JSONObject r = new JSONObject();
        r.put("success", success);
        r.put("output", output.toString());
        if (displayText != null && !displayText.isEmpty()) r.put("_displayText", displayText);
        if (displayHtml != null && !displayHtml.isEmpty()) r.put("_displayHtml", displayHtml);
        return r.toString();
    }

    private String formatTranslateSingleHtml(String source, String translated, String fromName, String toName, double matchScore) {
        String srcShow = source.length() > 500 ? source.substring(0, 500) + "\u2026" : source;
        String body = HtmlOutputHelper.keyValue(new String[][]{
                {isZh() ? "\u65b9\u5411" : "Direction", fromName + " \u2192 " + toName},
                {isZh() ? "\u539f\u6587" : "Source", srcShow},
                {isZh() ? "\u8bd1\u6587" : "Translation", translated},
                {isZh() ? "\u5339\u914d\u5ea6" : "Match", String.format(Locale.US, "%.2f", matchScore)}
        });
        return HtmlOutputHelper.card("\uD83C\uDF10", isZh() ? "\u7ffb\u8bd1\u5b8c\u6210" : "Translation complete", body);
    }

    private String formatTranslateBatchHtml(String originalText, String fromName, JSONArray results, int success, int failed) throws Exception {
        String head = HtmlOutputHelper.muted(isZh()
                ? ("\u539f\u6587 (" + fromName + "): " + (originalText.length() > 120 ? originalText.substring(0, 120) + "\u2026" : originalText))
                : ("Source (" + fromName + "): " + (originalText.length() > 120 ? originalText.substring(0, 120) + "\u2026" : originalText)));
        List<String[]> rows = new ArrayList<>();
        for (int i = 0; i < results.length(); i++) {
            JSONObject item = results.getJSONObject(i);
            rows.add(new String[]{
                    item.optString("toName", "") + " (" + item.optString("to", "") + ")",
                    item.optString("translatedText", "")
            });
        }
        String table = HtmlOutputHelper.table(
                new String[]{isZh() ? "\u76ee\u6807\u8bed\u8a00" : "Target", isZh() ? "\u8bd1\u6587" : "Translation"},
                rows);
        String footer = failed > 0
                ? HtmlOutputHelper.muted(isZh() ? ("\u26a0 " + failed + " \u79cd\u8bed\u8a00\u7ffb\u8bd1\u5931\u8d25") : ("\u26a0 " + failed + " language(s) failed"))
                : HtmlOutputHelper.successBadge();
        return HtmlOutputHelper.card("\uD83C\uDF10", isZh() ? "\u591a\u8bed\u8a00\u7ffb\u8bd1" : "Multi-language translation", head + table + footer);
    }

    private String formatLlmTranslateHtml(String source, String translated, String fromName, String toName, boolean truncated) {
        String srcShow = source.length() > 500 ? source.substring(0, 500) + "\u2026" : source;
        String body = HtmlOutputHelper.keyValue(new String[][]{
                {isZh() ? "\u65b9\u5411" : "Direction", fromName + " \u2192 " + toName},
                {isZh() ? "\u65b9\u5f0f" : "Mode", isZh() ? "\u667a\u80fd\u7ffb\u8bd1" : "AI translation"},
                {isZh() ? "\u539f\u6587" : "Source", srcShow},
                {isZh() ? "\u8bd1\u6587" : "Translation", translated}
        });
        if (truncated) body += HtmlOutputHelper.muted(isZh() ? "\u5185\u5bb9\u8f83\u957f\uff0c\u5df2\u6309\u6700\u5927\u957f\u5ea6\u622a\u53d6\u540e\u7ffb\u8bd1\u3002" : "Long input was truncated before translation.");
        return HtmlOutputHelper.card("\uD83C\uDF10", isZh() ? "\u667a\u80fd\u7ffb\u8bd1" : "AI Translation", body);
    }

    private String formatDetectLanguageHtml(String code, String langName, String textSample) {
        String preview = textSample.length() > 100 ? textSample.substring(0, 100) + "\u2026" : textSample;
        String body = HtmlOutputHelper.keyValue(new String[][]{
                {isZh() ? "\u8bed\u8a00" : "Language", langName + " (" + code + ")"},
                {isZh() ? "\u6587\u672c\u9884\u89c8" : "Preview", preview}
        });
        return HtmlOutputHelper.card("\uD83D\uDD0D", isZh() ? "\u8bed\u8a00\u68c0\u6d4b" : "Language detected", body);
    }

    private String formatSupportedLanguagesHtml() {
        List<String[]> rows = new ArrayList<>();
        for (Map.Entry<String, String[]> e : LANGUAGES.entrySet()) {
            String label = isZh() ? e.getValue()[0] : e.getValue()[1];
            rows.add(new String[]{e.getKey(), label});
        }
        String table = HtmlOutputHelper.table(
                new String[]{isZh() ? "\u4ee3\u7801" : "Code", isZh() ? "\u8bed\u8a00" : "Language"},
                rows);
        return HtmlOutputHelper.card("\uD83C\uDF10", isZh() ? "\u652f\u6301\u7684\u8bed\u8a00" : "Supported languages", table);
    }

    private static String firstNonEmptyJsonField(JSONObject o) {
        String s = o.optString("text", "").trim();
        if (!s.isEmpty()) return s;
        s = o.optString("output", "").trim();
        if (!s.isEmpty()) return s;
        return o.optString("content", "").trim();
    }

    private static String extractJsonTextContent(String text) {
        if (text == null || text.isEmpty()) return text;
        char c0 = text.charAt(0);
        if (c0 != '{' && c0 != '[') return text;
        try {
            if (c0 == '{') {
                JSONObject o = new JSONObject(text);
                String inner = firstNonEmptyJsonField(o);
                if (!inner.isEmpty()) return inner;
            } else {
                JSONArray a = new JSONArray(text);
                if (a.length() > 0 && a.get(0) instanceof JSONObject) {
                    String inner = firstNonEmptyJsonField(a.getJSONObject(0));
                    if (!inner.isEmpty()) return inner;
                }
            }
        } catch (Exception ignored) {
        }
        return text;
    }

    private static String detectLangCode(String text) {
        text = extractJsonTextContent(text);
        int cjk = 0, latin = 0, cyrillic = 0, arabic = 0, hangul = 0, kana = 0, thai = 0;
        for (int i = 0; i < Math.min(text.length(), 200); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) cjk++;
            else if (c >= 0x3040 && c <= 0x30FF) kana++;
            else if (c >= 0xAC00 && c <= 0xD7AF) hangul++;
            else if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) latin++;
            else if (c >= 0x0400 && c <= 0x04FF) cyrillic++;
            else if (c >= 0x0600 && c <= 0x06FF) arabic++;
            else if (c >= 0x0E00 && c <= 0x0E7F) thai++;
        }
        if (cjk > kana && cjk > hangul && cjk > latin) return "zh";
        if (kana > 0) return "ja";
        if (hangul > 0) return "ko";
        if (cyrillic > latin) return "ru";
        if (arabic > latin) return "ar";
        if (thai > latin) return "th";
        return "en";
    }

    private String getLangName(String code) {
        String[] names = LANGUAGES.get(code);
        if (names == null) return code;
        return isZh() ? names[0] : names[1];
    }

    private static String httpGet(String urlStr) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(TIMEOUT);
        conn.setReadTimeout(TIMEOUT);
        conn.setRequestProperty("User-Agent", "PandaGenie-Translator/1.0");
        try {
            int code = conn.getResponseCode();
            InputStream is = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code);
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    private static String error(String msg) throws Exception {
        return new JSONObject().put("success", false).put("error", msg).toString();
    }
}
