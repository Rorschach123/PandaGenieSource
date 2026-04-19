package ai.rorsch.moduleplugins.led_banner;

import android.content.Context;
import ai.rorsch.pandagenie.module.runtime.HtmlOutputHelper;
import ai.rorsch.pandagenie.module.runtime.ModulePlugin;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * PandaGenie「LED 灯牌」模块插件。
 * <p>
 * <b>模块用途：</b>根据文字、颜色、滚动模式与特效生成灯牌展示所需的 JSON/CSS 数据，
 * 并可指示宿主打开模块 Web 界面全屏展示；同时提供赛博风与明星应援等预设配色模板。
 * </p>
 * <p>
 * <b>对外 API（{@link #invoke} 的 {@code action}）：</b>
 * </p>
 * <ul>
 *   <li>{@code generateBanner} — 从参数生成灯牌配置，响应中带 {@code _openModule} 打开展示</li>
 *   <li>{@code getTemplates} — 返回赛博/霓虹类配色模板列表</li>
 *   <li>{@code getStarTemplates} — 返回明星/应援类配色模板列表</li>
 *   <li>{@code applyTemplate} — 按模板名套用字色/背景色并生成灯牌（支持模糊匹配模板名）</li>
 * </ul>
 * <p>
 * 本类由宿主 {@code ModuleRuntime} 反射加载；{@code generateBanner} / {@code applyTemplate} 成功时
 * 通过 {@code _openModule: true} 触发 UI 层打开对应模块页。
 * </p>
 */
public class LedBannerPlugin implements ModulePlugin {

    /** 赛博/霓虹风格预设：每项为 [显示名, 字色十六进制, 背景色十六进制]。 */
    private static final String[][] CYBER_TEMPLATES = {
        {"霓虹粉蓝", "#FF6EC7", "#0A0A2E"},
        {"赛博朋克", "#00FFFF", "#1A0033"},
        {"激光绿", "#39FF14", "#0D0D0D"},
        {"电子紫", "#BF00FF", "#0A0A0A"},
        {"烈焰橙", "#FF4500", "#1A0A00"},
        {"冰蓝光", "#00BFFF", "#000A14"},
        {"暗夜金", "#FFD700", "#0A0A00"},
        {"荧光黄", "#CCFF00", "#0D0D00"},
        {"深海蓝光", "#00CED1", "#001A1A"},
        {"血月红", "#DC143C", "#140000"},
        {"极光绿", "#7FFF00", "#001400"},
        {"星空紫", "#9370DB", "#0A0014"},
        {"钛金银", "#C0C0C0", "#1A1A1A"},
        {"玫瑰金", "#FF69B4", "#1A0A10"},
        {"翡翠绿", "#50C878", "#001A0D"},
        {"琥珀橙", "#FFBF00", "#1A1000"},
        {"宝石蓝", "#0F52BA", "#000A1A"},
        {"樱花粉", "#FFB7C5", "#1A0D12"},
        {"经典黑白", "#FFFFFF", "#000000"},
        {"经典红黑", "#FF0000", "#000000"}
    };

    /** 明星/应援主题预设：结构同 {@link #CYBER_TEMPLATES}。 */
    private static final String[][] STAR_TEMPLATES = {
        {"TFBOYS-王俊凯", "#FFA500", "#000000"},
        {"TFBOYS-王源", "#00BFFF", "#000000"},
        {"TFBOYS-易烊千玺", "#FF69B4", "#000000"},
        {"蔡徐坤", "#FF1493", "#000000"},
        {"周杰伦", "#FFD700", "#1A0A00"},
        {"邓紫棋", "#FF6EB4", "#0A0014"},
        {"薛之谦", "#00CED1", "#000000"},
        {"华晨宇", "#FF4500", "#0A0A0A"},
        {"张艺兴", "#9400D3", "#000000"},
        {"鹿晗", "#FFD700", "#000000"},
        {"朱一龙", "#FF6347", "#0A0000"},
        {"肖战", "#FF0000", "#000000"},
        {"王一博", "#00FF00", "#000000"},
        {"龚俊", "#FF8C00", "#000000"},
        {"时代少年团", "#00BFFF", "#0A0014"},
        {"刘耀文", "#FF69B4", "#000A14"},
        {"丁程鑫", "#7B68EE", "#000000"},
        {"张真源", "#00CED1", "#0A0A0A"},
        {"BLACKPINK", "#FF1493", "#000000"},
        {"BTS防弹少年团", "#9370DB", "#000000"},
        {"EXO", "#C0C0C0", "#0A0A0A"},
        {"NCT", "#00FF7F", "#000000"},
        {"TWICE", "#FF6EC7", "#1A0033"},
        {"IVE", "#FFB6C1", "#0A0014"},
        {"aespa", "#7F00FF", "#000000"},
        {"NewJeans", "#87CEEB", "#000A14"},
        {"SEVENTEEN", "#FF8C69", "#000000"},
        {"Stray Kids", "#FF0000", "#0D0D0D"},
        {"(G)I-DLE", "#9B59B6", "#000000"},
        {"LE SSERAFIM", "#FF69B4", "#0A0A2E"},
        {"红色应援", "#FF0000", "#000000"},
        {"粉色应援", "#FF69B4", "#000000"},
        {"蓝色应援", "#00BFFF", "#000000"},
        {"紫色应援", "#9370DB", "#000000"},
        {"绿色应援", "#00FF7F", "#000000"},
        {"橙色应援", "#FFA500", "#000000"},
        {"金色应援", "#FFD700", "#000000"},
        {"白色应援", "#FFFFFF", "#000000"}
    };

    /**
     * 模块入口：解析 JSON 参数并分发到各 action。
     *
     * @param context    Android 上下文
     * @param action     操作名
     * @param paramsJson JSON 参数字符串，空则按 {@code "{}"} 处理
     * @return 成功为含 {@code success}、{@code output} 的 JSON；生成类 action 另含 {@code _openModule}、{@code _displayText}
     * @throws Exception 构造 JSON 时可能抛出
     */
    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        try {
            JSONObject params = new JSONObject(emptyJson(paramsJson));
            switch (action) {
                case "generateBanner": {
                    JSONObject r = generateBanner(params);
                    return okOpenModule(r, formatBannerDisplay(r), formatBannerDisplayHtml(r));
                }
                case "getTemplates":
                    return ok(getTemplates());
                case "getStarTemplates":
                    return ok(getStarTemplates());
                case "applyTemplate": {
                    JSONObject r = applyTemplate(params);
                    return okOpenModule(r, formatBannerDisplay(r), formatBannerDisplayHtml(r));
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
     * 将灯牌生成结果格式化为简短中文说明，供聊天或通知展示。
     *
     * @param r {@link #generateBanner} 或 {@link #applyTemplate} 产出的配置对象
     * @return 多行展示字符串
     */
    private String formatBannerDisplay(JSONObject r) {
        StringBuilder sb = new StringBuilder();
        sb.append("\uD83D\uDCA1 灯牌已生成\n");
        sb.append("━━━━━━━━━━━━━━\n");
        sb.append("\uD83D\uDCDD 文字: ").append(r.optString("text", "")).append("\n");
        sb.append("\uD83C\uDFA8 字色: ").append(r.optString("fontColor", ""));
        sb.append(" | 背景: ").append(r.optString("bgColor", "")).append("\n");
        sb.append("\uD83D\uDCFA 模式: ").append(r.optString("modeName", "")).append("\n");
        sb.append("\uD83D\uDD26 点击下方按钮打开灯牌全屏展示");
        return sb.toString();
    }

    private boolean isZh() {
        return Locale.getDefault().getLanguage().toLowerCase(Locale.ROOT).startsWith("zh");
    }

    private String formatBannerDisplayHtml(JSONObject r) {
        String title = isZh() ? "灯牌已生成" : "Banner ready";
        String body = HtmlOutputHelper.keyValue(new String[][]{
                {isZh() ? "文字" : "Text", r.optString("text", "")},
                {isZh() ? "字色" : "Font", r.optString("fontColor", "")},
                {isZh() ? "背景" : "Background", r.optString("bgColor", "")},
                {isZh() ? "模式" : "Mode", r.optString("modeName", "")}
        }) + HtmlOutputHelper.p(isZh() ? "点击下方打开全屏展示" : "Tap below for fullscreen");
        return HtmlOutputHelper.card("💡", title, body + HtmlOutputHelper.successBadge());
    }

    /**
     * 根据用户参数组装灯牌完整配置（含 CSS 片段、模式中文名、特效开关）。
     *
     * @param params 支持：{@code text}（必填）、{@code fontColor}/{@code bgColor}、{@code mode}（scroll/fade/static）、
     *               {@code fontSize}（12–200）、{@code speed}（1–50）、{@code effects}（逗号/空格分隔关键字）
     * @return 包含 text、颜色、mode、effects、cssStyle、bgStyle、textShadow 等字段的 JSON
     * @throws Exception 缺少必填项等时抛出
     */
    private JSONObject generateBanner(JSONObject params) throws Exception {
        String text = params.optString("text", "").trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("text is required");
        }

        String fontColor = normalizeColor(params.optString("fontColor", "#FFFFFF"));
        String bgColor = normalizeColor(params.optString("bgColor", "#000000"));
        String mode = params.optString("mode", "scroll").toLowerCase(Locale.ROOT);
        int fontSize = clamp(params.optInt("fontSize", 72), 12, 200);
        int speed = clamp(params.optInt("speed", 10), 1, 50);
        String effectsStr = params.optString("effects", "");

        if (!mode.equals("scroll") && !mode.equals("fade") && !mode.equals("static")) {
            mode = "scroll"; // 非法模式回退为滚动
        }

        JSONObject effects = parseEffects(effectsStr);

        JSONObject result = new JSONObject();
        result.put("text", text);
        result.put("fontColor", fontColor);
        result.put("bgColor", bgColor);
        result.put("mode", mode);
        result.put("modeName", getModeName(mode));
        result.put("fontSize", fontSize);
        result.put("speed", speed);
        result.put("effects", effects);

        String textShadow = "";
        if (effects.optBoolean("glow", false)) {
            // 多层 text-shadow 模拟霓虹发光
            textShadow = "0 0 10px " + fontColor + ", 0 0 20px " + fontColor + ", 0 0 40px " + fontColor;
        }
        result.put("textShadow", textShadow);

        StringBuilder fontStyle = new StringBuilder();
        if (effects.optBoolean("bold", false)) fontStyle.append("font-weight:bold;");
        if (effects.optBoolean("italic", false)) fontStyle.append("font-style:italic;");
        fontStyle.append("font-size:").append(fontSize).append("px;");
        fontStyle.append("color:").append(fontColor).append(";");
        if (!textShadow.isEmpty()) fontStyle.append("text-shadow:").append(textShadow).append(";");
        result.put("cssStyle", fontStyle.toString());

        result.put("bgStyle", "background:" + bgColor + ";");

        return result;
    }

    /**
     * 返回赛博风模板数组及数量。
     *
     * @return JSON：{@code templates} 为数组，每项含 name/fontColor/bgColor；{@code count} 为条数
     * @throws Exception JSON 构造异常
     */
    private JSONObject getTemplates() throws Exception {
        JSONArray arr = new JSONArray();
        for (String[] t : CYBER_TEMPLATES) {
            JSONObject obj = new JSONObject();
            obj.put("name", t[0]);
            obj.put("fontColor", t[1]);
            obj.put("bgColor", t[2]);
            arr.put(obj);
        }
        JSONObject result = new JSONObject();
        result.put("templates", arr);
        result.put("count", arr.length());
        return result;
    }

    /**
     * 返回明星/应援类模板列表（结构同 {@link #getTemplates}）。
     *
     * @return 含 templates 与 count 的 JSON
     * @throws Exception JSON 构造异常
     */
    private JSONObject getStarTemplates() throws Exception {
        JSONArray arr = new JSONArray();
        for (String[] t : STAR_TEMPLATES) {
            JSONObject obj = new JSONObject();
            obj.put("name", t[0]);
            obj.put("fontColor", t[1]);
            obj.put("bgColor", t[2]);
            arr.put(obj);
        }
        JSONObject result = new JSONObject();
        result.put("templates", arr);
        result.put("count", arr.length());
        return result;
    }

    /**
     * 根据模板名称查找配色，再调用 {@link #generateBanner} 生成灯牌；未提供 {@code text} 时用模板名作为显示文字。
     *
     * @param params 必填 {@code templateName}；可选 {@code text}
     * @return 在 {@link #generateBanner} 结果上增加 {@code templateName} 字段
     * @throws Exception 找不到模板时抛出
     */
    private JSONObject applyTemplate(JSONObject params) throws Exception {
        String templateName = params.optString("templateName", "").trim();
        String text = params.optString("text", "").trim();

        if (templateName.isEmpty()) {
            throw new IllegalArgumentException("templateName is required");
        }
        if (text.isEmpty()) {
            text = templateName;
        }

        String fontColor = null;
        String bgColor = null;

        for (String[] t : CYBER_TEMPLATES) {
            if (t[0].equals(templateName)) {
                fontColor = t[1];
                bgColor = t[2];
                break;
            }
        }
        if (fontColor == null) {
            for (String[] t : STAR_TEMPLATES) {
                if (t[0].equals(templateName)) {
                    fontColor = t[1];
                    bgColor = t[2];
                    break;
                }
            }
        }
        if (fontColor == null) {
            String nameLower = templateName.toLowerCase(Locale.ROOT);
            // 模糊匹配：模板显示名包含用户输入子串
            for (String[] t : CYBER_TEMPLATES) {
                if (t[0].toLowerCase(Locale.ROOT).contains(nameLower)) {
                    fontColor = t[1];
                    bgColor = t[2];
                    break;
                }
            }
        }
        if (fontColor == null) {
            String nameLower = templateName.toLowerCase(Locale.ROOT);
            for (String[] t : STAR_TEMPLATES) {
                if (t[0].toLowerCase(Locale.ROOT).contains(nameLower)) {
                    fontColor = t[1];
                    bgColor = t[2];
                    break;
                }
            }
        }
        if (fontColor == null) {
            throw new IllegalArgumentException("Template not found: " + templateName);
        }

        JSONObject fakeParams = new JSONObject();
        fakeParams.put("text", text);
        fakeParams.put("fontColor", fontColor);
        fakeParams.put("bgColor", bgColor);
        fakeParams.put("mode", "scroll");
        fakeParams.put("fontSize", 72);
        fakeParams.put("speed", 10);
        fakeParams.put("effects", "glow");

        JSONObject result = generateBanner(fakeParams); // 统一默认：滚动 + 中等字号 + 发光
        result.put("templateName", templateName);
        return result;
    }

    /**
     * 将逗号、分号或空白分隔的特效关键字解析为布尔开关 JSON。
     *
     * @param effectsStr 原始字符串，支持英文关键字及少量中文触发词（如「闪」「粗」）
     * @return 含 glow、flash、shake、bold、italic 等字段的 JSONObject
     * @throws Exception JSON 写入异常
     */
    private JSONObject parseEffects(String effectsStr) throws Exception {
        JSONObject effects = new JSONObject();
        effects.put("glow", false);
        effects.put("flash", false);
        effects.put("shake", false);
        effects.put("bold", false);
        effects.put("italic", false);

        if (effectsStr == null || effectsStr.trim().isEmpty()) return effects;

        String[] parts = effectsStr.toLowerCase(Locale.ROOT).split("[,;\\s]+");
        for (String p : parts) {
            p = p.trim();
            if (p.equals("glow") || p.equals("fluorescent") || p.contains("荧光")) effects.put("glow", true);
            else if (p.equals("flash") || p.contains("闪")) effects.put("flash", true);
            else if (p.equals("shake") || p.contains("抖")) effects.put("shake", true);
            else if (p.equals("bold") || p.contains("粗")) effects.put("bold", true);
            else if (p.equals("italic") || p.contains("斜")) effects.put("italic", true);
        }
        return effects;
    }

    /**
     * 将内部 mode 代码转为中文展示名。
     *
     * @param mode {@code scroll} / {@code fade} / {@code static} 等
     * @return 中文模式名或原样返回
     */
    private String getModeName(String mode) {
        switch (mode) {
            case "scroll": return "滚动";
            case "fade": return "浮现";
            case "static": return "静止";
            default: return mode;
        }
    }

    /**
     * 规范化十六进制颜色：补全 {@code #} 前缀并转大写。
     *
     * @param color 用户输入的颜色字符串
     * @return 合法形式的 {@code #RRGGBB} 风格字符串；空则默认白字
     */
    private String normalizeColor(String color) {
        if (color == null || color.trim().isEmpty()) return "#FFFFFF";
        color = color.trim();
        if (!color.startsWith("#")) color = "#" + color;
        return color.toUpperCase(Locale.ROOT);
    }

    /**
     * 将整型限制在 [min, max] 闭区间内。
     *
     * @param value 待限制值
     * @param min   下限
     * @param max   上限
     * @return 夹紧后的值
     */
    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * 空或空白参数转为 {@code "{}"}。
     *
     * @param value 原始 JSON 字符串
     * @return 非空 JSON 对象字面量
     */
    private String emptyJson(String value) {
        return value == null || value.trim().isEmpty() ? "{}" : value;
    }

    /**
     * 成功响应（不打开模块、无展示文案）。
     *
     * @param output 业务结果 JSON
     * @return 包装后的响应字符串
     * @throws Exception JSON 异常
     */
    private String ok(JSONObject output) throws Exception {
        return new JSONObject()
                .put("success", true)
                .put("output", output.toString())
                .toString();
    }

    /**
     * 成功响应并请求宿主打开模块界面，同时可附带 {@code _displayText}。
     *
     * @param output      传给模块前端的配置 JSON（序列化在 {@code output} 字段内）
     * @param displayText 可选用户可见摘要
     * @return 完整响应 JSON 字符串
     * @throws Exception JSON 异常
     */
    private String okOpenModule(JSONObject output, String displayText, String displayHtml) throws Exception {
        JSONObject result = new JSONObject()
                .put("success", true)
                .put("output", output.toString())
                .put("_openModule", true);
        if (displayText != null && !displayText.isEmpty()) {
            result.put("_displayText", displayText);
        }
        if (displayHtml != null && !displayHtml.isEmpty()) {
            result.put("_displayHtml", displayHtml);
        }
        return result.toString();
    }

    /**
     * 构造失败响应。
     *
     * @param message 错误说明
     * @return JSON 字符串
     * @throws Exception JSON 异常
     */
    private String error(String message) throws Exception {
        return new JSONObject()
                .put("success", false)
                .put("error", message)
                .toString();
    }
}
