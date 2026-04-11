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

/**
 * 已安装应用管理模块插件：列举应用、启动应用、查询详情、发起卸载与打开应用设置页。
 * <p>
 * <b>模块用途：</b>基于 {@link PackageManager} 与 {@link Intent} 提供常见应用管理操作，供 Agent/任务在授权环境下调用。
 * </p>
 * <p>
 * <b>提供的 API（{@code action}）：</b>
 * {@code listApps}（参数可含 {@code includeSystem}）、{@code openApp}（{@code nameOrPackage}）、
 * {@code getAppInfo}、{@code uninstallApp}（{@code packageName} 或 {@code nameOrPackage}）、
 * {@code openAppSettings}。
 * </p>
 * <p>
 * <b>加载方式：</b>由 {@code ModuleRuntime} 通过反射实例化并实现 {@link ModulePlugin} 后调用 {@link #invoke}。
 * </p>
 */
public class AppManagerPlugin implements ModulePlugin {

    private static boolean isZh() {
        try {
            return java.util.Locale.getDefault().getLanguage().toLowerCase(java.util.Locale.ROOT).startsWith("zh");
        } catch (Exception e) {
            return false;
        }
    }

    private static String pgTable(String title, String[] headers, java.util.List<String[]> rows) {
        try {
            org.json.JSONObject t = new org.json.JSONObject();
            t.put("title", title);
            org.json.JSONArray h = new org.json.JSONArray();
            for (String hdr : headers) h.put(hdr);
            t.put("headers", h);
            org.json.JSONArray r = new org.json.JSONArray();
            for (String[] row : rows) {
                org.json.JSONArray rowArr = new org.json.JSONArray();
                for (String cell : row) rowArr.put(cell);
                r.put(rowArr);
            }
            t.put("rows", r);
            return "__pg_table__" + t.toString() + "__pg_table_end__";
        } catch (Exception e) {
            return title;
        }
    }

    /**
     * 根据 {@code action} 分发到列举、启动、查询、卸载或设置页等逻辑。
     *
     * @param context    Android 上下文，用于 {@link PackageManager} 与启动 Activity
     * @param action     操作名称，见类说明
     * @param paramsJson JSON 参数；空则按 {@code {}} 解析
     * @return 各操作约定的 JSON 字符串；多数字段包含 {@code success}，部分内含 {@code output}、{@code _displayText}
     * @throws Exception JSON 或系统服务异常
     */
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

    /**
     * 列出设备上已安装的应用基本信息（包名、显示名、版本名）。
     *
     * @param context 用于获取 {@link PackageManager}
     * @param params  {@code includeSystem} 为 true 时包含系统应用，默认 false
     * @return 纯数据 JSON 字符串（含 {@code count} 与 {@code apps} 数组），尚未包装外层 {@code success}
     * @throws Exception JSON 构造异常
     */
    private String listApps(Context context, JSONObject params) throws Exception {
        boolean includeSystem = params.optBoolean("includeSystem", false);
        PackageManager pm = context.getPackageManager();
        List<PackageInfo> packages = pm.getInstalledPackages(0);
        List<JSONObject> apps = new ArrayList<>();

        for (PackageInfo pi : packages) {
            // 默认跳过系统应用，除非调用方显式要求包含
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

    /**
     * 根据应用名或包名解析目标包并启动其启动 Activity（LAUNCHER）。
     *
     * @param context 用于启动 Activity，需 {@code FLAG_ACTIVITY_NEW_TASK}
     * @param params    必须提供 {@code nameOrPackage}（显示名或完整包名）
     * @return 成功时包装为带 {@code _displayText} 的 JSON；失败为 {@code success:false}
     * @throws Exception JSON 或启动过程异常
     */
    private String openApp(Context context, JSONObject params) throws Exception {
        String nameOrPkg = params.optString("nameOrPackage", "").trim();
        if (nameOrPkg.isEmpty()) return error("Missing parameter: nameOrPackage");

        String packageName = resolvePackageName(context, nameOrPkg);
        if (packageName == null) return error("App not found: " + nameOrPkg);

        PackageManager pm = context.getPackageManager();
        Intent intent = pm.getLaunchIntentForPackage(packageName);
        if (intent == null) return error("Cannot launch app (no launch activity): " + packageName);

        // 从非 Activity 上下文启动时必须带此标志，否则部分系统会抛异常
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);

        JSONObject result = new JSONObject();
        result.put("launched", true);
        result.put("packageName", packageName);
        String appName = pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString();
        result.put("appName", appName);
        return ok(result.toString(), "✅ Opening " + appName);
    }

    /**
     * 查询指定应用的详细元数据（版本、SDK、安装时间、安装来源、路径等）。
     *
     * @param context 用于 {@link PackageManager#getPackageInfo}
     * @param params    {@code nameOrPackage} 必填
     * @return 成功时为应用信息 JSON 字符串（由 {@link #invoke} 再包装）；解析失败时可能已是错误 JSON
     * @throws Exception 包不存在等异常
     */
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

        // 部分系统/版本上可能不可用或返回 null，需兜底
        String installer = "";
        try {
            installer = pm.getInstallerPackageName(packageName);
        } catch (Exception ignored) {}
        info.put("installerPackage", installer != null ? installer : "unknown");

        String installerLabel = resolveInstallerLabel(installer);
        info.put("installerLabel", installerLabel);

        return info.toString();
    }

