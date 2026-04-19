package ai.rorsch.moduleplugins.clipboard;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import ai.rorsch.pandagenie.module.runtime.HtmlOutputHelper;
import ai.rorsch.pandagenie.module.runtime.ModulePlugin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 系统剪贴板与本地历史记录模块插件：读写剪贴板、清空剪贴板，以及在 SD 卡 JSON 文件中维护历史条目。
 * <p>
 * <b>模块用途：</b>在主线程安全地访问 {@link ClipboardManager}，并将可选历史持久化到固定路径，供检索与回顾。
 * </p>
 * <p>
 * <b>提供的 API（{@code action}）：</b>
 * {@code getClipboard}、{@code setClipboard}（{@code text}）、{@code clearClipboard}、
 * {@code getClipboardHistory}（{@code limit}）、{@code saveToHistory}、{@code searchHistory}（{@code keyword}）、
 * {@code clearHistory}。
 * </p>
 * <p>
 * <b>加载方式：</b>由 {@code ModuleRuntime} 通过反射加载并实现 {@link ModulePlugin}。
 * </p>
 */
public class ClipboardPlugin implements ModulePlugin {

    /** 剪贴板历史持久化文件路径（UTF-8 JSON 数组） */
    private static final String HISTORY_FILE = "/sdcard/PandaGenie/data/clipboard/history.json";
    /** 历史条数上限，超出则从最早条目删除 */
    private static final int HISTORY_MAX = 100;
    /** {@code getClipboardHistory} 默认返回条数上限 */
    private static final int DEFAULT_LIMIT = 20;
    /** 主线程剪贴板操作的等待超时，防止死锁 */
    private static final long CLIPBOARD_TIMEOUT_MS = 3000L;
    /** 展示当前剪贴板全文时的最大字符数 */
    private static final int DISPLAY_BODY_MAX = 2000;
    /** 历史列表每条预览最大字符数 */
    private static final int DISPLAY_ITEM_MAX = 400;
    /** 搜索结果预览最多展示条数 */
    private static final int DISPLAY_SEARCH_PREVIEW = 5;

    private static boolean isZh() {
        try {
            return Locale.getDefault().getLanguage().toLowerCase(Locale.ROOT).startsWith("zh");
        } catch (Exception e) {
            return false;
        }
    }

    private static String pgTable(String title, String[] headers, List<String[]> rows) {
        try {
            JSONObject t = new JSONObject();
            t.put("title", title);
            JSONArray h = new JSONArray();
            for (String hdr : headers) {
                h.put(hdr);
            }
            t.put("headers", h);
            JSONArray r = new JSONArray();
            for (String[] row : rows) {
                JSONArray rowArr = new JSONArray();
                for (String cell : row) {
                    rowArr.put(cell);
                }
                r.put(rowArr);
            }
            t.put("rows", r);
            return "__pg_table__" + t.toString() + "__pg_table_end__";
        } catch (Exception e) {
            return title;
        }
    }

