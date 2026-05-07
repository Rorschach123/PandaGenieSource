package ai.rorsch.moduleplugins.weather;

import ai.rorsch.pandagenie.module.runtime.HtmlOutputHelper;
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
import java.util.ArrayList;
import java.util.List;
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
                case "getTemperatureAlert": return getTemperatureAlert(context, params);
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
        String html = HtmlOutputHelper.card("\uD83C\uDF24\uFE0F",
                isZh() ? city + " 当前天气" : city + " Current Weather",
                HtmlOutputHelper.metricGrid(new String[][]{
                        {temp + "\u00B0C", isZh() ? "温度" : "Temperature"},
                        {windSpeed + " km/h", isZh() ? "风速" : "Wind"},
                        {humidity + "%", isZh() ? "湿度" : "Humidity"}
                }) +
                        HtmlOutputHelper.keyValue(new String[][]{
                                {isZh() ? "天气" : "Condition", condition},
                                {isZh() ? "体感温度" : "Feels Like", feelsLike + "\u00B0C"},
                                {isZh() ? "风向" : "Wind Dir.", windDir + "\u00B0"}
                        })
        );
        r.put("_displayHtml", html);
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
        List<String[]> forecastRows = new ArrayList<>();
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

            forecastRows.add(new String[]{date, String.valueOf(minT), String.valueOf(maxT), emoji + " " + cond});

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
        String forecastHtml = HtmlOutputHelper.card("\uD83D\uDCC5",
                isZh() ? city + " 天气预报" : city + " Forecast",
                HtmlOutputHelper.table(
                        new String[]{isZh() ? "日期" : "Date", isZh() ? "最低" : "Min", isZh() ? "最高" : "Max", isZh() ? "天气" : "Weather"},
                        forecastRows
                )
        );
        r.put("_displayHtml", forecastHtml);
        return r.toString();
    }

    private String getTemperatureAlert(Context context, JSONObject params) throws Exception {
        String city = params.optString("city", "").trim();
        ResolvedLocation location = resolveLocation(context, city);

        String url = WEATHER_API + "?latitude=" + location.latitude + "&longitude=" + location.longitude
                + "&current=temperature_2m,relative_humidity_2m,apparent_temperature,wind_speed_10m,wind_direction_10m,weather_code"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,wind_speed_10m_max"
                + "&timezone=auto&forecast_days=2";

        JSONObject resp = new JSONObject(httpGet(url));
        JSONObject current = resp.getJSONObject("current");
        JSONObject daily = resp.getJSONObject("daily");

        JSONArray dates = daily.getJSONArray("time");
        if (dates.length() < 2) {
            throw new IllegalStateException(isZh()
                    ? "天气服务暂时没有返回明天的预报数据"
                    : "Weather service did not return tomorrow forecast data");
        }

        WeatherDay today = readWeatherDay(daily, 0);
        WeatherDay tomorrow = readWeatherDay(daily, 1);
        AlertEvaluation alert = evaluateTemperatureAlert(today, tomorrow);

        double currentTemp = round1(current.optDouble("temperature_2m", 0));
        double feelsLike = round1(current.optDouble("apparent_temperature", 0));
        int humidity = current.optInt("relative_humidity_2m", 0);
        double windSpeed = round1(current.optDouble("wind_speed_10m", 0));
        int currentCode = current.optInt("weather_code", 0);
        String currentCondition = weatherCodeToText(currentCode);

        JSONObject currentObj = new JSONObject();
        currentObj.put("time", current.optString("time", ""));
        currentObj.put("temperature", currentTemp);
        currentObj.put("feelsLike", feelsLike);
        currentObj.put("humidity", humidity);
        currentObj.put("windSpeed", windSpeed);
        currentObj.put("condition", currentCondition);
        currentObj.put("weatherCode", currentCode);

        JSONObject comparison = new JSONObject();
        comparison.put("avgTempDelta", alert.avgTempDelta);
        comparison.put("maxTempDelta", alert.maxTempDelta);
        comparison.put("minTempDelta", alert.minTempDelta);
        comparison.put("precipitationDelta", alert.precipitationDelta);
        comparison.put("windSpeedDelta", alert.windSpeedDelta);
        comparison.put("weatherChanged", alert.weatherChanged);
        comparison.put("temperatureTrend", alert.temperatureTrend);

        JSONObject alertObj = new JSONObject();
        alertObj.put("level", alert.level);
        alertObj.put("color", alert.color);
        alertObj.put("title", alert.title);
        alertObj.put("message", alert.message);
        alertObj.put("advice", alert.advice);

        JSONObject out = new JSONObject();
        out.put("city", location.label);
        out.put("current", currentObj);
        out.put("today", today.toJson());
        out.put("tomorrow", tomorrow.toJson());
        out.put("comparison", comparison);
        out.put("alert", alertObj);

        JSONObject r = new JSONObject();
        r.put("success", true);
        r.put("output", out.toString());
        r.put("_displayText", buildTemperatureAlertText(location.label, currentTemp, currentCondition, today, tomorrow, alert));
        r.put("_displayHtml", buildTemperatureAlertHtml(location.label, currentTemp, currentCondition, feelsLike, humidity, today, tomorrow, alert));
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
        String html = HtmlOutputHelper.card("\uD83C\uDF24\uFE0F",
                isZh() ? label + " 当前天气" : label + " Current Weather",
                HtmlOutputHelper.metricGrid(new String[][]{
                        {temp + "\u00B0C", isZh() ? "温度" : "Temperature"},
                        {windSpeed + " km/h", isZh() ? "风速" : "Wind"},
                        {humidity + "%", isZh() ? "湿度" : "Humidity"}
                }) +
                        HtmlOutputHelper.keyValue(new String[][]{
                                {isZh() ? "天气" : "Condition", condition},
                                {isZh() ? "体感温度" : "Feels Like", feelsLike + "\u00B0C"},
                                {isZh() ? "风向" : "Wind Dir.", windDir + "\u00B0"}
                        })
        );
        r.put("_displayHtml", html);
        return r.toString();
    }

    private ResolvedLocation resolveLocation(Context context, String city) throws Exception {
        if (city == null || city.trim().isEmpty()) {
            Location loc = getLastLocation(context);
            if (loc == null) {
                throw new IllegalArgumentException(isZh()
                        ? "未能获取位置信息，请提供城市名称或开启定位"
                        : "Location unavailable, please provide a city name or enable GPS");
            }
            return new ResolvedLocation(isZh() ? "当前位置" : "Current Location", loc.getLatitude(), loc.getLongitude());
        }
        double[] coords = geocode(city.trim());
        return new ResolvedLocation(city.trim(), coords[0], coords[1]);
    }

    private static WeatherDay readWeatherDay(JSONObject daily, int index) throws Exception {
        JSONArray dates = daily.getJSONArray("time");
        JSONArray codes = daily.getJSONArray("weather_code");
        JSONArray maxTemps = daily.getJSONArray("temperature_2m_max");
        JSONArray minTemps = daily.getJSONArray("temperature_2m_min");
        JSONArray precipProbs = daily.optJSONArray("precipitation_probability_max");
        JSONArray windMaxs = daily.optJSONArray("wind_speed_10m_max");

        int code = codes.optInt(index, 0);
        double maxT = round1(maxTemps.optDouble(index, 0));
        double minT = round1(minTemps.optDouble(index, 0));
        int precip = precipProbs != null ? precipProbs.optInt(index, 0) : 0;
        double windMax = round1(windMaxs != null ? windMaxs.optDouble(index, 0) : 0);
        return new WeatherDay(dates.getString(index), code, weatherCodeToText(code), maxT, minT, precip, windMax);
    }

    private static AlertEvaluation evaluateTemperatureAlert(WeatherDay today, WeatherDay tomorrow) {
        AlertEvaluation alert = new AlertEvaluation();
        alert.avgTempDelta = round1(tomorrow.avgTemp() - today.avgTemp());
        alert.maxTempDelta = round1(tomorrow.maxTemp - today.maxTemp);
        alert.minTempDelta = round1(tomorrow.minTemp - today.minTemp);
        alert.precipitationDelta = tomorrow.precipProbability - today.precipProbability;
        alert.windSpeedDelta = round1(tomorrow.windSpeedMax - today.windSpeedMax);
        alert.weatherChanged = weatherCategory(today.weatherCode) != weatherCategory(tomorrow.weatherCode);
        alert.temperatureTrend = alert.avgTempDelta >= 2 ? "warming" : (alert.avgTempDelta <= -2 ? "cooling" : "stable");

        double tempMagnitude = Math.max(Math.abs(alert.avgTempDelta),
                Math.max(Math.abs(alert.maxTempDelta), Math.abs(alert.minTempDelta)));
        int score = 0;
        if (tempMagnitude >= 12) score = 3;
        else if (tempMagnitude >= 8) score = 2;
        else if (tempMagnitude >= 4) score = 1;

        boolean severeTomorrow = isSevereWeatherCode(tomorrow.weatherCode);
        boolean wetWorsening = isWetWeatherCode(tomorrow.weatherCode) && !isWetWeatherCode(today.weatherCode);
        boolean windyTomorrow = tomorrow.windSpeedMax >= 30 || alert.windSpeedDelta >= 15;
        if (severeTomorrow) score = Math.max(score, 3);
        else if (wetWorsening) score = Math.max(score, 2);
        if (alert.precipitationDelta >= 50) score = Math.max(score, 2);
        else if (alert.precipitationDelta >= 30) score = Math.max(score, 1);
        if (tomorrow.windSpeedMax >= 45 || alert.windSpeedDelta >= 25) score = Math.max(score, 3);
        else if (windyTomorrow) score = Math.max(score, 2);

        boolean zh = isZh();
        if (score >= 3) {
            alert.level = "severe";
            alert.color = "red";
            alert.title = zh ? "强预警" : "Severe alert";
            alert.message = zh ? "明天较今天天气变化很大，建议提前调整出行和穿着。"
                    : "Tomorrow changes sharply compared with today. Adjust travel and clothing in advance.";
        } else if (score == 2) {
            alert.level = "warning";
            alert.color = "orange";
            alert.title = zh ? "明显变化" : "Noticeable change";
            alert.message = zh ? "明天温差、降水或风力变化明显，建议提前留意。"
                    : "Temperature, precipitation, or wind changes are noticeable tomorrow.";
        } else if (score == 1) {
            alert.level = "notice";
            alert.color = "blue";
            alert.title = zh ? "轻微变化" : "Small change";
            alert.message = zh ? "明天有轻微变化，按需调整穿着。"
                    : "Tomorrow has a small weather change. Adjust clothing as needed.";
        } else {
            alert.level = "normal";
            alert.color = "green";
            alert.title = zh ? "天气平稳" : "Stable";
            alert.message = zh ? "今天到明天天气较平稳。"
                    : "Weather stays relatively stable from today to tomorrow.";
        }

        StringBuilder advice = new StringBuilder();
        if (zh) {
            if ("cooling".equals(alert.temperatureTrend)) {
                advice.append("明天更凉，建议加衣，早晚温差请重点关注。");
            } else if ("warming".equals(alert.temperatureTrend)) {
                advice.append("明天更暖，注意补水和防晒，室外活动避开高温时段。");
            } else {
                advice.append("温度变化不大，按今天的穿着准备即可。");
            }
            if (wetWorsening || tomorrow.precipProbability >= 50) {
                advice.append(" 明天降水概率较高，建议备伞。");
            }
            if (windyTomorrow) {
                advice.append(" 风力偏大，注意固定户外物品。");
            }
        } else {
            if ("cooling".equals(alert.temperatureTrend)) {
                advice.append("It will be cooler tomorrow. Add layers and watch morning/evening temperature swings.");
            } else if ("warming".equals(alert.temperatureTrend)) {
                advice.append("It will be warmer tomorrow. Stay hydrated and consider sun protection.");
            } else {
                advice.append("Temperature is steady. Today's clothing plan should still work.");
            }
            if (wetWorsening || tomorrow.precipProbability >= 50) {
                advice.append(" Rain chance is higher tomorrow, so carry an umbrella.");
            }
            if (windyTomorrow) {
                advice.append(" Wind may be strong; secure outdoor items.");
            }
        }
        alert.advice = advice.toString();
        return alert;
    }

    private static String buildTemperatureAlertText(String city, double currentTemp, String currentCondition,
                                                    WeatherDay today, WeatherDay tomorrow, AlertEvaluation alert) {
        boolean zh = isZh();
        String trend = zh
                ? ("warming".equals(alert.temperatureTrend) ? "升温" : ("cooling".equals(alert.temperatureTrend) ? "降温" : "平稳"))
                : ("warming".equals(alert.temperatureTrend) ? "warmer" : ("cooling".equals(alert.temperatureTrend) ? "cooler" : "stable"));
        if (zh) {
            return "🌡️ " + city + " 气温预警：" + alert.title + "\n"
                    + "━━━━━━━━━━━━━━━━\n"
                    + "▸ 当前：" + formatTemp(currentTemp) + "，" + currentCondition + "\n"
                    + "▸ 今天：" + today.date + "，" + formatTempRange(today) + "，" + today.condition + "\n"
                    + "▸ 明天：" + tomorrow.date + "，" + formatTempRange(tomorrow) + "，" + tomorrow.condition + "\n"
                    + "▸ 均温变化：" + formatDelta(alert.avgTempDelta) + "，趋势：" + trend + "\n"
                    + "▸ 降水概率变化：" + signedInt(alert.precipitationDelta) + "%，风速变化：" + formatDelta(alert.windSpeedDelta).replace("\u00B0C", " km/h") + "\n"
                    + alert.message + "\n"
                    + alert.advice;
        }
        return "🌡️ " + city + " temperature alert: " + alert.title + "\n"
                + "────────────────\n"
                + "▸ Now: " + formatTemp(currentTemp) + ", " + currentCondition + "\n"
                + "▸ Today: " + today.date + ", " + formatTempRange(today) + ", " + today.condition + "\n"
                + "▸ Tomorrow: " + tomorrow.date + ", " + formatTempRange(tomorrow) + ", " + tomorrow.condition + "\n"
                + "▸ Average change: " + formatDelta(alert.avgTempDelta) + ", trend: " + trend + "\n"
                + "▸ Rain chance change: " + signedInt(alert.precipitationDelta) + "%, wind change: " + formatDelta(alert.windSpeedDelta).replace("\u00B0C", " km/h") + "\n"
                + alert.message + "\n"
                + alert.advice;
    }

    private static String buildTemperatureAlertHtml(String city, double currentTemp, String currentCondition,
                                                    double feelsLike, int humidity, WeatherDay today,
                                                    WeatherDay tomorrow, AlertEvaluation alert) {
        boolean zh = isZh();
        String body = HtmlOutputHelper.badge(alert.title, alert.color)
                + HtmlOutputHelper.metricGrid(new String[][]{
                        {formatTemp(currentTemp), zh ? "当前" : "Now"},
                        {formatTempRange(today), zh ? "今天" : "Today"},
                        {formatTempRange(tomorrow), zh ? "明天" : "Tomorrow"},
                        {formatDelta(alert.avgTempDelta), zh ? "均温变化" : "Avg change"}
                })
                + HtmlOutputHelper.keyValue(new String[][]{
                        {zh ? "当前天气" : "Current", currentCondition + " / " + (zh ? "体感 " : "feels ") + formatTemp(feelsLike)},
                        {zh ? "湿度" : "Humidity", humidity + "%"},
                        {zh ? "天气变化" : "Weather change", today.condition + " -> " + tomorrow.condition},
                        {zh ? "降水概率" : "Rain chance", today.precipProbability + "% -> " + tomorrow.precipProbability + "%"},
                        {zh ? "最大风速" : "Max wind", formatSpeed(today.windSpeedMax) + " -> " + formatSpeed(tomorrow.windSpeedMax)},
                        {zh ? "判断" : "Assessment", alert.message},
                        {zh ? "建议" : "Advice", alert.advice}
                });
        return HtmlOutputHelper.card("🌡️", zh ? city + " 气温预警" : city + " Temperature Alert", body);
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static String formatTemp(double value) {
        return String.format(Locale.US, "%.1f\u00B0C", round1(value));
    }

    private static String formatDelta(double value) {
        return String.format(Locale.US, "%+.1f\u00B0C", round1(value));
    }

    private static String formatSpeed(double value) {
        return String.format(Locale.US, "%.1f km/h", round1(value));
    }

    private static String formatTempRange(WeatherDay day) {
        return formatTemp(day.minTemp) + "~" + formatTemp(day.maxTemp);
    }

    private static String signedInt(int value) {
        return value > 0 ? "+" + value : String.valueOf(value);
    }

    private static boolean isWetWeatherCode(int code) {
        return (code >= 51 && code <= 67) || (code >= 80 && code <= 82) || code >= 95;
    }

    private static boolean isSevereWeatherCode(int code) {
        return code == 65 || code == 67 || code == 75 || code == 82 || code == 86 || code == 95 || code == 96 || code == 99;
    }

    private static int weatherCategory(int code) {
        if (code == 0 || code == 1) return 0;
        if (code == 2 || code == 3) return 1;
        if (code == 45 || code == 48) return 2;
        if (code >= 51 && code <= 67) return 3;
        if (code >= 71 && code <= 77) return 4;
        if (code >= 80 && code <= 86) return 5;
        if (code >= 95) return 6;
        return 7;
    }

    private static class ResolvedLocation {
        final String label;
        final double latitude;
        final double longitude;

        ResolvedLocation(String label, double latitude, double longitude) {
            this.label = label;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    private static class WeatherDay {
        final String date;
        final int weatherCode;
        final String condition;
        final double maxTemp;
        final double minTemp;
        final int precipProbability;
        final double windSpeedMax;

        WeatherDay(String date, int weatherCode, String condition, double maxTemp,
                   double minTemp, int precipProbability, double windSpeedMax) {
            this.date = date;
            this.weatherCode = weatherCode;
            this.condition = condition;
            this.maxTemp = maxTemp;
            this.minTemp = minTemp;
            this.precipProbability = precipProbability;
            this.windSpeedMax = windSpeedMax;
        }

        double avgTemp() {
            return round1((maxTemp + minTemp) / 2.0);
        }

        JSONObject toJson() throws Exception {
            JSONObject obj = new JSONObject();
            obj.put("date", date);
            obj.put("condition", condition);
            obj.put("weatherCode", weatherCode);
            obj.put("tempMax", maxTemp);
            obj.put("tempMin", minTemp);
            obj.put("tempAvg", avgTemp());
            obj.put("precipProbability", precipProbability);
            obj.put("windSpeedMax", windSpeedMax);
            return obj;
        }
    }

    private static class AlertEvaluation {
        String level;
        String color;
        String title;
        String message;
        String advice;
        String temperatureTrend;
        double avgTempDelta;
        double maxTempDelta;
        double minTempDelta;
        int precipitationDelta;
        double windSpeedDelta;
        boolean weatherChanged;
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
