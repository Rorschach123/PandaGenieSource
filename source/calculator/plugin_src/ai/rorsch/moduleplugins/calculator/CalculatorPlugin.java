package ai.rorsch.moduleplugins.calculator;

import android.content.Context;
import ai.rorsch.pandagenie.module.runtime.ModulePlugin;
import ai.rorsch.pandagenie.nativelib.CalculatorLib;
import org.json.JSONObject;

public class CalculatorPlugin implements ModulePlugin {
    private final CalculatorLib lib = new CalculatorLib();

    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        JSONObject params = new JSONObject(emptyJson(paramsJson));
        switch (action) {
            case "evaluate":
                return ok(formatNumber(lib.evaluate(params.optString("expression", ""))));
            case "add":
                return ok(String.valueOf(lib.add(params.optDouble("a"), params.optDouble("b"))));
            case "subtract":
                return ok(String.valueOf(lib.subtract(params.optDouble("a"), params.optDouble("b"))));
            case "multiply":
                return ok(String.valueOf(lib.multiply(params.optDouble("a"), params.optDouble("b"))));
            case "divide":
                return ok(String.valueOf(lib.divide(params.optDouble("a"), params.optDouble("b"))));
            case "power":
                return ok(String.valueOf(lib.power(params.optDouble("base"), params.optDouble("exp"))));
            case "sqrt":
                return ok(String.valueOf(lib.nSqrt(params.optDouble("x"))));
            case "sin":
                return ok(String.valueOf(lib.sin(params.optDouble("x"))));
            case "cos":
                return ok(String.valueOf(lib.cos(params.optDouble("x"))));
            case "tan":
                return ok(String.valueOf(lib.tan(params.optDouble("x"))));
            case "ln":
                return ok(String.valueOf(lib.ln(params.optDouble("x"))));
            case "log10":
                return ok(String.valueOf(lib.log10(params.optDouble("x"))));
            case "factorial":
                return ok(String.valueOf(lib.factorial(params.optInt("n"))));
            case "combination":
                return ok(String.valueOf(lib.combination(params.optInt("n"), params.optInt("r"))));
            case "permutation":
                return ok(String.valueOf(lib.permutation(params.optInt("n"), params.optInt("r"))));
            case "degToRad":
                return ok(String.valueOf(lib.degToRad(params.optDouble("deg"))));
            case "radToDeg":
                return ok(String.valueOf(lib.radToDeg(params.optDouble("rad"))));
            default:
                return error("Unsupported action: " + action);
        }
    }

    private String emptyJson(String value) {
        return value == null || value.trim().isEmpty() ? "{}" : value;
    }

    private String formatNumber(double value) {
        long longValue = (long) value;
        return value == (double) longValue ? String.valueOf(longValue) : String.valueOf(value);
    }

    private String ok(String output) throws Exception {
        return new JSONObject().put("success", true).put("output", output).toString();
    }

    private String error(String message) throws Exception {
        return new JSONObject().put("success", false).put("error", message).toString();
    }
}
