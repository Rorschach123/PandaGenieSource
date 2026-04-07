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

/**
 * PandaGenie 文本工具模块插件。
 * <p>
 * <b>模块用途：</b>在设备端提供常见文本处理：字数统计（含中日韩表意字符分词规则）、Base64/URL 编解码、正则匹配与替换、
 * 大小写/首字母大写/反转/trim 变换、UUID 生成、MD5/SHA 摘要等，无需网络。
 * </p>
 * <p>
 * <b>对外 API：</b>{@code wordCount}、{@code base64Encode}、{@code base64Decode}、{@code urlEncode}、{@code urlDecode}、
 * {@code regexMatch}、{@code regexReplace}、{@code textTransform}、{@code generateUUID}、{@code hashText}。
 * 成功时 {@code output} 为业务 JSON 字符串，多数动作带 {@code _displayText}。
 * </p>
 * <p>
 * 实现 {@link ModulePlugin}，由 {@code ModuleRuntime} 反射加载并调用 {@link #invoke}。
 * </p>
 */
public class TextToolsPlugin implements ModulePlugin {

    /** 英/中文句号、叹号、问号处断句（用于句子数估算）。 */
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[\\.\\!\\?。！？])(?=\\s|$)");
    /** 空行分隔段落（Java {@code \R} 匹配任意换行）。 */
    private static final Pattern PARAGRAPH_SPLIT = Pattern.compile("\\R\\s*\\R");
    /**
     * 按 {@code action} 执行文本工具；异常统一转为 {@code success=false}。
     *
     * @param context    未使用，保留接口签名
     * @param action     动作名见类说明
     * @param paramsJson 各动作所需字段的 JSON；空为 {@code {}}
     * @return 标准 JSON 响应
     */
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

    /**
     * 统计字符（Unicode 码点）、词、行、句、段数量并打包为 JSON。
     *
     * @param text 原始文本
     * @return 含 {@code characters}、{@code words}、{@code lines}、{@code sentences}、{@code paragraphs}
     */
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
     * 「词」数统计规则：中日韩等表意文字（{@link Character#isIdeographic(int)}）每个码点计 1 词；
     * 其它非空白字符从词首起连续读到下一个空白或表意字符为止计为 1 词（类似英文单词 + CJK 单字）。
     *
     * @param text 输入文本
     * @return 词数
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
            // 拉丁等文字：以连续非空白且非表意片段为一词
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

    /**
     * 按换行符计数行数；空文本视为 1 行（与常见编辑器行为一致）。
     */
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

    /**
     * 使用 {@link #SENTENCE_SPLIT} 拆分后统计非空片段数；至少返回 1（非空 trim 后）。
     */
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

    /**
     * 以双换行（含中间空白）分段；空文本返回 0，否则至少 1 段。
     */
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

    /**
     * UTF-8 字节上做 Base64（{@link Base64#NO_WRAP} 无换行）。
     */
    private static JSONObject base64Encode(String text) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        String encoded = Base64.encodeToString(bytes, Base64.NO_WRAP);
        return new JSONObject().put("encoded", encoded);
    }

    /**
     * Base64 解码为 UTF-8 文本（使用 {@link Base64#DEFAULT} 容忍换行等）。
     */
    private static JSONObject base64Decode(String text) throws Exception {
        byte[] raw = Base64.decode(text.trim(), Base64.DEFAULT);
        String decoded = new String(raw, StandardCharsets.UTF_8);
        return new JSONObject().put("decoded", decoded);
    }

    /** 使用 UTF-8 进行 {@link URLEncoder#encode(String, String)}（空格变为 {@code +} 等）。 */
    private static JSONObject urlEncode(String text) throws Exception {
        String encoded = URLEncoder.encode(text, StandardCharsets.UTF_8.name());
        return new JSONObject().put("encoded", encoded);
    }

    /** UTF-8 {@link URLDecoder#decode(String, String)}。 */
    private static JSONObject urlDecode(String text) throws Exception {
        String decoded = URLDecoder.decode(text, StandardCharsets.UTF_8.name());
        return new JSONObject().put("decoded", decoded);
    }

    /**
     * 在全文上反复 {@link Matcher#find()}，收集所有匹配子串。
     *
     * @param text        被匹配文本
     * @param patternStr  Java 正则表达式
     * @return {@code matches} 数组与 {@code count}
     */
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

    /**
     * 使用 {@link Matcher#replaceAll(String)} 做正则替换（支持捕获组引用如 {@code $1}）。
     */
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

    /**
     * 文本形态变换：{@code upper|lower|capitalize|reverse|trim}（均小写比较）。
     *
     * @param text      输入
     * @param transform 模式关键字
     * @return {@code result} 字段
     */
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

    /**
     * 按「词」首字母大写其余小写：以空白分隔词界，非字母原样保留。
     */
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

    /**
     * 按 Unicode 码点反转，避免破坏增补平面字符（代理对）。
     */
    private static String reverseByCodePoints(String s) {
        int[] cps = s.codePoints().toArray();
        StringBuilder sb = new StringBuilder();
        for (int i = cps.length - 1; i >= 0; i--) {
            sb.appendCodePoint(cps[i]);
        }
        return sb.toString();
    }

    /**
     * 生成随机 {@link UUID} 字符串（版本 4）。
     */
    private static JSONObject generateUuidJson() throws Exception {
        return new JSONObject().put("uuid", UUID.randomUUID().toString());
    }

    /**
     * 对 UTF-8 字节做消息摘要，输出小写十六进制。
     *
     * @param text      明文
     * @param algorithm {@code MD5}、{@code SHA-1}、{@code SHA-256}（兼容 {@code SHA1}/{@code SHA256} 简写）
     * @return {@code algorithm} 规范名与 {@code hex} 摘要
     */
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

    /** 字节数组转小写十六进制串（每字节两位）。 */
    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        }
        return sb.toString();
    }

    /**
     * 将空或空白 {@code paramsJson} 转为 {@code "{}"}。
     */
    private static String emptyJson(String value) {
        return value == null || value.trim().isEmpty() ? "{}" : value;
    }

    /** Escape {@code |} and newlines so Markdown table cells stay on one row. */
    private static String mdCell(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("|", "\\|").replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ');
    }

    /** {@code wordCount} 的展示块。 */
    private static String formatWordCountDisplay(JSONObject o) {
        return "📝 Text Stats\n\n"
                + "| Metric | Count |\n|---|---|\n"
                + "| Words | " + o.optInt("words") + " |\n"
                + "| Characters | " + o.optInt("characters") + " |\n"
                + "| Lines | " + o.optInt("lines") + " |\n"
                + "| Sentences | " + o.optInt("sentences") + " |\n"
                + "| Paragraphs | " + o.optInt("paragraphs") + " |";
    }

    /** Base64 编解码结果块。 */
    private static String formatBase64BlockDisplay(String resultText) {
        return "🔄 Base64 Result\n\n"
                + "| Item | Value |\n|---|---|\n"
                + "| Text | " + mdCell(resultText) + " |";
    }

    /** URL 编解码结果块。 */
    private static String formatUrlBlockDisplay(String resultText) {
        return "🔗 URL Result\n\n"
                + "| Item | Value |\n|---|---|\n"
                + "| Text | " + mdCell(resultText) + " |";
    }

    /** 列出正则匹配到的各子串（带序号）。 */
    private static String formatRegexMatchDisplay(JSONObject o) {
        int count = o.optInt("count", 0);
        JSONArray matches = o.optJSONArray("matches");
        StringBuilder sb = new StringBuilder();
        sb.append("🔍 Regex Match\n\n");
        sb.append("| Item | Value |\n|---|---|\n");
        sb.append("| Matches found | ").append(count).append(" |\n\n");
        sb.append("| # | Match |\n|---|---|\n");
        if (matches != null) {
            for (int i = 0; i < matches.length(); i++) {
                sb.append("| ").append(i + 1).append(" | ").append(mdCell(matches.optString(i))).append(" |\n");
            }
        }
        return sb.toString().trim();
    }

    /** 正则替换后的全文展示。 */
    private static String formatRegexReplaceDisplay(String resultText) {
        return "✏️ Regex Replace\n\n"
                + "| Item | Value |\n|---|---|\n"
                + "| Result | " + mdCell(resultText) + " |";
    }

    /**
     * 文本变换结果：带模式名与结果正文。
     *
     * @param transform   原始 transform 参数
     * @param resultText    变换后字符串
     */
    private static String formatTextTransformDisplay(String transform, String resultText) {
        String mode = transform == null ? "" : transform.trim();
        return "🔤 Text Transform\n\n"
                + "| Item | Value |\n|---|---|\n"
                + "| Mode | " + mdCell(mode) + " |\n"
                + "| Result | " + mdCell(resultText) + " |";
    }

    /** UUID 一行展示。 */
    private static String formatUuidDisplay(String uuid) {
        return "🔑 UUID Generated\n\n"
                + "| Item | Value |\n|---|---|\n"
                + "| UUID | " + mdCell(uuid) + " |";
    }

    /** 摘要算法名与十六进制摘要。 */
    private static String formatHashDisplay(JSONObject o) {
        return "🔐 Hash Result\n\n"
                + "| Algorithm | Hash |\n|---|---|\n"
                + "| " + mdCell(o.optString("algorithm")) + " | " + mdCell(o.optString("hex")) + " |";
    }

    /**
     * {@link JSONObject} 形式业务结果包装为成功响应。
     */
    private static String ok(JSONObject output, String displayText) throws Exception {
        return ok(output.toString(), displayText);
    }

    /**
     * 标准成功响应，可选 {@code _displayText}。
     *
     * @param output      业务 JSON 字符串（再作为字符串嵌入外层）
     * @param displayText 展示文案
     */
    private static String ok(String output, String displayText) throws Exception {
        JSONObject r = new JSONObject()
                .put("success", true)
                .put("output", output);
        if (displayText != null && !displayText.isEmpty()) {
            r.put("_displayText", displayText);
        }
        return r.toString();
    }

    /**
     * 顶层失败响应。
     *
     * @param message 错误信息（常来自异常 {@code getMessage()}）
     */
    private static String error(String message) throws Exception {
        return new JSONObject()
                .put("success", false)
                .put("error", message)
                .toString();
    }
}
