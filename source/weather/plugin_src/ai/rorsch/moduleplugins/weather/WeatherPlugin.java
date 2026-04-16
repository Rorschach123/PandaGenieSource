package ai.rorsch.moduleplugins.weather;

import ai.rorsch.pandagenie.module.runtime.ModulePlugin;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;

public class WeatherPlugin implements ModulePlugin {

    private static final String GEO_API = "https://geocoding-api.open-meteo.com/v1/search";
    private static final String WEATHER_API = "https://api.open-meteo.com/v1/forecast";
    private static final int TIMEOUT = 10_000;

    private static boolean isZh() {
        return Locale.getDefault().getLanguage().toLowerCase(Locale.ROOT).startsWith("zh");
    }

    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        try {
            JSONObject params = new JSONObject(paramsJson == null || paramsJson.trim().isEmpty() ? "{}" : paramsJson);
            switch (action) {
                case "getCurrentWeather": return getCurrentWeather(context, params);
                case "getForecast":       return getForecast(context, params);
                case "getWeatherByLocation": return getWeatherByLocation(params);
                case "openPage": {
                    JSONObject r = new JSONObject();
                    r.put("success", true);
                    r.put("output", "{}");
                    r.put("_openModule", true);
                    r.put("_displayText", isZh() ? "正在打开天气助手..." : "Opening Weather...");
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

    private String getCurrentWeather(Context context, JSONObject params) throws Exception {
        String city = params.optString("city", "").trim();
        double[] coords;
        if (city.isEmpty()) {
            Location loc = getLastLocation(context);
            if (loc == null) throw new IllegalArgumentException(
                    isZh() ? "未能获取位置信息，请提供城市名称或开启定位" : "Location unavailable, please provide a city name or enable GPS");
            coords = new double[]{loc.getLatitude(), loc.getLongitude()};
            city = isZh() ? "当前位置" : "Current Location";
        } else {
            coords = geocode(city);
        }

        String url = WEATHER_API + "?latitude=" + coords[0] + "&longitude=" + coords[1]
                + "&current=temperature_2m,relative_humidity_2m,apparent_temperature,wind_speed_10m,wind_direction_10m,weather_code,surface_pressure"
                + "&timezone=auto";

        JSONObject resp = new JSONObject(httpGet(url));
        JSONObject current = resp.getJSONObject("current");

        double temp = current.optDouble("temperature_2m", 0);
        double feelsLike = current.optDouble("apparent_temperature", 0);
        int humidity = current.optInt("relative_humidity_2m", 0);
        double windSpeed = current.optDouble("wind_speed_10m", 0);
        int windDir = current.optInt("wind_direction_10m", 0);
        int weatherCode = current.optInt("weather_code", 0);
        double pressure = current.optDouble("surface_pressure", 0);

        String condition = weatherCodeToText(weatherCode);
        String conditionEmoji = weatherCodeToEmoji(weatherCode);
        String windDirText = degreeToDirection(windDir);

        JSONObject out = new JSONObject();
        out.put("city", city);
        out.put("temperature", temp);
        out.put("feelsLike", feelsLike);
        out.put("humidity", humidity);
        out.put("windSpeed", windSpeed);
        out.put("windDirection", windDirText);
        out.put("pressure", pressure);
        out.put("condition", condition);
        out.put("weatherCode", weatherCode);

        String display;
        if (isZh()) {
            display = conditionEmoji + " " + city + " 当前天气\n"
                    + "\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\n"
                    + "\u25B8 天气: " + condition + "\n"
                    + "\u25B8 温度: " + temp + "\u00B0C (体感 " + feelsLike + "\u00B0C)\n"
                    + "\u25B8 湿度: " + humidity + "%\n"
                    + "\u25B8 风速: " + windSpeed + " km/h " + windDirText + "\n"
                    + "\u25B8 气压: " + pressure + " hPa";
        } else {
            display = conditionEmoji + " " + city + " Current Weather\n"
                    + "\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\n"
                    + "\u25B8 Condition: " + condition + "\n"
                    + "\u25B8 Temperature: " + temp + "\u00B0C (feels like " + feelsLike + "\u00B0C)\n"
                    + "\u25B8 Humidity: " + humidity + "%\n"
                    + "\u25B8 Wind: " + windSpeed + " km/h " + windDirText + "\n"
                    + "\u25B8 Pressure: " + pressure + " hPa";
        }

        JSONObject r = new JSONObject();
        r.put("success", true);
        r.put("output", out.toString());
        r.put("_displayText", display);
        return r.toString();
    }

    private String getForecast(Context context, JSONObject params) throws Exception {
        String city = params.optString("city", "").trim();
        int days = Math.max(1, Math.min(7, params.optInt("days", 3)));
        double[] coords;
        if (city.isEmpty()) {
            Location loc = getLastLocation(context);
            if (loc == null) throw new IllegalArgumentException(
                    isZh() ? "未能获取位置信息，请提供城市名称或开启定位" : "Location unavailable, please provide a city name or enable GPS");
            coords = new double[]{loc.getLatitude(), loc.getLongitude()};
            city = isZh() ? "当前位置" : "Current Location";
        } else {
            coords = geocode(city);
        }

        String url = WEATHER_API + "?latitude=" + coords[0] + "&longitude=" + coords[1]
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,wind_speed_10m_max"
                + "&timezone=auto&forecast_days=" + days;

        JSONObject resp = new JSONObject(httpGet(url));
        JSONObject daily = resp.getJSONObject("daily");

        JSONArray dates = daily.getJSONArray("time");
        JSONArray codes = daily.getJSONArray("weather_code");
        JSONArray maxTemps = daily.getJSONArray("temperature_2m_max");
        JSONArray minTemps = daily.getJSONArray("temperature_2m_min");
        JSONArray precipProbs = daily.getJSONArray("precipitation_probability_max");
        JSONArray windMaxs = daily.getJSONArray("wind_speed_10m_max");

        JSONArray forecastArr = new JSONArray();
        StringBuilder sb = new StringBuilder();
        if (isZh()) {
            sb.append("\uD83D\uDCC5 ").append(city).append(" ").append(days).append("日天气预报\n");
            sb.append("\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\n");
        } else {
            sb.append("\uD83D\uDCC5 ").append(city).append(" ").append(days).append("-Day Forecast\n");
            sb.append("\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\n");
        }

        for (int i = 0; i < dates.length(); i++) {
            String date = dates.getString(i);
            int code = codes.optInt(i, 0);
            double maxT = maxTemps.optDouble(i, 0);
            double minT = minTemps.optDouble(i, 0);
            int precip = precipProbs.optInt(i, 0);
            double windMax = windMaxs.optDouble(i, 0);
            String emoji = weatherCodeToEmoji(code);
            String cond = weatherCodeToText(code);

            JSONObject dayObj = new JSONObject();
            dayObj.put("date", date);
            dayObj.put("condition", cond);
            dayObj.put("tempMax", maxT);
            dayObj.put("tempMin", minT);
            dayObj.put("precipProbability", precip);
            dayObj.put("windSpeedMax", windMax);
            forecastArr.put(dayObj);

            sb.append(emoji).append(" ").append(date).append(" | ").append(cond)
              .append(" | ").append(minT).append("~").append(maxT).append("\u00B0C");
            if (precip > 0) sb.append(" | \uD83C\uDF27").append(precip).append("%");
            sb.append("\n");
        }

        JSONObject out = new JSONObject();
        out.put("city", city);
        out.put("days", days);
        out.put("forecast", forecastArr);

        JSONObject r = new JSONObject();
        r.put("success", true);
        r.put("output", out.toString());
        r.put("_displayText", sb.toString().trim());
        return r.toString();
    }

    private String getWeatherByLocation(JSONObject params) throws Exception {
        double lat = params.optDouble("latitude", Double.NaN);
        double lon = params.optDouble("longitude", Double.NaN);
        if (Double.isNaN(lat) || Double.isNaN(lon))
            throw new IllegalArgumentException(isZh() ? "请提供 latitude 和 longitude" : "latitude and longitude are required");

        String url = WEATHER_API + "?latitude=" + lat + "&longitude=" + lon
                + "&current=temperature_2m,relative_humidity_2m,apparent_temperature,wind_speed_10m,wind_direction_10m,weather_code,surface_pressure"
                + "&timezone=auto";

        JSONObject resp = new JSONObject(httpGet(url));
        JSONObject current = resp.getJSONObject("current");

        double temp = current.optDouble("temperature_2m", 0);
        double feelsLike = current.optDouble("apparent_temperature", 0);
        int humidity = current.optInt("relative_humidity_2m", 0);
        double windSpeed = current.optDouble("wind_speed_10m", 0);
        int windDir = current.optInt("wind_direction_10m", 0);
        int weatherCode = current.optInt("weather_code", 0);
        double pressure = current.optDouble("surface_pressure", 0);

        String condition = weatherCodeToText(weatherCode);
        String conditionEmoji = weatherCodeToEmoji(weatherCode);
        String windDirText = degreeToDirection(windDir);
        String label = String.format(Locale.US, "%.2f, %.2f", lat, lon);

        JSONObject out = new JSONObject();
        out.put("latitude", lat);
        out.put("longitude", lon);
        out.put("temperature", temp);
        out.put("feelsLike", feelsLike);
        out.put("humidity", humidity);
        out.put("windSpeed", windSpeed);
        out.put("windDirection", windDirText);
        out.put("pressure", pressure);
        out.put("condition", condition);
        out.put("weatherCode", weatherCode);

        String display;
        if (isZh()) {
            display = conditionEmoji + " (" + label + ") 当前天气\n"
                    + "\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\n"
                    + "\u25B8 天气: " + condition + "\n"
                    + "\u25B8 温度: " + temp + "\u00B0C (体感 " + feelsLike + "\u00B0C)\n"
                    + "\u25B8 湿度: " + humidity + "%\n"
                    + "\u25B8 风速: " + windSpeed + " km/h " + windDirText + "\n"
                    + "\u25B8 气压: " + pressure + " hPa";
        } else {
            display = conditionEmoji + " (" + label + ") Current Weather\n"
                    + "\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\n"
                    + "\u25B8 Condition: " + condition + "\n"
                    + "\u25B8 Temperature: " + temp + "\u00B0C (feels like " + feelsLike + "\u00B0C)\n"
                    + "\u25B8 Humidity: " + humidity + "%\n"
                    + "\u25B8 Wind: " + windSpeed + " km/h " + windDirText + "\n"
                    + "\u25B8 Pressure: " + pressure + " hPa";
        }

        JSONObject r = new JSONObject();
        r.put("success", true);
        r.put("output", out.toString());
        r.put("_displayText", display);
        return r.toString();
    }

    @SuppressWarnings("MissingPermission")
    private static Location getLastLocation(Context context) {
        try {
            LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return null;

            if (context.checkPermission("android.permission.ACCESS_FINE_LOCATION",
                    android.os.Process.myPid(), android.os.Process.myUid()) != PackageManager.PERMISSION_GRANTED
                && context.checkPermission("android.permission.ACCESS_COARSE_LOCATION",
                    android.os.Process.myPid(), android.os.Process.myUid()) != PackageManager.PERMISSION_GRANTED) {
                return null;
            }

            Location best = null;
            for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
                try {
                    if (!lm.isProviderEnabled(provider)) continue;
                    Location loc = lm.getLastKnownLocation(provider);
                    if (loc != null && (best == null || loc.getTime() > best.getTime())) {
                        best = loc;
                    }
                } catch (Exception ignored) {}
            }
            return best;
        } catch (Exception e) {
            return null;
        }
    }

    private double[] geocode(String city) throws Exception {
        String url = GEO_API + "?name=" + URLEncoder.encode(city, "UTF-8") + "&count=1&language=zh";
        JSONObject resp = new JSONObject(httpGet(url));
        JSONArray results = resp.optJSONArray("results");
        if (results == null || results.length() == 0) {
            throw new IllegalArgumentException(isZh() ? "未找到城市: " + city : "City not found: " + city);
        }
        JSONObject loc = results.getJSONObject(0);
        return new double[]{loc.getDouble("latitude"), loc.getDouble("longitude")};
    }

    private static String httpGet(String urlStr) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(TIMEOUT);
        conn.setReadTimeout(TIMEOUT);
        conn.setRequestProperty("User-Agent", "PandaGenie-Weather/1.0");
        try {
            int code = conn.getResponseCode();
            InputStream is = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code + ": " + sb);
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    private static String weatherCodeToText(int code) {
        boolean zh = Locale.getDefault().getLanguage().startsWith("zh");
        switch (code) {
            case 0:  return zh ? "晴" : "Clear";
            case 1:  return zh ? "大部晴朗" : "Mainly Clear";
            case 2:  return zh ? "多云" : "Partly Cloudy";
            case 3:  return zh ? "阴" : "Overcast";
            case 45: case 48: return zh ? "雾" : "Fog";
            case 51: return zh ? "小毛毛雨" : "Light Drizzle";
            case 53: return zh ? "毛毛雨" : "Moderate Drizzle";
            case 55: return zh ? "大毛毛雨" : "Dense Drizzle";
            case 61: return zh ? "小雨" : "Light Rain";
            case 63: return zh ? "中雨" : "Moderate Rain";
            case 65: return zh ? "大雨" : "Heavy Rain";
            case 66: case 67: return zh ? "冻雨" : "Freezing Rain";
            case 71: return zh ? "小雪" : "Light Snow";
            case 73: return zh ? "中雪" : "Moderate Snow";
            case 75: return zh ? "大雪" : "Heavy Snow";
            case 77: return zh ? "雪粒" : "Snow Grains";
            case 80: return zh ? "小阵雨" : "Light Showers";
            case 81: return zh ? "中阵雨" : "Moderate Showers";
            case 82: return zh ? "大阵雨" : "Violent Showers";
            case 85: return zh ? "小阵雪" : "Light Snow Showers";
            case 86: return zh ? "大阵雪" : "Heavy Snow Showers";
            case 95: return zh ? "雷暴" : "Thunderstorm";
            case 96: case 99: return zh ? "雷暴伴冰雹" : "Thunderstorm with Hail";
            default: return zh ? "未知" : "Unknown";
        }
    }

    private static String weatherCodeToEmoji(int code) {
        if (code == 0) return "\u2600\uFE0F";
        if (code <= 2) return "\u26C5";
        if (code == 3) return "\u2601\uFE0F";
        if (code <= 48) return "\uD83C\uDF2B\uFE0F";
        if (code <= 55) return "\uD83C\uDF26\uFE0F";
        if (code <= 67) return "\uD83C\uDF27\uFE0F";
        if (code <= 77) return "\u2744\uFE0F";
        if (code <= 82) return "\uD83C\uDF26\uFE0F";
        if (code <= 86) return "\uD83C\uDF28\uFE0F";
        return "\u26C8\uFE0F";
    }

    private static String degreeToDirection(int deg) {
        boolean zh = Locale.getDefault().getLanguage().startsWith("zh");
        String[] dirs = zh
                ? new String[]{"北","东北","东","东南","南","西南","西","西北"}
                : new String[]{"N","NE","E","SE","S","SW","W","NW"};
        return dirs[((deg + 22) % 360) / 45];
    }

    private static String error(String msg) throws Exception {
        return new JSONObject().put("success", false).put("error", msg).toString();
    }
}
