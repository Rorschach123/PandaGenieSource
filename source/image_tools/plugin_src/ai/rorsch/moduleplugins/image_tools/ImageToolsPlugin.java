package ai.rorsch.moduleplugins.image_tools;

import ai.rorsch.pandagenie.module.runtime.HtmlOutputHelper;
import ai.rorsch.pandagenie.module.runtime.ModulePlugin;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Size;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
 *   <li>{@code listGalleryImages} — 读取默认相册或指定相册图片列表</li>
 *   <li>{@code analyzeGalleryImages} — 汇总相册构成，按截图、文档图、缩略图、低清图等分类</li>
 *   <li>{@code findLowQualityImages} — 查找疑似模糊、过小、缩略图和低质量照片候选</li>
 *   <li>{@code findDuplicateImages} — 使用 SHA-256 查找字节级完全重复图片</li>
 *   <li>{@code findSimilarImages} — 使用 dHash 查找视觉相似图片和缩略图候选</li>
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
                    JSONArray rc = new JSONArray();
                    rc.put(richImage(params.optString("path", "").trim(), isZh() ? "图片预览" : "Image preview"));
                    return ok(out, formatGetImageInfoDisplay(out), formatGetImageInfoHtml(out), rc);
                }
                case "resizeImage": {
                    String out = resizeImage(params);
                    JSONObject outJson = new JSONObject(out);
                    JSONArray rc = new JSONArray();
                    rc.put(richImage(outJson.optString("outputPath"), isZh() ? "调整后的图片" : "Resized image"));
                    return ok(out, formatResizeImageDisplay(out), formatResizeImageHtml(out), rc);
                }
                case "compressImage": {
                    String out = compressImage(params);
                    JSONObject outJson = new JSONObject(out);
                    JSONArray rc = new JSONArray();
                    rc.put(richImage(outJson.optString("outputPath"), isZh() ? "压缩后的图片" : "Compressed image"));
                    return ok(out, formatCompressImageDisplay(out), formatCompressImageHtml(out), rc);
                }
                case "convertFormat": {
                    String out = convertFormat(params);
                    JSONObject outJson = new JSONObject(out);
                    JSONArray rc = new JSONArray();
                    rc.put(richImage(outJson.optString("outputPath"), isZh() ? "转换后的图片" : "Converted image"));
                    return ok(out, formatConvertFormatDisplay(out), formatConvertFormatHtml(out), rc);
                }
                case "rotateImage": {
                    String out = rotateImage(params);
                    JSONObject outJson = new JSONObject(out);
                    JSONArray rc = new JSONArray();
                    rc.put(richImage(outJson.optString("outputPath"), isZh() ? "旋转后的图片" : "Rotated image"));
                    return ok(out, formatRotateImageDisplay(out), formatRotateImageHtml(out), rc);
                }
                case "cropImage": {
                    String out = cropImage(params);
                    JSONObject outJson = new JSONObject(out);
                    JSONArray rc = new JSONArray();
                    rc.put(richImage(outJson.optString("outputPath"), isZh() ? "裁剪后的图片" : "Cropped image"));
                    return ok(out, formatCropImageDisplay(out), formatCropImageHtml(out), rc);
                }
                case "listGalleryImages": {
                    String out = listGalleryImages(context, params);
                    return ok(out, formatListGalleryImagesDisplay(out), formatListGalleryImagesHtml(out), null);
                }
                case "analyzeGalleryImages": {
                    String out = analyzeGalleryImages(context, params);
                    return ok(out, formatAnalyzeGalleryImagesDisplay(out), formatAnalyzeGalleryImagesHtml(out), null);
                }
                case "findLowQualityImages": {
                    String out = findLowQualityImages(context, params);
                    return ok(out, formatFindLowQualityImagesDisplay(out), formatFindLowQualityImagesHtml(out), null);
                }
                case "findDuplicateImages": {
                    String out = findDuplicateImages(context, params);
                    return ok(out, formatFindDuplicateImagesDisplay(out), formatFindDuplicateImagesHtml(out), null);
                }
                case "findSimilarImages": {
                    String out = findSimilarImages(context, params);
                    return ok(out, formatFindSimilarImagesDisplay(out),
                            formatFindSimilarImagesHtml(out, false),
                            formatFindSimilarImagesHtml(out, true),
                            similarImagesRichContent(out));
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
    private static String listGalleryImages(Context context, JSONObject params) throws Exception {
        int limit = clampInt(params.optInt("limit", 100), 1, 5000);
        boolean explicitImages = hasExplicitImagePaths(params);
        List<GalleryImage> images = resolveImageScanScope(context, params, limit);
        JSONArray arr = new JSONArray();
        for (GalleryImage image : images) {
            arr.put(image.toJson(false));
        }
        JSONObject out = new JSONObject();
        putImageScopeFields(out, params, explicitImages);
        out.put("count", arr.length());
        out.put("limit", limit);
        out.put("images", arr);
        return out.toString();
    }

    private static String analyzeGalleryImages(Context context, JSONObject params) throws Exception {
        int limit = clampInt(params.optInt("limit", 5000), 1, 20000);
        int maxItems = clampInt(params.optInt("maxItemsPerCategory", 8), 1, 50);
        long largeBytes = Math.max(1024L * 1024L, params.optLong("largeBytes", 20L * 1024L * 1024L));
        boolean explicitImages = hasExplicitImagePaths(params);
        List<GalleryImage> images = resolveImageScanScope(context, params, limit);

        JSONArray screenshots = new JSONArray();
        JSONArray textDocuments = new JSONArray();
        JSONArray thumbnails = new JSONArray();
        JSONArray lowResolution = new JSONArray();
        JSONArray largest = new JSONArray();
        Map<String, Integer> byMonth = new HashMap<>();
        Map<String, Integer> byAlbum = new HashMap<>();

        for (GalleryImage image : images) {
            countByKey(byAlbum, safeLabel(image.bucket, isZh() ? "未知相册" : "Unknown album"));
            countByKey(byMonth, monthKey(image));
            if (isScreenshotLike(image)) {
                putLimited(screenshots, image.toJson(false), maxItems);
            }
            if (isTextOrDocumentLike(image)) {
                putLimited(textDocuments, image.toJson(false), maxItems);
            }
            if (isThumbnailLike(image)) {
                putLimited(thumbnails, image.toJson(false), maxItems);
            }
            if (isLowResolutionLike(image)) {
                putLimited(lowResolution, image.toJson(false), maxItems);
            }
        }

        List<GalleryImage> sortedBySize = new ArrayList<>(images);
        Collections.sort(sortedBySize, new Comparator<GalleryImage>() {
            @Override
            public int compare(GalleryImage a, GalleryImage b) {
                return Long.compare(b.sizeBytes, a.sizeBytes);
            }
        });
        for (GalleryImage image : sortedBySize) {
            if (largest.length() >= maxItems) break;
            if (image.sizeBytes >= largeBytes || largest.length() < Math.min(5, sortedBySize.size())) {
                largest.put(image.toJson(false));
            }
        }

        JSONObject categories = new JSONObject();
        categories.put("screenshots", screenshots);
        categories.put("textOrDocumentImages", textDocuments);
        categories.put("thumbnails", thumbnails);
        categories.put("lowResolutionImages", lowResolution);
        categories.put("largestImages", largest);
        categories.put("byMonth", mapToArray(byMonth, isZh() ? "月份" : "Month"));
        categories.put("byAlbum", mapToArray(byAlbum, isZh() ? "相册" : "Album"));

        JSONObject out = new JSONObject();
        putImageScopeFields(out, params, explicitImages);
        out.put("scannedImages", images.size());
        out.put("screenshotCount", screenshots.length());
        out.put("textOrDocumentCount", textDocuments.length());
        out.put("thumbnailCount", thumbnails.length());
        out.put("lowResolutionCount", lowResolution.length());
        out.put("categories", categories);
        out.put("summary", buildGalleryAnalysisSummary(images.size(), screenshots.length(),
                textDocuments.length(), thumbnails.length(), lowResolution.length()));
        out.put("safeToDeleteAutomatically", false);
        out.put("note", isZh()
                ? "分类结果是整理建议，用于先看清相册构成；删除前请逐张确认。"
                : "Categories are cleanup suggestions. Review photos before deleting anything.");
        return out.toString();
    }

    private static String findLowQualityImages(Context context, JSONObject params) throws Exception {
        int limit = clampInt(params.optInt("limit", 5000), 1, 20000);
        int maxCandidates = clampInt(params.optInt("maxCandidates", 50), 1, 500);
        boolean explicitImages = hasExplicitImagePaths(params);
        List<GalleryImage> images = resolveImageScanScope(context, params, limit);
        List<LowQualityCandidate> candidates = new ArrayList<>();
        int unreadable = 0;
        for (GalleryImage image : images) {
            LowQualityCandidate candidate = scoreLowQualityCandidate(context, image);
            if (candidate.unreadable) {
                unreadable++;
            }
            if (candidate.score > 0) {
                candidates.add(candidate);
            }
        }
        Collections.sort(candidates, new Comparator<LowQualityCandidate>() {
            @Override
            public int compare(LowQualityCandidate a, LowQualityCandidate b) {
                int byScore = Integer.compare(b.score, a.score);
                if (byScore != 0) return byScore;
                return Long.compare(a.image.sizeBytes, b.image.sizeBytes);
            }
        });
        JSONArray arr = new JSONArray();
        for (int i = 0; i < candidates.size() && i < maxCandidates; i++) {
            arr.put(candidates.get(i).toJson());
        }

        JSONObject out = new JSONObject();
        putImageScopeFields(out, params, explicitImages);
        out.put("scannedImages", images.size());
        out.put("candidateCount", arr.length());
        out.put("unreadableImages", unreadable);
        out.put("safeToDeleteAutomatically", false);
        out.put("candidates", arr);
        out.put("summary", isZh()
                ? "找到 " + arr.length() + " 张疑似低质量图片，包含缩略图、低分辨率或疑似模糊照片。"
                : "Found " + arr.length() + " possible low-quality images, including thumbnails, low-resolution, or likely blurry photos.");
        out.put("note", isZh()
                ? "这些只是候选项，不会自动删除；建议结合预览确认。"
                : "These are review candidates only; nothing is deleted automatically.");
        return out.toString();
    }

    private static String findDuplicateImages(Context context, JSONObject params) throws Exception {
        int limit = clampInt(params.optInt("limit", 5000), 1, 20000);
        long minBytes = Math.max(0L, params.optLong("minBytes", 1L));
        boolean explicitImages = hasExplicitImagePaths(params);
        List<GalleryImage> images = resolveImageScanScope(context, params, limit);
        Map<Long, List<GalleryImage>> bySize = new HashMap<>();
        int skippedSmall = 0;
        for (GalleryImage image : images) {
            if (image.sizeBytes < minBytes || image.sizeBytes <= 0) {
                skippedSmall++;
                continue;
            }
            List<GalleryImage> sameSize = bySize.get(image.sizeBytes);
            if (sameSize == null) {
                sameSize = new ArrayList<>();
                bySize.put(image.sizeBytes, sameSize);
            }
            sameSize.add(image);
        }

        JSONArray groups = new JSONArray();
        int hashed = 0;
        int unreadable = 0;
        int duplicateImages = 0;
        long reclaimableBytes = 0L;
        for (Map.Entry<Long, List<GalleryImage>> entry : bySize.entrySet()) {
            List<GalleryImage> sameSize = entry.getValue();
            if (sameSize.size() < 2) {
                continue;
            }
            Map<String, List<GalleryImage>> byHash = new HashMap<>();
            for (GalleryImage image : sameSize) {
                try {
                    String hash = sha256ForImage(context, image);
                    hashed++;
                    List<GalleryImage> sameHash = byHash.get(hash);
                    if (sameHash == null) {
                        sameHash = new ArrayList<>();
                        byHash.put(hash, sameHash);
                    }
                    sameHash.add(image);
                } catch (Exception e) {
                    unreadable++;
                }
            }
            for (Map.Entry<String, List<GalleryImage>> hashEntry : byHash.entrySet()) {
                List<GalleryImage> dupes = hashEntry.getValue();
                if (dupes.size() < 2) {
                    continue;
                }
                Collections.sort(dupes, new Comparator<GalleryImage>() {
                    @Override
                    public int compare(GalleryImage a, GalleryImage b) {
                        long ad = Math.max(a.dateModified, a.dateAdded);
                        long bd = Math.max(b.dateModified, b.dateAdded);
                        int byDate = Long.compare(bd, ad);
                        if (byDate != 0) return byDate;
                        return Long.compare(b.id, a.id);
                    }
                });
                GalleryImage keep = dupes.get(0);
                JSONArray duplicates = new JSONArray();
                long groupReclaimable = 0L;
                for (int i = 1; i < dupes.size(); i++) {
                    GalleryImage duplicate = dupes.get(i);
                    duplicates.put(duplicate.toJson(true));
                    groupReclaimable += duplicate.sizeBytes;
                }
                JSONObject group = new JSONObject();
                group.put("hash", hashEntry.getKey());
                group.put("sizeBytes", entry.getKey());
                group.put("sizeFormatted", formatFileSizeMb(entry.getKey()));
                group.put("count", dupes.size());
                group.put("keepSuggestion", keep.toJson(false));
                group.put("duplicates", duplicates);
                group.put("reclaimableBytes", groupReclaimable);
                group.put("reclaimableFormatted", formatFileSizeMb(groupReclaimable));
                groups.put(group);
                duplicateImages += Math.max(0, dupes.size() - 1);
                reclaimableBytes += groupReclaimable;
            }
        }

        JSONObject out = new JSONObject();
        putImageScopeFields(out, params, explicitImages);
        out.put("scannedImages", images.size());
        out.put("hashedImages", hashed);
        out.put("unreadableImages", unreadable);
        out.put("skippedSmallImages", skippedSmall);
        out.put("duplicateGroups", groups);
        out.put("duplicateGroupCount", groups.length());
        out.put("duplicateImageCount", duplicateImages);
        out.put("reclaimableBytes", reclaimableBytes);
        out.put("reclaimableFormatted", formatFileSizeMb(reclaimableBytes));
        out.put("note", "Exact duplicates are detected by byte-for-byte SHA-256 content hash. No files are deleted.");
        return out.toString();
    }

    private static String findSimilarImages(Context context, JSONObject params) throws Exception {
        boolean zh = isZh();
        int limit = clampInt(params.optInt("limit", 5000), 1, 5000);
        int maxDistance = clampInt(params.optInt("maxDistance", 14), 0, 24);
        int looseMaxDistance = clampInt(params.optInt("looseMaxDistance", 24), maxDistance, 32);
        int minEdge = clampInt(params.optInt("minEdge", 32), 1, 2048);
        int maxGroups = clampInt(params.optInt("maxGroups", 50), 1, 500);
        double ratioTolerance = clampDouble(params.optDouble("ratioTolerance", 0.25), 0.0, 0.75);
        int timeLimitMs = clampInt(params.optInt("timeLimitMs", 65000), 5000, 85000);
        long deadlineAt = System.currentTimeMillis() + timeLimitMs;
        boolean stoppedByTimeBudget = false;

        boolean explicitImages = hasExplicitImagePaths(params);
        String requestedAlbum = explicitImages ? "" : normalizedAlbum(params);
        String effectiveAlbum = explicitImages ? "selected_images" : effectiveSimilarAlbum(requestedAlbum);
        JSONObject queryParams = params;
        if (!explicitImages && !safeEquals(requestedAlbum, effectiveAlbum)) {
            queryParams = new JSONObject(params.toString());
            queryParams.put("album", effectiveAlbum);
        }

        int effectiveLimit = limit;
        boolean fullScan = params.optBoolean("fullScan", false);
        if (!fullScan && limit > 300) {
            effectiveLimit = 300;
        }

        List<GalleryImage> images = resolveImageScanScope(context, queryParams, effectiveLimit);
        List<SimilarImageInfo> infos = new ArrayList<>();
        int unreadable = 0;
        int skippedSmall = 0;
        for (GalleryImage image : images) {
            if (System.currentTimeMillis() >= deadlineAt) {
                stoppedByTimeBudget = true;
                break;
            }
            try {
                SimilarImageInfo info = buildSimilarImageInfo(context, image);
                if (Math.min(info.width, info.height) < minEdge) {
                    skippedSmall++;
                    continue;
                }
                infos.add(info);
            } catch (Exception e) {
                unreadable++;
            }
        }

        int n = infos.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }

        int comparedPairs = 0;
        int matchedPairs = 0;
        int thumbnailPairs = 0;
        List<SimilarPair> nearestPairs = new ArrayList<>();
        outerPairs:
        for (int i = 0; i < n; i++) {
            SimilarImageInfo a = infos.get(i);
            for (int j = i + 1; j < n; j++) {
                if (System.currentTimeMillis() >= deadlineAt) {
                    stoppedByTimeBudget = true;
                    break outerPairs;
                }
                SimilarImageInfo b = infos.get(j);
                SimilarPair pair = buildSimilarPair(a, b, ratioTolerance);
                if (!isPairWorthComparing(pair, ratioTolerance)) {
                    continue;
                }
                comparedPairs++;
                addNearestPair(nearestPairs, pair, 80);
                if (isVisualMatch(pair, maxDistance, looseMaxDistance)) {
                    union(parent, i, j);
                    matchedPairs++;
                    if (pair.thumbnailCandidate) {
                        thumbnailPairs++;
                    }
                }
            }
        }

        Map<Integer, List<SimilarImageInfo>> byRoot = new HashMap<>();
        for (int i = 0; i < n; i++) {
            int root = find(parent, i);
            List<SimilarImageInfo> group = byRoot.get(root);
            if (group == null) {
                group = new ArrayList<>();
                byRoot.put(root, group);
            }
            group.add(infos.get(i));
        }

        List<List<SimilarImageInfo>> clusters = new ArrayList<>();
        for (List<SimilarImageInfo> cluster : byRoot.values()) {
            if (cluster.size() >= 2) {
                clusters.add(cluster);
            }
        }
        Collections.sort(clusters, new Comparator<List<SimilarImageInfo>>() {
            @Override
            public int compare(List<SimilarImageInfo> a, List<SimilarImageInfo> b) {
                int bySize = Integer.compare(b.size(), a.size());
                if (bySize != 0) return bySize;
                return Long.compare(bestImageScore(b), bestImageScore(a));
            }
        });

        JSONArray groups = new JSONArray();
        int candidateImages = 0;
        int thumbnailGroups = 0;
        int strictGroups = 0;
        int nearestReviewGroups = 0;
        int emitted = 0;
        for (List<SimilarImageInfo> cluster : clusters) {
            if (emitted >= maxGroups) {
                break;
            }
            Collections.sort(cluster, new Comparator<SimilarImageInfo>() {
                @Override
                public int compare(SimilarImageInfo a, SimilarImageInfo b) {
                    return Long.compare(imageQualityScore(b.image), imageQualityScore(a.image));
                }
            });
            SimilarImageInfo keep = cluster.get(0);
            JSONArray candidates = new JSONArray();
            boolean hasThumbnailCandidate = false;
            int minDistance = 64;
            int maxGroupDistance = 0;
            for (int i = 1; i < cluster.size(); i++) {
                SimilarImageInfo candidate = cluster.get(i);
                int distance = hammingDistance(keep.dhash, candidate.dhash);
                minDistance = Math.min(minDistance, distance);
                maxGroupDistance = Math.max(maxGroupDistance, distance);
                boolean thumbnail = isLikelyThumbnail(keep, candidate, ratioTolerance);
                hasThumbnailCandidate = hasThumbnailCandidate || thumbnail;
                JSONObject candidateJson = similarImageJson(candidate, false);
                candidateJson.put("reviewCandidate", true);
                candidateJson.put("relationToKeep", thumbnail ? "thumbnailCandidate" : "visualSimilar");
                candidateJson.put("hashDistanceToKeep", distance);
                candidateJson.put("aHashDistanceToKeep", hammingDistance(keep.ahash, candidate.ahash));
                candidateJson.put("similarityPercentToKeep", similarityPercent(distance));
                candidateJson.put("dimensionRatioToKeep", ratio(candidate.pixelCount(), keep.pixelCount()));
                candidateJson.put("sizeRatioToKeep", ratio(candidate.image.sizeBytes, keep.image.sizeBytes));
                candidateJson.put("colorDistanceToKeep", rounded(colorDistance(keep, candidate), 1));
                decorateSimilarCandidate(candidateJson, thumbnail ? "medium" : "high", thumbnail ? "thumbnailCandidate" : "visualSimilar", zh);
                candidates.put(candidateJson);
            }
            if (candidates.length() == 0) {
                continue;
            }
            JSONObject keepJson = similarImageJson(keep, false);
            decorateSimilarKeep(keepJson, zh);
            int groupNumber = groups.length() + 1;
            JSONObject group = new JSONObject();
            group.put("id", "similar-group-" + groupNumber);
            group.put("title", zh ? "\u76f8\u4f3c\u7167\u7247\u7ec4 " + groupNumber : "Similar photo group " + groupNumber);
            group.put("algorithm", "dHash64+aHash64+color");
            group.put("matchType", hasThumbnailCandidate ? "thumbnailCandidate" : "visualSimilar");
            group.put("confidence", hasThumbnailCandidate ? "medium" : "high");
            group.put("safeToDeleteAutomatically", false);
            group.put("count", cluster.size());
            group.put("keepSuggestion", keepJson);
            group.put("candidates", candidates);
            group.put("minHashDistanceToKeep", minDistance == 64 ? 0 : minDistance);
            group.put("maxHashDistanceToKeep", maxGroupDistance);
            group.put("threshold", maxDistance);
            group.put("reason", hasThumbnailCandidate
                    ? "Smaller image(s) share a close perceptual hash and aspect ratio with a larger image."
                    : "Images share a close perceptual hash and aspect ratio; review before deleting.");
            group.put("summary", zh
                    ? "\u5efa\u8bae\u4fdd\u7559\u6e05\u6670\u5ea6/\u5c3a\u5bf8\u66f4\u9ad8\u7684\u7167\u7247\uff0c\u5176\u4ed6\u7167\u7247\u4ec5\u4f5c\u4eba\u5de5\u590d\u6838\u5019\u9009\u3002"
                    : "Keep the clearest/largest photo; other photos are review candidates only.");
            groups.put(group);
            emitted++;
            strictGroups++;
            candidateImages += candidates.length();
            if (hasThumbnailCandidate) {
                thumbnailGroups++;
            }
        }
        if (groups.length() == 0 && !nearestPairs.isEmpty()) {
            Collections.sort(nearestPairs, new Comparator<SimilarPair>() {
                @Override
                public int compare(SimilarPair a, SimilarPair b) {
                    return Double.compare(a.rankScore, b.rankScore);
                }
            });
            int lowLimit = Math.min(maxGroups, Math.min(5, nearestPairs.size()));
            Set<String> emittedPairs = new HashSet<>();
            for (int i = 0; i < nearestPairs.size() && nearestReviewGroups < lowLimit; i++) {
                SimilarPair pair = nearestPairs.get(i);
                if (pair.rankScore > 115.0 && pair.dhashDistance > looseMaxDistance + 4
                        && pair.ahashDistance > looseMaxDistance + 4) {
                    break;
                }
                String pairKey = pairKey(pair);
                if (emittedPairs.contains(pairKey)) {
                    continue;
                }
                emittedPairs.add(pairKey);
                SimilarImageInfo keep = imageQualityScore(pair.a.image) >= imageQualityScore(pair.b.image) ? pair.a : pair.b;
                SimilarImageInfo candidate = keep == pair.a ? pair.b : pair.a;
                JSONObject candidateJson = similarImageJson(candidate, false);
                candidateJson.put("reviewCandidate", true);
                candidateJson.put("lowConfidence", true);
                candidateJson.put("relationToKeep", pair.thumbnailCandidate ? "thumbnailCandidate" : "nearVisualReview");
                candidateJson.put("hashDistanceToKeep", hammingDistance(keep.dhash, candidate.dhash));
                candidateJson.put("aHashDistanceToKeep", hammingDistance(keep.ahash, candidate.ahash));
                candidateJson.put("similarityPercentToKeep", combinedSimilarityPercent(pair));
                candidateJson.put("dimensionRatioToKeep", ratio(candidate.pixelCount(), keep.pixelCount()));
                candidateJson.put("sizeRatioToKeep", ratio(candidate.image.sizeBytes, keep.image.sizeBytes));
                candidateJson.put("colorDistanceToKeep", rounded(pair.colorDistance, 1));
                candidateJson.put("aspectDeltaToKeep", rounded(pair.aspectDelta, 3));
                decorateSimilarCandidate(candidateJson, "low", pair.thumbnailCandidate ? "thumbnailCandidate" : "nearVisualReview", zh);
                JSONArray candidates = new JSONArray();
                candidates.put(candidateJson);

                JSONObject keepJson = similarImageJson(keep, false);
                decorateSimilarKeep(keepJson, zh);
                int groupNumber = groups.length() + 1;
                JSONObject group = new JSONObject();
                group.put("id", "similar-group-" + groupNumber);
                group.put("title", zh ? "\u76f8\u4f3c\u7167\u7247\u7ec4 " + groupNumber : "Similar photo group " + groupNumber);
                group.put("algorithm", "dHash64+aHash64+color");
                group.put("matchType", pair.thumbnailCandidate ? "thumbnailCandidate" : "nearVisualReview");
                group.put("confidence", "low");
                group.put("safeToDeleteAutomatically", false);
                group.put("count", 2);
                group.put("keepSuggestion", keepJson);
                group.put("candidates", candidates);
                group.put("minHashDistanceToKeep", hammingDistance(keep.dhash, candidate.dhash));
                group.put("maxHashDistanceToKeep", hammingDistance(keep.dhash, candidate.dhash));
                group.put("threshold", maxDistance);
                group.put("reason", "No strict visual group was found. This pair is one of the nearest review candidates; inspect the actual images before taking any action.");
                group.put("summary", zh
                        ? "\u8fd9\u7ec4\u53ea\u662f\u770b\u8d77\u6765\u6709\u70b9\u50cf\uff0c\u8bf7\u70b9\u5f00\u5bf9\u6bd4\u540e\u518d\u51b3\u5b9a\uff0c\u4e0d\u5efa\u8bae\u76f4\u63a5\u5220\u9664\u3002"
                        : "This group only looks somewhat similar. Open and compare before deciding; do not delete directly.");
                groups.put(group);
                candidateImages += 1;
                nearestReviewGroups++;
                if (pair.thumbnailCandidate) {
                    thumbnailGroups++;
                }
            }
        }

        JSONObject out = new JSONObject();
        out.put("requestedAlbum", requestedAlbum);
        out.put("album", effectiveAlbum);
        putImageScopeFields(out, queryParams, explicitImages);
        out.put("matchMode", "visual");
        out.put("algorithm", "dHash64+aHash64+color");
        out.put("threshold", maxDistance);
        out.put("looseThreshold", looseMaxDistance);
        out.put("ratioTolerance", ratioTolerance);
        out.put("minEdge", minEdge);
        out.put("requestedLimit", limit);
        out.put("limitApplied", effectiveLimit);
        out.put("partialScan", effectiveLimit < limit || stoppedByTimeBudget);
        out.put("timeLimitMs", timeLimitMs);
        out.put("timeLimited", stoppedByTimeBudget);
        out.put("scannedImages", images.size());
        out.put("fingerprintedImages", infos.size());
        out.put("unreadableImages", unreadable);
        out.put("skippedSmallImages", skippedSmall);
        out.put("comparedPairs", comparedPairs);
        out.put("matchedPairs", matchedPairs);
        out.put("thumbnailMatchedPairs", thumbnailPairs);
        out.put("candidateGroups", groups);
        out.put("candidateGroupCount", groups.length());
        out.put("strictCandidateGroupCount", strictGroups);
        out.put("nearestReviewGroupCount", nearestReviewGroups);
        out.put("candidateImageCount", candidateImages);
        out.put("thumbnailCandidateGroupCount", thumbnailGroups);
        out.put("safeToDeleteAutomatically", false);
        out.put("note", "Visual matches use perceptual hash, average hash, color profile, image size, and aspect-ratio signals. To avoid long waits, the first pass is capped unless fullScan=true, and the module stops before the host timeout. Treat all results as review candidates, not automatic delete targets.");
        return out.toString();
    }

    private static String effectiveSimilarAlbum(String requestedAlbum) {
        String album = requestedAlbum == null ? "" : requestedAlbum.trim();
        if (album.length() == 0) {
            return "default";
        }
        if ("all".equalsIgnoreCase(album) || "*".equals(album)) {
            return "all";
        }
        return album;
    }

    private static boolean safeEquals(String a, String b) {
        if (a == null) {
            return b == null;
        }
        return a.equals(b);
    }

    private static List<GalleryImage> queryGalleryImages(Context context, JSONObject params, int limit) throws Exception {
        if (context == null) {
            throw new IllegalArgumentException("context is required to access gallery images");
        }
        ContentResolver resolver = context.getContentResolver();
        Uri collection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;

        String album = normalizedAlbum(params);
        boolean allAlbums = "all".equalsIgnoreCase(album) || "*".equals(album);
        boolean defaultAlbum = album.length() == 0
                || "default".equalsIgnoreCase(album)
                || "camera".equalsIgnoreCase(album);

        ArrayList<String> projectionList = new ArrayList<>();
        projectionList.add(MediaStore.Images.Media._ID);
        projectionList.add(MediaStore.Images.Media.DISPLAY_NAME);
        projectionList.add(MediaStore.Images.Media.DATA);
        projectionList.add(MediaStore.Images.Media.SIZE);
        projectionList.add(MediaStore.Images.Media.WIDTH);
        projectionList.add(MediaStore.Images.Media.HEIGHT);
        projectionList.add(MediaStore.Images.Media.DATE_ADDED);
        projectionList.add(MediaStore.Images.Media.DATE_MODIFIED);
        projectionList.add(MediaStore.Images.Media.BUCKET_DISPLAY_NAME);
        projectionList.add(MediaStore.Images.Media.MIME_TYPE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projectionList.add(MediaStore.Images.Media.RELATIVE_PATH);
        }

        String selection = null;
        String[] selectionArgs = null;
        if (!allAlbums) {
            if (defaultAlbum) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    selection = "(" + MediaStore.Images.Media.BUCKET_DISPLAY_NAME + " IN (?, ?) OR "
                            + MediaStore.Images.Media.RELATIVE_PATH + " LIKE ? OR "
                            + MediaStore.Images.Media.DATA + " LIKE ?)";
                    selectionArgs = new String[] { "Camera", "相机", "%DCIM/Camera%", "%/DCIM/Camera/%" };
                } else {
                    selection = "(" + MediaStore.Images.Media.BUCKET_DISPLAY_NAME + " IN (?, ?) OR "
                            + MediaStore.Images.Media.DATA + " LIKE ?)";
                    selectionArgs = new String[] { "Camera", "相机", "%/DCIM/Camera/%" };
                }
            } else {
                selection = MediaStore.Images.Media.BUCKET_DISPLAY_NAME + " = ?";
                selectionArgs = new String[] { album };
            }
        }

        String sortOrder = MediaStore.Images.Media.DATE_ADDED + " DESC";
        ArrayList<GalleryImage> result = new ArrayList<>();
        Cursor cursor = resolver.query(collection, projectionList.toArray(new String[0]), selection, selectionArgs, sortOrder);
        if (cursor == null) {
            return result;
        }
        try {
            int idCol = cursor.getColumnIndex(MediaStore.Images.Media._ID);
            int nameCol = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME);
            int dataCol = cursor.getColumnIndex(MediaStore.Images.Media.DATA);
            int sizeCol = cursor.getColumnIndex(MediaStore.Images.Media.SIZE);
            int widthCol = cursor.getColumnIndex(MediaStore.Images.Media.WIDTH);
            int heightCol = cursor.getColumnIndex(MediaStore.Images.Media.HEIGHT);
            int addedCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED);
            int modifiedCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED);
            int bucketCol = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME);
            int mimeCol = cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE);
            int relCol = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                    : -1;
            while (cursor.moveToNext() && result.size() < limit) {
                long id = idCol >= 0 ? cursor.getLong(idCol) : 0L;
                Uri uri = ContentUris.withAppendedId(collection, id);
                GalleryImage image = new GalleryImage();
                image.id = id;
                image.uri = uri;
                image.name = stringAt(cursor, nameCol);
                image.path = stringAt(cursor, dataCol);
                image.relativePath = stringAt(cursor, relCol);
                image.sizeBytes = longAt(cursor, sizeCol);
                image.width = intAt(cursor, widthCol);
                image.height = intAt(cursor, heightCol);
                image.dateAdded = longAt(cursor, addedCol);
                image.dateModified = longAt(cursor, modifiedCol);
                image.bucket = stringAt(cursor, bucketCol);
                image.mimeType = stringAt(cursor, mimeCol);
                result.add(image);
            }
        } finally {
            cursor.close();
        }
        return result;
    }

    private static List<GalleryImage> resolveImageScanScope(Context context, JSONObject params, int limit) throws Exception {
        List<String> paths = explicitImagePathList(params);
        if (paths.isEmpty()) {
            return queryGalleryImages(context, params, limit);
        }
        ArrayList<GalleryImage> result = new ArrayList<>();
        for (String path : paths) {
            if (result.size() >= limit) {
                break;
            }
            result.add(galleryImageFromPath(context, path));
        }
        return result;
    }

    private static boolean hasExplicitImagePaths(JSONObject params) {
        return !explicitImagePathList(params).isEmpty();
    }

    private static List<String> explicitImagePathList(JSONObject params) {
        ArrayList<String> paths = new ArrayList<>();
        if (params == null) {
            return paths;
        }
        addExplicitImagePaths(paths, params.opt("imagePaths"), false);
        addExplicitImagePaths(paths, params.opt("images"), false);
        addExplicitImagePaths(paths, params.opt("paths"), false);
        addExplicitImagePaths(paths, params.opt("inputPaths"), false);
        addExplicitImagePaths(paths, params.opt("files"), false);
        addExplicitImagePaths(paths, params.opt("imagePath"), true);
        addExplicitImagePaths(paths, params.opt("path"), true);
        return paths;
    }

    private static void addExplicitImagePaths(List<String> out, Object value, boolean requirePathLike) {
        if (value == null || value == JSONObject.NULL) {
            return;
        }
        if (value instanceof JSONArray) {
            JSONArray arr = (JSONArray) value;
            for (int i = 0; i < arr.length(); i++) {
                Object item = arr.opt(i);
                if (item instanceof JSONObject) {
                    JSONObject obj = (JSONObject) item;
                    addExplicitImagePath(out, obj.optString("path", obj.optString("uri", "")), requirePathLike);
                } else {
                    addExplicitImagePaths(out, item, requirePathLike);
                }
            }
            return;
        }
        if (value instanceof JSONObject) {
            JSONObject obj = (JSONObject) value;
            addExplicitImagePath(out, obj.optString("path", obj.optString("uri", "")), requirePathLike);
            return;
        }
        String text = String.valueOf(value).trim();
        if (text.length() == 0) {
            return;
        }
        if (text.startsWith("[") && text.endsWith("]")) {
            try {
                addExplicitImagePaths(out, new JSONArray(text), requirePathLike);
                return;
            } catch (Exception ignored) {
            }
        }
        String[] parts = text.split("[\\n;,]+");
        for (String part : parts) {
            addExplicitImagePath(out, part, requirePathLike);
        }
    }

    private static void addExplicitImagePath(List<String> out, String raw, boolean requirePathLike) {
        String path = normalizeExplicitImagePath(raw);
        if (path.length() == 0) {
            return;
        }
        if (requirePathLike && !looksLikeExplicitImagePath(path)) {
            return;
        }
        if (!out.contains(path)) {
            out.add(path);
        }
    }

    private static String normalizeExplicitImagePath(String raw) {
        if (raw == null) {
            return "";
        }
        String path = raw.trim();
        while ((path.startsWith("\"") && path.endsWith("\"")) || (path.startsWith("'") && path.endsWith("'"))) {
            path = path.substring(1, path.length() - 1).trim();
        }
        if (path.startsWith("file://")) {
            Uri uri = Uri.parse(path);
            String parsed = uri.getPath();
            if (parsed != null && parsed.length() > 0) {
                path = parsed;
            }
        }
        return path;
    }

    private static boolean looksLikeExplicitImagePath(String path) {
        if (TextUtils.isEmpty(path)) {
            return false;
        }
        String lower = path.toLowerCase(Locale.US);
        return lower.startsWith("/")
                || lower.startsWith("file:/")
                || lower.startsWith("content:/")
                || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg")
                || lower.endsWith(".png")
                || lower.endsWith(".webp")
                || lower.endsWith(".heic")
                || lower.endsWith(".bmp")
                || lower.endsWith(".gif");
    }

    private static GalleryImage galleryImageFromPath(Context context, String rawPath) throws Exception {
        String path = normalizeExplicitImagePath(rawPath);
        if (path.startsWith("content://")) {
            return galleryImageFromContentUri(context, Uri.parse(path));
        }
        requireReadableFile(path);
        File file = new File(path);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IllegalArgumentException("cannot read image bounds: " + path);
        }
        GalleryImage image = new GalleryImage();
        image.id = Math.abs((long) path.hashCode());
        image.uri = Uri.fromFile(file);
        image.name = file.getName();
        image.path = file.getAbsolutePath();
        File parent = file.getParentFile();
        image.relativePath = parent == null ? "" : parent.getAbsolutePath();
        image.bucket = parent == null ? "selected_images" : safeLabel(parent.getName(), "selected_images");
        image.mimeType = bounds.outMimeType != null ? bounds.outMimeType : mimeTypeFromExtension(path);
        image.sizeBytes = file.length();
        image.width = bounds.outWidth;
        image.height = bounds.outHeight;
        long modifiedSeconds = Math.max(0L, file.lastModified() / 1000L);
        image.dateModified = modifiedSeconds;
        image.dateAdded = modifiedSeconds;
        return image;
    }

    private static GalleryImage galleryImageFromContentUri(Context context, Uri uri) throws Exception {
        if (context == null) {
            throw new IOException("content uri requires Android context");
        }
        ContentResolver resolver = context.getContentResolver();
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        InputStream in = null;
        try {
            in = resolver.openInputStream(uri);
            if (in == null) {
                throw new IOException("image content is not readable: " + uri);
            }
            BitmapFactory.decodeStream(in, null, bounds);
        } finally {
            if (in != null) {
                try { in.close(); } catch (Exception ignored) {}
            }
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IllegalArgumentException("cannot read image bounds: " + uri);
        }

        String name = "";
        long sizeBytes = 0L;
        Cursor cursor = null;
        try {
            cursor = resolver.query(uri, new String[] {
                    OpenableColumns.DISPLAY_NAME,
                    OpenableColumns.SIZE
            }, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex >= 0) {
                    name = cursor.getString(nameIndex);
                }
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    sizeBytes = cursor.getLong(sizeIndex);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) {
                try { cursor.close(); } catch (Exception ignored) {}
            }
        }
        if (TextUtils.isEmpty(name)) {
            name = safeLabel(uri.getLastPathSegment(), "selected_image");
        }
        String mime = resolver.getType(uri);
        if (TextUtils.isEmpty(mime)) {
            mime = bounds.outMimeType != null ? bounds.outMimeType : mimeTypeFromExtension(name);
        }
        long nowSeconds = System.currentTimeMillis() / 1000L;

        GalleryImage image = new GalleryImage();
        image.id = Math.abs((long) uri.toString().hashCode());
        image.uri = uri;
        image.name = name;
        image.path = uri.toString();
        image.relativePath = "selected_images";
        image.bucket = "selected_images";
        image.mimeType = mime;
        image.sizeBytes = Math.max(0L, sizeBytes);
        image.width = bounds.outWidth;
        image.height = bounds.outHeight;
        image.dateModified = nowSeconds;
        image.dateAdded = nowSeconds;
        return image;
    }

    private static String mimeTypeFromExtension(String path) {
        String ext = extensionOf(path);
        if ("jpg".equals(ext) || "jpeg".equals(ext)) return "image/jpeg";
        if ("png".equals(ext)) return "image/png";
        if ("webp".equals(ext)) return "image/webp";
        if ("heic".equals(ext) || "heif".equals(ext)) return "image/heic";
        if ("bmp".equals(ext)) return "image/bmp";
        if ("gif".equals(ext)) return "image/gif";
        return "image/*";
    }

    private static void putImageScopeFields(JSONObject out, JSONObject params, boolean explicitImages) throws Exception {
        if (explicitImages) {
            out.put("scope", "image_paths");
            out.put("album", "selected_images");
            out.put("source", "input.imagePaths");
            out.put("inputImageCount", explicitImagePathList(params).size());
        } else {
            out.put("scope", "gallery_album");
            out.put("album", normalizedAlbum(params));
            out.put("source", "MediaStore.Images");
            out.put("inputImageCount", 0);
        }
    }

    private static String normalizedAlbum(JSONObject params) {
        String album = params.optString("album", params.optString("bucket", "default"));
        return album == null ? "default" : album.trim();
    }

    private static String sha256ForImage(Context context, GalleryImage image) throws Exception {
        InputStream in = null;
        try {
            in = openImageInput(context, image);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1024 * 64];
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n > 0) {
                    digest.update(buf, 0, n);
                }
            }
            return hex(digest.digest());
        } finally {
            if (in != null) {
                try { in.close(); } catch (Exception ignored) {}
            }
        }
    }

    private static InputStream openImageInput(Context context, GalleryImage image) throws Exception {
        if (image != null && !TextUtils.isEmpty(image.path)) {
            File file = new File(image.path);
            if (file.isFile() && file.canRead()) {
                return new FileInputStream(file);
            }
        }
        if (context == null || image == null || image.uri == null) {
            throw new IOException("image content is not readable");
        }
        InputStream in = context.getContentResolver().openInputStream(image.uri);
        if (in == null) {
            throw new IOException("image content is not readable");
        }
        return in;
    }

    private static SimilarImageInfo buildSimilarImageInfo(Context context, GalleryImage image) throws Exception {
        Bitmap tiny = null;
        try {
            tiny = decodeImageForHash(context, image, 16, 16);
            long dhash = computeDHash64(tiny);
            long ahash = computeAHash64(tiny);
            return new SimilarImageInfo(image, dhash, ahash, computeImageProfile(tiny));
        } finally {
            recycleQuietly(tiny);
        }
    }

    private static Bitmap decodeImageForHash(Context context, GalleryImage image, int targetWidth, int targetHeight) throws Exception {
        Bitmap thumbnail = loadThumbnailForHash(context, image, targetWidth, targetHeight);
        if (thumbnail != null) {
            return thumbnail;
        }

        InputStream in = null;
        Bitmap decoded = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            in = openImageInput(context, image);
            BitmapFactory.decodeStream(in, null, bounds);
            try { in.close(); } catch (Exception ignored) {}
            in = null;
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                throw new IOException("image bounds are not readable");
            }
            if (image.width <= 0) {
                image.width = bounds.outWidth;
            }
            if (image.height <= 0) {
                image.height = bounds.outHeight;
            }

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = false;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            opts.inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, 256);
            in = openImageInput(context, image);
            decoded = BitmapFactory.decodeStream(in, null, opts);
            if (decoded == null) {
                throw new IOException("image decode failed");
            }
            Bitmap scaled = Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true);
            if (scaled != decoded) {
                recycleQuietly(decoded);
            }
            decoded = null;
            return scaled;
        } finally {
            if (in != null) {
                try { in.close(); } catch (Exception ignored) {}
            }
            recycleQuietly(decoded);
        }
    }

    private static Bitmap loadThumbnailForHash(Context context, GalleryImage image, int targetWidth, int targetHeight) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || context == null || image == null || image.uri == null) {
            return null;
        }
        Bitmap thumbnail = null;
        try {
            int side = Math.max(128, Math.max(targetWidth, targetHeight));
            thumbnail = context.getContentResolver().loadThumbnail(image.uri, new Size(side, side), null);
            if (thumbnail == null) {
                return null;
            }
            Bitmap scaled = Bitmap.createScaledBitmap(thumbnail, targetWidth, targetHeight, true);
            if (scaled != thumbnail) {
                recycleQuietly(thumbnail);
            }
            return scaled;
        } catch (Throwable ignored) {
            recycleQuietly(thumbnail);
            return null;
        }
    }

    private static long computeDHash64(Bitmap bitmap) {
        long hash = 0L;
        int bit = 0;
        int w = Math.max(1, bitmap.getWidth());
        int h = Math.max(1, bitmap.getHeight());
        for (int y = 0; y < 8; y++) {
            int yy = Math.min(h - 1, Math.round(y * (h - 1) / 7.0f));
            for (int x = 0; x < 8; x++) {
                int lx = Math.min(w - 1, Math.round(x * (w - 1) / 8.0f));
                int rx = Math.min(w - 1, Math.round((x + 1) * (w - 1) / 8.0f));
                int left = luminance(bitmap.getPixel(lx, yy));
                int right = luminance(bitmap.getPixel(rx, yy));
                if (left > right) {
                    hash |= (1L << bit);
                }
                bit++;
            }
        }
        return hash;
    }

    private static long computeAHash64(Bitmap bitmap) {
        int w = Math.max(1, bitmap.getWidth());
        int h = Math.max(1, bitmap.getHeight());
        int[] values = new int[64];
        int sum = 0;
        int idx = 0;
        for (int y = 0; y < 8; y++) {
            int yy = Math.min(h - 1, Math.round((y + 0.5f) * h / 8.0f));
            for (int x = 0; x < 8; x++) {
                int xx = Math.min(w - 1, Math.round((x + 0.5f) * w / 8.0f));
                int v = luminance(bitmap.getPixel(xx, yy));
                values[idx++] = v;
                sum += v;
            }
        }
        double avg = sum / 64.0;
        long hash = 0L;
        for (int i = 0; i < values.length; i++) {
            if (values[i] >= avg) {
                hash |= (1L << i);
            }
        }
        return hash;
    }

    private static int luminance(int color) {
        int r = (color >> 16) & 0xff;
        int g = (color >> 8) & 0xff;
        int b = color & 0xff;
        return (r * 299 + g * 587 + b * 114) / 1000;
    }

    private static ImageProfile computeImageProfile(Bitmap bitmap) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int count = Math.max(1, w * h);
        double sumR = 0;
        double sumG = 0;
        double sumB = 0;
        double sumL = 0;
        double sumL2 = 0;
        double edge = 0;
        int edgeCount = 0;
        int[][] luma = new int[h][w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int color = bitmap.getPixel(x, y);
                int r = (color >> 16) & 0xff;
                int g = (color >> 8) & 0xff;
                int b = color & 0xff;
                int l = luminance(color);
                luma[y][x] = l;
                sumR += r;
                sumG += g;
                sumB += b;
                sumL += l;
                sumL2 += l * l;
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (x + 1 < w) {
                    edge += Math.abs(luma[y][x] - luma[y][x + 1]);
                    edgeCount++;
                }
                if (y + 1 < h) {
                    edge += Math.abs(luma[y][x] - luma[y + 1][x]);
                    edgeCount++;
                }
            }
        }
        double meanL = sumL / count;
        double variance = Math.max(0.0, (sumL2 / count) - (meanL * meanL));
        return new ImageProfile(
                sumR / count,
                sumG / count,
                sumB / count,
                Math.sqrt(variance),
                edgeCount == 0 ? 0.0 : edge / edgeCount
        );
    }

    private static boolean sameAspectRatio(SimilarImageInfo a, SimilarImageInfo b, double tolerance) {
        if (a.width <= 0 || a.height <= 0 || b.width <= 0 || b.height <= 0) {
            return false;
        }
        double ar = a.width / (double) a.height;
        double br = b.width / (double) b.height;
        double denom = Math.max(ar, br);
        return denom <= 0 || Math.abs(ar - br) / denom <= tolerance;
    }

    private static double aspectDelta(SimilarImageInfo a, SimilarImageInfo b) {
        if (a.width <= 0 || a.height <= 0 || b.width <= 0 || b.height <= 0) {
            return 1.0;
        }
        double ar = a.width / (double) a.height;
        double br = b.width / (double) b.height;
        double denom = Math.max(ar, br);
        return denom <= 0 ? 0.0 : Math.abs(ar - br) / denom;
    }

    private static boolean isLikelyThumbnail(SimilarImageInfo a, SimilarImageInfo b, double tolerance) {
        if (!sameAspectRatio(a, b, tolerance)) {
            return false;
        }
        long ap = a.pixelCount();
        long bp = b.pixelCount();
        if (ap <= 0 || bp <= 0) {
            return false;
        }
        double smallerPixelRatio = Math.min(ap, bp) / (double) Math.max(ap, bp);
        double smallerByteRatio = ratio(Math.min(a.image.sizeBytes, b.image.sizeBytes), Math.max(a.image.sizeBytes, b.image.sizeBytes));
        return smallerPixelRatio <= 0.45 || (smallerPixelRatio <= 0.65 && smallerByteRatio <= 0.45);
    }

    private static SimilarPair buildSimilarPair(SimilarImageInfo a, SimilarImageInfo b, double ratioTolerance) {
        int dhashDistance = hammingDistance(a.dhash, b.dhash);
        int ahashDistance = hammingDistance(a.ahash, b.ahash);
        double colorDistance = colorDistance(a, b);
        double lumaStdDelta = Math.abs(a.profile.lumaStdDev - b.profile.lumaStdDev);
        double edgeDelta = Math.abs(a.profile.edgeScore - b.profile.edgeScore);
        double aspectDelta = aspectDelta(a, b);
        boolean thumbnail = isLikelyThumbnail(a, b, Math.max(ratioTolerance, 0.35));
        double rankScore = dhashDistance
                + ahashDistance * 0.75
                + colorDistance / 7.5
                + lumaStdDelta / 8.0
                + edgeDelta / 8.0
                + aspectDelta * 32.0;
        if (thumbnail) {
            rankScore -= 6.0;
        }
        return new SimilarPair(a, b, dhashDistance, ahashDistance, colorDistance,
                lumaStdDelta, edgeDelta, aspectDelta, thumbnail, Math.max(0.0, rankScore));
    }

    private static boolean isPairWorthComparing(SimilarPair pair, double ratioTolerance) {
        double looseAspect = Math.max(ratioTolerance, 0.45);
        if (pair.aspectDelta <= looseAspect) {
            return true;
        }
        if (pair.thumbnailCandidate && pair.aspectDelta <= 0.6) {
            return true;
        }
        if (pair.dhashDistance <= 8 || pair.ahashDistance <= 8) {
            return true;
        }
        return pair.dhashDistance <= 24 && pair.ahashDistance <= 24 && pair.colorDistance <= 55.0;
    }

    private static boolean isVisualMatch(SimilarPair pair, int maxDistance, int looseMaxDistance) {
        if (pair.aspectDelta > 0.65
                && !pair.thumbnailCandidate
                && pair.dhashDistance > 6
                && pair.ahashDistance > 6) {
            return false;
        }
        if (pair.dhashDistance <= maxDistance
                && pair.colorDistance <= 74.0
                && pair.lumaStdDelta <= 58.0
                && pair.edgeDelta <= 55.0
                && pair.aspectDelta <= 0.38) {
            return true;
        }
        if (pair.ahashDistance <= Math.max(8, maxDistance)
                && pair.colorDistance <= 72.0
                && pair.lumaStdDelta <= 56.0
                && pair.edgeDelta <= 52.0
                && pair.aspectDelta <= 0.38) {
            return true;
        }
        if (pair.dhashDistance <= looseMaxDistance
                && pair.ahashDistance <= Math.max(12, looseMaxDistance - 2)
                && pair.colorDistance <= 95.0
                && pair.lumaStdDelta <= 72.0
                && pair.edgeDelta <= 70.0
                && pair.aspectDelta <= 0.48) {
            return true;
        }
        if (pair.thumbnailCandidate
                && pair.dhashDistance <= looseMaxDistance + 4
                && pair.ahashDistance <= looseMaxDistance + 4
                && pair.colorDistance <= 112.0) {
            return true;
        }
        return pair.dhashDistance <= 4 && pair.ahashDistance <= 10
                && pair.colorDistance <= 90.0
                && pair.lumaStdDelta <= 64.0;
    }

    private static void addNearestPair(List<SimilarPair> pairs, SimilarPair pair, int maxSize) {
        pairs.add(pair);
        if (pairs.size() <= maxSize) {
            return;
        }
        Collections.sort(pairs, new Comparator<SimilarPair>() {
            @Override
            public int compare(SimilarPair a, SimilarPair b) {
                return Double.compare(a.rankScore, b.rankScore);
            }
        });
        while (pairs.size() > maxSize) {
            pairs.remove(pairs.size() - 1);
        }
    }

    private static String pairKey(SimilarPair pair) {
        long a = Math.min(pair.a.image.id, pair.b.image.id);
        long b = Math.max(pair.a.image.id, pair.b.image.id);
        if (a > 0 || b > 0) {
            return a + ":" + b;
        }
        String ap = safeLabel(pair.a.image.path, pair.a.image.uri == null ? "" : pair.a.image.uri.toString());
        String bp = safeLabel(pair.b.image.path, pair.b.image.uri == null ? "" : pair.b.image.uri.toString());
        return ap.compareTo(bp) <= 0 ? ap + ":" + bp : bp + ":" + ap;
    }

    private static double combinedSimilarityPercent(SimilarPair pair) {
        double hashScore = (128.0 - Math.min(128.0, pair.dhashDistance + pair.ahashDistance)) * 100.0 / 128.0;
        double colorPenalty = Math.min(24.0, pair.colorDistance / 5.0);
        double aspectPenalty = Math.min(12.0, pair.aspectDelta * 24.0);
        double value = Math.max(0.0, Math.min(100.0, hashScore - colorPenalty - aspectPenalty));
        return Math.round(value * 10.0) / 10.0;
    }

    private static double colorDistance(SimilarImageInfo a, SimilarImageInfo b) {
        double dr = a.profile.avgR - b.profile.avgR;
        double dg = a.profile.avgG - b.profile.avgG;
        double db = a.profile.avgB - b.profile.avgB;
        return Math.sqrt(dr * dr + dg * dg + db * db);
    }

    private static JSONObject similarImageJson(SimilarImageInfo info, boolean includeDeleteCandidate) throws Exception {
        JSONObject o = info.image.toJson(includeDeleteCandidate);
        o.put("dhash", hash64Hex(info.dhash));
        o.put("ahash", hash64Hex(info.ahash));
        o.put("pixelCount", info.pixelCount());
        o.put("profileColor", String.format(Locale.US, "rgb(%.0f,%.0f,%.0f)",
                info.profile.avgR, info.profile.avgG, info.profile.avgB));
        return o;
    }

    private static void decorateSimilarKeep(JSONObject keep, boolean zh) throws Exception {
        keep.put("role", "keep");
        keep.put("deleteRecommendation", "keep");
        keep.put("recommendationLabel", zh
                ? "\u5efa\u8bae\u4fdd\u7559\uff1a\u901a\u5e38\u662f\u66f4\u6e05\u6670\u3001\u66f4\u5927\u6216\u66f4\u65b0\u7684\u4e00\u5f20"
                : "Suggested keep: usually clearer, larger, or newer");
        JSONArray tags = new JSONArray();
        tags.put(zh ? "\u5efa\u8bae\u4fdd\u7559" : "keep");
        tags.put(zh ? "\u8d28\u91cf\u4f18\u5148" : "best quality");
        if (keep.optInt("width", 0) > 0 && keep.optInt("height", 0) > 0) {
            tags.put(keep.optInt("width", 0) + "\u00d7" + keep.optInt("height", 0));
        }
        String size = keep.optString("sizeFormatted", "");
        if (!TextUtils.isEmpty(size)) {
            tags.put(size);
        }
        keep.put("tags", tags);
    }

    private static void decorateSimilarCandidate(JSONObject candidate, String confidence, String matchType, boolean zh) throws Exception {
        candidate.put("role", "candidate");
        String recommendation = "low".equals(confidence) ? "manual_review_only" : "review_before_delete";
        candidate.put("deleteRecommendation", recommendation);
        candidate.put("recommendationLabel", similarCandidateRecommendation(confidence, matchType, zh));
        JSONArray tags = new JSONArray();
        tags.put("low".equals(confidence) ? (zh ? "\u5148\u770b\u56fe\u786e\u8ba4" : "review first")
                : (zh ? "\u53ef\u590d\u6838\u5019\u9009" : "review candidate"));
        tags.put(similarRelation(matchType, zh));
        double similarity = candidate.optDouble("similarityPercentToKeep", -1.0);
        if (similarity >= 0.0) {
            tags.put(zh ? "\u76f8\u4f3c\u5ea6 " + similarity + "%" : similarity + "% similar");
        }
        double dimensionRatio = candidate.optDouble("dimensionRatioToKeep", 0.0);
        if (dimensionRatio > 0.0 && dimensionRatio < 0.75) {
            tags.put(zh ? "\u5c3a\u5bf8\u66f4\u5c0f" : "smaller resolution");
        }
        double sizeRatio = candidate.optDouble("sizeRatioToKeep", 0.0);
        if (sizeRatio > 0.0 && sizeRatio < 0.75) {
            tags.put(zh ? "\u6587\u4ef6\u66f4\u5c0f" : "smaller file");
        }
        if (candidate.optInt("width", 0) > 0 && candidate.optInt("height", 0) > 0) {
            tags.put(candidate.optInt("width", 0) + "\u00d7" + candidate.optInt("height", 0));
        }
        String size = candidate.optString("sizeFormatted", "");
        if (!TextUtils.isEmpty(size)) {
            tags.put(size);
        }
        tags.put(zh ? "\u5220\u9664\u524d\u5148\u70b9\u5f00\u786e\u8ba4" : "open before deleting");
        candidate.put("tags", tags);
    }

    private static String similarCandidateRecommendation(String confidence, String matchType, boolean zh) {
        if ("low".equals(confidence)) {
            return zh
                    ? "\u5148\u70b9\u5f00\u5bf9\u6bd4\uff1a\u8fd9\u5f20\u53ea\u662f\u770b\u8d77\u6765\u6709\u70b9\u50cf\uff0c\u4e0d\u5efa\u8bae\u76f4\u63a5\u5220\u9664"
                    : "Open and compare first: this only looks somewhat similar, so do not delete directly";
        }
        if ("thumbnailCandidate".equals(matchType)) {
            return zh
                    ? "\u53ef\u8003\u8651\u5220\u9664\uff1a\u7591\u4f3c\u7f29\u7565\u56fe\u6216\u538b\u7f29\u526f\u672c\uff0c\u5148\u70b9\u5f00\u786e\u8ba4"
                    : "Candidate: possible thumbnail/compressed copy; open to confirm first";
        }
        return zh
                ? "\u53ef\u8003\u8651\u5220\u9664\uff1a\u4e0e\u4fdd\u7559\u56fe\u89c6\u89c9\u76f8\u4f3c\uff0c\u5148\u70b9\u5f00\u5bf9\u6bd4"
                : "Candidate: visually similar to the keep image; compare first";
    }

    private static int hammingDistance(long a, long b) {
        return Long.bitCount(a ^ b);
    }

    private static double similarityPercent(int distance) {
        double value = (64 - Math.max(0, Math.min(64, distance))) * 100.0 / 64.0;
        return Math.round(value * 10.0) / 10.0;
    }

    private static double ratio(long a, long b) {
        if (a <= 0 || b <= 0) {
            return 0.0;
        }
        double value = a / (double) b;
        return Math.round(value * 10000.0) / 10000.0;
    }

    private static double rounded(double value, int decimals) {
        double factor = Math.pow(10, Math.max(0, decimals));
        return Math.round(value * factor) / factor;
    }

    private static long imageQualityScore(GalleryImage image) {
        long pixels = (long) Math.max(0, image.width) * Math.max(0, image.height);
        long bytes = Math.max(0L, image.sizeBytes);
        long date = Math.max(image.dateModified, image.dateAdded);
        long score = pixels * 1_000_000L + Math.min(bytes, 500_000_000L) + Math.max(0L, date / 1000L);
        String name = image.name == null ? "" : image.name.toLowerCase(Locale.US);
        if (name.contains("thumbnail") || name.contains("thumb") || name.contains("small")) {
            score -= 900_000_000_000L;
        }
        if (name.contains("compressed") || name.contains("copy") || name.contains("duplicate") || name.contains("blur")) {
            score -= 300_000_000_000L;
        }
        return score;
    }

    private static long bestImageScore(List<SimilarImageInfo> images) {
        long best = 0L;
        for (SimilarImageInfo info : images) {
            best = Math.max(best, imageQualityScore(info.image));
        }
        return best;
    }

    private static void countByKey(Map<String, Integer> counts, String key) {
        String safeKey = TextUtils.isEmpty(key) ? "Unknown" : key;
        Integer current = counts.get(safeKey);
        counts.put(safeKey, current == null ? 1 : current + 1);
    }

    private static String safeLabel(String value, String fallback) {
        if (TextUtils.isEmpty(value)) {
            return fallback == null ? "" : fallback;
        }
        return value;
    }

    private static void putLimited(JSONArray array, JSONObject item, int limit) {
        if (array.length() < limit) {
            array.put(item);
        }
    }

    private static JSONArray mapToArray(Map<String, Integer> counts, String labelKey) throws Exception {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
        Collections.sort(entries, new Comparator<Map.Entry<String, Integer>>() {
            @Override
            public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                int byCount = Integer.compare(b.getValue(), a.getValue());
                if (byCount != 0) return byCount;
                return a.getKey().compareToIgnoreCase(b.getKey());
            }
        });
        JSONArray arr = new JSONArray();
        for (Map.Entry<String, Integer> entry : entries) {
            JSONObject o = new JSONObject();
            o.put(labelKey, entry.getKey());
            o.put("count", entry.getValue());
            arr.put(o);
        }
        return arr;
    }

    private static String monthKey(GalleryImage image) {
        long ts = Math.max(image.dateAdded, image.dateModified);
        if (ts <= 0L) {
            return isZh() ? "未知月份" : "Unknown month";
        }
        if (ts < 1000000000000L) {
            ts *= 1000L;
        }
        return new SimpleDateFormat("yyyy-MM", Locale.US).format(new Date(ts));
    }

    private static String imageBlob(GalleryImage image) {
        return (safeLower(image.name) + " " + safeLower(image.path) + " "
                + safeLower(image.relativePath) + " " + safeLower(image.bucket)).trim();
    }

    private static String safeLower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.US);
    }

    private static boolean isScreenshotLike(GalleryImage image) {
        String blob = imageBlob(image);
        if (blob.contains("screenshot") || blob.contains("screenshots")
                || blob.contains("screen_shot") || blob.contains("screen-shot")
                || blob.contains("截屏") || blob.contains("截图")) {
            return true;
        }
        if (image.width >= 720 && image.height >= image.width * 1.65) {
            return true;
        }
        return image.height >= 720 && image.width >= image.height * 1.65;
    }

    private static boolean isTextOrDocumentLike(GalleryImage image) {
        String blob = imageBlob(image);
        return blob.contains("document")
                || blob.contains("doc")
                || blob.contains("scan")
                || blob.contains("ocr")
                || blob.contains("text")
                || blob.contains("receipt")
                || blob.contains("invoice")
                || blob.contains("whiteboard")
                || blob.contains("sensitive")
                || blob.contains("文档")
                || blob.contains("扫描")
                || blob.contains("文字")
                || blob.contains("票据")
                || blob.contains("发票");
    }

    private static boolean isThumbnailLike(GalleryImage image) {
        String blob = imageBlob(image);
        long pixels = (long) Math.max(0, image.width) * Math.max(0, image.height);
        int minEdge = Math.min(Math.max(0, image.width), Math.max(0, image.height));
        return blob.contains("thumb")
                || blob.contains("thumbnail")
                || blob.contains("small")
                || blob.contains("缩略")
                || minEdge > 0 && minEdge <= 360
                || pixels > 0 && pixels <= 180000L;
    }

    private static boolean isLowResolutionLike(GalleryImage image) {
        long pixels = (long) Math.max(0, image.width) * Math.max(0, image.height);
        int minEdge = Math.min(Math.max(0, image.width), Math.max(0, image.height));
        return minEdge > 0 && minEdge < 720 || pixels > 0 && pixels < 1000000L;
    }

    private static String buildGalleryAnalysisSummary(int scanned, int screenshots, int documents,
                                                      int thumbnails, int lowResolution) {
        if (isZh()) {
            return "已扫描 " + scanned + " 张图片：截图 " + screenshots + " 张、文档/文字图 "
                    + documents + " 张、缩略图 " + thumbnails + " 张、低分辨率图 "
                    + lowResolution + " 张。建议先按分类查看，再决定是否整理或删除。";
        }
        return "Scanned " + scanned + " images: " + screenshots + " screenshots, "
                + documents + " document/text images, " + thumbnails + " thumbnails, and "
                + lowResolution + " low-resolution images. Review categories before organizing or deleting.";
    }

    private static LowQualityCandidate scoreLowQualityCandidate(Context context, GalleryImage image) {
        int score = 0;
        List<String> reasons = new ArrayList<>();
        boolean unreadable = false;
        ImageProfile profile = null;
        String blob = imageBlob(image);
        long pixels = (long) Math.max(0, image.width) * Math.max(0, image.height);
        boolean documentLike = isTextOrDocumentLike(image);
        boolean screenshotLike = isScreenshotLike(image);
        boolean thumbnailLike = isThumbnailLike(image);
        boolean lowResolutionLike = isLowResolutionLike(image);
        boolean explicitlyCompressed = blob.contains("compressed")
                || blob.contains("compress")
                || blob.contains("low_quality")
                || blob.contains("lowquality")
                || blob.contains("压缩");
        if (blob.contains("blur") || blob.contains("模糊")) {
            score += 45;
            reasons.add(isZh() ? "文件名提示可能是模糊照片" : "Filename suggests a blurry photo");
        }
        if (thumbnailLike) {
            score += 35;
            reasons.add(isZh() ? "尺寸很小或疑似缩略图" : "Very small or likely a thumbnail");
        }
        if (lowResolutionLike && !documentLike && !screenshotLike) {
            score += 25;
            reasons.add(isZh() ? "分辨率偏低" : "Low resolution");
        }
        if (pixels > 0 && image.sizeBytes > 0) {
            double bytesPerMp = image.sizeBytes / (pixels / 1000000.0);
            if (!documentLike && !screenshotLike && (explicitlyCompressed || bytesPerMp < 25000.0 && pixels >= 600000L)) {
                score += explicitlyCompressed ? 25 : 15;
                reasons.add(isZh() ? "文件体积相对像素偏小，可能压缩较重" : "Small file for its pixel count, possibly heavily compressed");
            }
        }
        try {
            SimilarImageInfo info = buildSimilarImageInfo(context, image);
            profile = info.profile;
            if (!documentLike && !screenshotLike && profile.edgeScore < 4.5 && profile.lumaStdDev < 30.0 && pixels >= 600000L) {
                score += 20;
                reasons.add(isZh() ? "画面细节较少，可能模糊或纯色占比高" : "Low detail, possibly blurry or mostly flat color");
            }
        } catch (Exception e) {
            unreadable = true;
            score += 10;
            reasons.add(isZh() ? "图片无法读取，建议检查文件是否损坏" : "Image was unreadable; check whether the file is damaged");
        }
        score = Math.min(100, score);
        return new LowQualityCandidate(image, score, reasons, unreadable, profile);
    }

    private static int find(int[] parent, int x) {
        int p = parent[x];
        if (p != x) {
            parent[x] = find(parent, p);
        }
        return parent[x];
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra != rb) {
            parent[rb] = ra;
        }
    }

    private static String hash64Hex(long value) {
        return String.format(Locale.US, "%016x", value);
    }

    private static String stringAt(Cursor cursor, int column) {
        if (column < 0 || cursor.isNull(column)) return "";
        return cursor.getString(column);
    }

    private static long longAt(Cursor cursor, int column) {
        if (column < 0 || cursor.isNull(column)) return 0L;
        return cursor.getLong(column);
    }

    private static int intAt(Cursor cursor, int column) {
        if (column < 0 || cursor.isNull(column)) return 0;
        return cursor.getInt(column);
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clampDouble(double value, double min, double max) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static String hex(byte[] bytes) {
        char[] table = "0123456789abcdef".toCharArray();
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xff;
            out[i * 2] = table[v >>> 4];
            out[i * 2 + 1] = table[v & 0x0f];
        }
        return new String(out);
    }

    private static class GalleryImage {
        long id;
        Uri uri;
        String name = "";
        String path = "";
        String relativePath = "";
        String bucket = "";
        String mimeType = "";
        long sizeBytes;
        int width;
        int height;
        long dateAdded;
        long dateModified;

        JSONObject toJson(boolean includeDeleteCandidate) throws Exception {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("uri", uri != null ? uri.toString() : "");
            o.put("name", name);
            o.put("path", path);
            o.put("relativePath", relativePath);
            o.put("album", bucket);
            o.put("mimeType", mimeType);
            o.put("sizeBytes", sizeBytes);
            o.put("sizeFormatted", formatFileSizeMb(sizeBytes));
            o.put("width", width);
            o.put("height", height);
            o.put("dateAdded", dateAdded);
            o.put("dateModified", dateModified);
            if (includeDeleteCandidate) {
                o.put("deleteCandidate", true);
            }
            return o;
        }
    }

    private static class SimilarImageInfo {
        final GalleryImage image;
        final long dhash;
        final long ahash;
        final ImageProfile profile;
        final int width;
        final int height;

        SimilarImageInfo(GalleryImage image, long dhash, long ahash, ImageProfile profile) {
            this.image = image;
            this.dhash = dhash;
            this.ahash = ahash;
            this.profile = profile;
            this.width = image.width;
            this.height = image.height;
        }

        long pixelCount() {
            return (long) Math.max(0, width) * Math.max(0, height);
        }
    }

    private static class SimilarPair {
        final SimilarImageInfo a;
        final SimilarImageInfo b;
        final int dhashDistance;
        final int ahashDistance;
        final double colorDistance;
        final double lumaStdDelta;
        final double edgeDelta;
        final double aspectDelta;
        final boolean thumbnailCandidate;
        final double rankScore;

        SimilarPair(SimilarImageInfo a, SimilarImageInfo b, int dhashDistance, int ahashDistance,
                    double colorDistance, double lumaStdDelta, double edgeDelta, double aspectDelta,
                    boolean thumbnailCandidate, double rankScore) {
            this.a = a;
            this.b = b;
            this.dhashDistance = dhashDistance;
            this.ahashDistance = ahashDistance;
            this.colorDistance = colorDistance;
            this.lumaStdDelta = lumaStdDelta;
            this.edgeDelta = edgeDelta;
            this.aspectDelta = aspectDelta;
            this.thumbnailCandidate = thumbnailCandidate;
            this.rankScore = rankScore;
        }
    }

    private static class ImageProfile {
        final double avgR;
        final double avgG;
        final double avgB;
        final double lumaStdDev;
        final double edgeScore;

        ImageProfile(double avgR, double avgG, double avgB, double lumaStdDev, double edgeScore) {
            this.avgR = avgR;
            this.avgG = avgG;
            this.avgB = avgB;
            this.lumaStdDev = lumaStdDev;
            this.edgeScore = edgeScore;
        }
    }

    private static class LowQualityCandidate {
        final GalleryImage image;
        final int score;
        final List<String> reasons;
        final boolean unreadable;
        final ImageProfile profile;

        LowQualityCandidate(GalleryImage image, int score, List<String> reasons,
                            boolean unreadable, ImageProfile profile) {
            this.image = image;
            this.score = score;
            this.reasons = reasons;
            this.unreadable = unreadable;
            this.profile = profile;
        }

        JSONObject toJson() throws Exception {
            JSONObject o = image.toJson(true);
            o.put("score", score);
            o.put("reviewCandidate", true);
            o.put("safeToDeleteAutomatically", false);
            o.put("unreadable", unreadable);
            JSONArray arr = new JSONArray();
            for (String reason : reasons) {
                arr.put(reason);
            }
            o.put("reasons", arr);
            o.put("reasonText", joinReasons(reasons));
            if (profile != null) {
                JSONObject p = new JSONObject();
                p.put("avgColor", String.format(Locale.US, "rgb(%.0f,%.0f,%.0f)",
                        profile.avgR, profile.avgG, profile.avgB));
                p.put("lumaStdDev", rounded(profile.lumaStdDev, 1));
                p.put("edgeScore", rounded(profile.edgeScore, 1));
                o.put("profile", p);
            }
            return o;
        }
    }

    private static String joinReasons(List<String> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            return isZh() ? "未发现明显低质量原因" : "No obvious low-quality reason";
        }
        StringBuilder sb = new StringBuilder();
        for (String reason : reasons) {
            if (TextUtils.isEmpty(reason)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(isZh() ? "；" : "; ");
            }
            sb.append(reason);
        }
        return sb.toString();
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

    private static boolean isZh() {
        try {
            return Locale.getDefault().getLanguage().toLowerCase(Locale.ROOT).startsWith("zh");
        } catch (Exception e) {
            return false;
        }
    }

    private static String pgTable(String title, String[] headers, List<String[]> rows) {
        try {
            JSONObject t = new JSONObject();
            t.put("title", title);
            JSONArray h = new JSONArray();
            for (String hdr : headers) {
                h.put(hdr);
            }
            t.put("headers", h);
            JSONArray r = new JSONArray();
            for (String[] row : rows) {
                JSONArray a = new JSONArray();
                for (String c : row) {
                    a.put(c);
                }
                r.put(a);
            }
            t.put("rows", r);
            return "__pg_table__" + t.toString() + "__pg_table_end__";
        } catch (Exception e) {
            return title;
        }
    }

    private static String yesNo(boolean b, boolean zh) {
        return b ? (zh ? "是" : "yes") : (zh ? "否" : "no");
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
        boolean zh = isZh();
        JSONObject o = new JSONObject(outputJson);
        int w = o.optInt("width");
        int h = o.optInt("height");
        String formatLabel;
        if (o.isNull("mimeType")) {
            formatLabel = zh ? "未知" : "Unknown";
        } else {
            formatLabel = mimeTypeToDisplayFormat(o.optString("mimeType"), zh);
        }
        long sizeBytes = o.optLong("fileSizeBytes");
        String path = o.optString("path", "");
        String title = zh ? "图片信息" : "Image Info";
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] { zh ? "尺寸" : "Dimensions", w + "×" + h });
        rows.add(new String[] { zh ? "格式" : "Format", mdCell(formatLabel) });
        rows.add(new String[] { zh ? "文件大小" : "File size", formatFileSizeMb(sizeBytes) });
        rows.add(new String[] { zh ? "路径" : "Path", mdCell(path) });
        JSONObject exif = o.optJSONObject("exif");
        if (exif != null) {
            if (exif.has("error") && !exif.isNull("error")) {
                rows.add(new String[] { "EXIF", mdCell(exif.optString("error")) });
            } else {
                rows.add(new String[] { "EXIF orientation", String.valueOf(exif.optInt("orientation")) });
                String dt = exif.isNull("dateTime") ? "—" : exif.optString("dateTime");
                rows.add(new String[] { "EXIF date/time", mdCell(dt) });
                String dto = exif.isNull("dateTimeOriginal") ? "—" : exif.optString("dateTimeOriginal");
                rows.add(new String[] { "EXIF date/time (original)", mdCell(dto) });
                if (!exif.isNull("gps")) {
                    JSONObject gps = exif.optJSONObject("gps");
                    if (gps != null) {
                        rows.add(new String[] { "GPS latitude", String.valueOf(gps.optDouble("latitude")) });
                        rows.add(new String[] { "GPS longitude", String.valueOf(gps.optDouble("longitude")) });
                        if (gps.has("altitude") && !gps.isNull("altitude")) {
                            rows.add(new String[] { "GPS altitude", mdCell(gps.optString("altitude")) });
                        }
                    }
                } else {
                    rows.add(new String[] { "GPS", "—" });
                }
            }
        }
        return "🖼️ " + title + "\n\n" + pgTable(title, new String[] { zh ? "项目" : "Item", zh ? "值" : "Value" }, rows);
    }

    /**
     * 格式化缩放结果的展示文案。
     *
     * @param outputJson {@code resizeImage} 的 output JSON
     * @return 展示文本
     * @throws Exception JSON 解析异常
     */
    private static String formatResizeImageDisplay(String outputJson) throws Exception {
        boolean zh = isZh();
        JSONObject o = new JSONObject(outputJson);
        int ow = o.optInt("outputWidth");
        int oh = o.optInt("outputHeight");
        String path = o.optString("outputPath");
        String inPath = o.optString("inputPath");
        String title = zh ? "图片已调整大小" : "Image resized";
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] { zh ? "输入路径" : "Input path", mdCell(inPath) });
        rows.add(new String[] { zh ? "原始尺寸" : "Original size",
                o.optInt("originalWidth") + "×" + o.optInt("originalHeight") });
        rows.add(new String[] { zh ? "解码尺寸" : "Decoded size",
                o.optInt("decodedWidth") + "×" + o.optInt("decodedHeight") });
        rows.add(new String[] { zh ? "输出尺寸" : "Output size", ow + "×" + oh });
        rows.add(new String[] { zh ? "降采样" : "Subsampled", yesNo(o.optBoolean("subsampled"), zh) });
        rows.add(new String[] { zh ? "格式" : "Format", mdCell(o.optString("format")) });
        rows.add(new String[] { zh ? "输出路径" : "Output path", mdCell(path) });
        return "✅ " + title + "\n\n" + pgTable(title, new String[] { zh ? "项目" : "Item", zh ? "值" : "Value" }, rows);
    }

    /**
     * 格式化压缩前后大小对比的展示文案。
     *
     * @param outputJson {@code compressImage} 的 output JSON
     * @return 展示文本
     * @throws Exception JSON 解析异常
     */
    private static String formatCompressImageDisplay(String outputJson) throws Exception {
        boolean zh = isZh();
        JSONObject o = new JSONObject(outputJson);
        String inPath = o.optString("inputPath");
        long before = new File(inPath).length();
        long after = o.optLong("outputSizeBytes");
        long saved = Math.max(0L, before - after);
        int pct = before > 0 ? (int) Math.min(100L, (saved * 100L / before)) : 0;
        String path = o.optString("outputPath");
        String title = zh ? "图片已压缩" : "Image compressed";
        String summary = zh
                ? String.format(Locale.US, "已将图片从 %s 压缩到 %s，节省 %s（约 %d%%）。",
                formatFileSizeMb(before), formatFileSizeMb(after), formatFileSizeMb(saved), pct)
                : String.format(Locale.US, "Compressed from %s to %s, saving %s (~%d%%).",
                formatFileSizeMb(before), formatFileSizeMb(after), formatFileSizeMb(saved), pct);
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] { zh ? "压缩前大小" : "Size before", formatFileSizeMb(before) });
        rows.add(new String[] { zh ? "压缩后大小" : "Size after", formatFileSizeMb(after) });
        rows.add(new String[] { zh ? "节省空间" : "Saved", formatFileSizeMb(saved) + " (~" + pct + "%)" });
        rows.add(new String[] { zh ? "压缩质量" : "Quality", String.valueOf(o.optInt("quality")) });
        rows.add(new String[] { zh ? "输出格式" : "Format", mdCell(o.optString("format")) });
        rows.add(new String[] { zh ? "输出文件" : "Output file", mdCell(path) });
        return "✅ " + title + "\n\n" + summary + "\n\n"
                + pgTable(title, new String[] { zh ? "项目" : "Item", zh ? "结果" : "Result" }, rows);
    }

    /**
     * 格式化格式转换结果的展示文案。
     *
     * @param outputJson {@code convertFormat} 的 output JSON
     * @return 展示文本
     * @throws Exception JSON 解析异常
     */
    private static String formatConvertFormatDisplay(String outputJson) throws Exception {
        boolean zh = isZh();
        JSONObject o = new JSONObject(outputJson);
        String fmt = o.optString("format");
        String label = formatKeyToUpperLabel(fmt);
        String path = o.optString("outputPath");
        String inPath = o.optString("inputPath");
        String title = zh ? "图片格式已转换" : "Image format converted";
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] { zh ? "输入路径" : "Input path", mdCell(inPath) });
        rows.add(new String[] { zh ? "输出格式" : "Output format", mdCell(label) });
        rows.add(new String[] { zh ? "原始尺寸" : "Original size",
                o.optInt("originalWidth") + "×" + o.optInt("originalHeight") });
        rows.add(new String[] { zh ? "解码尺寸" : "Decoded size",
                o.optInt("decodedWidth") + "×" + o.optInt("decodedHeight") });
        rows.add(new String[] { zh ? "降采样" : "Subsampled", yesNo(o.optBoolean("subsampled"), zh) });
        rows.add(new String[] { zh ? "输出大小（字节）" : "Output size (bytes)", String.valueOf(o.optLong("outputSizeBytes")) });
        rows.add(new String[] { zh ? "输出路径" : "Output path", mdCell(path) });
        return "✅ " + title + "\n\n" + pgTable(title, new String[] { zh ? "项目" : "Item", zh ? "值" : "Value" }, rows);
    }

    /**
     * 格式化旋转结果的展示文案。
     *
     * @param outputJson {@code rotateImage} 的 output JSON
     * @return 展示文本
     * @throws Exception JSON 解析异常
     */
    private static String formatRotateImageDisplay(String outputJson) throws Exception {
        boolean zh = isZh();
        JSONObject o = new JSONObject(outputJson);
        int deg = o.optInt("degrees");
        String path = o.optString("outputPath");
        String inPath = o.optString("inputPath");
        String title = zh ? "图片已旋转" : "Image rotated";
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] { zh ? "输入路径" : "Input path", mdCell(inPath) });
        rows.add(new String[] { zh ? "角度" : "Degrees", String.valueOf(deg) });
        rows.add(new String[] { zh ? "原始尺寸" : "Original size",
                o.optInt("originalWidth") + "×" + o.optInt("originalHeight") });
        rows.add(new String[] { zh ? "解码尺寸" : "Decoded size",
                o.optInt("decodedWidth") + "×" + o.optInt("decodedHeight") });
        rows.add(new String[] { zh ? "输出尺寸" : "Output size",
                o.optInt("outputWidth") + "×" + o.optInt("outputHeight") });
        rows.add(new String[] { zh ? "降采样" : "Subsampled", yesNo(o.optBoolean("subsampled"), zh) });
        rows.add(new String[] { zh ? "格式" : "Format", mdCell(o.optString("format")) });
        rows.add(new String[] { zh ? "输出路径" : "Output path", mdCell(path) });
        return "✅ " + title + "\n\n" + pgTable(title, new String[] { zh ? "项目" : "Item", zh ? "值" : "Value" }, rows);
    }

    /**
     * 格式化裁剪结果的展示文案。
     *
     * @param outputJson {@code cropImage} 的 output JSON
     * @return 展示文本
     * @throws Exception JSON 解析异常
     */
    private static String formatCropImageDisplay(String outputJson) throws Exception {
        boolean zh = isZh();
        JSONObject o = new JSONObject(outputJson);
        String path = o.optString("outputPath");
        String inPath = o.optString("inputPath");
        String title = zh ? "图片已裁剪" : "Image cropped";
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] { zh ? "输入路径" : "Input path", mdCell(inPath) });
        rows.add(new String[] { zh ? "请求区域" : "Requested region",
                o.optInt("requestedX") + "," + o.optInt("requestedY") + " "
                        + o.optInt("requestedWidth") + "×" + o.optInt("requestedHeight") });
        rows.add(new String[] { zh ? "实际裁剪" : "Actual crop",
                o.optInt("cropX") + "," + o.optInt("cropY") + " "
                        + o.optInt("cropWidth") + "×" + o.optInt("cropHeight") });
        rows.add(new String[] { zh ? "源图尺寸" : "Source image",
                o.optInt("imageWidth") + "×" + o.optInt("imageHeight") });
        rows.add(new String[] { zh ? "输出尺寸" : "Output size",
                o.optInt("outputWidth") + "×" + o.optInt("outputHeight") });
        rows.add(new String[] { zh ? "区域降采样" : "Region subsampled", yesNo(o.optBoolean("regionSubsample"), zh) });
        rows.add(new String[] { zh ? "格式" : "Format", mdCell(o.optString("format")) });
        rows.add(new String[] { zh ? "输出路径" : "Output path", mdCell(path) });
        return "✅ " + title + "\n\n" + pgTable(title, new String[] { zh ? "项目" : "Item", zh ? "值" : "Value" }, rows);
    }

    private static String formatListGalleryImagesDisplay(String outputJson) throws Exception {
        boolean zh = isZh();
        JSONObject o = new JSONObject(outputJson);
        JSONArray images = o.optJSONArray("images");
        int count = images == null ? 0 : images.length();
        String title = isImagePathScope(o)
                ? (zh ? "\u5df2\u8bfb\u53d6\u9009\u4e2d\u56fe\u7247" : "Selected images loaded")
                : (zh ? "\u76f8\u518c\u56fe\u7247\u5df2\u8bfb\u53d6" : "Gallery images loaded");
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] { imageScopeLabel(o, zh), mdCell(imageScopeValue(o, zh)) });
        rows.add(new String[] { zh ? "\u8fd4\u56de\u6570\u91cf" : "Returned", String.valueOf(count) });
        rows.add(new String[] { zh ? "\u67e5\u8be2\u4e0a\u9650" : "Limit", String.valueOf(o.optInt("limit")) });
        int preview = Math.min(count, 8);
        for (int i = 0; i < preview; i++) {
            JSONObject image = images.optJSONObject(i);
            if (image == null) {
                continue;
            }
            String label = (zh ? "\u56fe\u7247 " : "Image ") + (i + 1);
            String value = image.optString("name");
            long size = image.optLong("sizeBytes");
            if (size > 0) {
                value += " | " + formatFileSizeMb(size);
            }
            String path = image.optString("path");
            if (TextUtils.isEmpty(path)) {
                path = image.optString("uri");
            }
            if (!TextUtils.isEmpty(path)) {
                value += " | " + path;
            }
            rows.add(new String[] { label, mdCell(value) });
        }
        return title + "\n\n" + pgTable(title, new String[] {
                zh ? "\u9879\u76ee" : "Item",
                zh ? "\u503c" : "Value"
        }, rows);
    }

    private static String formatAnalyzeGalleryImagesDisplay(String outputJson) throws Exception {
        boolean zh = isZh();
        JSONObject o = new JSONObject(outputJson);
        String title = zh ? "相册分类整理结果" : "Gallery organization summary";
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] { imageScopeLabel(o, zh), mdCell(imageScopeValue(o, zh)) });
        rows.add(new String[] { zh ? "扫描图片" : "Scanned images", String.valueOf(o.optInt("scannedImages")) });
        rows.add(new String[] { zh ? "截图" : "Screenshots", String.valueOf(o.optInt("screenshotCount")) });
        rows.add(new String[] { zh ? "文档/文字图" : "Document/text images", String.valueOf(o.optInt("textOrDocumentCount")) });
        rows.add(new String[] { zh ? "缩略图" : "Thumbnails", String.valueOf(o.optInt("thumbnailCount")) });
        rows.add(new String[] { zh ? "低分辨率图" : "Low-resolution images", String.valueOf(o.optInt("lowResolutionCount")) });
        rows.add(new String[] { zh ? "结论" : "Summary", mdCell(o.optString("summary")) });
        JSONObject categories = o.optJSONObject("categories");
        if (categories != null) {
            addCategoryPreviewRows(rows, categories.optJSONArray("screenshots"), zh ? "截图示例" : "Screenshot examples", zh);
            addCategoryPreviewRows(rows, categories.optJSONArray("textOrDocumentImages"), zh ? "文档图示例" : "Document examples", zh);
            addCategoryPreviewRows(rows, categories.optJSONArray("thumbnails"), zh ? "缩略图示例" : "Thumbnail examples", zh);
            addCategoryPreviewRows(rows, categories.optJSONArray("lowResolutionImages"), zh ? "低分辨率示例" : "Low-res examples", zh);
        }
        return title + "\n\n" + pgTable(title, new String[] {
                zh ? "项目" : "Item",
                zh ? "结果" : "Result"
        }, rows);
    }

    private static void addCategoryPreviewRows(List<String[]> rows, JSONArray images, String label, boolean zh) {
        if (images == null || images.length() == 0) {
            return;
        }
        int limit = Math.min(images.length(), 3);
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < limit; i++) {
            JSONObject image = images.optJSONObject(i);
            if (image == null) {
                continue;
            }
            if (value.length() > 0) {
                value.append(zh ? "；" : "; ");
            }
            value.append(image.optString("name"));
        }
        if (images.length() > limit) {
            value.append(zh ? " 等 " : " and ").append(images.length()).append(zh ? " 张" : " images");
        }
        rows.add(new String[] { label, mdCell(value.toString()) });
    }

    private static boolean isImagePathScope(JSONObject o) {
        return "image_paths".equals(o.optString("scope"));
    }

    private static String imageScopeLabel(JSONObject o, boolean zh) {
        return isImagePathScope(o) ? (zh ? "\u56fe\u7247\u6765\u6e90" : "Image source") : (zh ? "\u76f8\u518c" : "Album");
    }

    private static String imageScopeValue(JSONObject o, boolean zh) {
        if (!isImagePathScope(o)) {
            return o.optString("album");
        }
        int count = o.optInt("inputImageCount", o.optInt("scannedImages", 0));
        return zh ? ("\u9009\u4e2d\u56fe\u7247 " + count + " \u5f20") : (count + " selected image(s)");
    }

    private static String formatFindLowQualityImagesDisplay(String outputJson) throws Exception {
        boolean zh = isZh();
        JSONObject o = new JSONObject(outputJson);
        int candidateCount = o.optInt("candidateCount");
        String title = candidateCount > 0
                ? (zh ? "找到疑似低质量照片" : "Possible low-quality photos found")
                : (zh ? "未发现明显低质量照片" : "No obvious low-quality photos found");
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] { imageScopeLabel(o, zh), mdCell(imageScopeValue(o, zh)) });
        rows.add(new String[] { zh ? "扫描图片" : "Scanned images", String.valueOf(o.optInt("scannedImages")) });
        rows.add(new String[] { zh ? "候选图片" : "Candidates", String.valueOf(candidateCount) });
        rows.add(new String[] { zh ? "无法读取" : "Unreadable images", String.valueOf(o.optInt("unreadableImages")) });
        rows.add(new String[] { zh ? "结论" : "Summary", mdCell(o.optString("summary")) });
        JSONArray candidates = o.optJSONArray("candidates");
        int preview = Math.min(candidates == null ? 0 : candidates.length(), 6);
        for (int i = 0; i < preview; i++) {
            JSONObject candidate = candidates.optJSONObject(i);
            if (candidate == null) {
                continue;
            }
            String value = candidate.optString("name")
                    + " | " + candidate.optInt("score") + "/100"
                    + " | " + candidate.optString("reasonText");
            rows.add(new String[] { (zh ? "候选 " : "Candidate ") + (i + 1), mdCell(value) });
        }
        return title + "\n\n" + pgTable(title, new String[] {
                zh ? "项目" : "Item",
                zh ? "结果" : "Result"
        }, rows);
    }

    private static String formatFindDuplicateImagesDisplay(String outputJson) throws Exception {
        boolean zh = isZh();
        JSONObject o = new JSONObject(outputJson);
        int groupCount = o.optInt("duplicateGroupCount");
        String title = groupCount > 0
                ? (zh ? "\u627e\u5230\u91cd\u590d\u56fe\u7247" : "Duplicate images found")
                : (zh ? "\u672a\u53d1\u73b0\u91cd\u590d\u56fe\u7247" : "No duplicate images found");
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] { imageScopeLabel(o, zh), mdCell(imageScopeValue(o, zh)) });
        rows.add(new String[] { zh ? "\u626b\u63cf\u56fe\u7247" : "Scanned images", String.valueOf(o.optInt("scannedImages", o.optInt("scannedCount"))) });
        rows.add(new String[] { zh ? "\u91cd\u590d\u7ec4" : "Duplicate groups", String.valueOf(groupCount) });
        rows.add(new String[] { zh ? "\u91cd\u590d\u56fe\u7247" : "Duplicate images", String.valueOf(o.optInt("duplicateImageCount")) });
        rows.add(new String[] { zh ? "\u53ef\u91ca\u653e\u7a7a\u95f4" : "Reclaimable", o.optString("reclaimableFormatted") });
        rows.add(new String[] { zh ? "\u5df2\u8df3\u8fc7\u8fc7\u5c0f\u56fe\u7247" : "Skipped small images", String.valueOf(o.optInt("skippedSmallImages", o.optInt("skippedSmallFiles"))) });
        JSONArray groups = o.optJSONArray("duplicateGroups");
        int preview = Math.min(groups == null ? 0 : groups.length(), 5);
        for (int i = 0; i < preview; i++) {
            JSONObject group = groups.optJSONObject(i);
            if (group == null) {
                continue;
            }
            JSONObject keep = group.optJSONObject("keepSuggestion");
            JSONArray dupes = group.optJSONArray("duplicates");
            String keepName = keep == null ? "" : keep.optString("name");
            String value = (dupes == null ? 0 : dupes.length()) + " "
                    + (zh ? "\u5f20\u53ef\u5904\u7406" : "can be cleaned")
                    + (TextUtils.isEmpty(keepName) ? "" : " | keep " + keepName)
                    + " | " + formatFileSizeMb(group.optLong("sizeBytes"));
            rows.add(new String[] { (zh ? "\u91cd\u590d\u7ec4 " : "Group ") + (i + 1), mdCell(value) });
        }
        return title + "\n\n" + pgTable(title, new String[] {
                zh ? "\u9879\u76ee" : "Item",
                zh ? "\u503c" : "Value"
        }, rows);
    }

    private static String formatFindSimilarImagesDisplay(String outputJson) throws Exception {
        boolean zh = isZh();
        JSONObject o = new JSONObject(outputJson);
        int groupCount = o.optInt("candidateGroupCount");
        int strictCount = o.optInt("strictCandidateGroupCount");
        int nearestCount = o.optInt("nearestReviewGroupCount");
        int candidateImageCount = o.optInt("candidateImageCount");
        String title;
        if (groupCount <= 0) {
            title = zh ? "\u672a\u53d1\u73b0\u76f8\u4f3c\u56fe\u7247\u5019\u9009" : "No similar image candidates found";
        } else if (nearestCount > 0 && strictCount == 0) {
            title = zh ? "\u627e\u5230\u6700\u63a5\u8fd1\u7684\u76f8\u4f3c\u56fe\u5019\u9009" : "Nearest similar image review pairs found";
        } else {
            title = zh ? "\u627e\u5230\u76f8\u4f3c\u56fe\u7247\u5019\u9009" : "Similar image candidates found";
        }
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] { imageScopeLabel(o, zh), mdCell(imageScopeValue(o, zh)) });
        String requestedAlbum = o.optString("requestedAlbum");
        if (!TextUtils.isEmpty(requestedAlbum) && !safeEquals(requestedAlbum, o.optString("album"))) {
            rows.add(new String[] { zh ? "\u7528\u6237\u8f93\u5165" : "Requested", mdCell(requestedAlbum) });
        }
        rows.add(new String[] { zh ? "\u626b\u63cf\u56fe\u7247" : "Scanned images", String.valueOf(o.optInt("scannedImages")) });
        if (groupCount > 0) {
            rows.add(new String[] { zh ? "\u76f8\u4f3c\u7167\u7247\u7ec4" : "Similar photo groups", String.valueOf(groupCount) });
            rows.add(new String[] { zh ? "\u9700\u8981\u4f60\u786e\u8ba4\u7684\u7167\u7247" : "Photos to review", String.valueOf(candidateImageCount) });
            int thumbnailCount = o.optInt("thumbnailCandidateGroupCount");
            if (thumbnailCount > 0) {
                rows.add(new String[] { zh ? "\u7591\u4f3c\u7f29\u7565\u56fe/\u538b\u7f29\u526f\u672c" : "Possible thumbnails/copies", String.valueOf(thumbnailCount) });
            }
        } else {
            rows.add(new String[] { zh ? "\u7ed3\u679c" : "Result", mdCell(zh ? "\u672a\u53d1\u73b0\u660e\u663e\u76f8\u4f3c\u7167\u7247" : "No obvious similar photos found") });
        }
        JSONArray groups = o.optJSONArray("candidateGroups");
        int preview = Math.min(groups == null ? 0 : groups.length(), 5);
        for (int i = 0; i < preview; i++) {
            JSONObject group = groups.optJSONObject(i);
            if (group == null) {
                continue;
            }
            JSONObject keep = group.optJSONObject("keepSuggestion");
            JSONArray candidates = group.optJSONArray("candidates");
            String keepName = keep == null ? "" : keep.optString("name");
            String value = (candidates == null ? 0 : candidates.length()) + " "
                    + (zh ? "\u5f20\u5f85\u786e\u8ba4" : "to review")
                    + (TextUtils.isEmpty(keepName) ? "" : (zh ? " | \u5efa\u8bae\u5148\u4fdd\u7559\uff1a" : " | suggested keep: ") + keepName)
                    + " | " + similarRelation(group.optString("matchType"), zh);
            rows.add(new String[] { (zh ? "\u76f8\u4f3c\u7ec4 " : "Group ") + (i + 1), mdCell(value) });
        }
        if (groupCount == 0) {
            rows.add(new String[] { zh ? "\u4e0b\u4e00\u6b65" : "Next step",
                    mdCell(zh
                            ? "\u5982\u679c\u8089\u773c\u770b\u5230\u6709\u76f8\u4f3c\u7167\u7247\uff0c\u53ef\u4ee5\u8bf4\u201c\u653e\u5bbd\u76f8\u4f3c\u5ea6\u91cd\u65b0\u626b\u63cf\u201d\u3002\u653e\u5bbd\u626b\u63cf\u4e5f\u53ea\u4f1a\u5217\u51fa\u5019\u9009\uff0c\u4e0d\u4f1a\u81ea\u52a8\u5220\u56fe\u3002"
                            : "If you can see similar photos, ask PandaGenie to run a looser scan. It will still only list candidates and will not delete anything automatically.") });
        } else {
            rows.add(new String[] { zh ? "\u5efa\u8bae" : "Suggestion",
                    mdCell(zh
                            ? "\u70b9\u5f00\u6bcf\u7ec4\u7167\u7247\u5bf9\u6bd4\u540e\u518d\u9009\u62e9\u3002\u4f18\u5148\u4fdd\u7559\u66f4\u6e05\u6670\u3001\u5c3a\u5bf8\u66f4\u5927\u6216\u62cd\u6444\u65f6\u95f4\u66f4\u65b0\u7684\u4e00\u5f20\u3002"
                            : "Open each group and compare the photos before selecting anything. Prefer the clearer, larger, or newer photo.") });
        }
        return title + "\n\n" + pgTable(title, new String[] {
                zh ? "\u9879\u76ee" : "Item",
                zh ? "\u503c" : "Value"
        }, rows);
    }

    // ==================== _displayHtml (HtmlOutputHelper) ====================

    private static String formatGetImageInfoHtml(String outputJson) throws Exception {
        boolean zh = isZh();
        JSONObject o = new JSONObject(outputJson);
        int w = o.optInt("width");
        int h = o.optInt("height");
        String formatLabel;
        if (o.isNull("mimeType")) {
            formatLabel = zh ? "未知" : "Unknown";
        } else {
            formatLabel = mimeTypeToDisplayFormat(o.optString("mimeType"), zh);
        }
        long sizeBytes = o.optLong("fileSizeBytes");
        String path = o.optString("path", "");
        String title = zh ? "图片信息" : "Image Info";
        List<String[]> pairs = new ArrayList<>();
        pairs.add(new String[] { zh ? "尺寸" : "Dimensions", w + "×" + h });
        pairs.add(new String[] { zh ? "格式" : "Format", formatLabel });
        pairs.add(new String[] { zh ? "文件大小" : "File size", formatFileSizeMb(sizeBytes) });
        pairs.add(new String[] { zh ? "路径" : "Path", path });
        JSONObject exif = o.optJSONObject("exif");
        if (exif != null) {
            if (exif.has("error") && !exif.isNull("error")) {
                pairs.add(new String[] { "EXIF", exif.optString("error") });
            } else {
                pairs.add(new String[] { "EXIF orientation", String.valueOf(exif.optInt("orientation")) });
                String dt = exif.isNull("dateTime") ? "—" : exif.optString("dateTime");
                pairs.add(new String[] { "EXIF date/time", dt });
                String dto = exif.isNull("dateTimeOriginal") ? "—" : exif.optString("dateTimeOriginal");
                pairs.add(new String[] { "EXIF date/time (original)", dto });
                if (!exif.isNull("gps")) {
                    JSONObject gps = exif.optJSONObject("gps");
                    if (gps != null) {
                        pairs.add(new String[] { "GPS latitude", String.valueOf(gps.optDouble("latitude")) });
                        pairs.add(new String[] { "GPS longitude", String.valueOf(gps.optDouble("longitude")) });
                        if (gps.has("altitude") && !gps.isNull("altitude")) {
                            pairs.add(new String[] { "GPS altitude", gps.optString("altitude") });
                        }
                    }
                } else {
                    pairs.add(new String[] { "GPS", "—" });
                }
            }
        }
        return HtmlOutputHelper.card("🖼️", title, HtmlOutputHelper.keyValue(pairs.toArray(new String[0][])));
    }

    private static String formatResizeImageHtml(String outputJson) throws Exception {
        boolean zh = isZh();
        JSONObject o = new JSONObject(outputJson);
        int ow = o.optInt("outputWidth");
        int oh = o.optInt("outputHeight");
        String path = o.optString("outputPath");
        String inPath = o.optString("inputPath");
        String title = zh ? "图片已调整大小" : "Image resized";
        String body = HtmlOutputHelper.successBadge()
                + HtmlOutputHelper.keyValue(new String[][] {
                { zh ? "输入路径" : "Input path", inPath },
                { zh ? "原始尺寸" : "Original size", o.optInt("originalWidth") + "×" + o.optInt("originalHeight") },
                { zh ? "解码尺寸" : "Decoded size", o.optInt("decodedWidth") + "×" + o.optInt("decodedHeight") },
                { zh ? "输出尺寸" : "Output size", ow + "×" + oh },
                { zh ? "降采样" : "Subsampled", yesNo(o.optBoolean("subsampled"), zh) },
                { zh ? "格式" : "Format", o.optString("format") },
                { zh ? "输出路径" : "Output path", path }
        });
        return HtmlOutputHelper.card("✅", title, body);
    }

    private static String formatCompressImageHtml(String outputJson) throws Exception {
        boolean zh = isZh();
        JSONObject o = new JSONObject(outputJson);
        String inPath = o.optString("inputPath");
        long before = new File(inPath).length();
        long after = o.optLong("outputSizeBytes");
        long saved = Math.max(0L, before - after);
        int pct = before > 0 ? (int) Math.min(100L, (saved * 100L / before)) : 0;
        String path = o.optString("outputPath");
        String title = zh ? "图片已压缩" : "Image compressed";
        String body = HtmlOutputHelper.successBadge()
                + HtmlOutputHelper.metricGrid(new String[][] {
                { formatFileSizeMb(before), zh ? "压缩前" : "Before" },
                { formatFileSizeMb(after), zh ? "压缩后" : "After" },
                { "~" + pct + "%", zh ? "节省比例" : "Saved" },
                { String.valueOf(o.optInt("quality")), zh ? "质量" : "Quality" }
        })
                + HtmlOutputHelper.gauge(pct, "#4CAF50")
                + HtmlOutputHelper.muted((zh ? "输出文件：" : "Output file: ") + path);
        return HtmlOutputHelper.card("✅", title, body);
    }

    private static String formatConvertFormatHtml(String outputJson) throws Exception {
        boolean zh = isZh();
        JSONObject o = new JSONObject(outputJson);
        String fmt = o.optString("format");
        String label = formatKeyToUpperLabel(fmt);
        String path = o.optString("outputPath");
        String inPath = o.optString("inputPath");
        String title = zh ? "图片格式已转换" : "Image format converted";
        String body = HtmlOutputHelper.successBadge()
                + HtmlOutputHelper.keyValue(new String[][] {
                { zh ? "输入路径" : "Input path", inPath },
                { zh ? "输出格式" : "Output format", label },
                { zh ? "原始尺寸" : "Original size", o.optInt("originalWidth") + "×" + o.optInt("originalHeight") },
                { zh ? "解码尺寸" : "Decoded size", o.optInt("decodedWidth") + "×" + o.optInt("decodedHeight") },
                { zh ? "降采样" : "Subsampled", yesNo(o.optBoolean("subsampled"), zh) },
                { zh ? "输出大小（字节）" : "Output size (bytes)", String.valueOf(o.optLong("outputSizeBytes")) },
                { zh ? "输出路径" : "Output path", path }
        });
        return HtmlOutputHelper.card("✅", title, body);
    }

    private static String formatRotateImageHtml(String outputJson) throws Exception {
        boolean zh = isZh();
        JSONObject o = new JSONObject(outputJson);
        int deg = o.optInt("degrees");
        String path = o.optString("outputPath");
        String inPath = o.optString("inputPath");
        String title = zh ? "图片已旋转" : "Image rotated";
        String body = HtmlOutputHelper.successBadge()
                + HtmlOutputHelper.keyValue(new String[][] {
                { zh ? "输入路径" : "Input path", inPath },
                { zh ? "角度" : "Degrees", String.valueOf(deg) },
                { zh ? "原始尺寸" : "Original size", o.optInt("originalWidth") + "×" + o.optInt("originalHeight") },
                { zh ? "解码尺寸" : "Decoded size", o.optInt("decodedWidth") + "×" + o.optInt("decodedHeight") },
                { zh ? "输出尺寸" : "Output size", o.optInt("outputWidth") + "×" + o.optInt("outputHeight") },
                { zh ? "降采样" : "Subsampled", yesNo(o.optBoolean("subsampled"), zh) },
                { zh ? "格式" : "Format", o.optString("format") },
                { zh ? "输出路径" : "Output path", path }
        });
        return HtmlOutputHelper.card("✅", title, body);
    }

    private static String formatCropImageHtml(String outputJson) throws Exception {
        boolean zh = isZh();
        JSONObject o = new JSONObject(outputJson);
        String path = o.optString("outputPath");
        String inPath = o.optString("inputPath");
        String title = zh ? "图片已裁剪" : "Image cropped";
        String body = HtmlOutputHelper.successBadge()
                + HtmlOutputHelper.keyValue(new String[][] {
                { zh ? "输入路径" : "Input path", inPath },
                { zh ? "请求区域" : "Requested region",
                        o.optInt("requestedX") + "," + o.optInt("requestedY") + " "
                                + o.optInt("requestedWidth") + "×" + o.optInt("requestedHeight") },
                { zh ? "实际裁剪" : "Actual crop",
                        o.optInt("cropX") + "," + o.optInt("cropY") + " "
                                + o.optInt("cropWidth") + "×" + o.optInt("cropHeight") },
                { zh ? "源图尺寸" : "Source image", o.optInt("imageWidth") + "×" + o.optInt("imageHeight") },
                { zh ? "输出尺寸" : "Output size", o.optInt("outputWidth") + "×" + o.optInt("outputHeight") },
                { zh ? "区域降采样" : "Region subsampled", yesNo(o.optBoolean("regionSubsample"), zh) },
                { zh ? "格式" : "Format", o.optString("format") },
                { zh ? "输出路径" : "Output path", path }
        });
        return HtmlOutputHelper.card("✅", title, body);
    }

    private static String formatListGalleryImagesHtml(String outputJson) throws Exception {
        boolean zh = isZh();
        JSONObject o = new JSONObject(outputJson);
        JSONArray images = o.optJSONArray("images");
        int count = images == null ? 0 : images.length();
        String title = zh ? "\u76f8\u518c\u56fe\u7247\u5df2\u8bfb\u53d6" : "Gallery images loaded";
        List<String[]> pairs = new ArrayList<>();
        pairs.add(new String[] { imageScopeLabel(o, zh), imageScopeValue(o, zh) });
        pairs.add(new String[] { zh ? "\u8fd4\u56de\u6570\u91cf" : "Returned", String.valueOf(count) });
        pairs.add(new String[] { zh ? "\u67e5\u8be2\u4e0a\u9650" : "Limit", String.valueOf(o.optInt("limit")) });
        if (count > 0) {
            JSONObject first = images.optJSONObject(0);
            if (first != null) {
                pairs.add(new String[] { zh ? "\u6700\u65b0\u56fe\u7247" : "Newest image", first.optString("name") });
                pairs.add(new String[] { zh ? "\u6700\u65b0\u56fe\u7247\u5927\u5c0f" : "Newest image size",
                        formatFileSizeMb(first.optLong("sizeBytes")) });
            }
        }
        return HtmlOutputHelper.card("IMG", title,
                HtmlOutputHelper.keyValue(pairs.toArray(new String[0][])));
    }

    private static String formatAnalyzeGalleryImagesHtml(String outputJson) throws Exception {
        boolean zh = isZh();
        JSONObject o = new JSONObject(outputJson);
        String title = zh ? "相册分类整理结果" : "Gallery organization summary";
        String body = HtmlOutputHelper.metricGrid(new String[][] {
                { String.valueOf(o.optInt("scannedImages")), zh ? "扫描图片" : "Scanned" },
                { String.valueOf(o.optInt("screenshotCount")), zh ? "截图" : "Screenshots" },
                { String.valueOf(o.optInt("textOrDocumentCount")), zh ? "文档/文字图" : "Docs/Text" },
                { String.valueOf(o.optInt("thumbnailCount")), zh ? "缩略图" : "Thumbnails" },
                { String.valueOf(o.optInt("lowResolutionCount")), zh ? "低分辨率图" : "Low-res" }
        });
        body += HtmlOutputHelper.callout(
                zh ? "整理建议" : "Organization suggestion",
                o.optString("summary"),
                "warn");
        JSONObject categories = o.optJSONObject("categories");
        if (categories != null) {
            body += galleryCategoryHtml(zh ? "截图" : "Screenshots", categories.optJSONArray("screenshots"), zh);
            body += galleryCategoryHtml(zh ? "文档/文字图" : "Document/Text images", categories.optJSONArray("textOrDocumentImages"), zh);
            body += galleryCategoryHtml(zh ? "缩略图" : "Thumbnails", categories.optJSONArray("thumbnails"), zh);
            body += galleryCategoryHtml(zh ? "低分辨率图" : "Low-resolution images", categories.optJSONArray("lowResolutionImages"), zh);
            body += galleryCountSection(zh ? "按相册统计" : "By album", categories.optJSONArray("byAlbum"), zh);
            body += galleryCountSection(zh ? "按月份统计" : "By month", categories.optJSONArray("byMonth"), zh);
        }
        body += HtmlOutputHelper.muted(o.optString("note"));
        return HtmlOutputHelper.card("ORG", title, body);
    }

    private static String galleryCategoryHtml(String title, JSONArray images, boolean zh) {
        if (images == null || images.length() == 0) {
            return "";
        }
        StringBuilder body = new StringBuilder();
        int limit = Math.min(images.length(), 5);
        for (int i = 0; i < limit; i++) {
            JSONObject image = images.optJSONObject(i);
            if (image == null) {
                continue;
            }
            body.append(HtmlOutputHelper.item(
                    image.optString("name", zh ? "未命名图片" : "Unnamed image"),
                    image.optInt("width") + "×" + image.optInt("height") + " · " + image.optString("sizeFormatted"),
                    image.optString("path", image.optString("uri", ""))));
        }
        if (images.length() > limit) {
            body.append(HtmlOutputHelper.muted((zh ? "还有 " : "")
                    + (images.length() - limit)
                    + (zh ? " 张同类图片，点“查看详情”看完整结果。" : " more in this category. Tap View Details for the full result.")));
        }
        return HtmlOutputHelper.section(title, body.toString());
    }

    private static String galleryCountSection(String title, JSONArray items, boolean zh) {
        if (items == null || items.length() == 0) {
            return "";
        }
        List<String[]> rows = new ArrayList<>();
        int limit = Math.min(items.length(), 6);
        for (int i = 0; i < limit; i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String name = "";
            JSONArray names = item.names();
            if (names != null) {
                for (int j = 0; j < names.length(); j++) {
                    String key = names.optString(j);
                    if (!"count".equals(key)) {
                        name = item.optString(key);
                        break;
                    }
                }
            }
            rows.add(new String[] { name, String.valueOf(item.optInt("count")) });
        }
        return HtmlOutputHelper.section(title, HtmlOutputHelper.table(new String[] {
                zh ? "分类" : "Category",
                zh ? "数量" : "Count"
        }, rows));
    }

    private static String formatFindLowQualityImagesHtml(String outputJson) throws Exception {
        boolean zh = isZh();
        JSONObject o = new JSONObject(outputJson);
        int candidateCount = o.optInt("candidateCount");
        String title = candidateCount > 0
                ? (zh ? "疑似低质量照片候选" : "Low-quality photo candidates")
                : (zh ? "未发现明显低质量照片" : "No obvious low-quality photos");
        String body = candidateCount > 0 ? HtmlOutputHelper.badge(zh ? "需要人工确认" : "Review needed", "orange") : HtmlOutputHelper.successBadge();
        body += HtmlOutputHelper.keyValue(new String[][] {
                { zh ? "相册" : "Album", o.optString("album") },
                { zh ? "扫描图片" : "Scanned images", String.valueOf(o.optInt("scannedImages")) },
                { zh ? "候选图片" : "Candidates", String.valueOf(candidateCount) },
                { zh ? "无法读取" : "Unreadable", String.valueOf(o.optInt("unreadableImages")) }
        });
        body += HtmlOutputHelper.callout(
                zh ? "说明" : "Note",
                o.optString("summary") + " " + o.optString("note"),
                candidateCount > 0 ? "warn" : "");
        JSONArray candidates = o.optJSONArray("candidates");
        if (candidates != null && candidates.length() > 0) {
            StringBuilder items = new StringBuilder();
            int limit = Math.min(candidates.length(), 8);
            for (int i = 0; i < limit; i++) {
                JSONObject candidate = candidates.optJSONObject(i);
                if (candidate == null) {
                    continue;
                }
                items.append(HtmlOutputHelper.item(
                        candidate.optString("name", zh ? "未命名图片" : "Unnamed image"),
                        candidate.optInt("score") + "/100 · "
                                + candidate.optInt("width") + "×" + candidate.optInt("height")
                                + " · " + candidate.optString("sizeFormatted"),
                        candidate.optString("reasonText") + "\n" + candidate.optString("path", candidate.optString("uri", ""))));
            }
            body += HtmlOutputHelper.section(zh ? "候选列表" : "Candidates", items.toString());
        }
        return HtmlOutputHelper.card("LOW", title, body);
    }

    private static String formatFindDuplicateImagesHtml(String outputJson) throws Exception {
        boolean zh = isZh();
        JSONObject o = new JSONObject(outputJson);
        int groupCount = o.optInt("duplicateGroupCount");
        String title = groupCount > 0
                ? (zh ? "\u627e\u5230\u91cd\u590d\u56fe\u7247" : "Duplicate images found")
                : (zh ? "\u672a\u53d1\u73b0\u91cd\u590d\u56fe\u7247" : "No duplicate images found");
        List<String[]> pairs = new ArrayList<>();
        pairs.add(new String[] { imageScopeLabel(o, zh), imageScopeValue(o, zh) });
        pairs.add(new String[] { zh ? "\u626b\u63cf\u56fe\u7247" : "Scanned images", String.valueOf(o.optInt("scannedImages", o.optInt("scannedCount"))) });
        pairs.add(new String[] { zh ? "\u91cd\u590d\u7ec4" : "Duplicate groups", String.valueOf(groupCount) });
        pairs.add(new String[] { zh ? "\u91cd\u590d\u56fe\u7247" : "Duplicate images", String.valueOf(o.optInt("duplicateImageCount")) });
        pairs.add(new String[] { zh ? "\u53ef\u91ca\u653e\u7a7a\u95f4" : "Reclaimable", o.optString("reclaimableFormatted") });
        pairs.add(new String[] { zh ? "\u5df2\u8df3\u8fc7\u8fc7\u5c0f\u56fe\u7247" : "Skipped small images", String.valueOf(o.optInt("skippedSmallImages", o.optInt("skippedSmallFiles"))) });
        String body = groupCount > 0 ? HtmlOutputHelper.successBadge() : "";
        body += HtmlOutputHelper.keyValue(pairs.toArray(new String[0][]));
        return HtmlOutputHelper.card("DUP", title, body);
    }

    private static String formatFindSimilarImagesHtml(String outputJson) throws Exception {
        return formatFindSimilarImagesHtml(outputJson, false);
    }

    private static String formatFindSimilarImagesHtml(String outputJson, boolean full) throws Exception {
        boolean zh = isZh();
        JSONObject o = new JSONObject(outputJson);
        int groupCount = o.optInt("candidateGroupCount");
        int strictCount = o.optInt("strictCandidateGroupCount");
        int nearestCount = o.optInt("nearestReviewGroupCount");
        int candidateImageCount = o.optInt("candidateImageCount");
        String title;
        if (groupCount <= 0) {
            title = zh ? "\u672a\u53d1\u73b0\u76f8\u4f3c\u56fe\u7247\u5019\u9009" : "No similar image candidates found";
        } else if (nearestCount > 0 && strictCount == 0) {
            title = zh ? "\u627e\u5230\u6700\u63a5\u8fd1\u7684\u76f8\u4f3c\u56fe\u5019\u9009" : "Nearest similar image review pairs found";
        } else {
            title = zh ? "\u627e\u5230\u76f8\u4f3c\u56fe\u7247\u5019\u9009" : "Similar image candidates found";
        }
        List<String[]> pairs = new ArrayList<>();
        pairs.add(new String[] { zh ? "\u76f8\u518c" : "Album", o.optString("album") });
        String requestedAlbum = o.optString("requestedAlbum");
        if (!TextUtils.isEmpty(requestedAlbum) && !safeEquals(requestedAlbum, o.optString("album"))) {
            pairs.add(new String[] { zh ? "\u7528\u6237\u8f93\u5165" : "Requested", requestedAlbum });
        }
        pairs.add(new String[] { zh ? "\u626b\u63cf\u56fe\u7247" : "Scanned images", String.valueOf(o.optInt("scannedImages")) });
        if (groupCount > 0) {
            pairs.add(new String[] { zh ? "\u76f8\u4f3c\u7167\u7247\u7ec4" : "Similar photo groups", String.valueOf(groupCount) });
            pairs.add(new String[] { zh ? "\u9700\u8981\u4f60\u786e\u8ba4\u7684\u7167\u7247" : "Photos to review", String.valueOf(candidateImageCount) });
            int thumbnailCount = o.optInt("thumbnailCandidateGroupCount");
            if (thumbnailCount > 0) {
                pairs.add(new String[] { zh ? "\u7591\u4f3c\u7f29\u7565\u56fe/\u538b\u7f29\u526f\u672c" : "Possible thumbnails/copies", String.valueOf(thumbnailCount) });
            }
        } else {
            pairs.add(new String[] { zh ? "\u7ed3\u679c" : "Result", zh ? "\u672a\u53d1\u73b0\u660e\u663e\u76f8\u4f3c\u7167\u7247" : "No obvious similar photos found" });
        }
        String body = groupCount > 0 ? HtmlOutputHelper.successBadge() : "";
        body += HtmlOutputHelper.keyValue(pairs.toArray(new String[0][]));
        if (groupCount > 0) {
            body += HtmlOutputHelper.callout(
                    zh ? "\u8bf7\u624b\u52a8\u786e\u8ba4\u518d\u5220\u9664" : "Review before deleting",
                    nearestCount > 0 && strictCount == 0
                            ? (zh
                            ? "\u6ca1\u6709\u547d\u4e2d\u9ad8\u7f6e\u4fe1\u7684\u76f8\u4f3c\u7ec4\uff0c\u4e0b\u65b9\u53ea\u5217\u51fa\u6700\u63a5\u8fd1\u7684\u51e0\u7ec4\u4f9b\u4eba\u5de5\u770b\u56fe\u590d\u6838\u3002\u8fd9\u4e9b\u4e0d\u4ee3\u8868\u53ef\u4ee5\u5220\u9664\uff0c\u53ea\u662f\u5e2e\u4f60\u5feb\u901f\u5b9a\u4f4d\u53ef\u80fd\u76f8\u50cf\u7684\u7167\u7247\u3002"
                            : "No high-confidence group was found. The list below only shows the closest pairs for manual visual review. They are not delete decisions; they just help you locate photos that may look alike.")
                            : (zh
                            ? "\u8bf7\u70b9\u5f00\u5206\u7ec4\u9010\u5f20\u5bf9\u6bd4\u3002\u4e00\u822c\u4f18\u5148\u4fdd\u7559\u66f4\u6e05\u6670\u3001\u5c3a\u5bf8\u66f4\u5927\u6216\u62cd\u6444\u65f6\u95f4\u66f4\u65b0\u7684\u4e00\u5f20\u3002\u6a59\u8272\u7167\u7247\u53ea\u662f\u9700\u8981\u4f60\u590d\u6838\u7684\u5019\u9009\uff0c\u4e0d\u4ee3\u8868\u4e00\u5b9a\u8981\u5220\u3002"
                            : "Open each group and compare the photos. Prefer the clearer, larger, or newer photo. Orange photos are review candidates, not automatic delete decisions."),
                    "warn");
        } else {
            body += HtmlOutputHelper.callout(
                    zh ? "\u672c\u6b21\u6ca1\u6709\u547d\u4e2d\u5019\u9009" : "No candidates in this scan",
                    zh
                            ? "\u5982\u679c\u8089\u773c\u770b\u5230\u6709\u76f8\u4f3c\u7167\u7247\uff0c\u53ef\u4ee5\u8bf4\u201c\u653e\u5bbd\u76f8\u4f3c\u5ea6\u91cd\u65b0\u626b\u63cf\u201d\u3002\u653e\u5bbd\u626b\u63cf\u4e5f\u53ea\u4f1a\u5217\u51fa\u5019\u9009\uff0c\u4e0d\u4f1a\u81ea\u52a8\u5220\u56fe\u3002"
                            : "If you can see similar photos, ask PandaGenie to run a looser scan. It will still only list candidates and will not delete anything automatically.",
                    "warn");
        }
        return HtmlOutputHelper.card("\uD83D\uDD0D", title, body);
    }

    private static String similarRelation(String raw, boolean zh) {
        if ("thumbnailCandidate".equals(raw)) {
            return zh ? "\u7591\u4f3c\u7f29\u7565\u56fe/\u538b\u7f29\u526f\u672c" : "possible thumbnail/copy";
        }
        if ("nearVisualReview".equals(raw)) {
            return zh ? "\u770b\u8d77\u6765\u6709\u70b9\u50cf" : "looks similar";
        }
        return zh ? "\u753b\u9762\u76f8\u4f3c" : "similar look";
    }

    private static JSONObject similarImageGroupsRichItem(JSONObject output, JSONArray groups, boolean zh) throws Exception {
        int candidateCount = output.optInt("candidateImageCount", 0);
        if (candidateCount <= 0) {
            for (int i = 0; i < groups.length(); i++) {
                JSONObject group = groups.optJSONObject(i);
                JSONArray candidates = group == null ? null : group.optJSONArray("candidates");
                if (candidates != null) {
                    candidateCount += candidates.length();
                }
            }
        }
        JSONObject payload = new JSONObject();
        payload.put("title", zh ? "\u76f8\u4f3c\u7167\u7247\u5206\u7ec4" : "Similar photo groups");
        payload.put("summary", zh
                ? "\u53d1\u73b0 " + groups.length() + " \u7ec4\u76f8\u4f3c\u7167\u7247\uff0c" + candidateCount + " \u5f20\u5019\u9009\u9700\u8981\u4eba\u5de5\u590d\u6838\u3002\u7eff\u8272\u4e3a\u5efa\u8bae\u4fdd\u7559\uff0c\u6a59\u8272\u4e3a\u53ef\u52fe\u9009\u5220\u9664\u5019\u9009\u3002"
                : "Found " + groups.length() + " similar-photo groups and " + candidateCount + " review candidates. Green means keep; orange means selectable delete candidate.");
        payload.put("album", output.optString("album", ""));
        payload.put("requestedAlbum", output.optString("requestedAlbum", ""));
        payload.put("scannedImages", output.optInt("scannedImages", 0));
        payload.put("candidateImageCount", candidateCount);
        payload.put("safeToDeleteAutomatically", false);
        payload.put("groups", groups);

        JSONObject item = new JSONObject();
        item.put("type", "image_groups");
        item.put("title", payload.optString("title"));
        item.put("description", payload.optString("summary"));
        item.put("category", "similar_images");
        item.put("value", payload.toString());
        return item;
    }

    private static JSONArray similarImagesRichContent(String outputJson) throws Exception {
        boolean zh = isZh();
        JSONObject o = new JSONObject(outputJson);
        JSONArray groups = o.optJSONArray("candidateGroups");
        JSONArray rc = new JSONArray();
        if (groups == null || groups.length() == 0) {
            return rc;
        }
        rc.put(similarImageGroupsRichItem(o, groups, zh));
        return rc;
    }

    /**
     * 将 MIME 类型转为简短展示用格式名（如 JPEG、PNG）。
     *
     * @param mime 原始 MIME，可为 null
     * @return 展示标签
     */
    private static String mimeTypeToDisplayFormat(String mime, boolean zh) {
        if (mime == null || mime.isEmpty()) {
            return zh ? "未知" : "Unknown";
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
     * 构造成功响应 JSON，可选附带 {@code _displayText}、{@code _displayHtml} 与 {@code _richContent}。
     *
     * @param output      业务结果字符串（常为嵌套 JSON）
     * @param displayText 可选的展示文案；null 或空则省略该字段
     * @param displayHtml 可选的 HTML 迷你卡片；null 或空则省略该字段
     * @param richContent 可选的富媒体条目数组；null 或空则省略该字段
     * @return 完整响应 JSON 字符串
     * @throws Exception JSON 构造异常
     */
    private static String ok(String output, String displayText, String displayHtml, String displayHtmlFull, JSONArray richContent) throws Exception {
        JSONObject r = new JSONObject().put("success", true).put("output", output);
        if (displayText != null && !displayText.isEmpty()) {
            r.put("_displayText", displayText);
        }
        if (displayHtml != null && !displayHtml.isEmpty()) {
            r.put("_displayHtml", displayHtml);
        }
        if (displayHtmlFull != null && !displayHtmlFull.isEmpty()) {
            r.put("_displayHtmlFull", displayHtmlFull);
        }
        if (richContent != null && richContent.length() > 0) {
            r.put("_richContent", richContent);
        }
        return r.toString();
    }

    private static String ok(String output, String displayText, String displayHtml, JSONArray richContent) throws Exception {
        return ok(output, displayText, displayHtml, null, richContent);
    }

    private static String ok(String output, String displayText) throws Exception {
        return ok(output, displayText, null, null);
    }

    /**
     * 成功响应且不附加展示文案。
     *
     * @param output 业务 output 字符串
     * @return JSON 响应
     * @throws Exception JSON 构造异常
     */
    private static String ok(String output) throws Exception {
        return ok(output, null, null, null);
    }

    private static JSONObject richImage(String path, String title) throws Exception {
        JSONObject rc = new JSONObject();
        rc.put("type", "image");
        rc.put("path", path);
        if (title != null && !title.isEmpty()) {
            rc.put("title", title);
        }
        return rc;
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
