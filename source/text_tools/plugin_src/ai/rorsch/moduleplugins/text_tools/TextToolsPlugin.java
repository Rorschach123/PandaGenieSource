package ai.rorsch.moduleplugins.text_tools;

import android.content.Context;
import android.util.Base64;

import ai.rorsch.pandagenie.module.runtime.ModulePlugin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class TextToolsPlugin implements ModulePlugin {

    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[\\.\\!\\?。！？])(?=\\s|$)");
    private static final Pattern PARAGRAPH_SPLIT = Pattern.compile("\\R\\s*\\R");
    private static final String DISPLAY_RULE = "━━━━━━━━━━━━━━";

    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        try {
            JSONObject params = new JSONObject(emptyJson(paramsJson));
            switch (action) {
                case "wordCount": {
                    JSONObject out = wordCountJson(params.optString("text", ""));
                    return ok(out, formatWordCountDisplay(out));
                }
                case "base64Encode": {
                    JSONObject out = base64Encode(params.optString("text", ""));
                    return ok(out, formatBase64BlockDisplay(out.optString("encoded")));
                }
                case "base64Decode": {
                    JSONObject out = base64Decode(params.optString("text", ""));
                    return ok(out, formatBase64BlockDisplay(out.optString("decoded")));
                }
                case "urlEncode": {
                    JSONObject out = urlEncode(params.optString("text", ""));
                    return ok(out, formatUrlBlockDisplay(out.optString("encoded")));
                }
                case "urlDecode": {
                    JSONObject out = urlDecode(params.optString("text", ""));
                    return ok(out, formatUrlBlockDisplay(out.optString("decoded")));
                }
                case "regexMatch": {
                    JSONObject out = regexMatch(params.optString("text", ""), params.optString("pattern", ""));
                    return ok(out, formatRegexMatchDisplay(out));
                }
                case "regexReplace": {
                    JSONObject out = regexReplace(
                            params.optString("text", ""),
                            params.optString("pattern", ""),
                            params.optString("replacement", ""));
                    return ok(out, formatRegexReplaceDisplay(out.optString("result")));
                }
                case "textTransform": {
                    String transform = params.optString("transform", "");
                    JSONObject out = textTransform(params.optString("text", ""), transform);
                    return ok(out, formatTextTransformDisplay(transform, out.optString("result")));
                }
                case "generateUUID": {
                    JSONObject out = generateUuidJson();
                    return ok(out, formatUuidDisplay(out.optString("uuid")));
                }
                case "hashText": {
                    JSONObject out = hashText(params.optString("text", ""), params.optString("algorithm", ""));
                    return ok(out, formatHashDisplay(out));
                }
                default:
                    return error("Unsupported action: " + action);
            }
        } catch (Exception e) {
            String msg = e.getMessage();
            return error(msg != null && !msg.isEmpty() ? msg : e.getClass().getSimpleName());
        }
    }

    private static JSONObject wordCountJson(String text) throws Exception {
        int characters = text.codePointCount(0, text.length());
        int words = countWords(text);
        int lines = countLines(text);
        int sentences = countSentences(text);
        int paragraphs = countParagraphs(text);
        return new JSONObject()
                .put("characters", characters)
                .put("words", words)
                .put("lines", lines)
                .put("sentences", sentences)
                .put("paragraphs", paragraphs);
    }

    /**
     * CJK ideographs (and other ideographic code points): one word each.
     * Other non-whitespace runs: one word per maximal run until whitespace or ideograph.
     */
    private static int countWords(String text) {
        int words = 0;
        int i = 0;
        final int n = text.length();
        while (i < n) {
            int cp = text.codePointAt(i);
            int step = Character.charCount(cp);
            if (Character.isWhitespace(cp)) {
                i += step;
                continue;
            }
            if (Character.isIdeographic(cp)) {
                words++;
                i += step;
                continue;
            }
            words++;
            i += step;
            while (i < n) {
                int cp2 = text.codePointAt(i);
                int step2 = Character.charCount(cp2);
                if (Character.isWhitespace(cp2) || Character.isIdeographic(cp2)) {
                    break;
                }
                i += step2;
            }
        }
        return words;
    }

    private static int countLines(String text) {
        if (text.isEmpty()) {
            return 1;
        }
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\u2028' || c == '\u2029') {
                lines++;
            } else if (c == '\r') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                lines++;
            }
        }
        return lines;
    }

    private static int countSentences(String text) {
        String t = text.trim();
        if (t.isEmpty()) {
            return 0;
        }
        String[] parts = SENTENCE_SPLIT.split(t);
        int count = 0;
        for (String p : parts) {
            if (!p.trim().isEmpty()) {
                count++;
            }
        }
        return Math.max(count, 1);
    }

    private static int countParagraphs(String text) {
        if (text.trim().isEmpty()) {
            return 0;
        }
        String[] parts = PARAGRAPH_SPLIT.split(text);
        int count = 0;
        for (String p : parts) {
            if (!p.trim().isEmpty()) {
                count++;
            }
        }
        return Math.max(count, 1);
    }

    private static JSONObject base64Encode(String text) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        String encoded = Base64.encodeToString(bytes, Base64.NO_WRAP);
        return new JSONObject().put("encoded", encoded);
    }

    private static JSONObject base64Decode(String text) throws Exception {
        byte[] raw = Base64.decode(text.trim(), Base64.DEFAULT);
        String decoded = new String(raw, StandardCharsets.UTF_8);
        return new JSONObject().put("decoded", decoded);
    }

    private static JSONObject urlEncode(String text) throws Exception {
        String encoded = URLEncoder.encode(text, StandardCharsets.UTF_8.name());
        return new JSONObject().put("encoded", encoded);
    }

    private static JSONObject urlDecode(String text) throws Exception {
        String decoded = URLDecoder.decode(text, StandardCharsets.UTF_8.name());
        return new JSONObject().put("decoded", decoded);
    }

    private static JSONObject regexMatch(String text, String patternStr) throws Exception {
        if (patternStr.isEmpty()) {
            throw new IllegalArgumentException("pattern is required");
        }
        Pattern pattern;
        try {
            pattern = Pattern.compile(patternStr);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("invalid regex: " + e.getMessage());
        }
        Matcher m = pattern.matcher(text);
        JSONArray matches = new JSONArray();
        while (m.find()) {
            matches.put(m.group());
        }
        return new JSONObject().put("matches", matches).put("count", matches.length());
    }

    private static JSONObject regexReplace(String text, String patternStr, String replacement) throws Exception {
        if (patternStr.isEmpty()) {
            throw new IllegalArgumentException("pattern is required");
        }
        Pattern pattern;
        try {
            pattern = Pattern.compile(patternStr);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("invalid regex: " + e.getMessage());
        }
        Matcher matcher = pattern.matcher(text);
        String result = matcher.replaceAll(replacement);
        return new JSONObject().put("result", result);
    }

    private static JSONObject textTransform(String text, String transform) throws Exception {
        String mode = transform.trim().toLowerCase(Locale.ROOT);
        String result;
        switch (mode) {
            case "upper":
                result = text.toUpperCase(Locale.ROOT);
                break;
            case "lower":
                result = text.toLowerCase(Locale.ROOT);
                break;
            case "capitalize":
                result = capitalizeWords(text);
                break;
            case "reverse":
                result = reverseByCodePoints(text);
                break;
            case "trim":
                result = text.trim();
                break;
            default:
                throw new IllegalArgumentException("transform must be upper, lower, capitalize, reverse, or trim");
        }
        return new JSONObject().put("result", result);
    }

    private static String capitalizeWords(String s) {
        StringBuilder sb = new StringBuilder();
        boolean wordStart = true;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int len = Character.charCount(cp);
            if (Character.isWhitespace(cp)) {
                sb.appendCodePoint(cp);
                wordStart = true;
            } else {
                if (wordStart) {
                    if (Character.isLetter(cp)) {
                        sb.appendCodePoint(Character.toUpperCase(cp));
                    } else {
                        sb.appendCodePoint(cp);
                    }
                    wordStart = false;
                } else {
                    if (Character.isLetter(cp)) {
                        sb.appendCodePoint(Character.toLowerCase(cp));
                    } else {
                        sb.appendCodePoint(cp);
                    }
                }
            }
            i += len;
        }
        return sb.toString();
    }

    private static String reverseByCodePoints(String s) {
        int[] cps = s.codePoints().toArray();
        StringBuilder sb = new StringBuilder();
        for (int i = cps.length - 1; i >= 0; i--) {
            sb.appendCodePoint(cps[i]);
        }
        return sb.toString();
    }

    private static JSONObject generateUuidJson() throws Exception {
        return new JSONObject().put("uuid", UUID.randomUUID().toString());
    }

    private static JSONObject hashText(String text, String algorithm) throws Exception {
        String normalized = algorithm.trim().toUpperCase(Locale.ROOT);
        if (normalized.equals("SHA1")) {
            normalized = "SHA-1";
        } else if (normalized.equals("SHA256")) {
            normalized = "SHA-256";
        }
        String digestName;
        switch (normalized) {
            case "MD5":
                digestName = "MD5";
                break;
            case "SHA-1":
                digestName = "SHA-1";
                break;
            case "SHA-256":
                digestName = "SHA-256";
                break;
            default:
                throw new IllegalArgumentException("algorithm must be MD5, SHA-1, or SHA-256");
        }
        MessageDigest md = MessageDigest.getInstance(digestName);
        byte[] hash = md.digest(text.getBytes(StandardCharsets.UTF_8));
        return new JSONObject()
                .put("algorithm", digestName)
                .put("hex", toHex(hash));
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        }
        return sb.toString();
    }

    private static String emptyJson(String value) {
        return value == null || value.trim().isEmpty() ? "{}" : value;
    }

    private static String formatWordCountDisplay(JSONObject o) {
        return "📝 Text Stats\n" + DISPLAY_RULE + "\n"
                + "▸ Words: " + o.optInt("words") + "\n"
                + "▸ Chars: " + o.optInt("characters") + "\n"
                + "▸ Lines: " + o.optInt("lines");
    }

    private static String formatBase64BlockDisplay(String resultText) {
        return "🔄 Base64 Result\n" + DISPLAY_RULE + "\n" + resultText;
    }

    private static String formatUrlBlockDisplay(String resultText) {
        return "🔗 URL Result\n" + DISPLAY_RULE + "\n" + resultText;
    }

    private static String formatRegexMatchDisplay(JSONObject o) {
        int count = o.optInt("count", 0);
        JSONArray matches = o.optJSONArray("matches");
        StringBuilder sb = new StringBuilder();
        sb.append("🔍 Regex Match\n").append(DISPLAY_RULE).append("\n");
        sb.append("Found ").append(count).append(" match").append(count == 1 ? "" : "es").append("\n");
        if (matches != null) {
            for (int i = 0; i < matches.length(); i++) {
                sb.append(i + 1).append(". ").append(matches.optString(i)).append("\n");
            }
        }
        return sb.toString().trim();
    }

    private static String formatRegexReplaceDisplay(String resultText) {
        return "✏️ Regex Replace\n" + DISPLAY_RULE + "\n" + resultText;
    }

    private static String formatTextTransformDisplay(String transform, String resultText) {
        String mode = transform == null ? "" : transform.trim();
        return "🔤 Text Transform\n" + DISPLAY_RULE + "\n▸ Mode: " + mode + "\n\n" + resultText;
    }

    private static String formatUuidDisplay(String uuid) {
        return "🔑 UUID Generated\n" + DISPLAY_RULE + "\n" + uuid;
    }

    private static String formatHashDisplay(JSONObject o) {
        return "🔐 Hash Result\n" + DISPLAY_RULE + "\n"
                + "▸ Algorithm: " + o.optString("algorithm") + "\n"
                + "▸ Hash: " + o.optString("hex");
    }

    private static String ok(JSONObject output, String displayText) throws Exception {
        return ok(output.toString(), displayText);
    }

    private static String ok(String output, String displayText) throws Exception {
        JSONObject r = new JSONObject()
                .put("success", true)
                .put("output", output);
        if (displayText != null && !displayText.isEmpty()) {
            r.put("_displayText", displayText);
        }
        return r.toString();
    }

    private static String error(String message) throws Exception {
        return new JSONObject()
                .put("success", false)
                .put("error", message)
                .toString();
    }
}
