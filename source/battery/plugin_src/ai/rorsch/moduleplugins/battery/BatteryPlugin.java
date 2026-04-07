package ai.rorsch.moduleplugins.battery;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;

import ai.rorsch.pandagenie.module.runtime.ModulePlugin;

import org.json.JSONObject;

/**
 * 电池状态与健康信息模块插件：基于系统粘性广播 {@link Intent#ACTION_BATTERY_CHANGED} 读取电量、充电、温度等。
 * <p>
 * <b>模块用途：</b>为上层提供统一的电池查询接口，不依赖厂商私有 API，数据来自 Android 公开电池 Intent。
 * </p>
 * <p>
 * <b>提供的 API（{@code action}）：</b>
 * {@code getBatteryStatus}（详细字段）、{@code getBatteryHealth}（健康评估与温度提示）、
 * {@code getPowerSummary}（一行摘要 + 关键字段）。额外参数通常可忽略。
 * </p>
 * <p>
 * <b>加载方式：</b>由 {@code ModuleRuntime} 通过反射加载并实现 {@link ModulePlugin}。
 * </p>
 */
public class BatteryPlugin implements ModulePlugin {

    /**
     * 分发电池相关查询；异常时返回 {@code success:false} 而非向外抛出。
     *
     * @param context    用于注册/读取电池粘性广播
     * @param action     {@code getBatteryStatus} / {@code getBatteryHealth} / {@code getPowerSummary}
     * @param paramsJson 一般为空对象；未知 action 时若有额外键会在错误信息中提示已忽略
     * @return JSON 字符串，成功时含 {@code success}、{@code output}（嵌套 JSON 字符串）、{@code _displayText}
     * @throws Exception 理论上内部已捕获，接口仍声明以兼容 {@link ModulePlugin}
     */
    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        JSONObject params = new JSONObject(emptyJson(paramsJson));
        try {
            switch (action) {
                case "getBatteryStatus": {
                    JSONObject r = buildStatusJson(context);
                    return ok(r.toString(), formatBatteryStatus(r));
                }
                case "getBatteryHealth": {
                    JSONObject r = buildHealthReportJson(context);
                    return ok(r.toString(), formatHealthReport(r));
                }
                case "getPowerSummary": {
                    JSONObject r = buildPowerSummaryJson(context);
                    return ok(r.toString(), formatPowerSummary(r));
                }
                default:
                    return error("Unsupported action: " + action
                            + (params.length() > 0 ? " (unexpected params ignored)" : ""));
            }
        } catch (Exception e) {
            // 统一转为错误 JSON，避免插件调用方未捕获导致崩溃
            return error(e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    /**
     * 获取最近一次电池状态粘性 Intent（系统始终会缓存 ACTION_BATTERY_CHANGED）。
     *
     * @param context 应用上下文
     * @return 电池广播 Intent；Android 13+ 需 {@link Context#RECEIVER_NOT_EXPORTED}
     */
    private Intent getBatteryIntent(Context context) {
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return context.registerReceiver(null, filter, Context.RECEIVER_NOT_EXPORTED);
        }
        return context.registerReceiver(null, filter);
    }

    /**
     * 从电池广播中解析电量百分比、充放电状态、插电类型、温度、电压、健康与电池技术字符串等。
     *
     * @param context 用于 {@link #getBatteryIntent(Context)}
     * @return 扁平 JSON 对象，字段名见方法内 {@code put} 调用
     * @throws Exception 粘性 Intent 不可用时抛出
     */
    private JSONObject buildStatusJson(Context context) throws Exception {
        Intent i = getBatteryIntent(context);
        if (i == null) {
            throw new IllegalStateException("Battery sticky intent unavailable");
        }
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        // level/scale 为当前电量刻度与满量程，相除得 0–100 百分比
        int pct = (level >= 0 && scale > 0) ? Math.round(100f * level / scale) : -1;
        int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
        int plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        int tempTenths = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
        int voltageMv = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
        int health = i.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN);
        String technology = i.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY);

