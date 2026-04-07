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
import java.util.Locale;

/**
 * PandaGenie「图片工具」模块插件。
 * <p>
 * <b>模块用途：</b>对本地磁盘上的图片进行信息查询、缩放、压缩、格式转换、旋转与裁剪等处理，
 * 输出文件路径与结构化 JSON 结果，供上层展示或继续调用。
 * </p>
 * <p>
 * <b>对外 API（{@link #invoke} 的 {@code action} 参数）：</b>
 * </p>
 * <ul>
 *   <li>{@code getImageInfo} — 读取图片宽高、MIME、文件大小及 EXIF（朝向、拍摄时间、GPS 等）</li>
 *   <li>{@code resizeImage} — 将图片缩放到指定宽高</li>
 *   <li>{@code compressImage} — 按质量压缩（具体编码格式由输出路径扩展名或原图推断）</li>
 *   <li>{@code convertFormat} — 转换为 jpg / png / webp</li>
 *   <li>{@code rotateImage} — 顺时针旋转 90°、180° 或 270°</li>
 *   <li>{@code cropImage} — 按矩形区域裁剪（支持大图子采样解码）</li>
 * </ul>
 * <p>
 * 本类实现 {@link ai.rorsch.pandagenie.module.runtime.ModulePlugin}，由宿主 {@code ModuleRuntime}
 * 通过反射加载并调用 {@link #invoke}；请勿随意改动方法签名与 action 名称，以免破坏模块契约。
 * </p>
 */
public class ImageToolsPlugin implements ModulePlugin {

    /** 解码 Bitmap 时单边最大像素，避免超大图一次性载入导致 OOM。 */
    private static final int MAX_DECODE_SIDE = 4096;
    /** JPEG / WebP 等有损格式默认压缩质量（1–100）。 */
    private static final int DEFAULT_JPEG_QUALITY = 80;

