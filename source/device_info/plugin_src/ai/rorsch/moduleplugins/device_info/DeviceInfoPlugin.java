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

public class DeviceInfoPlugin implements ModulePlugin {

    private static final int CPUINFO_MAX_CHARS = 65536;

    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        JSONObject params = new JSONObject(emptyJson(paramsJson));
        switch (action) {
            case "getDeviceInfo":
                return ok(getDeviceInfo());
            case "getCpuInfo":
                return ok(getCpuInfo());
            case "getMemoryInfo":
                return ok(getMemoryInfo(context));
            case "getStorageInfo":
                return ok(getStorageInfo());
            case "getDisplayInfo":
                return ok(getDisplayInfo(context));
            case "getSystemSummary":
                return ok(getSystemSummary(context));
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

    private String ok(String output) throws Exception {
        return new JSONObject().put("success", true).put("output", output).toString();
    }

    private String error(String msg) throws Exception {
        return new JSONObject().put("success", false).put("error", msg).toString();
    }
}
