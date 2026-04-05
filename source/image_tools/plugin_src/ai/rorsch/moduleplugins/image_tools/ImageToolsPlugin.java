package ai.rorsch.moduleplugins.image_tools;

import ai.rorsch.pandagenie.module.runtime.ModulePlugin;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.media.ExifInterface;
import android.os.Build;
import android.text.TextUtils;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public class ImageToolsPlugin implements ModulePlugin {

    private static final int MAX_DECODE_SIDE = 4096;
    private static final int DEFAULT_JPEG_QUALITY = 80;

    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        try {
            JSONObject params = new JSONObject(emptyJson(paramsJson));
            switch (action) {
                case "getImageInfo":
                    return ok(getImageInfo(params.optString("path", "").trim()));
                case "resizeImage":
                    return ok(resizeImage(params));
                case "compressImage":
                    return ok(compressImage(params));
                case "convertFormat":
                    return ok(convertFormat(params));
                case "rotateImage":
                    return ok(rotateImage(params));
                case "cropImage":
                    return ok(cropImage(params));
                default:
                    return error("Unsupported action: " + action);
            }
        } catch (Exception e) {
            String msg = e.getMessage();
            return error(msg != null && !msg.isEmpty() ? msg : e.getClass().getSimpleName());
        }
    }

    private static String getImageInfo(String path) throws Exception {
        requireReadableFile(path);
        File file = new File(path);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IllegalArgumentException("cannot read image bounds");
        }
        JSONObject o = new JSONObject();
        o.put("path", path);
        o.put("width", bounds.outWidth);
        o.put("height", bounds.outHeight);
        o.put("mimeType", bounds.outMimeType != null ? bounds.outMimeType : JSONObject.NULL);
        o.put("fileSizeBytes", file.length());
        o.put("exif", readExifJson(path));
        return o.toString();
    }

    private static JSONObject readExifJson(String path) {
        JSONObject exif = new JSONObject();
        try {
            ExifInterface ei = new ExifInterface(path);
            exif.put("orientation", ei.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED));
            String dateTime = ei.getAttribute(ExifInterface.TAG_DATETIME);
            exif.put("dateTime", dateTime != null ? dateTime : JSONObject.NULL);
            String dateTimeOrig = ei.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL);
            exif.put("dateTimeOriginal", dateTimeOrig != null ? dateTimeOrig : JSONObject.NULL);
            float[] latlon = new float[2];
            if (ei.getLatLong(latlon)) {
                JSONObject gps = new JSONObject();
                gps.put("latitude", latlon[0]);
                gps.put("longitude", latlon[1]);
                String alt = ei.getAttribute(ExifInterface.TAG_GPS_ALTITUDE);
                if (alt != null) {
                    gps.put("altitude", alt);
                }
                exif.put("gps", gps);
            } else {
                exif.put("gps", JSONObject.NULL);
            }
        } catch (Exception e) {
            try { exif.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()); } catch (Exception ignored) {}
        }
        return exif;
    }

    private static String resizeImage(JSONObject params) throws Exception {
        String path = params.optString("path", "").trim();
        requireReadableFile(path);
        int outW = params.optInt("width", -1);
        int outH = params.optInt("height", -1);
        if (outW <= 0 || outH <= 0) {
            throw new IllegalArgumentException("width and height must be positive");
        }
        BitmapFactory.Options b = decodeBounds(path);
        String outputPath = outputOrDefault(params.optString("outputPath", "").trim(), path, "_resized");
        Bitmap src = null;
        Bitmap scaled = null;
        try {
            src = loadBitmapForProcessing(path);
            scaled = Bitmap.createScaledBitmap(src, outW, outH, true);
            Bitmap.CompressFormat fmt = compressFormatForPath(outputPath, path);
            int q = jpegQualityForFormat(fmt, DEFAULT_JPEG_QUALITY);
            saveBitmap(scaled, outputPath, fmt, q);
            JSONObject o = new JSONObject();
            o.put("inputPath", path);
            o.put("outputPath", outputPath);
            o.put("originalWidth", b.outWidth);
            o.put("originalHeight", b.outHeight);
            o.put("decodedWidth", src.getWidth());
            o.put("decodedHeight", src.getHeight());
            o.put("outputWidth", scaled.getWidth());
            o.put("outputHeight", scaled.getHeight());
            o.put("subsampled", computeInSampleSize(b.outWidth, b.outHeight, MAX_DECODE_SIDE) > 1);
            o.put("format", formatName(fmt));
            return o.toString();
        } finally {
            if (scaled != null && scaled != src) {
                recycleQuietly(scaled);
            }
            recycleQuietly(src);
        }
    }

    private static String compressImage(JSONObject params) throws Exception {
        String path = params.optString("path", "").trim();
        requireReadableFile(path);
        int quality = params.optInt("quality", DEFAULT_JPEG_QUALITY);
        if (quality < 1) {
            quality = 1;
        }
        if (quality > 100) {
            quality = 100;
        }
        String outputPath = outputOrDefault(params.optString("outputPath", "").trim(), path, "_compressed");
        BitmapFactory.Options b = decodeBounds(path);
        Bitmap src = null;
        try {
            src = loadBitmapForProcessing(path);
            Bitmap.CompressFormat fmt = compressFormatForPath(outputPath, path);
            int q = jpegQualityForFormat(fmt, quality);
            saveBitmap(src, outputPath, fmt, q);
            File out = new File(outputPath);
            JSONObject o = new JSONObject();
            o.put("inputPath", path);
            o.put("outputPath", outputPath);
            o.put("quality", q);
            o.put("originalWidth", b.outWidth);
            o.put("originalHeight", b.outHeight);
            o.put("decodedWidth", src.getWidth());
            o.put("decodedHeight", src.getHeight());
            o.put("subsampled", computeInSampleSize(b.outWidth, b.outHeight, MAX_DECODE_SIDE) > 1);
            o.put("format", formatName(fmt));
            o.put("outputSizeBytes", out.length());
            return o.toString();
        } finally {
            recycleQuietly(src);
        }
    }

    private static String convertFormat(JSONObject params) throws Exception {
        String path = params.optString("path", "").trim();
        requireReadableFile(path);
        String fmtRaw = params.optString("format", "").trim().toLowerCase();
        Bitmap.CompressFormat target = parseTargetFormat(fmtRaw);
        String outputPath = params.optString("outputPath", "").trim();
        if (TextUtils.isEmpty(outputPath)) {
            outputPath = defaultPathWithFormat(path, "_converted", target);
        }
        BitmapFactory.Options b = decodeBounds(path);
        Bitmap src = null;
        try {
            src = loadBitmapForProcessing(path);
            int q = target == Bitmap.CompressFormat.PNG ? 100 : DEFAULT_JPEG_QUALITY;
            q = jpegQualityForFormat(target, q);
            saveBitmap(src, outputPath, target, q);
            JSONObject o = new JSONObject();
            o.put("inputPath", path);
            o.put("outputPath", outputPath);
            o.put("originalWidth", b.outWidth);
            o.put("originalHeight", b.outHeight);
            o.put("decodedWidth", src.getWidth());
            o.put("decodedHeight", src.getHeight());
            o.put("subsampled", computeInSampleSize(b.outWidth, b.outHeight, MAX_DECODE_SIDE) > 1);
            o.put("format", formatName(target));
            o.put("outputSizeBytes", new File(outputPath).length());
            return o.toString();
        } finally {
            recycleQuietly(src);
        }
    }

    private static String rotateImage(JSONObject params) throws Exception {
        String path = params.optString("path", "").trim();
        requireReadableFile(path);
        int degrees = normalizeRotationDegrees(params.optInt("degrees", Integer.MIN_VALUE));
        if (degrees == Integer.MIN_VALUE) {
            throw new IllegalArgumentException("degrees must be 90, 180, or 270");
        }
        String outputPath = outputOrDefault(params.optString("outputPath", "").trim(), path, "_rotated");
        BitmapFactory.Options b = decodeBounds(path);
        Bitmap src = null;
        Bitmap rotated = null;
        try {
            src = loadBitmapForProcessing(path);
            Matrix m = new Matrix();
            m.postRotate(degrees);
            rotated = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
            Bitmap.CompressFormat fmt = compressFormatForPath(outputPath, path);
            int q = jpegQualityForFormat(fmt, DEFAULT_JPEG_QUALITY);
            saveBitmap(rotated, outputPath, fmt, q);
            JSONObject o = new JSONObject();
            o.put("inputPath", path);
            o.put("outputPath", outputPath);
            o.put("degrees", degrees);
            o.put("originalWidth", b.outWidth);
            o.put("originalHeight", b.outHeight);
            o.put("decodedWidth", src.getWidth());
            o.put("decodedHeight", src.getHeight());
            o.put("outputWidth", rotated.getWidth());
            o.put("outputHeight", rotated.getHeight());
            o.put("subsampled", computeInSampleSize(b.outWidth, b.outHeight, MAX_DECODE_SIDE) > 1);
            o.put("format", formatName(fmt));
            return o.toString();
        } finally {
            if (rotated != null && rotated != src) {
                recycleQuietly(rotated);
            }
            recycleQuietly(src);
        }
    }

    private static String cropImage(JSONObject params) throws Exception {
        String path = params.optString("path", "").trim();
        requireReadableFile(path);
        int x = params.optInt("x", Integer.MIN_VALUE);
        int y = params.optInt("y", Integer.MIN_VALUE);
        int w = params.optInt("width", -1);
        int h = params.optInt("height", -1);
        if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE) {
            throw new IllegalArgumentException("x and y are required");
        }
        if (w <= 0 || h <= 0) {
            throw new IllegalArgumentException("width and height must be positive");
        }
        String outputPath = outputOrDefault(params.optString("outputPath", "").trim(), path, "_cropped");
        BitmapFactory.Options bounds = decodeBounds(path);
        int iw = bounds.outWidth;
        int ih = bounds.outHeight;
        if (x < 0 || y < 0 || x >= iw || y >= ih) {
            throw new IllegalArgumentException("crop origin out of bounds");
        }
        int cw = Math.min(w, iw - x);
        int ch = Math.min(h, ih - y);
        if (cw <= 0 || ch <= 0) {
            throw new IllegalArgumentException("invalid crop rectangle");
        }
        Bitmap cropped = null;
        BitmapRegionDecoder decoder = null;
        try {
            decoder = BitmapRegionDecoder.newInstance(path, false);
            if (decoder == null) {
                throw new IllegalArgumentException("cannot open region decoder");
            }
            Rect rect = new Rect(x, y, x + cw, y + ch);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = computeInSampleSize(cw, ch, MAX_DECODE_SIDE);
            cropped = decoder.decodeRegion(rect, opts);
            if (cropped == null) {
                throw new IllegalArgumentException("crop decode failed");
            }
            Bitmap.CompressFormat fmt = compressFormatForPath(outputPath, path);
            int q = jpegQualityForFormat(fmt, DEFAULT_JPEG_QUALITY);
            saveBitmap(cropped, outputPath, fmt, q);
            JSONObject o = new JSONObject();
            o.put("inputPath", path);
            o.put("outputPath", outputPath);
            o.put("requestedX", x);
            o.put("requestedY", y);
            o.put("requestedWidth", w);
            o.put("requestedHeight", h);
            o.put("cropX", rect.left);
            o.put("cropY", rect.top);
            o.put("cropWidth", rect.width());
            o.put("cropHeight", rect.height());
            o.put("imageWidth", iw);
            o.put("imageHeight", ih);
            o.put("outputWidth", cropped.getWidth());
            o.put("outputHeight", cropped.getHeight());
            o.put("regionSubsample", opts.inSampleSize > 1);
            o.put("format", formatName(fmt));
            return o.toString();
        } finally {
            recycleQuietly(cropped);
            if (decoder != null) {
                try {
                    decoder.recycle();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static BitmapFactory.Options decodeBounds(String path) throws Exception {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, opts);
        if (opts.outWidth <= 0 || opts.outHeight <= 0) {
            throw new IllegalArgumentException("cannot decode image bounds");
        }
        return opts;
    }

    /**
     * Loads a full bitmap for processing, subsampling so max(width,height) &lt;= {@link #MAX_DECODE_SIDE} when needed.
     */
    private static Bitmap loadBitmapForProcessing(String path) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, opts);
        if (opts.outWidth <= 0 || opts.outHeight <= 0) {
            throw new IllegalArgumentException("cannot decode image");
        }
        opts.inJustDecodeBounds = false;
        opts.inSampleSize = computeInSampleSize(opts.outWidth, opts.outHeight, MAX_DECODE_SIDE);
        Bitmap bmp = BitmapFactory.decodeFile(path, opts);
        if (bmp == null) {
            throw new IllegalArgumentException("decode failed");
        }
        return bmp;
    }

    private static int computeInSampleSize(int outWidth, int outHeight, int maxSide) {
        int maxDim = Math.max(outWidth, outHeight);
        int inSampleSize = 1;
        if (maxDim <= maxSide) {
            return 1;
        }
        while (maxDim / inSampleSize > maxSide) {
            inSampleSize *= 2;
        }
        return inSampleSize;
    }

    private static void saveBitmap(Bitmap bmp, String outputPath, Bitmap.CompressFormat format, int quality)
            throws IOException {
        File out = new File(outputPath);
        File parent = out.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("cannot create output directory");
        }
        try (FileOutputStream fos = new FileOutputStream(out)) {
            if (!bmp.compress(format, quality, fos)) {
                throw new IOException("compress failed");
            }
            fos.flush();
        }
    }

    private static Bitmap.CompressFormat compressFormatForPath(String outputPath, String fallbackInputPath) {
        String ext = extensionOf(outputPath);
        if (TextUtils.isEmpty(ext)) {
            ext = extensionOf(fallbackInputPath);
        }
        if ("png".equals(ext)) {
            return Bitmap.CompressFormat.PNG;
        }
        if ("webp".equals(ext)) {
            return webpCompressFormat();
        }
        return Bitmap.CompressFormat.JPEG;
    }

    private static Bitmap.CompressFormat webpCompressFormat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Bitmap.CompressFormat.WEBP_LOSSY;
        }
        return Bitmap.CompressFormat.WEBP;
    }

    private static Bitmap.CompressFormat parseTargetFormat(String fmtRaw) {
        if ("jpg".equals(fmtRaw) || "jpeg".equals(fmtRaw)) {
            return Bitmap.CompressFormat.JPEG;
        }
        if ("png".equals(fmtRaw)) {
            return Bitmap.CompressFormat.PNG;
        }
        if ("webp".equals(fmtRaw)) {
            return webpCompressFormat();
        }
        throw new IllegalArgumentException("format must be jpg, png, or webp");
    }

    private static String defaultPathWithFormat(String inputPath, String suffix, Bitmap.CompressFormat fmt) {
        String ext;
        if (fmt == Bitmap.CompressFormat.PNG) {
            ext = "png";
        } else if (isWebpFormat(fmt)) {
            ext = "webp";
        } else {
            ext = "jpg";
        }
        int slash = inputPath.lastIndexOf('/');
        int dot = inputPath.lastIndexOf('.');
        String base;
        if (dot > slash && dot > 0) {
            base = inputPath.substring(0, dot);
        } else {
            base = inputPath;
        }
        return base + suffix + "." + ext;
    }

    private static String outputOrDefault(String outputPath, String inputPath, String suffix) {
        if (!TextUtils.isEmpty(outputPath)) {
            return outputPath;
        }
        return defaultPathWithSuffix(inputPath, suffix);
    }

    private static String defaultPathWithSuffix(String inputPath, String suffix) {
        int slash = inputPath.lastIndexOf('/');
        int dot = inputPath.lastIndexOf('.');
        if (dot > slash && dot > 0) {
            return inputPath.substring(0, dot) + suffix + inputPath.substring(dot);
        }
        return inputPath + suffix;
    }

    private static String extensionOf(String path) {
        if (path == null) {
            return "";
        }
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        if (dot <= slash || dot == path.length() - 1) {
            return "";
        }
        return path.substring(dot + 1).toLowerCase();
    }

    private static int jpegQualityForFormat(Bitmap.CompressFormat fmt, int quality) {
        if (fmt == Bitmap.CompressFormat.PNG) {
            return 100;
        }
        return quality;
    }

    private static String formatName(Bitmap.CompressFormat fmt) {
        if (fmt == Bitmap.CompressFormat.PNG) {
            return "png";
        }
        if (isWebpFormat(fmt)) {
            return "webp";
        }
        return "jpeg";
    }

    private static int normalizeRotationDegrees(int raw) {
        if (raw == Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        int d = ((raw % 360) + 360) % 360;
        if (d == 90 || d == 180 || d == 270) {
            return d;
        }
        return Integer.MIN_VALUE;
    }

    private static boolean isWebpFormat(Bitmap.CompressFormat fmt) {
        if (fmt == Bitmap.CompressFormat.WEBP) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return fmt == Bitmap.CompressFormat.WEBP_LOSSY || fmt == Bitmap.CompressFormat.WEBP_LOSSLESS;
        }
        return false;
    }

    private static void requireReadableFile(String path) {
        if (TextUtils.isEmpty(path)) {
            throw new IllegalArgumentException("path is required");
        }
        File f = new File(path);
        if (!f.isFile() || !f.canRead()) {
            throw new IllegalArgumentException("file not readable: " + path);
        }
    }

    private static void recycleQuietly(Bitmap b) {
        if (b != null && !b.isRecycled()) {
            b.recycle();
        }
    }

    private static String emptyJson(String v) {
        return v == null || v.trim().isEmpty() ? "{}" : v;
    }

    private static String ok(String output) throws Exception {
        return new JSONObject().put("success", true).put("output", output).toString();
    }

    private static String error(String msg) throws Exception {
        return new JSONObject().put("success", false).put("error", msg).toString();
    }
}