        JSONObject o = new JSONObject();
        o.put("levelPercent", pct);
        o.put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL);
        o.put("status", batteryStatusString(status));
        o.put("pluggedType", pluggedTypeString(plugged));
        o.put("pluggedRaw", plugged);
        o.put("temperatureC", tempTenths >= 0 ? tempTenths / 10.0 : JSONObject.NULL);
        o.put("voltageMilliVolts", voltageMv >= 0 ? voltageMv : JSONObject.NULL);
        o.put("health", batteryHealthString(health));
        o.put("healthRaw", health);
        o.put("technology", technology != null ? technology : "");
        return o;
    }

    /**
     * 在 {@link #buildStatusJson} 基础上增加温度区间提示、循环次数说明（公开 API 通常无真实循环数）与综合评估文案。
     *
     * @param context 上下文
     * @return 含 {@code healthStatus}、{@code temperatureWarning}、{@code overallAssessment} 等字段的 JSON
     * @throws Exception 底层状态构建失败时抛出
     */
    private JSONObject buildHealthReportJson(Context context) throws Exception {
        JSONObject base = buildStatusJson(context);
        double temp = base.isNull("temperatureC") ? Double.NaN : base.getDouble("temperatureC");
        int pct = base.optInt("levelPercent", -1);
        String healthStr = base.optString("health", "unknown");

        String temperatureWarning;
        if (Double.isNaN(temp)) {
            temperatureWarning = "温度数据不可用";
        } else if (temp >= 45) {
            temperatureWarning = "温度偏高，建议降低负载并改善散热";
        } else if (temp >= 40) {
            temperatureWarning = "温度略高，注意通风与后台耗电";
        } else if (temp <= 0) {
            temperatureWarning = "温度读数异常偏低";
        } else {
            temperatureWarning = "温度处于常见工作范围";
        }

        String cycleEstimate = "标准电池广播通常不提供真实循环次数；部分设备可通过厂商接口读取，本模块仅使用公开 API。";
        String cycleEstimateEn = "Standard battery intents usually do not expose cycle count; OEM APIs may exist but are not used here.";

        String overall;
        if ("Good".equals(healthStr) || "Cold".equals(healthStr)) {
            overall = "健康标识为良好或偏冷；" + temperatureWarning;
        } else if ("Overheat".equals(healthStr) || "Dead".equals(healthStr)
                || "Over voltage".equals(healthStr) || "Unspecified failure".equals(healthStr)) {
            overall = "健康异常(" + healthStr + ")，请关注发热与充电习惯，必要时送检。";
        } else {
            overall = "健康：" + healthStr + "；" + temperatureWarning;
        }

        JSONObject o = new JSONObject();
        o.put("healthStatus", healthStr);
        o.put("temperatureWarning", temperatureWarning);
        o.put("cycleEstimate", cycleEstimate);
        o.put("cycleEstimate_en", cycleEstimateEn);
        o.put("currentLevelPercent", pct);
        o.put("overallAssessment", overall);
        return o;
    }

    /**
     * 组合状态与健康信息，生成中文一行摘要 {@code summaryLine} 及关键字段副本，便于快速展示。
     *
     * @param context 上下文
     * @return 汇总 JSON（含 {@code summaryLine}）
     * @throws Exception 子调用异常
     */
    private JSONObject buildPowerSummaryJson(Context context) throws Exception {
        JSONObject s = buildStatusJson(context);
        JSONObject h = buildHealthReportJson(context);

        int pct = s.optInt("levelPercent", -1);
        String status = s.optString("status", "unknown");
        String plug = s.optString("pluggedType", "none");
        boolean charging = s.optBoolean("charging", false);

        StringBuilder line = new StringBuilder();
        if (pct >= 0) {
            line.append("电量 ").append(pct).append("%");
        } else {
            line.append("电量未知");
        }
        line.append("，").append(statusLabelZh(status, charging));
        if (!"none".equals(plug)) {
            line.append("，").append(plugLabelZh(plug));
        }
        if (!s.isNull("temperatureC")) {
            line.append("，约 ").append(String.format(java.util.Locale.US, "%.1f", s.getDouble("temperatureC"))).append("°C");
        }
        line.append("，健康 ").append(s.optString("health", "unknown"));

        JSONObject o = new JSONObject();
        o.put("levelPercent", pct);
        o.put("status", status);
        o.put("charging", charging);
        o.put("pluggedType", plug);
        o.put("temperatureC", s.opt("temperatureC"));
        o.put("voltageMilliVolts", s.opt("voltageMilliVolts"));
        o.put("health", s.optString("health", ""));
        o.put("technology", s.optString("technology", ""));
        o.put("summaryLine", line.toString());
        o.put("healthStatus", h.optString("healthStatus", ""));
        o.put("temperatureWarning", h.optString("temperatureWarning", ""));
        return o;
    }

    /**
     * 将英文状态枚举转为简短中文描述（用于 {@code summaryLine}）。
     *
     * @param status    {@link #batteryStatusString} 返回值
     * @param charging  是否处于充电中标志（与 full 状态展示有关）
     * @return 中文短语
     */
    private static String statusLabelZh(String status, boolean charging) {
        switch (status) {
            case "charging":
                return "正在充电";
            case "discharging":
                return "放电中";
            case "full":
                return charging ? "已充满" : "已满电";
            case "not_charging":
                return "未在充电";
            default:
                return "状态未知";
        }
    }

    /**
     * 将 {@code pluggedType} 英文键转为中文供电方式描述。
     *
     * @param plug {@code usb} / {@code ac} / {@code wireless} 等
     * @return 中文说明；未知则原样返回
     */
    private static String plugLabelZh(String plug) {
        switch (plug) {
            case "usb":
                return "USB 供电";
            case "ac":
                return "交流适配器";
            case "wireless":
                return "无线充电";
            default:
                return plug;
        }
    }

    /**
     * 将 {@link BatteryManager} 的 {@code EXTRA_PLUGGED} 位标志归一化为简单字符串。
     *
     * @param plugged 位掩码，0 表示未插电
     * @return {@code none}、{@code usb}、{@code ac}、{@code wireless} 或 {@code unknown}
     */
    private static String pluggedTypeString(int plugged) {
        if (plugged == 0) {
            return "none";
        }
        if ((plugged & BatteryManager.BATTERY_PLUGGED_USB) != 0) {
            return "usb";
        }
        if ((plugged & BatteryManager.BATTERY_PLUGGED_AC) != 0) {
            return "ac";
        }
        if ((plugged & BatteryManager.BATTERY_PLUGGED_WIRELESS) != 0) {
            return "wireless";
        }
        return "unknown";
    }

    /**
     * 将 {@link BatteryManager#EXTRA_STATUS} 常量转为小写英文枚举字符串（供 JSON 与逻辑判断）。
     *
     * @param status {@link BatteryManager} 的 BATTERY_STATUS_* 常量
     * @return charging/discharging/full/not_charging/unknown
     */
    private static String batteryStatusString(int status) {
        switch (status) {
            case BatteryManager.BATTERY_STATUS_CHARGING:
                return "charging";
            case BatteryManager.BATTERY_STATUS_DISCHARGING:
                return "discharging";
            case BatteryManager.BATTERY_STATUS_FULL:
                return "full";
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING:
                return "not_charging";
            default:
                return "unknown";
        }
    }

    /**
     * 将 {@link BatteryManager#EXTRA_HEALTH} 转为简短英文标签（与健康报告中的字符串一致）。
     *
     * @param health {@link BatteryManager} 的 BATTERY_HEALTH_* 常量
     * @return Good/Cold/Dead 等或 Unknown
     */
    private static String batteryHealthString(int health) {
        switch (health) {
            case BatteryManager.BATTERY_HEALTH_GOOD:
                return "Good";
            case BatteryManager.BATTERY_HEALTH_COLD:
                return "Cold";
            case BatteryManager.BATTERY_HEALTH_DEAD:
                return "Dead";
            case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE:
                return "Over voltage";
            case BatteryManager.BATTERY_HEALTH_OVERHEAT:
                return "Overheat";
            case BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE:
                return "Unspecified failure";
            case BatteryManager.BATTERY_HEALTH_UNKNOWN:
            default:
                return "Unknown";
        }
    }

    /**
     * 格式化 {@code getBatteryStatus} 的展示用多行文本（英文标签为主，与历史 UI 一致）。
     *
     * @param r {@link #buildStatusJson} 产物
     * @return 供 {@code _displayText} 使用的字符串
     */
    private String formatBatteryStatus(JSONObject r) {
        int pct = r.optInt("levelPercent", -1);
        boolean charging = r.optBoolean("charging", false);
        StringBuilder sb = new StringBuilder();
        sb.append("🔋 ").append(pct >= 0 ? pct + "%" : "N/A");
        sb.append(charging ? " ⚡ Charging" : "").append("\n\n");
        sb.append("| Item | Value |\n");
        sb.append("|---|---|\n");
        sb.append("| Status | ").append(r.optString("status", "unknown")).append(" |\n");
        sb.append("| Plugged | ").append(r.optString("pluggedType", "none")).append(" |\n");
        if (!r.isNull("temperatureC"))
            sb.append("| Temperature | ").append(String.format(java.util.Locale.US, "%.1f°C", r.optDouble("temperatureC"))).append(" |\n");
        if (!r.isNull("voltageMilliVolts"))
            sb.append("| Voltage | ").append(r.optInt("voltageMilliVolts")).append(" mV |\n");
        sb.append("| Health | ").append(r.optString("health", "unknown")).append(" |\n");
        String tech = r.optString("technology", "");
        if (!tech.isEmpty()) sb.append("| Technology | ").append(tech).append(" |\n");
        return sb.toString();
    }

    /**
     * 格式化 {@code getBatteryHealth} 的简要健康报告展示块。
     *
     * @param r {@link #buildHealthReportJson} 产物
     * @return 展示文本
     */
    private String formatHealthReport(JSONObject r) {
        StringBuilder sb = new StringBuilder();
        sb.append("🏥 Battery Health Report\n\n");
        sb.append("| Item | Value |\n");
        sb.append("|---|---|\n");
        sb.append("| Health | ").append(r.optString("healthStatus", "unknown")).append(" |\n");
        sb.append("| Level | ").append(r.optInt("currentLevelPercent", -1)).append("% |\n");
        String tempWarn = r.optString("temperatureWarning", "");
        if (!tempWarn.isEmpty()) sb.append("| Temp Warning | ").append(tempWarn).append(" |\n");
        String assessment = r.optString("overallAssessment", "");
        if (!assessment.isEmpty()) sb.append("| Assessment | ").append(assessment).append(" |\n");
        return sb.toString();
    }

    /**
     * 格式化 {@code getPowerSummary}：含 ASCII 电量条与状态摘要。
     *
     * @param r {@link #buildPowerSummaryJson} 产物
     * @return 展示文本
     */
    private String formatPowerSummary(JSONObject r) {
        int pct = r.optInt("levelPercent", -1);
        boolean charging = r.optBoolean("charging", false);
        String bar = buildBar(pct);
        StringBuilder sb = new StringBuilder();
        sb.append("🔋 ").append(bar).append(" ").append(pct >= 0 ? pct + "%" : "N/A");
        sb.append(charging ? " ⚡" : "").append("\n\n");
        sb.append("| Item | Value |\n");
        sb.append("|---|---|\n");
        sb.append("| Status | ").append(r.optString("status", "unknown")).append(" |\n");
        if (!r.isNull("temperatureC"))
            sb.append("| Temperature | ").append(String.format(java.util.Locale.US, "%.1f°C", r.optDouble("temperatureC"))).append(" |\n");
        sb.append("| Health | ").append(r.optString("health", "unknown")).append(" |\n");
        String tech = r.optString("technology", "");
        if (!tech.isEmpty()) sb.append("| Technology | ").append(tech).append(" |\n");
        return sb.toString();
    }

    /**
     * 根据百分比生成 10 格块状电量条（用于展示）。
     *
     * @param pct 0–100，负数表示未知
     * @return 如 {@code [████░░░░░░]}
     */
    private String buildBar(int pct) {
        if (pct < 0) return "[????]";
        int filled = pct / 10;
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 10; i++) sb.append(i < filled ? "█" : "░");
        sb.append("]");
        return sb.toString();
    }

    /**
     * 空参数字符串规范为 JSON 空对象字面量。
     *
     * @param v paramsJson
     * @return 非空原样，否则 {@code "{}"}
     */
    private String emptyJson(String v) {
        return v == null || v.trim().isEmpty() ? "{}" : v;
    }

    /**
     * 成功响应：{@code output} 为业务 JSON 字符串，{@code displayText} 为界面摘要。
     *
     * @param output       写入 {@code output}
     * @param displayText  可选 {@code _displayText}
     * @return 完整响应 JSON
     * @throws Exception JSON 异常
     */
    private String ok(String output, String displayText) throws Exception {
        JSONObject r = new JSONObject().put("success", true).put("output", output);
        if (displayText != null && !displayText.isEmpty()) r.put("_displayText", displayText);
        return r.toString();
    }

    /**
     * 成功响应且不附带展示文案。
     *
     * @param output 业务输出
     * @return JSON
     * @throws Exception JSON 异常
     */
    private String ok(String output) throws Exception {
        return ok(output, null);
    }

    /**
     * 失败响应。
     *
     * @param msg 错误信息
     * @return 含 {@code success:false} 与 {@code error}
     * @throws Exception JSON 异常
     */
    private String error(String msg) throws Exception {
        return new JSONObject().put("success", false).put("error", msg).toString();
    }
}
