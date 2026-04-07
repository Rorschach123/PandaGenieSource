package ai.rorsch.moduleplugins.unit_converter;

import android.content.Context;

import ai.rorsch.pandagenie.module.runtime.ModulePlugin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * PandaGenie 单位换算模块插件。
 * <p>
 * <b>模块用途：</b>在本地完成长度、质量、温度、面积、体积、速度、时间、数据存储等类别的单位换算；
 * 支持列出类别与每类可用单位键，并对用户输入的单位别名做规范化（如 {@code km/h}、{@code °C} 等）。
 * </p>
 * <p>
 * <b>对外 API：</b>{@code convert}（{@code value}、{@code category}、{@code fromUnit}、{@code toUnit}）、
 * {@code listUnits}、{@code listCategories}。结果含数值与可读等式 {@code _displayText}。
 * </p>
 * <p>
 * 实现 {@link ModulePlugin}，由 {@code ModuleRuntime} 反射加载；换算表在静态初始化块中填充。
 * </p>
 */
public class UnitConverterPlugin implements ModulePlugin {

    /** 「月」按 30 天折算为秒的近似系数（与时间类换算一致）。 */
    private static final double MONTH_SECONDS = 30.0 * 86400.0;
    /** 「年」按 365 天折算为秒的近似系数。 */
    private static final double YEAR_SECONDS = 365.0 * 86400.0;

    /** 长度：各单位相对于「米」的乘法因子（键为小写规范单位 token）。 */
    private static final LinkedHashMap<String, Double> LENGTH_TO_M = new LinkedHashMap<>();
    /** 质量：各单位相对于「千克」的乘法因子。 */
    private static final LinkedHashMap<String, Double> WEIGHT_TO_KG = new LinkedHashMap<>();
    /** 面积：各单位相对于「平方米」的乘法因子。 */
    private static final LinkedHashMap<String, Double> AREA_TO_M2 = new LinkedHashMap<>();
    /** 体积：各单位相对于「立方米」的乘法因子。 */
    private static final LinkedHashMap<String, Double> VOLUME_TO_M3 = new LinkedHashMap<>();
    /** 速度：各单位相对于「米/秒」的乘法因子。 */
    private static final LinkedHashMap<String, Double> SPEED_TO_MS = new LinkedHashMap<>();
    /** 时间：各单位相对于「秒」的乘法因子。 */
    private static final LinkedHashMap<String, Double> TIME_TO_S = new LinkedHashMap<>();
    /**
     * 数据量：各单位相对于「字节」的乘法因子；字节倍率按 1024 幂（KB/MB…），
     * bit 系列按 1024 bit 为一步再乘 0.125 转字节。
     */
    private static final LinkedHashMap<String, Double> DATA_TO_BYTES = new LinkedHashMap<>();

