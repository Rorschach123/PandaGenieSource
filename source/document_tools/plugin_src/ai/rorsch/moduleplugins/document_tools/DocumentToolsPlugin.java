package ai.rorsch.moduleplugins.document_tools;

import android.content.Context;
import android.os.Environment;

import ai.rorsch.pandagenie.module.runtime.HtmlOutputHelper;
import ai.rorsch.pandagenie.module.runtime.ModulePlugin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Document-level file tools for PandaGenie.
 *
 * This module complements filemanager/archive: filemanager handles filesystem
 * operations, archive handles compression, and this plugin handles document
 * content extraction, query, simple editing, creation, and text/html/markdown/json
 * conversion.
 */
public class DocumentToolsPlugin implements ModulePlugin {
    private static final int DEFAULT_MAX_CHARS = 12000;
    private static final int INTERNAL_MAX_CHARS = 220000;
    private static final int MAX_FILE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_DISPLAY_CHARS = 2600;
    private static final SimpleDateFormat SDF = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

    private static final Set<String> TEXT_EXTENSIONS = new HashSet<>(Arrays.asList(
            "txt", "md", "markdown", "csv", "json", "xml", "html", "htm", "log",
            "yaml", "yml", "ini", "properties", "java", "kt", "js", "ts", "css",
            "py", "sh", "bat", "gradle", "sql"
    ));
    private static final Set<String> OFFICE_EXTENSIONS = new HashSet<>(Arrays.asList("docx", "xlsx", "pptx"));
    private static final Set<String> CREATE_FORMATS = new HashSet<>(Arrays.asList("txt", "md", "html", "json", "csv"));
    private static final Set<String> CONVERT_FORMATS = new HashSet<>(Arrays.asList("txt", "md", "html", "json"));
    private static final Set<String> TABLE_FORMATS = new HashSet<>(Arrays.asList("xlsx", "csv", "md", "html", "json"));
    private static final List<String> SUPPORTED_ACTIONS = Arrays.asList(
            "getDocumentInfo", "extractText", "queryDocument", "replaceText", "appendText",
            "createDocument", "createTable", "importTable", "deleteDocument", "convertDocument",
            "listSupportedFormats"
    );
    private static final int DEFAULT_TABLE_MAX_ROWS = 80;
    private static final int MAX_TABLE_ROWS = 5000;
    private static final int MAX_TABLE_COLUMNS = 80;

    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        try {
            JSONObject params = new JSONObject(emptyJson(paramsJson));
            String normalizedAction = normalizeAction(action, params);
            switch (normalizedAction) {
                case "getDocumentInfo":
                    return handleGetDocumentInfo(params);
                case "extractText":
                    return handleExtractText(params);
                case "queryDocument":
                    return handleQueryDocument(params);
                case "replaceText":
                    return handleReplaceText(params);
                case "appendText":
                    return handleAppendText(params);
                case "createDocument":
                    return handleCreateDocument(params);
                case "createTable":
                case "generateTable":
                case "createSpreadsheet":
                case "generateSpreadsheet":
                    return handleCreateTable(params);
                case "importTable":
                case "readTable":
                case "importSpreadsheet":
                case "readSpreadsheet":
                    return handleImportTable(params);
                case "deleteDocument":
                    return handleDeleteDocument(params);
                case "convertDocument":
                    return handleConvertDocument(params);
                case "listSupportedFormats":
                    return handleListSupportedFormats();
                default:
                    return error("Unsupported action: " + action + "; supported actions: " + String.join(", ", SUPPORTED_ACTIONS));
            }
        } catch (Exception e) {
            String msg = e.getMessage();
            return error(msg != null && !msg.isEmpty() ? msg : e.getClass().getSimpleName());
        }
    }

    private String handleGetDocumentInfo(JSONObject params) throws Exception {
        File file = requireFile(params, false);
        String ext = extension(file.getName());
        JSONObject out = new JSONObject()
                .put("path", file.getAbsolutePath())
                .put("name", file.getName())
                .put("extension", ext)
                .put("sizeBytes", file.exists() ? file.length() : 0)
                .put("size", formatSize(file.exists() ? file.length() : 0))
                .put("exists", file.exists())
                .put("isFile", file.isFile())
                .put("readable", file.canRead())
                .put("writable", file.canWrite())
                .put("modifiedAt", file.exists() ? SDF.format(new Date(file.lastModified())) : "")
                .put("extractable", isExtractableExtension(ext) || looksTextual(file))
                .put("editable", isTextExtension(ext) || OFFICE_EXTENSIONS.contains(ext))
                .put("convertible", isExtractableExtension(ext) || looksTextual(file))
                .put("mimeType", guessMimeType(ext));
        return ok(out, infoDisplay(out), infoHtml(out));
    }

    private String handleExtractText(JSONObject params) throws Exception {
        File file = requireFile(params, true);
        int maxChars = clamp(params.optInt("maxChars", DEFAULT_MAX_CHARS), 500, INTERNAL_MAX_CHARS);
        DocumentText doc = extractDocumentText(file, maxChars);
        JSONObject out = new JSONObject()
                .put("path", file.getAbsolutePath())
                .put("name", file.getName())
                .put("format", doc.format)
                .put("text", doc.text)
                .put("chars", doc.text.length())
                .put("truncated", doc.truncated);
        if (!doc.warning.isEmpty()) out.put("warning", doc.warning);
        JSONArray rich = new JSONArray().put(richCode(doc.text, "text"));
        return ok(out, extractDisplay(file, doc), extractHtml(file, doc), rich);
    }

    private String handleQueryDocument(JSONObject params) throws Exception {
        File file = requireFile(params, true);
        String query = firstString(params, "query", "keyword", "text").trim();
        int maxResults = clamp(params.optInt("maxResults", 8), 1, 30);
        int contextChars = clamp(params.optInt("contextChars", 120), 30, 500);
        DocumentText doc = extractDocumentText(file, INTERNAL_MAX_CHARS);
        JSONArray matches = new JSONArray();
        if (query.isEmpty()) {
            List<String> chunks = firstParagraphs(doc.text, maxResults);
            for (int i = 0; i < chunks.size(); i++) {
                matches.put(new JSONObject()
                        .put("index", i + 1)
                        .put("excerpt", chunks.get(i)));
            }
        } else {
            String lower = doc.text.toLowerCase(Locale.ROOT);
            String needle = query.toLowerCase(Locale.ROOT);
            int from = 0;
            int idx;
            int count = 0;
            while ((idx = lower.indexOf(needle, from)) >= 0 && count < maxResults) {
                int start = Math.max(0, idx - contextChars);
                int end = Math.min(doc.text.length(), idx + query.length() + contextChars);
                String excerpt = normalizeWhitespace(doc.text.substring(start, end));
                matches.put(new JSONObject()
                        .put("index", count + 1)
                        .put("offset", idx)
                        .put("excerpt", excerpt));
                count++;
                from = idx + Math.max(1, query.length());
            }
        }
        JSONObject out = new JSONObject()
                .put("path", file.getAbsolutePath())
                .put("query", query)
                .put("matchCount", matches.length())
                .put("matches", matches);
        return ok(out, queryDisplay(file, query, matches), queryHtml(file, query, matches));
    }

    private String handleReplaceText(JSONObject params) throws Exception {
        File file = requireFile(params, true);
        String target = firstString(params, "target", "from", "oldText");
        String replacement = firstString(params, "replacement", "to", "newText");
        boolean regex = parseBoolean(params.opt("regex"), false);
        boolean backup = parseBoolean(params.opt("backup"), true);
        String outputRaw = firstString(params, "outputPath", "output", "dst").trim();
        if (target.isEmpty()) return error("target is empty");
        String ext = extension(file.getName());
        String outputPath = outputRaw.isEmpty() ? file.getAbsolutePath() : resolveStoragePath(outputRaw);

        EditResult result;
        if (OFFICE_EXTENSIONS.contains(ext)) {
            if (regex) return error("regex replacement is not supported for Office XML documents");
            result = replaceInOfficeDocument(file, target, replacement, outputPath, backup);
        } else if (isTextExtension(ext) || looksTextual(file)) {
            result = replaceInTextDocument(file, target, replacement, outputPath, regex, backup);
        } else {
            return error("Unsupported editable document type: ." + ext);
        }

        JSONObject out = new JSONObject()
                .put("path", file.getAbsolutePath())
                .put("outputPath", result.outputPath)
                .put("changed", result.changed)
                .put("occurrences", result.occurrences)
                .put("backupPath", result.backupPath);
        JSONArray rich = new JSONArray().put(richFile(result.outputPath, new File(result.outputPath).getName(), guessMimeType(extension(result.outputPath))));
        return ok(out, editDisplay(result), editHtml(result), rich);
    }

    private String handleAppendText(JSONObject params) throws Exception {
        File file = requireFile(params, true);
        String text = firstString(params, "text", "content", "append");
        String position = firstString(params, "position", "where").toLowerCase(Locale.ROOT);
        String outputRaw = firstString(params, "outputPath", "output", "dst").trim();
        if (text.isEmpty()) return error("text is empty");
        String ext = extension(file.getName());
        if (!isTextExtension(ext) && !looksTextual(file)) {
            return error("appendText only supports text-like documents");
        }
        String original = readText(file, MAX_FILE_BYTES);
        String merged;
        if ("start".equals(position) || "beginning".equals(position) || "prefix".equals(position)) {
            merged = text + lineJoiner(text, original) + original;
        } else {
            merged = original + lineJoiner(original, text) + text;
        }
        String outputPath = outputRaw.isEmpty() ? file.getAbsolutePath() : resolveStoragePath(outputRaw);
        boolean same = samePath(file, new File(outputPath));
        String backupPath = "";
        if (same) backupPath = backupFile(file);
        writeUtf8(new File(outputPath), merged);
        EditResult result = new EditResult(true, 1, outputPath, backupPath);
        JSONObject out = new JSONObject()
                .put("path", file.getAbsolutePath())
                .put("outputPath", outputPath)
                .put("changed", true)
                .put("backupPath", backupPath);
        JSONArray rich = new JSONArray().put(richFile(outputPath, new File(outputPath).getName(), guessMimeType(extension(outputPath))));
        return ok(out, editDisplay(result), editHtml(result), rich);
    }

    private String handleCreateDocument(JSONObject params) throws Exception {
        String rawPath = firstString(params, "path", "filePath", "outputPath");
        String content = firstString(params, "content", "text", "body");
        String format = firstString(params, "format", "type").trim().toLowerCase(Locale.ROOT);
        String title = firstString(params, "title", "name").trim();
        if (format.isEmpty()) format = extension(rawPath);
        if (format.isEmpty()) format = "txt";
        if (!CREATE_FORMATS.contains(format)) return error("Unsupported create format: " + format);
        String path = rawPath.trim().isEmpty()
                ? defaultCreatedPath(format, title)
                : resolveStoragePath(rawPath);
        File outFile = new File(path);
        ensureParent(outFile);
        String finalContent = createDocumentContent(format, title, content);
        writeUtf8(outFile, finalContent);
        JSONObject out = new JSONObject()
                .put("path", outFile.getAbsolutePath())
                .put("format", format)
                .put("sizeBytes", outFile.length());
        JSONArray rich = new JSONArray().put(richFile(outFile.getAbsolutePath(), outFile.getName(), guessMimeType(format)));
        return ok(out, fileOutputDisplay(outFile, isZh() ? "已创建文档" : "Document created"),
                fileOutputHtml(outFile, isZh() ? "已创建文档" : "Document created"), rich);
    }

    private String handleCreateTable(JSONObject params) throws Exception {
        String rawFormat = firstString(params, "format", "type", "targetFormat");
        String rawPath = firstString(params, "path", "filePath", "outputPath");
        String pathExt = extension(cleanPath(rawPath));
        String format = normalizeFormat(rawFormat, pathExt.isEmpty() ? "xlsx" : pathExt);
        String path = tableOutputPath(rawPath, format);
        if (rawFormat.trim().isEmpty()) format = normalizeFormat(extension(path), format);
        if (!TABLE_FORMATS.contains(format)) return error("Unsupported table format: " + format);

        TableData table = tableFromParams(params);
        if (table.headers.isEmpty() && table.rows.isEmpty()) return error("table data is empty");

        File outFile = new File(path);
        ensureParent(outFile);
        if ("xlsx".equals(format)) {
            writeXlsxTable(outFile, table, firstString(params, "title", "name"));
        } else {
            writeUtf8(outFile, tableContent(format, table, firstString(params, "title", "name")));
        }

        JSONObject out = tableJson(table)
                .put("path", outFile.getAbsolutePath())
                .put("format", format)
                .put("sizeBytes", outFile.length());
        JSONArray rich = new JSONArray().put(richFile(outFile.getAbsolutePath(), outFile.getName(), guessMimeType(format)));
        return ok(out, tableCreateDisplay(outFile, table),
                tableHtml(isZh() ? "表格已生成" : "Table created", outFile, table), rich);
    }

    private String handleImportTable(JSONObject params) throws Exception {
        File file = requireFile(params, true);
        String ext = extension(file.getName());
        int maxRows = clamp(params.optInt("maxRows", DEFAULT_TABLE_MAX_ROWS), 1, MAX_TABLE_ROWS);
        boolean hasHeader = parseBoolean(params.opt("hasHeader"), true);
        List<List<String>> rawRows;
        if ("csv".equals(ext) || "txt".equals(ext)) {
            rawRows = parseDelimitedRows(readText(file, MAX_FILE_BYTES), maxRows + 1);
        } else if ("xlsx".equals(ext)) {
            rawRows = readXlsxRows(file, maxRows + 1);
        } else {
            return error("importTable only supports csv and xlsx");
        }
        TableData table = tableFromRows(rawRows, hasHeader);
        JSONObject out = tableJson(table)
                .put("path", file.getAbsolutePath())
                .put("format", ext)
                .put("truncated", rawRows.size() > maxRows);
        return ok(out, tableImportDisplay(file, table),
                tableHtml(isZh() ? "表格已导入" : "Table imported", file, table));
    }

    private String handleDeleteDocument(JSONObject params) throws Exception {
        File file = requireFile(params, true);
        String path = file.getAbsolutePath();
        boolean deleted = file.delete();
        if (!deleted) return error("deleteDocument failed: " + path);
        JSONObject out = new JSONObject().put("path", path).put("deleted", true);
        return ok(out, (isZh() ? "已删除文档: " : "Document deleted: ") + displayPath(path),
                HtmlOutputHelper.card("DEL", isZh() ? "文档已删除" : "Document deleted",
                        HtmlOutputHelper.keyValue(new String[][]{{isZh() ? "路径" : "Path", path}})));
    }

    private String handleConvertDocument(JSONObject params) throws Exception {
        File input = requireFile(params, true);
        String format = firstString(params, "format", "to", "targetFormat").trim().toLowerCase(Locale.ROOT);
        if (format.startsWith(".")) format = format.substring(1);
        if (format.isEmpty()) format = "txt";
        if (!CONVERT_FORMATS.contains(format)) return error("Unsupported target format: " + format);
        String outputRaw = firstString(params, "outputPath", "output", "dst").trim();
        String outputPath = outputRaw.isEmpty() ? defaultConvertedPath(input, format) : resolveStoragePath(outputRaw);
        DocumentText doc = extractDocumentText(input, INTERNAL_MAX_CHARS);
        File outFile = new File(outputPath);
        ensureParent(outFile);
        writeUtf8(outFile, convertContent(format, input, doc.text));
        JSONObject out = new JSONObject()
                .put("inputPath", input.getAbsolutePath())
                .put("outputPath", outFile.getAbsolutePath())
                .put("format", format)
                .put("sizeBytes", outFile.length())
                .put("truncated", doc.truncated);
        JSONArray rich = new JSONArray().put(richFile(outFile.getAbsolutePath(), outFile.getName(), guessMimeType(format)));
        return ok(out, fileOutputDisplay(outFile, isZh() ? "转换完成" : "Converted"),
                fileOutputHtml(outFile, isZh() ? "转换完成" : "Converted"), rich);
    }

    private String handleListSupportedFormats() throws Exception {
        JSONObject out = new JSONObject()
                .put("extract", new JSONArray(Arrays.asList("txt", "md", "csv", "json", "xml", "html", "docx", "xlsx", "pptx", "pdf")))
                .put("edit", new JSONArray(Arrays.asList("txt", "md", "csv", "json", "xml", "html", "docx", "xlsx", "pptx")))
                .put("create", new JSONArray(CREATE_FORMATS))
                .put("createTable", new JSONArray(TABLE_FORMATS))
                .put("importTable", new JSONArray(Arrays.asList("csv", "xlsx")))
                .put("convertTo", new JSONArray(CONVERT_FORMATS));
        return ok(out, supportedDisplay(), supportedHtml());
    }

    private DocumentText extractDocumentText(File file, int maxChars) throws Exception {
        String ext = extension(file.getName());
        String text;
        String warning = "";
        if (isTextExtension(ext)) {
            text = readText(file, MAX_FILE_BYTES);
        } else if ("docx".equals(ext)) {
            text = extractDocx(file);
        } else if ("xlsx".equals(ext)) {
            text = extractXlsx(file);
        } else if ("pptx".equals(ext)) {
            text = extractPptx(file);
        } else if ("pdf".equals(ext)) {
            text = extractPdfText(file);
            warning = isZh()
                    ? "PDF 文本提取为本地基础解析，扫描版 PDF 请配合 OCR 模块。"
                    : "PDF extraction is best-effort. Scanned PDFs need OCR.";
        } else {
            String maybeText = readText(file, MAX_FILE_BYTES);
            if (!isMostlyReadable(maybeText)) {
                throw new IllegalArgumentException("Unsupported document type: ." + ext);
            }
            text = maybeText;
            warning = isZh() ? "未知扩展名，已按纯文本尝试读取。" : "Unknown extension, read as plain text.";
        }
        text = normalizeText(text);
        boolean truncated = text.length() > maxChars;
        if (truncated) text = text.substring(0, maxChars);
        return new DocumentText(text, ext.isEmpty() ? "text" : ext, truncated, warning);
    }

    private static String extractDocx(File file) throws Exception {
        StringBuilder sb = new StringBuilder();
        readZipTextEntries(file, sb, new EntryMatcher() {
            @Override public boolean matches(String name) {
                return name.equals("word/document.xml")
                        || name.startsWith("word/header")
                        || name.startsWith("word/footer");
            }
        });
        return sb.toString();
    }

    private static String extractXlsx(File file) throws Exception {
        List<String> shared = new ArrayList<>();
        StringBuilder sheetValues = new StringBuilder();
        ZipInputStream zis = new ZipInputStream(new FileInputStream(file));
        try {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    String name = entry.getName();
                    byte[] bytes = readAll(zis);
                    String xml = new String(bytes, StandardCharsets.UTF_8);
                    if ("xl/sharedStrings.xml".equals(name)) {
                        shared.addAll(extractSharedStrings(xml));
                    } else if (name.startsWith("xl/worksheets/") && name.endsWith(".xml")) {
                        appendWorksheetValues(xml, shared, sheetValues);
                    }
                }
                zis.closeEntry();
            }
        } finally {
            zis.close();
        }
        StringBuilder out = new StringBuilder();
        if (!shared.isEmpty()) {
            for (String s : shared) {
                if (!s.trim().isEmpty()) out.append(s.trim()).append('\n');
            }
        }
        if (sheetValues.length() > 0) out.append(sheetValues);
        return out.toString();
    }

    private static String extractPptx(File file) throws Exception {
        final Pattern slidePattern = Pattern.compile("ppt/slides/slide\\d+\\.xml");
        StringBuilder sb = new StringBuilder();
        readZipTextEntries(file, sb, new EntryMatcher() {
            @Override public boolean matches(String name) {
                return slidePattern.matcher(name).matches();
            }
        });
        return sb.toString();
    }

    private static void readZipTextEntries(File file, StringBuilder out, EntryMatcher matcher) throws Exception {
        ZipInputStream zis = new ZipInputStream(new FileInputStream(file));
        try {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory() && matcher.matches(entry.getName())) {
                    String xml = new String(readAll(zis), StandardCharsets.UTF_8);
                    String text = xmlToText(xml);
                    if (!text.trim().isEmpty()) out.append(text.trim()).append("\n\n");
                }
                zis.closeEntry();
            }
        } finally {
            zis.close();
        }
    }

    private static List<String> extractSharedStrings(String xml) {
        List<String> strings = new ArrayList<>();
        Matcher m = Pattern.compile("<si[\\s\\S]*?</si>").matcher(xml);
        while (m.find()) strings.add(xmlToText(m.group()));
        return strings;
    }

    private static void appendWorksheetValues(String xml, List<String> shared, StringBuilder out) {
        List<List<String>> rows = parseXlsxWorksheetRows(xml, shared, Integer.MAX_VALUE);
        for (List<String> row : rows) {
            if (!row.isEmpty()) out.append(join(row, "\t")).append('\n');
        }
    }

    private static String extractPdfText(File file) throws Exception {
        byte[] bytes = readBytes(file, Math.min((int) Math.min(file.length(), MAX_FILE_BYTES), MAX_FILE_BYTES));
        String raw = new String(bytes, Charset.forName("ISO-8859-1"));
        StringBuilder sb = new StringBuilder();
        Matcher literal = Pattern.compile("\\((?:\\\\.|[^\\\\)]){2,800}\\)").matcher(raw);
        int count = 0;
        while (literal.find() && count < 3000) {
            String token = literal.group();
            String text = decodePdfLiteral(token.substring(1, token.length() - 1));
            if (isMostlyReadable(text) && text.trim().length() >= 2) {
                sb.append(text.trim()).append('\n');
                count++;
            }
        }
        String out = normalizeText(sb.toString());
        if (out.length() < 12) {
            return "";
        }
        return out;
    }

    private EditResult replaceInTextDocument(File file, String target, String replacement, String outputPath,
                                             boolean regex, boolean backup) throws Exception {
        String original = readText(file, MAX_FILE_BYTES);
        ReplaceResult rr = replaceAll(original, target, replacement, regex);
        String backupPath = "";
        File output = new File(outputPath);
        ensureParent(output);
        if (samePath(file, output) && backup) backupPath = backupFile(file);
        writeUtf8(output, rr.text);
        return new EditResult(rr.occurrences > 0, rr.occurrences, output.getAbsolutePath(), backupPath);
    }

    private EditResult replaceInOfficeDocument(File file, String target, String replacement, String outputPath,
                                               boolean backup) throws Exception {
        File output = new File(outputPath);
        boolean same = samePath(file, output);
        File writeTarget = same ? new File(file.getParentFile(), file.getName() + ".pg_edit_tmp") : output;
        ensureParent(writeTarget);
        int occurrences = 0;
        ZipInputStream zis = new ZipInputStream(new FileInputStream(file));
        ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(writeTarget));
        try {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                ZipEntry outEntry = new ZipEntry(entry.getName());
                if (entry.getTime() > 0) outEntry.setTime(entry.getTime());
                zos.putNextEntry(outEntry);
                if (!entry.isDirectory()) {
                    byte[] bytes = readAll(zis);
                    if (isOfficeXmlEntry(file.getName(), entry.getName())) {
                        String xml = new String(bytes, StandardCharsets.UTF_8);
                        String xmlTarget = escapeXmlText(target);
                        String xmlReplacement = escapeXmlText(replacement);
                        ReplaceResult rr = replaceAll(xml, xmlTarget, xmlReplacement, false);
                        occurrences += rr.occurrences;
                        bytes = rr.text.getBytes(StandardCharsets.UTF_8);
                    }
                    zos.write(bytes);
                }
                zos.closeEntry();
                zis.closeEntry();
            }
        } finally {
            try { zis.close(); } catch (Exception ignored) {}
            try { zos.close(); } catch (Exception ignored) {}
        }
        String backupPath = "";
        if (same) {
            if (backup) backupPath = backupFile(file);
            if (!file.delete()) {
                writeTarget.delete();
                throw new IOException("Cannot replace original file: " + file.getAbsolutePath());
            }
            if (!writeTarget.renameTo(file)) {
                throw new IOException("Cannot move edited document to: " + file.getAbsolutePath());
            }
            output = file;
        }
        return new EditResult(occurrences > 0, occurrences, output.getAbsolutePath(), backupPath);
    }

    private static boolean isOfficeXmlEntry(String fileName, String entryName) {
        String ext = extension(fileName);
        if ("docx".equals(ext)) {
            return entryName.equals("word/document.xml")
                    || entryName.startsWith("word/header")
                    || entryName.startsWith("word/footer");
        }
        if ("xlsx".equals(ext)) {
            return entryName.equals("xl/sharedStrings.xml")
                    || (entryName.startsWith("xl/worksheets/") && entryName.endsWith(".xml"));
        }
        if ("pptx".equals(ext)) {
            return entryName.startsWith("ppt/slides/") && entryName.endsWith(".xml");
        }
        return false;
    }

    private static ReplaceResult replaceAll(String input, String target, String replacement, boolean regex) {
        if (regex) {
            Pattern p = Pattern.compile(target);
            Matcher m = p.matcher(input);
            int count = 0;
            while (m.find()) count++;
            String replaced = p.matcher(input).replaceAll(replacement);
            return new ReplaceResult(replaced, count);
        }
        int count = 0;
        int idx = 0;
        while ((idx = input.indexOf(target, idx)) >= 0) {
            count++;
            idx += Math.max(1, target.length());
        }
        return new ReplaceResult(input.replace(target, replacement), count);
    }

    private static File requireFile(JSONObject params, boolean mustExist) {
        String path = resolveStoragePath(firstString(params, "filePath", "path", "inputPath", "input"));
        if (path.isEmpty()) throw new IllegalArgumentException("filePath is empty");
        File file = new File(path);
        if (mustExist) {
            if (!file.isFile()) throw new IllegalArgumentException("File not found: " + path);
            if (!file.canRead()) throw new IllegalArgumentException("File not readable: " + path);
        }
        return file;
    }

    private static String readText(File file, int maxBytes) throws Exception {
        byte[] bytes = readBytes(file, (int) Math.min(Math.max(1, file.length()), maxBytes));
        if (bytes.length >= 3
                && (bytes[0] & 0xff) == 0xef
                && (bytes[1] & 0xff) == 0xbb
                && (bytes[2] & 0xff) == 0xbf) {
            byte[] trimmed = new byte[bytes.length - 3];
            System.arraycopy(bytes, 3, trimmed, 0, trimmed.length);
            bytes = trimmed;
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(File file, int maxBytes) throws Exception {
        FileInputStream fis = new FileInputStream(file);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int remaining = maxBytes;
            while (remaining > 0) {
                int n = fis.read(buffer, 0, Math.min(buffer.length, remaining));
                if (n < 0) break;
                bos.write(buffer, 0, n);
                remaining -= n;
            }
            return bos.toByteArray();
        } finally {
            fis.close();
        }
    }

    private static byte[] readAll(ZipInputStream zis) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = zis.read(buffer)) >= 0) {
            if (n > 0) bos.write(buffer, 0, n);
        }
        return bos.toByteArray();
    }

    private static void writeUtf8(File file, String text) throws Exception {
        ensureParent(file);
        FileOutputStream fos = new FileOutputStream(file);
        try {
            fos.write(text.getBytes(StandardCharsets.UTF_8));
        } finally {
            fos.close();
        }
    }

    private static String backupFile(File file) throws Exception {
        String backupPath = file.getAbsolutePath() + ".bak." + System.currentTimeMillis();
        copyFile(file, new File(backupPath));
        return backupPath;
    }

    private static void copyFile(File src, File dst) throws Exception {
        ensureParent(dst);
        FileInputStream fis = new FileInputStream(src);
        FileOutputStream fos = new FileOutputStream(dst);
        try {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = fis.read(buffer)) >= 0) {
                if (n > 0) fos.write(buffer, 0, n);
            }
        } finally {
            try { fis.close(); } catch (Exception ignored) {}
            try { fos.close(); } catch (Exception ignored) {}
        }
    }

    private static void ensureParent(File file) {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
    }

    private static String createDocumentContent(String format, String title, String content) throws Exception {
        if ("html".equals(format)) {
            if (content.toLowerCase(Locale.ROOT).contains("<html")) return content;
            String h = title.isEmpty() ? "Document" : title;
            return "<!doctype html><html><head><meta charset=\"utf-8\"><title>" + escHtml(h)
                    + "</title></head><body><pre>" + escHtml(content) + "</pre></body></html>";
        }
        if ("json".equals(format)) {
            String trimmed = content.trim();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) return content;
            return new JSONObject().put("title", title).put("content", content).toString(2);
        }
        if ("md".equals(format) && !title.isEmpty() && !content.trim().startsWith("#")) {
            return "# " + title + "\n\n" + content;
        }
        return content;
    }

    private static String convertContent(String format, File input, String text) throws Exception {
        if ("html".equals(format)) {
            return "<!doctype html><html><head><meta charset=\"utf-8\"><title>" + escHtml(input.getName())
                    + "</title><style>body{font-family:sans-serif;line-height:1.6;padding:16px;}"
                    + "pre{white-space:pre-wrap;word-break:break-word;}</style></head><body><pre>"
                    + escHtml(text) + "</pre></body></html>";
        }
        if ("json".equals(format)) {
            return new JSONObject()
                    .put("source", input.getAbsolutePath())
                    .put("name", input.getName())
                    .put("text", text)
                    .toString(2);
        }
        if ("md".equals(format)) {
            return "# " + input.getName() + "\n\n" + text;
        }
        return text;
    }

    private static String normalizeFormat(String raw, String fallback) {
        String format = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (format.startsWith(".")) format = format.substring(1);
        return format.isEmpty() ? fallback : format;
    }

    private static String tableOutputPath(String rawPath, String format) {
        String normalizedFormat = normalizeFormat(format, "xlsx");
        String raw = cleanPath(rawPath);
        if (raw.isEmpty()) return defaultTablePath(normalizedFormat);
        String path = resolveStoragePath(raw);
        if (path.endsWith("/") || path.endsWith("\\")) {
            return path + defaultTableFileName(normalizedFormat);
        }
        if (extension(path).isEmpty()) return path + "." + normalizedFormat;
        return path;
    }

    private static String defaultTablePath(String format) {
        File dir = new File(Environment.getExternalStorageDirectory(), "PandaGenie/documents");
        return new File(dir, defaultTableFileName(format)).getAbsolutePath();
    }

    private static String defaultCreatedPath(String format, String title) {
        File dir = new File(Environment.getExternalStorageDirectory(), "PandaGenie/documents");
        String base = sanitizeFileBase(title);
        if (base.isEmpty()) {
            base = "document_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        }
        return new File(dir, base + "." + normalizeFormat(format, "txt")).getAbsolutePath();
    }

    private static String sanitizeFileBase(String title) {
        if (title == null) return "";
        String clean = title.trim()
                .replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]+", "_")
                .replaceAll("\\s+", "_")
                .replaceAll("^_+|_+$", "");
        if (clean.length() > 40) clean = clean.substring(0, 40);
        return clean;
    }

    private static String defaultTableFileName(String format) {
        return "table_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + "." + normalizeFormat(format, "xlsx");
    }

    private static TableData tableFromParams(JSONObject params) throws Exception {
        List<String> headers = parseStringList(firstValue(params, "headers", "columns", "tableHeaders"));
        TableData table = null;

        Object rowInput = firstValue(params, "rows", "records", "values", "tableRows");
        if (rowInput == null && params.has("data")) {
            rowInput = firstValue(params, "data");
        }
        if (rowInput != null) {
            table = tableFromStructuredValue(rowInput, headers);
            headers = table.headers;
        }

        Object structuredInput = firstValue(params, "jsonData", "sourceData", "input", "value", "json");
        if (isEmptyTable(table) && structuredInput != null) {
            table = tableFromStructuredValue(structuredInput, headers);
            headers = table.headers;
        }

        String content = firstString(params, "content", "text", "csv", "markdown", "body");
        if (isEmptyTable(table) && !content.trim().isEmpty()) {
            Object parsedJson = parseJsonLiteral(content);
            if (parsedJson != null) {
                table = tableFromStructuredValue(parsedJson, headers);
            } else {
                List<List<String>> parsed = looksMarkdownTable(content)
                        ? parseMarkdownRows(content, MAX_TABLE_ROWS + 1)
                        : parseDelimitedRows(content, MAX_TABLE_ROWS + 1);
                boolean hasHeader = parseBoolean(params.opt("hasHeader"), headers.isEmpty());
                TableData parsedTable = tableFromRows(parsed, hasHeader);
                if (!headers.isEmpty()) return normalizeTable(headers, parsedTable.rows);
                return parsedTable;
            }
        }
        if (table != null) return normalizeTable(table.headers, table.rows);
        return normalizeTable(headers, new ArrayList<List<String>>());
    }

    private static Object firstValue(JSONObject params, String... keys) {
        for (String key : keys) {
            if (!params.has(key)) continue;
            Object value = params.opt(key);
            if (value == null || JSONObject.NULL.equals(value)) continue;
            if (value instanceof String && ((String) value).trim().isEmpty()) continue;
            return value;
        }
        return null;
    }

    private static List<String> parseStringList(Object value) {
        List<String> out = new ArrayList<>();
        if (value == null || JSONObject.NULL.equals(value)) return out;
        if (value instanceof JSONArray) {
            JSONArray arr = (JSONArray) value;
            for (int i = 0; i < arr.length() && out.size() < MAX_TABLE_COLUMNS; i++) {
                String cell = cellToString(arr.opt(i)).trim();
                if (!cell.isEmpty()) out.add(cell);
            }
            return out;
        }
        if (value instanceof JSONObject) {
            JSONArray names = ((JSONObject) value).names();
            if (names != null) {
                for (int i = 0; i < names.length() && out.size() < MAX_TABLE_COLUMNS; i++) {
                    out.add(names.optString(i));
                }
            }
            return out;
        }
        String text = cellToString(value).trim();
        if (text.isEmpty()) return out;
        Object parsedJson = parseJsonLiteral(text);
        if (parsedJson != null) return parseStringList(parsedJson);
        String[] parts = text.contains("\n") ? text.split("\\r?\\n") : text.split("[,，\\t|]");
        for (String part : parts) {
            String item = part.trim();
            if (!item.isEmpty()) out.add(item);
            if (out.size() >= MAX_TABLE_COLUMNS) break;
        }
        return out;
    }

    private static Object parseJsonLiteral(String text) {
        if (text == null) return null;
        String trimmed = text.trim();
        if (trimmed.length() < 2) return null;
        Object parsed = tryParseJsonLiteral(trimmed);
        if (parsed != null) return parsed;
        String unquoted = unquoteJsonString(trimmed);
        if (unquoted != null && !unquoted.equals(trimmed)) {
            parsed = parseJsonLiteral(unquoted);
            if (parsed != null) return parsed;
        }
        if (trimmed.contains("\\\"")) {
            String unescaped = trimmed.replace("\\\"", "\"").replace("\\/", "/");
            parsed = tryParseJsonLiteral(unescaped);
            if (parsed != null) return parsed;
        }
        return null;
    }

    private static Object tryParseJsonLiteral(String text) {
        try {
            if (text.startsWith("[") && text.endsWith("]")) return new JSONArray(text);
            if (text.startsWith("{") && text.endsWith("}")) return new JSONObject(text);
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String unquoteJsonString(String text) {
        if (text == null || text.length() < 2) return null;
        char first = text.charAt(0);
        char last = text.charAt(text.length() - 1);
        if (first == '\'' && last == '\'') {
            return text.substring(1, text.length() - 1);
        }
        if (first != '"' || last != '"') return null;
        try {
            JSONArray wrapper = new JSONArray("[" + text + "]");
            return wrapper.optString(0, null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Object coerceJsonValue(Object value) {
        if (value == null || JSONObject.NULL.equals(value)) return null;
        if (value instanceof JSONObject || value instanceof JSONArray) return value;
        Object parsed = parseJsonLiteral(cellToString(value));
        return parsed != null ? parsed : value;
    }

    private static TableData tableFromStructuredValue(Object value, List<String> preferredHeaders) throws Exception {
        List<String> headers = new ArrayList<>(preferredHeaders == null ? Collections.<String>emptyList() : preferredHeaders);
        Object normalized = coerceJsonValue(value);
        if (normalized == null || JSONObject.NULL.equals(normalized)) {
            return normalizeTable(headers, new ArrayList<List<String>>());
        }
        if (normalized instanceof JSONObject) {
            return tableFromJsonObject((JSONObject) normalized, headers);
        }
        if (normalized instanceof JSONArray) {
            return tableFromJsonArray((JSONArray) normalized, headers);
        }

        String text = cellToString(normalized);
        if (text.trim().isEmpty()) return normalizeTable(headers, new ArrayList<List<String>>());
        List<List<String>> parsed = looksMarkdownTable(text)
                ? parseMarkdownRows(text, MAX_TABLE_ROWS + 1)
                : parseDelimitedRows(text, MAX_TABLE_ROWS + 1);
        if (!headers.isEmpty()) return normalizeTable(headers, parsed);
        return tableFromRows(parsed, true);
    }

    private static TableData tableFromJsonObject(JSONObject obj, List<String> preferredHeaders) throws Exception {
        List<String> headers = new ArrayList<>(preferredHeaders == null ? Collections.<String>emptyList() : preferredHeaders);
        if (headers.isEmpty()) {
            headers.addAll(parseStringList(firstValue(obj, "headers", "columns", "tableHeaders")));
        }

        Object nestedRows = firstValue(obj, "rows", "records", "values", "tableRows", "extensionRows");
        if (nestedRows == null && obj.has("data")) nestedRows = firstValue(obj, "data");
        if (nestedRows != null) {
            return tableFromStructuredValue(nestedRows, headers);
        }

        JSONArray preferredArray = firstArrayValue(
                obj,
                "tableRows", "rows", "records", "values", "data", "items",
                "extensionBreakdown", "files", "results", "list", "entries", "extensionRows"
        );
        if (preferredArray != null) {
            return tableFromJsonArray(preferredArray, headers);
        }

        JSONArray onlyArray = onlyUsefulArray(obj);
        if (onlyArray != null) {
            return tableFromJsonArray(onlyArray, headers);
        }

        if (!headers.isEmpty()) {
            List<List<String>> rows = new ArrayList<>();
            rows.add(parseObjectRow(obj, headers));
            return normalizeTable(headers, rows);
        }
        return tableFromKeyValueObject(obj, headers);
    }

    private static TableData tableFromJsonArray(JSONArray arr, List<String> preferredHeaders) {
        List<String> headers = new ArrayList<>(preferredHeaders == null ? Collections.<String>emptyList() : preferredHeaders);
        List<List<String>> rows = new ArrayList<>();
        if (arr == null || arr.length() == 0) return normalizeTable(headers, rows);

        boolean hasObject = false;
        boolean hasArray = false;
        boolean hasScalar = false;
        for (int i = 0; i < arr.length(); i++) {
            Object item = arr.opt(i);
            if (item instanceof JSONObject) hasObject = true;
            else if (item instanceof JSONArray) hasArray = true;
            else if (item != null && item != JSONObject.NULL) hasScalar = true;
        }

        if (hasObject && !hasArray && !hasScalar) {
            if (headers.isEmpty()) {
                for (int i = 0; i < arr.length(); i++) {
                    Object item = arr.opt(i);
                    if (item instanceof JSONObject) collectObjectKeys((JSONObject) item, headers);
                    if (headers.size() >= MAX_TABLE_COLUMNS) break;
                }
            }
            for (int i = 0; i < arr.length() && rows.size() < MAX_TABLE_ROWS; i++) {
                Object item = arr.opt(i);
                if (item instanceof JSONObject) rows.add(parseObjectRow((JSONObject) item, headers));
            }
            return normalizeTable(headers, rows);
        }

        if (hasArray && !hasObject && !hasScalar) {
            for (int i = 0; i < arr.length() && rows.size() < MAX_TABLE_ROWS; i++) {
                Object item = arr.opt(i);
                if (item instanceof JSONArray) rows.add(parseArrayRow((JSONArray) item));
            }
            return normalizeTable(headers, rows);
        }

        if (!hasObject && !hasArray) {
            if (headers.isEmpty()) headers.add(isZh() ? "值" : "Value");
            for (int i = 0; i < arr.length() && rows.size() < MAX_TABLE_ROWS; i++) {
                rows.add(new ArrayList<>(Collections.singletonList(cellToString(arr.opt(i)))));
            }
            return normalizeTable(headers, rows);
        }

        if (headers.isEmpty()) {
            headers.add(isZh() ? "序号" : "Index");
            headers.add(isZh() ? "值" : "Value");
        }
        for (int i = 0; i < arr.length() && rows.size() < MAX_TABLE_ROWS; i++) {
            rows.add(new ArrayList<>(Arrays.asList(String.valueOf(i + 1), cellToString(arr.opt(i)))));
        }
        return normalizeTable(headers, rows);
    }

    private static TableData tableFromKeyValueObject(JSONObject obj, List<String> preferredHeaders) {
        List<String> headers = new ArrayList<>(preferredHeaders == null ? Collections.<String>emptyList() : preferredHeaders);
        if (headers.isEmpty()) headers.addAll(keyValueHeaders());
        while (headers.size() < 2) headers.add(defaultHeader(headers.size()));
        List<List<String>> rows = new ArrayList<>();
        JSONArray names = obj.names();
        if (names != null) {
            for (int i = 0; i < names.length() && rows.size() < MAX_TABLE_ROWS; i++) {
                String key = names.optString(i);
                appendKeyValueRows(rows, key, obj.opt(key));
            }
        }
        return normalizeTable(new ArrayList<>(headers.subList(0, Math.min(headers.size(), 2))), rows);
    }

    private static List<String> keyValueHeaders() {
        return new ArrayList<>(Arrays.asList(isZh() ? "键" : "Key", isZh() ? "值" : "Value"));
    }

    private static void appendKeyValueRows(List<List<String>> rows, String key, Object value) {
        if (rows.size() >= MAX_TABLE_ROWS) return;
        if (value == null || JSONObject.NULL.equals(value)) {
            rows.add(new ArrayList<>(Arrays.asList(key, "")));
            return;
        }
        if (value instanceof JSONArray) {
            JSONArray arr = (JSONArray) value;
            if (arr.length() == 0) {
                rows.add(new ArrayList<>(Arrays.asList(key, "[]")));
                return;
            }
            for (int i = 0; i < arr.length() && rows.size() < MAX_TABLE_ROWS; i++) {
                Object item = arr.opt(i);
                rows.add(new ArrayList<>(Arrays.asList(key, cellToString(item))));
            }
            return;
        }
        if (value instanceof JSONObject) {
            JSONObject child = (JSONObject) value;
            JSONArray names = child.names();
            if (names == null || names.length() == 0) {
                rows.add(new ArrayList<>(Arrays.asList(key, "{}")));
                return;
            }
            for (int i = 0; i < names.length() && rows.size() < MAX_TABLE_ROWS; i++) {
                String childKey = names.optString(i);
                appendKeyValueRows(rows, key + "." + childKey, child.opt(childKey));
            }
            return;
        }
        rows.add(new ArrayList<>(Arrays.asList(key, cellToString(value))));
    }

    private static JSONArray firstArrayValue(JSONObject obj, String... keys) {
        for (String key : keys) {
            Object value = obj.opt(key);
            if (value instanceof JSONArray) return (JSONArray) value;
            Object parsed = coerceJsonValue(value);
            if (parsed instanceof JSONArray) return (JSONArray) parsed;
        }
        return null;
    }

    private static JSONArray onlyUsefulArray(JSONObject obj) {
        JSONArray names = obj.names();
        if (names == null) return null;
        JSONArray found = null;
        for (int i = 0; i < names.length(); i++) {
            String key = names.optString(i);
            if (isHeaderLikeKey(key)) continue;
            Object value = obj.opt(key);
            JSONArray arr = value instanceof JSONArray ? (JSONArray) value : null;
            if (arr == null) {
                Object parsed = coerceJsonValue(value);
                if (parsed instanceof JSONArray) arr = (JSONArray) parsed;
            }
            if (arr == null) return null;
            if (arr.length() == 0) continue;
            if (found != null) return null;
            found = arr;
        }
        return found;
    }

    private static boolean isHeaderLikeKey(String key) {
        String normalized = normalizeHeaderKey(key);
        return isAny(normalized, "headers", "columns", "tableheaders");
    }

    private static boolean isEmptyTable(TableData table) {
        return table == null || (table.headers.isEmpty() && table.rows.isEmpty());
    }

    private static void collectObjectKeys(JSONObject obj, List<String> headers) {
        JSONArray names = obj.names();
        if (names == null) return;
        for (int i = 0; i < names.length() && headers.size() < MAX_TABLE_COLUMNS; i++) {
            String name = names.optString(i);
            if (!headers.contains(name)) headers.add(name);
        }
    }

    private static List<String> parseArrayRow(JSONArray arr) {
        List<String> row = new ArrayList<>();
        for (int i = 0; i < arr.length() && row.size() < MAX_TABLE_COLUMNS; i++) {
            row.add(cellToString(arr.opt(i)));
        }
        return row;
    }

    private static List<String> parseObjectRow(JSONObject obj, List<String> headers) {
        List<String> row = new ArrayList<>();
        for (int i = 0; i < headers.size() && row.size() < MAX_TABLE_COLUMNS; i++) {
            row.add(cellToString(valueForHeader(obj, headers.get(i))));
        }
        return row;
    }

    private static Object valueForHeader(JSONObject obj, String header) {
        if (obj == null || header == null) return null;
        if (obj.has(header)) return obj.opt(header);
        String normalizedHeader = normalizeHeaderKey(header);
        JSONArray names = obj.names();
        if (names != null) {
            for (int i = 0; i < names.length(); i++) {
                String key = names.optString(i);
                if (normalizedHeader.equals(normalizeHeaderKey(key))) return obj.opt(key);
            }
        }
        for (String alias : headerAliases(normalizedHeader)) {
            if (obj.has(alias)) return obj.opt(alias);
            if (names == null) continue;
            String normalizedAlias = normalizeHeaderKey(alias);
            for (int i = 0; i < names.length(); i++) {
                String key = names.optString(i);
                if (normalizedAlias.equals(normalizeHeaderKey(key))) return obj.opt(key);
            }
        }
        return null;
    }

    private static String normalizeHeaderKey(String value) {
        if (value == null) return "";
        return value.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s_\\-（）()：:]+", "");
    }

    private static List<String> headerAliases(String normalizedHeader) {
        if (normalizedHeader == null) return Collections.emptyList();
        if (isAny(normalizedHeader, "文件格式", "格式", "扩展名", "文件类型", "fileformat", "extension", "ext", "type")) {
            return Arrays.asList("extension", "ext", "fileFormat", "format", "type");
        }
        if (isAny(normalizedHeader, "文件数量", "数量", "个数", "总数", "count", "filecount")) {
            return Arrays.asList("count", "fileCount", "quantity", "totalCount");
        }
        if (isAny(normalizedHeader, "总大小", "大小", "占用空间", "容量", "totalsize", "totalsizeformatted", "size")) {
            return Arrays.asList("totalSizeFormatted", "sizeFormatted", "totalSize", "size", "bytes");
        }
        if (isAny(normalizedHeader, "路径", "文件路径", "path", "filepath", "outputpath")) {
            return Arrays.asList("path", "filePath", "outputPath", "absolutePath");
        }
        if (isAny(normalizedHeader, "文件名", "名称", "名字", "name", "filename")) {
            return Arrays.asList("name", "fileName", "title");
        }
        return Collections.emptyList();
    }

    private static boolean isAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.equals(normalizeHeaderKey(candidate))) return true;
        }
        return false;
    }

    private static TableData tableFromRows(List<List<String>> rawRows, boolean hasHeader) {
        List<List<String>> cleaned = new ArrayList<>();
        for (List<String> row : rawRows) {
            List<String> normalized = trimTrailingEmpty(row);
            if (!isEmptyRow(normalized)) cleaned.add(normalized);
            if (cleaned.size() >= MAX_TABLE_ROWS + 1) break;
        }
        if (cleaned.isEmpty()) return new TableData(new ArrayList<String>(), new ArrayList<List<String>>());
        List<String> headers;
        List<List<String>> rows = new ArrayList<>();
        if (hasHeader) {
            headers = new ArrayList<>(cleaned.get(0));
            for (int i = 1; i < cleaned.size() && rows.size() < MAX_TABLE_ROWS; i++) rows.add(cleaned.get(i));
        } else {
            headers = defaultHeaders(maxColumns(cleaned));
            for (int i = 0; i < cleaned.size() && rows.size() < MAX_TABLE_ROWS; i++) rows.add(cleaned.get(i));
        }
        return normalizeTable(headers, rows);
    }

    private static TableData normalizeTable(List<String> inputHeaders, List<List<String>> inputRows) {
        List<String> headers = new ArrayList<>(inputHeaders == null ? Collections.<String>emptyList() : inputHeaders);
        List<List<String>> rows = inputRows == null ? new ArrayList<List<String>>() : new ArrayList<>(inputRows);
        int columns = Math.max(headers.size(), maxColumns(rows));
        columns = Math.min(Math.max(columns, headers.isEmpty() && !rows.isEmpty() ? 1 : columns), MAX_TABLE_COLUMNS);
        if (columns == 0) return new TableData(new ArrayList<String>(), new ArrayList<List<String>>());
        while (headers.size() < columns) headers.add(defaultHeader(headers.size()));
        for (int i = 0; i < headers.size(); i++) {
            String h = headers.get(i).trim();
            headers.set(i, h.isEmpty() ? defaultHeader(i) : h);
        }
        if (headers.size() > columns) headers = new ArrayList<>(headers.subList(0, columns));

        List<List<String>> normalizedRows = new ArrayList<>();
        for (List<String> row : rows) {
            List<String> normalized = new ArrayList<>();
            for (int i = 0; i < columns; i++) {
                normalized.add(i < row.size() ? cellToString(row.get(i)) : "");
            }
            normalizedRows.add(normalized);
            if (normalizedRows.size() >= MAX_TABLE_ROWS) break;
        }
        return new TableData(headers, normalizedRows);
    }

    private static List<String> defaultHeaders(int count) {
        List<String> headers = new ArrayList<>();
        for (int i = 0; i < count && i < MAX_TABLE_COLUMNS; i++) headers.add(defaultHeader(i));
        return headers;
    }

    private static String defaultHeader(int index) {
        return (isZh() ? "列" : "Column ") + (index + 1);
    }

    private static int maxColumns(List<List<String>> rows) {
        int max = 0;
        for (List<String> row : rows) {
            if (row != null) max = Math.max(max, row.size());
        }
        return max;
    }

    private static boolean isEmptyRow(List<String> row) {
        if (row == null || row.isEmpty()) return true;
        for (String cell : row) if (cell != null && !cell.trim().isEmpty()) return false;
        return true;
    }

    private static List<String> trimTrailingEmpty(List<String> row) {
        List<String> copy = new ArrayList<>();
        if (row != null) {
            for (String cell : row) copy.add(cellToString(cell));
        }
        while (!copy.isEmpty() && copy.get(copy.size() - 1).trim().isEmpty()) copy.remove(copy.size() - 1);
        return copy;
    }

    private static String cellToString(Object value) {
        if (value == null || JSONObject.NULL.equals(value)) return "";
        return String.valueOf(value);
    }

    private static JSONObject tableJson(TableData table) throws Exception {
        JSONArray rowsJson = new JSONArray();
        for (List<String> row : table.rows) rowsJson.put(new JSONArray(row));
        return new JSONObject()
                .put("headers", new JSONArray(table.headers))
                .put("rows", rowsJson)
                .put("rowCount", table.rows.size())
                .put("columnCount", table.headers.size());
    }

    private static String tableContent(String format, TableData table, String title) throws Exception {
        if ("csv".equals(format)) return tableToCsv(table);
        if ("md".equals(format) || "markdown".equals(format)) return tableToMarkdown(table);
        if ("html".equals(format)) return tableToHtmlDocument(table, title);
        if ("json".equals(format)) return tableJson(table).put("title", title == null ? "" : title).toString(2);
        return tableToCsv(table);
    }

    private static String tableToCsv(TableData table) {
        StringBuilder sb = new StringBuilder();
        sb.append(csvLine(table.headers)).append('\n');
        for (List<String> row : table.rows) sb.append(csvLine(row)).append('\n');
        return sb.toString();
    }

    private static String csvLine(List<String> cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) sb.append(',');
            String cell = cellToString(cells.get(i));
            boolean quote = cell.indexOf(',') >= 0 || cell.indexOf('"') >= 0 || cell.indexOf('\n') >= 0 || cell.indexOf('\r') >= 0;
            if (quote) sb.append('"').append(cell.replace("\"", "\"\"")).append('"');
            else sb.append(cell);
        }
        return sb.toString();
    }

    private static String tableToMarkdown(TableData table) {
        StringBuilder sb = new StringBuilder();
        sb.append("| ").append(join(table.headers, " | ")).append(" |\n|");
        for (int i = 0; i < table.headers.size(); i++) sb.append(" --- |");
        sb.append('\n');
        for (List<String> row : table.rows) {
            sb.append("| ");
            for (int i = 0; i < table.headers.size(); i++) {
                if (i > 0) sb.append(" | ");
                sb.append(cellToString(i < row.size() ? row.get(i) : "").replace("|", "\\|"));
            }
            sb.append(" |\n");
        }
        return sb.toString();
    }

    private static String tableToHtmlDocument(TableData table, String title) {
        String h = (title == null || title.trim().isEmpty()) ? "Table" : title.trim();
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>" + escHtml(h)
                + "</title><style>body{font-family:sans-serif;padding:16px;}table{border-collapse:collapse;width:100%;}"
                + "th,td{border:1px solid #ddd;padding:8px;text-align:left;}th{background:#f5f5f5;}</style></head><body>"
                + "<h1>" + escHtml(h) + "</h1>" + tableHtmlFragment(table) + "</body></html>";
    }

    private static String tableHtmlFragment(TableData table) {
        List<String[]> rows = new ArrayList<>();
        for (List<String> row : table.rows) rows.add(row.toArray(new String[0]));
        return HtmlOutputHelper.table(table.headers.toArray(new String[0]), rows);
    }

    private static List<List<String>> parseDelimitedRows(String text, int maxRows) {
        List<List<String>> rows = new ArrayList<>();
        if (text == null || text.isEmpty()) return rows;
        char delimiter = detectDelimiter(text);
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQuotes = false;
        boolean sawAny = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            sawAny = true;
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == delimiter) {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(cell.toString());
                cell.setLength(0);
                if (!isEmptyRow(row)) rows.add(stripBom(row));
                row = new ArrayList<>();
                if (rows.size() >= maxRows) return rows;
            } else {
                cell.append(c);
            }
        }
        if (sawAny || cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            if (!isEmptyRow(row) && rows.size() < maxRows) rows.add(stripBom(row));
        }
        return rows;
    }

    private static List<String> stripBom(List<String> row) {
        if (!row.isEmpty() && row.get(0).startsWith("\uFEFF")) {
            row.set(0, row.get(0).substring(1));
        }
        return row;
    }

    private static char detectDelimiter(String text) {
        String firstLine = text.split("\\r?\\n", 2)[0];
        int commas = countChar(firstLine, ',');
        int tabs = countChar(firstLine, '\t');
        int semis = countChar(firstLine, ';');
        if (tabs > commas && tabs >= semis) return '\t';
        if (semis > commas) return ';';
        return ',';
    }

    private static int countChar(String text, char target) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == target) count++;
        return count;
    }

    private static boolean looksMarkdownTable(String text) {
        return text != null && text.contains("|") && Pattern.compile("(?m)^\\s*\\|?.+\\|.+$").matcher(text).find();
    }

    private static List<List<String>> parseMarkdownRows(String text, int maxRows) {
        List<List<String>> rows = new ArrayList<>();
        String[] lines = text.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.contains("|")) continue;
            List<String> cells = splitMarkdownRow(trimmed);
            if (cells.isEmpty() || isMarkdownSeparator(cells)) continue;
            rows.add(cells);
            if (rows.size() >= maxRows) break;
        }
        return rows;
    }

    private static List<String> splitMarkdownRow(String line) {
        String s = line.trim();
        if (s.startsWith("|")) s = s.substring(1);
        if (s.endsWith("|")) s = s.substring(0, s.length() - 1);
        String[] parts = s.split("\\|", -1);
        List<String> cells = new ArrayList<>();
        for (String part : parts) cells.add(part.trim().replace("\\|", "|"));
        return cells;
    }

    private static boolean isMarkdownSeparator(List<String> cells) {
        if (cells.isEmpty()) return false;
        for (String cell : cells) {
            if (!cell.trim().matches(":?-{3,}:?")) return false;
        }
        return true;
    }

    private static void writeXlsxTable(File file, TableData table, String title) throws Exception {
        ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(file));
        try {
            addZipText(zos, "[Content_Types].xml", xlsxContentTypes());
            addZipText(zos, "_rels/.rels", xlsxRootRels());
            addZipText(zos, "docProps/app.xml", xlsxAppProps());
            addZipText(zos, "docProps/core.xml", xlsxCoreProps(title));
            addZipText(zos, "xl/workbook.xml", xlsxWorkbookXml(title));
            addZipText(zos, "xl/_rels/workbook.xml.rels", xlsxWorkbookRels());
            addZipText(zos, "xl/styles.xml", xlsxStyles());
            addZipText(zos, "xl/worksheets/sheet1.xml", xlsxWorksheet(table));
        } finally {
            zos.close();
        }
    }

    private static void addZipText(ZipOutputStream zos, String name, String text) throws Exception {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(text.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private static String xlsxContentTypes() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>"
                + "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>"
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>"
                + "</Types>";
    }

    private static String xlsxRootRels() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>"
                + "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties\" Target=\"docProps/app.xml\"/>"
                + "</Relationships>";
    }

    private static String xlsxWorkbookRels() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
                + "</Relationships>";
    }

    private static String xlsxWorkbookXml(String title) {
        String sheet = sheetName(title);
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                + "<sheets><sheet name=\"" + escapeXmlAttr(sheet) + "\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>";
    }

    private static String xlsxStyles() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<fonts count=\"1\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>"
                + "<fills count=\"1\"><fill><patternFill patternType=\"none\"/></fill></fills>"
                + "<borders count=\"1\"><border/></borders>"
                + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
                + "<cellXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/></cellXfs>"
                + "</styleSheet>";
    }

    private static String xlsxAppProps() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\" "
                + "xmlns:vt=\"http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes\">"
                + "<Application>PandaGenie</Application></Properties>";
    }

    private static String xlsxCoreProps(String title) {
        String now = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(new Date());
        String safeTitle = title == null || title.trim().isEmpty() ? "Table" : title.trim();
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" "
                + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" "
                + "xmlns:dcmitype=\"http://purl.org/dc/dcmitype/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">"
                + "<dc:title>" + escapeXmlText(safeTitle) + "</dc:title><dc:creator>PandaGenie</dc:creator>"
                + "<cp:lastModifiedBy>PandaGenie</cp:lastModifiedBy>"
                + "<dcterms:created xsi:type=\"dcterms:W3CDTF\">" + now + "</dcterms:created>"
                + "<dcterms:modified xsi:type=\"dcterms:W3CDTF\">" + now + "</dcterms:modified>"
                + "</cp:coreProperties>";
    }

    private static String xlsxWorksheet(TableData table) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                .append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" ")
                .append("xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheetData>");
        int rowNum = 1;
        appendXlsxRow(sb, rowNum++, table.headers);
        for (List<String> row : table.rows) appendXlsxRow(sb, rowNum++, row);
        sb.append("</sheetData></worksheet>");
        return sb.toString();
    }

    private static void appendXlsxRow(StringBuilder sb, int rowNum, List<String> cells) {
        sb.append("<row r=\"").append(rowNum).append("\">");
        for (int i = 0; i < cells.size() && i < MAX_TABLE_COLUMNS; i++) {
            sb.append("<c r=\"").append(columnName(i)).append(rowNum).append("\" t=\"inlineStr\"><is><t>")
                    .append(escapeXmlText(cellToString(cells.get(i))))
                    .append("</t></is></c>");
        }
        sb.append("</row>");
    }

    private static String sheetName(String title) {
        String sheet = title == null || title.trim().isEmpty() ? "Sheet1" : title.trim();
        sheet = sheet.replaceAll("[\\\\/?*\\[\\]:]", " ").trim();
        if (sheet.isEmpty()) sheet = "Sheet1";
        return sheet.length() > 31 ? sheet.substring(0, 31) : sheet;
    }

    private static List<List<String>> readXlsxRows(File file, int maxRows) throws Exception {
        Map<String, String> entries = readZipXmlEntries(file);
        List<String> shared = entries.containsKey("xl/sharedStrings.xml")
                ? extractSharedStrings(entries.get("xl/sharedStrings.xml"))
                : new ArrayList<String>();
        String sheetPath = firstWorksheetPath(entries);
        if (sheetPath.isEmpty()) return new ArrayList<>();
        return parseXlsxWorksheetRows(entries.get(sheetPath), shared, maxRows);
    }

    private static Map<String, String> readZipXmlEntries(File file) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        ZipInputStream zis = new ZipInputStream(new FileInputStream(file));
        try {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().endsWith(".xml")) {
                    entries.put(entry.getName(), new String(readAll(zis), StandardCharsets.UTF_8));
                }
                zis.closeEntry();
            }
        } finally {
            zis.close();
        }
        return entries;
    }

    private static String firstWorksheetPath(Map<String, String> entries) {
        if (entries.containsKey("xl/worksheets/sheet1.xml")) return "xl/worksheets/sheet1.xml";
        List<String> names = new ArrayList<>(entries.keySet());
        Collections.sort(names);
        for (String name : names) {
            if (name.startsWith("xl/worksheets/") && name.endsWith(".xml")) return name;
        }
        return "";
    }

    private static List<List<String>> parseXlsxWorksheetRows(String xml, List<String> shared, int maxRows) {
        List<List<String>> rows = new ArrayList<>();
        Matcher rowMatcher = Pattern.compile("<row[^>]*>[\\s\\S]*?</row>").matcher(xml == null ? "" : xml);
        while (rowMatcher.find() && rows.size() < maxRows) {
            String rowXml = rowMatcher.group();
            List<String> row = new ArrayList<>();
            int nextColumn = 0;
            Matcher cellMatcher = Pattern.compile("<c([^>]*)>[\\s\\S]*?</c>").matcher(rowXml);
            while (cellMatcher.find()) {
                String cellXml = cellMatcher.group();
                String attrs = cellMatcher.group(1);
                int column = columnIndexFromCellRef(attr(attrs, "r"));
                if (column < 0) column = nextColumn;
                if (column >= MAX_TABLE_COLUMNS) continue;
                while (row.size() <= column) row.add("");
                row.set(column, readXlsxCell(cellXml, attrs, shared));
                nextColumn = column + 1;
            }
            row = trimTrailingEmpty(row);
            if (!isEmptyRow(row)) rows.add(row);
        }
        return rows;
    }

    private static String readXlsxCell(String cellXml, String attrs, List<String> shared) {
        String type = attr(attrs, "t");
        if ("s".equals(type)) {
            String raw = tagText(cellXml, "v");
            try {
                int idx = Integer.parseInt(raw.trim());
                if (idx >= 0 && idx < shared.size()) return shared.get(idx);
            } catch (Exception ignored) {}
            return raw;
        }
        if ("inlineStr".equals(type)) {
            String text = tagText(cellXml, "t");
            return text.isEmpty() ? xmlToText(cellXml) : unescapeXml(text);
        }
        String value = tagText(cellXml, "v");
        if (!value.isEmpty()) return unescapeXml(value);
        String text = tagText(cellXml, "t");
        return text.isEmpty() ? "" : unescapeXml(text);
    }

    private static String attr(String attrs, String name) {
        if (attrs == null) return "";
        Matcher m = Pattern.compile("\\b" + Pattern.quote(name) + "=[\"']([^\"']*)[\"']").matcher(attrs);
        return m.find() ? unescapeXml(m.group(1)) : "";
    }

    private static String tagText(String xml, String tag) {
        Matcher m = Pattern.compile("<" + tag + "[^>]*>([\\s\\S]*?)</" + tag + ">").matcher(xml == null ? "" : xml);
        return m.find() ? unescapeXml(m.group(1)) : "";
    }

    private static int columnIndexFromCellRef(String ref) {
        if (ref == null || ref.isEmpty()) return -1;
        int col = 0;
        int count = 0;
        for (int i = 0; i < ref.length(); i++) {
            char c = Character.toUpperCase(ref.charAt(i));
            if (c < 'A' || c > 'Z') break;
            col = col * 26 + (c - 'A' + 1);
            count++;
        }
        return count == 0 ? -1 : col - 1;
    }

    private static String columnName(int index) {
        StringBuilder sb = new StringBuilder();
        int n = index + 1;
        while (n > 0) {
            int r = (n - 1) % 26;
            sb.insert(0, (char) ('A' + r));
            n = (n - 1) / 26;
        }
        return sb.toString();
    }

    private static String escapeXmlAttr(String s) {
        return escapeXmlText(s).replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String tableCreateDisplay(File file, TableData table) {
        return (isZh() ? "表格已生成" : "Table created")
                + "\n" + (isZh() ? "路径: " : "Path: ") + file.getAbsolutePath()
                + "\n" + (isZh() ? "行数: " : "Rows: ") + table.rows.size()
                + "\n" + (isZh() ? "列数: " : "Columns: ") + table.headers.size();
    }

    private static String tableImportDisplay(File file, TableData table) {
        StringBuilder sb = new StringBuilder();
        sb.append(isZh() ? "表格已导入" : "Table imported")
                .append("\n").append(isZh() ? "文件: " : "File: ").append(displayPath(file.getAbsolutePath()))
                .append("\n").append(isZh() ? "行数: " : "Rows: ").append(table.rows.size())
                .append("\n").append(isZh() ? "列数: " : "Columns: ").append(table.headers.size());
        int previewRows = Math.min(table.rows.size(), 8);
        if (!table.headers.isEmpty()) sb.append("\n\n").append(join(table.headers, "\t"));
        for (int i = 0; i < previewRows; i++) sb.append("\n").append(join(table.rows.get(i), "\t"));
        return sb.toString();
    }

    private static String tableHtml(String title, File file, TableData table) {
        int previewRows = Math.min(table.rows.size(), 12);
        TableData preview = new TableData(table.headers, table.rows.subList(0, previewRows));
        String body = HtmlOutputHelper.keyValue(new String[][]{
                {isZh() ? "文件" : "File", file.getName()},
                {isZh() ? "行数" : "Rows", String.valueOf(table.rows.size())},
                {isZh() ? "列数" : "Columns", String.valueOf(table.headers.size())},
                {isZh() ? "路径" : "Path", displayPath(file.getAbsolutePath())}
        });
        body += tableHtmlFragment(preview);
        if (table.rows.size() > previewRows) {
            body += HtmlOutputHelper.muted(isZh() ? "仅显示前几行预览，完整内容已写入文件。" : "Preview only; the full table is in the file.");
        }
        return HtmlOutputHelper.card("TBL", title, body);
    }

    private static String defaultConvertedPath(File input, String format) {
        String name = input.getName();
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        File dir = new File(Environment.getExternalStorageDirectory(), "PandaGenie/documents");
        return new File(dir, name + "." + format).getAbsolutePath();
    }

    private static boolean isTextExtension(String ext) {
        return TEXT_EXTENSIONS.contains(ext);
    }

    private static boolean isExtractableExtension(String ext) {
        return isTextExtension(ext) || OFFICE_EXTENSIONS.contains(ext) || "pdf".equals(ext);
    }

    private static boolean looksTextual(File file) {
        try {
            if (!file.isFile() || file.length() > MAX_FILE_BYTES) return false;
            String text = readText(file, (int) Math.min(file.length(), 65536));
            return isMostlyReadable(text);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isMostlyReadable(String text) {
        if (text == null || text.isEmpty()) return false;
        int checked = Math.min(text.length(), 4000);
        int bad = 0;
        int visible = 0;
        for (int i = 0; i < checked; i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') continue;
            if (Character.isISOControl(c)) bad++;
            else visible++;
        }
        return visible > 0 && bad <= checked / 20;
    }

    private static String xmlToText(String xml) {
        String s = xml.replaceAll("(?i)<w:tab\\s*/?>", "\t")
                .replaceAll("(?i)<a:br\\s*/?>", "\n")
                .replaceAll("(?i)<w:br\\s*/?>", "\n")
                .replaceAll("(?i)</w:p>", "\n")
                .replaceAll("(?i)</a:p>", "\n")
                .replaceAll("(?i)</row>", "\n")
                .replaceAll("(?i)</si>", "\n");
        s = s.replaceAll("<[^>]+>", "");
        return normalizeText(unescapeXml(s));
    }

    private static String decodePdfLiteral(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n': out.append('\n'); break;
                    case 'r': out.append('\r'); break;
                    case 't': out.append('\t'); break;
                    case 'b': out.append('\b'); break;
                    case 'f': out.append('\f'); break;
                    case '\\': out.append('\\'); break;
                    case '(':
                    case ')': out.append(n); break;
                    default: out.append(n); break;
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String normalizeText(String s) {
        if (s == null) return "";
        return s.replace("\0", "")
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\t ]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private static String normalizeWhitespace(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    private static String normalizeAction(String action, JSONObject params) {
        if (action == null) return "";
        String compact = action.trim().replaceAll("[\\s_\\-]+", "").toLowerCase(Locale.ROOT);
        String format = firstString(params, "format", "type").trim().toLowerCase(Locale.ROOT);
        String path = firstString(params, "path", "filePath", "outputPath").trim().toLowerCase(Locale.ROOT);
        boolean looksCsv = "csv".equals(format) || path.endsWith(".csv");
        switch (compact) {
            case "createdocument":
            case "createdoc":
            case "newdocument":
            case "writedocument":
            case "generatedocument":
            case "createfile":
            case "writefile":
            case "savefile":
                return "createDocument";
            case "createtable":
            case "generatetable":
            case "createspreadsheet":
            case "generatespreadsheet":
            case "createexcel":
            case "generateexcel":
                return "createTable";
            case "createcsv":
            case "generatecsv":
            case "writecsv":
            case "savecsv":
            case "exportcsv":
                return looksCsv ? "createDocument" : "createTable";
            case "importtable":
            case "readtable":
            case "importspreadsheet":
            case "readspreadsheet":
            case "readcsv":
                return "importTable";
            default:
                return action.trim();
        }
    }

    private static List<String> firstParagraphs(String text, int max) {
        List<String> out = new ArrayList<>();
        String[] parts = text.split("\\n\\s*\\n|\\n");
        for (String p : parts) {
            String item = normalizeWhitespace(p);
            if (!item.isEmpty()) out.add(item);
            if (out.size() >= max) break;
        }
        return out;
    }

    private static String extension(String name) {
        if (name == null) return "";
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String guessMimeType(String ext) {
        if ("pdf".equals(ext)) return "application/pdf";
        if ("docx".equals(ext)) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if ("xlsx".equals(ext)) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if ("pptx".equals(ext)) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if ("html".equals(ext) || "htm".equals(ext)) return "text/html";
        if ("json".equals(ext)) return "application/json";
        if ("csv".equals(ext)) return "text/csv";
        if ("md".equals(ext) || "markdown".equals(ext)) return "text/markdown";
        return "text/plain";
    }

    private static String cleanPath(String path) {
        if (path == null) return "";
        String p = path.trim();
        p = p.replaceAll("^[\\u300c\\u300e\\u3010\\u300a\\u201c\\u2018\"'<]+", "")
                .replaceAll("[\\u300d\\u300f\\u3011\\u300b\\u201d\\u2019\"'>]+$", "");
        return p.trim();
    }

    private static String resolveStoragePath(String path) {
        String canonical = cleanPath(path)
                .replace("\\/", "/")
                .replace("\\", "/");
        while (canonical.contains("//") && !canonical.contains("://")) canonical = canonical.replace("//", "/");
        if (canonical.equals("/sdcard") || canonical.startsWith("/sdcard/")) {
            String realRoot = Environment.getExternalStorageDirectory().getAbsolutePath();
            if (!realRoot.equals("/sdcard")) return canonical.replaceFirst("/sdcard", realRoot);
        }
        return canonical;
    }

    private static String firstString(JSONObject params, String... keys) {
        for (String key : keys) {
            if (!params.has(key)) continue;
            Object value = params.opt(key);
            if (value == null || JSONObject.NULL.equals(value)) continue;
            String s = String.valueOf(value);
            if (!s.trim().isEmpty()) return s;
        }
        return "";
    }

    private static boolean parseBoolean(Object value, boolean fallback) {
        if (value == null || JSONObject.NULL.equals(value)) return fallback;
        if (value instanceof Boolean) return (Boolean) value;
        String s = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if ("true".equals(s) || "1".equals(s) || "yes".equals(s)) return true;
        if ("false".equals(s) || "0".equals(s) || "no".equals(s)) return false;
        return fallback;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static boolean samePath(File a, File b) {
        try {
            return a.getCanonicalPath().equals(b.getCanonicalPath());
        } catch (Exception e) {
            return a.getAbsolutePath().equals(b.getAbsolutePath());
        }
    }

    private static String lineJoiner(String a, String b) {
        if (a == null || a.isEmpty() || b == null || b.isEmpty()) return "";
        return a.endsWith("\n") || b.startsWith("\n") ? "" : "\n";
    }

    private static String formatSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format(Locale.US, "%.1f KB", size / 1024.0);
        if (size < 1024L * 1024 * 1024) return String.format(Locale.US, "%.2f MB", size / (1024.0 * 1024));
        return String.format(Locale.US, "%.2f GB", size / (1024.0 * 1024 * 1024));
    }

    private static String displayPath(String path) {
        String p = path.replace("/storage/emulated/0/", "/sdcard/").replace("/storage/emulated/0", "/sdcard");
        if (p.length() <= 56) return p;
        return p.substring(0, 20) + "..." + p.substring(p.length() - 24);
    }

    private static String extractDisplay(File file, DocumentText doc) {
        String preview = doc.text;
        if (preview.length() > MAX_DISPLAY_CHARS) preview = preview.substring(0, MAX_DISPLAY_CHARS) + "...";
        return (isZh() ? "文档文本提取" : "Document text extracted")
                + "\n" + (isZh() ? "文件: " : "File: ") + displayPath(file.getAbsolutePath())
                + "\n" + (isZh() ? "字符数: " : "Characters: ") + doc.text.length()
                + (doc.truncated ? "\n" + (isZh() ? "已截断展示" : "Truncated") : "")
                + (doc.warning.isEmpty() ? "" : "\n" + doc.warning)
                + "\n\n" + preview;
    }

    private static String queryDisplay(File file, String query, JSONArray matches) {
        StringBuilder sb = new StringBuilder();
        sb.append(isZh() ? "文档查询结果" : "Document query")
                .append("\n").append(isZh() ? "文件: " : "File: ").append(displayPath(file.getAbsolutePath()))
                .append("\n").append(isZh() ? "命中: " : "Matches: ").append(matches.length());
        if (!query.isEmpty()) sb.append("\n").append(isZh() ? "关键词: " : "Query: ").append(query);
        for (int i = 0; i < matches.length(); i++) {
            JSONObject item = matches.optJSONObject(i);
            if (item == null) continue;
            sb.append("\n\n").append(i + 1).append(". ").append(item.optString("excerpt", ""));
        }
        return sb.toString();
    }

    private static String editDisplay(EditResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append(isZh() ? "文档已处理" : "Document updated")
                .append("\n").append(isZh() ? "输出: " : "Output: ").append(displayPath(result.outputPath))
                .append("\n").append(isZh() ? "替换次数: " : "Occurrences: ").append(result.occurrences);
        if (!result.backupPath.isEmpty()) {
            sb.append("\n").append(isZh() ? "备份: " : "Backup: ").append(displayPath(result.backupPath));
        }
        if (!result.changed) {
            sb.append("\n").append(isZh() ? "未找到匹配文本，文件内容未实际变化。" : "No matching text found.");
        }
        return sb.toString();
    }

    private static String fileOutputDisplay(File file, String title) {
        return title + "\n" + (isZh() ? "路径: " : "Path: ") + file.getAbsolutePath()
                + "\n" + (isZh() ? "大小: " : "Size: ") + formatSize(file.length());
    }

    private static String supportedDisplay() {
        return isZh()
                ? "文档处理支持: 提取 txt/md/csv/json/xml/html/docx/xlsx/pptx/pdf；导入 csv/xlsx；生成 xlsx/csv/md/html/json 表格；编辑文本和 Office 文档；转换为 txt/md/html/json。"
                : "Supported: extract txt/md/csv/json/xml/html/docx/xlsx/pptx/pdf; import csv/xlsx; create xlsx/csv/md/html/json tables; edit text and Office documents; convert to txt/md/html/json.";
    }

    private static String infoDisplay(JSONObject out) {
        return (isZh() ? "文档信息" : "Document info")
                + "\n" + out.optString("name")
                + "\n" + out.optString("size")
                + "\n" + displayPath(out.optString("path"));
    }

    private static String extractHtml(File file, DocumentText doc) {
        String preview = doc.text.length() > 900 ? doc.text.substring(0, 900) + "..." : doc.text;
        String body = HtmlOutputHelper.keyValue(new String[][]{
                {isZh() ? "文件" : "File", file.getName()},
                {isZh() ? "格式" : "Format", doc.format},
                {isZh() ? "字符数" : "Characters", String.valueOf(doc.text.length())},
                {isZh() ? "路径" : "Path", displayPath(file.getAbsolutePath())}
        });
        if (!doc.warning.isEmpty()) body += HtmlOutputHelper.muted(doc.warning);
        body += HtmlOutputHelper.p(preview);
        return HtmlOutputHelper.card("DOC", isZh() ? "文档文本" : "Document Text", body);
    }

    private static String queryHtml(File file, String query, JSONArray matches) {
        List<String[]> rows = new ArrayList<>();
        for (int i = 0; i < matches.length(); i++) {
            JSONObject item = matches.optJSONObject(i);
            if (item != null) rows.add(new String[]{String.valueOf(i + 1), item.optString("excerpt", "")});
        }
        String body = HtmlOutputHelper.keyValue(new String[][]{
                {isZh() ? "文件" : "File", file.getName()},
                {isZh() ? "查询" : "Query", query.isEmpty() ? (isZh() ? "预览" : "Preview") : query},
                {isZh() ? "命中" : "Matches", String.valueOf(matches.length())}
        });
        body += HtmlOutputHelper.table(new String[]{"#", isZh() ? "内容" : "Excerpt"}, rows);
        return HtmlOutputHelper.card("DOC", isZh() ? "文档查询" : "Document Query", body);
    }

    private static String editHtml(EditResult result) {
        String body = HtmlOutputHelper.keyValue(new String[][]{
                {isZh() ? "输出" : "Output", displayPath(result.outputPath)},
                {isZh() ? "替换次数" : "Occurrences", String.valueOf(result.occurrences)},
                {isZh() ? "备份" : "Backup", result.backupPath.isEmpty() ? "-" : displayPath(result.backupPath)}
        });
        if (!result.changed) body += HtmlOutputHelper.muted(isZh() ? "未找到匹配文本。" : "No matching text found.");
        return HtmlOutputHelper.card("DOC", isZh() ? "文档处理完成" : "Document Updated", body);
    }

    private static String fileOutputHtml(File file, String title) {
        return HtmlOutputHelper.card("DOC", title, HtmlOutputHelper.keyValue(new String[][]{
                {isZh() ? "路径" : "Path", displayPath(file.getAbsolutePath())},
                {isZh() ? "大小" : "Size", formatSize(file.length())}
        }) + HtmlOutputHelper.successBadge());
    }

    private static String infoHtml(JSONObject out) {
        return HtmlOutputHelper.card("DOC", isZh() ? "文档信息" : "Document Info",
                HtmlOutputHelper.keyValue(new String[][]{
                        {isZh() ? "名称" : "Name", out.optString("name")},
                        {isZh() ? "类型" : "Type", out.optString("extension")},
                        {isZh() ? "大小" : "Size", out.optString("size")},
                        {isZh() ? "可提取" : "Extractable", String.valueOf(out.optBoolean("extractable"))},
                        {isZh() ? "可编辑" : "Editable", String.valueOf(out.optBoolean("editable"))},
                        {isZh() ? "路径" : "Path", displayPath(out.optString("path"))}
                }));
    }

    private static String supportedHtml() {
        return HtmlOutputHelper.card("DOC", isZh() ? "文档处理能力" : "Document Tools",
                HtmlOutputHelper.iconList(new String[][]{
                        {"R", isZh() ? "读取与查询: txt/md/csv/json/xml/html/docx/xlsx/pptx/pdf" : "Read/query txt, md, csv, json, xml, html, docx, xlsx, pptx, pdf"},
                        {"W", isZh() ? "编辑: 文本文件与 Office 文档精确替换" : "Edit text files and exact text in Office documents"},
                        {"C", isZh() ? "转换: 输出 txt/md/html/json" : "Convert to txt, md, html, json"},
                        {"T", isZh() ? "表格: 导入 csv/xlsx，生成 xlsx/csv/md/html/json" : "Tables: import csv/xlsx; create xlsx/csv/md/html/json"},
                        {"N", isZh() ? "创建: txt/md/html/json/csv" : "Create txt, md, html, json, csv"}
                }));
    }

    private static boolean isZh() {
        try {
            return Locale.getDefault().getLanguage().toLowerCase(Locale.ROOT).startsWith("zh");
        } catch (Exception e) {
            return false;
        }
    }

    private static String emptyJson(String value) {
        return value == null || value.trim().isEmpty() ? "{}" : value;
    }

    private static String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String escapeXmlText(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String unescapeXml(String s) {
        return s.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&apos;", "'");
    }

    private static String join(List<String> items, String delimiter) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(delimiter);
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    private static JSONObject richCode(String code, String language) throws Exception {
        return new JSONObject()
                .put("type", "code")
                .put("code", code == null ? "" : code)
                .put("language", language == null ? "text" : language);
    }

    private static JSONObject richFile(String path, String title, String mimeType) throws Exception {
        JSONObject rc = new JSONObject()
                .put("type", "file")
                .put("path", path);
        if (title != null && !title.isEmpty()) rc.put("title", title);
        if (mimeType != null && !mimeType.isEmpty()) rc.put("mimeType", mimeType);
        File f = new File(path);
        if (f.exists()) rc.put("size", f.length());
        return rc;
    }

    private static String ok(JSONObject output, String displayText, String displayHtml) throws Exception {
        return ok(output, displayText, displayHtml, null);
    }

    private static String ok(JSONObject output, String displayText, String displayHtml, JSONArray richContent) throws Exception {
        JSONObject r = new JSONObject()
                .put("success", true)
                .put("output", output.toString());
        if (displayText != null && !displayText.isEmpty()) r.put("_displayText", displayText);
        if (displayHtml != null && !displayHtml.isEmpty()) r.put("_displayHtml", displayHtml);
        if (richContent != null && richContent.length() > 0) r.put("_richContent", richContent);
        return r.toString();
    }

    private static String error(String message) throws Exception {
        return new JSONObject()
                .put("success", false)
                .put("error", message)
                .toString();
    }

    private interface EntryMatcher {
        boolean matches(String name);
    }

    private static class DocumentText {
        final String text;
        final String format;
        final boolean truncated;
        final String warning;
        DocumentText(String text, String format, boolean truncated, String warning) {
            this.text = text == null ? "" : text;
            this.format = format == null ? "" : format;
            this.truncated = truncated;
            this.warning = warning == null ? "" : warning;
        }
    }

    private static class ReplaceResult {
        final String text;
        final int occurrences;
        ReplaceResult(String text, int occurrences) {
            this.text = text;
            this.occurrences = occurrences;
        }
    }

    private static class EditResult {
        final boolean changed;
        final int occurrences;
        final String outputPath;
        final String backupPath;
        EditResult(boolean changed, int occurrences, String outputPath, String backupPath) {
            this.changed = changed;
            this.occurrences = occurrences;
            this.outputPath = outputPath == null ? "" : outputPath;
            this.backupPath = backupPath == null ? "" : backupPath;
        }
    }

    private static class TableData {
        final List<String> headers;
        final List<List<String>> rows;
        TableData(List<String> headers, List<List<String>> rows) {
            this.headers = headers == null ? new ArrayList<String>() : headers;
            this.rows = rows == null ? new ArrayList<List<String>>() : rows;
        }
    }
}
