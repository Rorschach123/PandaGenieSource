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

    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        try {
            JSONObject params = new JSONObject(emptyJson(paramsJson));
            switch (action) {
                case "wordCount":
                    return ok(wordCountJson(params.optString("text", "")));
                case "base64Encode":
                    return ok(base64Encode(params.optString("text", "")));
                case "base64Decode":
                    return ok(base64Decode(params.optString("text", "")));
                case "urlEncode":
                    return ok(urlEncode(params.optString("text", "")));
                case "urlDecode":
                    return ok(urlDecode(params.optString("text", "")));
                case "regexMatch":
                    return ok(regexMatch(params.optString("text", ""), params.optString("pattern", "")));
                case "regexReplace":
                    return ok(regexReplace(
                            params.optString("text", ""),
                            params.optString("pattern", ""),
                            params.optString("replacement", "")));
                case "textTransform":
                    return ok(textTransform(params.optString("text", ""), params.optString("transform", "")));
                case "generateUUID":
                    return ok(generateUuidJson());
                case "hashText":
                    return ok(hashText(params.optString("text", ""), params.optString("algorithm", "")));
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

    private static String ok(JSONObject output) throws Exception {
        return new JSONObject()
                .put("success", true)
                .put("output", output.toString())
                .toString();
    }

    private static String error(String message) throws Exception {
        return new JSONObject()
                .put("success", false)
                .put("error", message)
                .toString();
    }
}