    static {
        LENGTH_TO_M.put("mm", 0.001);
        LENGTH_TO_M.put("cm", 0.01);
        LENGTH_TO_M.put("m", 1.0);
        LENGTH_TO_M.put("km", 1000.0);
        LENGTH_TO_M.put("in", 0.0254);
        LENGTH_TO_M.put("ft", 0.3048);
        LENGTH_TO_M.put("yd", 0.9144);
        LENGTH_TO_M.put("mi", 1609.344);
        LENGTH_TO_M.put("nm", 1852.0);

        WEIGHT_TO_KG.put("mg", 1e-6);
        WEIGHT_TO_KG.put("g", 0.001);
        WEIGHT_TO_KG.put("kg", 1.0);
        WEIGHT_TO_KG.put("oz", 0.028349523125);
        WEIGHT_TO_KG.put("lb", 0.45359237);
        WEIGHT_TO_KG.put("ton", 1000.0);
        WEIGHT_TO_KG.put("st", 6.35029318);

        AREA_TO_M2.put("mm2", 1e-6);
        AREA_TO_M2.put("cm2", 1e-4);
        AREA_TO_M2.put("m2", 1.0);
        AREA_TO_M2.put("km2", 1e6);
        AREA_TO_M2.put("in2", 0.00064516);
        AREA_TO_M2.put("ft2", 0.09290304);
        AREA_TO_M2.put("yd2", 0.83612736);
        AREA_TO_M2.put("mi2", 2589988.110336);
        AREA_TO_M2.put("ha", 10000.0);
        AREA_TO_M2.put("acre", 4046.8564224);

        VOLUME_TO_M3.put("ml", 1e-6);
        VOLUME_TO_M3.put("l", 0.001);
        VOLUME_TO_M3.put("m3", 1.0);
        VOLUME_TO_M3.put("gal_us", 0.003785411784);
        VOLUME_TO_M3.put("gal_uk", 0.00454609);
        VOLUME_TO_M3.put("floz_us", 29.5735295625e-6);
        VOLUME_TO_M3.put("cup_us", 0.0002365882365);
        VOLUME_TO_M3.put("pt_us", 0.000473176473);
        VOLUME_TO_M3.put("qt_us", 0.000946352946);

        SPEED_TO_MS.put("m/s", 1.0);
        SPEED_TO_MS.put("km/h", 1.0 / 3.6);
        SPEED_TO_MS.put("mph", 0.44704);
        SPEED_TO_MS.put("knot", 1852.0 / 3600.0);
        SPEED_TO_MS.put("ft/s", 0.3048);

        TIME_TO_S.put("ms", 0.001);
        TIME_TO_S.put("s", 1.0);
        TIME_TO_S.put("min", 60.0);
        TIME_TO_S.put("h", 3600.0);
        TIME_TO_S.put("day", 86400.0);
        TIME_TO_S.put("week", 604800.0);
        TIME_TO_S.put("month", MONTH_SECONDS);
        TIME_TO_S.put("year", YEAR_SECONDS);

        DATA_TO_BYTES.put("B", 1.0);
        DATA_TO_BYTES.put("KB", 1024.0);
        DATA_TO_BYTES.put("MB", 1024.0 * 1024.0);
        DATA_TO_BYTES.put("GB", 1024.0 * 1024.0 * 1024.0);
        DATA_TO_BYTES.put("TB", Math.pow(1024, 4));
        DATA_TO_BYTES.put("PB", Math.pow(1024, 5));
        DATA_TO_BYTES.put("bit", 0.125);
        DATA_TO_BYTES.put("Kbit", 1024.0 * 0.125);
        DATA_TO_BYTES.put("Mbit", 1024.0 * 1024.0 * 0.125);
        DATA_TO_BYTES.put("Gbit", Math.pow(1024, 3) * 0.125);
        // 小写别名：与 normalizeUnitToken 中小写化后的键一致
        DATA_TO_BYTES.put("kb", DATA_TO_BYTES.get("KB"));
        DATA_TO_BYTES.put("mb", DATA_TO_BYTES.get("MB"));
        DATA_TO_BYTES.put("gb", DATA_TO_BYTES.get("GB"));
        DATA_TO_BYTES.put("tb", DATA_TO_BYTES.get("TB"));
        DATA_TO_BYTES.put("pb", DATA_TO_BYTES.get("PB"));
        DATA_TO_BYTES.put("kbit", DATA_TO_BYTES.get("Kbit"));
        DATA_TO_BYTES.put("mbit", DATA_TO_BYTES.get("Mbit"));
        DATA_TO_BYTES.put("gbit", DATA_TO_BYTES.get("Gbit"));
    }

    /** 类别内部键到英文展示标题（用于 {@code _displayText} 等）。 */
    private static final LinkedHashMap<String, String> CATEGORY_TITLES = new LinkedHashMap<>();
    static {
        CATEGORY_TITLES.put("length", "Length");
        CATEGORY_TITLES.put("weight", "Weight");
        CATEGORY_TITLES.put("temperature", "Temperature");
        CATEGORY_TITLES.put("area", "Area");
        CATEGORY_TITLES.put("volume", "Volume");
        CATEGORY_TITLES.put("speed", "Speed");
        CATEGORY_TITLES.put("time", "Time");
        CATEGORY_TITLES.put("data", "Data storage");
    }