    /**
     * 模块统一入口：根据 action 分发到具体图片处理逻辑。
     *
     * @param context   Android 上下文（当前实现中部分逻辑未直接使用，保留以符合插件接口）
     * @param action    操作名，如 {@code getImageInfo}、{@code resizeImage} 等
     * @param paramsJson  JSON 字符串参数；可为空，内部会按 {@code "{}"} 解析
     * @return 成功时为 {@code {"success":true,"output":"...","_displayText":"..."}} 形式字符串；
     *         失败时为 {@code {"success":false,"error":"..."}}；{@code output} 多为业务 JSON 字符串
     * @throws Exception 解析或构造响应 JSON 时可能抛出
     */
    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        try {
            JSONObject params = new JSONObject(emptyJson(paramsJson));
            switch (action) {
                case "getImageInfo": {
                    String out = getImageInfo(params.optString("path", "").trim());
                    return ok(out, formatGetImageInfoDisplay(out));
                }
                case "resizeImage": {
                    String out = resizeImage(params);
                    return ok(out, formatResizeImageDisplay(out));
                }
                case "compressImage": {
                    String out = compressImage(params);
                    return ok(out, formatCompressImageDisplay(out));
                }
                case "convertFormat": {
                    String out = convertFormat(params);
                    return ok(out, formatConvertFormatDisplay(out));
                }
                case "rotateImage": {
                    String out = rotateImage(params);
                    return ok(out, formatRotateImageDisplay(out));
                }
                case "cropImage": {
                    String out = cropImage(params);
                    return ok(out, formatCropImageDisplay(out));
                }
                default:
                    return error("Unsupported action: " + action);
            }
        } catch (Exception e) {
            String msg = e.getMessage();
            return error(msg != null && !msg.isEmpty() ? msg : e.getClass().getSimpleName());
        }
    }

    /**
     * 读取图片元信息（不解码全图像素，仅边界与 EXIF）。
     *
     * @param path 本地可读图片文件绝对路径
     * @return 描述 path、宽高、mime、文件大小、exif 的 JSON 字符串
     * @throws Exception 文件不可读或无法解析边界时抛出
     */
    private static String getImageInfo(String path) throws Exception {
        requireReadableFile(path);
        File file = new File(path);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true; // 只读尺寸与类型，不分配像素内存
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

    /**
     * 读取指定路径图片的 EXIF 信息并封装为 JSON（失败时写入 error 字段）。
     *
     * @param path 图片文件路径
     * @return 包含 orientation、时间、gps 等字段的 JSONObject；异常时尽量带 {@code error} 说明
     */
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

    /**
     * 将图片缩放到指定宽高并保存。
     *
     * @param params 需含 {@code path}、{@code width}、{@code height}；可选 {@code outputPath}
     * @return 含输入输出路径、原图/解码/输出尺寸、是否子采样、格式等信息的 JSON 字符串
     * @throws Exception 参数非法、解码或写入失败时抛出
     */
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
            scaled = Bitmap.createScaledBitmap(src, outW, outH, true); // 双线性等到目标尺寸
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

    /**
     * 在保持（子采样后）像素矩阵的前提下，按质量重新编码压缩图片。
     *
     * @param params 需含 {@code path}；可选 {@code quality}（1–100，默认 {@link #DEFAULT_JPEG_QUALITY}）、{@code outputPath}
     * @return 含质量、路径、尺寸、输出文件字节数等的 JSON 字符串
     * @throws Exception 参数或 IO 错误时抛出
     */
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

    /**
     * 将图片解码后按目标格式重新编码保存（可改变扩展名对应编码）。
     *
     * @param params 需含 {@code path}、{@code format}（jpg/png/webp）；可选 {@code outputPath}，空则自动生成带后缀路径
     * @return 输入输出路径、尺寸、格式、输出大小等 JSON 字符串
     * @throws Exception 格式不支持或读写失败时抛出
     */
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
            int q = target == Bitmap.CompressFormat.PNG ? 100 : DEFAULT_JPEG_QUALITY; // PNG 为无损，质量固定 100
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

    /**
     * 将图片按固定角度（90/180/270）旋转后保存。
     *
     * @param params 需含 {@code path}、{@code degrees}（规范化后为 90、180 或 270）；可选 {@code outputPath}
     * @return 含旋转角度、各阶段尺寸、输出路径等的 JSON 字符串
     * @throws Exception 角度非法或处理失败时抛出
     */
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
            m.postRotate(degrees); // 围绕原点旋转，后续 createBitmap 会应用变换矩阵
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

    /**
     * 使用 {@link BitmapRegionDecoder} 按矩形区域裁剪图片并保存（大图时可子采样）。
     *
     * @param params 需含 {@code path}、{@code x}、{@code y}、{@code width}、{@code height}；可选 {@code outputPath}
     * @return 含请求区域、实际裁剪矩形、原图与输出尺寸等的 JSON 字符串
     * @throws Exception 区域越界或解码失败时抛出
     */
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
        // cw/ch 可能与请求的 w/h 不同：在图像边缘处自动夹紧到有效范围
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

    /**
     * 仅解析图片宽高与 MIME，不加载像素。
     *
     * @param path 图片路径
     * @return 已设置 {@code inJustDecodeBounds} 且 outWidth/outHeight 有效的 Options
     * @throws Exception 无法解析边界时抛出
     */
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
     * 加载用于后续处理的完整位图；若原图过大则通过 {@code inSampleSize} 子采样，
     * 使解码后最大边不超过 {@link #MAX_DECODE_SIDE}，降低 OOM 风险。
     *
     * @param path 图片文件路径
     * @return 解码后的 {@link Bitmap}
     * @throws IllegalArgumentException 无法解码时抛出
     */
    private static Bitmap loadBitmapForProcessing(String path) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, opts);
        if (opts.outWidth <= 0 || opts.outHeight <= 0) {
            throw new IllegalArgumentException("cannot decode image");
        }
        opts.inJustDecodeBounds = false;
        opts.inSampleSize = computeInSampleSize(opts.outWidth, opts.outHeight, MAX_DECODE_SIDE); // 2 的幂次递增至满足边长上限
        Bitmap bmp = BitmapFactory.decodeFile(path, opts);
        if (bmp == null) {
            throw new IllegalArgumentException("decode failed");
        }
        return bmp;
    }

    /**
     * 根据原始最大边与允许的最大边，计算 {@link BitmapFactory.Options#inSampleSize}（2 的幂）。
     *
     * @param outWidth  原始宽
     * @param outHeight 原始高
     * @param maxSide   允许的最大边长（像素）
     * @return 至少为 1 的采样倍数
     */
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

    /**
     * 将位图压缩写入指定路径（自动创建父目录）。
     *
     * @param bmp        待保存位图
     * @param outputPath 输出文件绝对路径
     * @param format     压缩格式（JPEG/PNG/WebP 等）
     * @param quality    压缩质量；PNG 实际由 {@link #jpegQualityForFormat} 等处规范
     * @throws IOException 创建目录失败或 compress 返回 false 时抛出
     */
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

    /**
     * 根据输出路径扩展名推断压缩格式；若无扩展名则回退到输入路径扩展名。
     *
     * @param outputPath        输出文件路径
     * @param fallbackInputPath 用于推断格式的备用输入路径
     * @return 对应的 {@link Bitmap.CompressFormat}
     */
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

    /**
     * 返回当前系统 API 级别下应使用的 WebP 压缩枚举（R+ 使用有损 WEBP_LOSSY）。
     *
     * @return WebP 对应的 {@link Bitmap.CompressFormat}
     */
    private static Bitmap.CompressFormat webpCompressFormat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Bitmap.CompressFormat.WEBP_LOSSY;
        }
        return Bitmap.CompressFormat.WEBP;
    }

    /**
     * 将字符串格式名解析为 {@link Bitmap.CompressFormat}。
     *
     * @param fmtRaw 小写格式关键字：jpg/jpeg、png、webp
     * @return 对应压缩格式
     * @throws IllegalArgumentException 不支持的关键字
     */
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

    /**
     * 在去掉原扩展名的路径后追加后缀，并接上目标格式对应扩展名。
     *
     * @param inputPath 原图路径
     * @param suffix    文件名中插入的后缀（如 {@code "_converted"}）
     * @param fmt       目标格式
     * @return 新的完整输出路径字符串
     */
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

    /**
     * 若调用方指定了 {@code outputPath} 则原样返回，否则生成默认带后缀路径。
     *
     * @param outputPath 用户指定的输出路径，可为空
     * @param inputPath  输入路径，用于生成默认名
     * @param suffix     插入文件名与扩展名之间的后缀
     * @return 最终输出路径
     */
    private static String outputOrDefault(String outputPath, String inputPath, String suffix) {
        if (!TextUtils.isEmpty(outputPath)) {
            return outputPath;
        }
        return defaultPathWithSuffix(inputPath, suffix);
    }

    /**
     * 在保留原扩展名的前提下，在「文件名.扩展名」之间插入后缀。
     *
     * @param inputPath 输入文件路径
     * @param suffix    后缀片段
     * @return 新路径
     */
    private static String defaultPathWithSuffix(String inputPath, String suffix) {
        int slash = inputPath.lastIndexOf('/');
        int dot = inputPath.lastIndexOf('.');
        if (dot > slash && dot > 0) {
            return inputPath.substring(0, dot) + suffix + inputPath.substring(dot);
        }
        return inputPath + suffix;
    }

    /**
     * 提取路径中的小写扩展名（不含点）。
     *
     * @param path 文件路径
     * @return 扩展名，无法识别时返回空串
     */
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

    /**
     * PNG 无损编码忽略 quality，统一返回 100；其余格式使用传入 quality。
     *
     * @param fmt     压缩格式
     * @param quality 期望质量
     * @return 实际传入 {@link Bitmap#compress} 的质量参数
     */
    private static int jpegQualityForFormat(Bitmap.CompressFormat fmt, int quality) {
        if (fmt == Bitmap.CompressFormat.PNG) {
            return 100;
        }
        return quality;
    }

    /**
     * 将压缩格式枚举转为简短英文 key（用于 JSON 中的 format 字段）。
     *
     * @param fmt 压缩格式
     * @return {@code png}、{@code webp} 或 {@code jpeg}
     */
    private static String formatName(Bitmap.CompressFormat fmt) {
        if (fmt == Bitmap.CompressFormat.PNG) {
            return "png";
        }
        if (isWebpFormat(fmt)) {
            return "webp";
        }
        return "jpeg";
    }

    /**
     * 将任意角度规范到 0–359 后，仅接受 90、180、270；否则返回 {@link Integer#MIN_VALUE} 表示非法。
     *
     * @param raw 原始角度；可为 {@link Integer#MIN_VALUE} 表示未提供
     * @return 合法的标准角度或 {@link Integer#MIN_VALUE}
     */
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

    /**
     * 判断是否为 WebP 相关压缩格式（含 API 30+ 的有损/无损变体）。
     *
     * @param fmt 待判断格式
     * @return 是 WebP 族则为 true
     */
    private static boolean isWebpFormat(Bitmap.CompressFormat fmt) {
        if (fmt == Bitmap.CompressFormat.WEBP) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return fmt == Bitmap.CompressFormat.WEBP_LOSSY || fmt == Bitmap.CompressFormat.WEBP_LOSSLESS;
        }
        return false;
    }

    /**
     * 校验路径非空且指向可读常规文件。
     *
     * @param path 文件路径
     * @throws IllegalArgumentException 路径无效或不可读
     */
    private static void requireReadableFile(String path) {
        if (TextUtils.isEmpty(path)) {
            throw new IllegalArgumentException("path is required");
        }
        File f = new File(path);
        if (!f.isFile() || !f.canRead()) {
            throw new IllegalArgumentException("file not readable: " + path);
        }
    }

    /**
     * 安全回收位图，忽略已回收或 null。
     *
     * @param b 位图，可为 null
     */
    private static void recycleQuietly(Bitmap b) {
        if (b != null && !b.isRecycled()) {
            b.recycle();
        }
    }

    /**
     * 将 null 或空白参数字符串规范为 {@code "{}"}，便于 {@link JSONObject} 构造。
     *
     * @param v 原始 JSON 字符串
     * @return 非空 JSON 对象字面量字符串
     */
    private static String emptyJson(String v) {
        return v == null || v.trim().isEmpty() ? "{}" : v;
    }

    private static String mdCell(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("|", "\\|").replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ');
    }

    /**
     * 将 {@code getImageInfo} 的 JSON 输出格式化为用户可读展示文案。
     *
     * @param outputJson {@code getImageInfo} 返回的 output 字符串
     * @return 多行展示文本
     * @throws Exception JSON 解析异常
     */
    private static String formatGetImageInfoDisplay(String outputJson) throws Exception {
        JSONObject o = new JSONObject(outputJson);
        int w = o.optInt("width");
        int h = o.optInt("height");
        String formatLabel;
        if (o.isNull("mimeType")) {
            formatLabel = "Unknown";
        } else {
            formatLabel = mimeTypeToDisplayFormat(o.optString("mimeType"));
        }
        long sizeBytes = o.optLong("fileSizeBytes");
        String path = o.optString("path", "");
        StringBuilder sb = new StringBuilder();
        sb.append("🖼️ Image Info\n\n");
        sb.append("| Item | Value |\n|---|---|\n");
        sb.append("| Dimensions | ").append(w).append("×").append(h).append(" |\n");
        sb.append("| Format | ").append(mdCell(formatLabel)).append(" |\n");
        sb.append("| File size | ").append(formatFileSizeMb(sizeBytes)).append(" |\n");
        sb.append("| Path | ").append(mdCell(path)).append(" |\n");
        JSONObject exif = o.optJSONObject("exif");
        if (exif != null) {
            if (exif.has("error") && !exif.isNull("error")) {
                sb.append("| EXIF | ").append(mdCell(exif.optString("error"))).append(" |\n");
            } else {
                sb.append("| EXIF orientation | ").append(exif.optInt("orientation")).append(" |\n");
                String dt = exif.isNull("dateTime") ? "—" : exif.optString("dateTime");
                sb.append("| EXIF date/time | ").append(mdCell(dt)).append(" |\n");
                String dto = exif.isNull("dateTimeOriginal") ? "—" : exif.optString("dateTimeOriginal");
                sb.append("| EXIF date/time (original) | ").append(mdCell(dto)).append(" |\n");
                if (!exif.isNull("gps")) {
                    JSONObject gps = exif.optJSONObject("gps");
                    if (gps != null) {
                        sb.append("| GPS latitude | ").append(gps.optDouble("latitude")).append(" |\n");
                        sb.append("| GPS longitude | ").append(gps.optDouble("longitude")).append(" |\n");
                        if (gps.has("altitude") && !gps.isNull("altitude")) {
                            sb.append("| GPS altitude | ").append(mdCell(gps.optString("altitude"))).append(" |\n");
                        }
                    }
                } else {
                    sb.append("| GPS | — |\n");
                }
            }
        }
        return sb.toString().trim();
    }

    /**
     * 格式化缩放结果的展示文案。
     *
     * @param outputJson {@code resizeImage} 的 output JSON
     * @return 展示文本
     * @throws Exception JSON 解析异常
     */
    private static String formatResizeImageDisplay(String outputJson) throws Exception {
        JSONObject o = new JSONObject(outputJson);
        int ow = o.optInt("outputWidth");
        int oh = o.optInt("outputHeight");
        String path = o.optString("outputPath");
        String inPath = o.optString("inputPath");
        return "✅ Image resized\n\n"
                + "| Item | Value |\n|---|---|\n"
                + "| Input path | " + mdCell(inPath) + " |\n"
                + "| Original size | " + o.optInt("originalWidth") + "×" + o.optInt("originalHeight") + " |\n"
                + "| Decoded size | " + o.optInt("decodedWidth") + "×" + o.optInt("decodedHeight") + " |\n"
                + "| Output size | " + ow + "×" + oh + " |\n"
                + "| Subsampled | " + (o.optBoolean("subsampled") ? "yes" : "no") + " |\n"
                + "| Format | " + mdCell(o.optString("format")) + " |\n"
                + "| Output path | " + mdCell(path) + " |";
    }

    /**
     * 格式化压缩前后大小对比的展示文案。
     *
     * @param outputJson {@code compressImage} 的 output JSON
     * @return 展示文本
     * @throws Exception JSON 解析异常
     */
    private static String formatCompressImageDisplay(String outputJson) throws Exception {
        JSONObject o = new JSONObject(outputJson);
        String inPath = o.optString("inputPath");
        long before = new File(inPath).length();
        long after = o.optLong("outputSizeBytes");
        String path = o.optString("outputPath");
        return "✅ Image compressed\n\n"
                + "| Item | Value |\n|---|---|\n"
                + "| Input path | " + mdCell(inPath) + " |\n"
                + "| Size before | " + formatFileSizeMb(before) + " |\n"
                + "| Size after | " + formatFileSizeMb(after) + " |\n"
                + "| Quality | " + o.optInt("quality") + " |\n"
                + "| Original size | " + o.optInt("originalWidth") + "×" + o.optInt("originalHeight") + " |\n"
                + "| Decoded size | " + o.optInt("decodedWidth") + "×" + o.optInt("decodedHeight") + " |\n"
                + "| Subsampled | " + (o.optBoolean("subsampled") ? "yes" : "no") + " |\n"
                + "| Format | " + mdCell(o.optString("format")) + " |\n"
                + "| Output path | " + mdCell(path) + " |";
    }

    /**
     * 格式化格式转换结果的展示文案。
     *
     * @param outputJson {@code convertFormat} 的 output JSON
     * @return 展示文本
     * @throws Exception JSON 解析异常
     */
    private static String formatConvertFormatDisplay(String outputJson) throws Exception {
        JSONObject o = new JSONObject(outputJson);
        String fmt = o.optString("format");
        String label = formatKeyToUpperLabel(fmt);
        String path = o.optString("outputPath");
        String inPath = o.optString("inputPath");
        return "✅ Image format converted\n\n"
                + "| Item | Value |\n|---|---|\n"
                + "| Input path | " + mdCell(inPath) + " |\n"
                + "| Output format | " + mdCell(label) + " |\n"
                + "| Original size | " + o.optInt("originalWidth") + "×" + o.optInt("originalHeight") + " |\n"
                + "| Decoded size | " + o.optInt("decodedWidth") + "×" + o.optInt("decodedHeight") + " |\n"
                + "| Subsampled | " + (o.optBoolean("subsampled") ? "yes" : "no") + " |\n"
                + "| Output size (bytes) | " + o.optLong("outputSizeBytes") + " |\n"
                + "| Output path | " + mdCell(path) + " |";
    }

    /**
     * 格式化旋转结果的展示文案。
     *
     * @param outputJson {@code rotateImage} 的 output JSON
     * @return 展示文本
     * @throws Exception JSON 解析异常
     */
    private static String formatRotateImageDisplay(String outputJson) throws Exception {
        JSONObject o = new JSONObject(outputJson);
        int deg = o.optInt("degrees");
        String path = o.optString("outputPath");
        String inPath = o.optString("inputPath");
        return "✅ Image rotated\n\n"
                + "| Item | Value |\n|---|---|\n"
                + "| Input path | " + mdCell(inPath) + " |\n"
                + "| Degrees | " + deg + " |\n"
                + "| Original size | " + o.optInt("originalWidth") + "×" + o.optInt("originalHeight") + " |\n"
                + "| Decoded size | " + o.optInt("decodedWidth") + "×" + o.optInt("decodedHeight") + " |\n"
                + "| Output size | " + o.optInt("outputWidth") + "×" + o.optInt("outputHeight") + " |\n"
                + "| Subsampled | " + (o.optBoolean("subsampled") ? "yes" : "no") + " |\n"
                + "| Format | " + mdCell(o.optString("format")) + " |\n"
                + "| Output path | " + mdCell(path) + " |";
    }

    /**
     * 格式化裁剪结果的展示文案。
     *
     * @param outputJson {@code cropImage} 的 output JSON
     * @return 展示文本
     * @throws Exception JSON 解析异常
     */
    private static String formatCropImageDisplay(String outputJson) throws Exception {
        JSONObject o = new JSONObject(outputJson);
        String path = o.optString("outputPath");
        String inPath = o.optString("inputPath");
        return "✅ Image cropped\n\n"
                + "| Item | Value |\n|---|---|\n"
                + "| Input path | " + mdCell(inPath) + " |\n"
                + "| Requested region | " + o.optInt("requestedX") + "," + o.optInt("requestedY") + " "
                + o.optInt("requestedWidth") + "×" + o.optInt("requestedHeight") + " |\n"
                + "| Actual crop | " + o.optInt("cropX") + "," + o.optInt("cropY") + " "
                + o.optInt("cropWidth") + "×" + o.optInt("cropHeight") + " |\n"
                + "| Source image | " + o.optInt("imageWidth") + "×" + o.optInt("imageHeight") + " |\n"
                + "| Output size | " + o.optInt("outputWidth") + "×" + o.optInt("outputHeight") + " |\n"
                + "| Region subsampled | " + (o.optBoolean("regionSubsample") ? "yes" : "no") + " |\n"
                + "| Format | " + mdCell(o.optString("format")) + " |\n"
                + "| Output path | " + mdCell(path) + " |";
    }

    /**
     * 将 MIME 类型转为简短展示用格式名（如 JPEG、PNG）。
     *
     * @param mime 原始 MIME，可为 null
     * @return 展示标签
     */
    private static String mimeTypeToDisplayFormat(String mime) {
        if (mime == null || mime.isEmpty()) {
            return "Unknown";
        }
        String m = mime.toLowerCase(Locale.US);
        if (m.contains("jpeg") || m.endsWith("jpg")) {
            return "JPEG";
        }
        if (m.contains("png")) {
            return "PNG";
        }
        if (m.contains("webp")) {
            return "WEBP";
        }
        if (m.contains("gif")) {
            return "GIF";
        }
        if (m.contains("bmp") || m.contains("x-ms-bmp")) {
            return "BMP";
        }
        if (m.startsWith("image/")) {
            return m.substring(6).toUpperCase(Locale.US).replace('-', '_');
        }
        return mime;
    }

    /**
     * 将内部格式 key（jpeg/png/webp）转为大写展示标签。
     *
     * @param formatKey 小写或混合大小写格式名
     * @return 展示用标签
     */
    private static String formatKeyToUpperLabel(String formatKey) {
        if (formatKey == null || formatKey.isEmpty()) {
            return "UNKNOWN";
        }
        switch (formatKey.toLowerCase(Locale.US)) {
            case "jpeg":
            case "jpg":
                return "JPEG";
            case "png":
                return "PNG";
            case "webp":
                return "WEBP";
            default:
                return formatKey.toUpperCase(Locale.US);
        }
    }

    /**
     * 将字节数格式化为一位小数的 MB 字符串（用于 UI）。
     *
     * @param bytes 字节数，负数按 0 处理
     * @return 如 {@code "1.2 MB"}
     */
    private static String formatFileSizeMb(long bytes) {
        if (bytes < 0) {
            bytes = 0;
        }
        double mb = bytes / (1024.0 * 1024.0);
        return String.format(Locale.US, "%.1f MB", mb);
    }

    /**
     * 构造成功响应 JSON，可选附带 {@code _displayText}。
     *
     * @param output      业务结果字符串（常为嵌套 JSON）
     * @param displayText 可选的展示文案；null 或空则省略该字段
     * @return 完整响应 JSON 字符串
     * @throws Exception JSON 构造异常
     */
    private static String ok(String output, String displayText) throws Exception {
        JSONObject r = new JSONObject().put("success", true).put("output", output);
        if (displayText != null && !displayText.isEmpty()) {
            r.put("_displayText", displayText);
        }
        return r.toString();
    }

    /**
     * 成功响应且不附加展示文案。
     *
     * @param output 业务 output 字符串
     * @return JSON 响应
     * @throws Exception JSON 构造异常
     */
    private static String ok(String output) throws Exception {
        return ok(output, null);
    }

    /**
     * 构造失败响应 JSON。
     *
     * @param msg 错误信息
     * @return {@code success=false} 的 JSON 字符串
     * @throws Exception JSON 构造异常
     */
    private static String error(String msg) throws Exception {
        return new JSONObject().put("success", false).put("error", msg).toString();
    }
}
