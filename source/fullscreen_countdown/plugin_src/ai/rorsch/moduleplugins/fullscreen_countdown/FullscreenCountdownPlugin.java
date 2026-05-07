package ai.rorsch.moduleplugins.fullscreen_countdown;

import android.content.Context;
import ai.rorsch.pandagenie.module.runtime.HtmlOutputHelper;
import ai.rorsch.pandagenie.module.runtime.ModulePlugin;
import org.json.JSONObject;

import java.util.Locale;

public class FullscreenCountdownPlugin implements ModulePlugin {
    private static final int DEFAULT_MINUTES = 5;
    private static final boolean DEFAULT_VIBRATE_AT_ONE_MINUTE = true;
    private static final int DEFAULT_FINAL_VIBRATE_SECONDS = 10;
    private static final boolean DEFAULT_SOUND_AT_ONE_MINUTE = true;
    private static final int DEFAULT_FINAL_SOUND_SECONDS = 10;

    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        try {
            JSONObject params = new JSONObject(emptyJson(paramsJson));
            switch (action) {
                case "createCountdown": {
                    JSONObject config = createCountdown(params);
                    return okOpenModule(config, formatDisplay(config), formatDisplayHtml(config));
                }
                case "getDefaultSettings":
                    return ok(defaultSettings());
                default:
                    return error("Unsupported action: " + action);
            }
        } catch (Exception e) {
            String msg = e.getMessage();
            return error(msg != null && !msg.isEmpty() ? msg : e.getClass().getSimpleName());
        }
    }

    private JSONObject createCountdown(JSONObject params) throws Exception {
        int totalSeconds;
        if (params.has("durationSeconds")) {
            totalSeconds = params.optInt("durationSeconds", DEFAULT_MINUTES * 60);
        } else {
            int hours = clamp(params.optInt("hours", 0), 0, 99);
            int minutes = params.has("minutes")
                    ? clamp(params.optInt("minutes", 0), 0, 59)
                    : DEFAULT_MINUTES;
            int seconds = clamp(params.optInt("seconds", 0), 0, 59);
            totalSeconds = hours * 3600 + minutes * 60 + seconds;
        }
        totalSeconds = clamp(totalSeconds, 1, 99 * 3600 + 59 * 60 + 59);

        boolean vibrateAtOneMinute = params.has("vibrateAtOneMinute")
                ? params.optBoolean("vibrateAtOneMinute", DEFAULT_VIBRATE_AT_ONE_MINUTE)
                : DEFAULT_VIBRATE_AT_ONE_MINUTE;
        int finalVibrateSeconds = params.has("finalVibrateSeconds")
                ? clamp(params.optInt("finalVibrateSeconds", DEFAULT_FINAL_VIBRATE_SECONDS), 0, 60)
                : DEFAULT_FINAL_VIBRATE_SECONDS;
        boolean soundAtOneMinute = params.has("soundAtOneMinute")
                ? params.optBoolean("soundAtOneMinute", DEFAULT_SOUND_AT_ONE_MINUTE)
                : DEFAULT_SOUND_AT_ONE_MINUTE;
        int finalSoundSeconds = params.has("finalSoundSeconds")
                ? clamp(params.optInt("finalSoundSeconds", DEFAULT_FINAL_SOUND_SECONDS), 0, 60)
                : DEFAULT_FINAL_SOUND_SECONDS;

        String title = params.optString("title", "").trim();
        if (title.isEmpty()) title = isZh() ? "倒计时" : "Countdown";

        JSONObject result = new JSONObject();
        result.put("title", title);
        result.put("durationSeconds", totalSeconds);
        result.put("hours", totalSeconds / 3600);
        result.put("minutes", (totalSeconds % 3600) / 60);
        result.put("seconds", totalSeconds % 60);
        result.put("displayMode", totalSeconds > 3600 ? "hms" : "ms");
        result.put("vibrateAtOneMinute", vibrateAtOneMinute);
        result.put("finalVibrateSeconds", finalVibrateSeconds);
        result.put("soundAtOneMinute", soundAtOneMinute);
        result.put("finalSoundSeconds", finalSoundSeconds);
        result.put("autoStartFullscreen", params.has("autoStartFullscreen")
                ? params.optBoolean("autoStartFullscreen", true)
                : true);
        return result;
    }

    private JSONObject defaultSettings() throws Exception {
        JSONObject result = new JSONObject();
        result.put("durationSeconds", DEFAULT_MINUTES * 60);
        result.put("hours", 0);
        result.put("minutes", DEFAULT_MINUTES);
        result.put("seconds", 0);
        result.put("vibrateAtOneMinute", DEFAULT_VIBRATE_AT_ONE_MINUTE);
        result.put("finalVibrateSeconds", DEFAULT_FINAL_VIBRATE_SECONDS);
        result.put("soundAtOneMinute", DEFAULT_SOUND_AT_ONE_MINUTE);
        result.put("finalSoundSeconds", DEFAULT_FINAL_SOUND_SECONDS);
        result.put("autoStartFullscreen", true);
        return result;
    }

    private String formatDisplay(JSONObject config) {
        StringBuilder sb = new StringBuilder();
        sb.append(isZh() ? "倒计时已创建\n" : "Countdown created\n");
        sb.append(isZh() ? "标题: " : "Title: ").append(config.optString("title", "")).append("\n");
        sb.append(isZh() ? "时长: " : "Duration: ").append(formatDuration(config.optInt("durationSeconds", 0))).append("\n");
        if (config.optBoolean("vibrateAtOneMinute", true)) {
            sb.append(isZh() ? "剩余1分钟震动三次\n" : "Triple vibration at one minute remaining\n");
        }
        int finalVibrateSeconds = config.optInt("finalVibrateSeconds", 0);
        if (finalVibrateSeconds > 0) {
            sb.append(isZh() ? "最后" : "Final ").append(finalVibrateSeconds)
                    .append(isZh() ? "秒每秒震动一次\n" : " seconds vibrate every second\n");
        }
        if (config.optBoolean("soundAtOneMinute", true)) {
            sb.append(isZh() ? "剩余1分钟播放提示音\n" : "Play sound at one minute remaining\n");
        }
        int finalSoundSeconds = config.optInt("finalSoundSeconds", 0);
        if (finalSoundSeconds > 0) {
            sb.append(isZh() ? "最后" : "Final ").append(finalSoundSeconds)
                    .append(isZh() ? "秒每秒播放提示音" : " seconds play sound every second");
        }
        return sb.toString();
    }

    private String formatDisplayHtml(JSONObject config) {
        String title = isZh() ? "倒计时已准备" : "Countdown ready";
        int finalVibrateSeconds = config.optInt("finalVibrateSeconds", 0);
        String finalVibrateAlert = finalVibrateSeconds > 0
                ? (isZh() ? "最后" + finalVibrateSeconds + "秒每秒震动" : "Vibrate every second in final " + finalVibrateSeconds + "s")
                : (isZh() ? "关闭" : "Off");
        int finalSoundSeconds = config.optInt("finalSoundSeconds", 0);
        String soundAlert = finalSoundSeconds > 0
                ? (isZh() ? "最后" + finalSoundSeconds + "秒每秒提示音" : "Beep every second in final " + finalSoundSeconds + "s")
                : (isZh() ? "关闭" : "Off");
        String body = HtmlOutputHelper.keyValue(new String[][]{
                {isZh() ? "标题" : "Title", config.optString("title", "")},
                {isZh() ? "时长" : "Duration", formatDuration(config.optInt("durationSeconds", 0))},
                {isZh() ? "1分钟震动" : "1-minute vibration", config.optBoolean("vibrateAtOneMinute", true) ? (isZh() ? "震动三次" : "Triple vibration") : (isZh() ? "关闭" : "Off")},
                {isZh() ? "结束震动" : "Final vibration", finalVibrateAlert},
                {isZh() ? "1分钟提示音" : "1-minute sound", config.optBoolean("soundAtOneMinute", true) ? (isZh() ? "开启" : "On") : (isZh() ? "关闭" : "Off")},
                {isZh() ? "读秒提示音" : "Final sound", soundAlert}
        }) + HtmlOutputHelper.p(isZh() ? "将打开倒计时页面并横屏全屏启动。" : "The countdown page will open and start fullscreen landscape.");
        return HtmlOutputHelper.card("⏱", title, body + HtmlOutputHelper.successBadge());
    }

    private String formatDuration(int totalSeconds) {
        int h = totalSeconds / 3600;
        int m = (totalSeconds % 3600) / 60;
        int s = totalSeconds % 60;
        if (h > 0) {
            return String.format(Locale.ROOT, "%02d:%02d:%02d", h, m, s);
        }
        return String.format(Locale.ROOT, "%02d:%02d", m, s);
    }

    private boolean isZh() {
        return Locale.getDefault().getLanguage().toLowerCase(Locale.ROOT).startsWith("zh");
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String emptyJson(String s) {
        return s == null || s.trim().isEmpty() ? "{}" : s;
    }

    private String ok(JSONObject output) throws Exception {
        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("output", output);
        result.put("_displayText", output.toString(2));
        return result.toString();
    }

    private String okOpenModule(JSONObject output, String displayText, String displayHtml) throws Exception {
        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("output", output);
        result.put("_displayText", displayText);
        result.put("_displayHtml", displayHtml);
        result.put("_openModule", true);
        return result.toString();
    }

    private String error(String message) throws Exception {
        JSONObject result = new JSONObject();
        result.put("success", false);
        result.put("error", message);
        return result.toString();
    }
}
