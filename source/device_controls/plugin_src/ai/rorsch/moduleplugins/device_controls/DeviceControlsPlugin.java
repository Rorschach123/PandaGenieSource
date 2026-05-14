package ai.rorsch.moduleplugins.device_controls;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.provider.Settings;

import ai.rorsch.pandagenie.module.runtime.HtmlOutputHelper;
import ai.rorsch.pandagenie.module.runtime.ModulePlugin;

import org.json.JSONObject;

import java.util.Locale;

public class DeviceControlsPlugin implements ModulePlugin {

    private static boolean isZh() {
        try {
            return Locale.getDefault().getLanguage().toLowerCase(Locale.ROOT).startsWith("zh");
        } catch (Exception ignored) {
            return false;
        }
    }

    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        JSONObject params = new JSONObject(emptyJson(paramsJson));
        switch (action) {
            case "getBrightness":
                return getBrightness(context);
            case "setBrightness":
                return setBrightness(context, params.optInt("level", 50), params.optBoolean("disableAuto", true));
            case "increaseBrightness":
                return adjustBrightness(context, Math.max(1, params.optInt("delta", 10)), params.optBoolean("disableAuto", true));
            case "decreaseBrightness":
                return adjustBrightness(context, -Math.max(1, params.optInt("delta", 10)), params.optBoolean("disableAuto", true));
            case "getVolume":
                return getVolume(context, params.optString("stream", "media"));
            case "setVolume":
                return setVolume(context, params.optString("stream", "media"), params.optInt("level", 50), params.optBoolean("showUi", false));
            case "increaseVolume":
                return adjustVolume(context, params.optString("stream", "media"), Math.max(1, params.optInt("delta", 10)), params.optBoolean("showUi", false));
            case "decreaseVolume":
                return adjustVolume(context, params.optString("stream", "media"), -Math.max(1, params.optInt("delta", 10)), params.optBoolean("showUi", false));
            case "getControlSummary":
                return getControlSummary(context);
            case "getWirelessStatus":
                return getWirelessStatus(context);
            case "openWifiPanel":
                return openWifiPanel(context);
            case "openInternetPanel":
                return openInternetPanel(context);
            case "openWirelessSettings":
                return openWirelessSettings(context);
            case "openBluetoothSettings":
                return openBluetoothSettings(context);
            case "requestEnableBluetooth":
                return requestEnableBluetooth(context);
            case "setWifiEnabled":
                return setWifiEnabled(context, params.optBoolean("enabled", true));
            case "setBluetoothEnabled":
                return setBluetoothEnabled(context, params.optBoolean("enabled", true));
            default:
                return error("Unsupported action: " + action);
        }
    }

    private String getBrightness(Context context) throws Exception {
        JSONObject out = readBrightness(context);
        String title = isZh() ? "屏幕亮度" : "Screen Brightness";
        String text = isZh()
                ? "当前亮度 " + out.optInt("percent") + "%，模式：" + out.optString("modeLabel")
                : "Brightness " + out.optInt("percent") + "%, mode: " + out.optString("modeLabel");
        return ok(out, text, brightnessHtml(title, out));
    }

    private String adjustBrightness(Context context, int delta, boolean disableAuto) throws Exception {
        JSONObject current = readBrightness(context);
        int target = clamp(current.optInt("percent") + delta, 0, 100);
        return setBrightness(context, target, disableAuto);
    }

    private String setBrightness(Context context, int level, boolean disableAuto) throws Exception {
        if (!canWriteSettings(context)) {
            String msg = isZh()
                    ? "需要先授权 PandaGenie 修改系统设置，授权后才能调节屏幕亮度。"
                    : "PandaGenie needs Modify system settings permission before changing brightness.";
            String html = HtmlOutputHelper.card("☀️", isZh() ? "需要系统设置权限" : "Permission Required",
                    HtmlOutputHelper.badge(isZh() ? "未授权" : "Not authorized", "orange") + HtmlOutputHelper.p(msg));
            return error(msg, msg, html);
        }

        int percent = clamp(level, 0, 100);
        int raw = percentToBrightness(percent);
        if (disableAuto) {
            Settings.System.putInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        }
        Settings.System.putInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, raw);

        JSONObject out = readBrightness(context);
        out.put("requestedPercent", percent);
        out.put("rawValue", raw);
        out.put("changed", true);
        String text = isZh() ? "已将屏幕亮度调到 " + out.optInt("percent") + "%" : "Brightness set to " + out.optInt("percent") + "%";
        return ok(out, text, brightnessHtml(isZh() ? "亮度已调整" : "Brightness Updated", out));
    }

    private JSONObject readBrightness(Context context) throws Exception {
        int raw = Settings.System.getInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 125);
        int mode = Settings.System.getInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        int percent = Math.round(raw * 100f / 255f);
        JSONObject out = new JSONObject();
        out.put("percent", clamp(percent, 0, 100));
        out.put("rawValue", raw);
        out.put("mode", mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC ? "automatic" : "manual");
        out.put("modeLabel", mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                ? (isZh() ? "自动" : "automatic")
                : (isZh() ? "手动" : "manual"));
        out.put("canWriteSettings", canWriteSettings(context));
        return out;
    }

    private String getVolume(Context context, String streamName) throws Exception {
        AudioManager audio = audioManager(context);
        int stream = streamType(streamName);
        JSONObject out = readVolume(audio, stream, streamLabel(streamName));
        String text = isZh()
                ? out.optString("streamLabel") + "音量 " + out.optInt("percent") + "%"
                : out.optString("streamLabel") + " volume " + out.optInt("percent") + "%";
        return ok(out, text, volumeHtml(isZh() ? "音量状态" : "Volume Status", out));
    }

    private String setVolume(Context context, String streamName, int level, boolean showUi) throws Exception {
        AudioManager audio = audioManager(context);
        int stream = streamType(streamName);
        int percent = clamp(level, 0, 100);
        int max = audio.getStreamMaxVolume(stream);
        int target = Math.round(max * percent / 100f);
        int flags = showUi ? AudioManager.FLAG_SHOW_UI : 0;
        audio.setStreamVolume(stream, target, flags);
        JSONObject out = readVolume(audio, stream, streamLabel(streamName));
        out.put("requestedPercent", percent);
        out.put("changed", true);
        String text = isZh()
                ? "已将" + out.optString("streamLabel") + "音量调到 " + out.optInt("percent") + "%"
                : out.optString("streamLabel") + " volume set to " + out.optInt("percent") + "%";
        return ok(out, text, volumeHtml(isZh() ? "音量已调整" : "Volume Updated", out));
    }

    private String adjustVolume(Context context, String streamName, int delta, boolean showUi) throws Exception {
        AudioManager audio = audioManager(context);
        int stream = streamType(streamName);
        JSONObject current = readVolume(audio, stream, streamLabel(streamName));
        int target = clamp(current.optInt("percent") + delta, 0, 100);
        return setVolume(context, streamName, target, showUi);
    }

    private String getControlSummary(Context context) throws Exception {
        JSONObject brightness = readBrightness(context);
        AudioManager audio = audioManager(context);
        JSONObject volume = readVolume(audio, AudioManager.STREAM_MUSIC, streamLabel("media"));
        JSONObject wireless = readWirelessStatus(context);
        JSONObject wifi = wireless.optJSONObject("wifi");
        JSONObject bluetooth = wireless.optJSONObject("bluetooth");
        JSONObject out = new JSONObject();
        out.put("brightness", brightness);
        out.put("mediaVolume", volume);
        out.put("wireless", wireless);
        String html = HtmlOutputHelper.card("🎛️", isZh() ? "设备控制状态" : "Device Controls",
                HtmlOutputHelper.metricGrid(new String[][]{
                        {brightness.optInt("percent") + "%", isZh() ? "亮度" : "Brightness"},
                        {volume.optInt("percent") + "%", isZh() ? "媒体音量" : "Media Volume"},
                        {wifiStateLabel(wifi), "Wi-Fi"},
                        {bluetoothStateLabel(bluetooth), isZh() ? "蓝牙" : "Bluetooth"}
                }) + HtmlOutputHelper.keyValue(new String[][]{
                        {isZh() ? "亮度模式" : "Brightness mode", brightness.optString("modeLabel")},
                        {isZh() ? "亮度授权" : "Brightness permission", brightness.optBoolean("canWriteSettings") ? (isZh() ? "已授权" : "Granted") : (isZh() ? "未授权" : "Not granted")},
                        {isZh() ? "网络面板" : "Network panel", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? (isZh() ? "系统确认" : "User confirmation") : (isZh() ? "可直接控制旧版 Wi-Fi" : "Legacy direct Wi-Fi possible")},
                        {isZh() ? "蓝牙权限" : "Bluetooth permission", bluetooth != null && bluetooth.optBoolean("hasConnectPermission", true) ? (isZh() ? "已满足" : "Ready") : (isZh() ? "需授权" : "Needs permission")}
                }));
        String text = isZh()
                ? "亮度 " + brightness.optInt("percent") + "%，媒体音量 " + volume.optInt("percent") + "%，Wi-Fi " + wifiStateLabel(wifi) + "，蓝牙 " + bluetoothStateLabel(bluetooth)
                : "Brightness " + brightness.optInt("percent") + "%, media volume " + volume.optInt("percent") + "%, Wi-Fi " + wifiStateLabel(wifi) + ", Bluetooth " + bluetoothStateLabel(bluetooth);
        return ok(out, text, html);
    }

    private String getWirelessStatus(Context context) throws Exception {
        JSONObject out = readWirelessStatus(context);
        JSONObject wifi = out.optJSONObject("wifi");
        JSONObject bluetooth = out.optJSONObject("bluetooth");
        String text = isZh()
                ? "Wi-Fi " + wifiStateLabel(wifi) + "，蓝牙 " + bluetoothStateLabel(bluetooth)
                : "Wi-Fi " + wifiStateLabel(wifi) + ", Bluetooth " + bluetoothStateLabel(bluetooth);
        return ok(out, text, wirelessHtml(isZh() ? "无线连接状态" : "Wireless Status", out));
    }

    private String openWifiPanel(Context context) throws Exception {
        String action = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? Settings.Panel.ACTION_WIFI
                : Settings.ACTION_WIFI_SETTINGS;
        return openSettingsAction(context, action, isZh() ? "已打开 Wi-Fi 设置" : "Wi-Fi settings opened", "Wi-Fi");
    }

    private String openInternetPanel(Context context) throws Exception {
        String action = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? Settings.Panel.ACTION_INTERNET_CONNECTIVITY
                : Settings.ACTION_WIRELESS_SETTINGS;
        return openSettingsAction(context, action, isZh() ? "已打开互联网连接面板" : "Internet connectivity panel opened", isZh() ? "互联网连接" : "Internet");
    }

    private String openWirelessSettings(Context context) throws Exception {
        return openSettingsAction(context, Settings.ACTION_WIRELESS_SETTINGS,
                isZh() ? "已打开无线和网络设置" : "Wireless settings opened",
                isZh() ? "无线和网络" : "Wireless");
    }

    private String openBluetoothSettings(Context context) throws Exception {
        return openSettingsAction(context, Settings.ACTION_BLUETOOTH_SETTINGS,
                isZh() ? "已打开蓝牙设置" : "Bluetooth settings opened",
                isZh() ? "蓝牙" : "Bluetooth");
    }

    private String requestEnableBluetooth(Context context) throws Exception {
        JSONObject out = new JSONObject();
        BluetoothAdapter adapter = bluetoothAdapter(context);
        out.put("supported", adapter != null);
        out.put("requestedEnabled", true);
        if (adapter == null) {
            String msg = isZh() ? "当前设备不支持蓝牙。" : "Bluetooth is not supported on this device.";
            return error(msg, msg, HtmlOutputHelper.card("🟦", isZh() ? "蓝牙不可用" : "Bluetooth Unavailable", HtmlOutputHelper.p(msg)));
        }

        if (!hasBluetoothConnectPermission(context)) {
            boolean opened = startActivity(context, Settings.ACTION_BLUETOOTH_SETTINGS);
            out.put("openedSettings", opened);
            out.put("requiresPermission", true);
            out.put("requiresUserAction", true);
            String msg = isZh()
                    ? "需要蓝牙连接权限后才能请求开启蓝牙，已打开蓝牙设置。"
                    : "Bluetooth Connect permission is required before requesting Bluetooth enablement. Bluetooth settings opened.";
            return ok(out, msg, actionHtml(isZh() ? "蓝牙需要授权" : "Bluetooth Permission Required", msg, out));
        }

        if (adapter.isEnabled()) {
            out.put("alreadyEnabled", true);
            out.put("changed", false);
            String msg = isZh() ? "蓝牙已开启。" : "Bluetooth is already on.";
            return ok(out, msg, actionHtml(isZh() ? "蓝牙" : "Bluetooth", msg, out));
        }

        boolean opened = startActivity(context, BluetoothAdapter.ACTION_REQUEST_ENABLE);
        out.put("openedRequest", opened);
        out.put("requiresUserAction", true);
        out.put("changed", false);
        String msg = opened
                ? (isZh() ? "已弹出蓝牙开启确认，需要你在系统弹窗中确认。" : "Bluetooth enable confirmation opened. User confirmation is required.")
                : (isZh() ? "无法弹出蓝牙开启确认，请从系统蓝牙设置中手动开启。" : "Could not open Bluetooth enable confirmation. Please enable it in system Bluetooth settings.");
        if (!opened) {
            out.put("openedSettings", startActivity(context, Settings.ACTION_BLUETOOTH_SETTINGS));
        }
        return ok(out, msg, actionHtml(isZh() ? "请求开启蓝牙" : "Request Bluetooth Enable", msg, out));
    }

    @SuppressWarnings("deprecation")
    private String setWifiEnabled(Context context, boolean enabled) throws Exception {
        JSONObject out = new JSONObject();
        out.put("requestedEnabled", enabled);
        out.put("androidApi", Build.VERSION.SDK_INT);
        WifiManager wifi = wifiManager(context);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && wifi != null) {
            boolean accepted = wifi.setWifiEnabled(enabled);
            out.put("changed", accepted);
            out.put("requiresUserAction", false);
            out.put("controlMode", "legacy_direct");
            String msg = isZh()
                    ? "已向系统提交 Wi-Fi " + (enabled ? "开启" : "关闭") + "请求。"
                    : "Submitted Wi-Fi " + (enabled ? "enable" : "disable") + " request to the system.";
            return ok(out, msg, actionHtml(isZh() ? "Wi-Fi 控制" : "Wi-Fi Control", msg, out));
        }

        String action = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? Settings.Panel.ACTION_WIFI : Settings.ACTION_WIFI_SETTINGS;
        boolean opened = startActivity(context, action);
        out.put("changed", false);
        out.put("requiresUserAction", true);
        out.put("openedSettings", opened);
        out.put("controlMode", "system_panel");
        String msg = isZh()
                ? "当前 Android 版本不允许普通应用静默开关 Wi-Fi，已打开系统 Wi-Fi 面板，请手动确认。"
                : "This Android version does not allow ordinary apps to silently toggle Wi-Fi. The system Wi-Fi panel was opened for user confirmation.";
        return ok(out, msg, actionHtml(isZh() ? "Wi-Fi 需要系统确认" : "Wi-Fi Needs Confirmation", msg, out));
    }

    private String setBluetoothEnabled(Context context, boolean enabled) throws Exception {
        if (enabled) {
            return requestEnableBluetooth(context);
        }
        JSONObject out = new JSONObject();
        out.put("requestedEnabled", false);
        out.put("changed", false);
        out.put("requiresUserAction", true);
        out.put("openedSettings", startActivity(context, Settings.ACTION_BLUETOOTH_SETTINGS));
        String msg = isZh()
                ? "Android 不支持普通应用静默关闭蓝牙，已打开蓝牙设置，请手动关闭。"
                : "Android does not allow ordinary apps to silently turn Bluetooth off. Bluetooth settings were opened for user confirmation.";
        return ok(out, msg, actionHtml(isZh() ? "蓝牙需要系统确认" : "Bluetooth Needs Confirmation", msg, out));
    }

    private JSONObject readWirelessStatus(Context context) throws Exception {
        JSONObject out = new JSONObject();
        out.put("wifi", readWifiStatus(context));
        out.put("bluetooth", readBluetoothStatus(context));
        out.put("androidApi", Build.VERSION.SDK_INT);
        out.put("wifiDirectToggleAvailable", Build.VERSION.SDK_INT < Build.VERSION_CODES.Q);
        out.put("bluetoothDirectToggleAvailable", false);
        return out;
    }

    private JSONObject readWifiStatus(Context context) throws Exception {
        JSONObject out = new JSONObject();
        WifiManager wifi = wifiManager(context);
        out.put("supported", wifi != null);
        out.put("canDirectToggle", Build.VERSION.SDK_INT < Build.VERSION_CODES.Q);
        out.put("controlMode", Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ? "legacy_direct" : "system_panel");
        if (wifi == null) {
            out.put("enabled", false);
            out.put("connected", false);
            return out;
        }

        out.put("enabled", wifi.isWifiEnabled());
        fillConnectivityStatus(context, out);
        fillWifiInfo(wifi, out);
        return out;
    }

    private void fillConnectivityStatus(Context context, JSONObject out) throws Exception {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network active = cm.getActiveNetwork();
            NetworkCapabilities caps = active == null ? null : cm.getNetworkCapabilities(active);
            if (caps != null) {
                boolean wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
                boolean mobile = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
                boolean ethernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
                out.put("connected", wifi);
                out.put("activeTransport", wifi ? "wifi" : (mobile ? "mobile" : (ethernet ? "ethernet" : "other")));
                out.put("hasInternetCapability", caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET));
                out.put("validated", caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
            }
            return;
        }

        @SuppressWarnings("deprecation")
        NetworkInfo info = cm.getActiveNetworkInfo();
        if (info != null) {
            out.put("connected", info.isConnected() && info.getType() == ConnectivityManager.TYPE_WIFI);
            out.put("activeTransport", info.getType() == ConnectivityManager.TYPE_WIFI ? "wifi" : info.getTypeName().toLowerCase(Locale.ROOT));
        }
    }

    private void fillWifiInfo(WifiManager wifi, JSONObject out) throws Exception {
        WifiInfo info = wifi.getConnectionInfo();
        if (info == null) return;

        String ssid = cleanSsid(info.getSSID());
        if (!ssid.isEmpty()) out.put("ssid", ssid);
        if (info.getBSSID() != null && !"02:00:00:00:00:00".equals(info.getBSSID())) out.put("bssid", info.getBSSID());
        out.put("rssi", info.getRssi());
        out.put("signalPercent", clamp(WifiManager.calculateSignalLevel(info.getRssi(), 101), 0, 100));
        out.put("linkSpeedMbps", info.getLinkSpeed());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            out.put("frequencyMhz", info.getFrequency());
        }
        String ip = formatIpAddress(info.getIpAddress());
        if (!ip.isEmpty()) out.put("ipAddress", ip);
    }

    private JSONObject readBluetoothStatus(Context context) throws Exception {
        JSONObject out = new JSONObject();
        BluetoothAdapter adapter = bluetoothAdapter(context);
        boolean hasPermission = hasBluetoothConnectPermission(context);
        out.put("supported", adapter != null);
        out.put("requiresConnectPermission", Build.VERSION.SDK_INT >= Build.VERSION_CODES.S);
        out.put("hasConnectPermission", hasPermission);
        out.put("canRequestEnable", adapter != null);
        out.put("canDirectDisable", false);
        if (adapter == null) {
            out.put("enabled", false);
            out.put("state", "unsupported");
            return out;
        }
        if (!hasPermission) {
            out.put("enabled", false);
            out.put("state", "permission_required");
            return out;
        }
        int state = adapter.getState();
        out.put("stateCode", state);
        out.put("state", bluetoothStateName(state));
        out.put("enabled", state == BluetoothAdapter.STATE_ON);
        return out;
    }

    private AudioManager audioManager(Context context) {
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) {
            throw new IllegalStateException(isZh() ? "音频服务不可用" : "Audio service unavailable");
        }
        return audio;
    }

    private JSONObject readVolume(AudioManager audio, int stream, String streamLabel) throws Exception {
        int current = audio.getStreamVolume(stream);
        int max = audio.getStreamMaxVolume(stream);
        int min = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? audio.getStreamMinVolume(stream) : 0;
        int percent = max > min ? Math.round((current - min) * 100f / (max - min)) : 0;
        JSONObject out = new JSONObject();
        out.put("stream", streamName(stream));
        out.put("streamLabel", streamLabel);
        out.put("volume", current);
        out.put("minVolume", min);
        out.put("maxVolume", max);
        out.put("percent", clamp(percent, 0, 100));
        return out;
    }

    private int streamType(String value) {
        String s = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (s.equals("ring") || s.equals("ringtone")) return AudioManager.STREAM_RING;
        if (s.equals("notification") || s.equals("notify")) return AudioManager.STREAM_NOTIFICATION;
        if (s.equals("alarm")) return AudioManager.STREAM_ALARM;
        if (s.equals("system")) return AudioManager.STREAM_SYSTEM;
        if (s.equals("voice") || s.equals("call") || s.equals("voice_call")) return AudioManager.STREAM_VOICE_CALL;
        return AudioManager.STREAM_MUSIC;
    }

    private String streamName(int stream) {
        switch (stream) {
            case AudioManager.STREAM_RING: return "ring";
            case AudioManager.STREAM_NOTIFICATION: return "notification";
            case AudioManager.STREAM_ALARM: return "alarm";
            case AudioManager.STREAM_SYSTEM: return "system";
            case AudioManager.STREAM_VOICE_CALL: return "voice";
            default: return "media";
        }
    }

    private String streamLabel(String value) {
        int stream = streamType(value);
        if (!isZh()) return streamName(stream);
        switch (stream) {
            case AudioManager.STREAM_RING: return "铃声";
            case AudioManager.STREAM_NOTIFICATION: return "通知";
            case AudioManager.STREAM_ALARM: return "闹钟";
            case AudioManager.STREAM_SYSTEM: return "系统";
            case AudioManager.STREAM_VOICE_CALL: return "通话";
            default: return "媒体";
        }
    }

    private String brightnessHtml(String title, JSONObject out) {
        int percent = out.optInt("percent");
        return HtmlOutputHelper.card("☀️", title,
                HtmlOutputHelper.metricGrid(new String[][]{
                        {percent + "%", isZh() ? "亮度" : "Brightness"},
                        {out.optString("modeLabel"), isZh() ? "模式" : "Mode"}
                }) +
                        HtmlOutputHelper.gauge(percent, "#F5A623") +
                        HtmlOutputHelper.keyValue(new String[][]{
                                {isZh() ? "可调节" : "Can modify", out.optBoolean("canWriteSettings") ? (isZh() ? "是" : "Yes") : (isZh() ? "需授权" : "Needs permission")}
                        }));
    }

    private String volumeHtml(String title, JSONObject out) {
        int percent = out.optInt("percent");
        return HtmlOutputHelper.card("🔊", title,
                HtmlOutputHelper.metricGrid(new String[][]{
                        {percent + "%", out.optString("streamLabel")},
                        {out.optInt("volume") + "/" + out.optInt("maxVolume"), isZh() ? "档位" : "Level"}
                }) + HtmlOutputHelper.gauge(percent, "#4F6BED"));
    }

    private String wirelessHtml(String title, JSONObject out) {
        JSONObject wifi = out.optJSONObject("wifi");
        JSONObject bluetooth = out.optJSONObject("bluetooth");
        String ssid = wifi == null ? "" : wifi.optString("ssid", "");
        String transport = wifi == null ? "-" : wifi.optString("activeTransport", "-");
        String bluetoothPermission = bluetooth != null && bluetooth.optBoolean("hasConnectPermission", true)
                ? (isZh() ? "已满足" : "Ready")
                : (isZh() ? "需授权" : "Needs permission");
        String notice = isZh()
                ? "Wi-Fi 和蓝牙开关在现代 Android 上需要系统确认，模块会打开对应系统面板或蓝牙开启确认。"
                : "Wi-Fi and Bluetooth toggles need system confirmation on modern Android. The module opens the matching system panel or Bluetooth enable prompt.";
        return HtmlOutputHelper.card("📶", title,
                HtmlOutputHelper.metricGrid(new String[][]{
                        {wifiStateLabel(wifi), "Wi-Fi"},
                        {bluetoothStateLabel(bluetooth), isZh() ? "蓝牙" : "Bluetooth"}
                }) + HtmlOutputHelper.keyValue(new String[][]{
                        {isZh() ? "当前网络" : "Active transport", transport},
                        {"SSID", ssid.isEmpty() ? "-" : ssid},
                        {isZh() ? "蓝牙权限" : "Bluetooth permission", bluetoothPermission},
                        {isZh() ? "Wi-Fi 控制" : "Wi-Fi control", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? (isZh() ? "系统面板" : "System panel") : (isZh() ? "旧版直接控制" : "Legacy direct")}
                }) + HtmlOutputHelper.callout(isZh() ? "系统限制" : "System Limit", notice, "warn"));
    }

    private String actionHtml(String title, String message, JSONObject out) {
        return HtmlOutputHelper.card("⚙️", title,
                HtmlOutputHelper.badge(out.optBoolean("requiresUserAction") ? (isZh() ? "需要确认" : "Needs confirmation") : (isZh() ? "已提交" : "Submitted"),
                        out.optBoolean("requiresUserAction") ? "orange" : "green") +
                        HtmlOutputHelper.p(message) +
                        HtmlOutputHelper.keyValue(new String[][]{
                                {isZh() ? "是否已改变" : "Changed", yesNo(out.optBoolean("changed", false))},
                                {isZh() ? "需要用户操作" : "Needs user action", yesNo(out.optBoolean("requiresUserAction", false))},
                                {isZh() ? "设置已打开" : "Settings opened", yesNo(out.optBoolean("openedSettings", out.optBoolean("openedRequest", false)))}
                        }));
    }

    private String openSettingsAction(Context context, String action, String message, String title) throws Exception {
        JSONObject out = new JSONObject();
        out.put("action", action);
        out.put("changed", false);
        out.put("requiresUserAction", true);
        out.put("openedSettings", startActivity(context, action));
        return ok(out, message, actionHtml(title, message, out));
    }

    private boolean startActivity(Context context, String action) {
        try {
            Intent intent = new Intent(action);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private WifiManager wifiManager(Context context) {
        return (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    }

    private BluetoothAdapter bluetoothAdapter(Context context) {
        try {
            BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            if (manager != null) return manager.getAdapter();
        } catch (Exception ignored) {
        }
        try {
            return BluetoothAdapter.getDefaultAdapter();
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean hasBluetoothConnectPermission(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private String wifiStateLabel(JSONObject wifi) {
        if (wifi == null || !wifi.optBoolean("supported", true)) return isZh() ? "不支持" : "Unsupported";
        if (wifi.optBoolean("connected", false)) return isZh() ? "已连接" : "Connected";
        if (wifi.optBoolean("enabled", false)) return isZh() ? "已开启" : "On";
        return isZh() ? "已关闭" : "Off";
    }

    private String bluetoothStateLabel(JSONObject bluetooth) {
        if (bluetooth == null || !bluetooth.optBoolean("supported", true)) return isZh() ? "不支持" : "Unsupported";
        if (!bluetooth.optBoolean("hasConnectPermission", true)) return isZh() ? "需授权" : "Needs permission";
        if (bluetooth.optBoolean("enabled", false)) return isZh() ? "已开启" : "On";
        String state = bluetooth.optString("state", "");
        if ("turning_on".equals(state)) return isZh() ? "开启中" : "Turning on";
        if ("turning_off".equals(state)) return isZh() ? "关闭中" : "Turning off";
        return isZh() ? "已关闭" : "Off";
    }

    private String bluetoothStateName(int state) {
        switch (state) {
            case BluetoothAdapter.STATE_ON: return "on";
            case BluetoothAdapter.STATE_TURNING_ON: return "turning_on";
            case BluetoothAdapter.STATE_TURNING_OFF: return "turning_off";
            case BluetoothAdapter.STATE_OFF:
            default:
                return "off";
        }
    }

    private String cleanSsid(String ssid) {
        if (ssid == null) return "";
        String s = ssid.trim();
        if (s.isEmpty() || "<unknown ssid>".equalsIgnoreCase(s) || "0x".equalsIgnoreCase(s)) return "";
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1);
        }
        return s;
    }

    private String formatIpAddress(int ip) {
        if (ip == 0) return "";
        return String.format(Locale.US, "%d.%d.%d.%d",
                ip & 0xff,
                (ip >> 8) & 0xff,
                (ip >> 16) & 0xff,
                (ip >> 24) & 0xff);
    }

    private String yesNo(boolean value) {
        return value ? (isZh() ? "是" : "Yes") : (isZh() ? "否" : "No");
    }

    private boolean canWriteSettings(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(context);
    }

    private int percentToBrightness(int percent) {
        return clamp(Math.round(255f * clamp(percent, 0, 100) / 100f), 1, 255);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String emptyJson(String value) {
        return value == null || value.trim().isEmpty() ? "{}" : value;
    }

    private String ok(JSONObject output, String displayText, String displayHtml) throws Exception {
        JSONObject r = new JSONObject();
        r.put("success", true);
        r.put("output", output.toString());
        if (displayText != null && !displayText.isEmpty()) r.put("_displayText", displayText);
        if (displayHtml != null && !displayHtml.isEmpty()) r.put("_displayHtml", displayHtml);
        return r.toString();
    }

    private String error(String msg) throws Exception {
        return error(msg, null, null);
    }

    private String error(String msg, String displayText, String displayHtml) throws Exception {
        JSONObject r = new JSONObject();
        r.put("success", false);
        r.put("error", msg == null ? "" : msg);
        if (displayText != null && !displayText.isEmpty()) r.put("_displayText", displayText);
        if (displayHtml != null && !displayHtml.isEmpty()) r.put("_displayHtml", displayHtml);
        return r.toString();
    }
}
