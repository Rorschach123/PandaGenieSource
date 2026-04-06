package ai.rorsch.moduleplugins.clipboard;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import ai.rorsch.pandagenie.module.runtime.ModulePlugin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class ClipboardPlugin implements ModulePlugin {

    private static final String HISTORY_FILE = "/sdcard/PandaGenie/data/clipboard/history.json";
    private static final int HISTORY_MAX = 100;
    private static final int DEFAULT_LIMIT = 20;
    private static final long CLIPBOARD_TIMEOUT_MS = 3000L;
    private static final int DISPLAY_BODY_MAX = 2000;
    private static final int DISPLAY_ITEM_MAX = 400;
    private static final int DISPLAY_SEARCH_PREVIEW = 5;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private interface ClipboardTask {
        String run(Context ctx) throws Exception;
    }

    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        JSONObject params = new JSONObject(emptyJson(paramsJson));
        try {
            switch (action) {
                case "getClipboard": {
                    String out = getClipboard(context);
                    return ok(out, formatGetClipboardDisplay(new JSONObject(out)));
                }
                case "setClipboard": {
                    String out = setClipboard(context, params);
                    return ok(out, formatSetClipboardDisplay());
                }
                case "clearClipboard": {
                    String out = clearClipboard(context);
                    return ok(out, formatClearClipboardDisplay());
                }
                case "getClipboardHistory": {
                    String out = getClipboardHistory(params);
                    return ok(out, formatGetClipboardHistoryDisplay(new JSONObject(out)));
                }
                case "saveToHistory": {
                    String out = saveToHistory(context);
                    return ok(out, formatSaveToHistoryDisplay(new JSONObject(out)));
                }
                case "searchHistory": {
                    String out = searchHistory(params);
                    return ok(out, formatSearchHistoryDisplay(new JSONObject(out)));
                }
                case "clearHistory": {
                    String out = clearHistory();
                    return ok(out, formatClearHistoryDisplay());
                }
                default:
                    return error("Unsupported action: " + action);
            }
        } catch (IllegalArgumentException ex) {
            return error(ex.getMessage());
        }
    }

    private String emptyJson(String v) {
        return v == null || v.trim().isEmpty() ? "{}" : v;
    }

    private String ok(String output) throws Exception {
        return new JSONObject().put("success", true).put("output", output).toString();
    }

    private String ok(String output, String displayText) throws Exception {
        return new JSONObject()
                .put("success", true)
                .put("output", output)
                .put("_displayText", displayText)
                .toString();
    }

    private static String truncateForDisplay(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "…";
    }

    private static String formatGetClipboardDisplay(JSONObject result) {
        String text = result.optString("text", "");
        String body = text.isEmpty() ? "(empty)" : truncateForDisplay(text, DISPLAY_BODY_MAX);
        return "📋 Clipboard Content\n━━━━━━━━━━━━━━\n" + body;
    }

    private static String formatSetClipboardDisplay() {
        return "✅ Copied to clipboard";
    }

    private static String formatClearClipboardDisplay() {
        return "🗑️ Clipboard cleared";
    }

    private static String formatGetClipboardHistoryDisplay(JSONObject result) {
        JSONArray items = result.optJSONArray("items");
        if (items == null || items.length() == 0) {
            return "📋 Clipboard History\n━━━━━━━━━━━━━━\n(no entries)";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("📋 Clipboard History\n━━━━━━━━━━━━━━\n");
        for (int i = 0; i < items.length(); i++) {
            JSONObject row = items.optJSONObject(i);
            String t = row != null ? row.optString("text", "") : "";
            String line = t.isEmpty() ? "(empty)" : truncateForDisplay(t, DISPLAY_ITEM_MAX);
            sb.append(i + 1).append(". ").append(line);
            if (i < items.length() - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private static String formatSaveToHistoryDisplay(JSONObject result) {
        int len = result.optInt("textLength", 0);
        return "✅ Saved to clipboard history\n(" + len + " characters)";
    }

    private static String formatSearchHistoryDisplay(JSONObject result) {
        int count = result.optInt("count", 0);
        JSONArray items = result.optJSONArray("items");
        StringBuilder sb = new StringBuilder();
        sb.append("🔍 Search Results\n━━━━━━━━━━━━━━\n");
        sb.append("Found ").append(count).append(" match").append(count == 1 ? "" : "es");
        if (items == null || items.length() == 0) {
            return sb.toString();
        }
        sb.append('\n');
        int show = Math.min(items.length(), DISPLAY_SEARCH_PREVIEW);
        for (int i = 0; i < show; i++) {
            JSONObject row = items.optJSONObject(i);
            String t = row != null ? row.optString("text", "") : "";
            String line = t.isEmpty() ? "(empty)" : truncateForDisplay(t, DISPLAY_ITEM_MAX);
            sb.append(i + 1).append(". ").append(line);
            if (i < show - 1) {
                sb.append('\n');
            }
        }
        if (items.length() > DISPLAY_SEARCH_PREVIEW) {
            sb.append("\n… ").append(items.length() - DISPLAY_SEARCH_PREVIEW).append(" more");
        }
        return sb.toString();
    }

    private static String formatClearHistoryDisplay() {
        return "🗑️ History cleared";
    }

    private String error(String msg) throws Exception {
        return new JSONObject().put("success", false).put("error", msg).toString();
    }

    private String runOnMain(Context context, ClipboardTask task) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return task.run(context);
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> result = new AtomicReference<String>();
        final AtomicReference<Exception> err = new AtomicReference<Exception>();
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    result.set(task.run(context));
                } catch (Exception e) {
                    err.set(e);
                } finally {
                    latch.countDown();
                }
            }
        });
        if (!latch.await(CLIPBOARD_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw new IllegalArgumentException("Clipboard operation timed out");
        }
        if (err.get() != null) {
            throw err.get();
        }
        return result.get();
    }

    private static ClipboardManager clipboardManager(Context context) {
        Object svc = context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (!(svc instanceof ClipboardManager)) {
            throw new IllegalArgumentException("Clipboard service unavailable");
        }
        return (ClipboardManager) svc;
    }

    private static String readClipText(ClipboardManager cm) {
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            return "";
        }
        CharSequence t = clip.getItemAt(0).getText();
        return t != null ? t.toString() : "";
    }

    private String getClipboard(Context context) throws Exception {
        String text = runOnMain(context, new ClipboardTask() {
            @Override
            public String run(Context ctx) {
                return readClipText(clipboardManager(ctx));
            }
        });
        return new JSONObject().put("text", text).toString();
    }

    private String setClipboard(Context context, JSONObject params) throws Exception {
        if (!params.has("text")) {
            throw new IllegalArgumentException("Missing parameter: text");
        }
        final String text = params.optString("text", "");
        runOnMain(context, new ClipboardTask() {
            @Override
            public String run(Context ctx) {
                ClipData data = ClipData.newPlainText("text", text);
                clipboardManager(ctx).setPrimaryClip(data);
                return "";
            }
        });
        return new JSONObject().put("set", true).toString();
    }

    private String clearClipboard(Context context) throws Exception {
        runOnMain(context, new ClipboardTask() {
            @Override
            public String run(Context ctx) {
                ClipData data = ClipData.newPlainText("", "");
                clipboardManager(ctx).setPrimaryClip(data);
                return "";
            }
        });
        return new JSONObject().put("cleared", true).toString();
    }

    private static void ensureParentDir(File file) {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
    }

    private static String readFileUtf8(File file) throws IOException {
        if (!file.isFile()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder();
        FileReader reader = new FileReader(file);
        try {
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
        } finally {
            reader.close();
        }
        return sb.toString();
    }

    private static void writeFileUtf8(File file, String text) throws IOException {
        ensureParentDir(file);
        FileWriter writer = new FileWriter(file);
        try {
            writer.write(text);
        } finally {
            writer.close();
        }
    }

    private JSONArray loadHistoryArray() throws Exception {
        File f = new File(HISTORY_FILE);
        String raw = readFileUtf8(f).trim();
        if (raw.isEmpty()) {
            return new JSONArray();
        }
        try {
            return new JSONArray(raw);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private void saveHistoryArray(JSONArray arr) throws Exception {
        writeFileUtf8(new File(HISTORY_FILE), arr.toString());
    }

    /** Oldest at index 0, newest at end. Trim from front when over HISTORY_MAX. */
    private void trimOldest(JSONArray arr) {
        while (arr.length() > HISTORY_MAX) {
            arr.remove(0);
        }
    }

    private void appendHistoryEntry(String text) throws Exception {
        if (text == null) {
            text = "";
        }
        JSONArray arr = loadHistoryArray();
        JSONObject row = new JSONObject();
        row.put("text", text);
        row.put("timestamp", System.currentTimeMillis());
        arr.put(row);
        trimOldest(arr);
        saveHistoryArray(arr);
    }

    private String getClipboardHistory(JSONObject params) throws Exception {
        int limit = params.optInt("limit", DEFAULT_LIMIT);
        if (limit <= 0) {
            limit = DEFAULT_LIMIT;
        }
        if (limit > HISTORY_MAX) {
            limit = HISTORY_MAX;
        }
        JSONArray arr = loadHistoryArray();
        int n = arr.length();
        int from = Math.max(0, n - limit);
        JSONArray out = new JSONArray();
        for (int i = n - 1; i >= from; i--) {
            out.put(arr.optJSONObject(i));
        }
        return new JSONObject().put("items", out).put("count", out.length()).toString();
    }

    private String saveToHistory(final Context context) throws Exception {
        String text = runOnMain(context, new ClipboardTask() {
            @Override
            public String run(Context ctx) {
                return readClipText(clipboardManager(ctx));
            }
        });
        appendHistoryEntry(text);
        return new JSONObject().put("saved", true).put("textLength", text.length()).toString();
    }

    private String searchHistory(JSONObject params) throws Exception {
        String keyword = params.optString("keyword", "").trim();
        if (keyword.isEmpty()) {
            throw new IllegalArgumentException("Missing parameter: keyword");
        }
        String kw = keyword.toLowerCase(Locale.ROOT);
        JSONArray arr = loadHistoryArray();
        JSONArray out = new JSONArray();
        for (int i = arr.length() - 1; i >= 0; i--) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String t = o.optString("text", "").toLowerCase(Locale.ROOT);
            if (t.contains(kw)) {
                out.put(o);
            }
        }
        return new JSONObject().put("items", out).put("count", out.length()).toString();
    }

    private String clearHistory() throws Exception {
        saveHistoryArray(new JSONArray());
        return new JSONObject().put("cleared", true).toString();
    }
}
