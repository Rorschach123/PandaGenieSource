package ai.rorsch.moduleplugins.phone_test_recorder;

import ai.rorsch.pandagenie.module.runtime.HtmlOutputHelper;
import ai.rorsch.pandagenie.module.runtime.ModulePlugin;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class PhoneTestRecorderPlugin implements ModulePlugin {

    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        JSONObject params = new JSONObject(emptyJson(paramsJson));
        try {
            if ("startTestSession".equals(action)) return startSession(context, params);
            if ("recordSample".equals(action)) return recordSample(context, params);
            if ("finishTestReport".equals(action)) return finishReport(context, params);
            if ("compareTestReports".equals(action)) return compareReports(context, params);
            if ("listTestReports".equals(action)) return listReports(context, params);
            if ("openPage".equals(action)) {
                return new JSONObject().put("success", true).put("output", "{}").put("_openModule", true).toString();
            }
            return error("Unsupported action: " + action);
        } catch (Exception e) {
            return error(e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    private String startSession(Context context, JSONObject params) throws Exception {
        String mode = normalizeMode(params.optString("mode", "charging"));
        String label = params.optString("label", defaultLabel(mode)).trim();
        String note = params.optString("note", "").trim();
        String id = timeId(mode);
        JSONObject session = new JSONObject();
        session.put("sessionId", id);
        session.put("mode", mode);
        session.put("label", label.isEmpty() ? defaultLabel(mode) : label);
        session.put("note", note);
        session.put("startedAt", System.currentTimeMillis());
        session.put("completed", false);
        JSONArray samples = new JSONArray();
        samples.put(buildSample(context, 0));
        session.put("samples", samples);
        saveSession(context, session);
        return ok(session, formatStartedText(session), formatSessionHtml(session, null));
    }

    private String recordSample(Context context, JSONObject params) throws Exception {
        JSONObject session = requireSession(context, params.optString("sessionId", ""));
        JSONArray samples = session.optJSONArray("samples");
        if (samples == null) samples = new JSONArray();
        long start = session.optLong("startedAt", System.currentTimeMillis());
        samples.put(buildSample(context, Math.max(0, (System.currentTimeMillis() - start) / 1000)));
        session.put("samples", samples);
        saveSession(context, session);
        JSONObject out = new JSONObject();
        out.put("sessionId", session.optString("sessionId"));
        out.put("sampleCount", samples.length());
        out.put("latestSample", samples.getJSONObject(samples.length() - 1));
        return ok(out, formatSampleText(session), formatSessionHtml(session, summarize(session)));
    }

    private String finishReport(Context context, JSONObject params) throws Exception {
        JSONObject session = requireSession(context, params.optString("sessionId", ""));
        JSONArray samples = session.optJSONArray("samples");
        if (samples == null) samples = new JSONArray();
        long start = session.optLong("startedAt", System.currentTimeMillis());
        samples.put(buildSample(context, Math.max(0, (System.currentTimeMillis() - start) / 1000)));
        session.put("samples", samples);
        session.put("completed", true);
        session.put("endedAt", System.currentTimeMillis());
        JSONObject summary = summarize(session);
        session.put("summary", summary);
        saveSession(context, session);
        return ok(session, formatReportText(session, summary), formatSessionHtml(session, summary));
    }

    private String compareReports(Context context, JSONObject params) throws Exception {
        JSONObject a = requireSession(context, params.optString("sessionIdA", ""));
        JSONObject b = requireSession(context, params.optString("sessionIdB", ""));
        JSONObject sa = a.optJSONObject("summary");
        if (sa == null) sa = summarize(a);
        JSONObject sb = b.optJSONObject("summary");
        if (sb == null) sb = summarize(b);

        JSONObject out = new JSONObject();
        out.put("sessionA", a.optString("sessionId"));
        out.put("sessionB", b.optString("sessionId"));
        out.put("labelA", a.optString("label"));
        out.put("labelB", b.optString("label"));
        out.put("batteryDeltaDiff", round1(sb.optDouble("batteryDeltaPercent") - sa.optDouble("batteryDeltaPercent")));
        out.put("maxTempDiff", round1(sb.optDouble("maxTemperatureC") - sa.optDouble("maxTemperatureC")));
        out.put("avgPowerDiff", round1(sb.optDouble("avgPowerW") - sa.optDouble("avgPowerW")));
        out.put("durationDiffMinutes", round1(sb.optDouble("durationMinutes") - sa.optDouble("durationMinutes")));
        out.put("winner", pickWinner(a.optString("mode"), sa, sb, a.optString("label"), b.optString("label")));
        return ok(out, formatCompareText(a, b, sa, sb, out), formatCompareHtml(a, b, sa, sb, out));
    }

    private String listReports(Context context, JSONObject params) throws Exception {
        String mode = params.optString("mode", "").trim();
        int limit = Math.max(1, Math.min(50, params.optInt("limit", 10)));
        File[] files = sessionsDir(context).listFiles();
        JSONArray arr = new JSONArray();
        if (files != null) {
            for (int i = files.length - 1; i >= 0 && arr.length() < limit; i--) {
                if (!files[i].getName().endsWith(".json")) continue;
                JSONObject s = readJson(files[i]);
                if (!mode.isEmpty() && !mode.equals(s.optString("mode"))) continue;
                JSONObject item = new JSONObject();
                item.put("sessionId", s.optString("sessionId"));
                item.put("mode", s.optString("mode"));
                item.put("label", s.optString("label"));
                item.put("completed", s.optBoolean("completed"));
                item.put("startedAt", s.optLong("startedAt"));
                item.put("summary", s.optJSONObject("summary"));
                arr.put(item);
            }
        }
        JSONObject out = new JSONObject().put("reports", arr).put("count", arr.length());
        return ok(out, formatListText(arr), formatListHtml(arr));
    }

    private JSONObject buildSample(Context context, long elapsedSec) throws Exception {
        JSONObject o = new JSONObject();
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent i = Build.VERSION.SDK_INT >= 33
                ? context.registerReceiver(null, filter, Context.RECEIVER_NOT_EXPORTED)
                : context.registerReceiver(null, filter);
        int level = -1, scale = -1, pct = -1, temp = -1, voltage = -1, status = 0;
        if (i != null) {
            level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            pct = level >= 0 && scale > 0 ? Math.round(level * 100f / scale) : -1;
            temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
            voltage = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
            status = i.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
        }
        long currentUa = Long.MIN_VALUE;
        double powerW = 0;
        BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
        if (bm != null && Build.VERSION.SDK_INT >= 21) {
            currentUa = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            if (currentUa != Long.MIN_VALUE && voltage > 0) {
                powerW = round2(Math.abs(currentUa) / 1000000.0 * voltage / 1000.0);
            }
        }
        ActivityManager.MemoryInfo mi = memoryInfo(context);
        o.put("timestamp", System.currentTimeMillis());
        o.put("elapsedSec", elapsedSec);
        o.put("levelPercent", pct);
        o.put("temperatureC", temp >= 0 ? round1(temp / 10.0) : JSONObject.NULL);
        o.put("voltageMilliVolts", voltage >= 0 ? voltage : JSONObject.NULL);
        o.put("currentNowMicroAmp", currentUa == Long.MIN_VALUE ? JSONObject.NULL : currentUa);
        o.put("estimatedPowerW", powerW);
        o.put("status", statusText(status));
        if (mi != null) {
            long used = mi.totalMem - mi.availMem;
            o.put("memoryUsedPercent", mi.totalMem > 0 ? round1(used * 100.0 / mi.totalMem) : 0);
            o.put("memoryAvailableBytes", mi.availMem);
        }
        return o;
    }

    private JSONObject summarize(JSONObject session) throws Exception {
        JSONArray samples = session.optJSONArray("samples");
        JSONObject out = new JSONObject();
        if (samples == null || samples.length() == 0) return out;
        JSONObject first = samples.getJSONObject(0);
        JSONObject last = samples.getJSONObject(samples.length() - 1);
        double maxTemp = -999;
        double minTemp = 999;
        double powerSum = 0;
        int powerCount = 0;
        StringBuilder levels = new StringBuilder();
        for (int i = 0; i < samples.length(); i++) {
            JSONObject s = samples.getJSONObject(i);
            if (!s.isNull("temperatureC")) {
                double t = s.optDouble("temperatureC");
                maxTemp = Math.max(maxTemp, t);
                minTemp = Math.min(minTemp, t);
            }
            double p = s.optDouble("estimatedPowerW", 0);
            if (p > 0) {
                powerSum += p;
                powerCount++;
            }
            int lv = s.optInt("levelPercent", -1);
            if (lv >= 0) levels.append(lv).append(i == samples.length() - 1 ? "" : ",");
        }
        long durationSec = Math.max(1, last.optLong("elapsedSec", 0));
        int startLevel = first.optInt("levelPercent", -1);
        int endLevel = last.optInt("levelPercent", -1);
        double batteryDelta = startLevel >= 0 && endLevel >= 0 ? endLevel - startLevel : 0;
        double tempDelta = (!first.isNull("temperatureC") && !last.isNull("temperatureC"))
                ? round1(last.optDouble("temperatureC") - first.optDouble("temperatureC")) : 0;
        out.put("durationSeconds", durationSec);
        out.put("durationMinutes", round1(durationSec / 60.0));
        out.put("sampleCount", samples.length());
        out.put("startLevelPercent", startLevel);
        out.put("endLevelPercent", endLevel);
        out.put("batteryDeltaPercent", batteryDelta);
        out.put("temperatureDeltaC", tempDelta);
        out.put("maxTemperatureC", maxTemp == -999 ? JSONObject.NULL : round1(maxTemp));
        out.put("minTemperatureC", minTemp == 999 ? JSONObject.NULL : round1(minTemp));
        out.put("avgPowerW", powerCount > 0 ? round2(powerSum / powerCount) : 0);
        out.put("levelSeries", levels.toString());
        out.put("judgement", judge(session.optString("mode"), out));
        return out;
    }

    private String judge(String mode, JSONObject s) {
        double tempMax = s.optDouble("maxTemperatureC", 0);
        double delta = s.optDouble("batteryDeltaPercent", 0);
        if ("charging".equals(mode)) {
            if (tempMax >= 44) return "充电温度偏高，建议改善散热后复测";
            if (delta >= 20) return "充电速度表现积极，温度可继续观察";
            return "样本偏短或电量变化较小，建议延长记录";
        }
        if ("standby".equals(mode)) {
            if (delta <= -8) return "待机耗电偏高，建议排查后台和弱网";
            if (delta <= -3) return "待机耗电略高，建议连续复测几晚";
            return "待机表现较稳";
        }
        if (tempMax >= 45) return "游戏发热偏高，建议降画质或加强散热";
        if (s.optDouble("temperatureDeltaC", 0) >= 8) return "温升明显，长时间游戏需关注";
        return "发热控制相对稳定";
    }

    private String formatStartedText(JSONObject s) {
        return "📈 已开始记录：" + s.optString("label") + "\n"
                + "类型：" + modeLabel(s.optString("mode")) + "\n"
                + "sessionId：" + s.optString("sessionId") + "\n"
                + "核心判断：已采集起点数据，后续采样越密，曲线越可信。";
    }

    private String formatSampleText(JSONObject s) {
        JSONArray arr = s.optJSONArray("samples");
        JSONObject latest = arr != null && arr.length() > 0 ? arr.optJSONObject(arr.length() - 1) : null;
        return "📍 已追加采样：" + s.optString("label") + "\n"
                + "样本数：" + (arr == null ? 0 : arr.length()) + "\n"
                + "电量：" + (latest != null ? latest.optInt("levelPercent", -1) + "%" : "-") + "\n"
                + "温度：" + (latest != null && !latest.isNull("temperatureC") ? latest.optDouble("temperatureC") + "°C" : "-") + "\n"
                + "核心判断：记录继续进行中。";
    }

    private String formatReportText(JSONObject session, JSONObject summary) {
        return "📈 PandaGenie 实测记录报告\n"
                + "核心判断：" + summary.optString("judgement") + "\n\n"
                + "测试：" + session.optString("label") + "\n"
                + "类型：" + modeLabel(session.optString("mode")) + "\n"
                + "时长：" + summary.optDouble("durationMinutes") + " 分钟\n"
                + "电量变化：" + signed(summary.optDouble("batteryDeltaPercent")) + "%\n"
                + "温度变化：" + signed(summary.optDouble("temperatureDeltaC")) + "°C\n"
                + "最高温：" + valueOrDash(summary, "maxTemperatureC") + "°C\n"
                + "平均估算功率：" + summary.optDouble("avgPowerW") + " W\n"
                + "样本数：" + summary.optInt("sampleCount") + "\n\n"
                + "曲线：" + sparkline(summary.optString("levelSeries")) + "\n"
                + "由 PandaGenie 生成 · 开源免费 Android AI 助手";
    }

    private String formatCompareText(JSONObject a, JSONObject b, JSONObject sa, JSONObject sb, JSONObject out) {
        return "🔁 实测对比报告\n"
                + "核心判断：" + out.optString("winner") + "\n\n"
                + a.optString("label") + "：电量 " + signed(sa.optDouble("batteryDeltaPercent")) + "%，最高温 " + valueOrDash(sa, "maxTemperatureC") + "°C\n"
                + b.optString("label") + "：电量 " + signed(sb.optDouble("batteryDeltaPercent")) + "%，最高温 " + valueOrDash(sb, "maxTemperatureC") + "°C\n"
                + "差异：电量 " + signed(out.optDouble("batteryDeltaDiff")) + "%，最高温 " + signed(out.optDouble("maxTempDiff")) + "°C，平均功率 " + signed(out.optDouble("avgPowerDiff")) + "W";
    }

    private String formatListText(JSONArray arr) {
        StringBuilder sb = new StringBuilder("📚 实测历史\n");
        sb.append("共 ").append(arr.length()).append(" 条\n\n");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.optJSONObject(i);
            if (item == null) continue;
            sb.append("· ").append(item.optString("label")).append(" / ").append(item.optString("mode"))
                    .append(" / ").append(item.optString("sessionId")).append("\n");
        }
        return sb.toString().trim();
    }

    private String formatSessionHtml(JSONObject session, JSONObject summary) {
        String mode = session.optString("mode");
        JSONArray samples = session.optJSONArray("samples");
        int sampleCount = samples == null ? 0 : samples.length();
        String body = HtmlOutputHelper.badge(modeLabel(mode), colorForMode(mode))
                + HtmlOutputHelper.metricGrid(new String[][]{
                {session.optString("label"), "测试名称"},
                {String.valueOf(sampleCount), "样本数"},
                {summary != null ? summary.optDouble("durationMinutes") + " 分钟" : "进行中", "时长"},
                {summary != null ? signed(summary.optDouble("batteryDeltaPercent")) + "%" : "-", "电量变化"}
        });
        if (summary != null) {
            body += HtmlOutputHelper.keyValue(new String[][]{
                    {"核心判断", summary.optString("judgement")},
                    {"最高温", valueOrDash(summary, "maxTemperatureC") + "°C"},
                    {"温度变化", signed(summary.optDouble("temperatureDeltaC")) + "°C"},
                    {"平均估算功率", summary.optDouble("avgPowerW") + " W"},
                    {"电量曲线", sparkline(summary.optString("levelSeries"))}
            });
        }
        body += HtmlOutputHelper.muted("由 PandaGenie 生成 · 开源免费 Android AI 助手");
        return HtmlOutputHelper.card("📈", "手机实测记录", body);
    }

    private String formatCompareHtml(JSONObject a, JSONObject b, JSONObject sa, JSONObject sb, JSONObject out) {
        List<String[]> rows = new ArrayList<String[]>();
        rows.add(new String[]{"电量变化", signed(sa.optDouble("batteryDeltaPercent")) + "%", signed(sb.optDouble("batteryDeltaPercent")) + "%"});
        rows.add(new String[]{"最高温", valueOrDash(sa, "maxTemperatureC") + "°C", valueOrDash(sb, "maxTemperatureC") + "°C"});
        rows.add(new String[]{"平均功率", sa.optDouble("avgPowerW") + " W", sb.optDouble("avgPowerW") + " W"});
        String body = HtmlOutputHelper.badge("对比结论", "blue")
                + HtmlOutputHelper.p(out.optString("winner"))
                + HtmlOutputHelper.table(new String[]{"指标", a.optString("label"), b.optString("label")}, rows);
        return HtmlOutputHelper.card("🔁", "实测对比报告", body);
    }

    private String formatListHtml(JSONArray arr) {
        List<String[]> rows = new ArrayList<String[]>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.optJSONObject(i);
            if (item != null) {
                rows.add(new String[]{item.optString("label"), modeLabel(item.optString("mode")), item.optString("sessionId")});
            }
        }
        return HtmlOutputHelper.card("📚", "实测历史", HtmlOutputHelper.table(new String[]{"名称", "类型", "ID"}, rows));
    }

    private String pickWinner(String mode, JSONObject a, JSONObject b, String labelA, String labelB) {
        if ("charging".equals(mode)) {
            double speedA = a.optDouble("batteryDeltaPercent") / Math.max(0.1, a.optDouble("durationMinutes"));
            double speedB = b.optDouble("batteryDeltaPercent") / Math.max(0.1, b.optDouble("durationMinutes"));
            return (speedB > speedA ? labelB : labelA) + " 充电增速更明显；同时关注最高温差异。";
        }
        if ("standby".equals(mode)) {
            return (Math.abs(b.optDouble("batteryDeltaPercent")) < Math.abs(a.optDouble("batteryDeltaPercent")) ? labelB : labelA)
                    + " 待机掉电更少。";
        }
        return (b.optDouble("maxTemperatureC") < a.optDouble("maxTemperatureC") ? labelB : labelA)
                + " 最高温更低，稳定性更值得优先复测。";
    }

    private ActivityManager.MemoryInfo memoryInfo(Context context) {
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return null;
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return mi;
        } catch (Exception e) {
            return null;
        }
    }

    private JSONObject requireSession(Context context, String id) throws Exception {
        if (id == null || id.trim().isEmpty()) throw new IllegalArgumentException("sessionId is required");
        File file = new File(sessionsDir(context), id.trim() + ".json");
        if (!file.exists()) throw new IllegalArgumentException("Session not found: " + id);
        return readJson(file);
    }

    private void saveSession(Context context, JSONObject session) throws Exception {
        File file = new File(sessionsDir(context), session.optString("sessionId") + ".json");
        FileOutputStream fos = new FileOutputStream(file);
        fos.write(session.toString(2).getBytes("UTF-8"));
        fos.close();
    }

    private JSONObject readJson(File file) throws Exception {
        byte[] data = new byte[(int) file.length()];
        FileInputStream fis = new FileInputStream(file);
        int read = fis.read(data);
        fis.close();
        return new JSONObject(new String(data, 0, Math.max(0, read), "UTF-8"));
    }

    private File sessionsDir(Context context) {
        File dir = new File(context.getFilesDir(), "phone_test_recorder");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private String normalizeMode(String mode) {
        String m = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
        if ("standby".equals(m) || "game".equals(m) || "charging".equals(m)) return m;
        return "charging";
    }

    private String defaultLabel(String mode) {
        if ("standby".equals(mode)) return "睡前待机耗电测试";
        if ("game".equals(mode)) return "游戏发热稳定性测试";
        return "充电曲线测试";
    }

    private String modeLabel(String mode) {
        if ("standby".equals(mode)) return "待机耗电";
        if ("game".equals(mode)) return "游戏发热";
        return "充电曲线";
    }

    private String colorForMode(String mode) {
        if ("standby".equals(mode)) return "blue";
        if ("game".equals(mode)) return "orange";
        return "green";
    }

    private String timeId(String mode) {
        return mode + "_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + "_" + UUID.randomUUID().toString().substring(0, 4);
    }

    private String statusText(int status) {
        if (status == BatteryManager.BATTERY_STATUS_CHARGING) return "charging";
        if (status == BatteryManager.BATTERY_STATUS_DISCHARGING) return "discharging";
        if (status == BatteryManager.BATTERY_STATUS_FULL) return "full";
        if (status == BatteryManager.BATTERY_STATUS_NOT_CHARGING) return "not_charging";
        return "unknown";
    }

    private String sparkline(String series) {
        if (series == null || series.trim().isEmpty()) return "-";
        String[] parts = series.split(",");
        String ticks = "▁▂▃▄▅▆▇█";
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            try {
                int v = Integer.parseInt(p.trim());
                int idx = Math.max(0, Math.min(7, v * 8 / 101));
                sb.append(ticks.charAt(idx));
            } catch (Exception ignored) {}
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }

    private String valueOrDash(JSONObject o, String key) {
        return o == null || o.isNull(key) ? "-" : String.valueOf(o.opt(key));
    }

    private String signed(double v) {
        return (v > 0 ? "+" : "") + round1(v);
    }

    private double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private String emptyJson(String v) {
        return v == null || v.trim().isEmpty() ? "{}" : v;
    }

    private String ok(JSONObject output, String displayText, String displayHtml) throws Exception {
        JSONObject r = new JSONObject().put("success", true).put("output", output.toString());
        if (displayText != null) r.put("_displayText", displayText);
        if (displayHtml != null) r.put("_displayHtml", displayHtml);
        return r.toString();
    }

    private String error(String msg) throws Exception {
        return new JSONObject().put("success", false).put("error", msg).toString();
    }
}
