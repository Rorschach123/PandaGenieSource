package ai.rorsch.moduleplugins.hello_world;

import android.content.Context;
import ai.rorsch.pandagenie.module.runtime.HtmlOutputHelper;
import ai.rorsch.pandagenie.module.runtime.ModulePlugin;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * PandaGenie self-introduction module.
 *
 * The introduction is intentionally generated only when the module is executed.
 * It reads the live module market catalog, reports the current ecosystem size,
 * and highlights a few random modules so each greeting feels fresh.
 */
public class HelloPlugin implements ModulePlugin {

    private static final String API_BASE = "https://cf.pandagenie.ai";
    private static final int TIMEOUT_MS = 8000;
    private static final Random RANDOM = new Random();
    private static final int HIGHLIGHT_COUNT = 6;

    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        try {
            JSONObject params = new JSONObject(emptyJson(paramsJson));
            boolean zh = isZh();
            switch (action) {
                case "introduce":
                    return doIntroduce(zh);
                case "capabilities":
                    return doCapabilities(zh, params);
                case "usage_tips":
                    return doUsageTips(zh);
                default:
                    return error("Unsupported action: " + action);
            }
        } catch (Exception e) {
            String msg = e.getMessage();
            return error(msg != null && !msg.isEmpty() ? msg : e.getClass().getSimpleName());
        }
    }

    private String doIntroduce(boolean zh) throws Exception {
        MarketSnapshot snapshot = fetchMarketSnapshot(zh);
        List<ModuleInfo> highlights = pickRandomModules(snapshot.modules, HIGHLIGHT_COUNT);

        JSONObject out = new JSONObject();
        out.put("name", "PandaGenie");
        out.put("type", zh ? "\u0041\u0049 \u624b\u673a\u52a9\u624b" : "AI Phone Assistant");
        out.put("moduleCount", snapshot.totalCount());
        out.put("source", snapshot.source);
        JSONArray highlightJson = new JSONArray();
        for (ModuleInfo item : highlights) {
            highlightJson.put(new JSONObject()
                    .put("id", item.id)
                    .put("name", item.name)
                    .put("description", item.description)
                    .put("apiCount", item.apiCount));
        }
        out.put("highlights", highlightJson);

        String displayText = buildIntroText(zh, snapshot, highlights);
        String displayHtml = buildIntroHtml(zh, snapshot, highlights);
        return ok(out, displayText, displayHtml);
    }

    private String doCapabilities(boolean zh, JSONObject params) throws Exception {
        MarketSnapshot snapshot = fetchMarketSnapshot(zh);
        List<ModuleInfo> modules = snapshot.modules;
        JSONObject out = new JSONObject();
        out.put("moduleCount", snapshot.totalCount());
        out.put("source", snapshot.source);

        if (modules.isEmpty()) {
            return buildStaticCapabilities(zh, out);
        }

        StringBuilder text = new StringBuilder();
        text.append(zh
                ? "\uD83D\uDCE6 \u6211\u4ece\u6a21\u5757\u5e02\u573a\u8bfb\u5230\u4e86 " + snapshot.totalCount() + " \u4e2a\u53ef\u7528\u6a21\u5757\uff1a\n\n"
                : "\uD83D\uDCE6 I found " + snapshot.totalCount() + " available modules in the module market:\n\n");
        int limit = Math.min(20, modules.size());
        for (int i = 0; i < limit; i++) {
            ModuleInfo item = modules.get(i);
            text.append(i + 1)
                    .append(". ")
                    .append(item.name)
                    .append(" - ")
                    .append(shortText(item.description, 42))
                    .append("\n");
        }
        if (modules.size() > limit) {
            text.append(zh ? "\n\u8fd8\u6709 " + (modules.size() - limit) + " \u4e2a\u6a21\u5757\u53ef\u5728\u5e02\u573a\u4e2d\u7ee7\u7eed\u67e5\u770b\u3002"
                    : "\n" + (modules.size() - limit) + " more modules are available in the market.");
        }

        List<String[]> rows = new ArrayList<>();
        for (int i = 0; i < Math.min(10, modules.size()); i++) {
            ModuleInfo item = modules.get(i);
            rows.add(new String[]{item.name, shortText(item.description, 54)});
        }
        String html = HtmlOutputHelper.card(
                "\uD83D\uDCE6",
                zh ? "\u6a21\u5757\u80fd\u529b\u6982\u89c8" : "Capability Overview",
                HtmlOutputHelper.metricGrid(new String[][]{
                        {String.valueOf(snapshot.totalCount()), zh ? "\u5e02\u573a\u6a21\u5757" : "market modules"},
                        {String.valueOf(totalApis(modules)), zh ? "\u5df2\u66b4\u9732 API" : "published APIs"}
                })
                        + HtmlOutputHelper.table(
                        new String[]{zh ? "\u6a21\u5757" : "Module", zh ? "\u80fd\u529b" : "Capability"},
                        rows
                )
                        + HtmlOutputHelper.muted(zh
                        ? "\u76f4\u63a5\u7528\u81ea\u7136\u8bed\u8a00\u8bf4\u9700\u6c42\uff0cPandaGenie \u4f1a\u81ea\u52a8\u9009\u6a21\u5757\u5e76\u6267\u884c\u3002"
                        : "Describe your goal naturally; PandaGenie chooses and executes modules automatically.")
        );
        return ok(out, text.toString().trim(), html);
    }

    private String doUsageTips(boolean zh) throws Exception {
        JSONObject out = new JSONObject();
        String[][] tips = zh ? new String[][]{
                {"\uD83D\uDCAC", "\u50cf\u804a\u5929\u4e00\u6837\u76f4\u63a5\u8bf4\u9700\u6c42\uff0c\u4e0d\u9700\u8981\u8bb0\u547d\u4ee4\u3002"},
                {"\uD83E\uDDE9", "\u590d\u6742\u4efb\u52a1\u53ef\u4ee5\u4e00\u53e5\u8bdd\u8bf4\u5b8c\uff0cAI \u4f1a\u62c6\u6210\u591a\u6b65\u6267\u884c\u3002"},
                {"\uD83D\uDCE6", "\u6253\u5f00\u6a21\u5757\u5e02\u573a\u53ef\u4ee5\u5b89\u88c5\u66f4\u591a\u80fd\u529b\uff0c\u5b89\u88c5\u540e\u7acb\u523b\u53ef\u7528\u3002"},
                {"\uD83D\uDEE1", "\u6bcf\u4e2a\u6a21\u5757\u90fd\u5728\u6c99\u7bb1\u4e2d\u6267\u884c\uff0c\u6743\u9650\u7531 App \u7edf\u4e00\u7ba1\u63a7\u3002"}
        } : new String[][]{
                {"\uD83D\uDCAC", "Speak naturally. No commands to memorize."},
                {"\uD83E\uDDE9", "One sentence can become a multi-step executable task."},
                {"\uD83D\uDCE6", "Install more capabilities from the Module Market."},
                {"\uD83D\uDEE1", "Modules run in a sandbox and permissions are controlled by the app."}
        };

        StringBuilder text = new StringBuilder(zh ? "\uD83D\uDCA1 \u4f7f\u7528\u5efa\u8bae\n\n" : "\uD83D\uDCA1 Usage Tips\n\n");
        for (String[] tip : tips) {
            text.append(tip[0]).append(" ").append(tip[1]).append("\n");
        }
        text.append("\n").append(zh
                ? "\u4f8b\u5982\uff1a\u201c\u627e\u51fa\u91cd\u590d\u56fe\u7247\u201d\u3001\u201c\u67e5\u8be2\u7a7a\u6587\u4ef6\u5939\u201d\u3001\u201c\u628a\u56fe\u7247\u8f6c\u6210 PNG\u201d\u3002"
                : "Examples: \"find duplicate images\", \"query empty folders\", \"convert images to PNG\".");

        String html = HtmlOutputHelper.card(
                "\uD83D\uDCA1",
                zh ? "\u4f7f\u7528\u5efa\u8bae" : "Usage Tips",
                HtmlOutputHelper.iconList(tips)
                        + HtmlOutputHelper.muted(zh
                        ? "\u4e0b\u6b21\u76f4\u63a5\u8bf4\u4f60\u60f3\u5b8c\u6210\u7684\u624b\u673a\u4efb\u52a1\u5c31\u597d\u3002"
                        : "Next time, just say the phone task you want done.")
        );
        return ok(out, text.toString().trim(), html);
    }

    private String buildIntroText(boolean zh, MarketSnapshot snapshot, List<ModuleInfo> highlights) {
        StringBuilder text = new StringBuilder();
        text.append(zh
                ? "\uD83D\uDC3C PandaGenie - \u4f60\u7684 AI \u624b\u673a\u52a9\u624b\n\n"
                + "\u4f60\u597d\uff0c\u6211\u662f PandaGenie\u3002\u6211\u662f\u4e00\u4e2a\u5f00\u6e90\u3001\u514d\u8d39\u3001\u65e0\u5e7f\u544a\u7684\u5b89\u5353 AI \u52a9\u624b\uff0c\u4e0d\u53ea\u4f1a\u804a\u5929\uff0c\u8fd8\u80fd\u628a\u4f60\u7684\u9700\u6c42\u62c6\u6210\u53ef\u6267\u884c\u6b65\u9aa4\uff0c\u8c03\u7528\u6a21\u5757\u771f\u6b63\u5b8c\u6210\u624b\u673a\u4efb\u52a1\u3002\n\n"
                : "\uD83D\uDC3C PandaGenie - Your AI Phone Assistant\n\n"
                + "Hi, I'm PandaGenie: an open-source, free, ad-free Android AI assistant. I do more than chat: I turn your request into executable steps and call modules to complete real phone tasks.\n\n");

        if (snapshot.totalCount() > 0) {
            text.append(zh
                    ? "\u6211\u521a\u901a\u8fc7\u6a21\u5757\u5e02\u573a\u63a5\u53e3\u8bfb\u5230 " + snapshot.totalCount() + " \u4e2a\u53ef\u7528\u6a21\u5757\uff0c\u968f\u673a\u6311\u51e0\u4e2a\u7ed9\u4f60\u770b\u770b\uff1a\n"
                    : "I just read " + snapshot.totalCount() + " available modules from the module market API. Here are a few random highlights:\n");
            for (ModuleInfo item : highlights) {
                text.append("- ").append(item.name);
                if (!item.description.isEmpty()) {
                    text.append(": ").append(shortText(item.description, zh ? 36 : 58));
                }
                if (item.apiCount > 0) {
                    text.append(zh ? "\uff08" + item.apiCount + " \u4e2a API\uff09" : " (" + item.apiCount + " APIs)");
                }
                text.append("\n");
            }
            text.append("\n");
        } else {
            text.append(zh
                    ? "\u6211\u53ef\u4ee5\u8986\u76d6\u6587\u4ef6\u3001\u56fe\u7247\u3001\u5e94\u7528\u3001\u7cfb\u7edf\u3001\u65e5\u7a0b\u3001\u7f51\u7edc\u7b49\u624b\u673a\u4efb\u52a1\u3002\n\n"
                    : "I can cover files, images, apps, system tools, schedules, network tasks, and more.\n\n");
        }

        text.append(zh
                ? "\u4f60\u53ef\u4ee5\u76f4\u63a5\u8bf4\uff1a\u201c\u627e\u51fa\u91cd\u590d\u7167\u7247\u201d\u3001\u201c\u67e5\u8be2\u7a7a\u6587\u4ef6\u5939\u201d\u3001\u201c\u628a\u56fe\u7247\u8f6c\u6210 PNG\u201d\u3001\u201c\u6e05\u7406\u7f13\u5b58\u201d\u3002"
                : "You can say things like: \"find duplicate photos\", \"query empty folders\", \"convert images to PNG\", or \"clean cache\".");
        return text.toString().trim();
    }

    private String buildIntroHtml(boolean zh, MarketSnapshot snapshot, List<ModuleInfo> highlights) {
        String moduleCount = snapshot.totalCount() > 0 ? String.valueOf(snapshot.totalCount()) : "30+";
        List<String[]> rows = new ArrayList<>();
        for (ModuleInfo item : highlights) {
            String apiText = item.apiCount > 0
                    ? (zh ? item.apiCount + " \u4e2a API" : item.apiCount + " APIs")
                    : "";
            rows.add(new String[]{
                    item.name,
                    shortText(item.description, 52),
                    apiText
            });
        }
        String intro = zh
                ? "\u6211\u662f\u5f00\u6e90\u3001\u514d\u8d39\u3001\u65e0\u5e7f\u544a\u7684\u5b89\u5353 AI \u624b\u673a\u52a9\u624b\u3002\u4f60\u8bf4\u9700\u6c42\uff0c\u6211\u89c4\u5212\u6b65\u9aa4\u5e76\u8c03\u7528\u6a21\u5757\u5b8c\u6210\u3002"
                : "I am an open-source, free, ad-free Android AI assistant. You describe the goal; I plan steps and call modules to finish it.";
        String sourceText = snapshot.fromLiveMarket()
                ? (zh ? "\u5df2\u8fde\u63a5\u6a21\u5757\u5e02\u573a\u63a5\u53e3" : "Loaded from live module market API")
                : (zh ? "\u5e02\u573a\u6682\u4e0d\u53ef\u7528\uff0c\u5df2\u4f7f\u7528\u672c\u5730\u515c\u5e95\u4ecb\u7ecd" : "Market unavailable; using local fallback");

        String body = HtmlOutputHelper.metricGrid(new String[][]{
                {zh ? "AI \u624b\u673a\u52a9\u624b" : "AI Phone Assistant", zh ? "\u5b9a\u4f4d" : "Type"},
                {moduleCount, zh ? "\u5e02\u573a\u6a21\u5757" : "Market Modules"},
                {String.valueOf(totalApis(snapshot.modules)), zh ? "\u5df2\u66b4\u9732 API" : "Published APIs"},
                {zh ? "\u5f00\u6e90\u514d\u8d39" : "Open & Free", zh ? "\u4ef7\u683c" : "Price"}
        })
                + HtmlOutputHelper.p(intro)
                + (rows.isEmpty() ? "" : HtmlOutputHelper.table(
                new String[]{zh ? "\u968f\u673a\u6a21\u5757" : "Random Module", zh ? "\u80fd\u529b\u4eae\u70b9" : "Capability", "API"},
                rows
        ))
                + HtmlOutputHelper.muted(sourceText);

        return HtmlOutputHelper.card(
                "\uD83D\uDC3C",
                zh ? "\u5173\u4e8e PandaGenie" : "About PandaGenie",
                body
        );
    }

    private String buildStaticCapabilities(boolean zh, JSONObject out) throws Exception {
        String text = zh
                ? "\uD83D\uDCE6 \u6211\u53ef\u4ee5\u5904\u7406\u6587\u4ef6\u7ba1\u7406\u3001\u538b\u7f29\u89e3\u538b\u3001\u56fe\u7247\u5904\u7406\u3001OCR\u3001\u5929\u6c14\u3001\u5e94\u7528\u7ba1\u7406\u3001\u7cfb\u7edf\u4fe1\u606f\u3001\u65e5\u7a0b\u63d0\u9192\u3001\u4e8c\u7ef4\u7801\u3001\u5bc6\u7801\u751f\u6210\u7b49\u4efb\u52a1\u3002"
                : "\uD83D\uDCE6 I can handle file management, archives, image tools, OCR, weather, app management, system info, reminders, QR codes, password generation, and more.";
        String html = HtmlOutputHelper.card(
                "\uD83D\uDCE6",
                zh ? "\u80fd\u529b\u6982\u89c8" : "Capabilities",
                HtmlOutputHelper.iconList(zh ? new String[][]{
                        {"\uD83D\uDCC1", "\u6587\u4ef6\u7ba1\u7406\u3001\u7a7a\u6587\u4ef6\u5939\u3001\u91cd\u590d\u6587\u4ef6\u3001\u538b\u7f29\u5305"},
                        {"\uD83D\uDDBC", "\u56fe\u7247\u8f6c\u6362\u3001OCR\u3001\u56fe\u7247\u5206\u6790"},
                        {"\u2699", "\u5e94\u7528\u7ba1\u7406\u3001\u7cfb\u7edf\u4fe1\u606f\u3001\u6e05\u7406\u5de5\u5177"},
                        {"\uD83D\uDCDD", "\u7b14\u8bb0\u3001\u63d0\u9192\u3001\u6587\u672c\u5de5\u5177\u3001\u4e8c\u7ef4\u7801"}
                } : new String[][]{
                        {"\uD83D\uDCC1", "Files, empty folders, duplicates, archives"},
                        {"\uD83D\uDDBC", "Image conversion, OCR, image analysis"},
                        {"\u2699", "App management, system info, cleaning tools"},
                        {"\uD83D\uDCDD", "Notes, reminders, text tools, QR codes"}
                })
        );
        return ok(out, text, html);
    }

    private MarketSnapshot fetchMarketSnapshot(boolean zh) {
        List<ModuleInfo> modules = fetchCatalogModules(zh);
        if (!modules.isEmpty()) return new MarketSnapshot(modules, "catalog");

        modules = fetchListModules(zh);
        if (!modules.isEmpty()) return new MarketSnapshot(modules, "list");

        return new MarketSnapshot(new ArrayList<ModuleInfo>(), "fallback");
    }

    private List<ModuleInfo> fetchCatalogModules(boolean zh) {
        try {
            String body = get(API_BASE + "/modules/catalog");
            JSONObject json = new JSONObject(body);
            JSONArray arr = json.optJSONArray("modules");
            if (arr == null) return new ArrayList<>();
            List<ModuleInfo> modules = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item == null) continue;
                String id = item.optString("id", "");
                String name = localized(item.optJSONObject("name"), zh, id);
                String desc = localized(item.optJSONObject("description"), zh, "");
                int apiCount = 0;
                JSONArray apis = item.optJSONArray("apis");
                if (apis != null) apiCount = apis.length();
                modules.add(new ModuleInfo(id, name, desc, apiCount));
            }
            return modules;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private List<ModuleInfo> fetchListModules(boolean zh) {
        try {
            String body = get(API_BASE + "/modules/list?locale=" + (zh ? "zh" : "en"));
            JSONObject json = new JSONObject(body);
            if (!json.optBoolean("success", false)) return new ArrayList<>();
            JSONArray arr = json.optJSONArray("data");
            if (arr == null) return new ArrayList<>();
            List<ModuleInfo> modules = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item == null) continue;
                String id = item.optString("id", "");
                String name = item.optString("name", id);
                String desc = item.optString("desc", "");
                modules.add(new ModuleInfo(id, name, desc, 0));
            }
            return modules;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private String get(String urlText) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlText).openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "PandaGenie-Hello/1.2");
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    private List<ModuleInfo> pickRandomModules(List<ModuleInfo> modules, int count) {
        List<ModuleInfo> clean = new ArrayList<>();
        for (ModuleInfo item : modules) {
            if (item.name != null && !item.name.trim().isEmpty()) {
                clean.add(item);
            }
        }
        Collections.shuffle(clean, RANDOM);
        return clean.subList(0, Math.min(count, clean.size()));
    }

    private int totalApis(List<ModuleInfo> modules) {
        int total = 0;
        for (ModuleInfo item : modules) total += Math.max(0, item.apiCount);
        return total;
    }

    private String localized(JSONObject value, boolean zh, String fallback) {
        if (value == null) return fallback == null ? "" : fallback;
        String preferred = zh ? value.optString("zh", "") : value.optString("en", "");
        if (!preferred.isEmpty()) return preferred;
        String other = zh ? value.optString("en", "") : value.optString("zh", "");
        return other.isEmpty() ? (fallback == null ? "" : fallback) : other;
    }

    private String shortText(String value, int maxChars) {
        if (value == null) return "";
        String text = value.trim().replaceAll("\\s+", " ");
        if (text.length() <= maxChars) return text;
        return text.substring(0, Math.max(0, maxChars)) + "...";
    }

    private boolean isZh() {
        return Locale.getDefault().getLanguage().toLowerCase(Locale.ROOT).startsWith("zh");
    }

    private String emptyJson(String value) {
        return value == null || value.trim().isEmpty() ? "{}" : value;
    }

    private String ok(JSONObject output, String displayText, String displayHtml) throws Exception {
        JSONObject result = new JSONObject()
                .put("success", true)
                .put("output", output.toString());
        if (displayText != null && !displayText.isEmpty()) {
            result.put("_displayText", displayText);
        }
        if (displayHtml != null && !displayHtml.isEmpty()) {
            result.put("_displayHtml", displayHtml);
        }
        return result.toString();
    }

    private String error(String message) throws Exception {
        return new JSONObject()
                .put("success", false)
                .put("error", message)
                .toString();
    }

    private static final class ModuleInfo {
        final String id;
        final String name;
        final String description;
        final int apiCount;

        ModuleInfo(String id, String name, String description, int apiCount) {
            this.id = id == null ? "" : id;
            this.name = name == null ? "" : name;
            this.description = description == null ? "" : description;
            this.apiCount = apiCount;
        }
    }

    private static final class MarketSnapshot {
        final List<ModuleInfo> modules;
        final String source;

        MarketSnapshot(List<ModuleInfo> modules, String source) {
            this.modules = modules;
            this.source = source;
        }

        int totalCount() {
            return modules == null ? 0 : modules.size();
        }

        boolean fromLiveMarket() {
            return "catalog".equals(source) || "list".equals(source);
        }
    }
}
