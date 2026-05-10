package ai.rorsch.moduleplugins.phone_check_report;

import ai.rorsch.pandagenie.module.runtime.HtmlOutputHelper;
import ai.rorsch.pandagenie.module.runtime.ModuleLlm;
import ai.rorsch.pandagenie.module.runtime.ModulePlugin;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.MediaDrm;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public class PhoneCheckReportPlugin implements ModulePlugin {
    private static final UUID WIDEVINE_UUID = new UUID(0xedef8ba979d64aceL, 0xa3c827dcd51d21edL);

    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        JSONObject params = new JSONObject(emptyJson(paramsJson));
        try {
            if ("generateInspectionReport".equals(action)) {
                return generateInspectionReport(context, params);
            }
            if ("generateAiInspectionAdvice".equals(action)) {
                return generateAiInspectionAdvice(context, params);
            }
            if ("runStorageSpeedTest".equals(action)) {
                JSONObject speed = runStorageSpeedTest(context, clamp(params.optInt("sizeMB", 16), 4, 64));
                return ok(speed, formatSpeedText(speed), formatSpeedHtml(speed));
            }
            if ("getDeviceArchive".equals(action)) {
                JSONObject latest = readLatestReport(context);
                return ok(latest, formatArchiveText(latest), buildReportHtml(latest));
            }
            if ("openPage".equals(action)) {
                return new JSONObject().put("success", true).put("output", "{}").put("_openModule", true).toString();
            }
            return error("Unsupported action: " + action);
        } catch (Exception e) {
            return error(e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    private String generateInspectionReport(Context context, JSONObject params) throws Exception {
        String scenario = params.optString("scenario", "normal").trim();
        boolean runSpeed = params.optBoolean("runStorageSpeedTest", true);

        JSONObject report = new JSONObject();
        report.put("module", "phone_check_report");
        report.put("scenario", scenario.isEmpty() ? "normal" : scenario);
        report.put("generatedAt", System.currentTimeMillis());
        report.put("device", collectDevice());
        report.put("cpu", collectCpu());
        report.put("memory", collectMemory(context));
        report.put("storage", collectStorage());
        report.put("display", collectDisplay(context));
        report.put("battery", collectBattery(context));
        report.put("sensors", collectSensors(context));
        report.put("camera2", collectCamera(context));
        report.put("drm", collectDrm());
        report.put("apps", collectApps(context));
        if (runSpeed) {
            report.put("storageSpeed", runStorageSpeedTest(context, 16));
        }

        JSONObject verdict = buildVerdict(report);
        report.put("verdict", verdict);
        saveLatestReport(context, report);
        return ok(report, formatReportText(report), buildReportHtml(report));
    }

    private String generateAiInspectionAdvice(Context context, JSONObject params) throws Exception {
        JSONObject reportResponse = new JSONObject(generateInspectionReport(context, params));
        JSONObject report = new JSONObject(reportResponse.optString("output", "{}"));
        String scenario = report.optString("scenario", params.optString("scenario", "normal"));
        String instruction = params.optString("instruction", "").trim();
        String language = params.optString("language", "zh").trim();
        int maxTokens = clamp(params.optInt("maxTokens", 768), 128, 1024);

        String prompt = "你是 PandaGenie 的手机验机顾问。请根据下面的验机报告，给普通用户一份可信、谨慎的验机建议。"
                + "\n要求："
                + "\n1. 先给一句总体结论。"
                + "\n2. 分点说明值得关注的风险、可以复测的项目、适合截图分享的结论。"
                + "\n3. 不要根据报告没有的数据做确定性判断。"
                + "\n4. 输出要适合手机屏幕阅读。"
                + (instruction.isEmpty() ? "" : "\n用户额外要求：" + instruction)
                + "\n场景：" + scenario
                + "\n输出语言：" + language
                + "\n\n可读报告：\n" + formatReportText(report)
                + "\n\n结构化摘要：\n" + compactJson(report, 12000);
        JSONObject request = new JSONObject()
                .put("action", "phone_check_report.generateAiInspectionAdvice")
                .put("prompt", prompt)
                .put("temperature", 0.2)
                .put("maxTokens", maxTokens);
        JSONObject llm = new JSONObject(ModuleLlm.completeJson(context, request.toString()));
        if (!llm.optBoolean("success", false)) {
            throw new IllegalStateException(llm.optString("error", "LLM request failed"));
        }

        String advice = llm.optString("text", "").trim();
        JSONObject verdict = report.optJSONObject("verdict");
        JSONObject out = new JSONObject();
        out.put("scenario", scenario);
        out.put("score", verdict != null ? verdict.optInt("score") : JSONObject.NULL);
        out.put("report", report);
        out.put("advice", advice);
        out.put("source", "llm");

        String display = "✨ 智能验机建议\n" + advice;
        String html = HtmlOutputHelper.card("✨", "AI 验机建议",
                HtmlOutputHelper.badge("LLM", "blue")
                        + HtmlOutputHelper.keyValue(new String[][]{
                        {"场景", scenario},
                        {"评分", verdict != null ? verdict.optInt("score") + "/100" : "-"},
                        {"报告时间", String.valueOf(report.optLong("generatedAt"))}
                })
                        + HtmlOutputHelper.p(advice)
                        + HtmlOutputHelper.muted("建议基于当前报告生成，不替代人工验机。"));
        return ok(out, display, html);
    }

    private JSONObject collectDevice() throws Exception {
        JSONObject o = new JSONObject();
        o.put("brand", Build.BRAND);
        o.put("model", Build.MODEL);
        o.put("manufacturer", Build.MANUFACTURER);
        o.put("device", Build.DEVICE);
        o.put("product", Build.PRODUCT);
        o.put("androidVersion", Build.VERSION.RELEASE);
        o.put("sdkInt", Build.VERSION.SDK_INT);
        o.put("buildNumber", Build.DISPLAY);
        o.put("fingerprint", Build.FINGERPRINT);
        return o;
    }

    private JSONObject collectCpu() throws Exception {
        JSONObject o = new JSONObject();
        o.put("cores", Runtime.getRuntime().availableProcessors());
        o.put("arch", System.getProperty("os.arch", ""));
        o.put("abis", new JSONArray(Build.SUPPORTED_ABIS));
        return o;
    }

    private JSONObject collectMemory(Context context) throws Exception {
        JSONObject o = new JSONObject();
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return o.put("error", "ActivityManager unavailable");
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        long used = mi.totalMem - mi.availMem;
        o.put("totalBytes", mi.totalMem);
        o.put("availableBytes", mi.availMem);
        o.put("usedBytes", used);
        o.put("usedPercent", mi.totalMem > 0 ? round1(used * 100.0 / mi.totalMem) : 0);
        o.put("lowMemory", mi.lowMemory);
        return o;
    }

    private JSONObject collectStorage() throws Exception {
        JSONObject o = new JSONObject();
        o.put("data", stat(Environment.getDataDirectory()));
        o.put("primary", stat(Environment.getExternalStorageDirectory()));
        return o;
    }

    private JSONObject stat(File path) throws Exception {
        JSONObject o = new JSONObject();
        if (path == null || !path.exists()) return o.put("available", false);
        StatFs sf = new StatFs(path.getAbsolutePath());
        long block = sf.getBlockSizeLong();
        long total = sf.getBlockCountLong() * block;
        long free = sf.getAvailableBlocksLong() * block;
        long used = total - free;
        o.put("path", path.getAbsolutePath());
        o.put("totalBytes", total);
        o.put("availableBytes", free);
        o.put("usedBytes", used);
        o.put("usedPercent", total > 0 ? round1(used * 100.0 / total) : 0);
        return o;
    }

    private JSONObject collectDisplay(Context context) throws Exception {
        JSONObject o = new JSONObject();
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return o.put("error", "WindowManager unavailable");
        Display display = wm.getDefaultDisplay();
        DisplayMetrics dm = new DisplayMetrics();
        display.getRealMetrics(dm);
        o.put("widthPx", dm.widthPixels);
        o.put("heightPx", dm.heightPixels);
        o.put("densityDpi", dm.densityDpi);
        o.put("density", dm.density);
        o.put("refreshRateHz", round1(display.getRefreshRate()));
        return o;
    }

    private JSONObject collectBattery(Context context) throws Exception {
        JSONObject o = new JSONObject();
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent i = Build.VERSION.SDK_INT >= 33
                ? context.registerReceiver(null, filter, Context.RECEIVER_NOT_EXPORTED)
                : context.registerReceiver(null, filter);
        if (i == null) return o.put("error", "Battery status unavailable");
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int pct = level >= 0 && scale > 0 ? Math.round(level * 100f / scale) : -1;
        int temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
        int voltage = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
        int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
        int health = i.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN);
        o.put("levelPercent", pct);
        o.put("temperatureC", temp >= 0 ? round1(temp / 10.0) : JSONObject.NULL);
        o.put("voltageMilliVolts", voltage >= 0 ? voltage : JSONObject.NULL);
        o.put("status", statusText(status));
        o.put("health", healthText(health));
        return o;
    }

    private JSONObject collectSensors(Context context) throws Exception {
        JSONObject o = new JSONObject();
        SensorManager sm = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        JSONArray names = new JSONArray();
        JSONObject key = new JSONObject();
        if (sm != null) {
            List<Sensor> sensors = sm.getSensorList(Sensor.TYPE_ALL);
            for (int i = 0; i < sensors.size(); i++) names.put(sensors.get(i).getName());
            key.put("accelerometer", hasSensor(sm, Sensor.TYPE_ACCELEROMETER));
            key.put("gyroscope", hasSensor(sm, Sensor.TYPE_GYROSCOPE));
            key.put("magnetic", hasSensor(sm, Sensor.TYPE_MAGNETIC_FIELD));
            key.put("light", hasSensor(sm, Sensor.TYPE_LIGHT));
            key.put("proximity", hasSensor(sm, Sensor.TYPE_PROXIMITY));
            key.put("barometer", hasSensor(sm, Sensor.TYPE_PRESSURE));
        }
        o.put("count", names.length());
        o.put("keySensors", key);
        o.put("names", names);
        return o;
    }

    private boolean hasSensor(SensorManager sm, int type) {
        return sm.getDefaultSensor(type) != null;
    }

    private JSONObject collectCamera(Context context) throws Exception {
        JSONObject o = new JSONObject();
        CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        JSONArray cameras = new JSONArray();
        if (cm == null) return o.put("error", "CameraManager unavailable");
        String[] ids = cm.getCameraIdList();
        for (String id : ids) {
            CameraCharacteristics c = cm.getCameraCharacteristics(id);
            JSONObject item = new JSONObject();
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            Integer level = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
            item.put("id", id);
            item.put("facing", facing == null ? "unknown" : (facing == CameraCharacteristics.LENS_FACING_FRONT ? "front" : "back"));
            item.put("hardwareLevel", cameraLevel(level));
            cameras.put(item);
        }
        o.put("count", ids.length);
        o.put("cameras", cameras);
        return o;
    }

    private JSONObject collectDrm() throws Exception {
        JSONObject o = new JSONObject();
        MediaDrm drm = null;
        try {
            drm = new MediaDrm(WIDEVINE_UUID);
            o.put("widevine", true);
            o.put("securityLevel", safeProperty(drm, "securityLevel"));
            o.put("vendor", safeProperty(drm, "vendor"));
            o.put("version", safeProperty(drm, "version"));
        } catch (Exception e) {
            o.put("widevine", false);
            o.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        } finally {
            if (drm != null) {
                if (Build.VERSION.SDK_INT >= 28) drm.close(); else drm.release();
            }
        }
        return o;
    }

    private String safeProperty(MediaDrm drm, String name) {
        try { return drm.getPropertyString(name); } catch (Exception e) { return ""; }
    }

    private JSONObject collectApps(Context context) throws Exception {
        JSONObject o = new JSONObject();
        PackageManager pm = context.getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(0);
        int system = 0;
        JSONArray watch = new JSONArray();
        Set<String> seen = new HashSet<String>();
        String[] keywords = new String[]{"market", "game", "video", "browser", "wallet", "news", "ads", "store", "reader"};
        for (ApplicationInfo app : apps) {
            boolean isSystem = (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            if (isSystem) system++;
            String pkg = app.packageName == null ? "" : app.packageName.toLowerCase(Locale.ROOT);
            if (isSystem && watch.length() < 20) {
                for (String k : keywords) {
                    if (pkg.contains(k) && !seen.contains(pkg)) {
                        seen.add(pkg);
                        watch.put(app.packageName);
                        break;
                    }
                }
            }
        }
        o.put("installedCount", apps.size());
        o.put("systemAppCount", system);
        o.put("preinstallWatchList", watch);
        return o;
    }

    private JSONObject runStorageSpeedTest(Context context, int sizeMb) throws Exception {
        File dir = resolveSpeedTestDir(context);
        if (dir == null) {
            return speedUnavailable(sizeMb, "未找到可写入的模块沙箱目录，已跳过存储测速");
        }
        File file = new File(dir, "speed_test.bin");
        byte[] buffer = new byte[1024 * 1024];
        for (int i = 0; i < buffer.length; i++) buffer[i] = (byte) (i * 31);

        long writeStart = SystemClock.elapsedRealtime();
        try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(file))) {
            for (int i = 0; i < sizeMb; i++) out.write(buffer);
            out.flush();
        } catch (Exception e) {
            return speedUnavailable(sizeMb, "存储测速写入失败：" + shortError(e));
        }
        long writeMs = Math.max(1, SystemClock.elapsedRealtime() - writeStart);

        long checksum = 0;
        long readStart = SystemClock.elapsedRealtime();
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(file))) {
            int n;
            while ((n = in.read(buffer)) >= 0) {
                if (n > 0) checksum += buffer[0] & 0xff;
            }
        } catch (Exception e) {
            return speedUnavailable(sizeMb, "存储测速读取失败：" + shortError(e));
        } finally {
            if (file.exists()) file.delete();
        }
        long readMs = Math.max(1, SystemClock.elapsedRealtime() - readStart);

        JSONObject o = new JSONObject();
        o.put("available", true);
        o.put("sizeMB", sizeMb);
        o.put("writeMBps", round1(sizeMb * 1000.0 / writeMs));
        o.put("readMBps", round1(sizeMb * 1000.0 / readMs));
        o.put("checksumHint", checksum);
        o.put("note", "Lightweight app-cache test; not a full UFS benchmark.");
        return o;
    }

    private File resolveSpeedTestDir(Context context) {
        List<File> roots = new ArrayList<>();
        if (context.getCacheDir() != null) roots.add(context.getCacheDir());
        if (context.getFilesDir() != null) roots.add(context.getFilesDir());
        if (Build.VERSION.SDK_INT >= 21 && context.getCodeCacheDir() != null) roots.add(context.getCodeCacheDir());
        for (File root : roots) {
            if (root == null) continue;
            if (!root.exists() && !root.mkdirs()) continue;
            File dir = new File(root, "phone_check_speed");
            if ((dir.exists() || dir.mkdirs()) && dir.isDirectory() && dir.canWrite()) {
                return dir;
            }
        }
        return null;
    }

    private JSONObject speedUnavailable(int sizeMb, String reason) throws Exception {
        JSONObject o = new JSONObject();
        o.put("available", false);
        o.put("sizeMB", sizeMb);
        o.put("writeMBps", JSONObject.NULL);
        o.put("readMBps", JSONObject.NULL);
        o.put("checksumHint", 0);
        o.put("note", reason);
        return o;
    }

    private String shortError(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.trim().isEmpty()) msg = e.getClass().getSimpleName();
        return msg.length() > 160 ? msg.substring(0, 160) + "..." : msg;
    }

    private JSONObject buildVerdict(JSONObject r) throws Exception {
        int score = 100;
        JSONArray risks = new JSONArray();
        JSONObject battery = r.optJSONObject("battery");
        if (battery != null && !battery.isNull("temperatureC") && battery.optDouble("temperatureC") >= 42) {
            score -= 12;
            risks.put("电池温度偏高，建议静置降温后复测");
        }
        JSONObject storage = r.optJSONObject("storage");
        JSONObject primary = storage != null ? storage.optJSONObject("primary") : null;
        if (primary != null && primary.optDouble("usedPercent") >= 85) {
            score -= 10;
            risks.put("存储占用较高，可能影响后续体验");
        }
        JSONObject drm = r.optJSONObject("drm");
        String widevine = drm != null ? drm.optString("securityLevel", "") : "";
        if (widevine.length() == 0 || "L3".equalsIgnoreCase(widevine)) {
            score -= 10;
            risks.put("Widevine 不是 L1 或未读取到，高清视频权益需留意");
        }
        JSONObject sensors = r.optJSONObject("sensors");
        JSONObject key = sensors != null ? sensors.optJSONObject("keySensors") : null;
        if (key != null && (!key.optBoolean("gyroscope") || !key.optBoolean("magnetic"))) {
            score -= 8;
            risks.put("关键传感器不完整，游戏/导航/AR 体验需复测");
        }
        JSONObject camera = r.optJSONObject("camera2");
        if (camera != null && camera.optInt("count", 0) == 0) {
            score -= 15;
            risks.put("未读取到 Camera2 信息");
        }
        JSONObject apps = r.optJSONObject("apps");
        if (apps != null && apps.optInt("installedCount", 0) >= 220) {
            score -= 5;
            risks.put("已安装应用较多，建议关注后台和通知");
        }
        if (risks.length() == 0) risks.put("未发现明显异常，建议保留报告用于后续复测对比");

        String level = score >= 85 ? "green" : (score >= 70 ? "orange" : "red");
        String title = score >= 85 ? "状态良好" : (score >= 70 ? "建议复测" : "重点排查");
        JSONObject o = new JSONObject();
        o.put("score", Math.max(0, score));
        o.put("levelColor", level);
        o.put("title", title);
        o.put("risks", risks);
        o.put("coreJudgement", title + "，验机评分 " + Math.max(0, score) + "/100");
        return o;
    }

    private String formatReportText(JSONObject r) {
        JSONObject v = r.optJSONObject("verdict");
        JSONObject d = r.optJSONObject("device");
        JSONObject display = r.optJSONObject("display");
        JSONObject battery = r.optJSONObject("battery");
        JSONObject memory = r.optJSONObject("memory");
        JSONObject storage = r.optJSONObject("storage");
        JSONObject apps = r.optJSONObject("apps");
        JSONObject drm = r.optJSONObject("drm");
        JSONArray risks = v != null ? v.optJSONArray("risks") : null;
        StringBuilder sb = new StringBuilder();
        sb.append("✅ PandaGenie 机圈验机报告\n");
        sb.append("核心判断：").append(v != null ? v.optString("coreJudgement") : "已生成报告").append("\n\n");
        sb.append("设备：").append(d != null ? d.optString("brand") + " " + d.optString("model") : "-").append("\n");
        sb.append("系统：Android ").append(d != null ? d.optString("androidVersion") : "-").append(" / API ").append(d != null ? d.optInt("sdkInt") : 0).append("\n");
        if (display != null) sb.append("屏幕：").append(display.optInt("widthPx")).append("×").append(display.optInt("heightPx")).append(" @ ").append(display.optDouble("refreshRateHz")).append("Hz\n");
        if (memory != null) sb.append("内存：").append(formatBytes(memory.optLong("availableBytes"))).append(" 可用 / ").append(formatBytes(memory.optLong("totalBytes"))).append(" 总计\n");
        JSONObject primary = storage != null ? storage.optJSONObject("primary") : null;
        if (primary != null) sb.append("存储：").append(formatBytes(primary.optLong("availableBytes"))).append(" 可用，占用 ").append(primary.optDouble("usedPercent")).append("%\n");
        if (battery != null) sb.append("电池：").append(battery.optInt("levelPercent")).append("%，").append(valueOrDash(battery, "temperatureC")).append("°C，健康 ").append(battery.optString("health")).append("\n");
        if (drm != null) sb.append("DRM：Widevine ").append(drm.optString("securityLevel", drm.optBoolean("widevine") ? "可用" : "不可用")).append("\n");
        if (apps != null) sb.append("应用：").append(apps.optInt("installedCount")).append(" 个，预装关注项 ").append(apps.optJSONArray("preinstallWatchList") != null ? apps.optJSONArray("preinstallWatchList").length() : 0).append(" 个\n");
        sb.append("\n重点：\n");
        if (risks != null) {
            for (int i = 0; i < risks.length(); i++) sb.append("· ").append(risks.optString(i)).append("\n");
        }
        sb.append("\n由 PandaGenie 生成 · 开源免费 Android AI 助手");
        return sb.toString();
    }

    private String buildReportHtml(JSONObject r) {
        if (r == null || r.length() == 0) return HtmlOutputHelper.card("✅", "机圈验机报告", HtmlOutputHelper.muted("暂无历史报告"));
        JSONObject v = r.optJSONObject("verdict");
        JSONObject d = r.optJSONObject("device");
        JSONObject display = r.optJSONObject("display");
        JSONObject battery = r.optJSONObject("battery");
        JSONObject memory = r.optJSONObject("memory");
        JSONObject storage = r.optJSONObject("storage");
        JSONObject primary = storage != null ? storage.optJSONObject("primary") : null;
        JSONObject speed = r.optJSONObject("storageSpeed");
        JSONObject apps = r.optJSONObject("apps");
        String device = d != null ? (d.optString("brand") + " " + d.optString("model")).trim() : "-";
        String score = v != null ? v.optInt("score") + "/100" : "-";
        String color = v != null ? v.optString("levelColor", "blue") : "blue";
        StringBuilder body = new StringBuilder();
        body.append(HtmlOutputHelper.badge(v != null ? v.optString("title", "报告") : "报告", color));
        body.append(HtmlOutputHelper.metricGrid(new String[][]{
                {score, "验机评分"},
                {device, "设备"},
                {display != null ? display.optInt("widthPx") + "×" + display.optInt("heightPx") : "-", "屏幕"},
                {battery != null ? battery.optInt("levelPercent") + "%" : "-", "电量"}
        }));
        body.append(HtmlOutputHelper.keyValue(new String[][]{
                {"系统版本", d != null ? "Android " + d.optString("androidVersion") + " / API " + d.optInt("sdkInt") : "-"},
                {"内存", memory != null ? formatBytes(memory.optLong("availableBytes")) + " 可用 / " + formatBytes(memory.optLong("totalBytes")) : "-"},
                {"存储", primary != null ? formatBytes(primary.optLong("availableBytes")) + " 可用，占用 " + primary.optDouble("usedPercent") + "%" : "-"},
                {"电池温度", battery != null ? valueOrDash(battery, "temperatureC") + "°C" : "-"},
                {"存储测速", formatSpeedSummary(speed)},
                {"应用数量", apps != null ? apps.optInt("installedCount") + " 个" : "-"}
        }));
        JSONArray risks = v != null ? v.optJSONArray("risks") : null;
        if (risks != null) {
            String[][] items = new String[Math.min(risks.length(), 4)][2];
            for (int i = 0; i < items.length; i++) {
                items[i][0] = "•";
                items[i][1] = risks.optString(i);
            }
            body.append(HtmlOutputHelper.iconList(items));
        }
        body.append(HtmlOutputHelper.muted("由 PandaGenie 生成 · 开源免费 Android AI 助手"));
        return HtmlOutputHelper.card("✅", "PandaGenie 机圈验机报告", body.toString());
    }

    private String formatSpeedText(JSONObject speed) {
        if (speed == null || !speed.optBoolean("available", true)) {
            return "💾 存储轻量测速\n核心判断：已跳过\n说明：" + (speed != null ? speed.optString("note", "测速不可用") : "测速不可用");
        }
        return "💾 存储轻量测速\n核心判断：" + speedJudge(speed)
                + "\n写入：" + speed.optDouble("writeMBps") + " MB/s"
                + "\n读取：" + speed.optDouble("readMBps") + " MB/s"
                + "\n说明：" + speed.optString("note");
    }

    private String formatSpeedHtml(JSONObject speed) {
        if (speed == null || !speed.optBoolean("available", true)) {
            return HtmlOutputHelper.card("💾", "存储轻量测速",
                    HtmlOutputHelper.badge("已跳过", "orange")
                            + HtmlOutputHelper.muted(speed != null ? speed.optString("note", "测速不可用") : "测速不可用"));
        }
        String body = HtmlOutputHelper.badge(speedJudge(speed), speed.optDouble("writeMBps") >= 80 ? "green" : "orange")
                + HtmlOutputHelper.metricGrid(new String[][]{
                {speed.optDouble("writeMBps") + " MB/s", "写入"},
                {speed.optDouble("readMBps") + " MB/s", "读取"},
                {speed.optInt("sizeMB") + " MB", "测试大小"}
        }) + HtmlOutputHelper.muted(speed.optString("note"));
        return HtmlOutputHelper.card("💾", "存储轻量测速", body);
    }

    private String formatSpeedSummary(JSONObject speed) {
        if (speed == null) return "未测试";
        if (!speed.optBoolean("available", true)) return "已跳过：" + speed.optString("note", "测速不可用");
        return "写 " + speed.optDouble("writeMBps") + " MB/s，读 " + speed.optDouble("readMBps") + " MB/s";
    }

    private String speedJudge(JSONObject speed) {
        if (speed == null || !speed.optBoolean("available", true)) return "已跳过";
        double w = speed.optDouble("writeMBps");
        double r = speed.optDouble("readMBps");
        if (w >= 120 && r >= 160) return "表现较好";
        if (w >= 60 && r >= 100) return "日常够用";
        return "建议复测";
    }

    private String formatArchiveText(JSONObject latest) {
        if (latest == null || latest.length() == 0) return "暂无历史验机报告";
        return formatReportText(latest);
    }

    private void saveLatestReport(Context context, JSONObject report) {
        try {
            File dir = new File(context.getFilesDir(), "phone_check_report");
            if (!dir.exists()) dir.mkdirs();
            FileOutputStream fos = new FileOutputStream(new File(dir, "latest.json"));
            fos.write(report.toString(2).getBytes("UTF-8"));
            fos.close();
        } catch (Exception ignored) {}
    }

    private JSONObject readLatestReport(Context context) {
        try {
            File file = new File(new File(context.getFilesDir(), "phone_check_report"), "latest.json");
            if (!file.exists()) return new JSONObject();
            byte[] data = new byte[(int) file.length()];
            FileInputStream fis = new FileInputStream(file);
            int read = fis.read(data);
            fis.close();
            return new JSONObject(new String(data, 0, Math.max(0, read), "UTF-8"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private String cameraLevel(Integer level) {
        if (level == null) return "unknown";
        if (level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) return "LEGACY";
        if (level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED) return "LIMITED";
        if (level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL) return "FULL";
        if (Build.VERSION.SDK_INT >= 24 && level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3) return "LEVEL_3";
        if (Build.VERSION.SDK_INT >= 28 && level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL) return "EXTERNAL";
        return String.valueOf(level);
    }

    private String statusText(int status) {
        if (status == BatteryManager.BATTERY_STATUS_CHARGING) return "charging";
        if (status == BatteryManager.BATTERY_STATUS_DISCHARGING) return "discharging";
        if (status == BatteryManager.BATTERY_STATUS_FULL) return "full";
        if (status == BatteryManager.BATTERY_STATUS_NOT_CHARGING) return "not_charging";
        return "unknown";
    }

    private String healthText(int health) {
        if (health == BatteryManager.BATTERY_HEALTH_GOOD) return "Good";
        if (health == BatteryManager.BATTERY_HEALTH_OVERHEAT) return "Overheat";
        if (health == BatteryManager.BATTERY_HEALTH_COLD) return "Cold";
        if (health == BatteryManager.BATTERY_HEALTH_DEAD) return "Dead";
        if (health == BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE) return "Over voltage";
        return "Unknown";
    }

    private String valueOrDash(JSONObject o, String key) {
        return o == null || o.isNull(key) ? "-" : String.valueOf(o.opt(key));
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb);
        double gb = mb / 1024.0;
        if (gb < 1024) return String.format(Locale.US, "%.2f GB", gb);
        return String.format(Locale.US, "%.2f TB", gb / 1024.0);
    }

    private String compactJson(JSONObject obj, int maxLen) {
        String text = obj == null ? "{}" : obj.toString();
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
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
