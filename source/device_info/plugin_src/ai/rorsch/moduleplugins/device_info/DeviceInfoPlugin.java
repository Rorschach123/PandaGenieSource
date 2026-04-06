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

public class DeviceInfoPlugin implements ModulePlugin {

    private static final int CPUINFO_MAX_CHARS = 65536;

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
        double usedPct = total > 0 ? Math.round(used * 10000.0 / total) / 100.0 : 0.0;
        o.put("usedPercent", usedPct);
        o.put("lowMemory", mi.lowMemory);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            o.put("thresholdBytes", mi.threshold);
        }
        return o.toString();
    }

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

    private String getSystemSummary(Context context) throws Exception {
        JSONObject sum = new JSONObject();
        sum.put("device", new JSONObject(getDeviceInfo()));
        sum.put("cpu", new JSONObject(getCpuInfo()));
        sum.put("memory", new JSONObject(getMemoryInfo(context)));
        sum.put("storage", new JSONObject(getStorageInfo()));
        sum.put("display", new JSONObject(getDisplayInfo(context)));
        return sum.toString();
    }

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

    private String emptyJson(String v) {
        return v == null || v.trim().isEmpty() ? "{}" : v;
    }

    private static void appendDisplayLine(StringBuilder sb, String label, String value) {
        sb.append("▸ ").append(label).append(": ").append(value).append('\n');
    }

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

    private static String formatDeviceInfoDisplay(JSONObject d) {
        StringBuilder sb = new StringBuilder();
        sb.append("📱 Device Info\n");
        sb.append("━━━━━━━━━━━━━━\n");
        appendDisplayLine(sb, "Model", d.optString("model", "—"));
        appendDisplayLine(sb, "Brand", d.optString("brand", "—"));
        appendDisplayLine(sb, "Manufacturer", d.optString("manufacturer", "—"));
        appendDisplayLine(sb, "Android", d.optString("androidVersion", "—"));
        appendDisplayLine(sb, "SDK", String.valueOf(d.optInt("sdkInt", 0)));
        appendDisplayLine(sb, "Build", d.optString("buildNumber", "—"));
        appendDisplayLine(sb, "Incremental", d.optString("buildIncremental", "—"));
        String fp = d.optString("fingerprint", "");
        if (fp.length() > 96) {
            fp = fp.substring(0, 93) + "...";
        }
        appendDisplayLine(sb, "Fingerprint", fp.isEmpty() ? "—" : fp);
        return sb.toString();
    }

    private static String formatCpuInfoDisplay(JSONObject d) {
        StringBuilder sb = new StringBuilder();
        sb.append("⚙️ CPU Info\n");
        sb.append("━━━━━━━━━━━━━━\n");
        appendDisplayLine(sb, "Cores", String.valueOf(d.optInt("processorCount", 0)));
        appendDisplayLine(sb, "Architecture", d.optString("osArch", "—"));
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
        appendDisplayLine(sb, "ABIs", abisStr);
        appendDisplayLine(sb, "/proc/cpuinfo processors",
                String.valueOf(d.optInt("processorEntriesInCpuinfo", 0)));
        String cpuErr = d.optString("cpuinfoError", "");
        if (!cpuErr.isEmpty()) {
            appendDisplayLine(sb, "CPU info", cpuErr);
        }
        return sb.toString();
    }

    private static String formatMemoryInfoDisplay(JSONObject d) {
        StringBuilder sb = new StringBuilder();
        sb.append("💾 Memory\n");
        sb.append("━━━━━━━━━━━━━━\n");
        if (d.has("error")) {
            appendDisplayLine(sb, "Error", d.optString("error", "—"));
            return sb.toString();
        }
        appendDisplayLine(sb, "Total", formatBytes(d.optLong("totalBytes", 0)));
        appendDisplayLine(sb, "Available", formatBytes(d.optLong("availableBytes", 0)));
        appendDisplayLine(sb, "Used", formatBytes(d.optLong("usedBytes", 0)));
        appendDisplayLine(sb, "Used %", String.format(Locale.US, "%.2f%%", d.optDouble("usedPercent", 0)));
        appendDisplayLine(sb, "Low memory", d.optBoolean("lowMemory", false) ? "Yes" : "No");
        if (d.has("thresholdBytes")) {
            appendDisplayLine(sb, "Threshold", formatBytes(d.optLong("thresholdBytes", 0)));
        }
        return sb.toString();
    }

    private static void appendStorageVolume(StringBuilder sb, String title, JSONObject vol) {
        if (vol == null) {
            appendDisplayLine(sb, title, "—");
            return;
        }
        appendDisplayLine(sb, title + " path", vol.optString("path", "—"));
        appendDisplayLine(sb, title + " total", formatBytes(vol.optLong("totalBytes", 0)));
        appendDisplayLine(sb, title + " used", formatBytes(vol.optLong("usedBytes", 0)));
        appendDisplayLine(sb, title + " available", formatBytes(vol.optLong("availableBytes", 0)));
        appendDisplayLine(sb, title + " used %",
                String.format(Locale.US, "%.2f%%", vol.optDouble("usedPercent", 0)));
        String state = vol.optString("state", "");
        if (!state.isEmpty()) {
            appendDisplayLine(sb, title + " state", state);
        }
    }

    private static String formatStorageInfoDisplay(JSONObject root) {
        StringBuilder sb = new StringBuilder();
        sb.append("💿 Storage\n");
        sb.append("━━━━━━━━━━━━━━\n");
        appendStorageVolume(sb, "Internal", root.optJSONObject("internal"));
        appendStorageVolume(sb, "External", root.optJSONObject("externalPrimary"));
        return sb.toString();
    }

    private static String formatDisplayInfoDisplay(JSONObject d) {
        StringBuilder sb = new StringBuilder();
        sb.append("🖥️ Display\n");
        sb.append("━━━━━━━━━━━━━━\n");
        if (d.has("error")) {
            appendDisplayLine(sb, "Error", d.optString("error", "—"));
            return sb.toString();
        }
        int w = d.optInt("widthPx", 0);
        int h = d.optInt("heightPx", 0);
        appendDisplayLine(sb, "Resolution", w + " × " + h);
        appendDisplayLine(sb, "Density", String.format(Locale.US, "%.2f", d.optDouble("density", 0)));
        appendDisplayLine(sb, "Density DPI", String.valueOf(d.optInt("densityDpi", 0)));
        appendDisplayLine(sb, "Refresh rate",
                String.format(Locale.US, "%.1f Hz", d.optDouble("refreshRateHz", 0)));
        return sb.toString();
    }

    private static String formatSystemSummaryDisplay(JSONObject sum) {
        StringBuilder sb = new StringBuilder();
        sb.append("📋 System Summary\n");
        sb.append("━━━━━━━━━━━━━━\n");
        JSONObject device = sum.optJSONObject("device");
        if (device != null) {
            String name = (device.optString("brand", "") + " " + device.optString("model", "")).trim();
            appendDisplayLine(sb, "Device", name.isEmpty() ? "—" : name);
            appendDisplayLine(sb, "Android",
                    device.optString("androidVersion", "—") + " (API " + device.optInt("sdkInt", 0) + ")");
        }
        JSONObject cpu = sum.optJSONObject("cpu");
        if (cpu != null) {
            appendDisplayLine(sb, "CPU cores", String.valueOf(cpu.optInt("processorCount", 0)));
            appendDisplayLine(sb, "CPU arch", cpu.optString("osArch", "—"));
        }
        JSONObject memory = sum.optJSONObject("memory");
        if (memory != null) {
            if (memory.has("error")) {
                appendDisplayLine(sb, "Memory", memory.optString("error", "—"));
            } else {
                appendDisplayLine(sb, "RAM total", formatBytes(memory.optLong("totalBytes", 0)));
                appendDisplayLine(sb, "RAM used",
                        String.format(Locale.US, "%.2f%%", memory.optDouble("usedPercent", 0)));
            }
        }
        JSONObject storage = sum.optJSONObject("storage");
        if (storage != null) {
            JSONObject internal = storage.optJSONObject("internal");
            if (internal != null) {
                appendDisplayLine(sb, "Storage (internal)",
                        formatBytes(internal.optLong("usedBytes", 0))
                                + " / " + formatBytes(internal.optLong("totalBytes", 0))
                                + " (" + String.format(Locale.US, "%.1f%%",
                                internal.optDouble("usedPercent", 0)) + " used)");
            }
        }
        JSONObject display = sum.optJSONObject("display");
        if (display != null) {
            if (display.has("error")) {
                appendDisplayLine(sb, "Display", display.optString("error", "—"));
            } else {
                appendDisplayLine(sb, "Display",
                        display.optInt("widthPx", 0) + " × " + display.optInt("heightPx", 0)
                                + " @ " + String.format(Locale.US, "%.0f Hz",
                                display.optDouble("refreshRateHz", 0)));
            }
        }
        return sb.toString();
    }

    private String ok(String output) throws Exception {
        return ok(output, null);
    }

    private String ok(String output, String displayText) throws Exception {
        JSONObject j = new JSONObject();
        j.put("success", true);
        j.put("output", output);
        if (displayText != null) {
            j.put("_displayText", displayText);
        }
        return j.toString();
    }

    private String error(String msg) throws Exception {
        return new JSONObject().put("success", false).put("error", msg).toString();
    }
}
