package ai.rorsch.moduleplugins.ocr;

import ai.rorsch.pandagenie.module.runtime.HtmlOutputHelper;
import ai.rorsch.pandagenie.module.runtime.ModulePlugin;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;

public class OcrPlugin implements ModulePlugin {

    private static final String OCR_API = "https://api.ocr.space/parse/image";
    private static final String API_KEY = "K85403655788957";
    private static final int MAX_SIDE = 1600;
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
                    return recognizeText(params);
                case "openPage": {
                    JSONObject r = new JSONObject();
                    r.put("success", true);
                    r.put("output", "{}");
                    r.put("_openModule", true);
                    r.put("_displayText", isZh() ? "正在打开文字识别..." : "Opening OCR...");
                    return r.toString();
                }
                default:
                    return error("Unsupported action: " + action);
            }
        } catch (Exception e) {
            String msg = e.getMessage();
            return error(msg != null && !msg.isEmpty() ? msg : e.getClass().getSimpleName());
        }
    }

    private String recognizeText(JSONObject params) throws Exception {
        String imagePath = params.optString("imagePath", "").trim();
        if (imagePath.isEmpty()) throw new IllegalArgumentException("imagePath is required");

        File f = new File(imagePath);
        if (!f.isFile() || !f.canRead()) throw new IllegalArgumentException("file not readable: " + imagePath);

        String language = params.optString("language", "auto").trim().toLowerCase(Locale.ROOT);

        Bitmap bmp = loadScaledBitmap(imagePath, MAX_SIDE);
        if (bmp == null) throw new IllegalArgumentException("cannot decode image: " + imagePath);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, baos);
        bmp.recycle();
        String base64Img = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);

        String ocrLang;
        switch (language) {
            case "zh": ocrLang = "chs"; break;
            case "en": ocrLang = "eng"; break;
            default:   ocrLang = "chs"; break; // default Chinese which also picks up English
        }

        String postData = "apikey=" + URLEncoder.encode(API_KEY, "UTF-8")
                + "&base64Image=" + URLEncoder.encode("data:image/jpeg;base64," + base64Img, "UTF-8")
                + "&language=" + URLEncoder.encode(ocrLang, "UTF-8")
                + "&isOverlayRequired=false"
                + "&OCREngine=2";

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
            throw new IOException("OCR API returned HTTP " + code);
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

        String recognized = sb.toString().trim();
        JSONObject out = new JSONObject();
        out.put("imagePath", imagePath);
        out.put("language", ocrLang);
        out.put("text", recognized);
        out.put("charCount", recognized.length());
        out.put("lineCount", recognized.isEmpty() ? 0 : recognized.split("\n").length);

        String display;
        if (recognized.isEmpty()) {
            display = isZh() ? "\u274C 未识别到文字\n\u25B8 图片: " + imagePath
                    : "\u274C No text recognized\n\u25B8 Image: " + imagePath;
        } else {
            String preview = recognized.length() > 500 ? recognized.substring(0, 500) + "\u2026" : recognized;
            if (isZh()) {
                display = "\u2705 文字识别完成\n\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\n"
                        + "\u25B8 识别文字 (" + recognized.length() + " 字):\n" + preview
                        + "\n\u25B8 图片: " + imagePath;
            } else {
                display = "\u2705 Text Recognized\n\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\n"
                        + "\u25B8 Text (" + recognized.length() + " chars):\n" + preview
                        + "\n\u25B8 Image: " + imagePath;
            }
        }

        String displayHtml = formatRecognizeHtml(recognized, imagePath, ocrLang);
        JSONObject r = new JSONObject();
        r.put("success", true);
        r.put("output", out.toString());
        r.put("_displayText", display);
        if (displayHtml != null && !displayHtml.isEmpty()) r.put("_displayHtml", displayHtml);
        return r.toString();
    }

    private String formatRecognizeHtml(String recognized, String imagePath, String ocrLang) {
        if (recognized.isEmpty()) {
            return HtmlOutputHelper.card("\u274C", isZh() ? "\u672a\u8bc6\u522b\u5230\u6587\u5b57" : "No text found",
                    HtmlOutputHelper.muted(imagePath) + HtmlOutputHelper.errorBadge());
        }
        String preview = recognized.length() > 1200 ? recognized.substring(0, 1200) + "\u2026" : recognized;
        String body = HtmlOutputHelper.keyValue(new String[][]{
                {isZh() ? "\u8bed\u8a00" : "Language", ocrLang},
                {isZh() ? "\u5b57\u6570" : "Characters", String.valueOf(recognized.length())},
                {isZh() ? "\u56fe\u7247" : "Image", imagePath}
        }) + HtmlOutputHelper.p(preview);
        return HtmlOutputHelper.card("\u2705", isZh() ? "\u6587\u5b57\u8bc6\u522b" : "Text recognized", body + HtmlOutputHelper.successBadge());
    }

    private static Bitmap loadScaledBitmap(String path, int maxSide) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, opts);
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null;
        int w = opts.outWidth, h = opts.outHeight;
        int sample = 1;
        while (w / sample > maxSide || h / sample > maxSide) sample *= 2;
        opts.inJustDecodeBounds = false;
        opts.inSampleSize = sample;
        return BitmapFactory.decodeFile(path, opts);
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
}
