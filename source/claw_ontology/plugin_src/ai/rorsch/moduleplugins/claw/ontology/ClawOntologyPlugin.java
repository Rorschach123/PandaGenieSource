package ai.rorsch.moduleplugins.claw.ontology;

import ai.rorsch.pandagenie.module.runtime.HtmlOutputHelper;
import ai.rorsch.pandagenie.module.runtime.ModulePlugin;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ClawOntologyPlugin implements ModulePlugin {
    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        JSONObject params = new JSONObject(emptyJson(paramsJson));
        try {
            if ("addFact".equals(action)) return addFact(context, params);
            if ("queryFacts".equals(action) || "searchFacts".equals(action) || "getFacts".equals(action)) {
                return queryFacts(context, params);
            }
            if ("importFacts".equals(action)) return importFacts(context, params);
            if ("summarizeGraph".equals(action)) return summarizeGraph(context);
            if ("deleteFact".equals(action)) return deleteFact(context, params);
            if ("clearGraph".equals(action)) return clearGraph(context, params);
            if ("openPage".equals(action)) {
                return new JSONObject().put("success", true).put("output", "{}").put("_openModule", true).toString();
            }
            return error("Unsupported action: " + action);
        } catch (Exception e) {
            return error(e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    private String addFact(Context context, JSONObject params) throws Exception {
        JSONArray facts = load(context);
        JSONObject fact = buildFact(params);
        facts.put(fact);
        save(context, facts);

        JSONObject out = new JSONObject()
                .put("action", "addFact")
                .put("count", facts.length())
                .put("fact", fact)
                .put("facts", new JSONArray().put(fact))
                .put("store", store(context).getAbsolutePath());
        return ok(out, "Added fact: " + factLine(fact), formatHtml(out));
    }

    private String queryFacts(Context context, JSONObject params) throws Exception {
        JSONArray facts = load(context);
        int limit = clamp(params.optInt("limit", 20), 1, 100);
        JSONArray result = new JSONArray();
        for (int i = facts.length() - 1; i >= 0 && result.length() < limit; i--) {
            JSONObject fact = facts.optJSONObject(i);
            if (fact != null && matches(fact, params)) result.put(fact);
        }
        JSONObject out = new JSONObject()
                .put("action", "queryFacts")
                .put("count", result.length())
                .put("totalFacts", facts.length())
                .put("facts", result)
                .put("store", store(context).getAbsolutePath());
        return ok(out, "Found " + result.length() + " fact(s)", formatHtml(out));
    }

    private String importFacts(Context context, JSONObject params) throws Exception {
        String text = params.optString("text", "").trim();
        if (text.isEmpty()) throw new IllegalArgumentException("text is required");
        JSONArray facts = load(context);
        JSONArray imported = new JSONArray();
        JSONArray tags = parseTags(params.opt("tags"));
        String source = params.optString("source", "import");

        String[] lines = text.split("\\r?\\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            JSONObject seed = parseFactLine(line);
            if (seed == null) continue;
            seed.put("tags", copyArray(tags));
            seed.put("source", source);
            JSONObject fact = buildFact(seed);
            facts.put(fact);
            imported.put(fact);
        }
        save(context, facts);
        JSONObject out = new JSONObject()
                .put("action", "importFacts")
                .put("count", imported.length())
                .put("totalFacts", facts.length())
                .put("facts", imported)
                .put("store", store(context).getAbsolutePath());
        return ok(out, "Imported " + imported.length() + " fact(s)", formatHtml(out));
    }

    private String summarizeGraph(Context context) throws Exception {
        JSONArray facts = load(context);
        Map<String, Integer> relations = new HashMap<>();
        Map<String, Integer> subjects = new HashMap<>();
        Map<String, Integer> tags = new HashMap<>();
        long newest = 0L;
        for (int i = 0; i < facts.length(); i++) {
            JSONObject fact = facts.optJSONObject(i);
            if (fact == null) continue;
            inc(relations, fact.optString("relation", ""));
            inc(subjects, fact.optString("subject", ""));
            JSONArray tagArr = fact.optJSONArray("tags");
            if (tagArr != null) {
                for (int j = 0; j < tagArr.length(); j++) inc(tags, tagArr.optString(j, ""));
            }
            newest = Math.max(newest, fact.optLong("createdAt", 0L));
        }
        JSONObject out = new JSONObject()
                .put("action", "summarizeGraph")
                .put("count", facts.length())
                .put("topRelations", top(relations, 8))
                .put("topSubjects", top(subjects, 8))
                .put("topTags", top(tags, 8))
                .put("newestCreatedAt", newest == 0L ? JSONObject.NULL : newest)
                .put("store", store(context).getAbsolutePath());
        return ok(out, "Ontology facts: " + facts.length(), formatHtml(out));
    }

    private String deleteFact(Context context, JSONObject params) throws Exception {
        String id = params.optString("id", "").trim();
        if (id.isEmpty()) throw new IllegalArgumentException("id is required");
        JSONArray facts = load(context);
        JSONArray kept = new JSONArray();
        JSONObject deleted = null;
        for (int i = 0; i < facts.length(); i++) {
            JSONObject fact = facts.optJSONObject(i);
            if (fact != null && id.equals(fact.optString("id"))) {
                deleted = fact;
            } else if (fact != null) {
                kept.put(fact);
            }
        }
        if (deleted == null) throw new IllegalArgumentException("Fact not found: " + id);
        save(context, kept);
        JSONObject out = new JSONObject()
                .put("action", "deleteFact")
                .put("count", kept.length())
                .put("deleted", deleted)
                .put("store", store(context).getAbsolutePath());
        return ok(out, "Deleted fact: " + id, formatHtml(out));
    }

    private String clearGraph(Context context, JSONObject params) throws Exception {
        boolean confirm = params.optBoolean("confirm", false)
                || "CLEAR".equalsIgnoreCase(params.optString("confirm", ""));
        if (!confirm) throw new IllegalArgumentException("clearGraph requires confirm=true");
        JSONArray empty = new JSONArray();
        save(context, empty);
        JSONObject out = new JSONObject()
                .put("action", "clearGraph")
                .put("count", 0)
                .put("store", store(context).getAbsolutePath());
        return ok(out, "Ontology cleared", formatHtml(out));
    }

    private JSONObject buildFact(JSONObject params) throws Exception {
        String subject = params.optString("subject", "").trim();
        String relation = params.optString("relation", "").trim();
        String object = params.optString("object", "").trim();
        if (subject.isEmpty() || relation.isEmpty() || object.isEmpty()) {
            throw new IllegalArgumentException("subject, relation and object are required");
        }
        double confidence = params.has("confidence") ? params.optDouble("confidence", 1.0) : 1.0;
        confidence = Math.max(0.0, Math.min(1.0, confidence));
        return new JSONObject()
                .put("id", firstNonEmpty(params.optString("id", ""), newId()))
                .put("subject", subject)
                .put("relation", relation)
                .put("object", object)
                .put("type", firstNonEmpty(params.optString("type", ""), "fact"))
                .put("tags", parseTags(params.opt("tags")))
                .put("confidence", confidence)
                .put("source", params.optString("source", "manual"))
                .put("createdAt", params.optLong("createdAt", System.currentTimeMillis()));
    }

    private JSONObject parseFactLine(String line) throws Exception {
        String[] parts;
        if (line.contains("|")) {
            parts = line.split("\\|");
        } else if (line.contains("->")) {
            parts = line.split("\\s*->\\s*");
        } else {
            return null;
        }
        if (parts.length < 2) return null;
        JSONObject o = new JSONObject();
        if (parts.length == 2) {
            o.put("subject", parts[0].trim());
            o.put("relation", "related_to");
            o.put("object", parts[1].trim());
        } else {
            o.put("subject", parts[0].trim());
            o.put("relation", parts[1].trim());
            o.put("object", joinTail(parts, 2));
        }
        return o;
    }

    private boolean matches(JSONObject fact, JSONObject params) {
        String query = params.optString("query", "").trim().toLowerCase(Locale.ROOT);
        String subject = params.optString("subject", "").trim().toLowerCase(Locale.ROOT);
        String relation = params.optString("relation", "").trim().toLowerCase(Locale.ROOT);
        String object = params.optString("object", "").trim().toLowerCase(Locale.ROOT);
        String tag = params.optString("tag", "").trim().toLowerCase(Locale.ROOT);

        if (!subject.isEmpty() && !contains(fact.optString("subject"), subject)) return false;
        if (!relation.isEmpty() && !contains(fact.optString("relation"), relation)) return false;
        if (!object.isEmpty() && !contains(fact.optString("object"), object)) return false;
        if (!tag.isEmpty() && !hasTag(fact.optJSONArray("tags"), tag)) return false;
        if (!query.isEmpty()) {
            String haystack = (fact.optString("subject") + " " + fact.optString("relation") + " "
                    + fact.optString("object") + " " + fact.optString("type") + " "
                    + fact.optString("source") + " " + tagsText(fact.optJSONArray("tags")))
                    .toLowerCase(Locale.ROOT);
            return haystack.contains(query);
        }
        return true;
    }

    private JSONArray load(Context context) throws Exception {
        File file = store(context);
        if (!file.exists()) return new JSONArray();
        try (FileInputStream in = new FileInputStream(file)) {
            String raw = new String(readAll(in), StandardCharsets.UTF_8).trim();
            if (raw.isEmpty()) return new JSONArray();
            if (raw.startsWith("[")) return new JSONArray(raw);
            JSONArray facts = new JSONObject(raw).optJSONArray("facts");
            return facts == null ? new JSONArray() : facts;
        }
    }

    private void save(Context context, JSONArray facts) throws Exception {
        File file = store(context);
        File dir = file.getParentFile();
        if (dir != null && !dir.exists()) dir.mkdirs();
        JSONObject root = new JSONObject()
                .put("version", 1)
                .put("updatedAt", System.currentTimeMillis())
                .put("facts", facts);
        try (FileOutputStream out = new FileOutputStream(file, false)) {
            out.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }

    private File store(Context context) {
        File base = context != null && context.getFilesDir() != null
                ? context.getFilesDir()
                : new File(".");
        return new File(new File(base, "claw_ontology"), "graph.json");
    }

    private String formatHtml(JSONObject out) {
        String action = out.optString("action", "ontology");
        String body = HtmlOutputHelper.badge(action, "blue")
                + HtmlOutputHelper.keyValue(new String[][]{
                {"Facts", String.valueOf(out.optInt("count"))},
                {"Store", out.optString("store", "-")}
        });

        JSONArray facts = out.optJSONArray("facts");
        if (facts != null && facts.length() > 0) {
            List<String[]> rows = new ArrayList<>();
            for (int i = 0; i < facts.length(); i++) {
                JSONObject f = facts.optJSONObject(i);
                if (f == null) continue;
                rows.add(new String[]{
                        f.optString("subject"),
                        f.optString("relation"),
                        f.optString("object"),
                        tagsText(f.optJSONArray("tags"))
                });
            }
            body += HtmlOutputHelper.table(new String[]{"Subject", "Relation", "Object", "Tags"},
                    rows);
        } else if ("summarizeGraph".equals(action)) {
            body += HtmlOutputHelper.table(new String[]{"Relation", "Count"}, topRows(out.optJSONArray("topRelations")));
            body += HtmlOutputHelper.table(new String[]{"Tag", "Count"}, topRows(out.optJSONArray("topTags")));
        } else {
            body += HtmlOutputHelper.muted("No facts to display.");
        }
        return HtmlOutputHelper.card("KG", "Claw Ontology", body);
    }

    private List<String[]> topRows(JSONArray arr) {
        List<String[]> rows = new ArrayList<>();
        if (arr == null || arr.length() == 0) {
            rows.add(new String[]{"-", "0"});
            return rows;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) rows.add(new String[]{o.optString("name"), String.valueOf(o.optInt("count"))});
        }
        return rows;
    }

    private JSONArray top(Map<String, Integer> counts, int limit) throws Exception {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
        Collections.sort(entries, new Comparator<Map.Entry<String, Integer>>() {
            @Override
            public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                return b.getValue().compareTo(a.getValue());
            }
        });
        JSONArray arr = new JSONArray();
        for (int i = 0; i < entries.size() && i < limit; i++) {
            Map.Entry<String, Integer> e = entries.get(i);
            if (e.getKey() == null || e.getKey().trim().isEmpty()) continue;
            arr.put(new JSONObject().put("name", e.getKey()).put("count", e.getValue()));
        }
        return arr;
    }

    private void inc(Map<String, Integer> map, String key) {
        if (key == null || key.trim().isEmpty()) return;
        map.put(key, map.containsKey(key) ? map.get(key) + 1 : 1);
    }

    private JSONArray parseTags(Object value) throws Exception {
        JSONArray out = new JSONArray();
        if (value instanceof JSONArray) {
            JSONArray arr = (JSONArray) value;
            for (int i = 0; i < arr.length(); i++) addTag(out, arr.optString(i, ""));
        } else if (value != null && value != JSONObject.NULL) {
            String[] parts = String.valueOf(value).split(",");
            for (String p : parts) addTag(out, p);
        }
        return out;
    }

    private void addTag(JSONArray arr, String value) {
        String tag = value == null ? "" : value.trim();
        if (tag.isEmpty()) return;
        for (int i = 0; i < arr.length(); i++) {
            if (tag.equalsIgnoreCase(arr.optString(i))) return;
        }
        arr.put(tag);
    }

    private JSONArray copyArray(JSONArray arr) throws Exception {
        JSONArray copy = new JSONArray();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) copy.put(arr.opt(i));
        }
        return copy;
    }

    private boolean contains(String value, String needleLower) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needleLower);
    }

    private boolean hasTag(JSONArray tags, String tagLower) {
        if (tags == null) return false;
        for (int i = 0; i < tags.length(); i++) {
            if (tags.optString(i, "").toLowerCase(Locale.ROOT).contains(tagLower)) return true;
        }
        return false;
    }

    private String tagsText(JSONArray tags) {
        if (tags == null || tags.length() == 0) return "";
        List<String> values = new ArrayList<>();
        for (int i = 0; i < tags.length(); i++) values.add(tags.optString(i));
        return join(values, ", ");
    }

    private String factLine(JSONObject fact) {
        return fact.optString("subject") + " " + fact.optString("relation") + " " + fact.optString("object");
    }

    private String joinTail(String[] parts, int start) {
        List<String> values = new ArrayList<>();
        for (int i = start; i < parts.length; i++) values.add(parts[i].trim());
        return join(values, " -> ");
    }

    private String join(List<String> values, String separator) {
        StringBuilder sb = new StringBuilder();
        for (String v : values) {
            if (sb.length() > 0) sb.append(separator);
            sb.append(v);
        }
        return sb.toString();
    }

    private String newId() {
        return "f_" + Long.toString(System.currentTimeMillis(), 36)
                + "_" + Integer.toHexString((int) (Math.random() * 65535));
    }

    private String firstNonEmpty(String a, String b) {
        return a != null && !a.trim().isEmpty() ? a.trim() : b;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private byte[] readAll(FileInputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private String ok(JSONObject out, String text, String html) throws Exception {
        return new JSONObject()
                .put("success", true)
                .put("output", out.toString())
                .put("_displayText", text)
                .put("_displayHtml", html)
                .toString();
    }

    private String error(String message) throws Exception {
        return new JSONObject().put("success", false).put("error", message).toString();
    }

    private String emptyJson(String value) {
        return value == null || value.trim().isEmpty() ? "{}" : value;
    }
}
