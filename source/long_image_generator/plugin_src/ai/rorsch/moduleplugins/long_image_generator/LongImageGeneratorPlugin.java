package ai.rorsch.moduleplugins.long_image_generator;

import ai.rorsch.pandagenie.module.runtime.HtmlOutputHelper;
import ai.rorsch.pandagenie.module.runtime.ModuleLlm;
import ai.rorsch.pandagenie.module.runtime.ModulePlugin;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class LongImageGeneratorPlugin implements ModulePlugin {
    private static final int WIDTH = 1080;
    private static final int MARGIN = 64;
    private static final int MAX_TEXT_BYTES = 2 * 1024 * 1024;
    private static final String WATERMARK = "由 PandaGenie 生成 · 开源免费 Android AI 助手";
    private static final String STYLE_PLAIN = "plain";
    private static final String STYLE_HERO = "hero";
    private static final String STYLE_METRICS = "metrics";
    private static final String STYLE_WARNING = "warning";
    private static final String STYLE_DETAILS = "details";

    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        JSONObject params = new JSONObject(emptyJson(paramsJson));
        try {
            if ("renderInputToImage".equals(action)) return renderInputToImage(context, params);
            if ("generateSmartLongImage".equals(action)) return generateSmartLongImage(context, params);
            if ("saveLongImage".equals(action)) return saveLongImage(params);
            if ("extractInputText".equals(action)) return extractInputText(context, params);
            if ("formatShareText".equals(action)) return formatShareText(params);
            if ("openPage".equals(action)) {
                return new JSONObject().put("success", true).put("output", "{}").put("_openModule", true).toString();
            }
            return error("Unsupported action: " + action);
        } catch (Exception e) {
            return error(e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    private String renderInputToImage(Context context, JSONObject params) throws Exception {
        InputDoc doc = loadInput(context, params);
        String title = nonEmpty(params.optString("title", ""), nonEmpty(doc.title, "长图"));
        String subtitle = nonEmpty(params.optString("subtitle", ""), doc.sourceLabel);
        int maxHeight = clamp(params.optInt("maxHeight", 18000), 3000, 30000);
        int theme = parseColor(params.optString("themeColor", "#17A36B"));
        String language = resolveLanguage(params, doc.text + "\n" + title + "\n" + subtitle);
        StructuredDoc structured = buildStructuredDoc(doc.text, language);
        List<Section> sections;
        String inputType = doc.inputType;
        String sourceLabel = doc.sourceLabel;
        if (structured != null) {
            if (params.optString("title", "").trim().isEmpty()) title = structured.title;
            if (params.optString("subtitle", "").trim().isEmpty()) subtitle = structured.subtitle;
            sections = structured.sections;
            inputType = structured.inputType;
            sourceLabel = nonEmpty(structured.sourceLabel, doc.sourceLabel);
        } else {
            sections = sectionsFromText(doc.text, doc.inputType);
        }
        if (sections.isEmpty()) sections.add(new Section("内容", doc.text));
        return renderAndSave(params, title, subtitle, sections, theme, maxHeight, inputType, sourceLabel);
    }

    private String generateSmartLongImage(Context context, JSONObject params) throws Exception {
        InputDoc doc = loadInput(context, params);
        String language = resolveLanguage(params, doc.text + "\n" + params.optString("instruction", ""));
        String instruction = params.optString("instruction", "").trim();
        int maxChars = clamp(params.optInt("maxInputChars", 12000), 1000, 20000);
        int maxTokens = clamp(params.optInt("maxTokens", 1024), 256, 1024);
        int maxHeight = clamp(params.optInt("maxHeight", 18000), 3000, 30000);
        int theme = parseColor(params.optString("themeColor", "#17A36B"));
        boolean truncated = doc.text.length() > maxChars;
        String usedText = truncated ? doc.text.substring(0, maxChars) : doc.text;

        if (instruction.isEmpty()) {
            instruction = "zh".equals(language)
                    ? "请把输入内容整理成适合生成分享长图的标题、摘要和分段正文。要求信息准确、层次清楚、不要编造原文没有的内容。"
                    : "Turn the input into a shareable long-image draft with a title, short summary, and clear sections. Keep facts accurate and do not invent details.";
        }
        String prompt = ("zh".equals(language) ? "输出语言：" : "Output language: ") + language
                + "\n" + instruction
                + "\n\n请输出 Markdown，第一行用 # 标题，后续用二级标题分段。"
                + "\n\nSource:\n" + usedText;
        JSONObject request = new JSONObject()
                .put("action", "long_image_generator.generateSmartLongImage")
                .put("prompt", prompt)
                .put("temperature", 0.25)
                .put("maxTokens", maxTokens);
        JSONObject llm = new JSONObject(ModuleLlm.completeJson(context, request.toString()));
        if (!llm.optBoolean("success", false)) {
            throw new IllegalStateException(llm.optString("error", "LLM request failed"));
        }

        String aiText = normalizeText(llm.optString("text", ""));
        if (aiText.isEmpty()) {
            throw new IllegalStateException("LLM returned empty content");
        }
        List<Section> sections = sectionsFromText(aiText, "markdown");
        if (sections.isEmpty()) sections.add(new Section("AI 整理", aiText));
        String title = nonEmpty(params.optString("title", ""),
                firstUsefulLine(aiText, nonEmpty(doc.title, "智能长图")).replaceFirst("^#+\\s*", ""));
        String subtitle = nonEmpty(params.optString("subtitle", ""),
                ("zh".equals(language) ? "AI 整理 · " : "AI organized · ") + doc.sourceLabel);

        String rendered = renderAndSave(params, title, subtitle, sections, theme, maxHeight, "llm", doc.sourceLabel);
        JSONObject response = new JSONObject(rendered);
        JSONObject output = new JSONObject(response.optString("output", "{}"));
        output.put("source", "llm");
        output.put("inputSource", doc.sourceLabel);
        output.put("inputType", doc.inputType);
        output.put("inputTruncated", truncated);
        output.put("aiText", aiText);
        response.put("output", output.toString());
        return response.toString();
    }

    private String saveLongImage(JSONObject params) throws Exception {
        String title = nonEmpty(params.optString("title", ""), "长图");
        String subtitle = params.optString("subtitle", "");
        int maxHeight = clamp(params.optInt("maxHeight", 18000), 3000, 30000);
        int theme = parseColor(params.optString("themeColor", "#17A36B"));
        List<Section> sections = parseSections(params.opt("sections"), params.optString("summary", ""));
        String rawText = params.optString("text", params.optString("content", params.optString("reportText", "")));
        String language = resolveLanguage(params, rawText + "\n" + title + "\n" + subtitle);
        String probeText = rawText;
        if (probeText.trim().isEmpty() && !sections.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Section section : sections) {
                if (section.body != null && !section.body.trim().isEmpty()) sb.append(section.body).append('\n');
                if (sb.length() > 20000) break;
            }
            probeText = sb.toString();
        }
        StructuredDoc structured = buildStructuredDoc(probeText, language);
        if (structured != null) {
            if (params.optString("title", "").trim().isEmpty()) title = structured.title;
            if (params.optString("subtitle", "").trim().isEmpty()) subtitle = structured.subtitle;
            return renderAndSave(params, title, subtitle, structured.sections, theme, maxHeight, structured.inputType, structured.sourceLabel);
        }
        if (sections.isEmpty()) sections.add(new Section("内容", nonEmpty(rawText, "暂无内容")));
        return renderAndSave(params, title, subtitle, sections, theme, maxHeight, "sections", "手动输入");
    }

    private String extractInputText(Context context, JSONObject params) throws Exception {
        InputDoc doc = loadInput(context, params);
        JSONObject out = new JSONObject();
        out.put("title", doc.title);
        out.put("inputType", doc.inputType);
        out.put("source", doc.sourceLabel);
        out.put("charCount", doc.text.length());
        out.put("lineCount", doc.text.split("\\n", -1).length);
        out.put("preview", doc.text.length() > 800 ? doc.text.substring(0, 800) + "..." : doc.text);
        out.put("text", doc.text);
        String text = "📝 已提取可渲染文本\n核心判断：内容可用于生成长图\n来源：" + doc.sourceLabel
                + "\n类型：" + doc.inputType + "\n字数：" + doc.text.length();
        String html = HtmlOutputHelper.card("📝", "长图文本提取",
                HtmlOutputHelper.badge("可生成长图", "green")
                        + HtmlOutputHelper.keyValue(new String[][]{
                        {"来源", doc.sourceLabel},
                        {"类型", doc.inputType},
                        {"字符数", String.valueOf(doc.text.length())}
                })
                        + HtmlOutputHelper.p(out.optString("preview")));
        return ok(out, text, html);
    }

    private String formatShareText(JSONObject params) throws Exception {
        String title = nonEmpty(params.optString("title", ""), "长图内容");
        String summary = nonEmpty(params.optString("summary", ""), params.optString("text", ""));
        List<Section> sections = parseSections(params.opt("sections"), "");
        StringBuilder sb = new StringBuilder();
        sb.append("【").append(title).append("】\n\n");
        if (!summary.isEmpty()) sb.append(summary).append("\n\n");
        for (Section s : sections) {
            sb.append("## ").append(s.title).append("\n").append(s.body).append("\n\n");
        }
        sb.append(WATERMARK);
        JSONObject out = new JSONObject().put("shareText", sb.toString()).put("title", title);
        return ok(out, sb.toString(), HtmlOutputHelper.card("📝", "分享文本",
                HtmlOutputHelper.badge("可复制", "blue") + HtmlOutputHelper.p(summary)
                        + HtmlOutputHelper.muted(WATERMARK)));
    }

    private String renderAndSave(JSONObject params, String title, String subtitle, List<Section> sections,
                                 int theme, int maxHeight, String inputType, String sourceLabel) throws Exception {
        sections = splitOversizedSections(sections);
        Layout layout = buildLayout(title, subtitle, sections, maxHeight);
        Bitmap bitmap = Bitmap.createBitmap(WIDTH, layout.height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawLongImage(canvas, layout, title, subtitle, sections, theme);

        String outputPath = params.optString("outputPath", "").trim();
        File outFile = resolveOutputFile(outputPath, title);
        File parent = outFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new Exception("无法创建输出目录：" + parent.getAbsolutePath());
        }
        FileOutputStream fos = new FileOutputStream(outFile);
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
        fos.flush();
        fos.close();
        bitmap.recycle();

        JSONObject out = new JSONObject();
        out.put("outputPath", outFile.getAbsolutePath());
        out.put("width", WIDTH);
        out.put("height", layout.height);
        out.put("inputType", inputType);
        out.put("source", sourceLabel);
        out.put("sectionCount", sections.size());
        out.put("truncated", layout.truncated);
        out.put("watermark", WATERMARK);

        String display = "🖼️ 长图已生成\n核心判断：已保存为 1080px 宽 PNG"
                + (layout.truncated ? "\n提醒：内容较长，已按最大高度截断" : "")
                + "\n路径：" + outFile.getAbsolutePath();
        String html = HtmlOutputHelper.card("🖼️", "长图生成器",
                HtmlOutputHelper.badge(layout.truncated ? "已生成，内容截断" : "已生成", layout.truncated ? "orange" : "green")
                        + HtmlOutputHelper.keyValue(new String[][]{
                        {"标题", title},
                        {"尺寸", WIDTH + " × " + layout.height},
                        {"来源", sourceLabel},
                        {"路径", outFile.getAbsolutePath()}
                })
                        + HtmlOutputHelper.muted(WATERMARK));
        return ok(out, display, html);
    }

    private InputDoc loadInput(Context context, JSONObject params) throws Exception {
        String explicitType = params.optString("inputType", "auto").trim().toLowerCase(Locale.ROOT);
        String url = params.optString("url", "").trim();
        String inputPath = params.optString("inputPath", params.optString("path", "")).trim();
        String html = params.optString("html", "");
        String text = params.optString("text", params.optString("content", params.optString("reportText", "")));

        if (!url.isEmpty() || "url".equals(explicitType)) {
            if (url.isEmpty()) throw new Exception("缺少 url 参数");
            return fetchWebPage(url);
        }
        if (!inputPath.isEmpty()) {
            return readInputFile(inputPath, explicitType);
        }
        if (!html.trim().isEmpty() || "html".equals(explicitType)) {
            String title = extractHtmlTitle(html);
            return new InputDoc(nonEmpty(title, "HTML 长图"), htmlToText(html), "html", "HTML 内容");
        }
        if (!text.trim().isEmpty()) {
            String type = "markdown".equals(explicitType) ? "markdown" : "text";
            return new InputDoc(firstUsefulLine(text, "文本长图"), normalizeText(text), type, "直接输入");
        }
        throw new Exception("缺少输入内容：请提供 text、inputPath、url 或 html");
    }

    private InputDoc readInputFile(String rawPath, String explicitType) throws Exception {
        String path = normalizePath(rawPath);
        File file = new File(path);
        if (!file.exists() || !file.isFile()) throw new Exception("文件不存在：" + path);
        String name = file.getName();
        String lower = name.toLowerCase(Locale.ROOT);
        String type = "auto".equals(explicitType) || explicitType.isEmpty() ? detectType(lower) : explicitType;

        if ("doc".equals(type) || lower.endsWith(".doc")) {
            throw new Exception("暂不支持旧版 .doc 二进制文档，请转为 .docx 后再生成长图");
        }
        if ("docx".equals(type)) {
            String text = extractDocx(file);
            return new InputDoc(stripExt(name), text, "docx", file.getAbsolutePath());
        }
        String raw = readTextFile(file, MAX_TEXT_BYTES);
        if ("html".equals(type)) {
            return new InputDoc(nonEmpty(extractHtmlTitle(raw), stripExt(name)), htmlToText(raw), "html", file.getAbsolutePath());
        }
        String renderType = "markdown".equals(type) ? "markdown" : "text";
        return new InputDoc(stripExt(name), normalizeText(raw), renderType, file.getAbsolutePath());
    }

    private InputDoc fetchWebPage(String urlText) throws Exception {
        if (!urlText.startsWith("http://") && !urlText.startsWith("https://")) {
            throw new Exception("只支持 http/https 网页 URL");
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlText).openConnection();
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "PandaGenie LongImageGenerator/1.0");
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (in == null) throw new Exception("网页读取失败，HTTP " + code);
            String charset = charsetFromContentType(conn.getContentType());
            String html = decodeBytes(readLimited(in, MAX_TEXT_BYTES), charset);
            String title = nonEmpty(extractHtmlTitle(html), new URL(urlText).getHost());
            return new InputDoc(title, htmlToText(html), "url", urlText);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String extractDocx(File file) throws Exception {
        ZipInputStream zis = new ZipInputStream(new FileInputStream(file));
        try {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if ("word/document.xml".equals(entry.getName())) {
                    String xml = decodeBytes(readLimited(zis, MAX_TEXT_BYTES), "UTF-8");
                    return xmlToDocText(xml);
                }
            }
        } finally {
            zis.close();
        }
        throw new Exception("DOCX 中未找到 word/document.xml");
    }

    private String htmlToText(String html) {
        if (html == null) return "";
        String s = html;
        s = s.replaceAll("(?is)<script[^>]*>.*?</script>", " ");
        s = s.replaceAll("(?is)<style[^>]*>.*?</style>", " ");
        s = s.replaceAll("(?i)<br\\s*/?>", "\n");
        s = s.replaceAll("(?i)</p\\s*>", "\n\n");
        s = s.replaceAll("(?i)</h[1-6]\\s*>", "\n\n");
        s = s.replaceAll("(?i)</li\\s*>", "\n");
        s = s.replaceAll("<[^>]+>", " ");
        return normalizeText(unescapeHtml(s));
    }

    private String xmlToDocText(String xml) {
        String s = xml;
        s = s.replaceAll("(?i)<w:tab\\s*/>", "\t");
        s = s.replaceAll("(?i)</w:p>", "\n");
        s = s.replaceAll("(?i)</w:tr>", "\n");
        s = s.replaceAll("(?i)</w:tc>", "\t");
        s = s.replaceAll("<[^>]+>", "");
        return normalizeText(unescapeHtml(s));
    }

    private String extractHtmlTitle(String html) {
        if (html == null) return "";
        Matcher m = Pattern.compile("(?is)<title[^>]*>(.*?)</title>").matcher(html);
        return m.find() ? normalizeText(unescapeHtml(m.group(1))) : "";
    }

    private StructuredDoc buildStructuredDoc(String raw) {
        return buildStructuredDoc(raw, "");
    }

    private StructuredDoc buildStructuredDoc(String raw, String language) {
        String text = normalizeText(raw);
        if (text.isEmpty()) return null;
        String lang = nonEmpty(language, "zh").toLowerCase(Locale.ROOT);
        try {
            StructuredDoc embedded = buildEmbeddedPhoneDoc(text, lang);
            if (embedded == null && text.contains("\\\"")) {
                embedded = buildEmbeddedPhoneDoc(text.replace("\\\"", "\"").replace("\\/", "/"), lang);
            }
            if (embedded != null) return embedded;
        } catch (Exception ignored) {}
        if (!text.startsWith("{") && !text.startsWith("[")) return null;
        try {
            if (text.startsWith("{")) {
                JSONObject json = unwrapModuleOutput(new JSONObject(text));
                if (isPhoneCheckReport(json)) return buildPhoneCheckDoc(json, lang);
                return buildGenericJsonDoc(json, lang);
            }
            JSONArray arr = new JSONArray(text);
            boolean en = isEnglish(lang);
            List<Section> sections = new ArrayList<Section>();
            sections.add(new Section(en ? "Readable Summary" : "可读摘要",
                    en ? "Detected a JSON array with " + arr.length() + " items. The image shows a concise view instead of raw data."
                            : "识别到 JSON 数组，共 " + arr.length() + " 项。长图会优先展示摘要，不再直接铺满原始字段。",
                    STYLE_HERO));
            for (int i = 0; i < Math.min(arr.length(), 8); i++) {
                sections.add(new Section((en ? "Item " : "第 ") + (i + 1) + (en ? "" : " 项"), summarizeJsonValue(arr.opt(i), 900, lang), STYLE_DETAILS));
            }
            if (arr.length() > 8) sections.add(new Section(en ? "More Items" : "更多内容",
                    en ? (arr.length() - 8) + " more items are hidden. Open details for the full data."
                            : "还有 " + (arr.length() - 8) + " 项未展开，请在详情中查看完整数据。",
                    STYLE_WARNING));
            return new StructuredDoc(en ? "Structured Data Report" : "结构化数据报告",
                    "JSON " + (en ? "array" : "数组") + " · " + arr.length() + (en ? " items" : " 项"),
                    sections, "json", en ? "Structured data" : "结构化数据");
        } catch (Exception ignored) {
            return null;
        }
    }

    private JSONObject unwrapModuleOutput(JSONObject json) {
        String out = json.optString("output", "").trim();
        if (json.has("success") && out.startsWith("{")) {
            try { return new JSONObject(out); } catch (Exception ignored) {}
        }
        return json;
    }

    private boolean isPhoneCheckReport(JSONObject json) {
        return "phone_check_report".equals(json.optString("module"))
                || (json.has("device") && (json.has("battery") || json.has("display") || json.has("storage") || json.has("memory") || json.has("cpu")));
    }

    private StructuredDoc buildEmbeddedPhoneDoc(String text, String lang) throws Exception {
        JSONObject merged = new JSONObject();
        int parsed = 0;
        for (int start = 0; start < text.length(); start++) {
            if (text.charAt(start) != '{') continue;
            int end = findJsonObjectEnd(text, start);
            if (end <= start) continue;
            String chunk = text.substring(start, end + 1);
            try {
                JSONObject obj = unwrapModuleOutput(parseJsonObjectLoose(chunk));
                if (isPhoneCheckReport(obj)) return buildPhoneCheckDoc(obj, lang);
                mergePhoneFragment(merged, obj, text.substring(Math.max(0, start - 48), start));
                parsed++;
                start = end;
            } catch (Exception ignored) {
                // Mixed report text can contain braces that are not JSON.
            }
            if (parsed >= 32) break;
        }
        return isPhoneCheckReport(merged) ? buildPhoneCheckDoc(merged, lang) : null;
    }

    private JSONObject parseJsonObjectLoose(String chunk) throws Exception {
        try {
            return new JSONObject(chunk);
        } catch (Exception first) {
            String loose = chunk.replace("\\\"", "\"").replace("\\/", "/");
            return new JSONObject(loose);
        }
    }

    private int findJsonObjectEnd(String text, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (ch == '\\') escaped = true;
                else if (ch == '"') inString = false;
                continue;
            }
            if (ch == '"') inString = true;
            else if (ch == '{') depth++;
            else if (ch == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private void mergePhoneFragment(JSONObject merged, JSONObject obj, String label) throws Exception {
        if (obj == null || obj.length() == 0) return;
        String[] known = new String[]{"verdict", "device", "cpu", "memory", "storage", "display", "battery", "sensors", "camera2", "drm", "apps", "storageSpeed"};
        boolean copiedKnown = false;
        for (String key : known) {
            if (obj.has(key)) {
                merged.put(key, obj.opt(key));
                copiedKnown = true;
            }
        }
        if (copiedKnown) return;
        String key = guessPhoneFragmentKey(label, obj);
        if (key.length() > 0 && !merged.has(key)) merged.put(key, obj);
    }

    private String guessPhoneFragmentKey(String label, JSONObject obj) {
        String l = (label == null ? "" : label).toLowerCase(Locale.ROOT);
        if (l.contains("设备") || l.contains("device") || obj.has("brand") || obj.has("model") || obj.has("androidVersion") || obj.has("sdkInt")) return "device";
        if (l.contains("cpu") || l.contains("处理器") || obj.has("processorCount") || obj.has("supportedAbis") || obj.has("abis")) return "cpu";
        if (l.contains("内存") || l.contains("memory") || obj.has("lowMemory") || obj.has("thresholdBytes")) return "memory";
        if (l.contains("存储") || l.contains("storage") || l.contains("空间") || obj.has("freeFormatted") || obj.has("availableFormatted")) return "storage";
        if (l.contains("屏幕") || l.contains("display") || obj.has("widthPx") || obj.has("heightPx") || obj.has("refreshRateHz") || obj.has("densityDpi")) return "display";
        if (l.contains("电池") || l.contains("battery") || obj.has("levelPercent") || obj.has("temperatureC") || obj.has("voltageMilliVolts")) return "battery";
        if (l.contains("传感") || l.contains("sensor") || obj.has("keySensors")) return "sensors";
        if (l.contains("camera") || l.contains("相机") || obj.has("cameraCount") || obj.has("backLevel")) return "camera2";
        if (l.contains("widevine") || l.contains("drm") || obj.has("securityLevel")) return "drm";
        if (l.contains("应用") || l.contains("app") || obj.has("installedCount") || obj.has("systemAppCount")) return "apps";
        if (l.contains("测速") || l.contains("speed") || obj.has("writeMBps") || obj.has("readMBps")) return "storageSpeed";
        if (obj.has("totalBytes") && obj.has("availableBytes")) return "storage";
        return "";
    }

    private StructuredDoc buildPhoneCheckDoc(JSONObject r, String lang) throws Exception {
        boolean en = isEnglish(lang);
        JSONObject verdict = r.optJSONObject("verdict");
        JSONObject device = r.optJSONObject("device");
        JSONObject cpu = r.optJSONObject("cpu");
        JSONObject memory = r.optJSONObject("memory");
        JSONObject storage = r.optJSONObject("storage");
        JSONObject primary = firstStorage(storage);
        JSONObject display = r.optJSONObject("display");
        JSONObject battery = r.optJSONObject("battery");
        JSONObject sensors = r.optJSONObject("sensors");
        JSONObject camera = r.optJSONObject("camera2");
        JSONObject drm = r.optJSONObject("drm");
        JSONObject apps = r.optJSONObject("apps");
        JSONObject speed = r.optJSONObject("storageSpeed");

        String deviceName = deviceName(device);
        String android = device != null ? "Android " + valueOr(device, "androidVersion", "-") + " / API " + device.optInt("sdkInt", 0) : "-";
        String score = verdict != null && verdict.has("score") ? verdict.optInt("score") + "/100" : estimateScore(primary, battery, drm);
        String status = friendlyStatus(score, en);
        String conclusion = friendlyConclusion(score, primary, battery, drm, en);

        List<Section> sections = new ArrayList<Section>();
        sections.add(new Section(en ? "Main Takeaway" : "一句话结论",
                kv(en ? "Result" : "结论", status, conclusion) + "\n"
                        + kv(en ? "Device" : "设备", deviceName, android) + "\n"
                        + kv(en ? "Report Score" : "报告评分", score, en ? "For comparison only" : "用于留存和复测对比"),
                STYLE_HERO));

        sections.add(new Section(en ? "At A Glance" : "关键指标",
                kv(en ? "Display" : "屏幕", displaySummary(display, en), en ? "Resolution / refresh rate / DPI" : "分辨率 / 刷新率 / DPI") + "\n"
                        + kv(en ? "Battery" : "电池", batterySummary(battery, en), en ? "Level / temperature / health" : "电量 / 温度 / 健康") + "\n"
                        + kv(en ? "Memory" : "内存", memorySummary(memory, en), en ? "Available and used percentage" : "可用空间和占用比例") + "\n"
                        + kv(en ? "Storage" : "存储", storageSummary(primary, en), en ? "Internal shared storage" : "内部共享存储") + "\n"
                        + kv(en ? "Speed" : "存储测速", speedSummary(speed, en), en ? "Read / write quick test" : "轻量读写测试") + "\n"
                        + kv(en ? "DRM" : "视频权益", drmSummary(drm, en), en ? "Widevine level" : "Widevine 等级"),
                STYLE_METRICS));

        sections.add(new Section(en ? "What To Check" : "重点建议",
                risksText(r.optJSONObject("verdict") != null ? r.optJSONObject("verdict").optJSONArray("risks") : null, primary, battery, drm, en),
                STYLE_WARNING));

        sections.add(new Section(en ? "Hardware & System" : "硬件与系统",
                kv(en ? "Model" : "机型", deviceName, "") + "\n"
                        + kv(en ? "System" : "系统", android, "") + "\n"
                        + kv(en ? "Build" : "版本号", shortText(device != null ? valueOr(device, "buildNumber", "-") : "-", 80), "") + "\n"
                        + kv(en ? "CPU" : "处理器", cpuSummary(cpu, en), "") + "\n"
                        + kv(en ? "ABI" : "架构", firstArray(cpu != null ? cpu.optJSONArray("abis") : null, "-"), "") + "\n"
                        + kv(en ? "Fingerprint" : "系统指纹", en ? "Hidden in share image; available in details when needed." : "分享图中已隐藏，验机核对时可在详情里查看。", ""),
                STYLE_DETAILS));

        sections.add(new Section(en ? "Screen & Sensors" : "屏幕与传感器",
                kv(en ? "Display" : "屏幕", displaySummary(display, en), "") + "\n"
                        + kv(en ? "DPI" : "DPI", display != null ? display.optInt("densityDpi") + " dpi" : "-", "") + "\n"
                        + kv(en ? "Sensors" : "传感器数量", sensors != null ? sensors.optInt("count") + (en ? " sensors" : " 个") : "-", "") + "\n"
                        + kv(en ? "Key sensors" : "关键传感器", sensorSummary(sensors, en), ""),
                STYLE_DETAILS));

        sections.add(new Section(en ? "Camera, DRM & Apps" : "相机、视频权益与应用",
                kv("Camera2", cameraSummary(camera, en), "") + "\n"
                        + kv("Widevine", drmSummary(drm, en), "") + "\n"
                        + kv(en ? "Installed apps" : "已安装应用", appsSummary(apps, en), "") + "\n"
                        + kv(en ? "Preload watchlist" : "预装关注项", watchListSummary(apps != null ? apps.optJSONArray("preinstallWatchList") : null, en), ""),
                STYLE_DETAILS));

        String title = en ? "Phone Inspection Report" : "手机验机报告";
        String subtitle = deviceName + " · " + android;
        return new StructuredDoc(title, subtitle, sections, "phone_check_report", en ? "Readable phone report" : "面向用户的验机报告");
    }

    private StructuredDoc buildPhoneCheckDocLegacy(JSONObject r) throws Exception {
        JSONObject verdict = r.optJSONObject("verdict");
        JSONObject device = r.optJSONObject("device");
        JSONObject cpu = r.optJSONObject("cpu");
        JSONObject memory = r.optJSONObject("memory");
        JSONObject storage = r.optJSONObject("storage");
        JSONObject primary = storage != null ? storage.optJSONObject("primary") : null;
        JSONObject display = r.optJSONObject("display");
        JSONObject battery = r.optJSONObject("battery");
        JSONObject sensors = r.optJSONObject("sensors");
        JSONObject camera = r.optJSONObject("camera2");
        JSONObject drm = r.optJSONObject("drm");
        JSONObject apps = r.optJSONObject("apps");
        JSONObject speed = r.optJSONObject("storageSpeed");

        String deviceName = deviceName(device);
        String android = device != null ? "Android " + valueOr(device, "androidVersion", "-") + " / API " + device.optInt("sdkInt", 0) : "-";
        String score = verdict != null && verdict.has("score") ? verdict.optInt("score") + "/100" : "-";
        String title = verdict != null ? valueOr(verdict, "title", "已生成验机报告") : "已生成验机报告";
        JSONArray risks = verdict != null ? verdict.optJSONArray("risks") : null;

        List<Section> sections = new ArrayList<Section>();
        sections.add(new Section("核心判断",
                joinLines(
                        "结论：" + title + "，验机评分 " + score,
                        "设备：" + deviceName,
                        "系统：" + android,
                        "说明：这是一份给人看的留存/分享报告，已隐藏 CPU flags、完整指纹等超长原始字段。",
                        "建议：买新机、二手机交易、刷机后复测时，可以再次生成报告做对比。"
                )));

        sections.add(new Section("关键指标",
                joinLines(
                        metricLine("屏幕", displaySummary(display)),
                        metricLine("电池", batterySummary(battery)),
                        metricLine("内存", memorySummary(memory)),
                        metricLine("存储", storageSummary(primary)),
                        metricLine("存储测速", speedSummary(speed)),
                        metricLine("DRM", drmSummary(drm)),
                        metricLine("应用", appsSummary(apps))
                )));

        sections.add(new Section("风险与建议", risksText(risks, primary, battery, drm)));

        sections.add(new Section("硬件与系统",
                joinLines(
                        metricLine("品牌/型号", deviceName),
                        metricLine("厂商", device != null ? valueOr(device, "manufacturer", "-") : "-"),
                        metricLine("系统版本", android),
                        metricLine("版本号", shortText(device != null ? valueOr(device, "buildNumber", "-") : "-", 80)),
                        metricLine("CPU", cpuSummary(cpu)),
                        metricLine("ABI", firstArray(cpu != null ? cpu.optJSONArray("abis") : null, "-")),
                        metricLine("系统指纹", "已省略长串，可在原始详情中用于售后/刷机比对")
                )));

        sections.add(new Section("屏幕与传感器",
                joinLines(
                        metricLine("屏幕参数", displaySummary(display)),
                        metricLine("DPI", display != null ? display.optInt("densityDpi") + " dpi" : "-"),
                        metricLine("传感器数量", sensors != null ? sensors.optInt("count") + " 个" : "-"),
                        metricLine("关键传感器", sensorSummary(sensors))
                )));

        sections.add(new Section("相机与影音权益",
                joinLines(
                        metricLine("Camera2", cameraSummary(camera)),
                        metricLine("Widevine", drmSummary(drm)),
                        metricLine("提醒", "如果 Widevine 不是 L1，高清视频权益和部分流媒体清晰度需要重点复核。")
                )));

        sections.add(new Section("应用与预装关注",
                joinLines(
                        metricLine("已安装应用", apps != null ? apps.optInt("installedCount") + " 个" : "-"),
                        metricLine("系统应用", apps != null ? apps.optInt("systemAppCount") + " 个" : "-"),
                        metricLine("预装关注项", watchListSummary(apps != null ? apps.optJSONArray("preinstallWatchList") : null))
                )));

        return new StructuredDoc("手机验机报告", deviceName + " · " + android, sections, "phone_check_report", "结构化验机报告");
    }

    private StructuredDoc buildGenericJsonDoc(JSONObject json, String lang) throws Exception {
        boolean en = isEnglish(lang);
        JSONArray names = json.names();
        int count = names == null ? 0 : names.length();
        List<Section> sections = new ArrayList<Section>();

        sections.add(new Section(en ? "Readable Summary" : "可读摘要",
                kv(en ? "Data type" : "数据类型", "JSON object", en ? count + " top-level fields" : count + " 个顶层字段") + "\n"
                        + kv(en ? "View mode" : "展示方式", en ? "Summary first" : "先看结论", en ? "Raw fields are folded to avoid debug-style output." : "原始字段会折叠摘要，避免变成调试日志。"),
                STYLE_HERO));

        StringBuilder keyMetrics = new StringBuilder();
        if (names != null) {
            int added = 0;
            for (int i = 0; i < names.length() && added < 8; i++) {
                String key = names.optString(i);
                if (isNoisyKey(key)) continue;
                if (keyMetrics.length() > 0) keyMetrics.append('\n');
                keyMetrics.append(kv(humanKey(key, lang), summarizeScalar(json.opt(key), lang), en ? "Tap details for raw data" : "完整内容可在详情中查看"));
                added++;
            }
        }
        sections.add(new Section(en ? "Key Fields" : "关键字段", keyMetrics.length() == 0 ? (en ? "No readable fields found." : "没有识别到适合展示的字段。") : keyMetrics.toString(), STYLE_METRICS));

        StringBuilder details = new StringBuilder();
        if (names != null) {
            int added = 0;
            for (int i = 0; i < names.length() && added < 10; i++) {
                String key = names.optString(i);
                if (isNoisyKey(key)) continue;
                if (details.length() > 0) details.append('\n');
                details.append(kv(humanKey(key, lang), summarizeJsonValue(json.opt(key), 420, lang), ""));
                added++;
            }
        }
        sections.add(new Section(en ? "Details" : "详情摘要", details.toString(), STYLE_DETAILS));
        sections.add(new Section(en ? "Tip" : "阅读提示",
                en ? "This image is designed for sharing and comparison. Full raw JSON is intentionally hidden to keep the report understandable."
                        : "这张图面向分享和复测对比，完整原始 JSON 会刻意隐藏，避免普通用户看不懂。",
                STYLE_WARNING));

        return new StructuredDoc(en ? "Structured Data Report" : "结构化数据报告",
                "JSON " + (en ? "object" : "对象") + " · " + count + (en ? " fields" : " 个字段"),
                sections, "json", en ? "Structured data" : "结构化数据");
    }

    private StructuredDoc buildGenericJsonDocLegacy(JSONObject json) throws Exception {
        List<Section> sections = new ArrayList<Section>();
        JSONArray names = json.names();
        int count = names == null ? 0 : names.length();
        sections.add(new Section("结构化数据概览", "识别到 JSON 对象，共 " + count + " 个顶层字段。长图已自动改为摘要视图，避免把原始字段连续铺满屏幕。"));
        if (names != null) {
            for (int i = 0; i < Math.min(names.length(), 10); i++) {
                String key = names.optString(i);
                sections.add(new Section(humanKey(key), summarizeJsonValue(json.opt(key), 1000)));
            }
            if (names.length() > 10) sections.add(new Section("更多字段", "还有 " + (names.length() - 10) + " 个字段未展开，请查看原始详情。"));
        }
        return new StructuredDoc("结构化数据长图", "JSON 对象 · " + count + " 个字段", sections, "json", "结构化数据");
    }

    private boolean isEnglish(String lang) {
        return lang != null && lang.toLowerCase(Locale.ROOT).startsWith("en");
    }

    private String resolveLanguage(JSONObject params, String text) {
        String explicit = params.optString("language", params.optString("lang", params.optString("locale", ""))).trim().toLowerCase(Locale.ROOT);
        if (explicit.startsWith("en")) return "en";
        if (explicit.startsWith("zh") || explicit.startsWith("cn")) return "zh";
        String source = text == null ? "" : text;
        int cjk = 0;
        int latin = 0;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if ((c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF)) cjk++;
            else if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) latin++;
        }
        if (cjk > 0) return "zh";
        return latin > 80 ? "en" : "zh";
    }

    private String kv(String label, String value, String note) {
        return nonEmpty(label, "-") + "\t" + nonEmpty(value, "-") + "\t" + nonEmpty(note, "");
    }

    private JSONObject firstStorage(JSONObject storage) {
        if (storage == null) return null;
        JSONObject primary = storage.optJSONObject("primary");
        if (primary != null) return primary;
        JSONObject external = storage.optJSONObject("externalPrimary");
        if (external != null) return external;
        JSONObject internal = storage.optJSONObject("internal");
        if (internal != null) return internal;
        if (storage.has("totalBytes") || storage.has("availableBytes") || storage.has("freeBytes")) return storage;
        return storage.optJSONObject("data");
    }

    private String estimateScore(JSONObject storage, JSONObject battery, JSONObject drm) {
        int score = 92;
        if (storage != null && storage.optDouble("usedPercent", 0) >= 80) score -= 8;
        if (battery != null && !battery.isNull("temperatureC") && battery.optDouble("temperatureC") >= 40) score -= 8;
        if (drm != null && drm.optBoolean("widevine", false) && !"L1".equalsIgnoreCase(drm.optString("securityLevel", ""))) score -= 5;
        return Math.max(60, score) + "/100";
    }

    private String friendlyStatus(String scoreText, boolean en) {
        int score = parseScore(scoreText);
        if (score >= 90) return en ? "Looks good" : "状态良好";
        if (score >= 75) return en ? "Usable, check notes" : "整体可用，建议看重点";
        return en ? "Needs attention" : "需要重点复核";
    }

    private String friendlyConclusion(String scoreText, JSONObject storage, JSONObject battery, JSONObject drm, boolean en) {
        int score = parseScore(scoreText);
        if (score >= 90) {
            return en ? "No obvious issue found in the collected checks. Keep this report for future comparison."
                    : "本次采集未发现明显异常，建议保存这张图，后续刷机、交易或维修后可复测对比。";
        }
        if (storage != null && storage.optDouble("usedPercent", 0) >= 80) {
            return en ? "Storage usage is high. Clean large files and APKs before retesting."
                    : "存储占用偏高，建议先清理大文件、安装包和重复媒体，再复测。";
        }
        if (battery != null && !battery.isNull("temperatureC") && battery.optDouble("temperatureC") >= 40) {
            return en ? "Battery temperature is high. Let the phone cool down before performance or charging tests."
                    : "电池温度偏高，建议降温后再测游戏、充电或跑分。";
        }
        if (drm != null && drm.optBoolean("widevine", false) && !"L1".equalsIgnoreCase(drm.optString("securityLevel", ""))) {
            return en ? "Widevine is not L1. Verify HD streaming support if that matters to you."
                    : "Widevine 不是 L1，建议核对高清视频权益。";
        }
        return en ? "Some values need a second look. Review the highlighted suggestions below."
                : "部分项目需要进一步复核，优先看下面的重点建议。";
    }

    private int parseScore(String scoreText) {
        if (scoreText == null) return 0;
        Matcher m = Pattern.compile("(\\d{1,3})").matcher(scoreText);
        if (!m.find()) return 0;
        try { return Integer.parseInt(m.group(1)); } catch (Exception e) { return 0; }
    }

    private String displaySummary(JSONObject display, boolean en) {
        if (display == null) return "-";
        return display.optInt("widthPx") + "x" + display.optInt("heightPx")
                + " / " + trimDouble(display.optDouble("refreshRateHz")) + "Hz"
                + " / " + display.optInt("densityDpi") + "dpi";
    }

    private String batterySummary(JSONObject battery, boolean en) {
        if (battery == null) return en ? "Not available" : "未读取到";
        String temp = battery.isNull("temperatureC") ? "-" : trimDouble(battery.optDouble("temperatureC")) + "℃";
        return battery.optInt("levelPercent") + "% · " + temp + " · " + valueOr(battery, "health", "-");
    }

    private String memorySummary(JSONObject memory, boolean en) {
        if (memory == null) return en ? "Not available" : "未读取到";
        return formatBytes(memory.optLong("availableBytes")) + (en ? " free / " : " 可用 / ")
                + formatBytes(memory.optLong("totalBytes"))
                + " / " + trimDouble(memory.optDouble("usedPercent")) + "%";
    }

    private String storageSummary(JSONObject storage, boolean en) {
        if (storage == null) return en ? "Not available" : "未读取到";
        return formatBytes(storage.optLong("availableBytes")) + (en ? " free / " : " 可用 / ")
                + formatBytes(storage.optLong("totalBytes"))
                + " / " + trimDouble(storage.optDouble("usedPercent")) + "%";
    }

    private String speedSummary(JSONObject speed, boolean en) {
        if (speed == null) return en ? "Not tested" : "未测速";
        if (!speed.optBoolean("available", true)) return (en ? "Skipped: " : "已跳过：") + shortText(speed.optString("note", en ? "unavailable" : "不可用"), 80);
        return (en ? "Write " : "写入 ") + trimDouble(speed.optDouble("writeMBps")) + " MB/s · "
                + (en ? "Read " : "读取 ") + trimDouble(speed.optDouble("readMBps")) + " MB/s";
    }

    private String drmSummary(JSONObject drm, boolean en) {
        if (drm == null) return "-";
        if (!drm.optBoolean("widevine", false)) return en ? "Widevine unavailable" : "Widevine 不可用";
        return "Widevine " + nonEmpty(drm.optString("securityLevel", ""), en ? "available" : "可用");
    }

    private String appsSummary(JSONObject apps, boolean en) {
        if (apps == null) return en ? "Not available" : "未读取到";
        JSONArray watch = apps.optJSONArray("preinstallWatchList");
        return apps.optInt("installedCount") + (en ? " installed · " : " 个 · ")
                + apps.optInt("systemAppCount") + (en ? " system · " : " 个系统应用 · ")
                + (watch != null ? watch.length() : 0) + (en ? " flagged" : " 个关注项");
    }

    private String cpuSummary(JSONObject cpu, boolean en) {
        if (cpu == null) return "-";
        return cpu.optInt("cores") + (en ? " cores · " : " 核 · ") + valueOr(cpu, "arch", "-");
    }

    private String sensorSummary(JSONObject sensors, boolean en) {
        if (sensors == null) return "-";
        JSONObject key = sensors.optJSONObject("keySensors");
        if (key == null) return sensors.optInt("count") + (en ? " sensors" : " 个");
        if (en) {
            return "Accel " + yesNoEn(key.optBoolean("accelerometer"))
                    + ", Gyro " + yesNoEn(key.optBoolean("gyroscope"))
                    + ", Compass " + yesNoEn(key.optBoolean("magnetic"))
                    + ", Light " + yesNoEn(key.optBoolean("light"))
                    + ", Proximity " + yesNoEn(key.optBoolean("proximity"));
        }
        return "加速度 " + yesNo(key.optBoolean("accelerometer"))
                + "，陀螺仪 " + yesNo(key.optBoolean("gyroscope"))
                + "，磁力计 " + yesNo(key.optBoolean("magnetic"))
                + "，光线 " + yesNo(key.optBoolean("light"))
                + "，距离 " + yesNo(key.optBoolean("proximity"));
    }

    private String cameraSummary(JSONObject camera, boolean en) {
        if (camera == null) return "-";
        JSONArray cameras = camera.optJSONArray("cameras");
        if (cameras == null || cameras.length() == 0) return en ? "No Camera2 data" : "未读取到 Camera2 信息";
        StringBuilder sb = new StringBuilder(camera.optInt("count", cameras.length()) + (en ? " cameras" : " 个摄像头"));
        for (int i = 0; i < Math.min(cameras.length(), 3); i++) {
            JSONObject c = cameras.optJSONObject(i);
            if (c == null) continue;
            sb.append(en ? "; " : "；")
                    .append("front".equals(c.optString("facing")) ? (en ? "front" : "前摄") : (en ? "back" : "后摄"))
                    .append(" ").append(c.optString("id"))
                    .append(" ").append(c.optString("hardwareLevel", "-"));
        }
        return sb.toString();
    }

    private String watchListSummary(JSONArray watch, boolean en) {
        if (watch == null || watch.length() == 0) return en ? "No obvious preload item flagged" : "未发现明显预装关注项";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(watch.length(), 5); i++) {
            if (i > 0) sb.append(en ? ", " : "、");
            sb.append(watch.optString(i));
        }
        if (watch.length() > 5) sb.append(en ? " and " : " 等 ").append(watch.length() - 5).append(en ? " more" : " 个");
        return sb.toString();
    }

    private String risksText(JSONArray risks, JSONObject storage, JSONObject battery, JSONObject drm, boolean en) {
        StringBuilder sb = new StringBuilder();
        if (risks != null) {
            for (int i = 0; i < risks.length(); i++) {
                String item = risks.optString(i);
                if (!item.trim().isEmpty()) sb.append("• ").append(item).append('\n');
            }
        }
        if (storage != null && storage.optDouble("usedPercent") >= 80) {
            sb.append(en ? "• Storage usage is high. Clean large files, APKs and duplicate media, then retest.\n"
                    : "• 存储占用偏高，建议先清理大文件、安装包和重复媒体，再复测。\n");
        }
        if (battery != null && !battery.isNull("temperatureC") && battery.optDouble("temperatureC") >= 40) {
            sb.append(en ? "• Battery temperature is high. Let it cool before gaming, charging or benchmark tests.\n"
                    : "• 电池温度偏高，建议静置降温后再测游戏、充电或跑分。\n");
        }
        if (drm != null && drm.optBoolean("widevine", false) && !"L1".equalsIgnoreCase(drm.optString("securityLevel", ""))) {
            sb.append(en ? "• Widevine is not L1. Check HD streaming support before buying or reselling.\n"
                    : "• Widevine 不是 L1，买二手机或看高清视频前建议重点核对。\n");
        }
        if (sb.length() == 0) {
            sb.append(en ? "• No obvious issue found. Save this image and compare it after flashing, repairing, or reselling."
                    : "• 未发现明显异常。建议保存这张图，刷机、维修、二手机交易后可以复测对比。");
        }
        return sb.toString().trim();
    }

    private String summarizeJsonValue(Object value, int maxLen, String lang) {
        if (value == null || value == JSONObject.NULL) return "-";
        if (value instanceof JSONObject) {
            JSONObject obj = (JSONObject) value;
            JSONArray names = obj.names();
            if (names == null || names.length() == 0) return "{}";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(names.length(), 8); i++) {
                String key = names.optString(i);
                if (isNoisyKey(key)) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(kv(humanKey(key, lang), shortText(summarizeScalar(obj.opt(key), lang), 100), ""));
            }
            return shortText(sb.toString(), maxLen);
        }
        if (value instanceof JSONArray) {
            JSONArray arr = (JSONArray) value;
            return isEnglish(lang) ? arr.length() + " items" : arr.length() + " 项";
        }
        return shortText(String.valueOf(value), maxLen);
    }

    private String summarizeScalar(Object value, String lang) {
        if (value == null || value == JSONObject.NULL) return "-";
        if (value instanceof JSONObject) {
            JSONObject obj = (JSONObject) value;
            JSONArray names = obj.names();
            int count = names == null ? 0 : names.length();
            return isEnglish(lang) ? "Object · " + count + " fields" : "对象 · " + count + " 个字段";
        }
        if (value instanceof JSONArray) return isEnglish(lang) ? "Array · " + ((JSONArray) value).length() + " items" : "数组 · " + ((JSONArray) value).length() + " 项";
        return shortText(String.valueOf(value).replace('\n', ' ').replace('\t', ' '), 120);
    }

    private String humanKey(String key, String lang) {
        boolean en = isEnglish(lang);
        if ("device".equals(key)) return en ? "Device" : "设备";
        if ("display".equals(key)) return en ? "Display" : "屏幕";
        if ("battery".equals(key)) return en ? "Battery" : "电池";
        if ("memory".equals(key)) return en ? "Memory" : "内存";
        if ("storage".equals(key)) return en ? "Storage" : "存储";
        if ("camera2".equals(key)) return "Camera2";
        if ("drm".equals(key)) return "DRM";
        if ("apps".equals(key)) return en ? "Apps" : "应用";
        if ("verdict".equals(key)) return en ? "Verdict" : "结论";
        if ("sensors".equals(key)) return en ? "Sensors" : "传感器";
        if ("storageSpeed".equals(key)) return en ? "Storage Speed" : "存储测速";
        if ("cpu".equals(key)) return "CPU";
        return key == null ? "" : key.replace('_', ' ');
    }

    private boolean isNoisyKey(String key) {
        if (key == null) return false;
        String k = key.toLowerCase(Locale.ROOT);
        return k.contains("fingerprint") || k.contains("cpuinfo") || k.contains("features")
                || k.contains("buildincremental") || k.equals("names");
    }

    private String yesNoEn(boolean value) {
        return value ? "yes" : "no";
    }

    private String summarizeJsonValue(Object value, int maxLen) {
        if (value == null || value == JSONObject.NULL) return "-";
        if (value instanceof JSONObject) {
            JSONObject obj = (JSONObject) value;
            JSONArray names = obj.names();
            if (names == null || names.length() == 0) return "{}";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(names.length(), 12); i++) {
                String key = names.optString(i);
                sb.append("• ").append(humanKey(key)).append("：").append(shortText(summarizeScalar(obj.opt(key)), 120)).append('\n');
            }
            if (names.length() > 12) sb.append("• 其余字段：").append(names.length() - 12).append(" 项\n");
            return shortText(sb.toString().trim(), maxLen);
        }
        if (value instanceof JSONArray) {
            JSONArray arr = (JSONArray) value;
            if (arr.length() == 0) return "空数组";
            StringBuilder sb = new StringBuilder("共 " + arr.length() + " 项");
            for (int i = 0; i < Math.min(arr.length(), 8); i++) {
                sb.append("\n• ").append(shortText(summarizeScalar(arr.opt(i)), 120));
            }
            if (arr.length() > 8) sb.append("\n• 其余 ").append(arr.length() - 8).append(" 项已省略");
            return shortText(sb.toString(), maxLen);
        }
        return shortText(String.valueOf(value), maxLen);
    }

    private String summarizeScalar(Object value) {
        if (value == null || value == JSONObject.NULL) return "-";
        if (value instanceof JSONObject) return "对象 " + (((JSONObject) value).names() != null ? ((JSONObject) value).names().length() : 0) + " 字段";
        if (value instanceof JSONArray) return "数组 " + ((JSONArray) value).length() + " 项";
        return String.valueOf(value).replace('\n', ' ').replace('\t', ' ');
    }

    private String humanKey(String key) {
        if ("device".equals(key)) return "设备";
        if ("display".equals(key)) return "屏幕";
        if ("battery".equals(key)) return "电池";
        if ("memory".equals(key)) return "内存";
        if ("storage".equals(key)) return "存储";
        if ("camera2".equals(key)) return "Camera2";
        if ("drm".equals(key)) return "DRM";
        if ("apps".equals(key)) return "应用";
        if ("verdict".equals(key)) return "结论";
        if ("sensors".equals(key)) return "传感器";
        if ("storageSpeed".equals(key)) return "存储测速";
        return key == null ? "" : key.replace('_', ' ');
    }

    private String joinLines(String... lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (line == null) continue;
            String t = line.trim();
            if (t.isEmpty() || t.endsWith("：-")) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(t);
        }
        return sb.toString();
    }

    private String metricLine(String label, String value) {
        return "• " + label + "：" + nonEmpty(value, "-");
    }

    private String deviceName(JSONObject d) {
        if (d == null) return "-";
        return nonEmpty((d.optString("brand") + " " + d.optString("model")).trim(), valueOr(d, "device", "-"));
    }

    private String displaySummary(JSONObject display) {
        if (display == null) return "-";
        return display.optInt("widthPx") + "x" + display.optInt("heightPx")
                + " / " + trimDouble(display.optDouble("refreshRateHz")) + "Hz"
                + " / " + display.optInt("densityDpi") + "dpi";
    }

    private String batterySummary(JSONObject battery) {
        if (battery == null) return "-";
        String temp = battery.isNull("temperatureC") ? "-" : trimDouble(battery.optDouble("temperatureC")) + "℃";
        return battery.optInt("levelPercent") + "%，" + temp + "，健康 " + valueOr(battery, "health", "-") + "，" + valueOr(battery, "status", "-");
    }

    private String memorySummary(JSONObject memory) {
        if (memory == null) return "-";
        return formatBytes(memory.optLong("availableBytes")) + " 可用 / " + formatBytes(memory.optLong("totalBytes"))
                + " / " + trimDouble(memory.optDouble("usedPercent")) + "%";
    }

    private String storageSummary(JSONObject storage) {
        if (storage == null) return "-";
        return formatBytes(storage.optLong("availableBytes")) + " 可用 / " + formatBytes(storage.optLong("totalBytes"))
                + " / " + trimDouble(storage.optDouble("usedPercent")) + "%";
    }

    private String speedSummary(JSONObject speed) {
        if (speed == null) return "未测速";
        if (!speed.optBoolean("available", true)) return "已跳过：" + shortText(speed.optString("note", "测速不可用"), 80);
        return "写 " + trimDouble(speed.optDouble("writeMBps")) + " MB/s，读 " + trimDouble(speed.optDouble("readMBps")) + " MB/s";
    }

    private String drmSummary(JSONObject drm) {
        if (drm == null) return "-";
        if (!drm.optBoolean("widevine", false)) return "Widevine 不可用";
        return "Widevine " + nonEmpty(drm.optString("securityLevel", ""), "可用");
    }

    private String appsSummary(JSONObject apps) {
        if (apps == null) return "-";
        JSONArray watch = apps.optJSONArray("preinstallWatchList");
        return apps.optInt("installedCount") + " 个，系统应用 " + apps.optInt("systemAppCount")
                + " 个，预装关注 " + (watch != null ? watch.length() : 0) + " 个";
    }

    private String cpuSummary(JSONObject cpu) {
        if (cpu == null) return "-";
        return cpu.optInt("cores") + " 核，" + valueOr(cpu, "arch", "-");
    }

    private String sensorSummary(JSONObject sensors) {
        if (sensors == null) return "-";
        JSONObject key = sensors.optJSONObject("keySensors");
        if (key == null) return sensors.optInt("count") + " 个";
        return "加速度 " + yesNo(key.optBoolean("accelerometer"))
                + "，陀螺仪 " + yesNo(key.optBoolean("gyroscope"))
                + "，磁力计 " + yesNo(key.optBoolean("magnetic"))
                + "，光线 " + yesNo(key.optBoolean("light"))
                + "，距离 " + yesNo(key.optBoolean("proximity"))
                + "，气压计 " + yesNo(key.optBoolean("barometer"));
    }

    private String cameraSummary(JSONObject camera) {
        if (camera == null) return "-";
        JSONArray cameras = camera.optJSONArray("cameras");
        if (cameras == null || cameras.length() == 0) return "未读取到 Camera2 信息";
        StringBuilder sb = new StringBuilder(camera.optInt("count", cameras.length()) + " 个摄像头");
        for (int i = 0; i < Math.min(cameras.length(), 4); i++) {
            JSONObject c = cameras.optJSONObject(i);
            if (c == null) continue;
            sb.append("\n• ").append("front".equals(c.optString("facing")) ? "前摄" : "后摄")
                    .append(" ").append(c.optString("id"))
                    .append("：").append(c.optString("hardwareLevel", "-"));
        }
        if (cameras.length() > 4) sb.append("\n• 其余 ").append(cameras.length() - 4).append(" 个已省略");
        return sb.toString();
    }

    private String watchListSummary(JSONArray watch) {
        if (watch == null || watch.length() == 0) return "未发现明显预装关注项";
        StringBuilder sb = new StringBuilder("共 " + watch.length() + " 个");
        for (int i = 0; i < Math.min(watch.length(), 8); i++) {
            sb.append("\n• ").append(watch.optString(i));
        }
        if (watch.length() > 8) sb.append("\n• 其余 ").append(watch.length() - 8).append(" 个已省略");
        return sb.toString();
    }

    private String risksText(JSONArray risks, JSONObject storage, JSONObject battery, JSONObject drm) {
        StringBuilder sb = new StringBuilder();
        if (risks != null) {
            for (int i = 0; i < risks.length(); i++) {
                String item = risks.optString(i);
                if (!item.trim().isEmpty()) sb.append("• ").append(item).append('\n');
            }
        }
        if (storage != null && storage.optDouble("usedPercent") >= 80) {
            sb.append("• 存储占用较高，建议先清理大文件、安装包和重复媒体，再复测。\n");
        }
        if (battery != null && !battery.isNull("temperatureC") && battery.optDouble("temperatureC") >= 40) {
            sb.append("• 电池温度偏高，建议静置降温后再测游戏、充电或跑分。\n");
        }
        if (drm != null && !"L1".equalsIgnoreCase(drm.optString("securityLevel", ""))) {
            sb.append("• Widevine 不是 L1 或未读取到，建议核对高清视频权益。\n");
        }
        if (sb.length() == 0) sb.append("• 未发现明显异常。建议保留这张图，后续刷机、维修、二手机交易时可以做复测对比。");
        return sb.toString().trim();
    }

    private String firstArray(JSONArray arr, String fallback) {
        if (arr == null || arr.length() == 0) return fallback;
        return arr.optString(0, fallback);
    }

    private String valueOr(JSONObject obj, String key, String fallback) {
        if (obj == null) return fallback;
        String value = obj.optString(key, "");
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private String yesNo(boolean value) {
        return value ? "有" : "无";
    }

    private String trimDouble(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return "-";
        if (Math.abs(value - Math.round(value)) < 0.05) return String.valueOf((long) Math.round(value));
        return String.format(Locale.US, "%.1f", value);
    }

    private String formatBytes(long bytes) {
        if (bytes <= 0) return "-";
        double gb = bytes / 1024.0 / 1024.0 / 1024.0;
        if (gb >= 1) return String.format(Locale.US, "%.1f GB", gb);
        double mb = bytes / 1024.0 / 1024.0;
        return String.format(Locale.US, "%.0f MB", mb);
    }

    private String shortText(String value, int maxLen) {
        if (value == null) return "";
        String text = value.replace('\r', '\n').replaceAll("\\s*\\n\\s*", "\n").trim();
        return text.length() > maxLen ? text.substring(0, Math.max(0, maxLen - 3)) + "..." : text;
    }

    private Layout buildLayout(String title, String subtitle, List<Section> sections, int maxHeight) {
        int y = 36 + headerHeight(title, subtitle) + 76;
        boolean truncated = false;
        for (Section s : sections) {
            int sectionHeight = estimateSectionHeight(s);
            if (y + sectionHeight + 170 > maxHeight) {
                truncated = true;
                break;
            }
            y += sectionHeight;
        }
        y += truncated ? 210 : 150;
        return new Layout(Math.max(900, Math.min(maxHeight, y)), truncated);
    }

    private void drawLongImage(Canvas canvas, Layout layout, String title, String subtitle, List<Section> sections, int theme) {
        canvas.drawColor(Color.rgb(244, 247, 246));
        Paint card = paint(1, Color.WHITE, false);
        card.setStyle(Paint.Style.FILL);
        card.setShadowLayer(8, 0, 3, Color.argb(28, 0, 0, 0));
        RectF root = new RectF(36, 36, WIDTH - 36, layout.height - 36);
        canvas.drawRoundRect(root, 32, 32, card);

        Paint accent = paint(1, theme, false);
        accent.setStyle(Paint.Style.FILL);
        int headerHeight = headerHeight(title, subtitle);
        canvas.drawRoundRect(new RectF(36, 36, WIDTH - 36, 36 + headerHeight), 32, 32, accent);

        int y = 126;
        y = drawWrapped(canvas, title, paint(54, Color.WHITE, true), MARGIN, y, WIDTH - MARGIN * 2, 66);
        if (!subtitle.isEmpty()) {
            y = drawWrapped(canvas, subtitle, paint(30, Color.argb(230, 255, 255, 255), false),
                    MARGIN, y + 10, WIDTH - MARGIN * 2, 42);
        }
        y = 36 + headerHeight + 76;

        for (int i = 0; i < sections.size(); i++) {
            Section s = sections.get(i);
            int estimate = estimateSectionHeight(s);
            if (y + estimate + 170 > layout.height) break;
            y = drawSection(canvas, s, i + 1, y, theme);
        }

        if (layout.truncated) {
            Paint warn = paint(30, Color.rgb(180, 112, 32), true);
            canvas.drawText("内容较长，已按最大高度截断，可提高 maxHeight 后重新生成", MARGIN, layout.height - 128, warn);
        }
        canvas.drawText(WATERMARK, MARGIN, layout.height - 82, paint(28, Color.rgb(120, 128, 124), false));
    }

    private int headerHeight(String title, String subtitle) {
        Paint titlePaint = paint(54, Color.WHITE, true);
        Paint subPaint = paint(30, Color.WHITE, false);
        int height = 88 + wrap(title, titlePaint, WIDTH - MARGIN * 2).size() * 66 + 34;
        if (subtitle != null && !subtitle.isEmpty()) {
            height += wrap(subtitle, subPaint, WIDTH - MARGIN * 2).size() * 42 + 14;
        }
        return Math.max(190, height);
    }

    private int estimateSectionHeight(Section s) {
        int titleLines = wrap(s.title, paint(36, Color.BLACK, true), WIDTH - MARGIN * 2 - 66).size();
        if (STYLE_HERO.equals(s.style)) {
            return 276 + titleLines * 48;
        }
        if (STYLE_METRICS.equals(s.style)) {
            int cardW = (WIDTH - MARGIN * 2 - 34) / 2;
            return 104 + titleLines * 48 + metricRowsHeight(nonBlankLines(s.body), cardW, 28) + 46;
        }
        if (STYLE_DETAILS.equals(s.style)) {
            int rowsHeight = 0;
            for (String line : nonBlankLines(s.body)) {
                String[] parts = splitKv(line);
                rowsHeight += Math.max(58, wrap(parts[1], paint(29, Color.DKGRAY, false), WIDTH - MARGIN * 2 - 310).size() * 36 + 20);
            }
            return 100 + titleLines * 48 + rowsHeight + 34;
        }
        if (STYLE_WARNING.equals(s.style)) {
            return 112 + titleLines * 48 + wrap(s.body, paint(30, Color.rgb(92, 72, 38), false), WIDTH - MARGIN * 2 - 56).size() * 42 + 42;
        }
        return 110 + titleLines * 48
                + wrap(s.body, paint(31, Color.DKGRAY, false), WIDTH - MARGIN * 2 - 56).size() * 42;
    }

    private int drawSection(Canvas canvas, Section s, int index, int y, int theme) {
        if (STYLE_HERO.equals(s.style)) return drawHeroSection(canvas, s, y, theme);
        if (STYLE_METRICS.equals(s.style)) return drawMetricGridSection(canvas, s, index, y, theme);
        if (STYLE_WARNING.equals(s.style)) return drawWarningSection(canvas, s, index, y, theme);
        if (STYLE_DETAILS.equals(s.style)) return drawDetailsSection(canvas, s, index, y, theme);
        return drawPlainSection(canvas, s, index, y, theme);
    }

    private int drawPlainSection(Canvas canvas, Section s, int index, int y, int theme) {
        int estimate = estimateSectionHeight(s);
        Paint bg = fill(Color.rgb(248, 250, 249));
        RectF rect = new RectF(MARGIN, y - 42, WIDTH - MARGIN, y + estimate - 42);
        canvas.drawRoundRect(rect, 24, 24, bg);
        drawIndex(canvas, index, y, theme);
        y = drawWrapped(canvas, s.title, paint(36, Color.rgb(26, 32, 29), true), MARGIN + 66, y + 10, WIDTH - MARGIN * 2 - 66, 48);
        y = drawWrapped(canvas, s.body, paint(31, Color.rgb(70, 78, 74), false), MARGIN + 28, y + 20, WIDTH - MARGIN * 2 - 56, 42);
        return y + 64;
    }

    private int drawHeroSection(Canvas canvas, Section s, int y, int theme) {
        int height = estimateSectionHeight(s);
        RectF rect = new RectF(MARGIN, y - 42, WIDTH - MARGIN, y + height - 42);
        canvas.drawRoundRect(rect, 28, 28, fill(tint(theme, 0.08f)));
        canvas.drawRoundRect(new RectF(MARGIN, y - 42, MARGIN + 12, y + height - 42), 8, 8, fill(theme));
        y = drawWrapped(canvas, s.title, paint(36, Color.rgb(22, 38, 31), true), MARGIN + 34, y + 8, WIDTH - MARGIN * 2 - 68, 48);
        List<String> lines = nonBlankLines(s.body);
        if (!lines.isEmpty()) {
            String[] main = splitKv(lines.get(0));
            canvas.drawText(main[0], MARGIN + 34, y + 16, paint(26, Color.rgb(90, 102, 96), false));
            y = drawWrappedLimit(canvas, main[1], paint(48, theme, true), MARGIN + 34, y + 72, WIDTH - MARGIN * 2 - 68, 58, 2);
            if (!main[2].isEmpty()) y = drawWrappedLimit(canvas, main[2], paint(27, Color.rgb(72, 84, 78), false), MARGIN + 34, y + 8, WIDTH - MARGIN * 2 - 68, 38, 2);
        }
        int chipY = y + 28;
        for (int i = 1; i < Math.min(lines.size(), 4); i++) {
            String[] parts = splitKv(lines.get(i));
            int chipX = MARGIN + 34 + ((i - 1) % 2) * 455;
            int rowY = chipY + ((i - 1) / 2) * 82;
            RectF chip = new RectF(chipX, rowY, chipX + 420, rowY + 62);
            canvas.drawRoundRect(chip, 18, 18, fill(Color.WHITE));
            canvas.drawText(parts[0], chipX + 18, rowY + 25, paint(21, Color.rgb(115, 124, 119), false));
            drawWrappedLimit(canvas, parts[1], paint(26, Color.rgb(25, 33, 29), true), chipX + 18, rowY + 52, 384, 30, 1);
        }
        return (int) rect.bottom + 64;
    }

    private int drawMetricGridSection(Canvas canvas, Section s, int index, int y, int theme) {
        int height = estimateSectionHeight(s);
        RectF rect = new RectF(MARGIN, y - 42, WIDTH - MARGIN, y + height - 42);
        canvas.drawRoundRect(rect, 24, 24, fill(Color.WHITE));
        drawIndex(canvas, index, y, theme);
        y = drawWrapped(canvas, s.title, paint(36, Color.rgb(26, 32, 29), true), MARGIN + 66, y + 10, WIDTH - MARGIN * 2 - 66, 48);
        List<String> lines = nonBlankLines(s.body);
        int cardW = (WIDTH - MARGIN * 2 - 34) / 2;
        int x0 = MARGIN + 18;
        int rowTop = y + 16;
        for (int i = 0; i < lines.size(); i += 2) {
            String[] left = splitKv(lines.get(i));
            String[] right = (i + 1 < lines.size()) ? splitKv(lines.get(i + 1)) : null;
            int leftH = metricCardHeight(left, cardW);
            int rightH = right == null ? 0 : metricCardHeight(right, cardW);
            int rowH = Math.max(leftH, rightH);
            drawMetricCard(canvas, left, x0, rowTop, cardW, rowH, theme);
            if (right != null) drawMetricCard(canvas, right, x0 + cardW + 34, rowTop, cardW, rowH, theme);
            rowTop += rowH + 28;
        }
        return (int) rect.bottom + 64;
    }

    private int metricRowsHeight(List<String> lines, int cardW, int gap) {
        if (lines == null || lines.isEmpty()) return metricCardHeight(new String[]{"", "-", ""}, cardW);
        int total = 0;
        for (int i = 0; i < lines.size(); i += 2) {
            int left = metricCardHeight(splitKv(lines.get(i)), cardW);
            int right = (i + 1 < lines.size()) ? metricCardHeight(splitKv(lines.get(i + 1)), cardW) : 0;
            if (total > 0) total += gap;
            total += Math.max(left, right);
        }
        return total;
    }

    private int metricCardHeight(String[] parts, int cardW) {
        int inner = cardW - 44;
        int valueLines = limitedLineCount(parts.length > 1 ? parts[1] : "", paint(30, Color.rgb(26, 42, 34), true), inner, 3);
        int noteLines = (parts.length > 2 && !parts[2].isEmpty())
                ? limitedLineCount(parts[2], paint(21, Color.rgb(112, 122, 116), false), inner, 2)
                : 0;
        return Math.max(150, 34 + 18 + valueLines * 36 + (noteLines > 0 ? 14 + noteLines * 26 : 0) + 26);
    }

    private void drawMetricCard(Canvas canvas, String[] parts, int x, int top, int cardW, int cardH, int theme) {
        canvas.drawRoundRect(new RectF(x, top, x + cardW, top + cardH), 22, 22, fill(tint(theme, 0.07f)));
        canvas.drawText(parts[0], x + 22, top + 34, paint(22, Color.rgb(93, 106, 99), false));
        int valueBottom = drawWrappedLimit(canvas, parts[1], paint(30, Color.rgb(26, 42, 34), true), x + 22, top + 78, cardW - 44, 36, 3);
        if (!parts[2].isEmpty()) {
            drawWrappedLimit(canvas, parts[2], paint(21, Color.rgb(112, 122, 116), false), x + 22, valueBottom + 8, cardW - 44, 26, 2);
        }
    }

    private int drawWarningSection(Canvas canvas, Section s, int index, int y, int theme) {
        int height = estimateSectionHeight(s);
        RectF rect = new RectF(MARGIN, y - 42, WIDTH - MARGIN, y + height - 42);
        canvas.drawRoundRect(rect, 24, 24, fill(Color.rgb(255, 247, 232)));
        canvas.drawRoundRect(new RectF(MARGIN + 18, y - 22, MARGIN + 28, y + height - 62), 8, 8, fill(Color.rgb(224, 140, 46)));
        drawIndex(canvas, index, y, Color.rgb(224, 140, 46));
        y = drawWrapped(canvas, s.title, paint(36, Color.rgb(94, 62, 24), true), MARGIN + 66, y + 10, WIDTH - MARGIN * 2 - 66, 48);
        y = drawWrapped(canvas, s.body, paint(30, Color.rgb(92, 72, 38), false), MARGIN + 40, y + 20, WIDTH - MARGIN * 2 - 70, 42);
        return (int) rect.bottom + 64;
    }

    private int drawDetailsSection(Canvas canvas, Section s, int index, int y, int theme) {
        int height = estimateSectionHeight(s);
        RectF rect = new RectF(MARGIN, y - 42, WIDTH - MARGIN, y + height - 42);
        canvas.drawRoundRect(rect, 24, 24, fill(Color.rgb(249, 250, 250)));
        drawIndex(canvas, index, y, theme);
        y = drawWrapped(canvas, s.title, paint(36, Color.rgb(26, 32, 29), true), MARGIN + 66, y + 10, WIDTH - MARGIN * 2 - 66, 48);
        int rowY = y + 14;
        Paint linePaint = fill(Color.rgb(230, 236, 233));
        for (String line : nonBlankLines(s.body)) {
            String[] parts = splitKv(line);
            int rowH = Math.max(58, wrap(parts[1], paint(29, Color.rgb(48, 56, 52), false), WIDTH - MARGIN * 2 - 310).size() * 36 + 20);
            canvas.drawLine(MARGIN + 28, rowY - 10, WIDTH - MARGIN - 28, rowY - 10, linePaint);
            canvas.drawText(parts[0], MARGIN + 34, rowY + 30, paint(25, Color.rgb(103, 114, 108), false));
            drawWrapped(canvas, parts[1], paint(29, Color.rgb(48, 56, 52), false), MARGIN + 286, rowY + 30, WIDTH - MARGIN * 2 - 310, 36);
            rowY += rowH;
        }
        return (int) rect.bottom + 64;
    }

    private void drawIndex(Canvas canvas, int index, int y, int theme) {
        canvas.drawCircle(MARGIN + 30, y, 24, fill(theme));
        Paint indexPaint = paint(23, Color.WHITE, true);
        canvas.drawText(String.valueOf(index), MARGIN + (index >= 10 ? 15 : 23), y + 8, indexPaint);
    }

    private Paint fill(int color) {
        Paint p = paint(1, color, false);
        p.setStyle(Paint.Style.FILL);
        return p;
    }

    private int tint(int color, float amount) {
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);
        return Color.rgb((int) (255 - (255 - r) * amount), (int) (255 - (255 - g) * amount), (int) (255 - (255 - b) * amount));
    }

    private List<String> nonBlankLines(String text) {
        List<String> lines = new ArrayList<String>();
        if (text == null) return lines;
        String[] raw = text.split("\\n");
        for (String line : raw) {
            if (!line.trim().isEmpty()) lines.add(line.trim());
        }
        return lines;
    }

    private String[] splitKv(String line) {
        String[] parts = line == null ? new String[0] : line.split("\\t", -1);
        if (parts.length >= 3) return new String[]{parts[0], parts[1], parts[2]};
        if (parts.length == 2) return new String[]{parts[0], parts[1], ""};
        String value = line == null ? "" : line;
        int idx = value.indexOf('：');
        if (idx < 0) idx = value.indexOf(':');
        if (idx > 0) return new String[]{value.substring(0, idx).trim(), value.substring(idx + 1).trim(), ""};
        return new String[]{"", value, ""};
    }

    private List<Section> splitOversizedSections(List<Section> sections) {
        List<Section> out = new ArrayList<Section>();
        Paint bodyPaint = paint(31, Color.DKGRAY, false);
        int bodyWidth = WIDTH - MARGIN * 2 - 56;
        int maxLines = 15;
        for (Section s : sections) {
            int part = 1;
            int chunkLines = 0;
            StringBuilder chunk = new StringBuilder();
            String[] rawLines = (s.body == null ? "" : s.body).split("\\n", -1);
            for (String rawLine : rawLines) {
                List<String> wrapped = wrap(rawLine, bodyPaint, bodyWidth);
                int lineCount = Math.max(1, wrapped.size());
                if (lineCount > maxLines) {
                    if (chunk.length() > 0) {
                        out.add(new Section(continuedTitle(s.title, part++), chunk.toString(), s.style));
                        chunk.setLength(0);
                        chunkLines = 0;
                    }
                    for (int start = 0; start < wrapped.size(); start += maxLines) {
                        int end = Math.min(wrapped.size(), start + maxLines);
                        StringBuilder body = new StringBuilder();
                        for (int i = start; i < end; i++) {
                            body.append(wrapped.get(i));
                            if (i + 1 < end) body.append('\n');
                        }
                        out.add(new Section(continuedTitle(s.title, part++), body.toString(), s.style));
                    }
                    continue;
                }
                if (chunkLines > 0 && chunkLines + lineCount > maxLines) {
                    out.add(new Section(continuedTitle(s.title, part++), chunk.toString(), s.style));
                    chunk.setLength(0);
                    chunkLines = 0;
                }
                if (chunk.length() > 0) chunk.append('\n');
                chunk.append(rawLine);
                chunkLines += lineCount;
            }
            if (chunk.length() > 0 || rawLines.length == 0) {
                out.add(new Section(continuedTitle(s.title, part), chunk.toString(), s.style));
            }
        }
        return out;
    }

    private String continuedTitle(String title, int part) {
        return part == 1 ? title : title + "（续 " + part + "）";
    }

    private int drawWrappedLimit(Canvas canvas, String text, Paint p, int x, int y, int width, int lineHeight, int maxLines) {
        List<String> lines = wrap(text, p, width);
        int count = Math.min(lines.size(), Math.max(1, maxLines));
        for (int i = 0; i < count; i++) {
            String line = lines.get(i);
            if (i == count - 1 && lines.size() > count && line.length() > 1) {
                line = line.substring(0, Math.max(1, line.length() - 1)) + "…";
            }
            canvas.drawText(line, x, y, p);
            y += lineHeight;
        }
        return y;
    }

    private int drawWrapped(Canvas canvas, String text, Paint p, int x, int y, int width, int lineHeight) {
        List<String> lines = wrap(text, p, width);
        for (String line : lines) {
            canvas.drawText(line, x, y, p);
            y += lineHeight;
        }
        return y;
    }

    private int limitedLineCount(String text, Paint p, int width, int maxLines) {
        return Math.max(1, Math.min(wrap(text, p, width).size(), Math.max(1, maxLines)));
    }

    private List<String> wrap(String text, Paint p, int width) {
        List<String> out = new ArrayList<String>();
        if (text == null || text.length() == 0) {
            out.add("");
            return out;
        }
        String[] paragraphs = text.split("\\n", -1);
        for (String para : paragraphs) {
            if (para.length() == 0) {
                out.add("");
                continue;
            }
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < para.length(); i++) {
                char ch = para.charAt(i);
                String next = line.toString() + ch;
                if (p.measureText(next) > width && line.length() > 0) {
                    int breakAt = findWrapBreak(line);
                    if (breakAt > 0 && breakAt < line.length() - 1) {
                        out.add(line.substring(0, breakAt).trim());
                        String tail = line.substring(breakAt + 1).trim();
                        line.setLength(0);
                        line.append(tail);
                    } else {
                        out.add(line.toString());
                        line.setLength(0);
                    }
                }
                line.append(ch);
            }
            if (line.length() > 0) out.add(line.toString());
        }
        return out;
    }

    private int findWrapBreak(StringBuilder line) {
        for (int i = line.length() - 1; i >= 0; i--) {
            char ch = line.charAt(i);
            if (ch == ' ' || ch == '/' || ch == '-' || ch == ':' || ch == ';'
                    || ch == '\u00B7' || ch == '\uFF1A' || ch == '\uFF0C' || ch == '\u3001') {
                return i;
            }
        }
        return -1;
    }

    private List<Section> sectionsFromText(String raw, String type) {
        String text = normalizeText(raw);
        List<Section> sections = new ArrayList<Section>();
        if (text.isEmpty()) return sections;
        StructuredDoc structured = buildStructuredDoc(text);
        if (structured != null) return structured.sections;
        String[] lines = text.split("\\n");
        String currentTitle = "";
        StringBuilder body = new StringBuilder();
        boolean sawHeading = false;
        for (String line : lines) {
            String trimmed = line.trim();
            String heading = parseHeading(trimmed);
            if (heading.length() > 0) {
                sawHeading = true;
                if (body.length() > 0 || currentTitle.length() > 0) {
                    sections.add(new Section(nonEmpty(currentTitle, "内容"), body.toString().trim()));
                }
                currentTitle = heading;
                body.setLength(0);
            } else {
                body.append(line).append("\n");
            }
        }
        if (sawHeading) {
            if (body.length() > 0 || currentTitle.length() > 0) {
                sections.add(new Section(nonEmpty(currentTitle, "内容"), body.toString().trim()));
            }
            return sections;
        }

        String[] paragraphs = text.split("\\n\\s*\\n");
        StringBuilder chunk = new StringBuilder();
        int idx = 1;
        for (String p : paragraphs) {
            String part = p.trim();
            if (part.isEmpty()) continue;
            if (chunk.length() + part.length() > 900 && chunk.length() > 0) {
                sections.add(new Section("内容 " + idx++, chunk.toString().trim()));
                chunk.setLength(0);
            }
            chunk.append(part).append("\n\n");
        }
        if (chunk.length() > 0) sections.add(new Section(sections.isEmpty() ? "正文" : "内容 " + idx, chunk.toString().trim()));
        return sections;
    }

    private String parseHeading(String line) {
        if (line == null || line.length() == 0) return "";
        Matcher md = Pattern.compile("^#{1,6}\\s+(.+)$").matcher(line);
        if (md.find()) return md.group(1).trim();
        if (line.length() <= 34 && (line.endsWith("：") || line.endsWith(":"))) return line.substring(0, line.length() - 1).trim();
        return "";
    }

    private List<Section> parseSections(Object value, String fallbackText) {
        List<Section> out = new ArrayList<Section>();
        try {
            JSONArray arr = null;
            if (value instanceof JSONArray) arr = (JSONArray) value;
            else if (value instanceof String && ((String) value).trim().startsWith("[")) arr = new JSONArray((String) value);
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    Object item = arr.opt(i);
                    if (item instanceof JSONObject) {
                        JSONObject o = (JSONObject) item;
                        out.add(new Section(nonEmpty(o.optString("title", ""), "第 " + (i + 1) + " 段"),
                                o.optString("body", o.optString("content", ""))));
                    } else {
                        out.add(new Section("重点 " + (i + 1), String.valueOf(item)));
                    }
                }
                return out;
            }
        } catch (Exception ignored) {}
        String text = value instanceof String ? (String) value : fallbackText;
        return sectionsFromText(text, "text");
    }

    private File resolveOutputFile(String outputPath, String title) {
        String path = normalizePath(outputPath == null ? "" : outputPath.trim());
        if (path.isEmpty()) {
            path = "/storage/emulated/0/PandaGenie/long_images/" + safeName(title) + "_" + timeStamp() + ".png";
        }
        File out = new File(path);
        if (path.endsWith("/") || (out.exists() && out.isDirectory())) {
            out = new File(out, safeName(title) + "_" + timeStamp() + ".png");
        } else if (!out.getName().toLowerCase(Locale.ROOT).endsWith(".png")) {
            out = new File(out.getParentFile() == null ? new File("") : out.getParentFile(), out.getName() + ".png");
        }
        return out;
    }

    private String readTextFile(File file, int maxBytes) throws Exception {
        FileInputStream in = new FileInputStream(file);
        try {
            return decodeBytes(readLimited(in, maxBytes), null);
        } finally {
            in.close();
        }
    }

    private byte[] readLimited(InputStream in, int maxBytes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buf)) >= 0) {
            if (n == 0) continue;
            int allowed = Math.min(n, maxBytes - total);
            if (allowed > 0) out.write(buf, 0, allowed);
            total += allowed;
            if (total >= maxBytes) break;
        }
        return out.toByteArray();
    }

    private String decodeBytes(byte[] data, String charset) {
        if (charset != null && charset.length() > 0) {
            try { return stripBom(new String(data, Charset.forName(charset))); } catch (Exception ignored) {}
        }
        try {
            CharsetDecoder dec = Charset.forName("UTF-8").newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return stripBom(dec.decode(java.nio.ByteBuffer.wrap(data)).toString());
        } catch (CharacterCodingException ignored) {
            try { return stripBom(new String(data, Charset.forName("GB18030"))); } catch (Exception e) { return stripBom(new String(data)); }
        }
    }

    private String charsetFromContentType(String contentType) {
        if (contentType == null) return "";
        Matcher m = Pattern.compile("(?i)charset=([^;\\s]+)").matcher(contentType);
        return m.find() ? m.group(1).replace("\"", "").trim() : "";
    }

    private String detectType(String lowerName) {
        if (lowerName.endsWith(".docx")) return "docx";
        if (lowerName.endsWith(".doc")) return "doc";
        if (lowerName.endsWith(".html") || lowerName.endsWith(".htm")) return "html";
        if (lowerName.endsWith(".md") || lowerName.endsWith(".markdown")) return "markdown";
        return "text";
    }

    private String normalizeText(String s) {
        if (s == null) return "";
        String out = stripBom(s).replace("\r\n", "\n").replace('\r', '\n');
        out = out.replace('\u00A0', ' ');
        out = out.replaceAll("[ \\t]{2,}", " ");
        out = out.replaceAll("\\n{4,}", "\n\n\n");
        return out.trim();
    }

    private String unescapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'");
    }

    private String firstUsefulLine(String text, String fallback) {
        if (text == null) return fallback;
        String[] lines = text.split("\\n");
        for (String line : lines) {
            String t = parseHeading(line.trim());
            if (t.length() > 0) return t;
            t = line.trim();
            if (t.length() > 0) return t.length() > 30 ? t.substring(0, 30) : t;
        }
        return fallback;
    }

    private String stripExt(String name) {
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(0, i) : name;
    }

    private String stripBom(String s) {
        return s != null && s.startsWith("\uFEFF") ? s.substring(1) : (s == null ? "" : s);
    }

    private String normalizePath(String path) {
        if (path == null) return "";
        String p = path.trim();
        if (p.startsWith("/sdcard/")) return "/storage/emulated/0/" + p.substring("/sdcard/".length());
        if ("/sdcard".equals(p)) return "/storage/emulated/0";
        return p;
    }

    private String safeName(String title) {
        String safe = nonEmpty(title, "long_image").replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        if (safe.length() > 36) safe = safe.substring(0, 36);
        return safe;
    }

    private String timeStamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }

    private int parseColor(String color) {
        try { return Color.parseColor(color); } catch (Exception e) { return Color.rgb(23, 163, 107); }
    }

    private Paint paint(int size, int color, boolean bold) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(size);
        p.setColor(color);
        p.setFakeBoldText(bold);
        return p;
    }

    private int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private String nonEmpty(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private String emptyJson(String v) {
        return v == null || v.trim().isEmpty() ? "{}" : v;
    }

    private String ok(JSONObject output, String displayText, String displayHtml) throws Exception {
        JSONObject r = new JSONObject().put("success", true).put("output", output.toString());
        if (displayText != null) r.put("_displayText", displayText);
        if (displayHtml != null) r.put("_displayHtml", displayHtml);
        return r.toString();
    }

    private String error(String msg) throws Exception {
        return new JSONObject().put("success", false).put("error", msg).toString();
    }

    private static class InputDoc {
        final String title;
        final String text;
        final String inputType;
        final String sourceLabel;
        InputDoc(String title, String text, String inputType, String sourceLabel) {
            this.title = title == null ? "" : title;
            this.text = text == null ? "" : text;
            this.inputType = inputType == null ? "text" : inputType;
            this.sourceLabel = sourceLabel == null ? "" : sourceLabel;
        }
    }

    private static class StructuredDoc {
        final String title;
        final String subtitle;
        final List<Section> sections;
        final String inputType;
        final String sourceLabel;
        StructuredDoc(String title, String subtitle, List<Section> sections, String inputType, String sourceLabel) {
            this.title = title == null ? "" : title;
            this.subtitle = subtitle == null ? "" : subtitle;
            this.sections = sections == null ? new ArrayList<Section>() : sections;
            this.inputType = inputType == null ? "json" : inputType;
            this.sourceLabel = sourceLabel == null ? "结构化数据" : sourceLabel;
        }
    }

    private static class Section {
        final String title;
        final String body;
        final String style;
        Section(String title, String body) {
            this(title, body, STYLE_PLAIN);
        }
        Section(String title, String body, String style) {
            this.title = title == null ? "内容" : title;
            this.body = body == null ? "" : body;
            this.style = style == null || style.trim().isEmpty() ? STYLE_PLAIN : style;
        }
    }

    private static class Layout {
        final int height;
        final boolean truncated;
        Layout(int height, boolean truncated) {
            this.height = height;
            this.truncated = truncated;
        }
    }
}
