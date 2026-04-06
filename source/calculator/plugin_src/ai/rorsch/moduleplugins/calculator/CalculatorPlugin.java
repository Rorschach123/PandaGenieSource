package ai.rorsch.moduleplugins.calculator;

import android.content.Context;
import ai.rorsch.pandagenie.module.runtime.ModulePlugin;
import ai.rorsch.pandagenie.nativelib.CalculatorLib;
import org.json.JSONObject;

import java.util.Locale;

public class CalculatorPlugin implements ModulePlugin {
    private final CalculatorLib lib = new CalculatorLib();

    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        JSONObject params = new JSONObject(emptyJson(paramsJson));
        switch (action) {
            case "evaluate": {
                String out = formatNumber(lib.evaluate(params.optString("expression", "")));
                return ok(out, "🔢 = " + out);
            }
            case "add": {
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
                String out = String.valueOf(lib.divide(params.optDouble("a"), params.optDouble("b")));
                return ok(out, "🔢 Result: " + out);
            }
            case "power": {
                String out = String.valueOf(lib.power(params.optDouble("base"), params.optDouble("exp")));
                return ok(out, "🔢 Result: " + out);
            }
            case "sqrt": {
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

    private String emptyJson(String value) {
        return value == null || value.trim().isEmpty() ? "{}" : value;
    }

    private String formatParam(double value) {
        long lv = (long) value;
        return value == (double) lv ? String.valueOf(lv) : String.valueOf(value);
    }

    private String formatNumber(double value) {
        long longValue = (long) value;
        return value == (double) longValue ? String.valueOf(longValue) : String.valueOf(value);
    }

    private String ok(String output) throws Exception {
        return new JSONObject().put("success", true).put("output", output).toString();
    }

    private String ok(String output, String displayText) throws Exception {
        JSONObject r = new JSONObject().put("success", true).put("output", output);
        if (displayText != null && !displayText.isEmpty()) r.put("_displayText", displayText);
        return r.toString();
    }

    private String error(String message) throws Exception {
        return new JSONObject().put("success", false).put("error", message).toString();
    }
}
