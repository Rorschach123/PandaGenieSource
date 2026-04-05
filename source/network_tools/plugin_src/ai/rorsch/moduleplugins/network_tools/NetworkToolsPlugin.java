package ai.rorsch.moduleplugins.network_tools;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.text.TextUtils;

import ai.rorsch.pandagenie.module.runtime.ModulePlugin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class NetworkToolsPlugin implements ModulePlugin {

    private static final int PING_TIMEOUT_SEC = 20;
    private static final int PUBLIC_IP_TIMEOUT_MS = 5000;
    private static final Pattern SAFE_HOST = Pattern.compile("^[a-zA-Z0-9.:\\-_]+$");

    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        try {
            JSONObject params = new JSONObject(emptyJson(paramsJson));
            switch (action) {
                case "ping":
                    return ok(ping(params.optString("host", "").trim()));
                case "dnsLookup":
                    return ok(dnsLookup(params.optString("domain", "").trim()));
                case "getLocalIp":
                    return ok(getLocalIp());
                case "getPublicIp":
                    return ok(getPublicIp());
                case "checkConnectivity":
                    return ok(checkConnectivity(context));
                case "getNetworkInfo":
                    return ok(getNetworkInfo(context));
                default:
                    return error("Unsupported action: " + action);
            }
        } catch (Exception e) {
            String msg = e.getMessage();
            return error(msg != null && !msg.isEmpty() ? msg : e.getClass().getSimpleName());
        }
    }

    private static String ping(String host) throws Exception {
        if (TextUtils.isEmpty(host)) {
            throw new IllegalArgumentException("host is required");
        }
        if (!SAFE_HOST.matcher(host).matches()) {
            throw new IllegalArgumentException("invalid host characters");
        }
        Process process = Runtime.getRuntime().exec(new String[]{"ping", "-c", "4", host});
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        Thread drainOut = new Thread(() -> drainStream(process.getInputStream(), stdout));
        Thread drainErr = new Thread(() -> drainStream(process.getErrorStream(), stderr));
        drainOut.setDaemon(true);
        drainErr.setDaemon(true);
        drainOut.start();
        drainErr.start();
        boolean finished;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            finished = process.waitFor(PING_TIMEOUT_SEC, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
        } else {
            finished = waitForProcess(process, PING_TIMEOUT_SEC * 1000L);
            if (!finished) {
                process.destroyForcibly();
                waitForProcess(process, 2000);
            }
        }
        drainOut.join(3000);
        drainErr.join(3000);
        int exit = Integer.MIN_VALUE;
        try {
            exit = process.exitValue();
        } catch (IllegalThreadStateException ignored) {
            // still running or destroyed without exitValue
        }
        JSONObject o = new JSONObject();
        o.put("host", host);
        o.put("command", "ping -c 4 " + host);
        o.put("stdout", stdout.toString());
        o.put("stderr", stderr.toString());
        o.put("exitCode", exit);
        o.put("timedOut", !finished);
        return o.toString();
    }

    /**
     * Poll until process exits or timeoutMs elapses. Returns true if exited normally.
     */
    private static boolean waitForProcess(Process process, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                process.exitValue();
                return true;
            } catch (IllegalThreadStateException e) {
                Thread.sleep(50);
            }
        }
        return false;
    }

    private static void drainStream(InputStream in, StringBuilder sink) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sink.append(line).append('\n');
            }
        } catch (Exception ignored) {
            // stream closed after destroy
        }
    }

    private static String dnsLookup(String domain) throws Exception {
        if (TextUtils.isEmpty(domain)) {
            throw new IllegalArgumentException("domain is required");
        }
        InetAddress[] all = InetAddress.getAllByName(domain);
        JSONArray ips = new JSONArray();
        for (InetAddress a : all) {
            ips.put(a.getHostAddress());
        }
        JSONObject o = new JSONObject();
        o.put("domain", domain);
        o.put("addresses", ips);
        return o.toString();
    }

    private static String getLocalIp() throws Exception {
        JSONArray ipv4 = new JSONArray();
        JSONArray ipv6 = new JSONArray();
        Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
        if (en == null) {
            JSONObject o = new JSONObject();
            o.put("ipv4", ipv4);
            o.put("ipv6", ipv6);
            return o.toString();
        }
        ArrayList<NetworkInterface> list = Collections.list(en);
        for (NetworkInterface nif : list) {
            if (!nif.isUp() || nif.isLoopback()) {
                continue;
            }
            Enumeration<InetAddress> addrs = nif.getInetAddresses();
            while (addrs.hasMoreElements()) {
                InetAddress addr = addrs.nextElement();
                if (addr.isLoopbackAddress()) {
                    continue;
                }
                String host = addr.getHostAddress();
                if (host == null) {
                    continue;
                }
                if (addr.getAddress().length == 4) {
                    ipv4.put(host);
                } else {
                    ipv6.put(host);
                }
            }
        }
        JSONObject o = new JSONObject();
        o.put("ipv4", ipv4);
        o.put("ipv6", ipv6);
        return o.toString();
    }

    private static String getPublicIp() throws Exception {
        URL url = new URL("https://api.ipify.org?format=json");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(PUBLIC_IP_TIMEOUT_MS);
        conn.setReadTimeout(PUBLIC_IP_TIMEOUT_MS);
        conn.setRequestMethod("GET");
        conn.setInstanceFollowRedirects(true);
        int code = conn.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        String body = readFully(stream);
        conn.disconnect();
        JSONObject o = new JSONObject();
        o.put("httpStatus", code);
        o.put("body", body);
        if (code >= 200 && code < 300) {
            try {
                JSONObject parsed = new JSONObject(body);
                o.put("ip", parsed.optString("ip", ""));
            } catch (Exception e) {
                o.put("ip", JSONObject.NULL);
            }
        } else {
            o.put("ip", JSONObject.NULL);
        }
        return o.toString();
    }

    private static String readFully(InputStream in) throws Exception {
        if (in == null) {
            return "";
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }

    private static String checkConnectivity(Context context) throws Exception {
        ConnectivityManager cm =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        JSONObject o = new JSONObject();
        if (cm == null) {
            o.put("hasConnectivityService", false);
            return o.toString();
        }
        o.put("hasConnectivityService", true);
        Network network = cm.getActiveNetwork();
        if (network == null) {
            o.put("activeNetwork", false);
            return o.toString();
        }
        o.put("activeNetwork", true);
        NetworkCapabilities nc = cm.getNetworkCapabilities(network);
        if (nc == null) {
            o.put("capabilities", JSONObject.NULL);
            return o.toString();
        }
        o.put("hasInternetCapability", nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET));
        o.put("validated", nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
        o.put("metered", !nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
        JSONArray transports = new JSONArray();
        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            transports.put("wifi");
        }
        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            transports.put("cellular");
        }
        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            transports.put("ethernet");
        }
        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) {
            transports.put("bluetooth");
        }
        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            transports.put("vpn");
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1
                && nc.hasTransport(NetworkCapabilities.TRANSPORT_LOWPAN)) {
            transports.put("lowpan");
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE)) {
            transports.put("wifi_aware");
        }
        o.put("transports", transports);
        return o.toString();
    }

    private static String getNetworkInfo(Context context) throws Exception {
        ConnectivityManager cm =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        JSONObject o = new JSONObject();
        if (cm == null) {
            o.put("networkType", "unknown");
            return o.toString();
        }
        Network network = cm.getActiveNetwork();
        if (network == null) {
            o.put("networkType", "none");
            o.put("connected", false);
            return o.toString();
        }
        NetworkCapabilities nc = cm.getNetworkCapabilities(network);
        String type = "other";
        if (nc != null) {
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                type = "wifi";
            } else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                type = "mobile";
            } else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                type = "ethernet";
            } else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                type = "vpn";
            }
            o.put("connected", nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET));
        } else {
            o.put("connected", false);
        }
        o.put("networkType", type);

        WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null && "wifi".equals(type)) {
            WifiInfo info = wm.getConnectionInfo();
            if (info != null) {
                o.put("wifiSsid", safeSsid(info.getSSID()));
                o.put("wifiBssid", info.getBSSID() != null ? info.getBSSID() : JSONObject.NULL);
                o.put("linkSpeedMbps", info.getLinkSpeed());
                o.put("frequencyMhz", info.getFrequency());
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    o.put("rxLinkSpeedMbps", info.getRxLinkSpeedMbps());
                } else {
                    o.put("rxLinkSpeedMbps", JSONObject.NULL);
                }
            } else {
                o.put("wifiSsid", JSONObject.NULL);
                o.put("linkSpeedMbps", JSONObject.NULL);
            }
        } else {
            o.put("wifiSsid", JSONObject.NULL);
            o.put("linkSpeedMbps", JSONObject.NULL);
        }
        return o.toString();
    }

    private static String safeSsid(String raw) {
        if (raw == null || "<unknown ssid>".equalsIgnoreCase(raw) || "\"<unknown ssid>\"".equals(raw)) {
            return "";
        }
        if (raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
            return raw.substring(1, raw.length() - 1);
        }
        return raw;
    }

    private static String emptyJson(String v) {
        return v == null || v.trim().isEmpty() ? "{}" : v;
    }

    private static String ok(String output) throws Exception {
        return new JSONObject().put("success", true).put("output", output).toString();
    }

    private static String error(String msg) throws Exception {
        return new JSONObject().put("success", false).put("error", msg).toString();
    }
}