    /**
     * 分发换算、列单位、列类别三类动作。
     *
     * @param context    未使用
     * @param action     {@code convert|listUnits|listCategories}
     * @param paramsJson JSON 参数；{@code convert} 需有效数值与单位
     * @return 标准成功/失败 JSON
     */
    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        JSONObject params = new JSONObject(emptyJson(paramsJson));
        switch (action) {
            case "convert": {
                double value = params.optDouble("value", Double.NaN);
                if (Double.isNaN(value)) {
                    return error("Invalid value");
                }
                String category = normalizeCategory(params.optString("category", ""));
                String from = normalizeUnitToken(category, params.optString("fromUnit", ""));
                String to = normalizeUnitToken(category, params.optString("toUnit", ""));
                if (from.isEmpty() || to.isEmpty()) {
                    return error("Unknown or missing unit");
                }
                double result = convert(category, value, from, to);
                String outStr = formatNumber(result);
                JSONObject out = new JSONObject()
                        .put("result", result)
                        .put("resultText", outStr)
                        .put("fromUnit", from)
                        .put("toUnit", to)
                        .put("category", category);
                String prettyFrom = displayUnitLabel(category, from);
                String prettyTo = displayUnitLabel(category, to);
                String line = formatNumber(value) + " " + prettyFrom + " = " + outStr + " " + prettyTo;
                String catTitle = categoryTitle(category);
                String display = "📐 Unit Conversion\n" + line + "\nCategory: " + catTitle;
                return ok(out, display);
            }
            case "listUnits": {
                String category = normalizeCategory(params.optString("category", ""));
                List<String> units = listUnitKeys(category);
                if (units.isEmpty()) {
                    return error("Unknown category: " + params.optString("category", ""));
                }
                JSONArray arr = new JSONArray();
                for (String u : units) {
                    arr.put(u);
                }
                JSONObject out = new JSONObject().put("category", category).put("units", arr);
                String joined = String.join(", ", units);
                String title = categoryListTitle(category);
                String display = title + "\n" + joined;
                return ok(out, display);
            }
            case "listCategories": {
                JSONArray arr = new JSONArray();
                for (String c : CATEGORY_TITLES.keySet()) {
                    arr.put(c);
                }
                JSONObject out = new JSONObject().put("categories", arr);
                return ok(out, "📋 Categories\n" + String.join(", ", keysList(CATEGORY_TITLES)));
            }
            default:
                return error("Unsupported action: " + action);
        }
    }

    /**
     * 将 {@link LinkedHashMap} 的键集合拷贝为列表（保持插入顺序）。
     */
    private static List<String> keysList(LinkedHashMap<String, String> map) {
        return new ArrayList<>(map.keySet());
    }

    /** 类别键对应的英文标题；未知则回显原键。 */
    private static String categoryTitle(String category) {
        return CATEGORY_TITLES.getOrDefault(category, category);
    }

    /**
     * {@code listUnits} 展示用带图标的标题行。
     */
    private static String categoryListTitle(String category) {
        switch (category) {
            case "length":
                return "📏 Length Units";
            case "weight":
                return "⚖️ Weight Units";
            case "temperature":
                return "🌡️ Temperature Units";
            case "area":
                return "⬛ Area Units";
            case "volume":
                return "🧴 Volume Units";
            case "speed":
                return "🚀 Speed Units";
            case "time":
                return "⏱️ Time Units";
            case "data":
                return "💾 Data Units";
            default:
                return "📏 Units";
        }
    }

    /**
     * 将内部规范单位键转为更易读的标签（如 {@code m2→m²}、温度加度符号）。
     *
     * @param category  类别
     * @param canonical {@link #normalizeUnitToken} 之后的键
     */
    private static String displayUnitLabel(String category, String canonical) {
        if ("temperature".equals(category)) {
            if ("C".equals(canonical)) return "°C";
            if ("F".equals(canonical)) return "°F";
            if ("K".equals(canonical)) return "K";
        }
        if ("speed".equals(category) && "m/s".equals(canonical)) return "m/s";
        if ("area".equals(category)) {
            return canonical.replace("2", "²");
        }
        if ("volume".equals(category) && "m3".equals(canonical)) return "m³";
        return canonical;
    }

    /**
     * 返回某类别下可选的单位内部键列表（顺序即展示顺序）。
     */
    private static List<String> listUnitKeys(String category) {
        List<String> list = new ArrayList<>();
        switch (category) {
            case "length":
                list.addAll(LENGTH_TO_M.keySet());
                break;
            case "weight":
                list.addAll(WEIGHT_TO_KG.keySet());
                break;
            case "temperature":
                list.add("C");
                list.add("F");
                list.add("K");
                break;
            case "area":
                list.addAll(AREA_TO_M2.keySet());
                break;
            case "volume":
                list.addAll(VOLUME_TO_M3.keySet());
                break;
            case "speed":
                list.addAll(SPEED_TO_MS.keySet());
                break;
            case "time":
                list.addAll(TIME_TO_S.keySet());
                break;
            case "data":
                list.add("B");
                list.add("KB");
                list.add("MB");
                list.add("GB");
                list.add("TB");
                list.add("PB");
                list.add("bit");
                list.add("Kbit");
                list.add("Mbit");
                list.add("Gbit");
                break;
            default:
                break;
        }
        return list;
    }

    /**
     * 将用户输入的类别字符串规范为内部键（小写、去空白）；不在 {@link #CATEGORY_TITLES} 中则返回空串。
     */
    private static String normalizeCategory(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return "";
        if (CATEGORY_TITLES.containsKey(s)) return s;
        return "";
    }

    /**
     * 将用户输入的单位别名转为内部规范键（小写、去空格、部分符号替换）；无法识别返回空串。
     *
     * @param category 已规范类别
     * @param raw      用户原始单位字符串
     * @return 规范键，如 {@code km/h}、{@code C}/{@code F}/{@code K}
     */
    private static String normalizeUnitToken(String category, String raw) {
        if (raw == null) return "";
        String s = raw.trim().toLowerCase(Locale.ROOT);
        s = s.replace("²", "2").replace("³", "3");
        s = s.replace(" ", "");

        switch (category) {
            case "length":
                if ("nm".equals(s) || "nmi".equals(s) || "nauticalmile".equals(s)) return "nm";
                return LENGTH_TO_M.containsKey(s) ? s : "";
            case "weight":
                if ("t".equals(s)) return "ton";
                return WEIGHT_TO_KG.containsKey(s) ? s : "";
            case "temperature":
                if (s.equals("°c") || s.equals("degc") || s.equals("celsius")) return "C";
                if (s.equals("c")) return "C";
                if (s.equals("°f") || s.equals("degf") || s.equals("fahrenheit")) return "F";
                if (s.equals("f")) return "F";
                if (s.equals("k") || s.equals("kelvin")) return "K";
                return "";
            case "area":
                if ("sqm".equals(s) || "m^2".equals(s)) return "m2";
                if ("km^2".equals(s)) return "km2";
                return AREA_TO_M2.containsKey(s) ? s : "";
            case "volume":
                if ("m^3".equals(s)) return "m3";
                if ("gal".equals(s) || "galus".equals(s) || "gallon".equals(s) || "gallon_us".equals(s)) return "gal_us";
                if ("galuk".equals(s) || "gallon_uk".equals(s) || "ukgal".equals(s)) return "gal_uk";
                if ("floz".equals(s) || "fl_oz".equals(s) || "flozus".equals(s) || "oz_fl".equals(s)) return "floz_us";
                if ("cup".equals(s)) return "cup_us";
                if ("pt".equals(s) || "pint".equals(s)) return "pt_us";
                if ("qt".equals(s) || "quart".equals(s)) return "qt_us";
                return VOLUME_TO_M3.containsKey(s) ? s : "";
            case "speed":
                if ("kmh".equals(s) || "km/h".equals(s) || "kph".equals(s)) return "km/h";
                if ("mps".equals(s) || "m/s".equals(s)) return "m/s";
                if ("fts".equals(s) || "ft/s".equals(s) || "fps".equals(s)) return "ft/s";
                if ("kt".equals(s) || "kn".equals(s)) return "knot";
                return SPEED_TO_MS.containsKey(s) ? s : "";
            case "time":
                if ("sec".equals(s) || "second".equals(s) || "seconds".equals(s)) return "s";
                if ("hr".equals(s) || "hour".equals(s) || "hours".equals(s)) return "h";
                if ("minute".equals(s) || "minutes".equals(s)) return "min";
                if ("days".equals(s) || "d".equals(s)) return "day";
                if ("weeks".equals(s) || "wk".equals(s)) return "week";
                if ("months".equals(s)) return "month";
                if ("years".equals(s) || "yr".equals(s)) return "year";
                if ("millisecond".equals(s) || "milliseconds".equals(s)) return "ms";
                return TIME_TO_S.containsKey(s) ? s : "";
            case "data":
                if ("bytes".equals(s)) return "B";
                if ("kb".equals(s)) return "KB";
                if ("mb".equals(s)) return "MB";
                if ("gb".equals(s)) return "GB";
                if ("tb".equals(s)) return "TB";
                if ("pb".equals(s)) return "PB";
                if ("kbit".equals(s) || "kibit".equals(s)) return "Kbit";
                if ("mbit".equals(s) || "mibit".equals(s)) return "Mbit";
                if ("gbit".equals(s) || "gibit".equals(s)) return "Gbit";
                if ("bits".equals(s)) return "bit";
                return DATA_TO_BYTES.containsKey(s) ? s : "";
            default:
                return "";
        }
    }

    /**
     * 执行一次换算：温度走摄氏中间态；其它类别先乘「到基准」再除「从基准」。
     *
     * @param category 规范类别键
     * @param value    数值
     * @param from     源单位（已规范）
     * @param to       目标单位（已规范）
     * @return 换算结果
     */
    private static double convert(String category, double value, String from, String to) throws Exception {
        if ("temperature".equals(category)) {
            double c = toCelsius(from, value);
            return fromCelsius(to, c);
        }
        double base = toBase(category, from, value);
        return fromBase(category, to, base);
    }

    /**
     * 将 {@code value}（单位 {@code unit}）转为该类别的基准量（米/千克/秒等）。
     */
    private static double toBase(String category, String unit, double value) throws Exception {
        switch (category) {
            case "length":
                return value * require(LENGTH_TO_M, unit);
            case "weight":
                return value * require(WEIGHT_TO_KG, unit);
            case "area":
                return value * require(AREA_TO_M2, unit);
            case "volume":
                return value * require(VOLUME_TO_M3, unit);
            case "speed":
                return value * require(SPEED_TO_MS, unit);
            case "time":
                return value * require(TIME_TO_S, unit);
            case "data":
                return value * require(DATA_TO_BYTES, unit);
            default:
                throw new Exception("Unknown category");
        }
    }

    /**
     * 将基准量 {@code base} 转为 {@code unit} 下的数值。
     */
    private static double fromBase(String category, String unit, double base) throws Exception {
        switch (category) {
            case "length":
                return base / require(LENGTH_TO_M, unit);
            case "weight":
                return base / require(WEIGHT_TO_KG, unit);
            case "area":
                return base / require(AREA_TO_M2, unit);
            case "volume":
                return base / require(VOLUME_TO_M3, unit);
            case "speed":
                return base / require(SPEED_TO_MS, unit);
            case "time":
                return base / require(TIME_TO_S, unit);
            case "data":
                return base / require(DATA_TO_BYTES, unit);
            default:
                throw new Exception("Unknown category");
        }
    }

    /**
     * 从换算表取因子；缺失则抛「未知单位」类异常。
     */
    private static double require(Map<String, Double> map, String key) throws Exception {
        Double v = map.get(key);
        if (v == null) throw new Exception("Unknown unit");
        return v;
    }

    /** 将任意温标值转为摄氏温度。 */
    private static double toCelsius(String from, double value) throws Exception {
        switch (from) {
            case "C":
                return value;
            case "F":
                return (value - 32.0) * 5.0 / 9.0;
            case "K":
                return value - 273.15;
            default:
                throw new Exception("Unknown temperature unit");
        }
    }

    /** 从摄氏温度转到目标温标。 */
    private static double fromCelsius(String to, double celsius) throws Exception {
        switch (to) {
            case "C":
                return celsius;
            case "F":
                return celsius * 9.0 / 5.0 + 32.0;
            case "K":
                return celsius + 273.15;
            default:
                throw new Exception("Unknown temperature unit");
        }
    }

    /**
     * 将 double 格式化为简洁字符串：整数无小数；极大极小用科学计数；否则最多 10 位有效风格的 {@code %.10g}。
     */
    private static String formatNumber(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return String.valueOf(value);
        }
        double av = Math.abs(value);
        if (av != 0 && (av < 1e-6 || av >= 1e15)) {
            return String.format(Locale.US, "%.6e", value);
        }
        long asLong = (long) value;
        if (value == (double) asLong) {
            return String.valueOf(asLong);
        }
        return String.format(Locale.US, "%.10g", value);
    }

    /** 空参数转 {@code "{}"}。 */
    private static String emptyJson(String value) {
        return value == null || value.trim().isEmpty() ? "{}" : value;
    }

    /**
     * 成功响应：{@code output} 为业务 JSON 的字符串形式。
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

    /** 失败响应。 */
    private static String error(String message) throws Exception {
        return new JSONObject()
                .put("success", false)
                .put("error", message)
                .toString();
    }
}
