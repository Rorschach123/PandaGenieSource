package ai.rorsch.moduleplugins.url_codec;

import android.content.Context;
import ai.rorsch.pandagenie.module.runtime.ModulePlugin;
import org.json.JSONObject;
import org.json.JSONArray;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;

/**
 * URL 编解码模块
 * 提供 URL 编码、解码、组件编码、组件解码、URL 解析、URL 构建功能
 */
public class UrlCodecPlugin implements ModulePlugin {

    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        JSONObject params = new JSONObject(
                paramsJson != null && !paramsJson.isEmpty() ? paramsJson : "{}"
        );

        switch (action) {
            case "encode":
                return encode(params);
            case "decode":
                return decode(params);
            case "encodeComponent":
                return encodeComponent(params);
            case "decodeComponent":
                return decodeComponent(params);
            case "parseUrl":
                return parseUrl(params);
            case "buildUrl":
                return buildUrl(params);
            default:
                return error("Unsupported action: " + action);
        }
    }

    /**
     * URL 编码（标准 URI 编码，空格编码为 +）
     */
    private String encode(JSONObject params) throws Exception {
        String text = params.optString("text", "");
        if (text.isEmpty()) {
            return error("请输入需要编码的文本");
        }

        String encoded = URLEncoder.encode(text, "UTF-8");

        String displayText = "**URL 编码结果**\n\n"
                + "原文：" + text + "\n\n"
                + "编码：" + encoded;

        return ok(encoded, displayText);
    }

    /**
     * URL 解码（标准 URI 解码，+ 还原为空格）
     */
    private String decode(JSONObject params) throws Exception {
        String text = params.optString("text", "");
        if (text.isEmpty()) {
            return error("请输入需要解码的文本");
        }

        String decoded;
        try {
            decoded = URLDecoder.decode(text, "UTF-8");
        } catch (IllegalArgumentException e) {
            return error("解码失败，输入的文本不是有效的URL编码格式: " + e.getMessage());
        }

        String displayText = "**URL 解码结果**\n\n"
                + "原文：" + text + "\n\n"
                + "解码：" + decoded;

        return ok(decoded, displayText);
    }

    /**
     * URL 组件编码（使用 Percent Encoding，空格编码为 %20，不编码 ~!*()'）
     */
    private String encodeComponent(JSONObject params) throws Exception {
        String text = params.optString("text", "");
        if (text.isEmpty()) {
            return error("请输入需要编码的文本");
        }

        String encoded = encodeURIComponent(text);

        String displayText = "**URL 组件编码结果**\n\n"
                + "原文：" + text + "\n\n"
                + "编码：" + encoded;

        return ok(encoded, displayText);
    }

    /**
     * URL 组件解码
     */
    private String decodeComponent(JSONObject params) throws Exception {
        String text = params.optString("text", "");
        if (text.isEmpty()) {
            return error("请输入需要解码的文本");
        }

        String decoded;
        try {
            decoded = decodeURIComponent(text);
        } catch (IllegalArgumentException e) {
            return error("解码失败，输入的文本不是有效的URL组件编码格式: " + e.getMessage());
        }

        String displayText = "**URL 组件解码结果**\n\n"
                + "原文：" + text + "\n\n"
                + "解码：" + decoded;

        return ok(decoded, displayText);
    }

    /**
     * 解析 URL 各个组成部分
     */
    private String parseUrl(JSONObject params) throws Exception {
        String url = params.optString("url", "");
        if (url.isEmpty()) {
            return error("请输入需要解析的URL");
        }

        JSONObject result = new JSONObject();

        // 手动解析 URL 各部分
        String remaining = url;

        // 提取 fragment (#)
        String fragment = "";
        int hashIndex = remaining.indexOf('#');
        if (hashIndex >= 0) {
            fragment = remaining.substring(hashIndex + 1);
            remaining = remaining.substring(0, hashIndex);
        }

        // 提取 query (?)
        String query = "";
        int questionIndex = remaining.indexOf('?');
        if (questionIndex >= 0) {
            query = remaining.substring(questionIndex + 1);
            remaining = remaining.substring(0, questionIndex);
        }

        // 提取 protocol (://)
        String protocol = "";
        int protoIndex = remaining.indexOf("://");
        if (protoIndex >= 0) {
            protocol = remaining.substring(0, protoIndex).toLowerCase();
            remaining = remaining.substring(protoIndex + 3);
        }

        // 提取 host 和 port
        String host = "";
        String port = "";
        int slashIndex = remaining.indexOf('/');
        String hostPort;
        String path;
        if (slashIndex >= 0) {
            hostPort = remaining.substring(0, slashIndex);
            path = remaining.substring(slashIndex);
        } else {
            hostPort = remaining;
            path = "/";
        }

        int colonIndex = hostPort.indexOf(':');
        if (colonIndex >= 0) {
            host = hostPort.substring(0, colonIndex);
            port = hostPort.substring(colonIndex + 1);
        } else {
            host = hostPort;
        }

        // 解析查询参数为 JSON 数组
        JSONArray queryParams = new JSONArray();
        if (!query.isEmpty()) {
            String[] pairs = query.split("&");
            for (String pair : pairs) {
                int eqIndex = pair.indexOf('=');
                JSONObject param = new JSONObject();
                if (eqIndex >= 0) {
                    String key = pair.substring(0, eqIndex);
                    String value = pair.substring(eqIndex + 1);
                    param.put("key", decodeURIComponent(key));
                    param.put("value", decodeURIComponent(value));
                    param.put("raw", pair);
                } else {
                    param.put("key", decodeURIComponent(pair));
                    param.put("value", "");
                    param.put("raw", pair);
                }
                queryParams.put(param);
            }
        }

        result.put("protocol", protocol);
        result.put("host", host);
        result.put("port", port);
        result.put("path", path);
        result.put("query", query);
        result.put("queryParams", queryParams);
        result.put("fragment", fragment);

        // 构建显示文本
        StringBuilder sb = new StringBuilder();
        sb.append("**URL 解析结果**\n\n");
        sb.append("原始链接：").append(url).append("\n\n");
        if (!protocol.isEmpty()) sb.append("- **协议**：`").append(protocol).append("`\n");
        if (!host.isEmpty()) sb.append("- **主机**：`").append(host).append("`\n");
        if (!port.isEmpty()) sb.append("- **端口**：`").append(port).append("`\n");
        sb.append("- **路径**：`").append(path).append("`\n");
        if (!query.isEmpty()) {
            sb.append("- **查询参数**：\n");
            for (int i = 0; i < queryParams.length(); i++) {
                JSONObject p = queryParams.getJSONObject(i);
                sb.append("  - `").append(p.getString("key")).append("` = `").append(p.optString("value", "")).append("`\n");
            }
        }
        if (!fragment.isEmpty()) sb.append("- **片段标识**：`").append(fragment).append("`\n");

        return ok(result.toString(), sb.toString());
    }

    /**
     * 根据组件构建 URL
     */
    private String buildUrl(JSONObject params) throws Exception {
        String protocol = params.optString("protocol", "").trim();
        String host = params.optString("host", "").trim();
        String path = params.optString("path", "").trim();
        String query = params.optString("query", "").trim();
        String fragment = params.optString("fragment", "").trim();
        String port = params.optString("port", "").trim();

        if (host.isEmpty()) {
            return error("主机名(host)不能为空");
        }

        StringBuilder url = new StringBuilder();

        if (!protocol.isEmpty()) {
            url.append(protocol.toLowerCase()).append("://");
        } else {
            url.append("https://");
        }

        url.append(host);

        if (!port.isEmpty()) {
            url.append(":").append(port);
        }

        if (!path.isEmpty()) {
            if (!path.startsWith("/")) {
                url.append("/");
            }
            url.append(path);
        }

        if (!query.isEmpty()) {
            if (!query.startsWith("?")) {
                url.append("?");
            }
            url.append(query);
        }

        if (!fragment.isEmpty()) {
            if (!fragment.startsWith("#")) {
                url.append("#");
            }
            url.append(fragment);
        }

        String builtUrl = url.toString();

        String displayText = "**URL 构建结果**\n\n"
                + builtUrl + "\n\n"
                + "- 协议：" + (protocol.isEmpty() ? "https（默认）" : protocol) + "\n"
                + "- 主机：" + host + "\n"
                + (!port.isEmpty() ? "- 端口：" + port + "\n" : "")
                + "- 路径：" + (path.isEmpty() ? "/" : path) + "\n"
                + (!query.isEmpty() ? "- 查询：" + query + "\n" : "")
                + (!fragment.isEmpty() ? "- 片段：" + fragment + "\n" : "");

        return ok(builtUrl, displayText);
    }

    // ========== 辅助方法 ==========

    /**
     * 模拟 JavaScript encodeURIComponent
     * URIEncoder.encode 会将空格编码为 +，此方法将其编码为 %20
     * 同时不编码 ~!*()' 等字符
     */
    private String encodeURIComponent(String text) throws UnsupportedEncodingException {
        String encoded = URLEncoder.encode(text, "UTF-8");
        // URLEncoder.encode 将空格编码为 +，需要替换为 %20
        encoded = encoded.replace("+", "%20");
        // URLEncoder.encode 会额外编码一些 JS 不编码的字符，还原它们
        encoded = encoded.replace("%7E", "~");
        encoded = encoded.replace("%2A", "*");
        encoded = encoded.replace("%27", "'");
        encoded = encoded.replace("%28", "(");
        encoded = encoded.replace("%29", ")");
        return encoded;
    }

    /**
     * 模拟 JavaScript decodeURIComponent
     */
    private String decodeURIComponent(String text) {
        try {
            // 快速路径：使用标准 URLDecoder（处理大多数情况）
            // 先将 %XX 编码还原，手动处理 UTF-8
            StringBuilder result = new StringBuilder();
            int i = 0;
            while (i < text.length()) {
                char c = text.charAt(i);
                if (c == '%' && i + 2 < text.length()) {
                    String hex = text.substring(i + 1, i + 3);
                    try {
                        int value = Integer.parseInt(hex, 16);
                        // 处理 UTF-8 多字节字符
                        if (value < 0x80) {
                            result.append((char) value);
                            i += 3;
                        } else if ((value & 0xE0) == 0xC0 && i + 5 < text.length()) {
                            // 2字节 UTF-8
                            int b2 = Integer.parseInt(text.substring(i + 4, i + 6), 16);
                            int codePoint = ((value & 0x1F) << 6) | (b2 & 0x3F);
                            result.append((char) codePoint);
                            i += 6;
                        } else if ((value & 0xF0) == 0xE0 && i + 8 < text.length()) {
                            // 3字节 UTF-8
                            int b2 = Integer.parseInt(text.substring(i + 4, i + 6), 16);
                            int b3 = Integer.parseInt(text.substring(i + 7, i + 9), 16);
                            int codePoint = ((value & 0x0F) << 12) | ((b2 & 0x3F) << 6) | (b3 & 0x3F);
                            if (codePoint <= 0xFFFF) {
                                result.append((char) codePoint);
                            } else {
                                // 处理 surrogate pair
                                codePoint -= 0x10000;
                                result.append((char) (0xD800 + (codePoint >> 10)));
                                result.append((char) (0xDC00 + (codePoint & 0x3FF)));
                            }
                            i += 9;
                        } else if ((value & 0xF8) == 0xF0 && i + 11 < text.length()) {
                            // 4字节 UTF-8
                            int b2 = Integer.parseInt(text.substring(i + 4, i + 6), 16);
                            int b3 = Integer.parseInt(text.substring(i + 7, i + 9), 16);
                            int b4 = Integer.parseInt(text.substring(i + 10, i + 12), 16);
                            int codePoint = ((value & 0x07) << 18) | ((b2 & 0x3F) << 12)
                                    | ((b3 & 0x3F) << 6) | (b4 & 0x3F);
                            codePoint -= 0x10000;
                            result.append((char) (0xD800 + (codePoint >> 10)));
                            result.append((char) (0xDC00 + (codePoint & 0x3FF)));
                            i += 12;
                        } else {
                            // 无法识别的多字节序列，直接输出原始字符
                            result.append(c);
                            i++;
                        }
                    } catch (NumberFormatException e) {
                        result.append(c);
                        i++;
                    }
                } else if (c == '+') {
                    result.append(' ');
                    i++;
                } else {
                    result.append(c);
                    i++;
                }
            }
            return result.toString();
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to decode: " + e.getMessage(), e);
        }
    }

    /**
     * 返回成功结果
     */
    private String ok(String output, String displayText) throws Exception {
        return new JSONObject()
                .put("success", true)
                .put("output", output)
                .put("_displayText", displayText)
                .toString();
    }

    /**
     * 返回错误结果
     */
    private String error(String message) throws Exception {
        return new JSONObject()
                .put("success", false)
                .put("error", message)
                .toString();
    }
}
