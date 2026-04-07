package ai.rorsch.moduleplugins.device_info;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Point;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;

import ai.rorsch.pandagenie.module.runtime.ModulePlugin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Locale;

/**
 * PandaGenie 设备信息模块插件。
 * <p>
 * <b>模块用途：</b>读取本机硬件与系统摘要，包括品牌型号、Android 版本、CPU 与 {@code /proc/cpuinfo}、内存、
 * 内外置存储、屏幕参数及汇总信息，供任务或 AI 判断运行环境、排查性能问题。
 * </p>
 * <p>
 * <b>对外 API（{@code action}）：</b>{@code getDeviceInfo}、{@code getCpuInfo}、{@code getMemoryInfo}、
 * {@code getStorageInfo}、{@code getDisplayInfo}、{@code getSystemSummary}。
 * </p>
 * <p>
 * 实现 {@link ModulePlugin}，由 {@code ModuleRuntime} 反射加载并调用 {@link #invoke}。
 * </p>
 */
public class DeviceInfoPlugin implements ModulePlugin {

    /** 读取 {@code /proc/cpuinfo} 时正文最大字符数，防止超大文件占用内存 */
    private static final int CPUINFO_MAX_CHARS = 65536;

    /**
     * 根据 {@code action} 收集对应信息并返回标准 JSON 包装（含可选 {@code _displayText}）。
     *
     * @param context    用于获取 {@link ActivityManager}、{@link WindowManager} 等系统服务
     * @param action     操作名
     * @param paramsJson 当前各 action 基本忽略具体字段，非空时仅在未知 action 错误信息中提示
     * @return JSON 字符串：成功含 {@code success}、{@code output}；失败含 {@code error}
     * @throws Exception JSON 构建异常
     */
    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        JSONObject params = new JSONObject(emptyJson(paramsJson));
        switch (action) {
            case "getDeviceInfo": {
                String output = getDeviceInfo();
                return ok(output, formatDeviceInfoDisplay(new JSONObject(output)));
            }
            case "getCpuInfo": {
                String output = getCpuInfo();
                return ok(output, formatCpuInfoDisplay(new JSONObject(output)));
            }
            case "getMemoryInfo": {
                String output = getMemoryInfo(context);
                return ok(output, formatMemoryInfoDisplay(new JSONObject(output)));
            }
            case "getStorageInfo": {
                String output = getStorageInfo();
                return ok(output, formatStorageInfoDisplay(new JSONObject(output)));
            }
            case "getDisplayInfo": {
                String output = getDisplayInfo(context);
                return ok(output, formatDisplayInfoDisplay(new JSONObject(output)));
            }
            case "getSystemSummary": {
                String output = getSystemSummary(context);
                return ok(output, formatSystemSummaryDisplay(new JSONObject(output)));
            }
            default:
                return error("Unsupported action: " + action
                        + (params.length() > 0 ? " (unexpected params ignored)" : ""));
        }
    }

    /**
     * 采集 {@link Build} 中的品牌、型号、制造商、系统版本、SDK、指纹等静态信息。
     *
     * @return 上述字段的 JSON 字符串
     * @throws Exception JSON 异常
     */
    private String getDeviceInfo() throws Exception {
        JSONObject o = new JSONObject();
        o.put("brand", Build.BRAND);
        o.put("model", Build.MODEL);
        o.put("manufacturer", Build.MANUFACTURER);
        o.put("androidVersion", Build.VERSION.RELEASE);
        o.put("sdkInt", Build.VERSION.SDK_INT);
        o.put("buildNumber", Build.DISPLAY);
        o.put("buildIncremental", Build.VERSION.INCREMENTAL);
        o.put("fingerprint", Build.FINGERPRINT);
        return o.toString();
    }

    /**
     * 读取逻辑 CPU 数、{@code os.arch}、{@link Build#SUPPORTED_ABIS}，并尝试读取 {@code /proc/cpuinfo}（截断）。
     *
     * @return JSON；若读文件失败则含 {@code cpuinfoError}，仍返回已收集字段
     * @throws Exception JSON 异常
     */
    private String getCpuInfo() throws Exception {
        JSONObject o = new JSONObject();
        o.put("processorCount", Runtime.getRuntime().availableProcessors());
        String osArch = System.getProperty("os.arch");
        o.put("osArch", osArch != null ? osArch : "");
        o.put("supportedAbis", new JSONArray(Build.SUPPORTED_ABIS));

        StringBuilder raw = new StringBuilder();
        int processorLines = 0;
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader("/proc/cpuinfo"));
            String line;
            while ((line = reader.readLine()) != null) {
                // 统计 "processor" 行数，便于与逻辑核数对照（部分机型 cpuinfo 更细）
                if (line.startsWith("processor")) {
                    processorLines++;
                }
                if (raw.length() < CPUINFO_MAX_CHARS) {
                    int room = CPUINFO_MAX_CHARS - raw.length();
                    if (line.length() + 1 <= room) {
                        raw.append(line).append('\n');
                    } else if (room > 0) {
                        raw.append(line, 0, room);
                        raw.append("\n... (truncated)");
                        break;
                    }
                }
            }
        } catch (IOException e) {
            o.put("cpuinfoError", e.getMessage() != null ? e.getMessage() : "read failed");
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                }
            }
        }
        o.put("processorEntriesInCpuinfo", processorLines);
        o.put("cpuinfo", raw.toString());
        return o.toString();
    }

    /**
     * 通过 {@link ActivityManager#getMemoryInfo} 获取总内存、可用、已用比例及低内存标志等。
     *
     * @param context 用于获取 {@link ActivityManager}
     * @return JSON；服务不可用时 {@code error} 字段说明原因
     * @throws Exception JSON 异常
     */
    private String getMemoryInfo(Context context) throws Exception {
        JSONObject o = new JSONObject();
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) {
            o.put("error", "ACTIVITY_SERVICE unavailable");
            return o.toString();
        }
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        long total = mi.totalMem;
        long avail = mi.availMem;
        long used = total - avail;
        o.put("totalBytes", total);
        o.put("availableBytes", avail);
        o.put("usedBytes", used);
        // 已用内存占总内存的百分比（保留两位小数）
        double usedPct = total > 0 ? Math.round(used * 10000.0 / total) / 100.0 : 0.0;
        o.put("usedPercent", usedPct);
        o.put("lowMemory", mi.lowMemory);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            o.put("thresholdBytes", mi.threshold);
        }
        return o.toString();
    }

    /**
     * 分别统计内部数据分区（{@link Environment#getDataDirectory}）与主外部存储的总量、可用量及占用比例。
     *
     * @return JSON，含 {@code internal}、{@code externalPrimary} 子对象；路径不存在时为 {@code null}
     * @throws Exception JSON 或 StatFs 异常
     */
    private String getStorageInfo() throws Exception {
        JSONObject root = new JSONObject();
        JSONObject internal = statFsToJson(Environment.getDataDirectory());
        root.put("internal", internal != null ? internal : JSONObject.NULL);

        File ext = Environment.getExternalStorageDirectory();
        JSONObject external = statFsToJson(ext);
        if (external != null) {
            external.put("state", Environment.getExternalStorageState());
        }
        root.put("externalPrimary", external != null ? external : JSONObject.NULL);
        return root.toString();
    }

    /**
     * 获取默认屏幕逻辑分辨率、密度、DPI 及刷新率；在 API 17+ 使用 {@link Display#getRealSize} 得到含导航栏的物理像素。
     *
     * @param context 用于 {@link WindowManager}
     * @return JSON；服务不可用时含 {@code error}
     * @throws Exception JSON 异常
     */
    private String getDisplayInfo(Context context) throws Exception {
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) {
            return new JSONObject().put("error", "WINDOW_SERVICE unavailable").toString();
        }
        Display display = wm.getDefaultDisplay();

        DisplayMetrics dm = new DisplayMetrics();
        display.getMetrics(dm);

        int width = dm.widthPixels;
        int height = dm.heightPixels;
        // 真机物理像素（含系统栏），避免仅用 getMetrics 在全面屏上偏小
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            Point real = new Point();
            display.getRealSize(real);
            width = real.x;
            height = real.y;
        }

        JSONObject o = new JSONObject();
        o.put("widthPx", width);
        o.put("heightPx", height);
        o.put("density", dm.density);
        o.put("densityDpi", dm.densityDpi);

        float refreshHz;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            refreshHz = display.getRefreshRate();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            refreshHz = display.getMode().getRefreshRate();
        } else {
            //noinspection deprecation
            refreshHz = display.getRefreshRate();
        }
        o.put("refreshRateHz", refreshHz);
        return o.toString();
    }

    /**
     * 聚合 {@link #getDeviceInfo}、{@link #getCpuInfo}、{@link #getMemoryInfo}、{@link #getStorageInfo}、{@link #getDisplayInfo} 为一颗 JSON 树。
     *
     * @param context Android 上下文
     * @return 含 device/cpu/memory/storage/display 五段嵌套的 JSON 字符串
     * @throws Exception 子调用或 JSON 异常
     */
    private String getSystemSummary(Context context) throws Exception {
        JSONObject sum = new JSONObject();
        sum.put("device", new JSONObject(getDeviceInfo()));
        sum.put("cpu", new JSONObject(getCpuInfo()));
        sum.put("memory", new JSONObject(getMemoryInfo(context)));
        sum.put("storage", new JSONObject(getStorageInfo()));
        sum.put("display", new JSONObject(getDisplayInfo(context)));
        return sum.toString();
    }

    /**
     * 对给定路径执行 {@link StatFs} 统计，输出总量、可用、已用及百分比。
     *
     * @param path 分区挂载路径下的目录（通常为根或外置根）
     * @return JSON 对象；路径 null 或不存在返回 null
     * @throws Exception StatFs/JSON 异常
     */
    private static JSONObject statFsToJson(File path) throws Exception {
        if (path == null || !path.exists()) {
            return null;
        }
        StatFs stat = new StatFs(path.getAbsolutePath());
        long blockSize = stat.getBlockSizeLong();
        long total = stat.getBlockCountLong() * blockSize;
        long avail = stat.getAvailableBlocksLong() * blockSize;
        long used = total - avail;
        JSONObject o = new JSONObject();
        o.put("path", path.getAbsolutePath());
        o.put("totalBytes", total);
        o.put("availableBytes", avail);
        o.put("usedBytes", used);
        double usedPct = total > 0 ? Math.round(used * 10000.0 / total) / 100.0 : 0.0;
        o.put("usedPercent", usedPct);
        return o;
    }

    /**
     * @param v 原始参数字符串
     * @return 空则 {@code "{}"}
     */
    private String emptyJson(String v) {
        return v == null || v.trim().isEmpty() ? "{}" : v;
    }

    /**
     * 字节数转为 B/KB/MB/GB/TB 可读字符串（固定使用 {@link Locale#US} 格式化小数）。
     *
     * @param bytes 字节，负数按 0 处理
     * @return 人类可读大小
     */
    private static String formatBytes(long bytes) {
        if (bytes < 0) {
            bytes = 0;
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.US, "%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(Locale.US, "%.1f MB", mb);
        }
        double gb = mb / 1024.0;
        if (gb < 1024) {
            return String.format(Locale.US, "%.2f GB", gb);
        }
        return String.format(Locale.US, "%.2f TB", gb / 1024.0);
    }

    /**
     * 设备基础信息块的展示文本。
     *
     * @param d {@link #getDeviceInfo} 的输出对象
     * @return 多行字符串
     */
    private static String formatDeviceInfoDisplay(JSONObject d) {
        StringBuilder sb = new StringBuilder();
        sb.append("📱 Device Info\n\n");
        sb.append("| Item | Value |\n");
        sb.append("|---|---|\n");
        sb.append("| Model | ").append(d.optString("model", "—")).append(" |\n");
        sb.append("| Brand | ").append(d.optString("brand", "—")).append(" |\n");
        sb.append("| Manufacturer | ").append(d.optString("manufacturer", "—")).append(" |\n");
        sb.append("| Android | ").append(d.optString("androidVersion", "—")).append(" |\n");
        sb.append("| SDK | ").append(d.optInt("sdkInt", 0)).append(" |\n");
        sb.append("| Build | ").append(d.optString("buildNumber", "—")).append(" |\n");
        sb.append("| Incremental | ").append(d.optString("buildIncremental", "—")).append(" |\n");
        String fp = d.optString("fingerprint", "");
        if (fp.length() > 96) {
            fp = fp.substring(0, 93) + "...";
        }
        sb.append("| Fingerprint | ").append(fp.isEmpty() ? "—" : fp).append(" |\n");
        return sb.toString();
    }

    /**
     * CPU 信息展示：核心数、架构、ABI 列表及 cpuinfo 处理器条目数。
     *
     * @param d {@link #getCpuInfo} 输出
     * @return 展示文本
     */
    private static String formatCpuInfoDisplay(JSONObject d) {
        StringBuilder sb = new StringBuilder();
        sb.append("⚙️ CPU Info\n\n");
        sb.append("| Item | Value |\n");
        sb.append("|---|---|\n");
        sb.append("| Cores | ").append(d.optInt("processorCount", 0)).append(" |\n");
        sb.append("| Architecture | ").append(d.optString("osArch", "—")).append(" |\n");
        JSONArray abis = d.optJSONArray("supportedAbis");
        String abisStr = "—";
        if (abis != null && abis.length() > 0) {
            StringBuilder a = new StringBuilder();
            for (int i = 0; i < abis.length(); i++) {
                if (i > 0) {
                    a.append(", ");
                }
                a.append(abis.optString(i, ""));
            }
            abisStr = a.toString();
        }
        sb.append("| ABIs | ").append(abisStr).append(" |\n");
        sb.append("| /proc/cpuinfo processors | ")
                .append(d.optInt("processorEntriesInCpuinfo", 0)).append(" |\n");
        String cpuErr = d.optString("cpuinfoError", "");
        if (!cpuErr.isEmpty()) {
            sb.append("| CPU info | ").append(cpuErr).append(" |\n");
        }
        return sb.toString();
    }

    /**
     * 内存信息展示；若含 {@code error} 则仅输出错误行。
     *
     * @param d {@link #getMemoryInfo} 输出
     * @return 展示文本
     */
    private static String formatMemoryInfoDisplay(JSONObject d) {
        StringBuilder sb = new StringBuilder();
        sb.append("💾 Memory\n\n");
        sb.append("| Item | Value |\n");
        sb.append("|---|---|\n");
        if (d.has("error")) {
            sb.append("| Error | ").append(d.optString("error", "—")).append(" |\n");
            return sb.toString();
        }
        sb.append("| Total | ").append(formatBytes(d.optLong("totalBytes", 0))).append(" |\n");
        sb.append("| Available | ").append(formatBytes(d.optLong("availableBytes", 0))).append(" |\n");
        sb.append("| Used | ").append(formatBytes(d.optLong("usedBytes", 0))).append(" |\n");
        sb.append("| Used % | ")
                .append(String.format(Locale.US, "%.2f%%", d.optDouble("usedPercent", 0))).append(" |\n");
        sb.append("| Low memory | ").append(d.optBoolean("lowMemory", false) ? "Yes" : "No").append(" |\n");
        if (d.has("thresholdBytes")) {
            sb.append("| Threshold | ").append(formatBytes(d.optLong("thresholdBytes", 0))).append(" |\n");
        }
        return sb.toString();
    }

    /**
     * 存储汇总展示：内部 + 主外部卷。
     *
     * @param root {@link #getStorageInfo} 输出
     * @return 展示文本
     */
    private static String formatStorageInfoDisplay(JSONObject root) {
        JSONObject internal = root.optJSONObject("internal");
        JSONObject external = root.optJSONObject("externalPrimary");
        String inPath = internal != null ? internal.optString("path", "—") : "—";
        String exPath = external != null ? external.optString("path", "—") : "—";
        String inTotal = internal != null ? formatBytes(internal.optLong("totalBytes", 0)) : "—";
        String exTotal = external != null ? formatBytes(external.optLong("totalBytes", 0)) : "—";
        String inUsed = internal != null ? formatBytes(internal.optLong("usedBytes", 0)) : "—";
        String exUsed = external != null ? formatBytes(external.optLong("usedBytes", 0)) : "—";
        String inAvail = internal != null ? formatBytes(internal.optLong("availableBytes", 0)) : "—";
        String exAvail = external != null ? formatBytes(external.optLong("availableBytes", 0)) : "—";
        String inPct = internal != null
                ? String.format(Locale.US, "%.2f%%", internal.optDouble("usedPercent", 0)) : "—";
        String exPct = external != null
                ? String.format(Locale.US, "%.2f%%", external.optDouble("usedPercent", 0)) : "—";
        String exState = "";
        if (external != null) {
            exState = external.optString("state", "");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("💿 Storage\n\n");
        sb.append("| Metric | Internal | External |\n");
        sb.append("|---|---|---|\n");
        sb.append("| Path | ").append(inPath).append(" | ").append(exPath).append(" |\n");
        sb.append("| Total | ").append(inTotal).append(" | ").append(exTotal).append(" |\n");
        sb.append("| Used | ").append(inUsed).append(" | ").append(exUsed).append(" |\n");
        sb.append("| Available | ").append(inAvail).append(" | ").append(exAvail).append(" |\n");
        sb.append("| Used % | ").append(inPct).append(" | ").append(exPct).append(" |\n");
        sb.append("| State | — | ").append(exState.isEmpty() ? "—" : exState).append(" |\n");
        return sb.toString();
    }

    /**
     * 屏幕分辨率、密度与刷新率展示。
     *
     * @param d {@link #getDisplayInfo} 输出
     * @return 展示文本
     */
    private static String formatDisplayInfoDisplay(JSONObject d) {
        StringBuilder sb = new StringBuilder();
        sb.append("🖥️ Display\n\n");
        sb.append("| Item | Value |\n");
        sb.append("|---|---|\n");
        if (d.has("error")) {
            sb.append("| Error | ").append(d.optString("error", "—")).append(" |\n");
            return sb.toString();
        }
        int w = d.optInt("widthPx", 0);
        int h = d.optInt("heightPx", 0);
        sb.append("| Resolution | ").append(w).append(" × ").append(h).append(" |\n");
        sb.append("| Density | ")
                .append(String.format(Locale.US, "%.2f", d.optDouble("density", 0))).append(" |\n");
        sb.append("| Density DPI | ").append(d.optInt("densityDpi", 0)).append(" |\n");
        sb.append("| Refresh rate | ")
                .append(String.format(Locale.US, "%.1f Hz", d.optDouble("refreshRateHz", 0))).append(" |\n");
        return sb.toString();
    }

    /**
     * 系统摘要：从聚合 JSON 中抽取最关键一行级信息便于聊天展示。
     *
     * @param sum {@link #getSystemSummary} 输出
     * @return 展示文本
     */
    private static String formatSystemSummaryDisplay(JSONObject sum) {
        StringBuilder sb = new StringBuilder();
        sb.append("📋 System Summary\n\n");
        JSONObject device = sum.optJSONObject("device");
        if (device != null) {
            sb.append("Device\n\n");
            sb.append("| Item | Value |\n");
            sb.append("|---|---|\n");
            String name = (device.optString("brand", "") + " " + device.optString("model", "")).trim();
            sb.append("| Device | ").append(name.isEmpty() ? "—" : name).append(" |\n");
            sb.append("| Android | ")
                    .append(device.optString("androidVersion", "—"))
                    .append(" (API ").append(device.optInt("sdkInt", 0)).append(") |\n");
            sb.append("\n");
        }
        JSONObject cpu = sum.optJSONObject("cpu");
        if (cpu != null) {
            sb.append("CPU\n\n");
            sb.append("| Item | Value |\n");
            sb.append("|---|---|\n");
            sb.append("| CPU cores | ").append(cpu.optInt("processorCount", 0)).append(" |\n");
            sb.append("| CPU arch | ").append(cpu.optString("osArch", "—")).append(" |\n");
            sb.append("\n");
        }
        JSONObject memory = sum.optJSONObject("memory");
        if (memory != null) {
            sb.append("Memory\n\n");
            sb.append("| Item | Value |\n");
            sb.append("|---|---|\n");
            if (memory.has("error")) {
                sb.append("| Memory | ").append(memory.optString("error", "—")).append(" |\n");
            } else {
                sb.append("| RAM total | ").append(formatBytes(memory.optLong("totalBytes", 0))).append(" |\n");
                sb.append("| RAM used | ")
                        .append(String.format(Locale.US, "%.2f%%", memory.optDouble("usedPercent", 0)))
                        .append(" |\n");
            }
            sb.append("\n");
        }
        JSONObject storage = sum.optJSONObject("storage");
        if (storage != null) {
            JSONObject internal = storage.optJSONObject("internal");
            if (internal != null) {
                sb.append("Storage (internal)\n\n");
                sb.append("| Item | Value |\n");
                sb.append("|---|---|\n");
                sb.append("| Used / Total | ")
                        .append(formatBytes(internal.optLong("usedBytes", 0)))
                        .append(" / ")
                        .append(formatBytes(internal.optLong("totalBytes", 0)))
                        .append(" |\n");
                sb.append("| Used % | ")
                        .append(String.format(Locale.US, "%.1f%%", internal.optDouble("usedPercent", 0)))
                        .append(" |\n");
                sb.append("\n");
            }
        }
        JSONObject display = sum.optJSONObject("display");
        if (display != null) {
            sb.append("Display\n\n");
            sb.append("| Item | Value |\n");
            sb.append("|---|---|\n");
            if (display.has("error")) {
                sb.append("| Display | ").append(display.optString("error", "—")).append(" |\n");
            } else {
                sb.append("| Resolution @ Hz | ")
                        .append(display.optInt("widthPx", 0)).append(" × ")
                        .append(display.optInt("heightPx", 0)).append(" @ ")
                        .append(String.format(Locale.US, "%.0f Hz", display.optDouble("refreshRateHz", 0)))
                        .append(" |\n");
            }
        }
        return sb.toString();
    }

    /**
     * @param output 业务 JSON 字符串
     * @return 成功包装，无展示文案
     * @throws Exception JSON 异常
     */
    private String ok(String output) throws Exception {
        return ok(output, null);
    }

    /**
     * @param output      写入 {@code output} 字段的字符串
     * @param displayText 非 null 时写入 {@code _displayText}
     * @return 完整响应 JSON
     * @throws Exception JSON 异常
     */
    private String ok(String output, String displayText) throws Exception {
        JSONObject j = new JSONObject();
        j.put("success", true);
        j.put("output", output);
        if (displayText != null) {
            j.put("_displayText", displayText);
        }
        return j.toString();
    }

    /**
     * @param msg 错误信息
     * @return {@code success=false} 的 JSON
     * @throws Exception JSON 异常
     */
    private String error(String msg) throws Exception {
        return new JSONObject().put("success", false).put("error", msg).toString();
    }
}
