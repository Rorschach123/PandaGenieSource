package ai.rorsch.moduleplugins.ocr;

import ai.rorsch.pandagenie.module.runtime.HtmlOutputHelper;
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
            return error(msg != null && !msg.isEmpty() ? msg : e.getClass().getSimpleName());
        }
    }

    private String recognizeText(Context context, JSONObject params) throws Exception {
        String imagePath = params.optString("imagePath", "").trim();
        if (imagePath.isEmpty()) throw new IllegalArgumentException("imagePath is required");

        File f = new File(imagePath);
        if (!f.isFile() || !f.canRead()) throw new IllegalArgumentException("file not readable: " + imagePath);

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
        String recognized = ocr.text.trim();

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
        Bitmap bitmap = loadScaledBitmap(imagePath, 1920);
        if (bitmap == null) throw new IllegalArgumentException("cannot decode image: " + imagePath);
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
        Bitmap bmp = loadScaledBitmap(imagePath, maxSide);
        if (bmp == null) throw new IllegalArgumentException("cannot decode image: " + imagePath);

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
                {isZh() ? "\u5b57\u6570" : "Characters", String.valueOf(recognized.length())},
                {isZh() ? "\u56fe\u7247" : "Image", imagePath}
        } : new String[][]{
                {isZh() ? "\u8bed\u8a00" : "Language", ocrLang},
                {isZh() ? "\u65b9\u5f0f" : "Provider", provider},
                {isZh() ? "\u5f15\u64ce" : "Engine", engine},
                {isZh() ? "\u5b57\u6570" : "Characters", String.valueOf(recognized.length())},
                {isZh() ? "\u56fe\u7247" : "Image", imagePath}
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