    /** 用于将剪贴板操作 post 到主线程执行 */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * 在主线程执行的剪贴板读写任务，供 {@link #runOnMain} 调度。
     */
    private interface ClipboardTask {
        /**
         * 执行具体剪贴板逻辑。
         *
         * @param ctx 与外层调用一致的 Context
         * @return 任务返回值（部分任务返回空字符串）
         * @throws Exception 服务不可用等异常
         */
        String run(Context ctx) throws Exception;
    }

    /**
     * 根据 {@code action} 执行剪贴板或历史相关操作。
     *
     * @param context    Android 上下文
     * @param action     操作名，见类说明
     * @param paramsJson JSON 参数；非法参数时返回 {@code success:false}（如 {@link IllegalArgumentException}）
     * @return 标准插件 JSON（含 {@code success}、{@code output}、常含 {@code _displayText}）
     * @throws Exception 非预期错误（多数已转为 error JSON）
     */
    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        JSONObject params = new JSONObject(emptyJson(paramsJson));
        try {
            switch (action) {
                case "getClipboard": {
                    String out = getClipboard(context);
                    JSONObject parsed = new JSONObject(out);
                    return ok(out, formatGetClipboardDisplay(parsed), formatGetClipboardHtml(parsed));
                }
                case "setClipboard": {
                    String out = setClipboard(context, params);
                    return ok(out, formatSetClipboardDisplay(), formatSetClipboardHtml());
                }
                case "clearClipboard": {
                    String out = clearClipboard(context);
                    return ok(out, formatClearClipboardDisplay());
                }
                case "getClipboardHistory": {
                    String out = getClipboardHistory(params);
                    JSONObject parsed = new JSONObject(out);
                    return ok(out, formatGetClipboardHistoryDisplay(parsed), formatGetClipboardHistoryHtml(parsed));
                }
                case "saveToHistory": {
                    String out = saveToHistory(context);
                    return ok(out, formatSaveToHistoryDisplay(new JSONObject(out)));
                }
                case "searchHistory": {
                    String out = searchHistory(params);
                    JSONObject parsed = new JSONObject(out);
                    return ok(out, formatSearchHistoryDisplay(parsed), formatSearchHistoryHtml(parsed));
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

    /**
     * 空 paramsJson 转为 {@code "{}"} 以便解析。
     *
     * @param v 原始字符串
     * @return 非空原样，否则空对象字面量
     */
    private String emptyJson(String v) {
        return v == null || v.trim().isEmpty() ? "{}" : v;
    }

    /**
     * 成功响应，无展示文案。
     *
     * @param output 业务 JSON 字符串
     * @return 包装后的 JSON
     * @throws Exception JSON 异常
     */
    private String ok(String output) throws Exception {
        return new JSONObject().put("success", true).put("output", output).toString();
    }

    /**
     * 成功响应并始终写入 {@code _displayText}（本类所有分支均传入非 null 展示串）。
     *
     * @param output       业务 JSON 字符串
     * @param displayText  界面展示用文本
     * @return 包装后的 JSON
     * @throws Exception JSON 异常
     */
    private String ok(String output, String displayText) throws Exception {
        return ok(output, displayText, null);
    }

    /**
     * 成功响应，写入 {@code _displayText}，可选 {@code _displayHtml}。
     */
    private String ok(String output, String displayText, String displayHtml) throws Exception {
        JSONObject j = new JSONObject()
                .put("success", true)
                .put("output", output)
                .put("_displayText", displayText);
        if (displayHtml != null && !displayHtml.isEmpty()) {
            j.put("_displayHtml", displayHtml);
        }
        return j.toString();
    }

    /**
     * 截断过长文本并追加省略号，用于历史列表等 UI 预览。
     *
     * @param text     原文，可为 null
     * @param maxChars 最大保留字符数（不含省略号）
     * @return 截断后字符串；null 输入得到空串
     */
    private static String truncateForDisplay(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "…";
    }

    private static String mdCell(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("|", "\\|").replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ');
    }

    /**
     * 格式化「读取剪贴板」成功时的展示文本。
     *
     * @param result 含 {@code text} 字段的 JSON
     * @return 多行展示字符串
     */
    private static String formatGetClipboardDisplay(JSONObject result) {
        String text = result.optString("text", "");
        String emptyParen = "(" + (isZh() ? "空" : "empty") + ")";
        String body = text.isEmpty() ? emptyParen : truncateForDisplay(text, DISPLAY_BODY_MAX);
        String title = "📋 " + (isZh() ? "剪贴板内容" : "Clipboard Content");
        return pgTable(title, new String[]{"Item", "Value"},
                Collections.singletonList(new String[]{"Text", mdCell(body)}));
    }

    /**
     * @return 写入剪贴板成功时的固定提示语
     */
    private static String formatSetClipboardDisplay() {
        String msg = isZh() ? "已复制到剪贴板" : "Copied to clipboard";
        return pgTable("✅ " + msg,
                new String[]{isZh() ? "状态" : "Status", isZh() ? "消息" : "Message"},
                Collections.singletonList(new String[]{isZh() ? "成功" : "OK", msg}));
    }

    /**
     * @return 清空剪贴板成功时的固定提示语
     */
    private static String formatClearClipboardDisplay() {
        String msg = isZh() ? "剪贴板已清空" : "Clipboard cleared";
        return pgTable("🗑️ " + msg,
                new String[]{isZh() ? "状态" : "Status", isZh() ? "消息" : "Message"},
                Collections.singletonList(new String[]{isZh() ? "成功" : "OK", msg}));
    }

    /**
     * 将历史查询结果格式化为编号列表预览（每条可能截断）。
     *
     * @param result 含 {@code items} 数组
     * @return 展示文本；无条目时返回占位说明
     */
    private static String formatGetClipboardHistoryDisplay(JSONObject result) {
        JSONArray items = result.optJSONArray("items");
        String title = "📋 " + (isZh() ? "剪贴板历史" : "Clipboard History");
        if (items == null || items.length() == 0) {
            String entriesLabel = isZh() ? "条记录" : "Entries";
            String noEntries = isZh() ? "(无记录)" : "(no entries)";
            return pgTable(title, new String[]{"Item", "Value"},
                    Collections.singletonList(new String[]{entriesLabel, noEntries}));
        }
        String previewHeader = isZh() ? "预览" : "Preview";
        String emptyParen = "(" + (isZh() ? "空" : "empty") + ")";
        List<String[]> rows = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject row = items.optJSONObject(i);
            String t = row != null ? row.optString("text", "") : "";
            String line = t.isEmpty() ? emptyParen : truncateForDisplay(t, DISPLAY_ITEM_MAX);
            rows.add(new String[]{String.valueOf(i + 1), mdCell(line)});
        }
        return pgTable(title, new String[]{"#", previewHeader}, rows);
    }

    /**
     * 展示刚保存到历史的文本长度摘要。
     *
     * @param result 含 {@code textLength}
     * @return 展示字符串
     */
    private static String formatSaveToHistoryDisplay(JSONObject result) {
        int len = result.optInt("textLength", 0);
        String title = "✅ " + (isZh() ? "已保存到剪贴板历史" : "Saved to clipboard history");
        String charsLabel = isZh() ? "字符" : "Characters";
        return pgTable(title, new String[]{"Item", "Value"},
                Collections.singletonList(new String[]{charsLabel, String.valueOf(len)}));
    }

    /**
     * 格式化关键字搜索历史的结果摘要（含匹配数量与前几条条目预览）。
     *
     * @param result 含 {@code count} 与 {@code items}
     * @return 展示文本
     */
    private static String formatSearchHistoryDisplay(JSONObject result) {
        int count = result.optInt("count", 0);
        JSONArray items = result.optJSONArray("items");
        String title = "🔍 " + (isZh() ? "搜索结果" : "Search Results");
        String matchesLabel = isZh() ? "找到匹配" : "Matches found";
        String part1 = pgTable(title, new String[]{"Item", "Value"},
                Collections.singletonList(new String[]{matchesLabel, String.valueOf(count)}));
        if (items == null || items.length() == 0) {
            return part1;
        }
        String previewHeader = isZh() ? "预览" : "Preview";
        String emptyParen = "(" + (isZh() ? "空" : "empty") + ")";
        List<String[]> rows = new ArrayList<>();
        int show = Math.min(items.length(), DISPLAY_SEARCH_PREVIEW);
        for (int i = 0; i < show; i++) {
            JSONObject row = items.optJSONObject(i);
            String t = row != null ? row.optString("text", "") : "";
            String line = t.isEmpty() ? emptyParen : truncateForDisplay(t, DISPLAY_ITEM_MAX);
            rows.add(new String[]{String.valueOf(i + 1), mdCell(line)});
        }
        if (items.length() > DISPLAY_SEARCH_PREVIEW) {
            String more = (items.length() - DISPLAY_SEARCH_PREVIEW) + " "
                    + (isZh() ? "更多未显示" : "more not shown");
            rows.add(new String[]{"…", more});
        }
        String part2 = pgTable("", new String[]{"#", previewHeader}, rows);
        return part1 + "\n\n" + part2;
    }

    /**
     * @return 清空持久化历史后的固定提示语
     */
    private static String formatClearHistoryDisplay() {
        String msg = isZh() ? "历史已清空" : "History cleared";
        return pgTable("🗑️ " + msg,
                new String[]{isZh() ? "状态" : "Status", isZh() ? "消息" : "Message"},
                Collections.singletonList(new String[]{isZh() ? "成功" : "OK", msg}));
    }

    private static String formatClipboardTimestamp(long ms) {
        if (ms <= 0) {
            return "—";
        }
        String pat = isZh() ? "yyyy-MM-dd HH:mm:ss" : "MMM d, yyyy HH:mm:ss";
        SimpleDateFormat fmt = new SimpleDateFormat(pat, Locale.getDefault());
        return fmt.format(new Date(ms));
    }

    private static String formatGetClipboardHtml(JSONObject result) {
        String text = result.optString("text", "");
        boolean zh = isZh();
        String emptyParen = "(" + (zh ? "空" : "empty") + ")";
        String bodyText = text.isEmpty() ? emptyParen : truncateForDisplay(text, DISPLAY_BODY_MAX);
        String title = zh ? "剪贴板内容" : "Clipboard Content";
        String body = HtmlOutputHelper.p(bodyText);
        return HtmlOutputHelper.card("📋", title, body);
    }

    private static String formatSetClipboardHtml() {
        boolean zh = isZh();
        String msg = zh ? "已复制到剪贴板" : "Copied to clipboard";
        String body = HtmlOutputHelper.successBadge() + HtmlOutputHelper.p(msg);
        return HtmlOutputHelper.card("✅", zh ? "剪贴板" : "Clipboard", body);
    }

    private static String formatGetClipboardHistoryHtml(JSONObject result) {
        JSONArray items = result.optJSONArray("items");
        boolean zh = isZh();
        String title = zh ? "剪贴板历史" : "Clipboard History";
        if (items == null || items.length() == 0) {
            String empty = zh ? "无记录" : "No entries";
            return HtmlOutputHelper.card("📋", title, HtmlOutputHelper.muted(empty));
        }
        String[] headers = zh ? new String[]{"预览", "时间"} : new String[]{"Preview", "Time"};
        List<String[]> rows = new ArrayList<>();
        String emptyParen = "(" + (zh ? "空" : "empty") + ")";
        for (int i = 0; i < items.length(); i++) {
            JSONObject row = items.optJSONObject(i);
            String t = row != null ? row.optString("text", "") : "";
            String line = t.isEmpty() ? emptyParen : truncateForDisplay(t, DISPLAY_ITEM_MAX);
            long ts = row != null ? row.optLong("timestamp", 0L) : 0L;
            rows.add(new String[]{line, formatClipboardTimestamp(ts)});
        }
        String body = HtmlOutputHelper.table(headers, rows);
        return HtmlOutputHelper.card("📋", title, body);
    }

    private static String formatSearchHistoryHtml(JSONObject result) {
        int count = result.optInt("count", 0);
        JSONArray items = result.optJSONArray("items");
        boolean zh = isZh();
        String title = zh ? "搜索结果" : "Search Results";
        String summary = (zh ? "匹配 " : "Matches: ") + count;
        if (items == null || items.length() == 0) {
            return HtmlOutputHelper.card("🔍", title,
                    HtmlOutputHelper.muted(summary) + HtmlOutputHelper.p(zh ? "无条目" : "No entries"));
        }
        String[] headers = zh ? new String[]{"预览", "时间"} : new String[]{"Preview", "Time"};
        List<String[]> rows = new ArrayList<>();
        String emptyParen = "(" + (zh ? "空" : "empty") + ")";
        for (int i = 0; i < items.length(); i++) {
            JSONObject row = items.optJSONObject(i);
            String t = row != null ? row.optString("text", "") : "";
            String line = t.isEmpty() ? emptyParen : truncateForDisplay(t, DISPLAY_ITEM_MAX);
            long ts = row != null ? row.optLong("timestamp", 0L) : 0L;
            rows.add(new String[]{line, formatClipboardTimestamp(ts)});
        }
        String body = HtmlOutputHelper.muted(summary) + HtmlOutputHelper.table(headers, rows);
        return HtmlOutputHelper.card("🔍", title, body);
    }

    /**
     * 构造失败响应 JSON。
     *
     * @param msg 错误信息（如参数缺失、超时）
     * @return JSON 字符串
     * @throws Exception JSON 异常
     */
    private String error(String msg) throws Exception {
        return new JSONObject().put("success", false).put("error", msg).toString();
    }

    /**
     * 在 Android 主线程执行剪贴板相关操作：若当前已在主线程则直接执行，否则通过 Handler 投递并阻塞等待结果。
     *
     * @param context 传递给任务的 Context
     * @param task    读/写剪贴板逻辑
     * @return 任务返回值
     * @throws Exception 超时、任务抛错或中断
     */
    private String runOnMain(Context context, ClipboardTask task) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return task.run(context);
        }
        // 后台线程调用：用闭锁同步等待主线程执行完毕
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

    /**
     * 获取系统剪贴板服务并校验类型。
     *
     * @param context 上下文
     * @return 非 null 的 {@link ClipboardManager}
     * @throws IllegalArgumentException 服务不可用
     */
    private static ClipboardManager clipboardManager(Context context) {
        Object svc = context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (!(svc instanceof ClipboardManager)) {
            throw new IllegalArgumentException("Clipboard service unavailable");
        }
        return (ClipboardManager) svc;
    }

    /**
     * 读取当前主剪贴板第一条纯文本内容。
     *
     * @param cm 剪贴板管理器
     * @return 文本内容；无剪贴或空项时返回空串
     */
    private static String readClipText(ClipboardManager cm) {
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            return "";
        }
        CharSequence t = clip.getItemAt(0).getText();
        return t != null ? t.toString() : "";
    }

    /**
     * 主线程安全地读取剪贴板文本并封装为 JSON。
     *
     * @param context 上下文
     * @return {@code {"text":"..."}} 形式的 JSON 字符串
     * @throws Exception {@link #runOnMain} 异常
     */
    private String getClipboard(Context context) throws Exception {
        String text = runOnMain(context, new ClipboardTask() {
            @Override
            public String run(Context ctx) {
                return readClipText(clipboardManager(ctx));
            }
        });
        return new JSONObject().put("text", text).toString();
    }

    /**
     * 将参数中的纯文本写入系统主剪贴板（{@link ClipData#newPlainText}）。
     *
     * @param context 上下文
     * @param params  必须包含键 {@code text}
     * @return {@code {"set":true}} JSON
     * @throws Exception 缺少参数或剪贴板操作失败
     */
    private String setClipboard(Context context, JSONObject params) throws Exception {
        if (!params.has("text")) {
            throw new IllegalArgumentException("Missing parameter: text");
        }
        final String text = params.optString("text", "");
        runOnMain(context, new ClipboardTask() {
            @Override
            public String run(Context ctx) {
                // 使用纯文本 MIME 类型写入主剪贴板
                ClipData data = ClipData.newPlainText("text", text);
                clipboardManager(ctx).setPrimaryClip(data);
                return "";
            }
        });
        return new JSONObject().put("set", true).toString();
    }

    /**
     * 用空纯文本覆盖主剪贴板，等效于清空常见文本剪贴内容。
     *
     * @param context 上下文
     * @return {@code {"cleared":true}}
     * @throws Exception 剪贴板操作失败
     */
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

    /**
     * 若文件父目录不存在则创建（历史文件写入前使用）。
     *
     * @param file 目标文件
     */
    private static void ensureParentDir(File file) {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
    }

    /**
     * 以平台默认编码读取整个文本文件（历史文件约定为 UTF-8 兼容 ASCII JSON）。
     *
     * @param file 文件路径
     * @return 文件内容；非普通文件时返回 {@code "[]"}
     * @throws IOException 读取失败
     */
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

    /**
     * 覆盖写入文本文件，必要时创建父目录。
     *
     * @param file 目标文件
     * @param text 完整文件内容
     * @throws IOException 写入失败
     */
    private static void writeFileUtf8(File file, String text) throws IOException {
        ensureParentDir(file);
        FileWriter writer = new FileWriter(file);
        try {
            writer.write(text);
        } finally {
            writer.close();
        }
    }

    /**
     * 从 {@link #HISTORY_FILE} 加载 JSON 数组；文件不存在或损坏时返回空数组。
     *
     * @return 历史条目 {@link JSONArray}，每项为含 {@code text}、{@code timestamp} 的对象
     * @throws Exception 读取 IO 异常（解析失败则吞掉并返回空数组）
     */
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

    /**
     * 将整个历史数组写回磁盘文件。
     *
     * @param arr 要持久化的 {@link JSONArray}
     * @throws Exception IO 或 JSON 异常
     */
    private void saveHistoryArray(JSONArray arr) throws Exception {
        writeFileUtf8(new File(HISTORY_FILE), arr.toString());
    }

    /**
     * 限制数组长度：索引 0 为最旧，末尾为最新；超过 {@link #HISTORY_MAX} 时从头部删除旧条目。
     *
     * @param arr 内存中的历史数组（会被原地修改）
     */
    private void trimOldest(JSONArray arr) {
        while (arr.length() > HISTORY_MAX) {
            arr.remove(0);
        }
    }

    /**
     * 在历史末尾追加一条记录并保存；超长时自动丢弃最旧记录。
     *
     * @param text 剪贴板文本，可为 null（存为空串）
     * @throws Exception 读写文件失败
     */
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

    /**
     * 返回最近若干条历史，顺序为从新到旧（与存储文件中的时间顺序相反遍历）。
     *
     * @param params {@code limit} 可选，默认 {@link #DEFAULT_LIMIT}，最大不超过 {@link #HISTORY_MAX}
     * @return JSON：{@code items} 数组与 {@code count}
     * @throws Exception 加载历史失败
     */
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
        // 从数组尾部（最新）向前取 limit 条
        for (int i = n - 1; i >= from; i--) {
            out.put(arr.optJSONObject(i));
        }
        return new JSONObject().put("items", out).put("count", out.length()).toString();
    }

