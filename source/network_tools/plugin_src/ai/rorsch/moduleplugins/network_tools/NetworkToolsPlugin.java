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

/**
 * PandaGenie「网络工具」模块插件。
 * <p>
 * <b>模块用途：</b>在设备上执行常见网络诊断与信息查询：ping、DNS 解析、本机 IP 枚举、公网 IP 查询、
 * 连通性能力检测以及当前网络类型与 Wi‑Fi 详情（在权限与系统允许范围内）。
 * </p>
 * <p>
 * <b>对外 API（{@link #invoke} 的 {@code action}）：</b>
 * </p>
 * <ul>
 *   <li>{@code ping} — 对 {@code host} 执行系统 ping（-c 4），返回标准输出/退出码/是否超时</li>
 *   <li>{@code dnsLookup} — 解析域名到 IP 列表</li>
 *   <li>{@code getLocalIp} — 枚举非回环、已启用的网卡 IPv4/IPv6</li>
 *   <li>{@code getPublicIp} — 通过 ipify HTTP API 查询公网地址</li>
 *   <li>{@code checkConnectivity} — {@link ConnectivityManager} 能力：是否计费、传输类型等</li>
 *   <li>{@code getNetworkInfo} — 当前网络类型简况；Wi‑Fi 时附带 SSID、速率、频率等</li>
 * </ul>
 * <p>
 * 由宿主 {@code ModuleRuntime} 反射加载；ping 依赖系统 {@code ping} 命令，主机名经 {@link #SAFE_HOST} 白名单校验。
 * </p>
 */
public class NetworkToolsPlugin implements ModulePlugin {

    /** ping 子进程最长等待秒数（超时则强制结束）。 */
    private static final int PING_TIMEOUT_SEC = 20;
    /** 查询公网 IP 的 HTTP 连接/读取超时。 */
    private static final int PUBLIC_IP_TIMEOUT_MS = 5000;
    /** 允许的 ping 目标字符集（防命令注入的简单校验）。 */
    private static final Pattern SAFE_HOST = Pattern.compile("^[a-zA-Z0-9.:\\-_]+$");

    private static boolean isZh() {
        try {
            return java.util.Locale.getDefault().getLanguage().toLowerCase(java.util.Locale.ROOT).startsWith("zh");
        } catch (Exception e) {
            return false;
        }
    }

    private static String pgTable(String title, String[] headers, java.util.List<String[]> rows) {
        try {
            JSONObject root = new JSONObject();
            root.put("title", title);
            JSONArray h = new JSONArray();
            for (String hdr : headers) {
                h.put(hdr);
            }
            root.put("headers", h);
            JSONArray r = new JSONArray();
            for (String[] row : rows) {
                JSONArray rowArr = new JSONArray();
                for (String cell : row) {
                    rowArr.put(cell);
                }
                r.put(rowArr);
            }
            root.put("rows", r);
            return "__pg_table__" + root.toString() + "__pg_table_end__";
        } catch (Exception e) {
            return title;
        }
    }

    private static String t(String en, String zh) {
        return isZh() ? zh : en;
    }

