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

public class NotesPlugin implements ModulePlugin {

    private static final String NOTES_DIR = "/sdcard/PandaGenie/data/notes/";
    private static final int PREVIEW_MAX = 120;
    private static final SimpleDateFormat TS = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

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
                    return ok(out, formatExportNoteDisplay(out));
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
        return ok(output, null);
    }

    private String ok(String output, String displayText) throws Exception {
        JSONObject j = new JSONObject();
        j.put("success", true);
        j.put("output", output);
        if (displayText != null) {
            j.put("_displayText", displayText);
        }
        return j.toString();
    }

    private String formatCreateNoteDisplay(String noteJson) throws Exception {
        JSONObject note = new JSONObject(noteJson);
        String title = note.optString("title", "");
        return "📝 Note Created\n━━━━━━━━━━━━━━\n▸ Title: " + title;
    }

    private String formatListNotesDisplay(String listJson) throws Exception {
        JSONObject root = new JSONObject(listJson);
        JSONArray notes = root.optJSONArray("notes");
        int count = root.optInt("count", notes != null ? notes.length() : 0);
        StringBuilder sb = new StringBuilder();
        sb.append("📋 Notes (").append(count).append(" total)\n━━━━━━━━━━━━━━");
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

    private String formatGetNoteDisplay(String noteJson) throws Exception {
        JSONObject note = new JSONObject(noteJson);
        String title = note.optString("title", "");
        String contentPreview = preview(note.optString("content", ""));
        return "📝 Note\n━━━━━━━━━━━━━━\n▸ Title: " + title + "\n" + contentPreview;
    }

    private String formatUpdateNoteDisplay() {
        return "✅ Note updated";
    }

    private String formatDeleteNoteDisplay() {
        return "🗑️ Note deleted";
    }

    private String formatSearchNotesDisplay(String searchJson, String keyword) throws Exception {
        JSONObject root = new JSONObject(searchJson);
        int count = root.optInt("count", 0);
        JSONArray notes = root.optJSONArray("notes");
        if (notes != null && count == 0) {
            count = notes.length();
        }
        return "🔍 Search Results\n━━━━━━━━━━━━━━\nFound " + count + " notes matching '" + keyword + "'";
    }

    private String formatExportNoteDisplay(String exportJson) throws Exception {
        JSONObject root = new JSONObject(exportJson);
        String path = root.optString("path", "");
        return "📤 Note exported\n▸ File: " + path;
    }

    private String error(String msg) throws Exception {
        return new JSONObject().put("success", false).put("error", msg).toString();
    }

    private void ensureNotesDir() {
        File dir = new File(NOTES_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    private File noteFile(String id) {
        return new File(NOTES_DIR, id + ".json");
    }

    private String nowString() {
        return TS.format(new Date());
    }

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
                return ub.compareTo(ua);
            }
        });
        return list;
    }

    private boolean matchesKeyword(JSONObject note, String keywordLower) {
        if (keywordLower == null || keywordLower.isEmpty()) {
            return true;
        }
        String title = note.optString("title", "").toLowerCase(Locale.ROOT);
        String content = note.optString("content", "").toLowerCase(Locale.ROOT);
        return title.contains(keywordLower) || content.contains(keywordLower);
    }

    private JSONObject toSummary(JSONObject src) throws Exception {
        JSONObject row = new JSONObject();
        row.put("id", src.optString("id", ""));
        row.put("title", src.optString("title", ""));
        row.put("createdAt", src.optString("createdAt", ""));
        row.put("updatedAt", src.optString("updatedAt", ""));
        row.put("preview", preview(src.optString("content", "")));
        return row;
    }

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
                .toString();
    }
}
