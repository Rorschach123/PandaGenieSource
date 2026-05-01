package ai.rorsch.moduleplugins.device_controls;

import android.content.Context;
import android.media.AudioManager;
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
        JSONObject out = new JSONObject();
        out.put("brightness", brightness);
        out.put("mediaVolume", volume);
        String html = HtmlOutputHelper.card("🎛️", isZh() ? "设备控制状态" : "Device Controls",
                HtmlOutputHelper.metricGrid(new String[][]{
                        {brightness.optInt("percent") + "%", isZh() ? "亮度" : "Brightness"},
                        {volume.optInt("percent") + "%", isZh() ? "媒体音量" : "Media Volume"}
                }) + HtmlOutputHelper.keyValue(new String[][]{
                        {isZh() ? "亮度模式" : "Brightness mode", brightness.optString("modeLabel")},
                        {isZh() ? "亮度授权" : "Brightness permission", brightness.optBoolean("canWriteSettings") ? (isZh() ? "已授权" : "Granted") : (isZh() ? "未授权" : "Not granted")}
                }));
        String text = isZh()
                ? "亮度 " + brightness.optInt("percent") + "%，媒体音量 " + volume.optInt("percent") + "%"
                : "Brightness " + brightness.optInt("percent") + "%, media volume " + volume.optInt("percent") + "%";
        return ok(out, text, html);
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
