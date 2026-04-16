package ai.rorsch.moduleplugins.translator;

import ai.rorsch.pandagenie.module.runtime.ModulePlugin;
import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
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
                case "translate":              return translate(params);
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

    private String translate(JSONObject params) throws Exception {
        String text = params.optString("text", "").trim();
        if (text.isEmpty()) throw new IllegalArgumentException(isZh() ? "请提供要翻译的文本" : "text is required");

        if (text.length() > MAX_TEXT_LENGTH) {
            text = text.substring(0, MAX_TEXT_LENGTH);
        }

        String from = params.optString("from", "").trim().toLowerCase(Locale.ROOT);
        String to = params.optString("to", "").trim().toLowerCase(Locale.ROOT);

        if (from.isEmpty()) {
            from = detectLangCode(text);
        }
        if (to.isEmpty()) {
            to = from.equals("en") ? "zh" : "en";
        }

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

        JSONObject r = new JSONObject();
        r.put("success", true);
        r.put("output", out.toString());
        r.put("_displayText", display);
        return r.toString();
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

        JSONObject r = new JSONObject();
        r.put("success", true);
        r.put("output", out.toString());
        r.put("_displayText", display);
        return r.toString();
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

        JSONObject r = new JSONObject();
        r.put("success", true);
        r.put("output", out.toString());
        r.put("_displayText", sb.toString().trim());
        return r.toString();
    }

    private static String detectLangCode(String text) {
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