    /**
     * 模块入口：需要 {@link Context} 的 action 会传入用于获取系统服务。
     *
     * @param context    用于 {@code checkConnectivity}、{@code getNetworkInfo} 等
     * @param action     操作名
     * @param paramsJson JSON 参数（如 ping 的 host、dns 的 domain）
     * @return 统一包装的成功/失败 JSON，多数成功结果带 {@code _displayText} 摘要
     * @throws Exception 构造响应时可能抛出
     */
    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        try {
            JSONObject params = new JSONObject(emptyJson(paramsJson));
            switch (action) {
                case "ping": {
                    String out = ping(params.optString("host", "").trim());
                    return ok(out, formatPingDisplay(out));
                }
                case "dnsLookup": {
                    String out = dnsLookup(params.optString("domain", "").trim());
                    return ok(out, formatDnsLookupDisplay(out));
                }
                case "getLocalIp": {
                    String out = getLocalIp();
                    return ok(out, formatLocalIpDisplay(out));
                }
                case "getPublicIp": {
                    String out = getPublicIp();
                    return ok(out, formatPublicIpDisplay(out));
                }
                case "checkConnectivity": {
                    String out = checkConnectivity(context);
                    return ok(out, formatConnectivityDisplay(out));
                }
                case "getNetworkInfo": {
                    String out = getNetworkInfo(context);
                    return ok(out, formatNetworkInfoDisplay(out));
                }
                default:
                    return error("Unsupported action: " + action);
            }
        } catch (Exception e) {
            String msg = e.getMessage();
            return error(msg != null && !msg.isEmpty() ? msg : e.getClass().getSimpleName());
        }
    }

    /**
     * 执行 {@code ping -c 4 host}，异步排空标准输出/错误，并在超时后销毁进程。
     *
     * @param host 主机名或 IP，须通过 {@link #SAFE_HOST}
     * @return 含 stdout、stderr、exitCode、timedOut 等的 JSON 字符串
     * @throws Exception 参数非法或线程中断等
     */
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
        // 子进程输出必须在主线程 waitFor 同时被读取，避免缓冲区满导致死锁
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
     * 轮询子进程直至退出或超出 {@code timeoutMs}；用于 API 26 以下无 {@code Process#waitFor(long, TimeUnit)} 的情况。
     *
     * @param process   子进程
     * @param timeoutMs 最长等待毫秒数
     * @return 若在时限内已退出则为 true，否则 false
     * @throws InterruptedException 当前线程被中断
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

    /**
     * 将输入流按行读入 StringBuilder（UTF-8）；流关闭时结束。
     *
     * @param in   进程输出流
     * @param sink 聚合缓冲区
     */
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

    /**
     * 使用系统 DNS 将域名解析为所有 {@link InetAddress}。
     *
     * @param domain 域名
     * @return JSON：domain、addresses 数组
     * @throws Exception 域名为空或解析失败
     */
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

    /**
     * 遍历 {@link NetworkInterface}，收集已启用且非回环的 IPv4 与 IPv6 地址字符串。
     *
     * @return JSON：ipv4、ipv6 两个数组
     * @throws Exception 枚举接口时异常
     */
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
                continue; // 跳过未启用与环回接口
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

    /**
     * 请求 {@code https://api.ipify.org?format=json} 获取公网 IPv4（或服务端返回的 IP 字符串）。
     *
     * @return JSON：httpStatus、body、成功时 ip 字段
     * @throws Exception 网络或解析异常
     */
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

    /**
     * 将流完整读为单个字符串（按行拼接，无换行保留）。
     *
     * @param in 输入流，可为 null
     * @return 文本内容
     * @throws Exception IO 异常
     */
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

    /**
     * 查询当前活动网络的 {@link NetworkCapabilities}：是否有 INTERNET、是否已验证、是否计费、传输类型列表等。
     *
     * @param context 用于获取 {@link ConnectivityManager}
     * @return 描述连通能力与传输介质的 JSON 字符串
     * @throws Exception 获取服务失败等
     */
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
        // 未声明 NOT_METERED 则视为可能按流量计费
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

    /**
     * 返回简化的网络类型（wifi/mobile/ethernet/vpn/none）及是否认为已连接；
     * 在 Wi‑Fi 下尝试补充 SSID、BSSID、协商速率、频段等（受系统隐私策略影响可能为空）。
     *
     * @param context Android 上下文
     * @return JSON 字符串
     * @throws Exception 获取 Wi‑Fi 信息失败等
     */
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

    /**
     * 处理 {@link WifiInfo#getSSID()} 返回值：去掉引号并隐藏 unknown 占位。
     *
     * @param raw 系统返回的 SSID 字符串
     * @return 可读 SSID 或空串
     */
    private static String safeSsid(String raw) {
        if (raw == null || "<unknown ssid>".equalsIgnoreCase(raw) || "\"<unknown ssid>\"".equals(raw)) {
            return "";
        }
        if (raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
            return raw.substring(1, raw.length() - 1);
        }
        return raw;
    }

    /**
     * 规范空 JSON 参数。
     *
     * @param v 原始字符串
     * @return {@code "{}"} 或原值
     */
    private static String emptyJson(String v) {
        return v == null || v.trim().isEmpty() ? "{}" : v;
    }

    /**
     * 从 ping 的 JSON 输出提取延迟与可达性，生成简短展示文案。
     *
     * @param outputJson {@link #ping} 返回的字符串
     * @return 展示文本；解析失败返回空串
     */
    private static String formatPingDisplay(String outputJson) {
        try {
            JSONObject o = new JSONObject(outputJson);
            String stdout = o.optString("stdout", "");
            return t("🏓 Ping Results", "🏓 Ping 结果") + "\n\n" + stdout;
        } catch (Exception ignored) {
            return "";
        }
    }

    /**
     * DNS 结果的用户可读摘要。
     *
     * @param outputJson {@link #dnsLookup} 输出
     * @return 展示字符串
     */
    private static String formatDnsLookupDisplay(String outputJson) {
        try {
            JSONObject o = new JSONObject(outputJson);
            String host = o.optString("domain", "—");
            JSONArray arr = o.optJSONArray("addresses");
            ArrayList<String[]> rows = new ArrayList<>();
            if (arr != null && arr.length() > 0) {
                for (int i = 0; i < arr.length(); i++) {
                    rows.add(new String[]{host, arr.optString(i, "—")});
                }
            } else {
                rows.add(new String[]{host, "—"});
            }
            return pgTable(
                    t("🌐 DNS Lookup", "🌐 DNS 查询"),
                    new String[]{t("Domain", "域名"), t("Address", "地址")},
                    rows);
        } catch (Exception ignored) {
            return "";
        }
    }

    /**
     * 展示本机首个 IPv4，若无则首个 IPv6。
     *
     * @param outputJson {@link #getLocalIp} 输出
     * @return 单行展示
     */
    private static String formatLocalIpDisplay(String outputJson) {
        try {
            JSONObject o = new JSONObject(outputJson);
            ArrayList<String[]> rows = new ArrayList<>();
            JSONArray v4 = o.optJSONArray("ipv4");
            if (v4 != null) {
                for (int i = 0; i < v4.length(); i++) {
                    rows.add(new String[]{"IPv4", v4.optString(i, "—")});
                }
            }
            JSONArray v6 = o.optJSONArray("ipv6");
            if (v6 != null) {
                for (int i = 0; i < v6.length(); i++) {
                    rows.add(new String[]{"IPv6", v6.optString(i, "—")});
                }
            }
            if (rows.isEmpty()) {
                rows.add(new String[]{"—", "—"});
            }
            return pgTable(
                    t("📡 Local IP", "📡 本地 IP"),
                    new String[]{t("Family", "地址族"), t("Address", "地址")},
                    rows);
        } catch (Exception ignored) {
            return "";
        }
    }

    /**
     * 展示公网 IP 或占位符。
     *
     * @param outputJson {@link #getPublicIp} 输出
     * @return 单行展示
     */
    private static String formatPublicIpDisplay(String outputJson) {
        try {
            JSONObject o = new JSONObject(outputJson);
            String ip = o.optString("ip", "");
            if (ip.isEmpty()) {
                ip = "—";
            }
            ArrayList<String[]> rows = new ArrayList<>();
            rows.add(new String[]{"IP", ip});
            rows.add(new String[]{t("HTTP status", "HTTP 状态"), String.valueOf(o.optInt("httpStatus", 0))});
            return pgTable(
                    t("🌍 Public IP", "🌍 公网 IP"),
                    new String[]{t("Item", "项目"), t("Value", "值")},
                    rows);
        } catch (Exception ignored) {
            return "";
        }
    }

    /**
     * 将 {@link #checkConnectivity} 结果格式化为连接状态与传输类型。
     *
     * @param outputJson JSON 字符串
     * @return 多行展示
     */
    private static String formatConnectivityDisplay(String outputJson) {
        try {
            JSONObject o = new JSONObject(outputJson);
            String title = t("📶 Network Status", "📶 网络状态");
            String[] hdr = new String[]{t("Item", "项目"), t("Value", "值")};
            if (!o.optBoolean("hasConnectivityService", false)) {
                ArrayList<String[]> rows = new ArrayList<>();
                rows.add(new String[]{t("Connected", "已连接"), "❌"});
                rows.add(new String[]{t("Type", "类型"), "—"});
                return pgTable(title, hdr, rows);
            }
            boolean active = o.optBoolean("activeNetwork", false);
            JSONArray transports = o.optJSONArray("transports");
            String typeStr = transportsToFriendly(transports);
            ArrayList<String[]> rows = new ArrayList<>();
            rows.add(new String[]{t("Connected", "已连接"), active ? "✅" : "❌"});
            rows.add(new String[]{t("Type", "类型"), active ? typeStr : "—"});
            return pgTable(title, hdr, rows);
        } catch (Exception ignored) {
            return "";
        }
    }

    /**
     * 将 transports 数组拼成友好文案（如 WiFi/Mobile）。
     *
     * @param transports JSON 字符串数组
     * @return 用 {@code /} 连接的标签
     */
    private static String transportsToFriendly(JSONArray transports) {
        if (transports == null || transports.length() == 0) {
            return "—";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < transports.length(); i++) {
            if (i > 0) {
                sb.append("/");
            }
            sb.append(transportTokenToDisplay(transports.optString(i, "")));
        }
        return sb.toString();
    }

    /**
     * 单个传输类型 token 的显示名映射。
     *
     * @param t 内部 token，如 wifi、cellular
     * @return 展示名
     */
    private static String transportTokenToDisplay(String t) {
        if (t == null || t.isEmpty()) {
            return "—";
        }
        switch (t) {
            case "wifi":
                return "WiFi";
            case "cellular":
                return "Mobile";
            case "ethernet":
                return "Ethernet";
            case "vpn":
                return "VPN";
            case "bluetooth":
                return "Bluetooth";
            case "lowpan":
                return "LoWPAN";
            case "wifi_aware":
                return "Wi-Fi Aware";
            default:
                return t;
        }
    }

    /**
     * 格式化 {@link #getNetworkInfo} 的输出为类型、SSID、连接状态、链路速率等。
     *
     * @param outputJson JSON 字符串
     * @return 多行展示
     */
    private static String formatNetworkInfoDisplay(String outputJson) {
        try {
            JSONObject o = new JSONObject(outputJson);
            String rawType = o.optString("networkType", "unknown");
            String typeLine = networkTypeToDisplay(rawType);
            ArrayList<String[]> rows = new ArrayList<>();
            rows.add(new String[]{t("Type", "类型"), typeLine});
            if ("wifi".equals(rawType)) {
                String ssid = o.optString("wifiSsid", "");
                rows.add(new String[]{"SSID", ssid.isEmpty() ? "—" : ssid});
            }
            rows.add(new String[]{t("Connected", "已连接"), o.optBoolean("connected", false) ? "✅" : "❌"});
            if ("wifi".equals(rawType)) {
                int link = o.optInt("linkSpeedMbps", -1);
                if (link >= 0) {
                    rows.add(new String[]{t("Link speed", "连接速度"), link + " Mbps"});
                }
                int freq = o.optInt("frequencyMhz", 0);
                if (freq > 0) {
                    rows.add(new String[]{t("Frequency", "频率"), freq + " MHz"});
                }
            }
            return pgTable(
                    t("📶 Network Info", "📶 网络信息"),
                    new String[]{t("Item", "项目"), t("Value", "值")},
                    rows);
        } catch (Exception ignored) {
            return "";
        }
    }

    /**
     * 网络类型代码到展示文案。
     *
     * @param type 如 wifi、mobile、none
     * @return 展示标签
     */
    private static String networkTypeToDisplay(String type) {
        if (type == null || type.isEmpty()) {
            return "—";
        }
        switch (type) {
            case "wifi":
                return "WiFi";
            case "mobile":
                return "Mobile";
            case "ethernet":
                return "Ethernet";
            case "vpn":
                return "VPN";
            case "none":
                return t("None", "无");
            case "unknown":
                return "Unknown";
            case "other":
                return "Other";
            default:
                return type;
        }
    }

    /**
     * 成功响应包装。
     *
     * @param output      业务 JSON 字符串
     * @param displayText 可选 {@code _displayText}
     * @return 完整响应
     * @throws Exception JSON 异常
     */
    private static String ok(String output, String displayText) throws Exception {
        JSONObject r = new JSONObject().put("success", true).put("output", output);
        if (displayText != null && !displayText.isEmpty()) {
            r.put("_displayText", displayText);
        }
        return r.toString();
    }

    /**
     * 成功响应，不含展示文案。
     *
     * @param output 业务输出
     * @return JSON 字符串
     * @throws Exception JSON 异常
     */
    private static String ok(String output) throws Exception {
        return ok(output, null);
    }

    /**
     * 失败响应。
     *
     * @param msg 错误信息
     * @return JSON 字符串
     * @throws Exception JSON 异常
     */
    private static String error(String msg) throws Exception {
        return new JSONObject().put("success", false).put("error", msg).toString();
    }
}