    /**
     * 读取当前剪贴板内容并追加到持久化历史文件。
     *
     * @param context 上下文
     * @return {@code saved} 与 {@code textLength}
     * @throws Exception 剪贴板或文件 IO 异常
     */
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

    /**
     * 在历史记录中按子串搜索（不区分大小写），结果从新到旧排列。
     *
     * @param params 必须包含非空 {@code keyword}
     * @return {@code items} 与 {@code count}
     * @throws IllegalArgumentException 关键字为空
     * @throws Exception 加载历史失败
     */
    private String searchHistory(JSONObject params) throws Exception {
        String keyword = params.optString("keyword", "").trim();
        if (keyword.isEmpty()) {
            throw new IllegalArgumentException("Missing parameter: keyword");
        }
        String kw = keyword.toLowerCase(Locale.ROOT);
        JSONArray arr = loadHistoryArray();
        JSONArray out = new JSONArray();
        // 从新到旧扫描，匹配项按时间倒序加入结果
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

    /**
     * 清空磁盘上的全部剪贴板历史（写入空 JSON 数组）。
     *
     * @return {@code {"cleared":true}}
     * @throws Exception 写入失败
     */
    private String clearHistory() throws Exception {
        saveHistoryArray(new JSONArray());
        return new JSONObject().put("cleared", true).toString();
    }
}
