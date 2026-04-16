package ai.rorsch.moduleplugins.notes;

import android.content.Context;

import ai.rorsch.pandagenie.module.runtime.ModulePlugin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * PandaGenie「笔记」模块插件。
 * <p>
 * <b>模块用途：</b>在固定目录下以 JSON 文件形式持久化笔记，支持创建、列表、读取、更新、删除、
 * 关键词搜索以及导出为纯文本文件，供助手或用户管理本地备忘。
 * </p>
 * <p>
 * <b>对外 API（{@link #invoke} 的 {@code action}）：</b>
 * </p>
 * <ul>
 *   <li>{@code createNote} — 新建笔记（必填 title，可选 content）</li>
 *   <li>{@code listNotes} — 列出摘要，可选 {@code keyword} 过滤标题与正文</li>
 *   <li>{@code getNote} — 按 id 读取完整 JSON 笔记</li>
 *   <li>{@code updateNote} — 按 id 更新 title 和/或 content</li>
 *   <li>{@code deleteNote} — 按 id 删除文件</li>
 *   <li>{@code searchNotes} — 按 keyword 搜索（标题或正文包含即命中）</li>
 *   <li>{@code exportNote} — 将指定笔记导出为 .txt（可指定文件路径或目录）</li>
 * </ul>
 * <p>
 * 数据目录为 {@link #NOTES_DIR}；本类由宿主 {@code ModuleRuntime} 反射加载。需确保应用具有外部存储访问权限。
 * </p>
 */
public class NotesPlugin implements ModulePlugin {

    /** 笔记 JSON 文件存放目录（每篇笔记一个 {@code id.json}）。 */
    private static final String NOTES_DIR = "/sdcard/PandaGenie/data/notes/";
    /** 列表/摘要中正文预览最大字符数。 */
    private static final int PREVIEW_MAX = 120;
    /** 创建与更新时间戳格式。 */
    private static final SimpleDateFormat TS = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

    private static boolean isZh() {
        try {
            return Locale.getDefault().getLanguage().toLowerCase(Locale.ROOT).startsWith("zh");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 模块入口：仅将 {@link IllegalArgumentException} 转为错误响应，其它异常会向上抛出。
     *
     * @param context    上下文（当前实现未使用，保留接口一致）
     * @param action     操作名
     * @param paramsJson JSON 参数
     * @return 包装后的成功或失败 JSON 字符串
     * @throws Exception 非 IllegalArgumentException 的异常
     */
    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        JSONObject params = new JSONObject(emptyJson(paramsJson));
        try {
            switch (action) {
                case "createNote": {
                    String out = createNote(params);
                    return ok(out, formatCreateNoteDisplay(out));
                }
                case "listNotes": {
                    String out = listNotes(params);
                    return ok(out, formatListNotesDisplay(out));
                }
                case "getNote": {
                    String out = getNote(params);
                    return ok(out, formatGetNoteDisplay(out));
                }
                case "updateNote": {
                    String out = updateNote(params);
                    return ok(out, formatUpdateNoteDisplay());
                }
                case "deleteNote": {
                    String out = deleteNote(params);
                    return ok(out, formatDeleteNoteDisplay());
                }
                case "searchNotes": {
                    String out = searchNotes(params);
                    return ok(out, formatSearchNotesDisplay(out, params.optString("keyword", "").trim()));
                }
                case "exportNote": {
                    String out = exportNote(params);
                    JSONObject root = new JSONObject(out);
                    JSONArray rc = null;
                    String path = root.optString("path", "");
                    if (!path.isEmpty()) {
                        rc = new JSONArray();
                        String noteTitle = root.optString("noteTitle", "");
                        rc.put(richFile(path, noteTitle.isEmpty() ? null : noteTitle, "text/plain"));
                    }
                    return ok(out, formatExportNoteDisplay(out), rc);
                }
                case "openPage": {
                    JSONObject r = new JSONObject();
                    r.put("success", true);
                    r.put("output", "{}");
                    r.put("_openModule", true);
                    r.put("_displayText", isZh() ? "正在打开笔记助手..." : "Opening Notes...");
                    return r.toString();
                }
                default:
                    return error("Unsupported action: " + action);
            }
        } catch (IllegalArgumentException ex) {
            return error(ex.getMessage());
        }
    }

    /**
     * 将空或空白 JSON 参数视为 {@code "{}"}。
     *
     * @param v 原始字符串
     * @return 非空 JSON 对象字面量
     */
    private String emptyJson(String v) {
        return v == null || v.trim().isEmpty() ? "{}" : v;
    }

    /**
     * 成功响应，不含 {@code _displayText}。
     *
     * @param output 业务 JSON 字符串
     * @return 包装结果
     * @throws Exception JSON 异常
     */
    private String ok(String output) throws Exception {
        return ok(output, null);
    }

    /**
     * 成功响应，可选附带展示文案。
     *
     * @param output      写入 {@code output} 字段的字符串
     * @param displayText 非 null 时写入 {@code _displayText}（可为空串则仍会 put）
     * @return JSON 字符串
     * @throws Exception JSON 异常
     */
    private String ok(String output, String displayText) throws Exception {
        return ok(output, displayText, null);
    }

    /**
     * 成功响应，可选附带展示文案与 {@code _richContent}。
     *
     * @param output       写入 {@code output} 字段的字符串
     * @param displayText  非 null 时写入 {@code _displayText}
     * @param richContent  非 null 且非空时写入 {@code _richContent}
     * @return JSON 字符串
     * @throws Exception JSON 异常
     */
    private String ok(String output, String displayText, JSONArray richContent) throws Exception {
        JSONObject j = new JSONObject();
        j.put("success", true);
        j.put("output", output);
        if (displayText != null) {
            j.put("_displayText", displayText);
        }
        if (richContent != null && richContent.length() > 0) {
            j.put("_richContent", richContent);
        }
        return j.toString();
    }

    private static JSONObject richFile(String path, String title, String mimeType) throws Exception {
        JSONObject rc = new JSONObject();
        rc.put("type", "file");
        rc.put("path", path);
        if (title != null) rc.put("title", title);
        if (mimeType != null) rc.put("mimeType", mimeType);
        File f = new File(path);
        if (f.exists()) rc.put("size", f.length());
        return rc;
    }

    /**
     * 「创建笔记」操作的展示文案。
     *
     * @param noteJson 完整笔记 JSON
     * @return 展示字符串
     * @throws Exception 解析异常
     */
    private String formatCreateNoteDisplay(String noteJson) throws Exception {
        JSONObject note = new JSONObject(noteJson);
        String title = note.optString("title", "");
        if (isZh()) {
            return "📝 笔记已创建\n━━━━━━━━━━━━━━\n▸ 标题: " + title;
        }
        return "📝 Note Created\n━━━━━━━━━━━━━━\n▸ Title: " + title;
    }

    /**
     * 笔记列表的展示文案（含序号、标题、预览）。
     *
     * @param listJson {@link #listNotes} 返回的 JSON
     * @return 多行文本
     * @throws Exception 解析异常
     */
    private String formatListNotesDisplay(String listJson) throws Exception {
        JSONObject root = new JSONObject(listJson);
        JSONArray notes = root.optJSONArray("notes");
        int count = root.optInt("count", notes != null ? notes.length() : 0);
        StringBuilder sb = new StringBuilder();
        if (isZh()) {
            sb.append("📋 笔记（共 ").append(count).append("）\n━━━━━━━━━━━━━━");
        } else {
            sb.append("📋 Notes (").append(count).append(" total)\n━━━━━━━━━━━━━━");
        }
        if (notes != null && notes.length() > 0) {
            sb.append('\n');
            for (int i = 0; i < notes.length(); i++) {
                JSONObject row = notes.optJSONObject(i);
                if (row == null) {
                    continue;
                }
                String title = row.optString("title", "");
                String pv = row.optString("preview", "");
                if (i > 0) {
                    sb.append('\n');
                }
                sb.append(i + 1).append(". ").append(title).append(" - ").append(pv);
            }
        }
        return sb.toString();
    }

    /**
     * 单篇笔记读取后的展示（标题 + 正文预览）。
     *
     * @param noteJson 笔记 JSON
     * @return 展示文本
     * @throws Exception 解析异常
     */
    private String formatGetNoteDisplay(String noteJson) throws Exception {
        JSONObject note = new JSONObject(noteJson);
        String title = note.optString("title", "");
        String contentPreview = preview(note.optString("content", ""));
        if (isZh()) {
            return "📝 笔记\n━━━━━━━━━━━━━━\n▸ 标题: " + title + "\n" + contentPreview;
        }
        return "📝 Note\n━━━━━━━━━━━━━━\n▸ Title: " + title + "\n" + contentPreview;
    }

    /**
     * 更新成功后的固定提示。
     *
     * @return 展示字符串
     */
    private String formatUpdateNoteDisplay() {
        return isZh() ? "✅ 笔记已更新" : "✅ Note updated";
    }

    /**
     * 删除成功后的固定提示。
     *
     * @return 展示字符串
     */
    private String formatDeleteNoteDisplay() {
        return isZh() ? "🗑️ 笔记已删除" : "🗑️ Note deleted";
    }

    /**
     * 搜索结果摘要：匹配数量与关键词。
     *
     * @param searchJson {@link #searchNotes} 输出
     * @param keyword    用户搜索词
     * @return 展示文本
     * @throws Exception 解析异常
     */
    private String formatSearchNotesDisplay(String searchJson, String keyword) throws Exception {
        JSONObject root = new JSONObject(searchJson);
        int count = root.optInt("count", 0);
        JSONArray notes = root.optJSONArray("notes");
        if (notes != null && count == 0) {
            count = notes.length();
        }
        if (isZh()) {
            return "🔍 搜索结果\n━━━━━━━━━━━━━━\n找到 " + count + " 条匹配笔记 '" + keyword + "'";
        }
        return "🔍 Search Results\n━━━━━━━━━━━━━━\nFound " + count + " notes matching '" + keyword + "'";
    }

    /**
     * 导出成功后的文件路径展示。
     *
     * @param exportJson {@link #exportNote} 输出
     * @return 展示文本
     * @throws Exception 解析异常
     */
    private String formatExportNoteDisplay(String exportJson) throws Exception {
        JSONObject root = new JSONObject(exportJson);
        String path = root.optString("path", "");
        if (isZh()) {
            return "📤 笔记已导出\n▸ 文件: " + path;
        }
        return "📤 Note exported\n▸ File: " + path;
    }

    /**
     * 失败响应 JSON。
     *
     * @param msg 错误信息
     * @return JSON 字符串
     * @throws Exception JSON 异常
     */
    private String error(String msg) throws Exception {
        return new JSONObject().put("success", false).put("error", msg).toString();
    }

    /**
     * 若笔记目录不存在则递归创建。
     */
    private void ensureNotesDir() {
        File dir = new File(NOTES_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    /**
     * 由笔记 id 构造磁盘上的 JSON 文件。
     *
     * @param id 笔记唯一标识
     * @return {@link File} 对象
     */
    private File noteFile(String id) {
        return new File(NOTES_DIR, id + ".json");
    }

    /**
     * 当前时间的格式化时间戳字符串。
     *
     * @return {@link #TS} 格式的时间
     */
    private String nowString() {
        return TS.format(new Date());
    }

    /**
     * 生成大概率唯一的笔记 id：毫秒时间戳 + 随机后缀，冲突时重试。
     *
     * @return 新 id 字符串
     */
    private String generateNoteId() {
        String base = String.valueOf(System.currentTimeMillis());
        int rnd = (int) (Math.random() * Integer.MAX_VALUE);
        String candidate = base + "_" + rnd;
        int guard = 0;
        while (noteFile(candidate).exists() && guard < 256) {
            rnd = (int) (Math.random() * Integer.MAX_VALUE);
            candidate = base + "_" + rnd;
            guard++;
        }
        return candidate;
    }

    /**
     * 以平台默认编码读取整个文本文件到字符串（与 {@link FileReader} 行为一致）。
     *
     * @param file 文件
     * @return 文件全文
     * @throws IOException 读取失败
     */
    private static String readFileUtf8(File file) throws IOException {
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
     * 写入文本文件（创建父目录、覆盖写入）。
     *
     * @param file 目标文件
     * @param text 完整文本
     * @throws IOException IO 错误
     */
    private static void writeFileUtf8(File file, String text) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        FileWriter writer = new FileWriter(file);
        try {
            writer.write(text);
        } finally {
            writer.close();
        }
    }

    /**
     * 将正文截断为不超过 {@link #PREVIEW_MAX} 的预览，过长加省略号。
     *
     * @param content 原始正文
     * @return 预览字符串
     */
    private String preview(String content) {
        if (content == null) {
            return "";
        }
        String t = content.trim();
        if (t.length() <= PREVIEW_MAX) {
            return t;
        }
        return t.substring(0, PREVIEW_MAX) + "...";
    }

    /**
     * 扫描笔记目录下所有 .json，解析为 {@link JSONObject}，按 {@code updatedAt} 降序排序。
     *
     * @return 笔记列表；损坏文件会被跳过
     * @throws Exception 目录创建或列举失败
     */
    private List<JSONObject> loadAllNotes() throws Exception {
        ensureNotesDir();
        File dir = new File(NOTES_DIR);
        File[] files = dir.listFiles();
        if (files == null) {
            return Collections.emptyList();
        }
        List<JSONObject> list = new ArrayList<JSONObject>();
        for (File f : files) {
            if (!f.isFile() || !f.getName().endsWith(".json")) {
                continue;
            }
            try {
                list.add(new JSONObject(readFileUtf8(f)));
            } catch (Exception ignored) {
                // skip corrupt files
            }
        }
        Collections.sort(list, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject a, JSONObject b) {
                String ua = a.optString("updatedAt", "");
                String ub = b.optString("updatedAt", "");
                // ISO 风格时间字符串可按字典序比较；ub 与 ua 颠倒实现降序（最近更新在前）
                return ub.compareTo(ua);
            }
        });
        return list;
    }

    /**
     * 判断笔记标题或正文是否包含关键词（keywordLower 已小写；空关键词视为全匹配）。
     *
     * @param note         笔记对象
     * @param keywordLower 小写关键词，可为空
     * @return 是否匹配
     */
    private boolean matchesKeyword(JSONObject note, String keywordLower) {
        if (keywordLower == null || keywordLower.isEmpty()) {
            return true;
        }
        String title = note.optString("title", "").toLowerCase(Locale.ROOT);
        String content = note.optString("content", "").toLowerCase(Locale.ROOT);
        return title.contains(keywordLower) || content.contains(keywordLower);
    }

    /**
     * 由完整笔记生成列表行用的摘要对象（不含全文 content）。
     *
     * @param src 完整笔记 JSON
     * @return 摘要 JSON
     * @throws Exception 构造异常
     */
    private JSONObject toSummary(JSONObject src) throws Exception {
        JSONObject row = new JSONObject();
        row.put("id", src.optString("id", ""));
        row.put("title", src.optString("title", ""));
        row.put("createdAt", src.optString("createdAt", ""));
        row.put("updatedAt", src.optString("updatedAt", ""));
        row.put("preview", preview(src.optString("content", "")));
        return row;
    }

    /**
     * 创建新笔记并写入 {@code id.json}。
     *
     * @param params 必填 {@code title}；可选 {@code content}
     * @return 新笔记完整 JSON 字符串
     * @throws Exception 参数非法或 IO 失败
     */
    private String createNote(JSONObject params) throws Exception {
        String title = params.optString("title", "").trim();
        if (title.isEmpty()) {
            throw new IllegalArgumentException("Missing or empty parameter: title");
        }
        String content = params.optString("content", "");
        if (content == null) {
            content = "";
        }

        ensureNotesDir();
        String id = generateNoteId();
        String created = nowString();
        JSONObject note = new JSONObject();
        note.put("id", id);
        note.put("title", title);
        note.put("content", content);
        note.put("createdAt", created);
        note.put("updatedAt", created);

        writeFileUtf8(noteFile(id), note.toString());
        return note.toString();
    }

    /**
     * 列出全部笔记摘要，可选按 keyword 过滤（与 {@link #searchNotes} 类似但 keyword 可为空表示全量）。
     *
     * @param params 可选 {@code keyword}
     * @return JSON：{@code notes} 数组、{@code count}
     * @throws Exception IO 或 JSON 异常
     */
    private String listNotes(JSONObject params) throws Exception {
        String keyword = params.optString("keyword", "").trim();
        String kwLower = keyword.isEmpty() ? "" : keyword.toLowerCase(Locale.ROOT);
        List<JSONObject> all = loadAllNotes();
        JSONArray out = new JSONArray();
        for (JSONObject src : all) {
            if (!matchesKeyword(src, kwLower)) {
                continue;
            }
            out.put(toSummary(src));
        }
        return new JSONObject().put("notes", out).put("count", out.length()).toString();
    }

    /**
     * 按 id 读取单篇笔记原始 JSON 文件内容。
     *
     * @param params 必填 {@code id}
     * @return 文件中的 JSON 字符串
     * @throws Exception 缺少 id、文件不存在或读取失败
     */
    private String getNote(JSONObject params) throws Exception {
        String id = params.optString("id", "").trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("Missing parameter: id");
        }
        File f = noteFile(id);
        if (!f.isFile()) {
            throw new IllegalArgumentException("Note not found: " + id);
        }
        return readFileUtf8(f);
    }

    /**
     * 更新已有笔记的标题和/或正文，并刷新 {@code updatedAt}。
     *
     * @param params 必填 {@code id}；至少提供 {@code title} 或 {@code content} 字段之一
     * @return 更新后的完整笔记 JSON
     * @throws Exception 参数或文件问题
     */
    private String updateNote(JSONObject params) throws Exception {
        String id = params.optString("id", "").trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("Missing parameter: id");
        }
        if (!params.has("title") && !params.has("content")) {
            throw new IllegalArgumentException("Provide at least one of: title, content");
        }
        File f = noteFile(id);
        if (!f.isFile()) {
            throw new IllegalArgumentException("Note not found: " + id);
        }
        JSONObject note = new JSONObject(readFileUtf8(f));
        if (params.has("title")) {
            note.put("title", params.optString("title", ""));
        }
        if (params.has("content")) {
            String c = params.optString("content", "");
            if (c == null) {
                c = "";
            }
            note.put("content", c);
        }
        note.put("updatedAt", nowString());
        writeFileUtf8(f, note.toString());
        return note.toString();
    }

    /**
     * 删除指定 id 的笔记文件。
     *
     * @param params 必填 {@code id}
     * @return JSON：{@code deleted:true}、{@code id}
     * @throws Exception 文件不存在或删除失败
     */
    private String deleteNote(JSONObject params) throws Exception {
        String id = params.optString("id", "").trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("Missing parameter: id");
        }
        File f = noteFile(id);
        if (!f.isFile()) {
            throw new IllegalArgumentException("Note not found: " + id);
        }
        if (!f.delete()) {
            throw new IllegalArgumentException("Failed to delete note: " + id);
        }
        return new JSONObject().put("deleted", true).put("id", id).toString();
    }

    /**
     * 按关键词搜索笔记（标题或正文包含即命中），返回摘要列表。
     *
     * @param params 必填非空 {@code keyword}
     * @return JSON：notes、count
     * @throws Exception 参数非法或 IO 错误
     */
    private String searchNotes(JSONObject params) throws Exception {
        String keyword = params.optString("keyword", "").trim();
        if (keyword.isEmpty()) {
            throw new IllegalArgumentException("Missing parameter: keyword");
        }
        String kwLower = keyword.toLowerCase(Locale.ROOT);
        List<JSONObject> all = loadAllNotes();
        JSONArray out = new JSONArray();
        for (JSONObject src : all) {
            if (matchesKeyword(src, kwLower)) {
                out.put(toSummary(src));
            }
        }
        return new JSONObject().put("notes", out).put("count", out.length()).toString();
    }

    /**
     * 将笔记导出为纯文本：第一行为标题，空行后为正文。
     *
     * @param params 必填 {@code id} 与 {@code path}；{@code path} 若以 .txt 结尾则视为完整文件路径，否则视为目录（写入 {@code id.txt}）
     * @return JSON：{@code exported}、{@code path} 实际绝对路径
     * @throws Exception 参数、源文件或写入失败
     */
    private String exportNote(JSONObject params) throws Exception {
        String id = params.optString("id", "").trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("Missing parameter: id");
        }
        String path = params.optString("path", "").trim();
        if (path.isEmpty()) {
            throw new IllegalArgumentException("Missing parameter: path");
        }

        File f = noteFile(id);
        if (!f.isFile()) {
            throw new IllegalArgumentException("Note not found: " + id);
        }
        JSONObject note = new JSONObject(readFileUtf8(f));
        String title = note.optString("title", "");
        String content = note.optString("content", "");
        StringBuilder sb = new StringBuilder();
        sb.append(title).append("\n\n").append(content);

        File outFile;
        if (path.endsWith(".txt")) {
            outFile = new File(path);
        } else {
            String dirPath = path.endsWith("/") ? path : path + "/";
            File dir = new File(dirPath);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            outFile = new File(dir, id + ".txt");
        }

        writeFileUtf8(outFile, sb.toString());
        return new JSONObject()
                .put("exported", true)
                .put("path", outFile.getAbsolutePath())
                .put("noteTitle", title)
                .toString();
    }
}
