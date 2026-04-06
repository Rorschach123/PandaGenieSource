package ai.rorsch.moduleplugins.led_banner;

import android.content.Context;
import ai.rorsch.pandagenie.module.runtime.ModulePlugin;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

public class LedBannerPlugin implements ModulePlugin {

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

    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        try {
            JSONObject params = new JSONObject(emptyJson(paramsJson));
            switch (action) {
                case "generateBanner": {
                    JSONObject r = generateBanner(params);
                    return okOpenModule(r, formatBannerDisplay(r));
                }
                case "getTemplates":
                    return ok(getTemplates());
                case "getStarTemplates":
                    return ok(getStarTemplates());
                case "applyTemplate": {
                    JSONObject r = applyTemplate(params);
                    return okOpenModule(r, formatBannerDisplay(r));
                }
                default:
                    return error("Unsupported action: " + action);
            }
        } catch (Exception e) {
            String msg = e.getMessage();
            return error(msg != null && !msg.isEmpty() ? msg : e.getClass().getSimpleName());
        }
    }

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
            mode = "scroll";
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

        JSONObject result = generateBanner(fakeParams);
        result.put("templateName", templateName);
        return result;
    }

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

    private String getModeName(String mode) {
        switch (mode) {
            case "scroll": return "滚动";
            case "fade": return "浮现";
            case "static": return "静止";
            default: return mode;
        }
    }

    private String normalizeColor(String color) {
        if (color == null || color.trim().isEmpty()) return "#FFFFFF";
        color = color.trim();
        if (!color.startsWith("#")) color = "#" + color;
        return color.toUpperCase(Locale.ROOT);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String emptyJson(String value) {
        return value == null || value.trim().isEmpty() ? "{}" : value;
    }

    private String ok(JSONObject output) throws Exception {
        return new JSONObject()
                .put("success", true)
                .put("output", output.toString())
                .toString();
    }

    private String okOpenModule(JSONObject output, String displayText) throws Exception {
        JSONObject result = new JSONObject()
                .put("success", true)
                .put("output", output.toString())
                .put("_openModule", true);
        if (displayText != null && !displayText.isEmpty()) {
            result.put("_displayText", displayText);
        }
        return result.toString();
    }

    private String error(String message) throws Exception {
        return new JSONObject()
                .put("success", false)
                .put("error", message)
                .toString();
    }
}
