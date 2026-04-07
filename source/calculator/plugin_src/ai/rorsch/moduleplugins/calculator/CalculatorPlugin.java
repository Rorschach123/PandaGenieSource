package ai.rorsch.moduleplugins.calculator;

import android.content.Context;
import ai.rorsch.pandagenie.module.runtime.ModulePlugin;
import ai.rorsch.pandagenie.nativelib.CalculatorLib;
import org.json.JSONObject;

import java.util.Locale;

/**
 * 科学计算与表达式求值模块插件：通过 {@link CalculatorLib} 调用 Native 实现高精度/扩展运算。
 * <p>
 * <b>模块用途：</b>为 Agent 或自动化任务提供算术、三角函数、对数、阶乘、排列组合及表达式解析等能力。
 * </p>
 * <p>
 * <b>提供的 API（{@code action}）：</b>
 * {@code evaluate}（{@code expression}）、四则运算 {@code add}/{@code subtract}/{@code multiply}/{@code divide}（{@code a},{@code b}）、
 * {@code power}（{@code base},{@code exp}）、{@code sqrt}/{@code sin}/{@code cos}/{@code tan}/{@code ln}/{@code log10}（{@code x}）、
 * {@code factorial}（{@code n}）、{@code combination}/{@code permutation}（{@code n},{@code r}）、
 * {@code degToRad}/{@code radToDeg}。
 * </p>
 * <p>
 * <b>加载方式：</b>由 {@code ModuleRuntime} 反射加载并实现 {@link ModulePlugin}。
 * </p>
 */
public class CalculatorPlugin implements ModulePlugin {
    /** 原生计算器库，执行具体数学运算与表达式解析 */
    private final CalculatorLib lib = new CalculatorLib();

