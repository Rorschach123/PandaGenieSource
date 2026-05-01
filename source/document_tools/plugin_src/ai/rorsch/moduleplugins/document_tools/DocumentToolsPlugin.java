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
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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

    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        try {
            JSONObject params = new JSONObject(emptyJson(paramsJson));
            switch (action) {
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
                case "deleteDocument":
                    return handleDeleteDocument(params);
                case "convertDocument":
                    return handleConvertDocument(params);
                case "listSupportedFormats":
                    return handleListSupportedFormats();
                default:
                    return error("Unsupported action: " + action);
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
        String path = resolveStoragePath(firstString(params, "path", "filePath", "outputPath"));
        String content = firstString(params, "content", "text", "body");
        String format = firstString(params, "format", "type").trim().toLowerCase(Locale.ROOT);
        String title = firstString(params, "title", "name").trim();
        if (path.isEmpty()) return error("path is empty");
        if (format.isEmpty()) format = extension(path);
        if (format.isEmpty()) format = "txt";
        if (!CREATE_FORMATS.contains(format)) return error("Unsupported create format: " + format);
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
        Matcher rowMatcher = Pattern.compile("<row[\\s\\S]*?</row>").matcher(xml);
        while (rowMatcher.find()) {
            String row = rowMatcher.group();
            List<String> cells = new ArrayList<>();
            Matcher cellMatcher = Pattern.compile("<c([^>]*)>[\\s\\S]*?<v>(.*?)</v>[\\s\\S]*?</c>").matcher(row);
            while (cellMatcher.find()) {
                String attrs = cellMatcher.group(1);
                String raw = unescapeXml(cellMatcher.group(2));
                if (attrs != null && attrs.contains("t=\"s\"")) {
                    try {
                        int idx = Integer.parseInt(raw.trim());
                        if (idx >= 0 && idx < shared.size()) raw = shared.get(idx);
                    } catch (Exception ignored) {}
                }
                if (!raw.trim().isEmpty()) cells.add(raw.trim());
            }
            if (!cells.isEmpty()) out.append(join(cells, "\t")).append('\n');
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
                ? "文档处理支持: 提取 txt/md/csv/json/xml/html/docx/xlsx/pptx/pdf；编辑文本和 Office 文档；转换为 txt/md/html/json。"
                : "Supported: extract txt/md/csv/json/xml/html/docx/xlsx/pptx/pdf; edit text and Office documents; convert to txt/md/html/json.";
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
}
