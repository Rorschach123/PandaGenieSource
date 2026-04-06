package ai.rorsch.moduleplugins.app_manager;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import ai.rorsch.pandagenie.module.runtime.ModulePlugin;
import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class AppManagerPlugin implements ModulePlugin {

    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        JSONObject params = new JSONObject(emptyJson(paramsJson));
        switch (action) {
            case "listApps": {
                String out = listApps(context, params);
                return ok(out, formatListAppsDisplay(new JSONObject(out)));
            }
            case "openApp":
                return openApp(context, params);
            case "getAppInfo": {
                String out = getAppInfo(context, params);
                JSONObject probe = new JSONObject(out);
                if (probe.has("success") && !probe.optBoolean("success", true)) return out;
                return ok(out, formatGetAppInfoDisplay(probe));
            }
            case "uninstallApp":
                return uninstallApp(context, params);
            case "openAppSettings":
                return openAppSettings(context, params);
            default:
                return error("Unsupported action: " + action);
        }
    }

    private String listApps(Context context, JSONObject params) throws Exception {
        boolean includeSystem = params.optBoolean("includeSystem", false);
        PackageManager pm = context.getPackageManager();
        List<PackageInfo> packages = pm.getInstalledPackages(0);
        List<JSONObject> apps = new ArrayList<>();

        for (PackageInfo pi : packages) {
            if (!includeSystem && isSystemApp(pi)) continue;
            JSONObject app = new JSONObject();
            app.put("packageName", pi.packageName);
            app.put("appName", pm.getApplicationLabel(pi.applicationInfo).toString());
            app.put("versionName", pi.versionName != null ? pi.versionName : "");
            apps.add(app);
        }

        Collections.sort(apps, (a, b) -> {
            try {
                return a.getString("appName").compareToIgnoreCase(b.getString("appName"));
            } catch (Exception e) { return 0; }
        });

        JSONObject result = new JSONObject();
        JSONArray arr = new JSONArray();
        for (JSONObject app : apps) arr.put(app);
        result.put("count", apps.size());
        result.put("apps", arr);
        return result.toString();
    }

    private String openApp(Context context, JSONObject params) throws Exception {
        String nameOrPkg = params.optString("nameOrPackage", "").trim();
        if (nameOrPkg.isEmpty()) return error("Missing parameter: nameOrPackage");

        String packageName = resolvePackageName(context, nameOrPkg);
        if (packageName == null) return error("App not found: " + nameOrPkg);

        PackageManager pm = context.getPackageManager();
        Intent intent = pm.getLaunchIntentForPackage(packageName);
        if (intent == null) return error("Cannot launch app (no launch activity): " + packageName);

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);

        JSONObject result = new JSONObject();
        result.put("launched", true);
        result.put("packageName", packageName);
        String appName = pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString();
        result.put("appName", appName);
        return ok(result.toString(), "✅ Opening " + appName);
    }

    private String getAppInfo(Context context, JSONObject params) throws Exception {
        String nameOrPkg = params.optString("nameOrPackage", "").trim();
        if (nameOrPkg.isEmpty()) return error("Missing parameter: nameOrPackage");

        String packageName = resolvePackageName(context, nameOrPkg);
        if (packageName == null) return error("App not found: " + nameOrPkg);

        PackageManager pm = context.getPackageManager();
        PackageInfo pi = pm.getPackageInfo(packageName, 0);
        ApplicationInfo ai = pi.applicationInfo;
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

        JSONObject info = new JSONObject();
        info.put("packageName", pi.packageName);
        info.put("appName", pm.getApplicationLabel(ai).toString());
        info.put("versionName", pi.versionName != null ? pi.versionName : "");
        info.put("versionCode", pi.versionCode);
        info.put("firstInstallTime", sdf.format(new Date(pi.firstInstallTime)));
        info.put("lastUpdateTime", sdf.format(new Date(pi.lastUpdateTime)));
        info.put("targetSdkVersion", ai.targetSdkVersion);
        info.put("minSdkVersion", ai.minSdkVersion);
        info.put("isSystemApp", isSystemApp(pi));
        info.put("enabled", ai.enabled);
        info.put("dataDir", ai.dataDir != null ? ai.dataDir : "");
        info.put("sourceDir", ai.sourceDir != null ? ai.sourceDir : "");

        String installer = "";
        try {
            installer = pm.getInstallerPackageName(packageName);
        } catch (Exception ignored) {}
        info.put("installerPackage", installer != null ? installer : "unknown");

        String installerLabel = resolveInstallerLabel(installer);
        info.put("installerLabel", installerLabel);

        return info.toString();
    }

    private String uninstallApp(Context context, JSONObject params) throws Exception {
        String packageName = params.optString("packageName", "").trim();
        if (packageName.isEmpty()) {
            String nameOrPkg = params.optString("nameOrPackage", "").trim();
            if (!nameOrPkg.isEmpty()) packageName = resolvePackageName(context, nameOrPkg);
        }
        if (packageName == null || packageName.isEmpty()) return error("Missing parameter: packageName");

        Intent intent = new Intent(Intent.ACTION_DELETE);
        intent.setData(Uri.parse("package:" + packageName));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);

        PackageManager pm = context.getPackageManager();
        JSONObject result = new JSONObject();
        result.put("requestSent", true);
        result.put("packageName", packageName);
        result.put("message", "Uninstall confirmation dialog has been shown to the user");
        String appLabel = packageName;
        try {
            appLabel = pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString();
        } catch (PackageManager.NameNotFoundException ignored) {}
        return ok(result.toString(), "🗑️ Uninstall requested: " + appLabel);
    }

    private String openAppSettings(Context context, JSONObject params) throws Exception {
        String nameOrPkg = params.optString("nameOrPackage", "").trim();
        if (nameOrPkg.isEmpty()) return error("Missing parameter: nameOrPackage");

        String packageName = resolvePackageName(context, nameOrPkg);
        if (packageName == null) return error("App not found: " + nameOrPkg);

        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + packageName));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);

        PackageManager pm = context.getPackageManager();
        JSONObject result = new JSONObject();
        result.put("opened", true);
        result.put("packageName", packageName);
        String appLabel = pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString();
        return ok(result.toString(), "⚙️ Opening settings for " + appLabel);
    }

    /**
     * Resolve user input (app name or package name) to an actual package name.
     * First tries exact package name match, then searches by app label (case-insensitive).
     */
    private String resolvePackageName(Context context, String nameOrPkg) {
        PackageManager pm = context.getPackageManager();
        try {
            pm.getPackageInfo(nameOrPkg, 0);
            return nameOrPkg;
        } catch (PackageManager.NameNotFoundException ignored) {}

        String lowerInput = nameOrPkg.toLowerCase(Locale.getDefault());
        List<PackageInfo> packages = pm.getInstalledPackages(0);
        String bestMatch = null;
        for (PackageInfo pi : packages) {
            String label = pm.getApplicationLabel(pi.applicationInfo).toString();
            if (label.equalsIgnoreCase(nameOrPkg)) return pi.packageName;
            if (label.toLowerCase(Locale.getDefault()).contains(lowerInput) && bestMatch == null) {
                bestMatch = pi.packageName;
            }
        }
        return bestMatch;
    }

    private boolean isSystemApp(PackageInfo pi) {
        return pi.applicationInfo != null && (pi.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
    }

    private String resolveInstallerLabel(String installerPkg) {
        if (installerPkg == null || installerPkg.isEmpty()) return "Unknown";
        switch (installerPkg) {
            case "com.android.vending": return "Google Play";
            case "com.huawei.appmarket": return "Huawei AppGallery";
            case "com.xiaomi.market": return "Xiaomi Market";
            case "com.oppo.market": return "OPPO Market";
            case "com.heytap.market": return "OPPO/OnePlus Market";
            case "com.bbk.appstore": return "Vivo App Store";
            case "com.tencent.android.qqdownloader": return "Tencent MyApp";
            case "com.baidu.appsearch": return "Baidu App Store";
            case "com.wandoujia.phoenix2": return "Wandoujia";
            case "com.sec.android.app.samsungapps": return "Samsung Galaxy Store";
            case "com.amazon.venezia": return "Amazon Appstore";
            default: return installerPkg;
        }
    }

    private String emptyJson(String v) { return v == null || v.trim().isEmpty() ? "{}" : v; }

    private String formatListAppsDisplay(JSONObject obj) throws Exception {
        int count = obj.optInt("count", 0);
        JSONArray apps = obj.optJSONArray("apps");
        StringBuilder sb = new StringBuilder();
        sb.append("📱 Installed Apps (").append(count).append(" total)\n━━━━━━━━━━━━━━\n");
        if (apps != null) {
            for (int i = 0; i < apps.length(); i++) {
                JSONObject app = apps.getJSONObject(i);
                String name = app.optString("appName", "");
                String pkg = app.optString("packageName", "");
                sb.append(i + 1).append(". ").append(name).append(" (").append(pkg).append(")\n");
            }
        }
        String s = sb.toString();
        return s.endsWith("\n") ? s.substring(0, s.length() - 1) : s;
    }

    private String formatGetAppInfoDisplay(JSONObject info) {
        String name = info.optString("appName", "");
        String pkg = info.optString("packageName", "");
        String ver = info.optString("versionName", "");
        return "📱 App Info\n━━━━━━━━━━━━━━\n▸ Name: " + name + "\n▸ Package: " + pkg + "\n▸ Version: " + ver;
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
