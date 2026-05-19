package ai.rorsch.moduleplugins.location_helper;

import ai.rorsch.pandagenie.module.runtime.HtmlOutputHelper;
import ai.rorsch.pandagenie.module.runtime.ModulePlugin;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.CancellationSignal;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class LocationHelperPlugin implements ModulePlugin {
    private static final int DEFAULT_TIMEOUT_MS = 8000;
    private static final int MAX_TIMEOUT_MS = 15000;

    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        JSONObject params = new JSONObject(emptyJson(paramsJson));
        try {
            if ("getCurrentLocation".equals(action)) {
                return getCurrentLocation(context, params);
            }
            if ("reverseGeocode".equals(action)) {
                return reverseGeocode(params);
            }
            if ("openPage".equals(action)) {
                JSONObject r = new JSONObject();
                r.put("success", true);
                r.put("output", "{}");
                r.put("_openModule", true);
                r.put("_displayText", zh() ? "正在打开位置助手..." : "Opening Location Helper...");
                return r.toString();
            }
            return error("Unsupported action: " + action);
        } catch (Exception e) {
            return error(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private String getCurrentLocation(Context context, JSONObject params) throws Exception {
        ensureLocationPermission(context);

        boolean fresh = params.optBoolean("fresh", false);
        boolean includeAddress = params.optBoolean("includeAddress", true);
        boolean useNetworkFallback = params.optBoolean("useNetworkFallback", true);
        int timeoutMs = clamp(params.optInt("timeoutMs", DEFAULT_TIMEOUT_MS), 1000, MAX_TIMEOUT_MS);
        Locale locale = localeFrom(params.optString("language", ""));

        Location loc = resolveLocation(context, fresh, timeoutMs);
        if (loc == null) {
            throw new IllegalStateException(zh()
                    ? "未能获取当前位置。请确认系统定位已开启，并在设置里允许 PandaGenie 使用位置权限后重试。"
                    : "Location unavailable. Please enable system location and allow PandaGenie location permission, then retry.");
        }

        GeoInfo geo = includeAddress
                ? reverseGeocodeInternal(context, loc.getLatitude(), loc.getLongitude(), locale, useNetworkFallback)
                : new GeoInfo();

        JSONObject out = locationToJson(context, loc, geo);
        return ok(out, buildDisplayText(out), buildDisplayHtml(out), buildRichContent(out));
    }

    private String reverseGeocode(JSONObject params) throws Exception {
        double lat = params.optDouble("latitude", Double.NaN);
        double lon = params.optDouble("longitude", Double.NaN);
        if (Double.isNaN(lat) || Double.isNaN(lon)) {
            throw new IllegalArgumentException(zh() ? "请提供 latitude 和 longitude" : "latitude and longitude are required");
        }
        Locale locale = localeFrom(params.optString("language", ""));
        boolean useNetworkFallback = params.optBoolean("useNetworkFallback", true);
        GeoInfo geo = reverseGeocodeInternal(null, lat, lon, locale, useNetworkFallback);

        JSONObject out = new JSONObject();
        out.put("latitude", lat);
        out.put("longitude", lon);
        addGeo(out, geo);
        return ok(out, buildReverseDisplayText(out), buildReverseDisplayHtml(out), buildRichContent(out));
    }

    @SuppressLint("MissingPermission")
    private Location resolveLocation(Context context, boolean fresh, int timeoutMs) {
        Location last = getBestLastKnownLocation(context);
        if (!fresh && last != null) return last;

        Location current = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            current = getCurrentLocationApi30(context, timeoutMs);
        }
        if (current != null) return current;
        return last;
    }

    @SuppressLint("MissingPermission")
    private Location getBestLastKnownLocation(Context context) {
        LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return null;
        boolean fine = hasFineLocation(context);
        boolean coarse = hasCoarseLocation(context);
        Location best = null;
        String[] providers = new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER};
        for (String provider : providers) {
            if (LocationManager.GPS_PROVIDER.equals(provider) && !fine) continue;
            if (!fine && !coarse) continue;
            try {
                Location loc = lm.getLastKnownLocation(provider);
                if (loc == null) continue;
                if (best == null || loc.getTime() > best.getTime() ||
                        (loc.getTime() == best.getTime() && loc.getAccuracy() < best.getAccuracy())) {
                    best = loc;
                }
            } catch (Exception ignored) {
            }
        }
        return best;
    }

    @SuppressLint("MissingPermission")
    private Location getCurrentLocationApi30(Context context, int timeoutMs) {
        LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return null;
        boolean fine = hasFineLocation(context);
        boolean coarse = hasCoarseLocation(context);
        String[] providers = fine
                ? new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}
                : new String[]{LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER};
        long deadline = System.currentTimeMillis() + timeoutMs;
        for (String provider : providers) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) break;
            try {
                if (!lm.isProviderEnabled(provider)) continue;
                if (LocationManager.GPS_PROVIDER.equals(provider) && !fine) continue;
                if (!fine && !coarse) continue;
                final CountDownLatch latch = new CountDownLatch(1);
                final AtomicReference<Location> result = new AtomicReference<>();
                final CancellationSignal signal = new CancellationSignal();
                lm.getCurrentLocation(provider, signal, Runnable::run, new Consumer<Location>() {
                    @Override
                    public void accept(Location location) {
                        result.set(location);
                        latch.countDown();
                    }
                });
                latch.await(Math.min(remaining, 6000), TimeUnit.MILLISECONDS);
                signal.cancel();
                if (result.get() != null) return result.get();
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private GeoInfo reverseGeocodeInternal(Context context, double lat, double lon, Locale locale, boolean networkFallback) {
        GeoInfo geo = new GeoInfo();
        if (context != null) {
            try {
                if (Geocoder.isPresent()) {
                    Geocoder geocoder = new Geocoder(context, locale);
                    List<Address> addresses = geocoder.getFromLocation(lat, lon, 1);
                    if (addresses != null && !addresses.isEmpty()) {
                        geo = fromAddress(addresses.get(0));
                        geo.source = "android_geocoder";
                    }
                }
            } catch (Exception ignored) {
            }
        }
        if (geo.hasUsefulAddress() || !networkFallback) return geo;
        try {
            GeoInfo remote = reverseByNetwork(lat, lon, locale);
            if (remote.hasUsefulAddress()) return remote;
        } catch (Exception ignored) {
        }
        return geo;
    }

    private GeoInfo fromAddress(Address a) {
        GeoInfo g = new GeoInfo();
        g.country = value(a.getCountryName());
        g.countryCode = value(a.getCountryCode());
        g.province = value(a.getAdminArea());
        g.city = firstNonEmpty(a.getLocality(), a.getSubAdminArea(), a.getAdminArea());
        g.district = firstNonEmpty(a.getSubLocality(), a.getSubAdminArea());
        g.street = firstNonEmpty(a.getThoroughfare(), a.getFeatureName());
        g.postalCode = value(a.getPostalCode());
        g.address = value(a.getAddressLine(0));
        return g;
    }

    private GeoInfo reverseByNetwork(double lat, double lon, Locale locale) throws Exception {
        String language = locale.getLanguage().startsWith("zh") ? "zh" : "en";
        String url = "https://api.bigdatacloud.net/data/reverse-geocode-client?latitude="
                + lat + "&longitude=" + lon + "&localityLanguage=" + language;
        JSONObject o = new JSONObject(httpGet(url));
        GeoInfo g = new GeoInfo();
        g.country = o.optString("countryName", "");
        g.countryCode = o.optString("countryCode", "");
        g.province = o.optString("principalSubdivision", "");
        g.city = firstNonEmpty(o.optString("city", ""), o.optString("locality", ""), o.optString("principalSubdivision", ""));
        g.district = o.optString("locality", "");
        g.address = firstNonEmpty(o.optString("locality", ""), o.optString("city", ""), o.optString("principalSubdivision", ""));
        g.source = "bigdatacloud";
        return g;
    }

    private JSONObject locationToJson(Context context, Location loc, GeoInfo geo) throws Exception {
        JSONObject out = new JSONObject();
        out.put("latitude", round6(loc.getLatitude()));
        out.put("longitude", round6(loc.getLongitude()));
        out.put("hasAltitude", loc.hasAltitude());
        out.put("altitudeMeters", loc.hasAltitude() ? round1(loc.getAltitude()) : JSONObject.NULL);
        out.put("accuracyMeters", loc.hasAccuracy() ? round1(loc.getAccuracy()) : JSONObject.NULL);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            out.put("verticalAccuracyMeters", loc.hasVerticalAccuracy() ? round1(loc.getVerticalAccuracyMeters()) : JSONObject.NULL);
        }
        out.put("provider", value(loc.getProvider()));
        out.put("timeMillis", loc.getTime());
        out.put("time", formatTime(loc.getTime()));
        long age = loc.getTime() > 0 ? Math.max(0, (System.currentTimeMillis() - loc.getTime()) / 1000) : -1;
        out.put("ageSeconds", age);
        out.put("speedMps", loc.hasSpeed() ? round2(loc.getSpeed()) : JSONObject.NULL);
        out.put("bearingDegrees", loc.hasBearing() ? round1(loc.getBearing()) : JSONObject.NULL);
        out.put("mock", Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? loc.isMock() : loc.isFromMockProvider());
        out.put("locationServicesEnabled", isLocationServiceEnabled(context));
        addGeo(out, geo);
        return out;
    }

    private void addGeo(JSONObject out, GeoInfo geo) throws Exception {
        out.put("country", value(geo.country));
        out.put("countryCode", value(geo.countryCode));
        out.put("province", value(geo.province));
        out.put("city", value(geo.city));
        out.put("district", value(geo.district));
        out.put("street", value(geo.street));
        out.put("postalCode", value(geo.postalCode));
        out.put("address", value(geo.address));
        out.put("geocodeSource", value(geo.source));
    }

    private String buildDisplayText(JSONObject o) {
        if (zh()) {
            return "📍 当前位置信息\n"
                    + "━━━━━━━━━━━━━━━━\n"
                    + "城市/国家: " + cityCountry(o) + "\n"
                    + "地址: " + dash(o.optString("address", "")) + "\n"
                    + "经纬度: " + o.optDouble("latitude") + ", " + o.optDouble("longitude") + "\n"
                    + "海拔: " + formatMeters(o, "altitudeMeters") + "\n"
                    + "精度: " + formatAccuracy(o) + "\n"
                    + "来源: " + dash(o.optString("provider", "")) + " · " + ageText(o.optLong("ageSeconds", -1)) + "\n"
                    + "时间: " + dash(o.optString("time", ""));
        }
        return "📍 Current Location\n"
                + "────────────────\n"
                + "City/Country: " + cityCountry(o) + "\n"
                + "Address: " + dash(o.optString("address", "")) + "\n"
                + "Coordinates: " + o.optDouble("latitude") + ", " + o.optDouble("longitude") + "\n"
                + "Altitude: " + formatMeters(o, "altitudeMeters") + "\n"
                + "Accuracy: " + formatAccuracy(o) + "\n"
                + "Provider: " + dash(o.optString("provider", "")) + " · " + ageText(o.optLong("ageSeconds", -1)) + "\n"
                + "Time: " + dash(o.optString("time", ""));
    }

    private String buildReverseDisplayText(JSONObject o) {
        if (zh()) {
            return "📍 坐标地址解析\n"
                    + "━━━━━━━━━━━━━━━━\n"
                    + "城市/国家: " + cityCountry(o) + "\n"
                    + "地址: " + dash(o.optString("address", "")) + "\n"
                    + "经纬度: " + o.optDouble("latitude") + ", " + o.optDouble("longitude");
        }
        return "📍 Reverse Geocode\n"
                + "────────────────\n"
                + "City/Country: " + cityCountry(o) + "\n"
                + "Address: " + dash(o.optString("address", "")) + "\n"
                + "Coordinates: " + o.optDouble("latitude") + ", " + o.optDouble("longitude");
    }

    private String buildDisplayHtml(JSONObject o) {
        String body = HtmlOutputHelper.metricGrid(new String[][]{
                {String.valueOf(o.optDouble("latitude")), zh() ? "纬度" : "Latitude"},
                {String.valueOf(o.optDouble("longitude")), zh() ? "经度" : "Longitude"},
                {formatMeters(o, "altitudeMeters"), zh() ? "海拔" : "Altitude"},
                {formatAccuracy(o), zh() ? "精度" : "Accuracy"}
        }) + HtmlOutputHelper.keyValue(new String[][]{
                {zh() ? "城市/国家" : "City/Country", cityCountry(o)},
                {zh() ? "地址" : "Address", dash(o.optString("address", ""))},
                {zh() ? "定位来源" : "Provider", dash(o.optString("provider", ""))},
                {zh() ? "更新时间" : "Updated", ageText(o.optLong("ageSeconds", -1))},
                {zh() ? "地址来源" : "Geocoder", dash(o.optString("geocodeSource", ""))}
        });
        return HtmlOutputHelper.card("📍", zh() ? "当前位置信息" : "Current Location", body);
    }

    private String buildReverseDisplayHtml(JSONObject o) {
        String body = HtmlOutputHelper.metricGrid(new String[][]{
                {String.valueOf(o.optDouble("latitude")), zh() ? "纬度" : "Latitude"},
                {String.valueOf(o.optDouble("longitude")), zh() ? "经度" : "Longitude"}
        }) + HtmlOutputHelper.keyValue(new String[][]{
                {zh() ? "城市/国家" : "City/Country", cityCountry(o)},
                {zh() ? "地址" : "Address", dash(o.optString("address", ""))},
                {zh() ? "地址来源" : "Geocoder", dash(o.optString("geocodeSource", ""))}
        });
        return HtmlOutputHelper.card("📍", zh() ? "坐标地址解析" : "Reverse Geocode", body);
    }

    private JSONArray buildRichContent(JSONObject o) throws Exception {
        JSONArray arr = new JSONArray();
        JSONObject loc = new JSONObject();
        loc.put("type", "location");
        loc.put("latitude", o.optDouble("latitude"));
        loc.put("longitude", o.optDouble("longitude"));
        loc.put("address", firstNonEmpty(o.optString("address", ""), cityCountry(o)));
        loc.put("title", zh() ? "当前位置" : "Current Location");
        arr.put(loc);
        return arr;
    }

    private String ok(JSONObject output, String displayText, String displayHtml, JSONArray rich) throws Exception {
        JSONObject r = new JSONObject();
        r.put("success", true);
        r.put("output", output.toString());
        r.put("_displayText", displayText);
        r.put("_displayHtml", displayHtml);
        if (rich != null && rich.length() > 0) r.put("_richContent", rich);
        return r.toString();
    }

    private String error(String message) throws Exception {
        JSONObject r = new JSONObject();
        r.put("success", false);
        r.put("error", message == null || message.trim().isEmpty() ? "Unknown error" : message);
        return r.toString();
    }

    private void ensureLocationPermission(Context context) {
        if (!hasFineLocation(context) && !hasCoarseLocation(context)) {
            throw new SecurityException(zh()
                    ? "需要位置权限。请为 PandaGenie 授权精确位置或大致位置后重试。"
                    : "Location permission required. Please grant precise or approximate location to PandaGenie and retry.");
        }
    }

    private boolean hasFineLocation(Context context) {
        return context.checkPermission(Manifest.permission.ACCESS_FINE_LOCATION,
                android.os.Process.myPid(), android.os.Process.myUid()) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasCoarseLocation(Context context) {
        return context.checkPermission(Manifest.permission.ACCESS_COARSE_LOCATION,
                android.os.Process.myPid(), android.os.Process.myUid()) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isLocationServiceEnabled(Context context) {
        try {
            LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return lm.isLocationEnabled();
            return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception e) {
            return false;
        }
    }

    private Locale localeFrom(String language) {
        String lang = language == null ? "" : language.trim().toLowerCase(Locale.ROOT);
        if (lang.startsWith("zh")) return Locale.SIMPLIFIED_CHINESE;
        if (lang.startsWith("en")) return Locale.ENGLISH;
        return Locale.getDefault();
    }

    private static String httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestProperty("User-Agent", "PandaGenie-LocationHelper/1.0");
        try {
            int code = conn.getResponseCode();
            InputStream is = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code + ": " + sb);
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    private static String emptyJson(String s) {
        return s == null || s.trim().isEmpty() ? "{}" : s;
    }

    private static boolean zh() {
        return Locale.getDefault().getLanguage().toLowerCase(Locale.ROOT).startsWith("zh");
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String value(String s) {
        return s == null ? "" : s.trim();
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return "";
    }

    private static String dash(String value) {
        return value == null || value.trim().isEmpty() ? "-" : value.trim();
    }

    private static String cityCountry(JSONObject o) {
        String city = firstNonEmpty(o.optString("city", ""), o.optString("province", ""));
        String country = o.optString("country", "");
        if (!city.isEmpty() && !country.isEmpty()) return city + ", " + country;
        return dash(firstNonEmpty(city, country));
    }

    private static String formatMeters(JSONObject o, String key) {
        if (o.isNull(key)) return "-";
        return String.format(Locale.US, "%.1f m", o.optDouble(key));
    }

    private static String formatAccuracy(JSONObject o) {
        if (o.isNull("accuracyMeters")) return "-";
        return String.format(Locale.US, "±%.1f m", o.optDouble("accuracyMeters"));
    }

    private static String ageText(long seconds) {
        if (seconds < 0) return "-";
        if (zh()) {
            if (seconds < 60) return seconds + " 秒前";
            if (seconds < 3600) return (seconds / 60) + " 分钟前";
            return (seconds / 3600) + " 小时前";
        }
        if (seconds < 60) return seconds + "s ago";
        if (seconds < 3600) return (seconds / 60) + "m ago";
        return (seconds / 3600) + "h ago";
    }

    private static String formatTime(long timeMillis) {
        if (timeMillis <= 0) return "";
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        df.setTimeZone(TimeZone.getDefault());
        return df.format(new Date(timeMillis));
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double round6(double v) {
        return Math.round(v * 1000000.0) / 1000000.0;
    }

    private static class GeoInfo {
        String country = "";
        String countryCode = "";
        String province = "";
        String city = "";
        String district = "";
        String street = "";
        String postalCode = "";
        String address = "";
        String source = "";

        boolean hasUsefulAddress() {
            return !firstNonEmpty(country, city, address).isEmpty();
        }
    }
}
