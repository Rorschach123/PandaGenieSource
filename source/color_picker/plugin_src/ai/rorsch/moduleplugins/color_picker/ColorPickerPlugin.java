package ai.rorsch.moduleplugins.color_picker;

import android.content.Context;

import ai.rorsch.pandagenie.module.runtime.ModulePlugin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PandaGenie 颜色工具模块插件。
 * <p>
 * <b>模块用途：</b>解析与转换多种颜色表示（十六进制、RGB、HSL、CMYK、CSS 颜色名、{@code rgb()}/{@code hsl()} 函数等），
 * 生成配色方案（互补、类似、三色、分裂互补、四色/方形）、随机配色，以及查询与 CSS 命名色最接近的颜色与欧氏距离。
 * </p>
 * <p>
 * <b>对外 API（{@code action}）：</b>{@code convertColor}、{@code generatePalette}、{@code randomColor}、{@code getColorInfo}。
 * </p>
 * <p>
 * 实现 {@link ModulePlugin}，由宿主应用 {@code ModuleRuntime} 通过反射加载并调用 {@link #invoke}；{@link Context} 未参与计算。
 * </p>
 */
public class ColorPickerPlugin implements ModulePlugin {

    /** 用于 {@link #handleRandomColor} 的加密安全随机数源 */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** CSS 标准命名色 → 六位大写十六进制（不含 {@code #}），用于名称解析与最近色匹配 */
    private static final LinkedHashMap<String, String> NAMED_HEX = new LinkedHashMap<>();

    static {
        String[][] pairs = new String[][]{
                {"aliceblue", "F0F8FF"}, {"antiquewhite", "FAEBD7"}, {"aqua", "00FFFF"}, {"aquamarine", "7FFFD4"},
                {"azure", "F0FFFF"}, {"beige", "F5F5DC"}, {"bisque", "FFE4C4"}, {"black", "000000"},
                {"blanchedalmond", "FFEBCD"}, {"blue", "0000FF"}, {"blueviolet", "8A2BE2"}, {"brown", "A52A2A"},
                {"burlywood", "DEB887"}, {"cadetblue", "5F9EA0"}, {"chartreuse", "7FFF00"}, {"chocolate", "D2691E"},
                {"coral", "FF7F50"}, {"cornflowerblue", "6495ED"}, {"cornsilk", "FFF8DC"}, {"crimson", "DC143C"},
                {"cyan", "00FFFF"}, {"darkblue", "00008B"}, {"darkcyan", "008B8B"}, {"darkgoldenrod", "B8860B"},
                {"darkgray", "A9A9A9"}, {"darkgreen", "006400"}, {"darkgrey", "A9A9A9"}, {"darkkhaki", "BDB76B"},
                {"darkmagenta", "8B008B"}, {"darkolivegreen", "556B2F"}, {"darkorange", "FF8C00"}, {"darkorchid", "9932CC"},
                {"darkred", "8B0000"}, {"darksalmon", "E9967A"}, {"darkseagreen", "8FBC8F"}, {"darkslateblue", "483D8B"},
                {"darkslategray", "2F4F4F"}, {"darkslategrey", "2F4F4F"}, {"darkturquoise", "00CED1"}, {"darkviolet", "9400D3"},
                {"deeppink", "FF1493"}, {"deepskyblue", "00BFFF"}, {"dimgray", "696969"}, {"dimgrey", "696969"},
                {"dodgerblue", "1E90FF"}, {"firebrick", "B22222"}, {"floralwhite", "FFFAF0"}, {"forestgreen", "228B22"},
                {"fuchsia", "FF00FF"}, {"gainsboro", "DCDCDC"}, {"ghostwhite", "F8F8FF"}, {"gold", "FFD700"},
                {"goldenrod", "DAA520"}, {"gray", "808080"}, {"green", "008000"}, {"greenyellow", "ADFF2F"},
                {"grey", "808080"}, {"honeydew", "F0FFF0"}, {"hotpink", "FF69B4"}, {"indianred", "CD5C5C"},
                {"indigo", "4B0082"}, {"ivory", "FFFFF0"}, {"khaki", "F0E68C"}, {"lavender", "E6E6FA"},
                {"lavenderblush", "FFF0F5"}, {"lawngreen", "7CFC00"}, {"lemonchiffon", "FFFACD"}, {"lightblue", "ADD8E6"},
                {"lightcoral", "F08080"}, {"lightcyan", "E0FFFF"}, {"lightgoldenrodyellow", "FAFAD2"}, {"lightgray", "D3D3D3"},
                {"lightgreen", "90EE90"}, {"lightgrey", "D3D3D3"}, {"lightpink", "FFB6C1"}, {"lightsalmon", "FFA07A"},
                {"lightseagreen", "20B2AA"}, {"lightskyblue", "87CEFA"}, {"lightslategray", "778899"}, {"lightslategrey", "778899"},
                {"lightsteelblue", "B0C4DE"}, {"lightyellow", "FFFFE0"}, {"lime", "00FF00"}, {"limegreen", "32CD32"},
                {"linen", "FAF0E6"}, {"magenta", "FF00FF"}, {"maroon", "800000"}, {"mediumaquamarine", "66CDAA"},
                {"mediumblue", "0000CD"}, {"mediumorchid", "BA55D3"}, {"mediumpurple", "9370DB"}, {"mediumseagreen", "3CB371"},
                {"mediumslateblue", "7B68EE"}, {"mediumspringgreen", "00FA9A"}, {"mediumturquoise", "48D1CC"}, {"mediumvioletred", "C71585"},
                {"midnightblue", "191970"}, {"mintcream", "F5FFFA"}, {"mistyrose", "FFE4E1"}, {"moccasin", "FFE4B5"},
                {"navajowhite", "FFDEAD"}, {"navy", "000080"}, {"oldlace", "FDF5E6"}, {"olive", "808000"},
                {"olivedrab", "6B8E23"}, {"orange", "FFA500"}, {"orangered", "FF4500"}, {"orchid", "DA70D6"},
                {"palegoldenrod", "EEE8AA"}, {"palegreen", "98FB98"}, {"paleturquoise", "AFEEEE"}, {"palevioletred", "DB7093"},
                {"papayawhip", "FFEFD5"}, {"peachpuff", "FFDAB9"}, {"peru", "CD853F"}, {"pink", "FFC0CB"},
                {"plum", "DDA0DD"}, {"powderblue", "B0E0E6"}, {"purple", "800080"}, {"rebeccapurple", "663399"},
                {"red", "FF0000"}, {"rosybrown", "BC8F8F"}, {"royalblue", "4169E1"}, {"saddlebrown", "8B4513"},
                {"salmon", "FA8072"}, {"sandybrown", "F4A460"}, {"seagreen", "2E8B57"}, {"seashell", "FFF5EE"},
                {"sienna", "A0522D"}, {"silver", "C0C0C0"}, {"skyblue", "87CEEB"}, {"slateblue", "6A5ACD"},
                {"slategray", "708090"}, {"slategrey", "708090"}, {"snow", "FFFAFA"}, {"springgreen", "00FF7F"},
                {"steelblue", "4682B4"}, {"tan", "D2B48C"}, {"teal", "008080"}, {"thistle", "D8BFD8"},
                {"tomato", "FF6347"}, {"turquoise", "40E0D0"}, {"violet", "EE82EE"}, {"wheat", "F5DEB3"},
                {"white", "FFFFFF"}, {"whitesmoke", "F5F5F5"}, {"yellow", "FFFF00"}, {"yellowgreen", "9ACD32"}
        };
        // 填充静态表，保持插入顺序以便遍历顺序稳定（最近命名色搜索时遍历顺序一致）
        for (String[] p : pairs) {
            NAMED_HEX.put(p[0], p[1]);
        }
    }

    /** 匹配可选 # 前缀的 3/6/8 位十六进制（8 位时含 Alpha，解析时取前 6 位） */
    private static final Pattern HEX_PATTERN = Pattern.compile("^#?([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$");
    /** CSS {@code rgb()/rgba()} 前三通道整数提取 */
    private static final Pattern RGB_FUNC = Pattern.compile(
            "rgba?\\s*\\(\\s*([0-9]+)\\s*,\\s*([0-9]+)\\s*,\\s*([0-9]+)", Pattern.CASE_INSENSITIVE);
    /** CSS {@code hsl()/hsla()} 前三参数提取（百分比与 0–1 小数均兼容） */
    private static final Pattern HSL_FUNC = Pattern.compile(
            "hsla?\\s*\\(\\s*([0-9.]+)\\s*,\\s*([0-9.]+)\\s*%?\\s*,\\s*([0-9.]+)\\s*%?", Pattern.CASE_INSENSITIVE);

    /**
     * 模块入口：根据 {@code action} 解析 JSON 参数并返回标准成功/失败 JSON（成功时常含 {@code _displayText} 色块展示）。
     *
     * @param context    Android 上下文（本插件未使用）
     * @param action     {@code convertColor} 等
     * @param paramsJson 各 action 的参数字段，见各 {@code handle*} 方法
     * @return JSON 字符串
     * @throws Exception 解析或序列化异常
     */
    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        JSONObject params = new JSONObject(emptyJson(paramsJson));
        switch (action) {
            case "convertColor":
                return handleConvertColor(params);
            case "generatePalette":
                return handleGeneratePalette(params);
            case "randomColor":
                return handleRandomColor(params);
            case "getColorInfo":
                return handleGetColorInfo(params);
            default:
                return error("Unsupported action: " + action);
        }
    }

    /**
     * 颜色格式转换：支持自动识别或指定源格式，输出完整 JSON（hex/rgb/hsl/cmyk 等）或单一目标格式字符串。
     *
     * @param params {@code input} 必填；{@code fromFormat} 默认 {@code auto}；{@code toFormat} 默认 {@code all}（输出全部字段）或 hex/rgb/hsl/cmyk
     * @return {@link #ok} 包装结果或 {@link #error}
     * @throws Exception JSON 异常
     */
    private String handleConvertColor(JSONObject params) throws Exception {
        String input = params.optString("input", "").trim();
        String from = params.optString("fromFormat", "auto").trim().toLowerCase(Locale.ROOT);
        String to = params.optString("toFormat", "all").trim().toLowerCase(Locale.ROOT);
        if (input.isEmpty()) {
            return error("Missing input");
        }
        int[] rgb;
        if ("auto".equals(from)) {
            rgb = parseColorAuto(input);
        } else {
            rgb = parseColor(input, from);
        }
        if (rgb == null) {
            return error("Could not parse color");
        }
        JSONObject out = buildColorJson(rgb);
        // toFormat=all 时返回全部分量；否则仅附加单一格式的 result 字段
        boolean all = "all".equals(to);
        if (!all) {
            String single = formatAs(rgb, to);
            if (single == null) {
                return error("Unknown toFormat: " + to);
            }
            out.put("result", single);
        }
        String display = formatDisplayBlocks(rgb, out);
        return ok(out, display);
    }

    /**
     * 以基准色在 HSL 空间按指定和谐关系生成一组十六进制颜色（互补、类似、三色、分裂互补、四色/方形）。
     *
     * @param params {@code baseHex} 基准色；{@code harmony} 默认 complementary，支持下划线/连写变体
     * @return 成功 JSON 含 {@code colors} 数组与展示文本
     * @throws Exception JSON 异常
     */
    private String handleGeneratePalette(JSONObject params) throws Exception {
        String baseHex = normalizeHexParam(params.optString("baseHex", ""));
        String harmony = params.optString("harmony", "complementary").trim().toLowerCase(Locale.ROOT);
        harmony = harmony.replace("_", "-");
        if (baseHex == null) {
            return error("Invalid baseHex");
        }
        int[] rgb = parseHexToRgb(baseHex);
        if (rgb == null) {
            return error("Invalid base color");
        }
        float[] hsl = rgbToHsl(rgb[0], rgb[1], rgb[2]);
        List<int[]> rgbs = new ArrayList<>();
        float h = hsl[0];
        float s = hsl[1];
        float l = hsl[2];
        // 在 HSL 色相环上按规则旋转/取点生成和谐色组
        switch (harmony) {
            case "complementary":
                rgbs.add(rgb);
                rgbs.add(hslToRgb((h + 180f) % 360f, s, l));
                break;
            case "analogous":
                rgbs.add(hslToRgb((h - 30f + 360f) % 360f, s, l));
                rgbs.add(hslToRgb((h - 15f + 360f) % 360f, s, l));
                rgbs.add(rgb);
                rgbs.add(hslToRgb((h + 15f) % 360f, s, l));
                rgbs.add(hslToRgb((h + 30f) % 360f, s, l));
                break;
            case "triadic":
                rgbs.add(rgb);
                rgbs.add(hslToRgb((h + 120f) % 360f, s, l));
                rgbs.add(hslToRgb((h + 240f) % 360f, s, l));
                break;
            case "split-complementary":
            case "splitcomplementary":
                rgbs.add(rgb);
                rgbs.add(hslToRgb((h + 150f) % 360f, s, l));
                rgbs.add(hslToRgb((h + 210f) % 360f, s, l));
                break;
            case "tetradic":
            case "square":
                rgbs.add(rgb);
                rgbs.add(hslToRgb((h + 90f) % 360f, s, l));
                rgbs.add(hslToRgb((h + 180f) % 360f, s, l));
                rgbs.add(hslToRgb((h + 270f) % 360f, s, l));
                break;
            default:
                return error("Unknown harmony: " + harmony);
        }
        JSONArray colors = new JSONArray();
        for (int[] c : rgbs) {
            colors.put(rgbToHex(c[0], c[1], c[2]));
        }
        JSONObject out = new JSONObject()
                .put("baseHex", rgbToHex(rgb[0], rgb[1], rgb[2]))
                .put("harmony", harmony)
                .put("colors", colors);
        return ok(out, formatPaletteDisplay(colors, harmony));
    }

    /**
     * 在指定色相范围内生成若干饱和度、亮度适中的随机 HSL 颜色，并转为十六进制列表。
     *
     * @param params {@code count} 1–20；{@code hueMin}/{@code hueMax} 色相范围 0–360，若颠倒则自动交换
     * @return JSON：{@code colors} 数组
     * @throws Exception JSON 异常
     */
    private String handleRandomColor(JSONObject params) throws Exception {
        int count = clamp(params.optInt("count", 1), 1, 20);
        float hueMin = (float) clampDouble(params.optDouble("hueMin", 0), 0, 360);
        float hueMax = (float) clampDouble(params.optDouble("hueMax", 360), 0, 360);
        if (hueMax < hueMin) {
            float t = hueMin;
            hueMin = hueMax;
            hueMax = t;
        }
        JSONArray arr = new JSONArray();
        StringBuilder sb = new StringBuilder();
        sb.append("🎲 Random colors\n\n");
        sb.append("| # | Swatch | Hex |\n|---|---|---|\n");
        for (int i = 0; i < count; i++) {
            float hue = hueMin + RANDOM.nextFloat() * (hueMax - hueMin);
            float sat = 55f + RANDOM.nextFloat() * 40f;
            float light = 45f + RANDOM.nextFloat() * 25f;
            int[] rgb = hslToRgb(hue, sat, light);
            String hex = rgbToHex(rgb[0], rgb[1], rgb[2]);
            arr.put(hex);
            sb.append("| ").append(i + 1).append(" | ").append(blockEmojiForRgb(rgb[0], rgb[1], rgb[2]))
                    .append(" | #").append(hex).append(" |\n");
        }
        JSONObject out = new JSONObject().put("colors", arr).put("count", count);
        return ok(out, sb.toString().trim());
    }

    /**
     * 根据十六进制颜色在命名色表中查找欧氏距离最近的颜色及距离。
     *
     * @param params {@code hex} 或 {@code input}，六位色值（可有 #）
     * @return JSON：closestName、closestHex、distance 等
     * @throws Exception JSON 异常
     */
    private String handleGetColorInfo(JSONObject params) throws Exception {
        String hex = normalizeHexParam(params.optString("hex", params.optString("input", "")));
        if (hex == null) {
            return error("Invalid hex");
        }
        int[] rgb = parseHexToRgb(hex);
        if (rgb == null) {
            return error("Invalid hex color");
        }
        NamedMatch m = findClosestNamed(rgb[0], rgb[1], rgb[2]);
        JSONObject out = new JSONObject()
                .put("hex", hex)
                .put("closestName", m.name)
                .put("closestHex", "#" + m.hex)
                .put("distance", round4(m.distance));
        String display = "🎨 Named color\n"
                + blockLine(rgb[0], rgb[1], rgb[2]) + "\n\n"
                + "| Item | Value |\n|---|---|\n"
                + "| Input HEX | #" + mdCell(hex) + " |\n"
                + "| Closest name | " + mdCell(m.name) + " |\n"
                + "| Closest HEX | #" + mdCell(m.hex) + " |\n"
                + "| ΔRGB distance | " + round4(m.distance) + " |";
        return ok(out, display);
    }

    /**
     * 将 RGB 整数三元组展开为 hex、rgb/hsl/cmyk 的多种字符串与分量字段。
     *
     * @param rgb 长度 3，0–255
     * @return 完整颜色描述 JSON
     * @throws Exception JSON 异常
     */
    private static JSONObject buildColorJson(int[] rgb) throws Exception {
        int r = rgb[0], g = rgb[1], b = rgb[2];
        float[] hsl = rgbToHsl(r, g, b);
        float[] cmyk = rgbToCmyk(r, g, b);
        return new JSONObject()
                .put("hex", "#" + rgbToHex(r, g, b))
                .put("rgb", String.format(Locale.US, "%d,%d,%d", r, g, b))
                .put("rgbCss", String.format(Locale.US, "rgb(%d, %d, %d)", r, g, b))
                .put("hsl", String.format(Locale.US, "%.2f,%.2f,%.2f", hsl[0], hsl[1], hsl[2]))
                .put("hslCss", String.format(Locale.US, "hsl(%.1f, %.1f%%, %.1f%%)", hsl[0], hsl[1], hsl[2]))
                .put("cmyk", String.format(Locale.US, "%.2f,%.2f,%.2f,%.2f", cmyk[0], cmyk[1], cmyk[2], cmyk[3]))
                .put("r", r).put("g", g).put("b", b)
                .put("h", round4(hsl[0])).put("s", round4(hsl[1])).put("l", round4(hsl[2]))
                .put("c", round4(cmyk[0])).put("m", round4(cmyk[1])).put("y", round4(cmyk[2])).put("k", round4(cmyk[3]));
    }

    /**
     * 将 RGB 格式化为单一目标格式的字符串。
     *
     * @param rgb RGB 三元组
     * @param to  {@code hex}、{@code rgb}、{@code hsl}、{@code cmyk}
     * @return 格式化串；未知格式返回 null
     */
    private static String formatAs(int[] rgb, String to) {
        int r = rgb[0], g = rgb[1], b = rgb[2];
        switch (to) {
            case "hex":
                return "#" + rgbToHex(r, g, b);
            case "rgb":
                return String.format(Locale.US, "%d,%d,%d", r, g, b);
            case "hsl": {
                float[] h = rgbToHsl(r, g, b);
                return String.format(Locale.US, "%.2f,%.2f,%.2f", h[0], h[1], h[2]);
            }
            case "cmyk": {
                float[] c = rgbToCmyk(r, g, b);
                return String.format(Locale.US, "%.2f,%.2f,%.2f,%.2f", c[0], c[1], c[2], c[3]);
            }
            default:
                return null;
        }
    }

    /**
     * 组合带 emoji 色块示意与多行 HEX/RGB/HSL/CMYK 的展示文本。
     *
     * @param rgb  RGB 分量
     * @param full {@link #buildColorJson} 结果
     * @return 多行展示字符串
     * @throws Exception JSON 异常
     */
    private static String mdCell(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("|", "\\|").replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ');
    }

    private static String formatDisplayBlocks(int[] rgb, JSONObject full) throws Exception {
        String blocks = blockLine(rgb[0], rgb[1], rgb[2]);
        return "🎨 Color\n"
                + blocks + "\n\n"
                + "| Format | Value |\n|---|---|\n"
                + "| HEX | " + mdCell(full.optString("hex")) + " |\n"
                + "| RGB | " + mdCell(full.optString("rgb")) + " |\n"
                + "| RGB (CSS) | " + mdCell(full.optString("rgbCss")) + " |\n"
                + "| HSL | " + mdCell(full.optString("hsl")) + " |\n"
                + "| HSL (CSS) | " + mdCell(full.optString("hslCss")) + " |\n"
                + "| CMYK | " + mdCell(full.optString("cmyk")) + " |";
    }

    /**
     * 将调色板数组格式化为每行一色的展示（含色相 emoji）。
     *
     * @param colors  十六进制字符串数组
     * @param harmony 和谐类型名，用于标题
     * @return 展示文本
     */
    private static String formatPaletteDisplay(JSONArray colors, String harmony) {
        StringBuilder sb = new StringBuilder();
        sb.append("🧩 Palette (").append(harmony).append(")\n\n");
        sb.append("| # | Swatch | Hex |\n|---|---|---|\n");
        for (int i = 0; i < colors.length(); i++) {
            String hx = colors.optString(i, "");
            int[] rgb = parseHexToRgb(hx.replace("#", ""));
            String hexCell = hx.startsWith("#") ? hx : "#" + hx;
            if (rgb != null) {
                sb.append("| ").append(i + 1).append(" | ").append(blockEmojiForRgb(rgb[0], rgb[1], rgb[2]))
                        .append(" | ").append(mdCell(hexCell)).append(" |\n");
            } else {
                sb.append("| ").append(i + 1).append(" | ⬛ | ").append(mdCell(hexCell)).append(" |\n");
            }
        }
        return sb.toString().trim();
    }

    /**
     * 使用 Unicode 全块字符 {@code █} 拼成一行粗色条（纯文本环境下的简易色块占位；未按 RGB 着色，仅作视觉分隔）。
     *
     * @param r 红 0–255（当前实现未使用，保留签名便于将来按真彩色终端扩展）
     * @param g 绿
     * @param b 蓝
     * @return 由多个 █ 组成的字符串
     */
    private static String blockLine(int r, int g, int b) {
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append('\u2588');
        }
        return sb.toString();
    }

    /**
     * 根据 HSL 将颜色粗略映射为彩圆 emoji（黑/白/灰有单独分支）。
     *
     * @param r 红
     * @param g 绿
     * @param b 蓝
     * @return 单个 emoji 字符
     */
    private static String blockEmojiForRgb(int r, int g, int b) {
        float[] hsl = rgbToHsl(r, g, b);
        float h = hsl[0];
        float s = hsl[1];
        float l = hsl[2];
        if (l < 18f) return "⬛";
        if (l > 92f && s < 12f) return "⬜";
        if (s < 12f) {
            if (l < 40f) return "⬛";
            return "◻️";
        }
        if (h < 30f || h >= 330f) return "🟥";
        if (h < 70f) return "🟧";
        if (h < 150f) return "🟨";
        if (h < 200f) return "🟩";
        if (h < 260f) return "🟦";
        return "🟪";
    }

    /**
     * 自动识别输入格式：依次尝试十六进制、逗号分隔 RGB/HSL/CMYK、CSS 函数、命名色。
     *
     * @param input 原始颜色字符串
     * @return RGB 三元组或 null
     */
    private static int[] parseColorAuto(String input) {
        String s = input.trim();
        if (s.isEmpty()) return null;
        int[] rgb = parseHexFlexible(s);
        if (rgb != null) return rgb;
        rgb = parseRgbTriplet(s);
        if (rgb != null) return rgb;
        rgb = parseHslTriplet(s);
        if (rgb != null) return rgb;
        rgb = parseCmykQuad(s);
        if (rgb != null) return rgb;
        Matcher m = RGB_FUNC.matcher(s);
        if (m.find()) {
            return clampRgb(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        }
        m = HSL_FUNC.matcher(s);
        if (m.find()) {
            float h = Float.parseFloat(m.group(1));
            float sp = Float.parseFloat(m.group(2));
            float lp = Float.parseFloat(m.group(3));
            if (sp <= 1f && !s.contains("%")) sp *= 100f;
            if (lp <= 1f && !s.contains("%")) lp *= 100f;
            return hslToRgb(h, sp, lp);
        }
        String lower = s.toLowerCase(Locale.ROOT);
        if (NAMED_HEX.containsKey(lower)) {
            return parseHexToRgb(NAMED_HEX.get(lower));
        }
        return null;
    }

    /**
     * 在指定格式 {@code from} 下解析颜色（hex/rgb/hsl/cmyk）。
     *
     * @param input 颜色串
     * @param from  小写格式名
     * @return RGB 或 null
     */
    private static int[] parseColor(String input, String from) {
        String s = input.trim();
        switch (from) {
            case "hex":
                return parseHexFlexible(s);
            case "rgb":
                int[] t = parseRgbTriplet(s);
                if (t != null) return t;
                Matcher m = RGB_FUNC.matcher(s);
                if (m.find()) {
                    return clampRgb(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
                }
                return null;
            case "hsl":
                t = parseHslTriplet(s);
                if (t != null) return t;
                m = HSL_FUNC.matcher(s);
                if (m.find()) {
                    float h = Float.parseFloat(m.group(1));
                    float sp = Float.parseFloat(m.group(2));
                    float lp = Float.parseFloat(m.group(3));
                    if (sp <= 1f && !s.contains("%")) sp *= 100f;
                    if (lp <= 1f && !s.contains("%")) lp *= 100f;
                    return hslToRgb(h, sp, lp);
                }
                return null;
            case "cmyk":
                return parseCmykQuad(s);
            default:
                return null;
        }
    }

    /**
     * 校验并解析带可选 # 的十六进制串为 RGB。
     *
     * @param s 输入
     * @return RGB 或 null
     */
    private static int[] parseHexFlexible(String s) {
        String x = s.startsWith("#") ? s.substring(1) : s;
        Matcher mx = HEX_PATTERN.matcher(x);
        if (!mx.matches()) return null;
        return parseHexToRgb(x);
    }

    /**
     * 将 3/6/8 位十六进制（可含 #）转为 RGB；8 位时丢弃 Alpha 通道。
     *
     * @param hex 色值字符串
     * @return RGB 三元组或 null
     */
    private static int[] parseHexToRgb(String hex) {
        if (hex == null) return null;
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        if (h.length() == 8) h = h.substring(0, 6);
        if (h.length() == 3) {
            h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
        }
        if (h.length() != 6) return null;
        try {
            int v = Integer.parseInt(h, 16);
            return clampRgb((v >> 16) & 0xff, (v >> 8) & 0xff, v & 0xff);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 规范化插件参数中的 hex：补 #、解析并输出标准六位大写无 # 形式供内部统一使用。
     *
     * @param raw 用户输入
     * @return 六位大写 hex，无 #；非法返回 null
     */
    private static String normalizeHexParam(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String s = raw.trim();
        if (!s.startsWith("#")) s = "#" + s;
        int[] rgb = parseHexToRgb(s);
        if (rgb == null) return null;
        return rgbToHex(rgb[0], rgb[1], rgb[2]);
    }

    /**
     * 从逗号或空白分隔的三元组解析 RGB（可含 rgb 字样与括号）。
     *
     * @param s 输入串
     * @return RGB 或 null
     */
    private static int[] parseRgbTriplet(String s) {
        String t = s.replaceAll("[()]", " ").replace("rgb", " ").replace("RGB", " ").trim();
        String[] parts = t.split("[,\\s]+");
        List<Integer> nums = new ArrayList<>();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            try {
                nums.add(Integer.parseInt(p));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (nums.size() < 3) return null;
        return clampRgb(nums.get(0), nums.get(1), nums.get(2));
    }

    /**
     * 解析 HSL 三个浮点数（可带 %），无 % 且 S/L≤1 时按 0–1 小数缩放为百分比再转 RGB。
     *
     * @param s 输入串
     * @return RGB 或 null
     */
    private static int[] parseHslTriplet(String s) {
        String t = s.replaceAll("[()]", " ").replace("hsl", " ").replace("HSL", " ").replace("%", " ").trim();
        String[] parts = t.split("[,\\s]+");
        List<Float> nums = new ArrayList<>();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            try {
                nums.add(Float.parseFloat(p));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (nums.size() < 3) return null;
        float h = nums.get(0);
        float sp = nums.get(1);
        float lp = nums.get(2);
        if (sp <= 1f && lp <= 1f && !s.contains("%")) {
            sp *= 100f;
            lp *= 100f;
        }
        return hslToRgb(h, sp, lp);
    }

    /**
     * 解析 CMYK 四元组（0–100 或 0–1），转为 RGB。
     *
     * @param s 输入串
     * @return RGB 或 null
     */
    private static int[] parseCmykQuad(String s) {
        String t = s.replaceAll("[()]", " ").replace("cmyk", " ").replace("CMYK", " ").replace("%", " ").trim();
        String[] parts = t.split("[,\\s]+");
        List<Float> nums = new ArrayList<>();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            try {
                nums.add(Float.parseFloat(p));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (nums.size() < 4) return null;
        float c = nums.get(0), m = nums.get(1), y = nums.get(2), k = nums.get(3);
        if (c <= 1f && m <= 1f && y <= 1f && k <= 1f && !s.contains("%")) {
            c *= 100f;
            m *= 100f;
            y *= 100f;
            k *= 100f;
        }
        return cmykToRgb(c, m, y, k);
    }

    /**
     * 将 r、g、b 各自限制在 0–255。
     *
     * @param r 红
     * @param g 绿
     * @param b 蓝
     * @return 新数组 {@code [r',g',b']}
     */
    private static int[] clampRgb(int r, int g, int b) {
        return new int[]{clampByte(r), clampByte(g), clampByte(b)};
    }

    /**
     * @param v 分量
     * @return 限制在 [0,255]
     */
    private static int clampByte(int v) {
        if (v < 0) return 0;
        if (v > 255) return 255;
        return v;
    }

    /**
     * @param v  值
     * @param lo 下界（含）
     * @param hi 上界（含）
     * @return 裁剪后的整数
     */
    private static int clamp(int v, int lo, int hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    /**
     * 双精度版本的 {@link #clamp}。
     *
     * @param v  值
     * @param lo 下界
     * @param hi 上界
     * @return 裁剪结果
     */
    private static double clampDouble(double v, double lo, double hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    /**
     * sRGB 线性归一化后的 RGB 转 HSL，H 为 0–360，S/L 为 0–100。
     *
     * @param r 红 0–255
     * @param g 绿
     * @param b 蓝
     * @return float[3] = {H,S,L}
     */
    private static float[] rgbToHsl(int r, int g, int b) {
        float rf = r / 255f;
        float gf = g / 255f;
        float bf = b / 255f;
        float max = Math.max(rf, Math.max(gf, bf));
        float min = Math.min(rf, Math.min(gf, bf));
        float h = 0f;
        float s;
        float l = (max + min) / 2f;
        float d = max - min;
        if (d < 1e-6f) {
            s = 0f;
        } else {
            s = l > 0.5f ? d / (2f - max - min) : d / (max + min);
            if (max == rf) {
                h = (gf - bf) / d + (gf < bf ? 6f : 0f);
            } else if (max == gf) {
                h = (bf - rf) / d + 2f;
            } else {
                h = (rf - gf) / d + 4f;
            }
            h *= 60f;
            if (h < 0) h += 360f;
        }
        return new float[]{h, s * 100f, l * 100f};
    }

    /**
     * HSL（H 度、S/L 为 0–100）转 RGB，使用常见分段线性插值算法。
     *
     * @param h 色相 0–360
     * @param s 饱和度 0–100
     * @param l 亮度 0–100
     * @return RGB 三元组
     */
    private static int[] hslToRgb(float h, float s, float l) {
        float hf = ((h % 360f) + 360f) % 360f;
        float sf = clamp(s, 0f, 100f) / 100f;
        float lf = clamp(l, 0f, 100f) / 100f;
        if (sf <= 0f) {
            int v = Math.round(lf * 255f);
            return clampRgb(v, v, v);
        }
        float q = lf < 0.5f ? lf * (1f + sf) : lf + sf - lf * sf;
        float p = 2f * lf - q;
        float hk = hf / 360f;
        float tr = hk + 1f / 3f;
        float tg = hk;
        float tb = hk - 1f / 3f;
        int r = Math.round(hueToRgb(p, q, tr) * 255f);
        int g = Math.round(hueToRgb(p, q, tg) * 255f);
        int b = Math.round(hueToRgb(p, q, tb) * 255f);
        return clampRgb(r, g, b);
    }

    /**
     * HSL 转 RGB 时辅助函数：对分段参数 t 做 0–1 周期折叠并插值。
     *
     * @param p 暗分量
     * @param q 亮分量
     * @param t 色相相关参数
     * @return 单通道 0–1
     */
    private static float hueToRgb(float p, float q, float t) {
        float tt = t;
        if (tt < 0) tt += 1f;
        if (tt > 1) tt -= 1f;
        if (tt < 1f / 6f) return p + (q - p) * 6f * tt;
        if (tt < 0.5f) return q;
        if (tt < 2f / 3f) return p + (q - p) * (2f / 3f - tt) * 6f;
        return p;
    }

    /**
     * RGB 转 CMYK（各分量 0–100，K 为黑版）。
     *
     * @param r 红
     * @param g 绿
     * @param b 蓝
     * @return float[4] = {C,M,Y,K}
     */
    private static float[] rgbToCmyk(int r, int g, int b) {
        float rf = r / 255f;
        float gf = g / 255f;
        float bf = b / 255f;
        float k = 1f - Math.max(rf, Math.max(gf, bf));
        if (k >= 1f - 1e-6f) {
            return new float[]{0f, 0f, 0f, 100f};
        }
        float c = (1f - rf - k) / (1f - k);
        float m = (1f - gf - k) / (1f - k);
        float y = (1f - bf - k) / (1f - k);
        return new float[]{c * 100f, m * 100f, y * 100f, k * 100f};
    }

    /**
     * CMYK（0–100）转 RGB。
     *
     * @param c 青
     * @param m 品红
     * @param y 黄
     * @param k 黑
     * @return RGB
     */
    private static int[] cmykToRgb(float c, float m, float y, float k) {
        float cf = clamp(c, 0f, 100f) / 100f;
        float mf = clamp(m, 0f, 100f) / 100f;
        float yf = clamp(y, 0f, 100f) / 100f;
        float kf = clamp(k, 0f, 100f) / 100f;
        int r = Math.round(255f * (1f - cf) * (1f - kf));
        int g = Math.round(255f * (1f - mf) * (1f - kf));
        int b = Math.round(255f * (1f - yf) * (1f - kf));
        return clampRgb(r, g, b);
    }

    /**
     * 浮点裁剪到 [lo,hi]。
     *
     * @param v  值
     * @param lo 下界
     * @param hi 上界
     * @return 裁剪结果
     */
    private static float clamp(float v, float lo, float hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    /**
     * @param r 红
     * @param g 绿
     * @param b 蓝
     * @return 六位大写十六进制，无 # 前缀
     */
    private static String rgbToHex(int r, int g, int b) {
        return String.format(Locale.US, "%02X%02X%02X", r, g, b);
    }

    /**
     * @param x 原始双精度
     * @return 保留四位小数（四舍五入）
     */
    private static double round4(double x) {
        return Math.round(x * 10000d) / 10000d;
    }

    /** 与预置命名色比对时的最近匹配结果（RGB 欧氏距离，distance 为开方后距离）。 */
    private static class NamedMatch {
        final String name;
        final String hex;
        final double distance;

        /**
         * @param name     CSS 命名色名称
         * @param hex      对应六位大写十六进制（无 #）
         * @param distance 与输入颜色的 RGB 欧氏距离
         */
        NamedMatch(String name, String hex, double distance) {
            this.name = name;
            this.hex = hex;
            this.distance = distance;
        }
    }

    /**
     * 遍历 {@link #NAMED_HEX} 全表，找与目标 RGB 欧氏距离最小的命名色。
     *
     * @param r 红
     * @param g 绿
     * @param b 蓝
     * @return 最近匹配及距离
     */
    private static NamedMatch findClosestNamed(int r, int g, int b) {
        String bestName = "black";
        String bestHex = "000000";
        double best = Double.MAX_VALUE;
        for (Map.Entry<String, String> e : NAMED_HEX.entrySet()) {
            int[] cr = parseHexToRgb(e.getValue());
            if (cr == null) continue;
            double d = colorDistanceSq(r, g, b, cr[0], cr[1], cr[2]);
            if (d < best) {
                best = d;
                bestName = e.getKey();
                bestHex = e.getValue();
            }
        }
        return new NamedMatch(bestName, bestHex, Math.sqrt(best));
    }

    /**
     * 两颜色在 RGB 空间中的平方距离（未开方，便于比较大小）。
     *
     * @param r1 点1 红
     * @param g1 点1 绿
     * @param b1 点1 蓝
     * @param r2 点2 红
     * @param g2 点2 绿
     * @param b2 点2 蓝
     * @return 平方距离
     */
    private static double colorDistanceSq(int r1, int g1, int b1, int r2, int g2, int b2) {
        double dr = r1 - r2;
        double dg = g1 - g2;
        double db = b1 - b2;
        return dr * dr + dg * dg + db * db;
    }

    /**
     * @param value 原始 JSON 参数
     * @return 空则 {@code "{}"}
     */
    private static String emptyJson(String value) {
        return value == null || value.trim().isEmpty() ? "{}" : value;
    }

    /**
     * 成功响应：{@code output} 为字符串化的 JSON，可选 {@code _displayText}。
     *
     * @param output      业务结果对象序列化到 {@code output} 字段
     * @param displayText 展示文案
     * @return 完整响应 JSON 字符串
     * @throws Exception JSON 异常
     */
    private static String ok(JSONObject output, String displayText) throws Exception {
        JSONObject r = new JSONObject()
                .put("success", true)
                .put("output", output.toString());
        if (displayText != null && !displayText.isEmpty()) {
            r.put("_displayText", displayText);
        }
        return r.toString();
    }

    /**
     * @param message 错误说明
     * @return {@code success=false} 的 JSON
     * @throws Exception JSON 异常
     */
    private static String error(String message) throws Exception {
        return new JSONObject()
                .put("success", false)
                .put("error", message)
                .toString();
    }
}