    /**
     * 根据 {@code action} 从参数 JSON 读取操作数并调用对应 Native 方法，结果格式化为字符串返回。
     *
     * @param context    接口预留，当前实现未使用
     * @param action     运算类型名称
     * @param paramsJson JSON 参数；缺失键时 {@link JSONObject#optDouble(String)} 等返回默认值 0
     * @return 成功 JSON（{@code output} 为结果字符串，常带 {@code _displayText}）；未知 action 返回错误 JSON
     * @throws Exception JSON 序列化异常
     */
    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        JSONObject params = new JSONObject(emptyJson(paramsJson));
        switch (action) {
            case "evaluate": {
                // 解析并计算完整算术表达式字符串
                String out = formatNumber(lib.evaluate(params.optString("expression", "")));
                return ok(out, "🔢 = " + out);
            }
            case "add": {
                // 双精度浮点加法
                String out = String.valueOf(lib.add(params.optDouble("a"), params.optDouble("b")));
                return ok(out, "🔢 Result: " + out);
            }
            case "subtract": {
                String out = String.valueOf(lib.subtract(params.optDouble("a"), params.optDouble("b")));
                return ok(out, "🔢 Result: " + out);
            }
            case "multiply": {
                String out = String.valueOf(lib.multiply(params.optDouble("a"), params.optDouble("b")));
                return ok(out, "🔢 Result: " + out);
            }
            case "divide": {
                // 除法由 Native 层处理除零等语义
                String out = String.valueOf(lib.divide(params.optDouble("a"), params.optDouble("b")));
                return ok(out, "🔢 Result: " + out);
            }
            case "power": {
                String out = String.valueOf(lib.power(params.optDouble("base"), params.optDouble("exp")));
                return ok(out, "🔢 Result: " + out);
            }
            case "sqrt": {
                // nSqrt：Native 实现的平方根
                double x = params.optDouble("x");
                String out = String.valueOf(lib.nSqrt(x));
                return ok(out, String.format(Locale.US, "📐 sqrt(%s) = %s", formatParam(x), out));
            }
            case "sin": {
                double x = params.optDouble("x");
                String out = String.valueOf(lib.sin(x));
                return ok(out, String.format(Locale.US, "📐 sin(%s) = %s", formatParam(x), out));
            }
            case "cos": {
                double x = params.optDouble("x");
                String out = String.valueOf(lib.cos(x));
                return ok(out, String.format(Locale.US, "📐 cos(%s) = %s", formatParam(x), out));
            }
            case "tan": {
                double x = params.optDouble("x");
                String out = String.valueOf(lib.tan(x));
                return ok(out, String.format(Locale.US, "📐 tan(%s) = %s", formatParam(x), out));
            }
            case "ln": {
                double x = params.optDouble("x");
                String out = String.valueOf(lib.ln(x));
                return ok(out, String.format(Locale.US, "📐 ln(%s) = %s", formatParam(x), out));
            }
            case "log10": {
                double x = params.optDouble("x");
                String out = String.valueOf(lib.log10(x));
                return ok(out, String.format(Locale.US, "📐 log10(%s) = %s", formatParam(x), out));
            }
            case "factorial": {
                String out = String.valueOf(lib.factorial(params.optInt("n")));
                return ok(out, "🔢 Result: " + out);
            }
            case "combination": {
                String out = String.valueOf(lib.combination(params.optInt("n"), params.optInt("r")));
                return ok(out, "🔢 Result: " + out);
            }
            case "permutation": {
                String out = String.valueOf(lib.permutation(params.optInt("n"), params.optInt("r")));
                return ok(out, "🔢 Result: " + out);
            }
            case "degToRad": {
                String out = String.valueOf(lib.degToRad(params.optDouble("deg")));
                return ok(out, "🔢 Result: " + out);
            }
            case "radToDeg": {
                String out = String.valueOf(lib.radToDeg(params.optDouble("rad")));
                return ok(out, "🔢 Result: " + out);
            }
            default:
                return error("Unsupported action: " + action);
        }
    }

    /**
     * 将空或 null 的 paramsJson 转为 "{}" 以便安全构造 {@link JSONObject}。
     *
     * @param value 原始 JSON 字符串
     * @return 规范化后的字符串
     */
    private String emptyJson(String value) {
        return value == null || value.trim().isEmpty() ? "{}" : value;
    }

    /**
     * 将 double 格式化为展示用字符串：整数显示为无小数，否则保留默认 double 字符串形式。
     *
     * @param value 参数值
     * @return 用于 {@code _displayText} 中的简短数字表示
     */
    private String formatParam(double value) {
        long lv = (long) value;
        return value == (double) lv ? String.valueOf(lv) : String.valueOf(value);
    }

    /**
     * 与 {@link #formatParam} 相同策略，用于表达式求值结果的 {@code output} 展示。
     *
     * @param value 计算结果
     * @return 整型外观或完整 double 字符串
     */
    private String formatNumber(double value) {
        long longValue = (long) value;
        return value == (double) longValue ? String.valueOf(longValue) : String.valueOf(value);
    }

    /**
     * 成功响应，仅含 {@code success} 与 {@code output}。
     *
     * @param output 数值结果的字符串形式
     * @return JSON
     * @throws Exception JSON 异常
     */
    private String ok(String output) throws Exception {
        return new JSONObject().put("success", true).put("output", output).toString();
    }

    /**
     * 成功响应并附加可选 {@code _displayText}（含 emoji 的算式说明）。
     *
     * @param output       结果字符串
     * @param displayText  界面展示文案
     * @return JSON
     * @throws Exception JSON 异常
     */
    private String ok(String output, String displayText) throws Exception {
        JSONObject r = new JSONObject().put("success", true).put("output", output);
        if (displayText != null && !displayText.isEmpty()) r.put("_displayText", displayText);
        return r.toString();
    }

    /**
     * 失败响应（如不支持的 action）。
     *
     * @param message 错误描述
     * @return JSON
     * @throws Exception JSON 异常
     */
    private String error(String message) throws Exception {
        return new JSONObject().put("success", false).put("error", message).toString();
    }
}
