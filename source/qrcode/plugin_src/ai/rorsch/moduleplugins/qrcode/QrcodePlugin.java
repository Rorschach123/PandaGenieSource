package ai.rorsch.moduleplugins.qrcode;

import ai.rorsch.moduleplugins.qrcode.qr.DataTooLongException;
import ai.rorsch.moduleplugins.qrcode.qr.QrCode;
import ai.rorsch.pandagenie.module.runtime.HtmlOutputHelper;
import ai.rorsch.pandagenie.module.runtime.ModulePlugin;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Environment;
import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import android.graphics.BitmapFactory;

public class QrcodePlugin implements ModulePlugin {

    private static final int SIZE_MIN = 64;
    private static final int SIZE_MAX = 2048;
    private static final int SIZE_DEFAULT = 512;
    private static final int BASE64_MAX_SIDE = 512;

    private static boolean isZh() {
        try {
            return Locale.getDefault().getLanguage().toLowerCase(Locale.ROOT).startsWith("zh");
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        try {
            JSONObject params = new JSONObject(emptyJson(paramsJson));
            switch (action) {
                case "generateQR": {
                    String out = generateQR(params);
                    JSONObject gen = new JSONObject(out);
                    String path = gen.optString("path", "");
                    JSONArray rich = new JSONArray();
                    if (!path.isEmpty()) {
                        rich.put(richImage(path, isZh() ? "二维码" : "QR Code"));
                    }
                    return ok(out, formatGenerateDisplay(out), rich, formatGenerateHtml(out));
                }
                case "decodeQR": {
                    String out = decodeQR(params);
                    JSONObject decoded = new JSONObject(out);
                    JSONArray rich = new JSONArray();
                    String imgPath = decoded.optString("imagePath", "");
                    if (!imgPath.isEmpty()) {
                        rich.put(richImage(imgPath, isZh() ? "扫描图片" : "Scanned Image"));
                    }
                    return ok(out, formatDecodeDisplay(out), rich, formatDecodeHtml(out));
                }
                case "detectQR": {
                    String out = detectQR(params);
                    return ok(out, formatDetectDisplay(out), formatDetectHtml(out));
                }
                case "listGenerated": {
                    String out = listGenerated();
                    JSONArray rich = new JSONArray();
                    JSONObject lo = new JSONObject(out);
                    JSONArray files = lo.optJSONArray("files");
                    if (files != null) {
                        String imgTitle = isZh() ? "二维码" : "QR Code";
                        int max = Math.min(files.length(), 10);
                        for (int i = 0; i < max; i++) {
                            JSONObject item = files.optJSONObject(i);
                            if (item != null) {
                                String p = item.optString("path", "");
                                if (!p.isEmpty()) {
                                    rich.put(richImage(p, imgTitle));
                                }
                            }
                        }
                    }
                    return ok(out, formatListDisplay(out), rich, formatListHtml(out));
                }
                case "openPage": {
                    String page = params.optString("page", "gen").trim();
                    JSONObject r = new JSONObject();
                    r.put("success", true);
                    r.put("output", new JSONObject().put("page", page).toString());
                    r.put("_openModule", true);
                    String hint;
                    if ("scan".equals(page)) {
                        hint = isZh() ? "正在打开相机扫描（需要相机权限）..." : "Opening camera scan (camera permission required)...";
                    } else {
                        hint = isZh() ? "正在打开二维码工具..." : "Opening QR Code Tools...";
                    }
                    r.put("_displayText", hint);
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

    // ─── generateQR ─────────────────────────────────────────────────────

    private static String generateQR(JSONObject params) throws Exception {
        String text = params.optString("text", "").trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("text is required");
        }
        int size = params.optInt("size", SIZE_DEFAULT);
        if (size < SIZE_MIN) size = SIZE_MIN;
        if (size > SIZE_MAX) size = SIZE_MAX;
        boolean includeBase64 = params.optBoolean("includeBase64", false);

        QrCode qr;
        try {
            qr = QrCode.encodeText(text, QrCode.Ecc.MEDIUM);
        } catch (DataTooLongException e) {
            throw new IllegalArgumentException("text too long for QR code");
        }

        Bitmap bitmap = renderQrBitmap(qr, size);
        File outDir = new File(Environment.getExternalStorageDirectory(), "PandaGenie/qrcode");
        if (!outDir.exists() && !outDir.mkdirs()) {
            recycleQuietly(bitmap);
            throw new IllegalStateException("cannot create output directory");
        }
        String filename = "qr_" + System.currentTimeMillis() + ".png";
        File outFile = new File(outDir, filename);
        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)) {
                throw new IllegalStateException("PNG compress failed");
            }
            fos.flush();
        }

        JSONObject o = new JSONObject();
        o.put("path", outFile.getAbsolutePath());
        o.put("text", text);
        o.put("requestedSize", size);
        o.put("imageSize", bitmap.getWidth());
        o.put("qrVersion", qr.version);
        o.put("modules", qr.size);

        if (includeBase64 || size <= BASE64_MAX_SIDE) {
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos);
                o.put("base64", Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP));
            }
        }
        recycleQuietly(bitmap);
        return o.toString();
    }

    private static Bitmap renderQrBitmap(QrCode qr, int pixelSize) {
        final int border = 4;
        int modCount = qr.size + 2 * border;
        int cell = Math.max(1, pixelSize / modCount);
        int dim = cell * modCount;
        Bitmap bmp = Bitmap.createBitmap(dim, dim, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint pLight = new Paint(Paint.ANTI_ALIAS_FLAG);
        pLight.setStyle(Paint.Style.FILL);
        pLight.setColor(0xFFFFFFFF);
        Paint pDark = new Paint(Paint.ANTI_ALIAS_FLAG);
        pDark.setStyle(Paint.Style.FILL);
        pDark.setColor(0xFF000000);
        canvas.drawRect(0, 0, dim, dim, pLight);
        for (int y = 0; y < modCount; y++) {
            for (int x = 0; x < modCount; x++) {
                boolean dark;
                if (x < border || y < border || x >= border + qr.size || y >= border + qr.size) {
                    dark = false;
                } else {
                    dark = qr.getModule(x - border, y - border);
                }
                float left = x * cell;
                float top = y * cell;
                canvas.drawRect(left, top, left + cell, top + cell, dark ? pDark : pLight);
            }
        }
        return bmp;
    }

    // ─── decodeQR — real QR content extraction ──────────────────────────

    private static String decodeQR(JSONObject params) throws Exception {
        String path = params.optString("imagePath", "").trim();
        if (TextUtils.isEmpty(path)) {
            throw new IllegalArgumentException("imagePath is required");
        }
        File f = new File(path);
        if (!f.isFile() || !f.canRead()) {
            throw new IllegalArgumentException("file not readable: " + path);
        }

        Bitmap bmp = loadScaledBitmap(path, 1200);
        if (bmp == null) {
            throw new IllegalArgumentException("cannot decode image: " + path);
        }

        String decoded = null;
        try {
            decoded = QrBitmapDecoder.decode(bmp);
        } catch (Exception ignored) {
        } finally {
            recycleQuietly(bmp);
        }

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);

        JSONObject o = new JSONObject();
        o.put("imagePath", path);
        o.put("width", bounds.outWidth);
        o.put("height", bounds.outHeight);
        o.put("fileSizeBytes", f.length());
        o.put("mimeType", bounds.outMimeType != null ? bounds.outMimeType : JSONObject.NULL);
        o.put("decoded", decoded != null ? decoded : JSONObject.NULL);
        o.put("hasQrCode", decoded != null);
        return o.toString();
    }

    // ─── detectQR — check if image contains QR ─────────────────────────

    private static String detectQR(JSONObject params) throws Exception {
        String path = params.optString("imagePath", "").trim();
        if (TextUtils.isEmpty(path)) {
            throw new IllegalArgumentException("imagePath is required");
        }
        File f = new File(path);
        if (!f.isFile() || !f.canRead()) {
            throw new IllegalArgumentException("file not readable: " + path);
        }

        Bitmap bmp = loadScaledBitmap(path, 800);
        if (bmp == null) {
            throw new IllegalArgumentException("cannot decode image: " + path);
        }

        String decoded = null;
        try {
            decoded = QrBitmapDecoder.decode(bmp);
        } catch (Exception ignored) {
        } finally {
            recycleQuietly(bmp);
        }

        JSONObject o = new JSONObject();
        o.put("imagePath", path);
        o.put("hasQrCode", decoded != null);
        if (decoded != null) {
            o.put("decoded", decoded);
        }
        return o.toString();
    }

    // ─── listGenerated ──────────────────────────────────────────────────

    private static String listGenerated() throws Exception {
        File dir = new File(Environment.getExternalStorageDirectory(), "PandaGenie/qrcode");
        if (!dir.isDirectory()) {
            JSONObject o = new JSONObject();
            o.put("directory", dir.getAbsolutePath());
            o.put("files", new JSONArray());
            o.put("count", 0);
            return o.toString();
        }
        File[] list = dir.listFiles();
        List<File> files = new ArrayList<>();
        if (list != null) {
            for (File f : list) {
                if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".png")) {
                    files.add(f);
                }
            }
        }
        Collections.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        JSONArray arr = new JSONArray();
        for (File f : files) {
            JSONObject item = new JSONObject();
            item.put("name", f.getName());
            item.put("path", f.getAbsolutePath());
            item.put("sizeBytes", f.length());
            item.put("modified", f.lastModified());
            arr.put(item);
        }
        JSONObject o = new JSONObject();
        o.put("directory", dir.getAbsolutePath());
        o.put("files", arr);
        o.put("count", arr.length());
        return o.toString();
    }

    // ─── Bitmap helpers ─────────────────────────────────────────────────

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

    private static void recycleQuietly(Bitmap b) {
        if (b != null && !b.isRecycled()) b.recycle();
    }

    // ─── Display text formatters ────────────────────────────────────────

    private static String formatGenerateDisplay(String outputJson) throws Exception {
        JSONObject o = new JSONObject(outputJson);
        String text = o.optString("text", "");
        String preview = text.length() > 48 ? text.substring(0, 48) + "\u2026" : text;
        String path = o.optString("path", "");
        if (isZh()) {
            return "\u2705 二维码已生成\n"
                    + "━━━━━━━━━━━━━━━━\n"
                    + "\u25B8 编码内容: " + preview + "\n"
                    + "\u25B8 图片尺寸: " + o.optInt("imageSize") + " \u00D7 " + o.optInt("imageSize") + " px\n"
                    + "\u25B8 QR 版本: " + o.optInt("qrVersion") + " (" + o.optInt("modules") + " \u00D7 " + o.optInt("modules") + " 模块)\n"
                    + "\u25B8 文件路径: " + path;
        }
        return "\u2705 QR Code Generated\n"
                + "\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\n"
                + "\u25B8 Content: " + preview + "\n"
                + "\u25B8 Size: " + o.optInt("imageSize") + " \u00D7 " + o.optInt("imageSize") + " px\n"
                + "\u25B8 QR Version: " + o.optInt("qrVersion") + " (" + o.optInt("modules") + " \u00D7 " + o.optInt("modules") + " modules)\n"
                + "\u25B8 Path: " + path;
    }

    private static String formatDecodeDisplay(String outputJson) throws Exception {
        JSONObject o = new JSONObject(outputJson);
        int w = o.optInt("width");
        int h = o.optInt("height");
        boolean hasQr = o.optBoolean("hasQrCode", false);
        String decoded = o.isNull("decoded") ? null : o.optString("decoded", null);
        String path = o.optString("imagePath", "");

        if (hasQr && decoded != null) {
            String preview = decoded.length() > 200 ? decoded.substring(0, 200) + "\u2026" : decoded;
            if (isZh()) {
                return "\u2705 二维码识别成功\n"
                        + "━━━━━━━━━━━━━━━━\n"
                        + "\u25B8 解码内容:\n" + preview + "\n"
                        + "\u25B8 图片: " + w + " \u00D7 " + h + " px\n"
                        + "\u25B8 来源: " + path;
            }
            return "\u2705 QR Code Decoded\n"
                    + "\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\n"
                    + "\u25B8 Content:\n" + preview + "\n"
                    + "\u25B8 Image: " + w + " \u00D7 " + h + " px\n"
                    + "\u25B8 Source: " + path;
        }
        if (isZh()) {
            return "\u274C 未识别到二维码\n"
                    + "━━━━━━━━━━━━━━━━\n"
                    + "\u25B8 图片: " + w + " \u00D7 " + h + " px\n"
                    + "\u25B8 来源: " + path + "\n"
                    + "\u25B8 请确认图片包含清晰的二维码";
        }
        return "\u274C No QR Code Found\n"
                + "\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\n"
                + "\u25B8 Image: " + w + " \u00D7 " + h + " px\n"
                + "\u25B8 Source: " + path + "\n"
                + "\u25B8 Please ensure the image contains a clear QR code";
    }

    private static String formatDetectDisplay(String outputJson) throws Exception {
        JSONObject o = new JSONObject(outputJson);
        boolean hasQr = o.optBoolean("hasQrCode", false);
        String path = o.optString("imagePath", "");
        if (hasQr) {
            String decoded = o.optString("decoded", "");
            String preview = decoded.length() > 100 ? decoded.substring(0, 100) + "\u2026" : decoded;
            if (isZh()) {
                return "\u2705 检测到二维码\n"
                        + "━━━━━━━━━━━━━━━━\n"
                        + "\u25B8 内容: " + preview + "\n"
                        + "\u25B8 图片: " + path;
            }
            return "\u2705 QR Code Detected\n"
                    + "\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\n"
                    + "\u25B8 Content: " + preview + "\n"
                    + "\u25B8 Image: " + path;
        }
        if (isZh()) {
            return "\u274C 未检测到二维码\n\u25B8 图片: " + path;
        }
        return "\u274C No QR Code Detected\n\u25B8 Image: " + path;
    }

    private static String formatListDisplay(String outputJson) throws Exception {
        JSONObject o = new JSONObject(outputJson);
        int n = o.optInt("count");
        String dir = o.optString("directory", "");
        if (isZh()) {
            return "\uD83D\uDCC2 二维码图库\n"
                    + "━━━━━━━━━━━━━━━━\n"
                    + "\u25B8 共 " + n + " 个 PNG 文件\n"
                    + "\u25B8 存储目录: " + dir;
        }
        return "\uD83D\uDCC2 QR Code Gallery\n"
                + "\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\n"
                + "\u25B8 " + n + " PNG file(s)\n"
                + "\u25B8 Directory: " + dir;
    }

    private static String formatGenerateHtml(String outputJson) throws Exception {
        JSONObject o = new JSONObject(outputJson);
        String preview = o.optString("text", "");
        if (preview.length() > 48) preview = preview.substring(0, 48) + "\u2026";
        String path = o.optString("path", "");
        String kv = HtmlOutputHelper.keyValue(new String[][]{
                {isZh() ? "\u7f16\u7801\u5185\u5bb9" : "Content", preview},
                {isZh() ? "\u56fe\u7247\u5c3a\u5bf8" : "Image size", o.optInt("imageSize") + " \u00d7 " + o.optInt("imageSize") + " px"},
                {isZh() ? "QR \u7248\u672c" : "QR version", o.optInt("qrVersion") + " (" + o.optInt("modules") + " \u00d7 " + o.optInt("modules") + ")"},
                {isZh() ? "\u6587\u4ef6\u8def\u5f84" : "File path", path}
        });
        return HtmlOutputHelper.card("\u2705", isZh() ? "\u4e8c\u7ef4\u7801\u5df2\u751f\u6210" : "QR code generated", kv + HtmlOutputHelper.successBadge());
    }

    private static String formatDecodeHtml(String outputJson) throws Exception {
        JSONObject o = new JSONObject(outputJson);
        int w = o.optInt("width");
        int h = o.optInt("height");
        boolean hasQr = o.optBoolean("hasQrCode", false);
        String decoded = o.isNull("decoded") ? null : o.optString("decoded", null);
        String path = o.optString("imagePath", "");
        if (hasQr && decoded != null) {
            String prev = decoded.length() > 400 ? decoded.substring(0, 400) + "\u2026" : decoded;
            String body = HtmlOutputHelper.p(prev)
                    + HtmlOutputHelper.keyValue(new String[][]{
                    {isZh() ? "\u56fe\u7247" : "Image", w + " \u00d7 " + h + " px"},
                    {isZh() ? "\u6765\u6e90" : "Source", path}
            });
            return HtmlOutputHelper.card("\u2705", isZh() ? "\u8bc6\u522b\u6210\u529f" : "Decoded", body + HtmlOutputHelper.successBadge());
        }
        String body = HtmlOutputHelper.keyValue(new String[][]{
                {isZh() ? "\u56fe\u7247" : "Image", w + " \u00d7 " + h + " px"},
                {isZh() ? "\u6765\u6e90" : "Source", path}
        }) + HtmlOutputHelper.muted(isZh() ? "\u672a\u8bc6\u522b\u5230\u4e8c\u7ef4\u7801\uff0c\u8bf7\u786e\u8ba4\u56fe\u7247\u6e05\u6670\u3002" : "No QR code found. Ensure the image is clear.");
        return HtmlOutputHelper.card("\u274C", isZh() ? "\u672a\u8bc6\u522b" : "Not found", body + HtmlOutputHelper.errorBadge());
    }

    private static String formatDetectHtml(String outputJson) throws Exception {
        JSONObject o = new JSONObject(outputJson);
        boolean hasQr = o.optBoolean("hasQrCode", false);
        String path = o.optString("imagePath", "");
        if (hasQr) {
            String decoded = o.optString("decoded", "");
            String preview = decoded.length() > 200 ? decoded.substring(0, 200) + "\u2026" : decoded;
            return HtmlOutputHelper.card("\u2705", isZh() ? "\u68c0\u6d4b\u5230\u4e8c\u7ef4\u7801" : "QR detected",
                    HtmlOutputHelper.p(preview) + HtmlOutputHelper.muted(path) + HtmlOutputHelper.successBadge());
        }
        return HtmlOutputHelper.card("\u274C", isZh() ? "\u672a\u68c0\u6d4b\u5230" : "Not detected",
                HtmlOutputHelper.muted(path) + HtmlOutputHelper.errorBadge());
    }

    private static String formatListHtml(String outputJson) throws Exception {
        JSONObject o = new JSONObject(outputJson);
        String dir = o.optString("directory", "");
        JSONArray files = o.optJSONArray("files");
        List<String[]> rows = new ArrayList<>();
        if (files != null) {
            int max = Math.min(files.length(), 20);
            for (int i = 0; i < max; i++) {
                JSONObject it = files.optJSONObject(i);
                if (it == null) continue;
                rows.add(new String[]{
                        it.optString("name", ""),
                        it.optString("path", "")
                });
            }
        }
        String table = HtmlOutputHelper.table(
                new String[]{isZh() ? "\u6587\u4ef6" : "File", isZh() ? "\u8def\u5f84" : "Path"},
                rows);
        String title = isZh() ? "\u4e8c\u7ef4\u7801\u56fe\u5e93 (" + o.optInt("count") + ")" : "QR gallery (" + o.optInt("count") + ")";
        return HtmlOutputHelper.card("\uD83D\uDCC2", title, HtmlOutputHelper.muted(dir) + table);
    }

    // ─── JSON helpers ───────────────────────────────────────────────────

    private static String emptyJson(String v) {
        return v == null || v.trim().isEmpty() ? "{}" : v;
    }

    private static String ok(String output, String displayText, JSONArray richContent, String displayHtml) throws Exception {
        JSONObject r = new JSONObject().put("success", true).put("output", output);
        if (displayText != null && !displayText.isEmpty()) r.put("_displayText", displayText);
        if (displayHtml != null && !displayHtml.isEmpty()) r.put("_displayHtml", displayHtml);
        if (richContent != null && richContent.length() > 0) r.put("_richContent", richContent);
        return r.toString();
    }

    private static String ok(String output, String displayText, JSONArray richContent) throws Exception {
        return ok(output, displayText, richContent, null);
    }

    private static String ok(String output, String displayText) throws Exception {
        return ok(output, displayText, null, null);
    }

    private static String ok(String output, String displayText, String displayHtml) throws Exception {
        return ok(output, displayText, null, displayHtml);
    }

    private static String ok(String output) throws Exception {
        return ok(output, null, null, null);
    }

    private static JSONObject richImage(String path, String title) throws Exception {
        JSONObject rc = new JSONObject();
        rc.put("type", "image");
        rc.put("path", path);
        if (title != null && !title.isEmpty()) rc.put("title", title);
        return rc;
    }

    private static String error(String msg) throws Exception {
        return new JSONObject().put("success", false).put("error", msg).toString();
    }
}
