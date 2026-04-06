package ai.rorsch.moduleplugins.battery;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;

import ai.rorsch.pandagenie.module.runtime.ModulePlugin;

import org.json.JSONObject;

public class BatteryPlugin implements ModulePlugin {

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
            return error(e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    private Intent getBatteryIntent(Context context) {
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return context.registerReceiver(null, filter, Context.RECEIVER_NOT_EXPORTED);
        }
        return context.registerReceiver(null, filter);
    }

    private JSONObject buildStatusJson(Context context) throws Exception {
        Intent i = getBatteryIntent(context);
        if (i == null) {
            throw new IllegalStateException("Battery sticky intent unavailable");
        }
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
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

    private String formatBatteryStatus(JSONObject r) {
        StringBuilder sb = new StringBuilder();
        int pct = r.optInt("levelPercent", -1);
        boolean charging = r.optBoolean("charging", false);
        sb.append("🔋 ").append(pct >= 0 ? pct + "%" : "N/A");
        sb.append(charging ? " ⚡ Charging" : "").append("\n");
        sb.append("━━━━━━━━━━━━━━\n");
        sb.append("▸ Status: ").append(r.optString("status", "unknown")).append("\n");
        sb.append("▸ Plugged: ").append(r.optString("pluggedType", "none")).append("\n");
        if (!r.isNull("temperatureC"))
            sb.append("▸ Temp: ").append(String.format(java.util.Locale.US, "%.1f°C", r.optDouble("temperatureC"))).append("\n");
        if (!r.isNull("voltageMilliVolts"))
            sb.append("▸ Voltage: ").append(r.optInt("voltageMilliVolts")).append(" mV\n");
        sb.append("▸ Health: ").append(r.optString("health", "unknown")).append("\n");
        String tech = r.optString("technology", "");
        if (!tech.isEmpty()) sb.append("▸ Tech: ").append(tech).append("\n");
        return sb.toString();
    }

    private String formatHealthReport(JSONObject r) {
        StringBuilder sb = new StringBuilder();
        sb.append("🏥 Battery Health Report\n");
        sb.append("━━━━━━━━━━━━━━━━━━━\n");
        sb.append("▸ Health: ").append(r.optString("healthStatus", "unknown")).append("\n");
        sb.append("▸ Level: ").append(r.optInt("currentLevelPercent", -1)).append("%\n");
        sb.append("▸ ").append(r.optString("temperatureWarning", "")).append("\n");
        return sb.toString();
    }

    private String formatPowerSummary(JSONObject r) {
        StringBuilder sb = new StringBuilder();
        int pct = r.optInt("levelPercent", -1);
        boolean charging = r.optBoolean("charging", false);
        String bar = buildBar(pct);
        sb.append("🔋 Power Summary\n");
        sb.append("━━━━━━━━━━━━━━━━━━━\n");
        sb.append(bar).append(" ").append(pct >= 0 ? pct + "%" : "N/A");
        sb.append(charging ? " ⚡" : "").append("\n");
        sb.append("▸ Status: ").append(r.optString("status", "unknown")).append("\n");
        if (!r.isNull("temperatureC"))
            sb.append("▸ Temp: ").append(String.format(java.util.Locale.US, "%.1f°C", r.optDouble("temperatureC"))).append("\n");
        sb.append("▸ Health: ").append(r.optString("health", "unknown")).append("\n");
        return sb.toString();
    }

    private String buildBar(int pct) {
        if (pct < 0) return "[????]";
        int filled = pct / 10;
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 10; i++) sb.append(i < filled ? "█" : "░");
        sb.append("]");
        return sb.toString();
    }

    private String emptyJson(String v) {
        return v == null || v.trim().isEmpty() ? "{}" : v;
    }

    private String ok(String output, String displayText) throws Exception {
        JSONObject r = new JSONObject().put("success", true).put("output", output);
        if (displayText != null && !displayText.isEmpty()) r.put("_displayText", displayText);
        return r.toString();
    }

    private String ok(String output) throws Exception {
        return ok(output, null);
    }

    private String error(String msg) throws Exception {
        return new JSONObject().put("success", false).put("error", msg).toString();
    }
}
