package ai.rorsch.moduleplugins.app_manager;

import android.content.Context;
import android.content.Intent;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.provider.Settings;
import ai.rorsch.pandagenie.module.runtime.HtmlOutputHelper;
import ai.rorsch.pandagenie.module.runtime.ModulePlugin;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 已安装应用管理模块插件：列举应用、启动应用、查询详情、发起卸载与打开应用设置页。
 * <p>
 * <b>模块用途：</b>基于 {@link PackageManager} 与 {@link Intent} 提供常见应用管理操作，供 Agent/任务在授权环境下调用。
 * </p>
 * <p>
 * <b>提供的 API（{@code action}）：</b>
 * {@code listApps}（参数可含 {@code includeSystem}）、{@code listCallableApps}、{@code invokeCallableApp}、
 * {@code openApp}（{@code nameOrPackage}）、{@code getAppInfo}、{@code uninstallApp}
 * （{@code packageName} 或 {@code nameOrPackage}）、{@code openAppSettings}。
 * </p>
 * <p>
 * <b>加载方式：</b>由 {@code ModuleRuntime} 通过反射实例化并实现 {@link ModulePlugin} 后调用 {@link #invoke}。
 * </p>
 */
public class AppManagerPlugin implements ModulePlugin {

    private static final String SDK_APPS_URL = "https://cf.pandagenie.ai/sdk/apps";
    private static final int SDK_TIMEOUT_MS = 12000;
    private static final String ACTION_CAPABILITY_SERVICE = "ai.rorsch.pandagenie.sdk.action.CAPABILITY_SERVICE";
    private static final String META_ROLE = "ai.rorsch.pandagenie.sdk.ROLE";
    private static final String META_PROVIDER_AUTHORITY = "ai.rorsch.pandagenie.sdk.PROVIDER_AUTHORITY";
    private static final String ROLE_PROVIDER = "provider";
    private static final String METHOD_GET_MANIFEST = "getManifest";
    private static final String METHOD_INVOKE = "invoke";
    private static final int MSG_GET_MANIFEST = 1001;
    private static final int MSG_INVOKE = 1002;
    private static final int MSG_RESULT = 1003;
    private static final int MSG_ERROR = 1004;
    private static final String KEY_MANIFEST_JSON = "manifest_json";
    private static final String KEY_CAPABILITY_ID = "capability_id";
    private static final String KEY_PARAMS_JSON = "params_json";
    private static final String KEY_RESULT_JSON = "result_json";
    private static final String KEY_ERROR = "error";
    private static final String KEY_GRANT_TOKEN = "grant_token";
    private static final String KEY_AGENT_PACKAGE = "agent_package";
    private static final String KEY_AGENT_SIGNATURE_SHA256 = "agent_signature_sha256";
    private static final long SDK_BIND_TIMEOUT_MS = 8000L;
    private static final String SDK_DEMO_PACKAGE = "ai.rorsch.pandagenie.sdkdemo.provider";
    private static final String SDK_DEMO_SERVICE = "ai.rorsch.pandagenie.sdkdemo.provider.DemoCapabilityService";
    private static final String SDK_DEMO_PROVIDER_AUTHORITY = "ai.rorsch.pandagenie.sdkdemo.provider.sdk";
    private static final String SDK_DEMO_MAIN_ACTIVITY = "ai.rorsch.pandagenie.sdkdemo.provider.MainActivity";
    private static final String SDK_DEMO_PROVIDER_URI = "content://ai.rorsch.pandagenie.sdkdemo.provider.samples/items";
    private static final String SDK_DEMO_BROADCAST_ACTION = "ai.rorsch.pandagenie.sdkdemo.provider.DEMO_PING";

    private static final class ProviderCandidate {
        ComponentName component;
        String appName = "";
        String packageName = "";
        String serviceName = "";
        String providerAuthority = "";
        String signatureSha256 = "";
        JSONObject manifest;
        JSONArray capabilities = new JSONArray();
    }

    private static final class CapabilityMatch {
        ProviderCandidate provider;
        JSONObject capability;
        int score;
    }

    private static final class SdkResponse {
        int what;
        Bundle data;
    }

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
                String output = listApps(context, params);
                return ok(output, formatListAppsDisplay(new JSONObject(output)), formatListAppsHtml(output));
            }
            case "listRecentApps": {
                String output = listRecentApps(context, params);
                return ok(output, formatRecentAppsDisplay(new JSONObject(output)), formatListRecentAppsHtml(output));
            }
            case "listCallableApps":
            case "listInteractableApps":
            case "listSdkApps": {
                String output = listCallableApps(context, params);
                JSONObject obj = new JSONObject(output);
                return ok(output, formatCallableAppsDisplay(obj), formatCallableAppsHtml(obj));
            }
            case "invokeCallableApp":
            case "invokeInteractableApp":
            case "callSdkApp":
                return invokeCallableApp(context, params);
            case "openApp":
                return openApp(context, params);
            case "getAppInfo": {
                String output = getAppInfo(context, params);
                JSONObject probe = new JSONObject(output);
                if (probe.has("success") && !probe.optBoolean("success", true)) return output;
                return ok(output, formatGetAppInfoDisplay(probe), formatGetAppInfoHtml(output));
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

    private String listRecentApps(Context context, JSONObject params) throws Exception {
        int days = params.optInt("days", 30);
        boolean includeSystem = params.optBoolean("includeSystem", false);
        String dateField = params.optString("dateField", "firstInstallTime");
        boolean useInstall = "firstInstallTime".equals(dateField);
        PackageManager pm = context.getPackageManager();
        List<PackageInfo> packages = pm.getInstalledPackages(0);
        long cutoff = System.currentTimeMillis() - (long) days * 24 * 3600 * 1000;
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
        List<JSONObject> apps = new ArrayList<>();

        for (PackageInfo pi : packages) {
            if (!includeSystem && isSystemApp(pi)) continue;
            long ts = useInstall ? pi.firstInstallTime : pi.lastUpdateTime;
            if (ts < cutoff) continue;
            JSONObject app = new JSONObject();
            app.put("packageName", pi.packageName);
            app.put("appName", pm.getApplicationLabel(pi.applicationInfo).toString());
            app.put("versionName", pi.versionName != null ? pi.versionName : "");
            app.put("firstInstallTime", sdf.format(new Date(pi.firstInstallTime)));
            app.put("lastUpdateTime", sdf.format(new Date(pi.lastUpdateTime)));
            app.put("_sortTs", ts);
            apps.add(app);
        }

        Collections.sort(apps, (a, b) -> Long.compare(b.optLong("_sortTs", 0), a.optLong("_sortTs", 0)));
        for (JSONObject app : apps) app.remove("_sortTs");

        JSONObject result = new JSONObject();
        JSONArray arr = new JSONArray();
        for (JSONObject app : apps) arr.put(app);
        result.put("count", apps.size());
        result.put("days", days);
        result.put("dateField", dateField);
        result.put("apps", arr);
        return result.toString();
    }

    private String listCallableApps(Context context, JSONObject params) throws Exception {
        int limit = Math.max(1, Math.min(params.optInt("limit", 50), 100));
        String search = params.optString("search", params.optString("q", "")).trim();
        boolean onlyInstalled = params.optBoolean("onlyInstalled", false);
        String locale = isZh() ? "zh" : "en";

        StringBuilder url = new StringBuilder(SDK_APPS_URL)
                .append("?role=provider")
                .append("&limit=").append(limit)
                .append("&locale=").append(URLEncoder.encode(locale, "UTF-8"));
        if (!search.isEmpty()) {
            url.append("&search=").append(URLEncoder.encode(search, "UTF-8"));
        }

        JSONObject response = new JSONObject(httpGet(url.toString()));
        if (!response.optBoolean("success", false)) {
            throw new IOException(response.optString("error", "Fetch SDK apps failed"));
        }
        JSONObject data = response.optJSONObject("data");
        JSONArray items = data != null ? data.optJSONArray("items") : null;
        JSONArray apps = new JSONArray();

        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                String packageName = item.optString("package_name", "");
                boolean installed = isPackageInstalled(context, packageName);
                if (onlyInstalled && !installed) continue;

                JSONObject app = new JSONObject();
                app.put("id", item.optInt("id", 0));
                app.put("appName", item.optString("app_name", ""));
                app.put("packageName", packageName);
                app.put("role", item.optString("role", "provider"));
                app.put("developerName", item.optString("developer_name", ""));
                app.put("description", item.optString("description", ""));
                app.put("homepageUrl", item.optString("homepage_url", ""));
                app.put("updatedAt", item.optString("updated_at", ""));
                app.put("signatureCount", item.optInt("signature_count", 0));
                app.put("capabilityCount", item.optInt("capability_count", 0));
                app.put("installed", installed);
                app.put("capabilities", parseExpectedApis(item.optString("expected_apis", "")));
                apps.put(app);
            }
        }

        JSONObject result = new JSONObject();
        result.put("count", apps.length());
        result.put("total", data != null ? data.optInt("total", apps.length()) : apps.length());
        result.put("limit", limit);
        result.put("search", search);
        result.put("onlyInstalled", onlyInstalled);
        result.put("serverTime", data != null ? data.optString("server_time", "") : "");
        result.put("apps", apps);
        return result.toString();
    }

    /**
     * 发现并调用已安装 SDK Provider 暴露的能力，例如打开演示页或读取 Provider 数据。
     */
    private String invokeCallableApp(Context context, JSONObject params) throws Exception {
        String target = firstNonEmpty(
                params.optString("appNameOrPackage", ""),
                params.optString("nameOrPackage", ""),
                params.optString("packageName", ""),
                params.optString("appName", "")
        ).trim();
        String capabilityId = firstNonEmpty(
                params.optString("capabilityId", ""),
                params.optString("id", "")
        ).trim();
        String capabilityKeyword = firstNonEmpty(
                params.optString("capabilityKeyword", ""),
                params.optString("capability", ""),
                params.optString("action", ""),
                params.optString("query", ""),
                params.optString("title", "")
        ).trim();
        String userText = (target + " " + capabilityId + " " + capabilityKeyword + " "
                + params.optString("message", "") + " " + params.optString("userQuery", "")).trim();
        if (capabilityKeyword.isEmpty() && looksLikeDemoOpen(userText)) {
            capabilityKeyword = isZh() ? "\u6253\u5f00\u6f14\u793a\u9875\u9762" : "open demo page";
        }
        String inferredCapabilityId = inferCapabilityId(userText + " " + capabilityKeyword);
        if (capabilityId.isEmpty() && !inferredCapabilityId.isEmpty()) {
            capabilityId = inferredCapabilityId;
        }
        List<ProviderCandidate> providers = discoverSdkProviders(context, target);
        CapabilityMatch match = findBestCapability(providers, target, capabilityId, capabilityKeyword, userText);
        if (isWeakCapabilityMatch(match) && !userText.equals(target)) {
            List<ProviderCandidate> expandedProviders = discoverSdkProviders(context, userText);
            if (expandedProviders != null && !expandedProviders.isEmpty()) {
                LinkedHashMap<String, ProviderCandidate> merged = new LinkedHashMap<>();
                mergeProviderCandidates(merged, providers);
                mergeProviderCandidates(merged, expandedProviders);
                providers = new ArrayList<>(merged.values());
                match = findBestCapability(providers, firstNonEmpty(target, userText), capabilityId, capabilityKeyword, userText);
            }
        }
        if (isWeakCapabilityMatch(match)) {
            return error(buildCapabilityMatchError(providers, target, capabilityId, capabilityKeyword));
        }

        JSONObject invokeParams = buildInvokeParams(params, match.capability);
        Bundle bundle = new Bundle();
        bundle.putString(KEY_CAPABILITY_ID, match.capability.optString("id", capabilityId));
        bundle.putString(KEY_PARAMS_JSON, invokeParams.toString());
        bundle.putString(KEY_GRANT_TOKEN, params.optString("grantToken", ""));
        bundle.putString(KEY_AGENT_PACKAGE, context.getPackageName());
        bundle.putString(KEY_AGENT_SIGNATURE_SHA256, getOwnSignatureSha256(context));

        SdkResponse response;
        try {
            response = sendSdkCall(context, match.provider, MSG_INVOKE, bundle);
        } catch (Exception e) {
            throw e;
        }
        if (response.what == MSG_ERROR) {
            String err = response.data != null ? response.data.getString(KEY_ERROR, "") : "";
            if (err == null || err.trim().isEmpty()) err = "SDK capability invocation failed";
            String lower = err.toLowerCase(Locale.ROOT);
            if (lower.contains("not authorized") || lower.contains("not trusted") || lower.contains("unauthorized")) {
                err = isZh()
                        ? "SDK \u8c03\u7528\u672a\u6388\u6743\uff1a\u8bf7\u786e\u8ba4 PandaGenie \u5df2\u4f5c\u4e3a AI \u52a9\u624b\u89d2\u8272\u5b8c\u6210\u6ce8\u518c\u5e76\u901a\u8fc7\u5ba1\u6838\u3002\u539f\u59cb\u9519\u8bef\uff1a" + err
                        : "SDK invocation is not authorized. Make sure PandaGenie is registered and approved as an AI assistant. Raw error: " + err;
            }
            return error(err);
        }
        if (response.what != MSG_RESULT) return error("Unexpected SDK response: " + response.what);

        String resultJson = response.data != null ? response.data.getString(KEY_RESULT_JSON, "{}") : "{}";
        JSONObject result;
        try {
            result = new JSONObject(resultJson == null || resultJson.trim().isEmpty() ? "{}" : resultJson);
        } catch (Exception e) {
            result = new JSONObject().put("raw", resultJson);
        }
        result = normalizeKnownDemoResult(context, match, invokeParams, result);

        JSONObject output = buildInvokeOutput(match, invokeParams, result);
        return ok(output.toString(), formatInvokeCallableAppDisplay(output), formatInvokeCallableAppHtml(output), formatInvokeCallableAppHtmlFull(output));
    }

    private JSONObject normalizeKnownDemoResult(Context context, CapabilityMatch match, JSONObject invokeParams, JSONObject result) {
        if (result == null) result = new JSONObject();
        String capabilityId = resolveKnownDemoFallbackCapabilityId(match, invokeParams);
        if (!"demo.read_note".equals(capabilityId)) return result;
        String note = result.optString("note", "").trim();
        if (!note.isEmpty() || context == null) return result;
        try {
            String fallbackNote = context.getSharedPreferences("pandagenie_sdk_demo_fallback", Context.MODE_PRIVATE)
                    .getString("last_note", "");
            if (fallbackNote != null && !fallbackNote.trim().isEmpty()) {
                result.put("note", fallbackNote);
                result.put("empty", false);
                result.put("fallbackLocalCache", true);
            }
        } catch (Exception ignored) {}
        return result;
    }

    private JSONObject buildInvokeOutput(CapabilityMatch match, JSONObject invokeParams, JSONObject result) throws Exception {
        JSONObject output = new JSONObject();
        output.put("appName", match.provider.appName);
        output.put("packageName", match.provider.packageName);
        output.put("capabilityId", match.capability.optString("id", ""));
        output.put("capabilityTitle", capabilityTitle(match.capability));
        output.put("kind", match.capability.optString("kind", ""));
        output.put("params", invokeParams);
        output.put("result", result);
        return output;
    }

    private JSONObject tryInvokeKnownDemoFallback(Context context, CapabilityMatch match, JSONObject invokeParams, Exception cause) {
        if (context == null || match == null || match.provider == null || match.capability == null) return null;
        if (!isKnownDemoComponent(match.provider.component) && !SDK_DEMO_PACKAGE.equals(match.provider.packageName)) return null;
        String capabilityId = resolveKnownDemoFallbackCapabilityId(match, invokeParams);
        if (capabilityId.isEmpty()) return null;
        try {
            return invokeKnownDemoFallback(context, capabilityId, invokeParams, cause);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String tryInvokeDirectKnownDemoFallback(Context context, JSONObject params, String target,
                                                   String capabilityId, String capabilityKeyword, String userText) {
        if (context == null || params == null) return null;
        String text = buildDirectKnownDemoFallbackText(params, target, capabilityId, capabilityKeyword, userText);
        if (!looksLikeDemoTarget(text)) return null;
        String resolvedCapabilityId = inferKnownDemoFallbackCapabilityId(firstNonEmpty(capabilityId, "") + " " + text);
        if (resolvedCapabilityId.isEmpty()) return null;
        try {
            CapabilityMatch match = buildKnownDemoCapabilityMatch(resolvedCapabilityId);
            if (match == null || match.capability == null) return null;
            JSONObject invokeParams = buildInvokeParams(params, match.capability);
            JSONObject result = invokeKnownDemoFallback(context, resolvedCapabilityId, invokeParams, null);
            if (result == null) return null;
            JSONObject output = buildInvokeOutput(match, invokeParams, result);
            return ok(output.toString(), formatInvokeCallableAppDisplay(output), formatInvokeCallableAppHtml(output), formatInvokeCallableAppHtmlFull(output));
        } catch (Exception ignored) {
            return null;
        }
    }

    private String buildDirectKnownDemoFallbackText(JSONObject params, String target, String capabilityId,
                                                   String capabilityKeyword, String userText) {
        StringBuilder sb = new StringBuilder();
        if (target != null) sb.append(target).append(' ');
        if (capabilityId != null) sb.append(capabilityId).append(' ');
        if (capabilityKeyword != null) sb.append(capabilityKeyword).append(' ');
        if (userText != null) sb.append(userText).append(' ');
        if (params != null) sb.append(params.toString());
        return sb.toString();
    }

    private CapabilityMatch buildKnownDemoCapabilityMatch(String capabilityId) throws Exception {
        JSONObject manifest = buildDemoProviderManifest();
        JSONArray caps = manifest.optJSONArray("capabilities");
        JSONObject cap = findCapabilityById(caps, capabilityId);
        if (cap == null) return null;
        ProviderCandidate provider = new ProviderCandidate();
        provider.packageName = SDK_DEMO_PACKAGE;
        provider.serviceName = SDK_DEMO_SERVICE;
        provider.providerAuthority = SDK_DEMO_PROVIDER_AUTHORITY;
        provider.component = new ComponentName(SDK_DEMO_PACKAGE, SDK_DEMO_SERVICE);
        provider.manifest = manifest;
        provider.capabilities = caps != null ? caps : new JSONArray();
        provider.appName = manifest.optString("appName", isZh()
                ? "PandaGenie \u80fd\u529b\u6f14\u793a"
                : "PandaGenie Capability Demo");
        CapabilityMatch match = new CapabilityMatch();
        match.provider = provider;
        match.capability = cap;
        match.score = 999;
        return match;
    }

    private static JSONObject findCapabilityById(JSONArray capabilities, String capabilityId) {
        if (capabilities == null || capabilityId == null) return null;
        String wanted = capabilityId.trim();
        if (wanted.isEmpty()) return null;
        for (int i = 0; i < capabilities.length(); i++) {
            JSONObject cap = capabilities.optJSONObject(i);
            if (cap != null && wanted.equals(cap.optString("id", "").trim())) return cap;
        }
        return null;
    }

    private String resolveKnownDemoFallbackCapabilityId(CapabilityMatch match, JSONObject invokeParams) {
        if (match == null || match.capability == null) return "";
        String exact = match.capability.optString("id", "").trim();
        if (isKnownDemoFallbackCapability(exact)) return exact;

        String text = buildKnownDemoFallbackText(match, invokeParams);
        return inferKnownDemoFallbackCapabilityId(text);
    }

    private static String inferKnownDemoFallbackCapabilityId(String text) {
        if (text == null) return "";
        String trimmed = text.trim();
        if (isKnownDemoFallbackCapability(trimmed)) return trimmed;
        String inferred = inferCapabilityId(text);
        if (isKnownDemoFallbackCapability(inferred)) return inferred;
        if (looksLikeNoteSave(text)) return "demo.save_note";
        if (looksLikeNoteRead(text)) return "demo.read_note";
        if (looksLikeProviderSearch(text)) return "demo.provider_search";
        if (looksLikeDemoDataRead(text)) return "demo.provider_rows";
        if (looksLikeDetailOpen(text)) return "demo.open_detail_page";
        if (looksLikeDemoOpen(text)) return "demo.open_activity";
        if (looksLikeBroadcast(text)) return "demo.broadcast_ping";
        if (looksLikeEcho(text)) return "demo.echo";
        return "";
    }

    private static boolean isKnownDemoFallbackCapability(String capabilityId) {
        if (capabilityId == null) return false;
        String id = capabilityId.trim();
        return "demo.provider_rows".equals(id)
                || "demo.provider_search".equals(id)
                || "demo.open_activity".equals(id)
                || "demo.open_detail_page".equals(id)
                || "demo.broadcast_ping".equals(id)
                || "demo.broadcast_status".equals(id)
                || "demo.echo".equals(id)
                || "demo.save_note".equals(id)
                || "demo.read_note".equals(id)
                || "demo.long_task".equals(id);
    }

    private String buildKnownDemoFallbackText(CapabilityMatch match, JSONObject invokeParams) {
        StringBuilder sb = new StringBuilder();
        if (match != null) {
            if (match.capability != null) sb.append(match.capability.toString()).append(' ');
            if (match.provider != null) {
                if (match.provider.appName != null) sb.append(match.provider.appName).append(' ');
                if (match.provider.packageName != null) sb.append(match.provider.packageName).append(' ');
            }
        }
        if (invokeParams != null) sb.append(invokeParams.toString());
        return sb.toString();
    }

    private JSONObject invokeKnownDemoFallback(Context context, String capabilityId, JSONObject params, Exception cause) throws Exception {
        String id = capabilityId == null ? "" : capabilityId.trim();
        if ("demo.provider_rows".equals(id) || "demo.provider_search".equals(id)) {
            String keyword = firstNonEmpty(
                    params.optString("keyword", ""),
                    params.optString("query", ""),
                    params.optString("message", "")
            );
            return queryDemoProviderRows(context, "demo.provider_search".equals(id) ? keyword : "");
        }
        if ("demo.open_activity".equals(id) || "demo.open_detail_page".equals(id)) {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(SDK_DEMO_PACKAGE, SDK_DEMO_MAIN_ACTIVITY));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            intent.putExtra("title", firstNonEmpty(params.optString("title", ""), isZh() ? "\u0050\u0061\u006e\u0064\u0061\u0047\u0065\u006e\u0069\u0065\u0020\u0053\u0044\u004b\u0020\u6f14\u793a" : "PandaGenie SDK Demo"));
            intent.putExtra("message", firstNonEmpty(params.optString("message", ""), isZh() ? "\u7531\u0020\u0050\u0061\u006e\u0064\u0061\u0047\u0065\u006e\u0069\u0065\u0020\u8c03\u7528\u6f14\u793a\u5e94\u7528\u6253\u5f00" : "Opened by PandaGenie"));
            intent.putExtra("source", "PandaGenie");
            if ("demo.open_detail_page".equals(id)) {
                intent.putExtra("detailId", firstNonEmpty(params.optString("detailId", ""), "demo-detail"));
            }
            context.startActivity(intent);
            return new JSONObject()
                    .put("opened", true)
                    .put("fallback", true)
                    .put("packageName", SDK_DEMO_PACKAGE)
                    .put("note", isZh()
                            ? "\u0053\u0044\u004b\u0020\u0053\u0065\u0072\u0076\u0069\u0063\u0065\u0020\u672a\u80fd\u7ed1\u5b9a\uff0c\u5df2\u76f4\u63a5\u6253\u5f00\u6f14\u793a\u9875\u3002"
                            : "SDK service binding failed, opened the demo page directly.");
        }
        if ("demo.broadcast_ping".equals(id) || "demo.broadcast_status".equals(id)) {
            Intent intent = new Intent(SDK_DEMO_BROADCAST_ACTION);
            intent.setPackage(SDK_DEMO_PACKAGE);
            intent.putExtra("title", params.optString("title", ""));
            intent.putExtra("message", params.optString("message", ""));
            intent.putExtra("source", "PandaGenie");
            context.sendBroadcast(intent);
            return new JSONObject().put("broadcastSent", true).put("fallback", true).put("action", SDK_DEMO_BROADCAST_ACTION);
        }
        if ("demo.echo".equals(id)) {
            return new JSONObject()
                    .put("echo", firstNonEmpty(params.optString("message", ""), params.toString()))
                    .put("fallback", true);
        }
        if ("demo.save_note".equals(id)) {
            String note = firstNonEmpty(params.optString("note", ""), params.optString("message", ""), params.toString());
            context.getSharedPreferences("pandagenie_sdk_demo_fallback", Context.MODE_PRIVATE)
                    .edit()
                    .putString("last_note", note)
                    .putLong("updated_at", System.currentTimeMillis())
                    .apply();
            return new JSONObject()
                    .put("saved", true)
                    .put("note", note)
                    .put("fallback", true);
        }
        if ("demo.read_note".equals(id)) {
            String note = context.getSharedPreferences("pandagenie_sdk_demo_fallback", Context.MODE_PRIVATE)
                    .getString("last_note", "");
            return new JSONObject()
                    .put("note", note)
                    .put("empty", note == null || note.isEmpty())
                    .put("fallback", true);
        }
        if ("demo.long_task".equals(id)) {
            return new JSONObject()
                    .put("done", true)
                    .put("durationMs", 0)
                    .put("fallback", true);
        }
        return null;
    }

    private JSONObject queryDemoProviderRows(Context context, String keyword) throws Exception {
        Cursor cursor = null;
        JSONArray rows = new JSONArray();
        String filter = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        try {
            cursor = context.getContentResolver().query(Uri.parse(SDK_DEMO_PROVIDER_URI), null, null, null, null);
            if (cursor == null) {
                return buildDemoProviderRowsFallback(filter, isZh()
                        ? "\u6f14\u793a\u5e94\u7528\u7684 Provider \u6ca1\u6709\u8fd4\u56de\u6570\u636e\uff0c\u5df2\u4f7f\u7528\u672c\u5730\u6f14\u793a\u6570\u636e\u3002"
                        : "The demo provider returned no data, so local demo data was used.");
            }
            while (cursor.moveToNext()) {
                JSONObject row = new JSONObject();
                for (int i = 0; i < cursor.getColumnCount(); i++) {
                    String name = cursor.getColumnName(i);
                    if (name == null) name = "column_" + i;
                    int type = cursor.getType(i);
                    if (type == Cursor.FIELD_TYPE_NULL) {
                        row.put(name, JSONObject.NULL);
                    } else if (type == Cursor.FIELD_TYPE_INTEGER) {
                        row.put(name, cursor.getLong(i));
                    } else if (type == Cursor.FIELD_TYPE_FLOAT) {
                        row.put(name, cursor.getDouble(i));
                    } else if (type == Cursor.FIELD_TYPE_BLOB) {
                        row.put(name, "[blob]");
                    } else {
                        row.put(name, cursor.getString(i));
                    }
                }
                String text = row.toString().toLowerCase(Locale.ROOT);
                if (filter.isEmpty() || text.contains(filter)) {
                    rows.put(row);
                }
            }
        } catch (Exception e) {
            return buildDemoProviderRowsFallback(filter, isZh()
                    ? "\u65e0\u6cd5\u8bfb\u53d6\u6f14\u793a\u5e94\u7528 Provider\uff0c\u5df2\u4f7f\u7528\u672c\u5730\u6f14\u793a\u6570\u636e\u3002\u539f\u56e0\uff1a" + e.getMessage()
                    : "Unable to read the demo app Provider, so local demo data was used. Reason: " + e.getMessage());
        } finally {
            if (cursor != null) cursor.close();
        }
        return new JSONObject()
                .put("fallback", false)
                .put("providerAvailable", true)
                .put("source", SDK_DEMO_PROVIDER_URI)
                .put("count", rows.length())
                .put("rows", rows);
    }

    private JSONObject buildDemoProviderRowsFallback(String filter, String reason) throws Exception {
        JSONArray samples = new JSONArray();
        boolean zh = isZh();
        samples.put(new JSONObject()
                .put("id", 1)
                .put("title", zh ? "\u0053\u0044\u004b\u0020\u0050\u0072\u006f\u0076\u0069\u0064\u0065\u0072\u0020\u6f14\u793a\u8bb0\u5f55" : "SDK Provider demo record")
                .put("message", zh ? "\u0050\u0061\u006e\u0064\u0061\u0047\u0065\u006e\u0069\u0065\u0020\u53ef\u4ee5\u901a\u8fc7\u0020\u0053\u0044\u004b\u0020\u8bfb\u53d6\u88ab\u8c03\u7528\u5e94\u7528\u66b4\u9732\u7684\u0020\u0050\u0072\u006f\u0076\u0069\u0064\u0065\u0072\u0020\u6570\u636e\u3002" : "PandaGenie can read Provider data exposed by callable apps through the SDK.")
                .put("type", "provider"));
        samples.put(new JSONObject()
                .put("id", 2)
                .put("title", zh ? "\u0041\u0063\u0074\u0069\u0076\u0069\u0074\u0079\u0020\u6f14\u793a" : "Activity demo")
                .put("message", zh ? "\u53ef\u901a\u8fc7\u0020\u0053\u0044\u004b\u0020\u6253\u5f00\u76ee\u6807\u5e94\u7528\u6307\u5b9a\u9875\u9762\u5e76\u4f20\u5165\u6587\u672c\u3002" : "The SDK can open a target app page and pass text to it.")
                .put("type", "activity"));
        samples.put(new JSONObject()
                .put("id", 3)
                .put("title", zh ? "\u0042\u0072\u006f\u0061\u0064\u0063\u0061\u0073\u0074\u0020\u6f14\u793a" : "Broadcast demo")
                .put("message", zh ? "\u53ef\u901a\u8fc7\u0020\u0053\u0044\u004b\u0020\u89e6\u53d1\u76ee\u6807\u5e94\u7528\u7684\u5e7f\u64ad\u80fd\u529b\u3002" : "The SDK can trigger broadcast capabilities in the target app.")
                .put("type", "broadcast"));

        JSONArray rows = new JSONArray();
        String needle = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < samples.length(); i++) {
            JSONObject row = samples.optJSONObject(i);
            if (row == null) continue;
            if (needle.isEmpty() || row.toString().toLowerCase(Locale.ROOT).contains(needle)) {
                rows.put(row);
            }
        }
        return new JSONObject()
                .put("fallback", true)
                .put("providerAvailable", false)
                .put("source", SDK_DEMO_PROVIDER_URI)
                .put("note", reason == null ? "" : reason)
                .put("count", rows.length())
                .put("rows", rows);
    }

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

    private static String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value;
        }
        return "";
    }

    private static boolean looksLikeDemoOpen(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        return (text.contains("\u6f14\u793a") || lower.contains("demo"))
                && (text.contains("\u6253\u5f00") || text.contains("\u542f\u52a8")
                || lower.contains("open") || lower.contains("launch"));
    }

    private static boolean looksLikeDemoApp(String text) {
        if (text == null) return false;
        String n = normalizeCapabilityText(text);
        return n.contains("pandagenie") && (n.contains("\u80fd\u529b\u6f14\u793a")
                || n.contains("sdkdemo") || n.contains("demo") || n.contains("\u6f14\u793a"));
    }

    private static String inferCapabilityId(String text) {
        if (looksLikeNoteSave(text)) return "demo.save_note";
        if (looksLikeNoteRead(text)) return "demo.read_note";
        if (looksLikeProviderSearch(text)) return "demo.provider_search";
        if (looksLikeProviderRead(text) || looksLikeDemoDataRead(text)) return "demo.provider_rows";
        if (looksLikeDetailOpen(text)) return "demo.open_detail_page";
        if (looksLikeDemoOpen(text)) return "demo.open_activity";
        if (looksLikeBroadcast(text)) return "demo.broadcast_ping";
        if (looksLikeEcho(text)) return "demo.echo";
        return "";
    }

    private static boolean looksLikeProviderRead(String text) {
        String n = normalizeCapabilityText(text);
        return n.contains("provider")
                && containsAny(n, "\u8bfb\u53d6", "\u67e5\u8be2", "\u83b7\u53d6", "\u5217\u8868", "\u6570\u636e", "read", "list", "rows", "data")
                && !containsAny(n, "\u641c\u7d22", "\u67e5\u627e", "search");
    }

    private static boolean looksLikeDemoDataRead(String text) {
        String n = normalizeCapabilityText(text);
        return looksLikeDemoTarget(text)
                && containsAny(n, "provider", "\u6f14\u793a\u6570\u636e", "\u8bfb\u53d6", "\u67e5\u8be2", "\u83b7\u53d6", "\u5217\u8868", "\u6570\u636e",
                "read", "list", "rows", "data");
    }

    private static boolean looksLikeProviderSearch(String text) {
        String n = normalizeCapabilityText(text);
        return n.contains("provider") && containsAny(n, "\u641c\u7d22", "\u67e5\u627e", "search");
    }

    private static boolean looksLikeDetailOpen(String text) {
        String n = normalizeCapabilityText(text);
        return containsAny(n, "\u8be6\u60c5", "detail")
                && containsAny(n, "\u6253\u5f00", "\u542f\u52a8", "\u9875\u9762", "open", "launch", "page");
    }

    private static boolean looksLikeBroadcast(String text) {
        String n = normalizeCapabilityText(text);
        return containsAny(n, "\u5e7f\u64ad", "broadcast");
    }

    private static boolean looksLikeEcho(String text) {
        String n = normalizeCapabilityText(text);
        return containsAny(n, "\u56de\u663e", "\u56de\u54cd", "echo");
    }

    private static boolean looksLikeNoteSave(String text) {
        String n = normalizeCapabilityText(text);
        return containsAny(n, "\u4fbf\u7b7e", "\u7b14\u8bb0", "\u6807\u7b7e", "note")
                && containsAny(n, "\u4fdd\u5b58", "\u5199\u5165", "save", "write");
    }

    private static boolean looksLikeNoteRead(String text) {
        String n = normalizeCapabilityText(text);
        return containsAny(n, "\u4fbf\u7b7e", "\u7b14\u8bb0", "\u6807\u7b7e", "note")
                && containsAny(n, "\u8bfb\u53d6", "\u67e5\u770b", "\u67e5\u8be2", "read", "list", "query");
    }

    private static String normalizeCapabilityText(String text) {
        if (text == null) return "";
        return text.toLowerCase(Locale.ROOT)
                .replace(" ", "")
                .replace("_", "")
                .replace("-", "")
                .replace("\t", "")
                .replace("\n", "")
                .replace("\r", "");
    }

    private static boolean containsAny(String text, String... needles) {
        if (text == null || needles == null) return false;
        for (String needle : needles) {
            if (needle != null && !needle.isEmpty() && text.contains(needle.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private List<ProviderCandidate> discoverSdkProviders(Context context, String target) throws Exception {
        PackageManager pm = context.getPackageManager();
        Map<String, ProviderCandidate> result = new LinkedHashMap<>();
        boolean demoTarget = looksLikeDemoTarget(target);

        LinkedHashSet<String> targetPackages = new LinkedHashSet<>();
        targetPackages.addAll(findTargetSdkPackages(context, target, demoTarget));
        targetPackages.addAll(fetchSdkProviderPackagesFromServer(target));

        for (String packageName : targetPackages) {
            querySdkPackageInto(context, pm, packageName, result);
        }
        querySdkProviderMetadataInto(context, pm, result);
        if (demoTarget) {
            addDemoProviderFallbackIfInstalled(context, result);
            addProviderCandidateFromComponent(context, pm,
                    new ComponentName(SDK_DEMO_PACKAGE, SDK_DEMO_SERVICE),
                    result);
        }

        querySdkServicesInto(context, pm, new Intent(ACTION_CAPABILITY_SERVICE), result);
        return new ArrayList<>(result.values());
    }

    private void querySdkPackageInto(Context context, PackageManager pm, String packageName,
                                     Map<String, ProviderCandidate> result) {
        if (packageName == null || packageName.trim().isEmpty()) return;
        String pkg = packageName.trim();
        addProviderCandidateFromPackageMetadata(context, pm, pkg, result);
        Intent packageIntent = new Intent(ACTION_CAPABILITY_SERVICE);
        packageIntent.setPackage(pkg);
        querySdkServicesInto(context, pm, packageIntent, result);
        addKnownServiceCandidates(context, pm, pkg, result);
    }

    private void querySdkProviderMetadataInto(Context context, PackageManager pm,
                                              Map<String, ProviderCandidate> result) {
        if (context == null || pm == null || result == null) return;
        int flags = PackageManager.GET_META_DATA;
        if (Build.VERSION.SDK_INT >= 23) flags |= PackageManager.MATCH_ALL;
        try {
            List<PackageInfo> packages = pm.getInstalledPackages(flags);
            if (packages == null) return;
            for (PackageInfo pi : packages) {
                if (pi == null || pi.applicationInfo == null) continue;
                addProviderCandidateFromApplicationInfo(context, pm, pi.applicationInfo, result);
            }
        } catch (Exception ignored) {
        }
    }

    private void addProviderCandidateFromPackageMetadata(Context context, PackageManager pm, String packageName,
                                                        Map<String, ProviderCandidate> result) {
        if (context == null || pm == null || packageName == null || packageName.trim().isEmpty()) return;
        try {
            ApplicationInfo appInfo = pm.getApplicationInfo(packageName.trim(), PackageManager.GET_META_DATA);
            addProviderCandidateFromApplicationInfo(context, pm, appInfo, result);
        } catch (Exception ignored) {
        }
    }

    private void addProviderCandidateFromApplicationInfo(Context context, PackageManager pm, ApplicationInfo appInfo,
                                                        Map<String, ProviderCandidate> result) {
        if (context == null || pm == null || appInfo == null || appInfo.metaData == null || result == null) return;
        String packageName = appInfo.packageName == null ? "" : appInfo.packageName.trim();
        if (packageName.isEmpty() || packageName.equals(context.getPackageName())) return;
        String authority = firstNonEmpty(
                appInfo.metaData.getString(META_PROVIDER_AUTHORITY, ""),
                appInfo.metaData.getString("pandagenie_sdk_provider_authority", "")
        ).trim();
        if (authority.isEmpty()) return;
        String role = firstNonEmpty(appInfo.metaData.getString(META_ROLE, ""), "").trim();
        if (!role.isEmpty() && !ROLE_PROVIDER.equalsIgnoreCase(role)) return;

        ProviderCandidate provider = new ProviderCandidate();
        provider.packageName = packageName;
        provider.providerAuthority = authority;
        try {
            CharSequence label = appInfo.loadLabel(pm);
            provider.appName = label == null ? packageName : label.toString();
        } catch (Exception ignored) {
            provider.appName = packageName;
        }
        try {
            provider.signatureSha256 = getPackageSignatureSha256(context, packageName);
        } catch (Exception ignored) {
        }
        try {
            provider.manifest = loadSdkManifest(context, authority);
            hydrateProviderFromManifest(provider);
        } catch (Exception ignored) {
        }
        if (provider.manifest == null && SDK_DEMO_PACKAGE.equals(packageName)) {
            try {
                provider.manifest = buildDemoProviderManifest();
                hydrateProviderFromManifest(provider);
            } catch (Exception ignored) {
            }
        }
        if (hasCapabilities(provider)) {
            result.put(providerKey(provider), provider);
        }
    }

    private String readProviderAuthorityFromMetadata(Context context, PackageManager pm, String packageName) {
        if (context == null || pm == null || packageName == null || packageName.trim().isEmpty()) return "";
        try {
            ApplicationInfo appInfo = pm.getApplicationInfo(packageName.trim(), PackageManager.GET_META_DATA);
            if (appInfo == null || appInfo.metaData == null) return "";
            return firstNonEmpty(
                    appInfo.metaData.getString(META_PROVIDER_AUTHORITY, ""),
                    appInfo.metaData.getString("pandagenie_sdk_provider_authority", "")
            ).trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private void addKnownServiceCandidates(Context context, PackageManager pm, String packageName,
                                           Map<String, ProviderCandidate> result) {
        if (packageName == null || packageName.trim().isEmpty()) return;
        String pkg = packageName.trim();
        if (!isPackageInstalled(context, pkg)) return;
        LinkedHashSet<String> services = new LinkedHashSet<>();
        if (SDK_DEMO_PACKAGE.equals(pkg)) services.add(SDK_DEMO_SERVICE);
        services.add(pkg + ".DemoCapabilityService");
        services.add(pkg + ".CapabilityService");
        services.add(pkg + ".provider.DemoCapabilityService");
        services.add(pkg + ".provider.CapabilityService");
        services.add(pkg + ".sdk.CapabilityService");
        for (String service : services) {
            addProviderCandidateFromComponent(context, pm, new ComponentName(pkg, service), result);
        }
        if (SDK_DEMO_PACKAGE.equals(pkg)) addDemoProviderFallbackIfInstalled(context, result);
    }

    private void addDemoProviderFallbackIfInstalled(Context context, Map<String, ProviderCandidate> result) {
        if (context == null || result == null) return;
        String key = SDK_DEMO_PACKAGE + "@" + SDK_DEMO_PROVIDER_AUTHORITY;
        if (result.containsKey(key)) return;
        if (!isPackageInstalled(context, SDK_DEMO_PACKAGE)) return;
        try {
            ProviderCandidate provider = new ProviderCandidate();
            provider.packageName = SDK_DEMO_PACKAGE;
            provider.serviceName = SDK_DEMO_SERVICE;
            provider.providerAuthority = SDK_DEMO_PROVIDER_AUTHORITY;
            provider.component = new ComponentName(SDK_DEMO_PACKAGE, SDK_DEMO_SERVICE);
            try {
                provider.manifest = loadSdkManifest(context, SDK_DEMO_PROVIDER_AUTHORITY);
            } catch (Exception ignored) {
            }
            if (provider.manifest == null) provider.manifest = buildDemoProviderManifest();
            hydrateProviderFromManifest(provider);
            if (provider.appName == null || provider.appName.trim().isEmpty()) {
                provider.appName = provider.manifest.optString("appName", isZh()
                        ? "PandaGenie \u80fd\u529b\u6f14\u793a"
                        : "PandaGenie Capability Demo");
            }
            try {
                provider.signatureSha256 = getPackageSignatureSha256(context, SDK_DEMO_PACKAGE);
            } catch (Exception ignored) {}
            if (hasCapabilities(provider)) result.put(providerKey(provider), provider);
        } catch (Exception ignored) {
            // Demo fallback is best-effort; normal SDK discovery still handles real providers.
        }
    }

    private void querySdkServicesInto(Context context, PackageManager pm, Intent intent,
                                      Map<String, ProviderCandidate> result) {
        List<ResolveInfo> services = querySdkServices(pm, intent);
        if (services == null) return;
        for (ResolveInfo info : services) {
            if (info == null || info.serviceInfo == null) continue;
            addProviderCandidateFromComponent(context, pm,
                    new ComponentName(info.serviceInfo.packageName, info.serviceInfo.name),
                    result);
        }
    }

    private List<ResolveInfo> querySdkServices(PackageManager pm, Intent intent) {
        int flags = PackageManager.GET_META_DATA;
        if (Build.VERSION.SDK_INT >= 23) flags |= PackageManager.MATCH_ALL;
        try {
            List<ResolveInfo> services = pm.queryIntentServices(intent, flags);
            return services == null ? new ArrayList<ResolveInfo>() : services;
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
    }

    private Set<String> findTargetSdkPackages(Context context, String target, boolean demoTarget) {
        LinkedHashSet<String> packages = new LinkedHashSet<>();
        PackageManager pm = context.getPackageManager();
        String raw = target == null ? "" : target.trim();
        String normalizedTarget = normalizeCapabilityText(raw);
        if (!raw.isEmpty()) {
            try {
                pm.getPackageInfo(raw, 0);
                packages.add(raw);
            } catch (Exception ignored) {}
        }
        if (demoTarget) {
            packages.add(SDK_DEMO_PACKAGE);
        }
        try {
            List<PackageInfo> installed = pm.getInstalledPackages(0);
            if (installed != null) {
                for (PackageInfo pi : installed) {
                    if (pi == null || pi.packageName == null || pi.applicationInfo == null) continue;
                    String label = "";
                    try {
                        CharSequence cs = pm.getApplicationLabel(pi.applicationInfo);
                        label = cs == null ? "" : cs.toString();
                    } catch (Exception ignored) {}
                    String pkgNorm = normalizeCapabilityText(pi.packageName);
                    String labelNorm = normalizeCapabilityText(label);
                    boolean packageMatches = !pkgNorm.isEmpty()
                            && (pkgNorm.contains(normalizedTarget) || normalizedTarget.contains(pkgNorm));
                    boolean labelMatches = !labelNorm.isEmpty()
                            && (labelNorm.contains(normalizedTarget) || normalizedTarget.contains(labelNorm));
                    if (!normalizedTarget.isEmpty() && (packageMatches || labelMatches)) {
                        packages.add(pi.packageName);
                    }
                    if (demoTarget && (pkgNorm.contains("sdkdemo")
                            || pkgNorm.contains("pandagenie")
                            || labelNorm.contains("\u80fd\u529b\u6f14\u793a")
                            || labelNorm.contains("\u6f14\u793a")
                            || labelNorm.contains("pandageniesdk")
                            || labelNorm.contains("sdk\u6f14\u793a")
                            || labelNorm.contains("能力演示")
                            || labelNorm.contains("演示")
                            || labelNorm.contains("pandageniesdk")
                            || labelNorm.contains("sdk演示"))) {
                        packages.add(pi.packageName);
                    }
                }
            }
        } catch (Exception ignored) {}
        return packages;
    }

    private Set<String> fetchSdkProviderPackagesFromServer(String target) {
        LinkedHashSet<String> packages = new LinkedHashSet<>();
        LinkedHashSet<String> searches = new LinkedHashSet<>();
        String raw = target == null ? "" : target.trim();
        if (!raw.isEmpty()) searches.add(raw);
        if (looksLikeDemoTarget(raw)) {
            searches.add("PandaGenie");
            searches.add("PandaGenie SDK");
            searches.add("demo");
            searches.add("\u80fd\u529b\u6f14\u793a");
            searches.add("\u6f14\u793a");
        }
        for (String search : searches) {
            appendProviderPackagesFromServer(search, packages);
        }
        return packages;
    }

    private void appendProviderPackagesFromServer(String search, Set<String> packages) {
        if (search == null || search.trim().isEmpty() || packages == null) return;
        try {
            String encoded = URLEncoder.encode(search.trim(), "UTF-8");
            String json = httpGet(SDK_APPS_URL + "?role=provider&limit=100&search=" + encoded);
            JSONObject response = new JSONObject(json);
            JSONObject data = response.optJSONObject("data");
            if (data == null) data = response;
            JSONArray items = data.optJSONArray("items");
            if (items == null) items = data.optJSONArray("apps");
            if (items == null) items = response.optJSONArray("items");
            if (items == null) return;
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                String pkg = firstNonEmpty(
                        item.optString("package_name", ""),
                        item.optString("packageName", "")
                ).trim();
                if (!pkg.isEmpty()) packages.add(pkg);
            }
        } catch (Exception ignored) {
            // SDK discovery must continue offline or when the server is temporarily unavailable.
        }
    }

    private void addProviderCandidateFromComponent(Context context, PackageManager pm, ComponentName component,
                                                   Map<String, ProviderCandidate> result) {
        if (component == null || result == null) return;
        String key = component.flattenToShortString();
        if (key != null && result.containsKey(key)) return;

        ProviderCandidate provider = new ProviderCandidate();
        provider.packageName = component.getPackageName();
        provider.serviceName = component.getClassName();
        provider.component = component;
        provider.providerAuthority = readProviderAuthorityFromMetadata(context, pm, provider.packageName);
        if (provider.providerAuthority.isEmpty() && isKnownDemoComponent(component)) {
            provider.providerAuthority = SDK_DEMO_PROVIDER_AUTHORITY;
        }
        try {
            provider.appName = pm.getApplicationLabel(pm.getApplicationInfo(provider.packageName, 0)).toString();
        } catch (Exception ignored) {
            provider.appName = provider.packageName;
        }
        try {
            provider.signatureSha256 = getPackageSignatureSha256(context, provider.packageName);
        } catch (Exception ignored) {}
        if (provider.providerAuthority != null && !provider.providerAuthority.trim().isEmpty()) {
            try {
                provider.manifest = loadSdkManifest(context, provider.providerAuthority.trim());
                hydrateProviderFromManifest(provider);
            } catch (Exception ignored) {}
        }
        if (provider.manifest == null) {
            try {
                provider.manifest = loadSdkManifest(context, provider.component);
                hydrateProviderFromManifest(provider);
            } catch (Exception ignored) {}
        }
        if (provider.manifest == null && isKnownDemoComponent(component)) {
            try {
                provider.manifest = buildDemoProviderManifest();
                hydrateProviderFromManifest(provider);
            } catch (Exception ignored) {}
        }
        if (hasCapabilities(provider)) {
            result.put(providerKey(provider), provider);
        }
    }

    private void hydrateProviderFromManifest(ProviderCandidate provider) {
        if (provider == null || provider.manifest == null) return;
        String manifestName = firstNonEmpty(
                provider.manifest.optString("appName", ""),
                provider.manifest.optString("name", "")
        ).trim();
        if (!manifestName.isEmpty()) provider.appName = manifestName;
        JSONArray caps = provider.manifest.optJSONArray("capabilities");
        if (caps == null) caps = provider.manifest.optJSONArray("apis");
        if (caps != null) provider.capabilities = caps;
    }

    private static boolean hasCapabilities(ProviderCandidate provider) {
        return provider != null && provider.capabilities != null && provider.capabilities.length() > 0;
    }

    private static boolean isKnownDemoComponent(ComponentName component) {
        if (component == null || !SDK_DEMO_PACKAGE.equals(component.getPackageName())) return false;
        String cls = component.getClassName() == null ? "" : component.getClassName();
        return SDK_DEMO_SERVICE.equals(cls)
                || cls.endsWith(".DemoCapabilityService")
                || cls.endsWith("DemoCapabilityService");
    }

    private static JSONObject buildDemoProviderManifest() throws Exception {
        boolean zh = isZh();
        JSONObject manifest = new JSONObject();
        manifest.put("appName", zh ? "PandaGenie \u80fd\u529b\u6f14\u793a" : "PandaGenie Capability Demo");
        manifest.put("packageName", SDK_DEMO_PACKAGE);
        manifest.put("version", "1.0.0");
        manifest.put("description", zh
                ? "\u7528\u4e8e\u9a8c\u8bc1 PandaGenieSDK Activity\u3001Service\u3001ContentProvider\u3001Broadcast \u548c\u672c\u5730\u6570\u636e\u80fd\u529b\u7684\u6f14\u793a\u5e94\u7528\u3002"
                : "Demo app for PandaGenieSDK Activity, Service, ContentProvider, Broadcast and local data capabilities.");
        manifest.put("category", "sdk_demo");
        JSONArray capabilities = new JSONArray();
        capabilities.put(demoCapability("demo.echo", "\u56de\u663e\u6587\u672c", "Echo text", "query", "service", "echo", "\u56de\u663e", "\u6587\u672c"));
        capabilities.put(demoCapability("demo.open_activity", "\u6253\u5f00\u6f14\u793a\u9875\u9762", "Open demo page", "open_ui", "open_ui", "demo", "\u6253\u5f00", "\u6f14\u793a\u9875"));
        capabilities.put(demoCapability("demo.open_detail_page", "\u6253\u5f00\u8be6\u60c5\u9875", "Open detail page", "open_ui", "open_ui", "detail", "\u8be6\u60c5"));
        capabilities.put(demoCapability("demo.provider_rows", "\u8bfb\u53d6 Provider \u6570\u636e", "Read Provider data", "provider", "data_read", "provider", "read", "\u8bfb\u53d6", "\u6570\u636e"));
        capabilities.put(demoCapability("demo.provider_search", "\u641c\u7d22 Provider \u6570\u636e", "Search Provider data", "provider", "data_read", "provider", "search", "\u641c\u7d22"));
        capabilities.put(demoCapability("demo.save_note", "\u4fdd\u5b58\u672c\u5730\u4fbf\u7b7e", "Save local note", "mutation", "data_write", "note", "save", "\u4fdd\u5b58"));
        capabilities.put(demoCapability("demo.read_note", "\u8bfb\u53d6\u672c\u5730\u4fbf\u7b7e", "Read local note", "query", "data_read", "note", "read", "\u8bfb\u53d6"));
        capabilities.put(demoCapability("demo.broadcast_ping", "\u53d1\u9001\u6f14\u793a\u5e7f\u64ad", "Send demo broadcast", "broadcast", "broadcast", "\u5e7f\u64ad", "ping"));
        capabilities.put(demoCapability("demo.broadcast_status", "\u53d1\u9001\u72b6\u6001\u5e7f\u64ad", "Send status broadcast", "event", "broadcast", "status", "\u72b6\u6001"));
        capabilities.put(demoCapability("demo.long_task", "\u6267\u884c\u540e\u53f0\u4efb\u52a1", "Run background task", "service", "service", "task", "\u540e\u53f0"));
        manifest.put("capabilities", capabilities);
        return manifest;
    }

    private static JSONObject demoCapability(String id, String titleZh, String titleEn, String kind, String... tags) throws Exception {
        boolean zh = isZh();
        JSONObject cap = new JSONObject();
        cap.put("id", id);
        cap.put("title", zh ? titleZh : titleEn);
        cap.put("title_zh", titleZh);
        cap.put("title_en", titleEn);
        cap.put("description", zh ? titleZh : titleEn);
        cap.put("kind", kind);
        cap.put("risk", "medium");
        cap.put("tags", jsonArray(tags));
        cap.put("input_schema", new JSONObject().put("type", "object").put("properties", new JSONObject()));
        return cap;
    }

    private static JSONArray jsonArray(String... values) {
        JSONArray array = new JSONArray();
        if (values == null) return array;
        for (String value : values) array.put(value);
        return array;
    }

    private static boolean looksLikeDemoTarget(String text) {
        String n = normalizeCapabilityText(text);
        return looksLikeDemoApp(text)
                || n.contains("pandagenie\u80fd\u529b\u6f14\u793a")
                || n.contains("pandageniesdk\u6f14\u793a")
                || n.contains("sdk\u6f14\u793a")
                || n.contains("\u80fd\u529b\u6f14\u793a")
                || n.contains("\u6f14\u793a")
                || n.contains("pandagenie能力演示")
                || n.contains("pandageniesdk演示")
                || n.contains("sdk演示")
                || n.contains("能力演示")
                || n.contains("演示")
                || n.contains("demo");
    }

    private JSONObject loadSdkManifest(Context context, ComponentName component) throws Exception {
        if (context != null && component != null) {
            String authority = readProviderAuthorityFromMetadata(context, context.getPackageManager(), component.getPackageName());
            if (authority.isEmpty() && isKnownDemoComponent(component)) authority = SDK_DEMO_PROVIDER_AUTHORITY;
            if (!authority.isEmpty()) {
                try {
                    JSONObject manifest = loadSdkManifest(context, authority);
                    if (manifest != null) return manifest;
                } catch (Exception ignored) {
                    // Older SDK builds exposed a bound service; keep that as a compatibility path.
                }
            }
        }
        SdkResponse response = sendSdkMessage(context, component, MSG_GET_MANIFEST, new Bundle());
        if (response.what != MSG_RESULT || response.data == null) return null;
        String manifestJson = response.data.getString(KEY_MANIFEST_JSON, "");
        if (manifestJson == null || manifestJson.trim().isEmpty()) return null;
        return new JSONObject(manifestJson);
    }

    private JSONObject loadSdkManifest(Context context, String authority) throws Exception {
        SdkResponse response = sendSdkProviderMessage(context, authority, MSG_GET_MANIFEST, new Bundle());
        if (response.what != MSG_RESULT || response.data == null) return null;
        String manifestJson = response.data.getString(KEY_MANIFEST_JSON, "");
        if (manifestJson == null || manifestJson.trim().isEmpty()) return null;
        return new JSONObject(manifestJson);
    }

    private CapabilityMatch findBestCapability(List<ProviderCandidate> providers, String target, String capabilityId,
                                               String capabilityKeyword, String userText) {
        CapabilityMatch best = null;
        for (ProviderCandidate provider : providers) {
            int providerScore = scoreProvider(provider, target);
            for (int i = 0; i < provider.capabilities.length(); i++) {
                JSONObject cap = provider.capabilities.optJSONObject(i);
                if (cap == null) continue;
                int score = providerScore + scoreCapability(cap, capabilityId, capabilityKeyword, userText);
                if (best == null || score > best.score) {
                    best = new CapabilityMatch();
                    best.provider = provider;
                    best.capability = cap;
                    best.score = score;
                }
            }
        }
        return best;
    }

    private boolean isWeakCapabilityMatch(CapabilityMatch match) {
        return match == null || match.provider == null || match.capability == null || match.score < 40;
    }

    private void mergeProviderCandidates(Map<String, ProviderCandidate> merged, List<ProviderCandidate> providers) {
        if (merged == null || providers == null) return;
        for (ProviderCandidate provider : providers) {
            if (provider == null) continue;
            merged.put(providerKey(provider), provider);
        }
    }

    private String providerKey(ProviderCandidate provider) {
        if (provider == null) return "";
        String pkg = provider.packageName == null ? "" : provider.packageName;
        String authority = provider.providerAuthority == null ? "" : provider.providerAuthority.trim();
        if (!authority.isEmpty()) return pkg + "@" + authority;
        String svc = provider.serviceName == null ? "" : provider.serviceName;
        return pkg + "/" + svc;
    }

    private int scoreProvider(ProviderCandidate provider, String target) {
        if (provider == null || target == null || target.trim().isEmpty()) return 0;
        String t = target.trim().toLowerCase(Locale.ROOT);
        String pkg = provider.packageName == null ? "" : provider.packageName.toLowerCase(Locale.ROOT);
        String app = provider.appName == null ? "" : provider.appName.toLowerCase(Locale.ROOT);
        String manifest = provider.manifest == null ? "" : provider.manifest.toString().toLowerCase(Locale.ROOT);
        if (pkg.equals(t)) return 100;
        if (app.equals(t)) return 90;
        int score = 0;
        if (pkg.contains(t)) score += 60;
        if (app.contains(t)) score += 70;
        if (manifest.contains(t)) score += 35;
        if (looksLikeDemoOpen(target) && (pkg.contains("demo") || app.toLowerCase(Locale.ROOT).contains("demo")
                || app.contains("\u6f14\u793a"))) {
            score += 80;
        }
        if (looksLikeDemoApp(target) && (pkg.contains("sdkdemo") || pkg.contains("provider")
                || manifest.contains("demo") || manifest.contains("\u80fd\u529b\u6f14\u793a"))) {
            score += 120;
        }
        return score;
    }

    private int scoreCapability(JSONObject capability, String capabilityId, String capabilityKeyword, String userText) {
        String id = capability.optString("id", "").toLowerCase(Locale.ROOT);
        String normalizedId = normalizeCapabilityText(id);
        String title = capabilityTitle(capability).toLowerCase(Locale.ROOT);
        String kind = capability.optString("kind", "").toLowerCase(Locale.ROOT);
        String haystack = capability.toString().toLowerCase(Locale.ROOT);
        String combinedText = (userText == null ? "" : userText) + " " + (capabilityKeyword == null ? "" : capabilityKeyword);
        int score = 0;
        if (capabilityId != null && !capabilityId.trim().isEmpty()) {
            String cid = capabilityId.trim().toLowerCase(Locale.ROOT);
            if (id.equals(cid)) score += 220;
            else if (id.contains(cid) || cid.contains(id)) score += 120;
        }
        if (capabilityKeyword != null && !capabilityKeyword.trim().isEmpty()) {
            String kw = capabilityKeyword.trim().toLowerCase(Locale.ROOT);
            if (title.contains(kw)) score += 120;
            if (id.contains(kw)) score += 80;
            if (haystack.contains(kw)) score += 100;
        }
        if (looksLikeProviderRead(combinedText)) {
            if ("demo.provider_rows".equals(id) || normalizedId.contains("providerrows")) score += 260;
            if (kind.contains("provider")) score += 100;
            if (haystack.contains("data_read")) score += 80;
            if (title.contains("provider") && (title.contains("\u8bfb\u53d6") || title.contains("\u5217\u8868"))) score += 120;
        }
        if (looksLikeProviderSearch(combinedText)) {
            if ("demo.provider_search".equals(id) || normalizedId.contains("providersearch")) score += 260;
            if (kind.contains("provider")) score += 100;
        }
        if (looksLikeBroadcast(combinedText)) {
            if (id.contains("broadcast")) score += 220;
            if (kind.contains("broadcast") || haystack.contains("broadcast")) score += 100;
        }
        if (looksLikeEcho(combinedText)) {
            if (id.contains("echo")) score += 220;
        }
        if (looksLikeNoteSave(combinedText)) {
            if (id.contains("save_note") || normalizedId.contains("savenote")) score += 220;
        }
        if (looksLikeNoteRead(combinedText)) {
            if (id.contains("read_note") || normalizedId.contains("readnote")) score += 220;
        }
        if (looksLikeDetailOpen(combinedText)) {
            if ("demo.open_detail_page".equals(id) || normalizedId.contains("opendetailpage")) score += 220;
            if (kind.contains("open_ui")) score += 80;
        }
        if (looksLikeDemoOpen(combinedText)) {
            if ("demo.open_activity".equals(id) || id.contains("open_activity")) score += 240;
            if (kind.contains("open_ui")) score += 120;
            if (title.contains("\u6f14\u793a") || haystack.contains("demo page") || haystack.contains("\u6f14\u793a")) score += 90;
        }
        if ((userText != null && (userText.contains("\u6253\u5f00") || userText.toLowerCase(Locale.ROOT).contains("open")))
                && kind.contains("open_ui")) {
            score += 60;
        }
        return score;
    }

    private String buildCapabilityMatchError(List<ProviderCandidate> providers, String target, String capabilityId, String capabilityKeyword) {
        boolean zh = isZh();
        if (providers == null || providers.isEmpty()) {
            return zh
                    ? "\u672a\u53d1\u73b0\u672c\u673a SDK \u80fd\u529b\u670d\u52a1\u3002\u8bf7\u786e\u8ba4\u76ee\u6807\u5e94\u7528\u5df2\u5b89\u88c5\uff0c\u5e76\u4f7f\u7528\u6700\u65b0\u7248\u672c\u7684 PandaGenie SDK \u66b4\u9732\u80fd\u529b\u3002"
                    : "No local SDK capability service was discovered. Make sure the target app is installed and exposes capabilities with the latest PandaGenie SDK.";
        }
        int capCount = 0;
        StringBuilder samples = new StringBuilder();
        for (ProviderCandidate provider : providers) {
            if (provider == null || provider.capabilities == null) continue;
            capCount += provider.capabilities.length();
            if (samples.length() > 0) samples.append("; ");
            samples.append(firstNonEmpty(provider.appName, provider.packageName));
            samples.append(": ");
            int limit = Math.min(3, provider.capabilities.length());
            for (int i = 0; i < limit; i++) {
                if (i > 0) samples.append(", ");
                JSONObject cap = provider.capabilities.optJSONObject(i);
                samples.append(cap == null ? "" : capabilityTitle(cap));
            }
        }
        String request = firstNonEmpty(capabilityId, capabilityKeyword, target);
        if (zh) {
            return "\u5df2\u53d1\u73b0 " + providers.size() + " \u4e2a SDK \u5e94\u7528\u3001" + capCount
                    + " \u9879\u80fd\u529b\uff0c\u4f46\u6ca1\u6709\u5339\u914d\u5230\u672c\u6b21\u8bf7\u6c42"
                    + (request.isEmpty() ? "\u3002" : "\uff1a" + request + "\u3002")
                    + "\u53ef\u7528\u80fd\u529b\u793a\u4f8b\uff1a" + samples;
        }
        return "Discovered " + providers.size() + " SDK app(s) and " + capCount
                + " capability item(s), but none matched"
                + (request.isEmpty() ? "." : ": " + request + ".")
                + " Available examples: " + samples;
    }

    private JSONObject buildInvokeParams(JSONObject params, JSONObject capability) throws Exception {
        JSONObject out = new JSONObject();
        String paramsJson = params.optString("paramsJson", "").trim();
        if (!paramsJson.isEmpty()) {
            try {
                Object parsed = new org.json.JSONTokener(paramsJson).nextValue();
                if (parsed instanceof JSONObject) out = (JSONObject) parsed;
            } catch (Exception ignored) {}
        }
        String[] keys = new String[]{"title", "message", "text", "keyword", "query", "value", "detailId", "source", "note", "status"};
        for (String key : keys) {
            if (params.has(key) && !params.optString(key, "").trim().isEmpty()) {
                out.put(key, params.optString(key, ""));
            }
        }
        if (!out.has("source")) out.put("source", "PandaGenie");
        String capId = capability.optString("id", "");
        if ("demo.open_activity".equals(capId)) {
            if (!out.has("title")) out.put("title", isZh() ? "PandaGenie SDK \u6f14\u793a" : "PandaGenie SDK Demo");
            if (!out.has("message")) out.put("message", isZh()
                    ? "\u7531 PandaGenie \u901a\u8fc7 SDK \u8c03\u7528\u6253\u5f00\u7684\u6f14\u793a\u9875\u9762"
                    : "Opened by PandaGenie through the SDK");
        }
        return out;
    }

    private SdkResponse sendSdkCall(Context context, ProviderCandidate provider, int what, Bundle data) throws Exception {
        if (provider != null && provider.providerAuthority != null && !provider.providerAuthority.trim().isEmpty()) {
            return sendSdkProviderMessage(context, provider.providerAuthority.trim(), what, data);
        }
        if (provider != null && provider.component != null) {
            return sendSdkMessage(context, provider.component, what, data);
        }
        throw new IOException(isZh()
                ? "\u76ee\u6807\u5e94\u7528\u6ca1\u6709\u66b4\u9732 SDK Provider\uff0c\u65e0\u6cd5\u8c03\u7528\u3002"
                : "Target app does not expose an SDK Provider.");
    }

    private SdkResponse sendSdkProviderMessage(Context context, String authority, int what, Bundle data) throws Exception {
        if (context == null || authority == null || authority.trim().isEmpty()) {
            throw new IOException("SDK provider authority is empty");
        }
        String method = what == MSG_GET_MANIFEST ? METHOD_GET_MANIFEST : METHOD_INVOKE;
        Bundle responseBundle = context.getContentResolver().call(
                Uri.parse("content://" + authority.trim()),
                method,
                null,
                data == null ? new Bundle() : data
        );
        if (responseBundle == null) {
            throw new IOException("SDK provider returned empty response: " + authority);
        }
        SdkResponse response = new SdkResponse();
        String error = responseBundle.getString(KEY_ERROR, "");
        response.what = error == null || error.trim().isEmpty() ? MSG_RESULT : MSG_ERROR;
        response.data = responseBundle;
        return response;
    }

    private SdkResponse sendSdkMessage(Context context, ComponentName component, int what, Bundle data) throws Exception {
        final CountDownLatch connectedLatch = new CountDownLatch(1);
        final CountDownLatch responseLatch = new CountDownLatch(1);
        final AtomicReference<Messenger> serviceRef = new AtomicReference<>();
        final AtomicReference<SdkResponse> responseRef = new AtomicReference<>();
        final AtomicReference<Exception> errorRef = new AtomicReference<>();
        HandlerThread thread = new HandlerThread("PandaGenieSdkCall");
        thread.start();
        Messenger replyMessenger = new Messenger(new Handler(thread.getLooper()) {
            @Override
            public void handleMessage(Message msg) {
                SdkResponse response = new SdkResponse();
                response.what = msg.what;
                response.data = msg.getData();
                responseRef.set(response);
                responseLatch.countDown();
            }
        });

        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                serviceRef.set(new Messenger(service));
                connectedLatch.countDown();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                serviceRef.set(null);
            }
        };

        Intent intent = new Intent(ACTION_CAPABILITY_SERVICE);
        intent.setComponent(component);
        boolean bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        try {
            if (!bound) throw new IOException("Unable to bind SDK capability service: " + component.flattenToShortString());
            if (!connectedLatch.await(SDK_BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw new IOException("SDK capability service bind timeout: " + component.flattenToShortString());
            }
            Messenger service = serviceRef.get();
            if (service == null) throw new IOException("SDK capability service disconnected: " + component.flattenToShortString());
            Message request = Message.obtain(null, what);
            request.replyTo = replyMessenger;
            request.setData(data == null ? new Bundle() : data);
            try {
                service.send(request);
            } catch (Exception e) {
                errorRef.set(e);
                throw e;
            }
            if (!responseLatch.await(SDK_BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw new IOException("SDK capability invocation timeout: " + component.flattenToShortString());
            }
            SdkResponse response = responseRef.get();
            if (response == null) throw new IOException("SDK capability returned empty response");
            return response;
        } finally {
            try { context.unbindService(connection); } catch (Exception ignored) {}
            if (Build.VERSION.SDK_INT >= 18) thread.quitSafely();
            else thread.quit();
        }
    }

    private String getOwnSignatureSha256(Context context) throws Exception {
        return getPackageSignatureSha256(context, context.getPackageName());
    }

    private String getPackageSignatureSha256(Context context, String packageName) throws Exception {
        PackageManager pm = context.getPackageManager();
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= 28) {
            PackageInfo info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES);
            if (info.signingInfo == null) return "";
            signatures = info.signingInfo.hasMultipleSigners()
                    ? info.signingInfo.getApkContentsSigners()
                    : info.signingInfo.getSigningCertificateHistory();
        } else {
            PackageInfo info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES);
            signatures = info.signatures;
        }
        if (signatures == null || signatures.length == 0) return "";
        return sha256(signatures[0].toByteArray());
    }

    private static String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(bytes);
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) sb.append(String.format(Locale.US, "%02X", b & 0xFF));
        return sb.toString();
    }

    private String formatInvokeCallableAppDisplay(JSONObject output) throws Exception {
        boolean zh = isZh();
        JSONObject result = output.optJSONObject("result");
        String status = result != null && result.optBoolean("opened", false)
                ? (zh ? "\u5df2\u6253\u5f00" : "Opened")
                : (zh ? "\u5df2\u5b8c\u6210" : "Done");
        String[] headers = zh
                ? new String[]{"\u5e94\u7528", "\u80fd\u529b", "\u72b6\u6001", "\u8fd4\u56de"}
                : new String[]{"App", "Capability", "Status", "Result"};
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{
                mdCell(output.optString("appName", "")),
                mdCell(output.optString("capabilityTitle", output.optString("capabilityId", ""))),
                status,
                mdCell(summarizeInvokeResult(result, zh))
        });
        return pgTable(zh ? "\u53ef\u4ea4\u4e92\u5e94\u7528\u80fd\u529b\u5df2\u8c03\u7528" : "Interactable App Capability Invoked", headers, rows);
    }

    private String formatInvokeCallableAppHtml(JSONObject output) {
        return formatInvokeCallableAppHtml(output, false);
    }

    private String formatInvokeCallableAppHtmlFull(JSONObject output) {
        return formatInvokeCallableAppHtml(output, true);
    }

    private String formatInvokeCallableAppHtml(JSONObject output, boolean includeRaw) {
        boolean zh = isZh();
        JSONObject result = output.optJSONObject("result");
        String status = result != null && result.optBoolean("opened", false)
                ? (zh ? "\u5df2\u6253\u5f00" : "Opened")
                : (zh ? "\u5df2\u5b8c\u6210" : "Done");
        String body = HtmlOutputHelper.keyValue(new String[][]{
                {zh ? "\u5e94\u7528" : "App", output.optString("appName", "")},
                {zh ? "\u80fd\u529b" : "Capability", output.optString("capabilityTitle", output.optString("capabilityId", ""))},
                {zh ? "\u7c7b\u578b" : "Kind", output.optString("kind", "")},
                {zh ? "\u72b6\u6001" : "Status", status}
        });
        if (result != null && result.length() > 0) {
            String friendly = formatInvokeResultFriendlyHtml(result, zh);
            if (!friendly.isEmpty()) {
                body += friendly;
            }
            if (includeRaw) {
                body += HtmlOutputHelper.section(
                        zh ? "\u5b8c\u6574\u8fd4\u56de\u6570\u636e" : "Full returned data",
                        HtmlOutputHelper.pre(prettyJson(result))
                );
            }
        }
        return HtmlOutputHelper.card("\uD83E\uDDE9",
                zh ? "\u53ef\u4ea4\u4e92\u5e94\u7528\u80fd\u529b" : "Interactable App Capability",
                body);
    }

    private String prettyJson(JSONObject obj) {
        if (obj == null) return "";
        try {
            return obj.toString(2);
        } catch (Exception ignored) {
            return obj.toString();
        }
    }

    private String prettyJson(JSONArray arr) {
        if (arr == null) return "";
        try {
            return arr.toString(2);
        } catch (Exception ignored) {
            return arr.toString();
        }
    }

    private String summarizeInvokeResult(JSONObject result, boolean zh) {
        if (result == null || result.length() == 0) return "";
        String message = result.optString("message", "").trim();
        if (result.optBoolean("opened", false)) {
            return !message.isEmpty()
                    ? message
                    : (zh ? "\u5df2\u6253\u5f00\u76ee\u6807\u9875\u9762" : "Opened target page");
        }
        String note = result.optString("note", "").trim();
        if (!note.isEmpty()) {
            if (result.optBoolean("saved", false)) {
                return (zh ? "\u5df2\u4fdd\u5b58\uff1a" : "Saved: ") + note;
            }
            return (zh ? "\u4fbf\u7b7e\u5185\u5bb9\uff1a" : "Note: ") + note;
        }
        JSONArray rows = optResultRows(result);
        if (rows != null) {
            StringBuilder sb = new StringBuilder();
            sb.append(zh ? "\u5171 " : "").append(rows.length()).append(zh ? " \u6761\u6570\u636e" : " rows");
            int count = Math.min(rows.length(), 3);
            for (int i = 0; i < count; i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                String title = firstNonEmpty(row.optString("title", ""), row.optString("name", ""), row.optString("value", ""));
                if (!title.isEmpty()) {
                    sb.append(i == 0 ? "\uff1a" : "\uff1b").append(title);
                }
            }
            return sb.toString();
        }
        String raw = message;
        if (raw.trim().isEmpty()) raw = result.optString("raw", "");
        if (raw.trim().isEmpty()) raw = zh ? "\u5df2\u5b8c\u6210" : "Done";
        return raw;
    }

    private String formatInvokeResultFriendlyHtml(JSONObject result, boolean zh) {
        StringBuilder body = new StringBuilder();
        String message = result.optString("message", "").trim();
        if (result.optBoolean("opened", false)) {
            String text = message.isEmpty()
                    ? (zh ? "\u76ee\u6807\u9875\u9762\u5df2\u6253\u5f00\u3002" : "Target page opened.")
                    : message;
            body.append(HtmlOutputHelper.callout(zh ? "\u6267\u884c\u7ed3\u679c" : "Result", text, null));
        } else if (!message.isEmpty()) {
            body.append(HtmlOutputHelper.section(
                    zh ? "\u8bf4\u660e" : "Message",
                    HtmlOutputHelper.pre(message)
            ));
        }
        String note = result.optString("note", "").trim();
        if (!note.isEmpty()) {
            body.append(HtmlOutputHelper.section(
                    zh ? "\u4fbf\u7b7e\u5185\u5bb9" : "Note",
                    HtmlOutputHelper.pre(note)
            ));
        }
        JSONArray rows = optResultRows(result);
        if (rows != null && rows.length() > 0) {
            body.append(formatInvokeRowsHtml(rows, zh));
        }
        return body.toString();
    }

    private String formatInvokeRowsHtml(JSONArray rows, boolean zh) {
        StringBuilder body = new StringBuilder();
        int limit = Math.min(rows.length(), 8);
        for (int i = 0; i < limit; i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            String title = firstNonEmpty(
                    row.optString("title", ""),
                    row.optString("name", ""),
                    row.optString("label", ""),
                    "#" + firstNonEmpty(row.optString("_id", ""), row.optString("id", ""), String.valueOf(i + 1))
            );
            String meta = firstNonEmpty(
                    row.optString("type", ""),
                    row.optString("value", ""),
                    row.optString("key", "")
            );
            String text = firstNonEmpty(
                    row.optString("message", ""),
                    row.optString("content", ""),
                    row.optString("description", ""),
                    row.toString()
            );
            body.append(HtmlOutputHelper.item(title, meta, text));
        }
        if (rows.length() > limit) {
            body.append(HtmlOutputHelper.muted((zh ? "\u8fd8\u6709 " : "Plus ")
                    + (rows.length() - limit)
                    + (zh ? " \u6761\u6570\u636e\uff0c\u53ef\u5728\u5b8c\u6574\u8fd4\u56de\u6570\u636e\u4e2d\u67e5\u770b\u3002" : " more rows in full returned data.")));
        }
        return HtmlOutputHelper.section(
                zh ? "\u8bfb\u53d6\u5230\u7684\u6570\u636e" : "Rows",
                body.toString()
        );
    }

    private static JSONArray optResultRows(JSONObject result) {
        if (result == null) return null;
        String[] keys = {"rows", "items", "data", "records"};
        for (String key : keys) {
            JSONArray arr = result.optJSONArray(key);
            if (arr != null) return arr;
        }
        return null;
    }

    private static String httpGet(String urlStr) throws IOException {
        HttpURLConnection conn = null;
        InputStream stream = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(SDK_TIMEOUT_MS);
            conn.setReadTimeout(SDK_TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            String body = readAll(stream);
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + ": " + body);
            }
            return body;
        } finally {
            if (stream != null) {
                try { stream.close(); } catch (Exception ignored) {}
            }
            if (conn != null) conn.disconnect();
        }
    }

    private static String readAll(InputStream stream) throws IOException {
        if (stream == null) return "";
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        return sb.toString();
    }

    private static JSONArray parseExpectedApis(String raw) {
        if (raw == null || raw.trim().isEmpty()) return new JSONArray();
        try {
            Object parsed = new org.json.JSONTokener(raw).nextValue();
            if (parsed instanceof JSONArray) return (JSONArray) parsed;
            if (parsed instanceof JSONObject) {
                JSONArray arr = new JSONArray();
                arr.put(parsed);
                return arr;
            }
        } catch (Exception ignored) {}
        return new JSONArray();
    }

    private boolean isPackageInstalled(Context context, String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) return false;
        try {
            context.getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static String capabilityTitle(JSONObject cap) {
        String title = cap.optString("title", "").trim();
        if (!title.isEmpty()) return title;
        title = cap.optString("description", "").trim();
        if (!title.isEmpty()) return title;
        title = cap.optString("id", "").trim();
        return title.isEmpty() ? "—" : title;
    }

    private static String capabilitySummary(JSONArray caps, boolean zh, int max) {
        if (caps == null || caps.length() == 0) return zh ? "未填写能力说明" : "No capability details";
        StringBuilder sb = new StringBuilder();
        int count = Math.min(caps.length(), max);
        for (int i = 0; i < count; i++) {
            JSONObject cap = caps.optJSONObject(i);
            if (cap == null) continue;
            if (sb.length() > 0) sb.append(zh ? "、" : ", ");
            sb.append(capabilityTitle(cap));
        }
        if (caps.length() > count) {
            sb.append(zh ? (" 等 " + caps.length() + " 项") : (" and " + (caps.length() - count) + " more"));
        }
        return sb.toString();
    }

    private static String installedLabel(boolean installed, boolean zh) {
        if (zh) return installed ? "已安装" : "未安装";
        return installed ? "Installed" : "Not installed";
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

    private String formatCallableAppsDisplay(JSONObject obj) throws Exception {
        int count = obj.optInt("count", 0);
        JSONArray apps = obj.optJSONArray("apps");
        boolean zh = isZh();
        String title = zh
                ? ("🧩 可交互应用能力（共 " + count + " 个）")
                : ("🧩 Interactable App Capabilities (" + count + ")");
        String[] headers = zh
                ? new String[]{"应用", "状态", "包名", "可调用能力"}
                : new String[]{"App", "Status", "Package", "Capabilities"};
        List<String[]> rows = new ArrayList<>();
        if (apps != null) {
            for (int i = 0; i < apps.length(); i++) {
                JSONObject app = apps.optJSONObject(i);
                if (app == null) continue;
                JSONArray caps = app.optJSONArray("capabilities");
                rows.add(new String[]{
                        mdCell(app.optString("appName", "")),
                        mdCell(installedLabel(app.optBoolean("installed", false), zh)),
                        mdCell(app.optString("packageName", "")),
                        mdCell(capabilitySummary(caps, zh, 4))
                });
            }
        }
        return pgTable(title, headers, rows);
    }

    private String formatCallableAppsHtml(JSONObject obj) {
        boolean zh = isZh();
        JSONArray apps = obj.optJSONArray("apps");
        int count = obj.optInt("count", apps != null ? apps.length() : 0);
        if (apps == null || apps.length() == 0) {
            String text = zh
                    ? "服务端暂时没有返回已审核的可交互应用。可以稍后刷新，或让开发者先在官网提交应用并通过审核。"
                    : "No approved interactable apps were returned. Try again later or ask developers to submit and pass review first.";
            return HtmlOutputHelper.card("🧩", zh ? "可交互应用" : "Interactable Apps",
                    HtmlOutputHelper.callout(zh ? "暂无结果" : "No results", text, "warn"));
        }

        StringBuilder body = new StringBuilder();
        body.append(HtmlOutputHelper.muted(zh
                ? "来自 PandaGenie 服务端的已审核能力应用。"
                : "Approved capability apps from the PandaGenie server."));
        int limit = Math.min(apps.length(), 20);
        for (int i = 0; i < limit; i++) {
            JSONObject app = apps.optJSONObject(i);
            if (app == null) continue;
            JSONArray caps = app.optJSONArray("capabilities");
            String meta = installedLabel(app.optBoolean("installed", false), zh)
                    + " · " + app.optInt("capabilityCount", caps != null ? caps.length() : 0)
                    + (zh ? " 项能力 · " : " capabilities · ")
                    + app.optString("packageName", "—");
            String desc = app.optString("description", "").trim();
            String capText = (zh ? "能力：" : "Capabilities: ") + capabilitySummary(caps, zh, 5);
            body.append(HtmlOutputHelper.item(
                    app.optString("appName", zh ? "未命名应用" : "Unnamed app"),
                    meta,
                    desc.isEmpty() ? capText : (desc + "\n" + capText)
            ));
        }
        if (count > limit) {
            body.append(HtmlOutputHelper.muted((zh ? "仅显示前 " : "Showing first ") + limit
                    + (zh ? " 个，共 " : " of ") + count + (zh ? " 个。" : ".")));
        }
        return HtmlOutputHelper.card("🧩", zh ? "可交互应用" : "Interactable Apps", body.toString());
    }

    private String formatListAppsDisplay(JSONObject obj) throws Exception {
        int count = obj.optInt("count", 0);
        JSONArray apps = obj.optJSONArray("apps");
        boolean zh = isZh();
        String title = zh
                ? ("\ud83d\udcf1 \u5df2\u5b89\u88c5\u5e94\u7528\uff08\u5171 " + count + "\uff09")
                : ("\ud83d\udcf1 Installed Apps (" + count + " total)");
        String[] headers = zh
                ? new String[]{"\u540d\u79f0", "\u5305\u540d", "\u7248\u672c"}
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

    private String formatListAppsHtml(String output) throws Exception {
        boolean zh = isZh();
        JSONObject j = new JSONObject(output);
        JSONArray apps = j.optJSONArray("apps");
        if (apps == null) return "";
        java.util.List<String[]> rows = new java.util.ArrayList<>();
        int limit = Math.min(apps.length(), 30);
        for (int i = 0; i < limit; i++) {
            JSONObject app = apps.optJSONObject(i);
            if (app != null) {
                rows.add(new String[]{
                        app.optString("appName", "—"),
                        app.optString("versionName", "—"),
                        app.optString("packageName", "—")
                });
            }
        }
        String body = HtmlOutputHelper.table(
                new String[]{zh ? "名称" : "Name", zh ? "版本" : "Version", zh ? "包名" : "Package"},
                rows
        );
        int total = j.optInt("count", apps.length());
        if (total > limit) {
            body += HtmlOutputHelper.muted((zh ? "显示前 " : "Showing first ") + limit
                    + (zh ? " 个，共 " : " of ") + total + (zh ? " 个" : ""));
        }
        return HtmlOutputHelper.card("📱", zh ? "已安装应用" : "Installed Apps", body);
    }

    private String formatListRecentAppsHtml(String output) throws Exception {
        boolean zh = isZh();
        JSONObject j = new JSONObject(output);
        JSONArray apps = j.optJSONArray("apps");
        if (apps == null) return "";
        java.util.List<String[]> rows = new java.util.ArrayList<>();
        int limit = Math.min(apps.length(), 30);
        for (int i = 0; i < limit; i++) {
            JSONObject app = apps.optJSONObject(i);
            if (app != null) {
                rows.add(new String[]{
                        app.optString("appName", "—"),
                        app.optString("firstInstallTime", "—"),
                        app.optString("lastUpdateTime", "—"),
                        app.optString("versionName", "—")
                });
            }
        }
        String body = HtmlOutputHelper.table(
                new String[]{
                        zh ? "名称" : "Name",
                        zh ? "安装时间" : "Installed",
                        zh ? "最后更新" : "Updated",
                        zh ? "版本" : "Version"
                },
                rows
        );
        int total = j.optInt("count", apps.length());
        if (total > limit) {
            body += HtmlOutputHelper.muted((zh ? "显示前 " : "Showing first ") + limit
                    + (zh ? " 个，共 " : " of ") + total + (zh ? " 个" : ""));
        }
        int days = j.optInt("days", 30);
        String title = zh ? ("最近 " + days + " 天的应用") : ("Apps in last " + days + " days");
        return HtmlOutputHelper.card("📱", title, body);
    }

    private String formatGetAppInfoHtml(String output) throws Exception {
        boolean zh = isZh();
        JSONObject j = new JSONObject(output);
        return HtmlOutputHelper.card("📦", j.optString("appName", "App"),
                HtmlOutputHelper.keyValue(new String[][]{
                        {zh ? "包名" : "Package", j.optString("packageName", "—")},
                        {zh ? "版本" : "Version", j.optString("versionName", "—") + " (" + j.optInt("versionCode", 0) + ")"},
                        {zh ? "安装时间" : "Installed", j.optString("firstInstallTime", "—")},
                        {zh ? "更新时间" : "Updated", j.optString("lastUpdateTime", "—")},
                        {zh ? "大小" : "Size", j.optString("apkSize", "—")},
                        {zh ? "系统应用" : "System", j.optBoolean("isSystemApp") ? (zh ? "是" : "Yes") : (zh ? "否" : "No")}
                })
        );
    }

    private String formatRecentAppsDisplay(JSONObject obj) throws Exception {
        int count = obj.optInt("count", 0);
        int days = obj.optInt("days", 30);
        JSONArray apps = obj.optJSONArray("apps");
        boolean zh = isZh();
        String title = zh
                ? ("\ud83d\udcf1 \u8fd1 " + days + " \u5929\u5b89\u88c5/\u66f4\u65b0\u7684\u5e94\u7528\uff08\u5171 " + count + "\uff09")
                : ("\ud83d\udcf1 Apps installed/updated in last " + days + " days (" + count + " total)");
        String[] headers = zh
                ? new String[]{"\u540d\u79f0", "\u5b89\u88c5\u65f6\u95f4", "\u6700\u540e\u66f4\u65b0", "\u7248\u672c"}
                : new String[]{"Name", "Installed", "Updated", "Version"};
        List<String[]> rowList = new ArrayList<>();
        if (apps != null) {
            for (int i = 0; i < apps.length(); i++) {
                JSONObject app = apps.getJSONObject(i);
                rowList.add(new String[]{
                        mdCell(app.optString("appName", "")),
                        mdCell(app.optString("firstInstallTime", "")),
                        mdCell(app.optString("lastUpdateTime", "")),
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
        return ok(output, displayText, null);
    }

    private String ok(String output, String displayText, String displayHtml) throws Exception {
        return ok(output, displayText, displayHtml, null);
    }

    private String ok(String output, String displayText, String displayHtml, String displayHtmlFull) throws Exception {
        JSONObject r = new JSONObject().put("success", true).put("output", output);
        if (displayText != null && !displayText.isEmpty()) r.put("_displayText", displayText);
        if (displayHtml != null && !displayHtml.isEmpty()) r.put("_displayHtml", displayHtml);
        if (displayHtmlFull != null && !displayHtmlFull.isEmpty()) r.put("_displayHtmlFull", displayHtmlFull);
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
