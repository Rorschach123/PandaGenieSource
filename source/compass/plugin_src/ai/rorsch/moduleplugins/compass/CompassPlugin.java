package ai.rorsch.moduleplugins.compass;

import ai.rorsch.pandagenie.module.runtime.HtmlOutputHelper;
import ai.rorsch.pandagenie.module.runtime.ModulePlugin;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class CompassPlugin implements ModulePlugin {

    private static boolean isZh() {
        return Locale.getDefault().getLanguage().toLowerCase(Locale.ROOT).startsWith("zh");
    }

    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        try {
            switch (action) {
                case "getDirection": return getDirection(context);
                case "openPage": {
                    JSONObject r = new JSONObject();
                    r.put("success", true);
                    r.put("output", "{}");
                    r.put("_openModule", true);
                    String dt = isZh() ? "正在打开指南针..." : "Opening Compass...";
                    r.put("_displayText", dt);
                    r.put("_displayHtml", HtmlOutputHelper.card("🧭", isZh() ? "指南针" : "Compass",
                            HtmlOutputHelper.muted(dt)));
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

    private String getDirection(Context context) throws Exception {
        SensorManager sm = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        if (sm == null) throw new IllegalStateException(isZh() ? "传感器服务不可用" : "Sensor service unavailable");

        Sensor rotationSensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        Sensor accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        Sensor mag = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);

        boolean useRotation = rotationSensor != null;
        if (!useRotation && (accel == null || mag == null)) {
            throw new IllegalStateException(isZh() ? "设备不支持方向传感器" : "Device does not support orientation sensors");
        }

        final float[] result = new float[1];
        final CountDownLatch latch = new CountDownLatch(1);

        if (useRotation) {
            SensorEventListener listener = new SensorEventListener() {
                @Override
                public void onSensorChanged(SensorEvent event) {
                    float[] rotMat = new float[9];
                    SensorManager.getRotationMatrixFromVector(rotMat, event.values);
                    float[] orientation = new float[3];
                    SensorManager.getOrientation(rotMat, orientation);
                    result[0] = (float) Math.toDegrees(orientation[0]);
                    if (result[0] < 0) result[0] += 360;
                    sm.unregisterListener(this);
                    latch.countDown();
                }
                @Override
                public void onAccuracyChanged(Sensor sensor, int accuracy) {}
            };
            sm.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_GAME);
        } else {
            final float[] gravity = new float[3];
            final float[] geomagnetic = new float[3];
            final boolean[] gotG = {false};
            final boolean[] gotM = {false};

            SensorEventListener listener = new SensorEventListener() {
                @Override
                public void onSensorChanged(SensorEvent event) {
                    if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                        System.arraycopy(event.values, 0, gravity, 0, 3);
                        gotG[0] = true;
                    } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
                        System.arraycopy(event.values, 0, geomagnetic, 0, 3);
                        gotM[0] = true;
                    }
                    if (gotG[0] && gotM[0]) {
                        float[] R = new float[9];
                        if (SensorManager.getRotationMatrix(R, null, gravity, geomagnetic)) {
                            float[] orientation = new float[3];
                            SensorManager.getOrientation(R, orientation);
                            result[0] = (float) Math.toDegrees(orientation[0]);
                            if (result[0] < 0) result[0] += 360;
                            sm.unregisterListener(this);
                            latch.countDown();
                        }
                    }
                }
                @Override
                public void onAccuracyChanged(Sensor sensor, int accuracy) {}
            };
            sm.registerListener(listener, accel, SensorManager.SENSOR_DELAY_GAME);
            sm.registerListener(listener, mag, SensorManager.SENSOR_DELAY_GAME);
        }

        if (!latch.await(3, TimeUnit.SECONDS)) {
            throw new IllegalStateException(isZh() ? "读取传感器超时" : "Sensor read timeout");
        }

        float azimuth = result[0];
        int azInt = Math.round(azimuth);
        String cardinal = getCardinal(azInt);
        String cardinalFull = getCardinalFull(azInt);

        JSONObject out = new JSONObject();
        out.put("azimuth", azInt);
        out.put("direction", cardinal);
        out.put("directionFull", cardinalFull);

        String display;
        if (isZh()) {
            display = "\uD83E\uDDED 当前方向\n\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\n"
                    + "\u25B8 方位角: " + azInt + "\u00B0\n"
                    + "\u25B8 方向: " + cardinalFull + " (" + cardinal + ")";
        } else {
            display = "\uD83E\uDDED Current Heading\n\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\n"
                    + "\u25B8 Azimuth: " + azInt + "\u00B0\n"
                    + "\u25B8 Direction: " + cardinalFull + " (" + cardinal + ")";
        }

        String html = HtmlOutputHelper.card("🧭", isZh() ? "当前方向" : "Heading",
                HtmlOutputHelper.keyValue(new String[][]{
                        {isZh() ? "方位角" : "Azimuth", azInt + "°"},
                        {isZh() ? "方向" : "Direction", cardinalFull + " (" + cardinal + ")"}
                }));

        JSONObject r = new JSONObject();
        r.put("success", true);
        r.put("output", out.toString());
        r.put("_displayText", display);
        r.put("_displayHtml", html);
        return r.toString();
    }

    private static String getCardinal(int deg) {
        String[] dirs = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        return dirs[((deg + 22) % 360) / 45];
    }

    private static String getCardinalFull(int deg) {
        boolean zh = Locale.getDefault().getLanguage().startsWith("zh");
        String[] dirs = zh
                ? new String[]{"北", "东北", "东", "东南", "南", "西南", "西", "西北"}
                : new String[]{"North", "Northeast", "East", "Southeast", "South", "Southwest", "West", "Northwest"};
        return dirs[((deg + 22) % 360) / 45];
    }

    private static String error(String msg) throws Exception {
        return new JSONObject().put("success", false).put("error", msg).toString();
    }
}