    /**
     * 向系统发起卸载请求：弹出系统卸载确认界面（不会静默卸载）。
     *
     * @param context 用于启动 {@link Intent#ACTION_DELETE}
     * @param params    优先 {@code packageName}；若为空则尝试 {@code nameOrPackage} 解析
     * @return 表示已发起请求的 JSON，并附带展示文案
     * @throws Exception JSON 构造异常
     */
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

    /**
     * 打开指定应用的系统「应用信息」设置页。
     *
     * @param context 用于启动 {@link Settings#ACTION_APPLICATION_DETAILS_SETTINGS}
     * @param params    {@code nameOrPackage} 必填
     * @return 成功响应 JSON 与展示文案
     * @throws Exception 包不存在等异常
     */
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
     * 将用户输入的应用显示名或包名解析为确定的包名字符串。
     * <p>先尝试按完整包名精确匹配；失败则遍历已安装应用：优先忽略大小写完全匹配显示名，
     * 否则取第一个显示名包含输入（小写）的应用作为模糊匹配。</p>
     *
     * @param context   用于枚举已安装包
     * @param nameOrPkg 用户输入的包名或应用名
     * @return 解析到的包名；无法匹配时返回 {@code null}
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

    /**
     * 判断安装包是否带有系统应用标记。
     *
     * @param pi {@link PackageInfo}
     * @return {@code true} 表示 {@link ApplicationInfo#FLAG_SYSTEM}
     */
    private boolean isSystemApp(PackageInfo pi) {
        return pi.applicationInfo != null && (pi.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
    }

    /**
     * 将安装器包名转换为可读的应用商店/渠道名称（常见厂商与商店映射）。
     *
     * @param installerPkg {@link PackageManager#getInstallerPackageName} 返回值，可为 null
     * @return 展示用标签；未知时返回包名本身或 "Unknown"
     */
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

    /**
     * 规范化 JSON 参数字符串，空输入视为空对象。
     *
     * @param v 原始 paramsJson
     * @return 非空串原样返回，否则 "{}"
     */
    private String emptyJson(String v) { return v == null || v.trim().isEmpty() ? "{}" : v; }

    /**
     * 将 {@code listApps} 的 JSON 结果格式化为多行可读列表（用于 {@code _displayText}）。
     *
     * @param obj 含 {@code count} 与 {@code apps} 的 JSONObject
     * @return 展示文本
     * @throws Exception 数组访问异常
     */
    private static String mdCell(String s) {
        if (s == null) return "";
        return s.replace("\r", "").replace("\n", " ").replace("|", "\\|");
    }

    private String formatListAppsDisplay(JSONObject obj) throws Exception {
        int count = obj.optInt("count", 0);
        JSONArray apps = obj.optJSONArray("apps");
        boolean zh = isZh();
        String title = zh
                ? ("📱 已安装应用（共 " + count + "）")
                : ("📱 Installed Apps (" + count + " total)");
        String[] headers = zh
                ? new String[]{"名称", "包名", "版本"}
                : new String[]{"Name", "Package", "Version"};
        List<String[]> rowList = new ArrayList<>();
        if (apps != null) {
            for (int i = 0; i < apps.length(); i++) {
                JSONObject app = apps.getJSONObject(i);
                rowList.add(new String[]{
                        mdCell(app.optString("appName", "")),
                        mdCell(app.optString("packageName", "")),
                        mdCell(app.optString("versionName", ""))
                });
            }
        }
        return pgTable(title, headers, rowList);
    }

    /**
     * 从 {@code getAppInfo} 的 JSON 中提取名称、包名、版本生成简短展示块。
     *
     * @param info 应用信息 JSON
     * @return 多行展示字符串
     */
    private String formatGetAppInfoDisplay(JSONObject info) {
        boolean zh = isZh();
        String title = zh ? "📱 应用信息" : "📱 App Info";
        String[] headers = zh ? new String[]{"项目", "值"} : new String[]{"Item", "Value"};
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{zh ? "名称" : "Name", mdCell(info.optString("appName", ""))});
        rows.add(new String[]{zh ? "包名" : "Package", mdCell(info.optString("packageName", ""))});
        rows.add(new String[]{zh ? "版本" : "Version", mdCell(info.optString("versionName", ""))});
        rows.add(new String[]{zh ? "版本号" : "Version code", mdCell(String.valueOf(info.optInt("versionCode", 0)))});
        rows.add(new String[]{zh ? "首次安装" : "First install", mdCell(info.optString("firstInstallTime", ""))});
        rows.add(new String[]{zh ? "最后更新" : "Last update", mdCell(info.optString("lastUpdateTime", ""))});
        rows.add(new String[]{zh ? "目标 SDK" : "Target SDK", mdCell(String.valueOf(info.optInt("targetSdkVersion", 0)))});
        rows.add(new String[]{zh ? "最低 SDK" : "Min SDK", mdCell(String.valueOf(info.optInt("minSdkVersion", 0)))});
        rows.add(new String[]{zh ? "系统应用" : "System app", mdCell(String.valueOf(info.optBoolean("isSystemApp", false)))});
        rows.add(new String[]{zh ? "已启用" : "Enabled", mdCell(String.valueOf(info.optBoolean("enabled", true)))});
        rows.add(new String[]{zh ? "数据目录" : "Data dir", mdCell(info.optString("dataDir", ""))});
        rows.add(new String[]{zh ? "源码目录" : "Source dir", mdCell(info.optString("sourceDir", ""))});
        rows.add(new String[]{zh ? "安装器包名" : "Installer package", mdCell(info.optString("installerPackage", ""))});
        rows.add(new String[]{zh ? "安装来源" : "Installer label", mdCell(info.optString("installerLabel", ""))});
        return pgTable(title, headers, rows);
    }

    /**
     * 包装成功响应（无额外展示字段）。
     *
     * @param output 写入 {@code output} 的字符串
     * @return JSON
     * @throws Exception JSON 异常
     */
    private String ok(String output) throws Exception {
        return new JSONObject().put("success", true).put("output", output).toString();
    }

    /**
     * 包装成功响应并可选附加 {@code _displayText}。
     *
     * @param output      业务输出 JSON 字符串或其它文本
     * @param displayText 界面展示用摘要
     * @return JSON
     * @throws Exception JSON 异常
     */
    private String ok(String output, String displayText) throws Exception {
        JSONObject r = new JSONObject().put("success", true).put("output", output);
        if (displayText != null && !displayText.isEmpty()) r.put("_displayText", displayText);
        return r.toString();
    }

    /**
     * 构造失败响应 JSON。
     *
     * @param message 错误信息
     * @return 含 {@code success:false} 与 {@code error}
     * @throws Exception JSON 异常
     */
    private String error(String message) throws Exception {
        return new JSONObject().put("success", false).put("error", message).toString();
    }
}
