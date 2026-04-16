package ai.rorsch.moduleplugins.flashlight;

import ai.rorsch.pandagenie.module.runtime.ModulePlugin;
import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;
import org.json.JSONObject;

import java.util.Locale;

public class FlashlightPlugin implements ModulePlugin {

    private static volatile boolean torchOn = false;
    private static String cameraId = null;

    private static boolean isZh() {
        return Locale.getDefault().getLanguage().toLowerCase(Locale.ROOT).startsWith("zh");
    }

    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        try {
            CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (cm == null) throw new IllegalStateException(isZh() ? "相机服务不可用" : "Camera service unavailable");

            if (cameraId == null) {
                String[] ids = cm.getCameraIdList();
                if (ids.length == 0) throw new IllegalStateException(isZh() ? "未找到相机" : "No camera found");
                cameraId = ids[0];
            }

            switch (action) {
                case "turnOn":   return setTorch(cm, true);
                case "turnOff":  return setTorch(cm, false);
                case "toggle":   return setTorch(cm, !torchOn);
                case "getStatus": return getStatus();
                case "openPage": {
                    JSONObject r = new JSONObject();
                    r.put("success", true);
                    r.put("output", "{}");
                    r.put("_openModule", true);
                    r.put("_displayText", isZh() ? "正在打开手电筒..." : "Opening Flashlight...");
                    return r.toString();
                }
                default:
                    return error("Unsupported action: " + action);
            }
        } catch (Exception e) {
            String msg = e.getMessage();
            return error(msg != null && !msg.isEmpty() ? msg : e.getClass().getSimpleName());
        }
    }

    private String setTorch(CameraManager cm, boolean on) throws Exception {
        try {
            cm.setTorchMode(cameraId, on);
            torchOn = on;
        } catch (CameraAccessException e) {
            throw new IllegalStateException(isZh() ? "无法控制闪光灯" : "Cannot control flashlight");
        }

        JSONObject out = new JSONObject();
        out.put("on", torchOn);

        String display;
        if (isZh()) {
            display = (torchOn ? "\uD83D\uDD26 手电筒已打开" : "\uD83D\uDD26 手电筒已关闭");
        } else {
            display = (torchOn ? "\uD83D\uDD26 Flashlight ON" : "\uD83D\uDD26 Flashlight OFF");
        }

        JSONObject r = new JSONObject();
        r.put("success", true);
        r.put("output", out.toString());
        r.put("_displayText", display);
        return r.toString();
    }

    private String getStatus() throws Exception {
        JSONObject out = new JSONObject();
        out.put("on", torchOn);

        String display;
        if (isZh()) {
            display = "\uD83D\uDD26 手电筒状态: " + (torchOn ? "开启" : "关闭");
        } else {
            display = "\uD83D\uDD26 Flashlight: " + (torchOn ? "ON" : "OFF");
        }

        JSONObject r = new JSONObject();
        r.put("success", true);
        r.put("output", out.toString());
        r.put("_displayText", display);
        return r.toString();
    }

    private static String error(String msg) throws Exception {
        return new JSONObject().put("success", false).put("error", msg).toString();
    }
}
