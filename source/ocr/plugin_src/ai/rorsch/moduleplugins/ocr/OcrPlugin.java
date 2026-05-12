package ai.rorsch.moduleplugins.ocr;

import ai.rorsch.pandagenie.module.runtime.HtmlOutputHelper;
import ai.rorsch.pandagenie.module.runtime.ModuleLlm;
import ai.rorsch.pandagenie.module.runtime.ModulePlugin;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;

public class OcrPlugin implements ModulePlugin {

    private static final String OCR_API = "https://api.ocr.space/parse/image";
    private static final String API_KEY = "K85403655788957";
    private static final int[] OCR_RETRY_SIDES = new int[]{1280, 960, 720};
    private static final int CONNECT_TIMEOUT = 15_000;
    private static final int READ_TIMEOUT = 30_000;

    private static boolean isZh() {
        return Locale.getDefault().getLanguage().toLowerCase(Locale.ROOT).startsWith("zh");
    }

    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        try {
            JSONObject params = new JSONObject(paramsJson == null || paramsJson.trim().isEmpty() ? "{}" : paramsJson);
            switch (action) {
                case "recognizeText":
                    return recognizeText(context, params);
                case "recognizeAndAnalyzeText":
                    return recognizeAndAnalyzeText(context, params);
                case "openPage":
                    return new JSONObject()
                            .put("success", true)
                            .put("output", "{}")
                            .put("_openModule", true)
                            .put("_displayText", isZh() ? "\u6b63\u5728\u6253\u5f00\u6587\u5b57\u8bc6\u522b..." : "Opening OCR...")
                            .toString();
                default:
                    return error("Unsupported action: " + action);
            }
        } catch (Exception e) {
            String msg = e.getMessage();
            return error(friendlyError(msg != null && !msg.isEmpty() ? msg : e.getClass().getSimpleName()));
        }
    }

    private String recognizeText(Context context, JSONObject params) throws Exception {
        String imagePath = params.optString("imagePath", "").trim();
        if (imagePath.isEmpty()) throw new IllegalArgumentException(isZh() ? "缺少图片路径参数 imagePath" : "imagePath is required");

        File f = new File(imagePath);
        if (!f.isFile() || !f.canRead()) {
            throw new IllegalArgumentException(isZh() ? "文件无法读取：" + imagePath : "file not readable: " + imagePath);
        }
        validateImageFile(imagePath);

        String language = params.optString("language", "auto").trim().toLowerCase(Locale.ROOT);
        String ocrLang;
        switch (language) {
            case "zh":
                ocrLang = "chs";
                break;
            case "en":
                ocrLang = "eng";
                break;
            default:
                ocrLang = "chs";
                break;
        }

        OcrResponse ocr = recognizeWithFallback(context, imagePath, ocrLang);
        String recognized = cleanRecognizedText(ocr.text);

        JSONObject out = new JSONObject();
        out.put("imagePath", imagePath);
        out.put("language", ocrLang);
        out.put("provider", ocr.provider);
        out.put("ocrEngine", ocr.engineLabel);
        if (ocr.maxSide > 0) out.put("maxSide", ocr.maxSide);
        out.put("text", recognized);
        out.put("charCount", recognized.length());
        out.put("lineCount", recognized.isEmpty() ? 0 : recognized.split("\n").length);

        String display;
        if (recognized.isEmpty()) {
            display = isZh()
                    ? "\u274c \u672a\u8bc6\u522b\u5230\u6587\u5b57\n\u25b8 \u56fe\u7247: " + imagePath
                    : "\u274c No text recognized\n\u25b8 Image: " + imagePath;
        } else {
            String preview = recognized.length() > 500 ? recognized.substring(0, 500) + "\u2026" : recognized;
            display = isZh()
                    ? "\u2705 \u6587\u5b57\u8bc6\u522b\u5b8c\u6210\n"
                    + "\u25b8 \u8bc6\u522b\u6587\u5b57 (" + recognized.length() + " \u5b57):\n" + preview
                    + "\n\u25b8 \u56fe\u7247: " + imagePath
                    : "\u2705 Text recognized\n"
                    + "\u25b8 Text (" + recognized.length() + " chars):\n" + preview
                    + "\n\u25b8 Image: " + imagePath;
        }

        String displayHtml = formatRecognizeHtml(recognized, imagePath, ocrLang, ocr.provider, ocr.engineLabel, ocr.maxSide);
        JSONObject r = new JSONObject();
        r.put("success", true);
        r.put("output", out.toString());
        r.put("_displayText", display);
        if (displayHtml != null && !displayHtml.isEmpty()) r.put("_displayHtml", displayHtml);
        return r.toString();
    }

    private String recognizeAndAnalyzeText(Context context, JSONObject params) throws Exception {
        JSONObject recognizedResponse = new JSONObject(recognizeText(context, params));
        if (!recognizedResponse.optBoolean("success", false)) return recognizedResponse.toString();

        JSONObject base = new JSONObject(recognizedResponse.optString("output", "{}"));
        String rawText = base.optString("text", "").trim();
        if (rawText.isEmpty()) return recognizedResponse.toString();

        String task = params.optString("task", "clean").trim().toLowerCase(Locale.ROOT);
        String instruction = params.optString("instruction", "").trim();
        String outputLanguage = params.optString("outputLanguage", isZh() ? "zh" : "en").trim();
        int maxChars = clamp(params.optInt("maxTextChars", 12000), 1000, 20000);
        int maxTokens = clamp(params.optInt("maxTokens", 768), 128, 1024);
        boolean truncated = rawText.length() > maxChars;
        String usedText = truncated ? rawText.substring(0, maxChars) : rawText;

        String taskPrompt;
        if ("extract".equals(task) || "structure".equals(task)) {
            taskPrompt = isZh()
                    ? "请从 OCR 文本中提取关键信息，按清晰条目或 JSON 风格输出。"
                    : "Extract key information from the OCR text in clear bullets or a JSON-like structure.";
        } else if ("summary".equals(task) || "summarize".equals(task)) {
            taskPrompt = isZh()
                    ? "请总结 OCR 文本，保留关键事实、数字和专有名词。"
                    : "Summarize the OCR text while preserving key facts, numbers, and proper nouns.";
        } else {
            taskPrompt = isZh()
                    ? "请校正 OCR 文本中的明显识别错误，整理成可读文本。不要编造原文没有的信息。"
                    : "Correct obvious OCR mistakes and format the text readably. Do not invent missing facts.";
        }
        if (!instruction.isEmpty()) {
            taskPrompt += "\n" + (isZh() ? "额外要求：" : "Extra instruction: ") + instruction;
        }

        String prompt = (isZh() ? "输出语言：" : "Output language: ") + outputLanguage
                + "\n" + taskPrompt
                + "\n\nOCR text:\n" + usedText;
        JSONObject request = new JSONObject()
                .put("action", "ocr.recognizeAndAnalyzeText")
                .put("prompt", prompt)
                .put("temperature", 0.1)
                .put("maxTokens", maxTokens);
        JSONObject llm = new JSONObject(ModuleLlm.completeJson(context, request.toString()));
        if (!llm.optBoolean("success", false)) {
            throw new IllegalStateException(llm.optString("error", "LLM request failed"));
        }

        String result = llm.optString("text", "").trim();
        JSONObject out = new JSONObject();
        out.put("imagePath", base.optString("imagePath"));
        out.put("language", base.optString("language"));
        out.put("provider", base.optString("provider"));
        out.put("ocrEngine", base.optString("ocrEngine"));
        out.put("task", task);
        out.put("rawText", rawText);
        out.put("rawCharCount", rawText.length());
        out.put("usedCharCount", usedText.length());
        out.put("truncated", truncated);
        out.put("result", result);
        out.put("source", "llm");

        String display = (isZh() ? "✅ OCR 智能整理完成\n" : "✅ OCR analysis complete\n")
                + result
                + "\n\n" + (isZh() ? "原始字数：" : "Raw chars: ") + rawText.length();
        String html = HtmlOutputHelper.card("✨", isZh() ? "OCR 智能整理" : "OCR AI result",
                HtmlOutputHelper.badge("LLM", "blue")
                        + HtmlOutputHelper.keyValue(new String[][]{
                        {isZh() ? "任务" : "Task", task},
                        {isZh() ? "原始字数" : "Raw chars", String.valueOf(rawText.length())},
                        {isZh() ? "图片" : "Image", base.optString("imagePath")}
                })
                        + HtmlOutputHelper.p(result)
                        + HtmlOutputHelper.successBadge());
        return new JSONObject()
                .put("success", true)
                .put("output", out.toString())
                .put("_displayText", display)
                .put("_displayHtml", html)
                .toString();
    }

    private OcrResponse recognizeWithFallback(Context context, String imagePath, String ocrLang) throws Exception {
        Exception localError = null;
        try {
            OcrResponse local = requestMlKitOcr(context, imagePath, ocrLang);
            if (!local.text.trim().isEmpty()) return local;
            localError = new IOException("Local OCR returned empty text");
        } catch (Exception e) {
            localError = e;
        }

        int[] engines = "eng".equals(ocrLang) ? new int[]{2, 1} : new int[]{1, 2};
        Exception lastError = localError;
        for (int maxSide : OCR_RETRY_SIDES) {
            for (int engine : engines) {
                try {
                    String text = requestOcr(imagePath, ocrLang, engine, maxSide);
                    return new OcrResponse(text, "ocr.space", String.valueOf(engine), maxSide);
                } catch (Exception e) {
                    lastError = e;
                    if (!isRetriableOcrError(e.getMessage())) throw e;
                }
            }
        }
        throw lastError != null ? lastError : new IOException("OCR failed");
    }

    private OcrResponse requestMlKitOcr(Context context, String imagePath, String ocrLang) throws Exception {
        Object options = buildMlKitOptions(ocrLang);
        ClassLoader cl = OcrPlugin.class.getClassLoader();
        Class<?> inputImageClass = Class.forName("com.google.mlkit.vision.common.InputImage", true, cl);
        Bitmap bitmap = decodeImageOrThrow(imagePath, 1920);
        Object image = inputImageClass
                .getMethod("fromBitmap", Bitmap.class, int.class)
                .invoke(null, bitmap, 0);

        Class<?> textRecognitionClass = Class.forName("com.google.mlkit.vision.text.TextRecognition", true, cl);
        Object recognizer = null;
        Object task = null;
        try {
            recognizer = invokeGetClient(textRecognitionClass, options);
            task = recognizer.getClass().getMethod("process", inputImageClass).invoke(recognizer, image);
            Object result = awaitTask(task);
            String text = String.valueOf(result.getClass().getMethod("getText").invoke(result));
            String engine = options.getClass().getName().toLowerCase(Locale.ROOT).contains("chinese") ? "mlkit-chinese" : "mlkit-latin";
            return new OcrResponse(text, "mlkit-local", engine, 0);
        } catch (Exception e) {
            throw unwrapReflectionException(e);
        } finally {
            try {
                bitmap.recycle();
            } catch (Throwable ignored) { }
            if (recognizer != null) {
                try {
                    recognizer.getClass().getMethod("close").invoke(recognizer);
                } catch (Throwable ignored) { }
            }
        }
    }

    private Object buildMlKitOptions(String ocrLang) throws Exception {
        ClassLoader cl = OcrPlugin.class.getClassLoader();
        if ("eng".equals(ocrLang)) {
            Class<?> latinOptions = Class.forName("com.google.mlkit.vision.text.latin.TextRecognizerOptions", true, cl);
            return latinOptions.getField("DEFAULT_OPTIONS").get(null);
        }
        Class<?> builderClass = Class.forName("com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions$Builder", true, cl);
        Object builder = builderClass.getDeclaredConstructor().newInstance();
        return builderClass.getMethod("build").invoke(builder);
    }

    private Object invokeGetClient(Class<?> textRecognitionClass, Object options) throws Exception {
        for (java.lang.reflect.Method method : textRecognitionClass.getMethods()) {
            if (!"getClient".equals(method.getName()) || method.getParameterTypes().length != 1) continue;
            Class<?> paramType = method.getParameterTypes()[0];
            if (paramType.isAssignableFrom(options.getClass())) {
                return method.invoke(null, options);
            }
        }
        throw new NoSuchMethodException("TextRecognition.getClient(options)");
    }

    private Object awaitTask(Object task) throws Exception {
        ClassLoader cl = OcrPlugin.class.getClassLoader();
        Class<?> tasksClass = Class.forName("com.google.android.gms.tasks.Tasks", true, cl);
        Class<?> taskClass = Class.forName("com.google.android.gms.tasks.Task", true, cl);
        try {
            return tasksClass.getMethod("await", taskClass).invoke(null, task);
        } catch (Exception e) {
            throw unwrapReflectionException(e);
        }
    }

    private Exception unwrapReflectionException(Exception e) {
        Throwable t = e;
        if (t instanceof java.lang.reflect.InvocationTargetException && ((java.lang.reflect.InvocationTargetException) t).getTargetException() != null) {
            t = ((java.lang.reflect.InvocationTargetException) t).getTargetException();
        }
        if (t instanceof java.util.concurrent.ExecutionException && t.getCause() != null) {
            t = t.getCause();
        }
        if (t instanceof Exception) return (Exception) t;
        return new Exception(t);
    }

    private String requestOcr(String imagePath, String ocrLang, int engine, int maxSide) throws Exception {
        Bitmap bmp = decodeImageOrThrow(imagePath, maxSide);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            bmp.compress(Bitmap.CompressFormat.JPEG, 78, baos);
        } finally {
            bmp.recycle();
        }
        String base64Img = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);

        String postData = "apikey=" + URLEncoder.encode(API_KEY, "UTF-8")
                + "&base64Image=" + URLEncoder.encode("data:image/jpeg;base64," + base64Img, "UTF-8")
                + "&language=" + URLEncoder.encode(ocrLang, "UTF-8")
                + "&isOverlayRequired=false"
                + "&scale=true"
                + "&detectOrientation=true"
                + "&OCREngine=" + engine;

        HttpURLConnection conn = (HttpURLConnection) new URL(OCR_API).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

        try (OutputStream os = conn.getOutputStream()) {
            os.write(postData.getBytes("UTF-8"));
        }

        int code = conn.getResponseCode();
        InputStream is = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        String body = readStream(is);
        conn.disconnect();

        if (code < 200 || code >= 300) {
            throw new IOException("OCR API returned HTTP " + code + (body.isEmpty() ? "" : ": " + body));
        }

        JSONObject resp = new JSONObject(body);
        if (resp.optBoolean("IsErroredOnProcessing", false)) {
            String errMsg = "OCR processing error";
            if (resp.has("ErrorMessage")) {
                Object em = resp.get("ErrorMessage");
                if (em instanceof org.json.JSONArray) {
                    errMsg = ((org.json.JSONArray) em).optString(0, errMsg);
                } else {
                    errMsg = em.toString();
                }
            }
            throw new IOException(errMsg);
        }

        org.json.JSONArray results = resp.optJSONArray("ParsedResults");
        StringBuilder sb = new StringBuilder();
        if (results != null) {
            for (int i = 0; i < results.length(); i++) {
                JSONObject pr = results.getJSONObject(i);
                String text = pr.optString("ParsedText", "");
                if (!text.isEmpty()) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(text.trim());
                }
            }
        }
        return sb.toString().trim();
    }

    private static void validateImageFile(String imagePath) {
        String ext = extensionOf(imagePath);
        if (ext.isEmpty() || isSupportedImageExtension(ext)) return;
        String category = fileCategory(ext);
        throw new IllegalArgumentException(unsupportedImageInputMessage(imagePath, ext, category));
    }

    private static Bitmap decodeImageOrThrow(String imagePath, int maxSide) {
        Bitmap bitmap = loadScaledBitmap(imagePath, maxSide);
        if (bitmap != null) return bitmap;
        throw new IllegalArgumentException(decodeImageFailureMessage(imagePath));
    }

    private static String cleanRecognizedText(String text) {
        if (text == null || text.isEmpty()) return "";
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String cleanedLine = line;
            for (int i = 0; i < 3; i++) {
                String next = cleanedLine.replaceFirst("^\\s*\\$\\s*\\{\\s*input_(?:file|image|audio)_\\d+\\s*\\}\\s*[:：,，;；|｜\\-–—]*\\s*", "");
                if (next.equals(cleanedLine)) break;
                cleanedLine = next;
            }
            if (sb.length() > 0) sb.append('\n');
            sb.append(cleanedLine);
        }
        return sb.toString().trim();
    }

    private static String extensionOf(String path) {
        if (path == null) return "";
        String clean = path.trim();
        int q = clean.indexOf('?');
        if (q >= 0) clean = clean.substring(0, q);
        int hash = clean.indexOf('#');
        if (hash >= 0) clean = clean.substring(0, hash);
        int slash = Math.max(clean.lastIndexOf('/'), clean.lastIndexOf('\\'));
        int dot = clean.lastIndexOf('.');
        if (dot <= slash || dot == clean.length() - 1) return "";
        return clean.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean isSupportedImageExtension(String ext) {
        switch (ext) {
            case "jpg":
            case "jpeg":
            case "png":
            case "webp":
            case "bmp":
            case "gif":
            case "heic":
            case "heif":
                return true;
            default:
                return false;
        }
    }

    private static String fileCategory(String ext) {
        switch (ext) {
            case "mp3":
            case "m4a":
            case "aac":
            case "wav":
            case "flac":
            case "ogg":
            case "opus":
            case "amr":
            case "mid":
            case "midi":
                return "audio";
            case "mp4":
            case "mkv":
            case "mov":
            case "avi":
            case "webm":
            case "3gp":
            case "flv":
                return "video";
            case "pdf":
            case "doc":
            case "docx":
            case "xls":
            case "xlsx":
            case "ppt":
            case "pptx":
            case "txt":
            case "md":
            case "csv":
                return "document";
            case "zip":
            case "apk":
            case "jar":
            case "aar":
            case "rar":
            case "7z":
            case "tar":
            case "gz":
            case "tgz":
                return "archive";
            default:
                return "file";
        }
    }

    private static String categoryLabel(String category) {
        if (!isZh()) {
            switch (category) {
                case "audio": return "audio";
                case "video": return "video";
                case "document": return "document";
                case "archive": return "archive";
                default: return "file";
            }
        }
        switch (category) {
            case "audio": return "\u97f3\u9891";
            case "video": return "\u89c6\u9891";
            case "document": return "\u6587\u6863";
            case "archive": return "\u538b\u7f29\u5305";
            default: return "\u6587\u4ef6";
        }
    }

    private static String unsupportedImageInputMessage(String imagePath, String ext, String category) {
        String type = categoryLabel(category);
        if (isZh()) {
            return "OCR \u53ea\u80fd\u8bc6\u522b\u56fe\u7247\u6587\u4ef6\uff0c\u5f53\u524d\u6587\u4ef6\u662f" + type + "\uff08." + ext + "\uff09\u3002"
                    + "\u8bf7\u9009\u62e9 JPG/PNG/WebP/BMP/GIF/HEIC \u7b49\u56fe\u7247\uff1b\u5982\u679c\u8981\u5904\u7406" + type + "\uff0c\u8bf7\u4f7f\u7528\u5bf9\u5e94\u6a21\u5757\u3002"
                    + "\u8def\u5f84\uff1a" + imagePath;
        }
        return "OCR only recognizes image files, but this file looks like " + type + " (." + ext + "). "
                + "Choose a JPG/PNG/WebP/BMP/GIF/HEIC image, or use the matching module for this file type. Path: " + imagePath;
    }

    private static String decodeImageFailureMessage(String imagePath) {
        if (isZh()) {
            return "\u65e0\u6cd5\u89e3\u7801\u56fe\u7247\uff1a\u6587\u4ef6\u53ef\u80fd\u4e0d\u662f\u6709\u6548\u56fe\u7247\u3001\u5df2\u635f\u574f\uff0c\u6216\u683c\u5f0f\u6682\u4e0d\u652f\u6301\u3002"
                    + "\u8bf7\u4f7f\u7528 JPG/PNG/WebP/BMP/GIF/HEIC \u7b49\u5e38\u89c1\u56fe\u7247\u3002\u8def\u5f84\uff1a" + imagePath;
        }
        return "Cannot decode image: the file may be invalid, corrupted, or unsupported. "
                + "Use a JPG/PNG/WebP/BMP/GIF/HEIC image. Path: " + imagePath;
    }

    private static String friendlyError(String msg) {
        if (msg == null || msg.trim().isEmpty()) {
            return isZh() ? "OCR \u8bc6\u522b\u5931\u8d25\uff0c\u4f46\u672a\u8fd4\u56de\u5177\u4f53\u539f\u56e0" : "OCR failed without a detailed reason";
        }
        String lower = msg.toLowerCase(Locale.ROOT);
        if (lower.contains("e500") || lower.contains("system resource exhaustion") || lower.contains("ocr binary failed")) {
            return isZh()
                    ? "OCR \u670d\u52a1\u4e34\u65f6\u8d44\u6e90\u4e0d\u8db3\uff08E500\uff09\uff0c\u672c\u6b21\u56fe\u7247\u6ca1\u6709\u8bc6\u522b\u6210\u529f\u3002\u8bf7\u7a0d\u540e\u91cd\u8bd5\uff0c\u6216\u6362\u4e00\u5f20\u66f4\u5c0f\u3001\u66f4\u6e05\u6670\u7684\u56fe\u7247\u3002"
                    : "OCR service is temporarily out of resources (E500). Try again later, or use a smaller/clearer image.";
        }
        if (lower.startsWith("cannot decode image:")) {
            String path = msg.substring("cannot decode image:".length()).trim();
            return decodeImageFailureMessage(path);
        }
        if (lower.startsWith("file not readable:")) {
            String path = msg.substring("file not readable:".length()).trim();
            return isZh() ? "\u6587\u4ef6\u65e0\u6cd5\u8bfb\u53d6\uff1a" + path : msg;
        }
        if (lower.contains("imagepath is required")) {
            return isZh() ? "\u7f3a\u5c11\u56fe\u7247\u8def\u5f84\u53c2\u6570 imagePath" : msg;
        }
        return msg;
    }

    private static boolean isRetriableOcrError(String msg) {
        if (msg == null) return true;
        String lower = msg.toLowerCase(Locale.ROOT);
        return lower.contains("e500")
                || lower.contains("resource")
                || lower.contains("binary")
                || lower.contains("timeout")
                || lower.contains("temporar")
                || lower.contains("http 5")
                || lower.contains("failed");
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String formatRecognizeHtml(String recognized, String imagePath, String ocrLang, String provider, String engine, int maxSide) {
        if (recognized.isEmpty()) {
            return HtmlOutputHelper.card("\u274c", isZh() ? "\u672a\u8bc6\u522b\u5230\u6587\u5b57" : "No text found",
                    HtmlOutputHelper.muted(imagePath) + HtmlOutputHelper.errorBadge());
        }
        String preview = recognized.length() > 1200 ? recognized.substring(0, 1200) + "\u2026" : recognized;
        String[][] rows = maxSide > 0 ? new String[][]{
                {isZh() ? "\u8bed\u8a00" : "Language", ocrLang},
                {isZh() ? "\u65b9\u5f0f" : "Provider", provider},
                {isZh() ? "\u5f15\u64ce" : "Engine", engine},
                {isZh() ? "\u7f29\u653e" : "Max side", String.valueOf(maxSide)},
                {isZh() ? "\u5b57\u6570" : "Characters", String.valueOf(recognized.length())}
        } : new String[][]{
                {isZh() ? "\u8bed\u8a00" : "Language", ocrLang},
                {isZh() ? "\u65b9\u5f0f" : "Provider", provider},
                {isZh() ? "\u5f15\u64ce" : "Engine", engine},
                {isZh() ? "\u5b57\u6570" : "Characters", String.valueOf(recognized.length())}
        };
        String body = HtmlOutputHelper.keyValue(rows) + HtmlOutputHelper.p(preview);
        return HtmlOutputHelper.card("\u2705", isZh() ? "\u6587\u5b57\u8bc6\u522b" : "Text recognized", body + HtmlOutputHelper.successBadge());
    }

    private static Bitmap loadScaledBitmap(String path, int maxSide) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, opts);
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null;

        int sample = 1;
        while (opts.outWidth / sample > maxSide || opts.outHeight / sample > maxSide) sample *= 2;

        opts.inJustDecodeBounds = false;
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap decoded = BitmapFactory.decodeFile(path, opts);
        if (decoded == null) return null;

        int longSide = Math.max(decoded.getWidth(), decoded.getHeight());
        if (longSide <= maxSide) return decoded;

        float ratio = maxSide / (float) longSide;
        int targetW = Math.max(1, Math.round(decoded.getWidth() * ratio));
        int targetH = Math.max(1, Math.round(decoded.getHeight() * ratio));
        Bitmap scaled = Bitmap.createScaledBitmap(decoded, targetW, targetH, true);
        if (scaled != decoded) decoded.recycle();
        return scaled;
    }

    private static String readStream(InputStream is) throws IOException {
        if (is == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return sb.toString();
    }

    private static String error(String msg) throws Exception {
        return new JSONObject().put("success", false).put("error", msg).toString();
    }

    private static class OcrResponse {
        final String text;
        final String provider;
        final String engineLabel;
        final int maxSide;

        OcrResponse(String text, String provider, String engineLabel, int maxSide) {
            this.text = text == null ? "" : text;
            this.provider = provider == null ? "" : provider;
            this.engineLabel = engineLabel == null ? "" : engineLabel;
            this.maxSide = maxSide;
        }
    }
}
