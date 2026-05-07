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
import android.provider.MediaStore;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
        case "findDuplicateImages": {
            String out = findDuplicateImages(context, params);
            return ok(out, formatFindDuplicateImagesDisplay(out), formatFindDuplicateImagesHtml(out), null);
        }
        case "findSimilarImages": {
            String out = findSimilarImages(context, params);
            return ok(out, formatFindSimilarImagesDisplay(out), formatFindSimilarImagesHtml(out), null);
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
        List<GalleryImage> images = queryGalleryImages(context, params, limit);
        JSONArray arr = new JSONArray();
        for (GalleryImage image : images) {
            arr.put(image.toJson(false));
        }
        JSONObject out = new JSONObject();
        out.put("album", normalizedAlbum(params));
        out.put("source", "MediaStore.Images");
        out.put("count", arr.length());
        out.put("limit", limit);
        out.put("images", arr);
        return out.toString();
    }

    private static String findDuplicateImages(Context context, JSONObject params) throws Exception {
        int limit = clampInt(params.optInt("limit", 5000), 1, 20000);
        long minBytes = Math.max(0L, params.optLong("minBytes", 1L));
        List<GalleryImage> images = queryGalleryImages(context, params, limit);
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
        out.put("album", normalizedAlbum(params));
        out.put("source", "MediaStore.Images");
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
        int limit = clampInt(params.optInt("limit", 1000), 1, 5000);
        int maxDistance = clampInt(params.optInt("maxDistance", 8), 0, 16);
        int minEdge = clampInt(params.optInt("minEdge", 32), 1, 2048);
        int maxGroups = clampInt(params.optInt("maxGroups", 50), 1, 500);
        double ratioTolerance = clampDouble(params.optDouble("ratioTolerance", 0.08), 0.0, 0.5);

        List<GalleryImage> images = queryGalleryImages(context, params, limit);
        List<SimilarImageInfo> infos = new ArrayList<>();
        int unreadable = 0;
        int skippedSmall = 0;
        for (GalleryImage image : images) {
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
        for (int i = 0; i < n; i++) {
            SimilarImageInfo a = infos.get(i);
            for (int j = i + 1; j < n; j++) {
                SimilarImageInfo b = infos.get(j);
                if (!sameAspectRatio(a, b, ratioTolerance)) {
                    continue;
                }
                comparedPairs++;
                int distance = hammingDistance(a.dhash, b.dhash);
                if (distance <= maxDistance) {
                    union(parent, i, j);
                    matchedPairs++;
                    if (isLikelyThumbnail(a, b, ratioTolerance)) {
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
                candidateJson.put("similarityPercentToKeep", similarityPercent(distance));
                candidateJson.put("dimensionRatioToKeep", ratio(candidate.pixelCount(), keep.pixelCount()));
                candidateJson.put("sizeRatioToKeep", ratio(candidate.image.sizeBytes, keep.image.sizeBytes));
                candidates.put(candidateJson);
            }
            if (candidates.length() == 0) {
                continue;
            }
            JSONObject group = new JSONObject();
            group.put("algorithm", "dHash64");
            group.put("matchType", hasThumbnailCandidate ? "thumbnailCandidate" : "visualSimilar");
            group.put("safeToDeleteAutomatically", false);
            group.put("count", cluster.size());
            group.put("keepSuggestion", similarImageJson(keep, false));
            group.put("candidates", candidates);
            group.put("minHashDistanceToKeep", minDistance == 64 ? 0 : minDistance);
            group.put("maxHashDistanceToKeep", maxGroupDistance);
            group.put("threshold", maxDistance);
            group.put("reason", hasThumbnailCandidate
                    ? "Smaller image(s) share a close perceptual hash and aspect ratio with a larger image."
                    : "Images share a close perceptual hash and aspect ratio; review before deleting.");
            groups.put(group);
            emitted++;
            candidateImages += candidates.length();
            if (hasThumbnailCandidate) {
                thumbnailGroups++;
            }
        }

        JSONObject out = new JSONObject();
        out.put("album", normalizedAlbum(params));
        out.put("source", "MediaStore.Images");
        out.put("matchMode", "visual");
        out.put("algorithm", "dHash64");
        out.put("threshold", maxDistance);
        out.put("ratioTolerance", ratioTolerance);
        out.put("minEdge", minEdge);
        out.put("scannedImages", images.size());
        out.put("fingerprintedImages", infos.size());
        out.put("unreadableImages", unreadable);
        out.put("skippedSmallImages", skippedSmall);
        out.put("comparedPairs", comparedPairs);
        out.put("matchedPairs", matchedPairs);
        out.put("thumbnailMatchedPairs", thumbnailPairs);
        out.put("candidateGroups", groups);
        out.put("candidateGroupCount", groups.length());
        out.put("candidateImageCount", candidateImages);
        out.put("thumbnailCandidateGroupCount", thumbnailGroups);
        out.put("note", "Visual matches use 64-bit dHash after down-sampling and aspect-ratio filtering. Treat results as review candidates, not automatic delete targets.");
        return out.toString();
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
            tiny = decodeImageForHash(context, image, 9, 8);
            long dhash = computeDHash64(tiny);
            return new SimilarImageInfo(image, dhash);
        } finally {
            recycleQuietly(tiny);
        }
    }

    private static Bitmap decodeImageForHash(Context context, GalleryImage image, int targetWidth, int targetHeight) throws Exception {
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
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            opts.inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, 512);
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

    private static long computeDHash64(Bitmap bitmap) {
        long hash = 0L;
        int bit = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int left = luminance(bitmap.getPixel(x, y));
                int right = luminance(bitmap.getPixel(x + 1, y));
                if (left > right) {
                    hash |= (1L << bit);
                }
                bit++;
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

    private static boolean sameAspectRatio(SimilarImageInfo a, SimilarImageInfo b, double tolerance) {
        if (a.width <= 0 || a.height <= 0 || b.width <= 0 || b.height <= 0) {
            return false;
        }
        double ar = a.width / (double) a.height;
        double br = b.width / (double) b.height;
        double denom = Math.max(ar, br);
        return denom <= 0 || Math.abs(ar - br) / denom <= tolerance;
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

    private static JSONObject similarImageJson(SimilarImageInfo info, boolean includeDeleteCandidate) throws Exception {
        JSONObject o = info.image.toJson(includeDeleteCandidate);
        o.put("dhash", hash64Hex(info.dhash));
        o.put("pixelCount", info.pixelCount());
        return o;
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

    private static long imageQualityScore(GalleryImage image) {
        long pixels = (long) Math.max(0, image.width) * Math.max(0, image.height);
        long bytes = Math.max(0L, image.sizeBytes);
        long date = Math.max(image.dateModified, image.dateAdded);
        return pixels * 1024L + Math.min(bytes, 1023L) + Math.max(0L, date / 1000000L);
    }

    private static long bestImageScore(List<SimilarImageInfo> images) {
        long best = 0L;
        for (SimilarImageInfo info : images) {
            best = Math.max(best, imageQualityScore(info.image));
        }
        return best;
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
        final int width;
        final int height;

        SimilarImageInfo(GalleryImage image, long dhash) {
            this.image = image;
            this.dhash = dhash;
            this.width = image.width;
            this.height = image.height;
        }

        long pixelCount() {
            return (long) Math.max(0, width) * Math.max(0, height);
        }
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
        String title = zh ? "\u76f8\u518c\u56fe\u7247\u5df2\u8bfb\u53d6" : "Gallery images loaded";
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] { zh ? "\u76f8\u518c" : "Album", mdCell(o.optString("album")) });
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

    private static String formatFindDuplicateImagesDisplay(String outputJson) throws Exception {
        boolean zh = isZh();
        JSONObject o = new JSONObject(outputJson);
        int groupCount = o.optInt("duplicateGroupCount");
        String title = groupCount > 0
                ? (zh ? "\u627e\u5230\u91cd\u590d\u56fe\u7247" : "Duplicate images found")
                : (zh ? "\u672a\u53d1\u73b0\u91cd\u590d\u56fe\u7247" : "No duplicate images found");
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] { zh ? "\u76f8\u518c" : "Album", mdCell(o.optString("album")) });
        rows.add(new String[] { zh ? "\u626b\u63cf\u56fe\u7247" : "Scanned images", String.valueOf(o.optInt("scannedCount")) });
        rows.add(new String[] { zh ? "\u91cd\u590d\u7ec4" : "Duplicate groups", String.valueOf(groupCount) });
        rows.add(new String[] { zh ? "\u91cd\u590d\u56fe\u7247" : "Duplicate images", String.valueOf(o.optInt("duplicateImageCount")) });
        rows.add(new String[] { zh ? "\u53ef\u91ca\u653e\u7a7a\u95f4" : "Reclaimable", o.optString("reclaimableFormatted") });
        rows.add(new String[] { zh ? "\u5df2\u8df3\u8fc7\u8fc7\u5c0f\u6587\u4ef6" : "Skipped small files", String.valueOf(o.optInt("skippedSmallFiles")) });
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
        String title = groupCount > 0
                ? (zh ? "\u627e\u5230\u76f8\u4f3c\u56fe\u7247\u5019\u9009" : "Similar image candidates found")
                : (zh ? "\u672a\u53d1\u73b0\u76f8\u4f3c\u56fe\u7247\u5019\u9009" : "No similar image candidates found");
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] { zh ? "\u76f8\u518c" : "Album", mdCell(o.optString("album")) });
        rows.add(new String[] { zh ? "\u626b\u63cf\u56fe\u7247" : "Scanned images", String.valueOf(o.optInt("scannedImages")) });
        rows.add(new String[] { zh ? "\u5df2\u751f\u6210\u6307\u7eb9" : "Fingerprinted", String.valueOf(o.optInt("fingerprintedImages")) });
        rows.add(new String[] { zh ? "\u76f8\u4f3c\u7ec4" : "Candidate groups", String.valueOf(groupCount) });
        rows.add(new String[] { zh ? "\u5019\u9009\u56fe\u7247" : "Candidate images", String.valueOf(o.optInt("candidateImageCount")) });
        rows.add(new String[] { zh ? "\u7f29\u7565\u56fe\u7ec4" : "Thumbnail groups", String.valueOf(o.optInt("thumbnailCandidateGroupCount")) });
        rows.add(new String[] { zh ? "\u7b97\u6cd5" : "Algorithm", mdCell(o.optString("algorithm")) });
        rows.add(new String[] { zh ? "\u9608\u503c" : "Threshold", String.valueOf(o.optInt("threshold")) });
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
                    + (zh ? "\u5f20\u9700\u4eba\u5de5\u786e\u8ba4" : "need review")
                    + (TextUtils.isEmpty(keepName) ? "" : " | keep " + keepName)
                    + " | " + group.optString("matchType");
            rows.add(new String[] { (zh ? "\u76f8\u4f3c\u7ec4 " : "Group ") + (i + 1), mdCell(value) });
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
        pairs.add(new String[] { zh ? "\u76f8\u518c" : "Album", o.optString("album") });
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

    private static String formatFindDuplicateImagesHtml(String outputJson) throws Exception {
        boolean zh = isZh();
        JSONObject o = new JSONObject(outputJson);
        int groupCount = o.optInt("duplicateGroupCount");
        String title = groupCount > 0
                ? (zh ? "\u627e\u5230\u91cd\u590d\u56fe\u7247" : "Duplicate images found")
                : (zh ? "\u672a\u53d1\u73b0\u91cd\u590d\u56fe\u7247" : "No duplicate images found");
        List<String[]> pairs = new ArrayList<>();
        pairs.add(new String[] { zh ? "\u76f8\u518c" : "Album", o.optString("album") });
        pairs.add(new String[] { zh ? "\u626b\u63cf\u56fe\u7247" : "Scanned images", String.valueOf(o.optInt("scannedCount")) });
        pairs.add(new String[] { zh ? "\u91cd\u590d\u7ec4" : "Duplicate groups", String.valueOf(groupCount) });
        pairs.add(new String[] { zh ? "\u91cd\u590d\u56fe\u7247" : "Duplicate images", String.valueOf(o.optInt("duplicateImageCount")) });
        pairs.add(new String[] { zh ? "\u53ef\u91ca\u653e\u7a7a\u95f4" : "Reclaimable", o.optString("reclaimableFormatted") });
        pairs.add(new String[] { zh ? "\u5df2\u8df3\u8fc7\u8fc7\u5c0f\u6587\u4ef6" : "Skipped small files", String.valueOf(o.optInt("skippedSmallFiles")) });
        String body = groupCount > 0 ? HtmlOutputHelper.successBadge() : "";
        body += HtmlOutputHelper.keyValue(pairs.toArray(new String[0][]));
        return HtmlOutputHelper.card("DUP", title, body);
    }

    private static String formatFindSimilarImagesHtml(String outputJson) throws Exception {
        boolean zh = isZh();
        JSONObject o = new JSONObject(outputJson);
        int groupCount = o.optInt("candidateGroupCount");
        String title = groupCount > 0
                ? (zh ? "\u627e\u5230\u76f8\u4f3c\u56fe\u7247\u5019\u9009" : "Similar image candidates found")
                : (zh ? "\u672a\u53d1\u73b0\u76f8\u4f3c\u56fe\u7247\u5019\u9009" : "No similar image candidates found");
        List<String[]> pairs = new ArrayList<>();
        pairs.add(new String[] { zh ? "\u76f8\u518c" : "Album", o.optString("album") });
        pairs.add(new String[] { zh ? "\u626b\u63cf\u56fe\u7247" : "Scanned images", String.valueOf(o.optInt("scannedImages")) });
        pairs.add(new String[] { zh ? "\u5df2\u751f\u6210\u6307\u7eb9" : "Fingerprinted", String.valueOf(o.optInt("fingerprintedImages")) });
        pairs.add(new String[] { zh ? "\u76f8\u4f3c\u7ec4" : "Candidate groups", String.valueOf(groupCount) });
        pairs.add(new String[] { zh ? "\u5019\u9009\u56fe\u7247" : "Candidate images", String.valueOf(o.optInt("candidateImageCount")) });
        pairs.add(new String[] { zh ? "\u7f29\u7565\u56fe\u7ec4" : "Thumbnail groups", String.valueOf(o.optInt("thumbnailCandidateGroupCount")) });
        pairs.add(new String[] { zh ? "\u7b97\u6cd5" : "Algorithm", o.optString("algorithm") });
        String body = groupCount > 0 ? HtmlOutputHelper.successBadge() : "";
        body += HtmlOutputHelper.keyValue(pairs.toArray(new String[0][]));
        return HtmlOutputHelper.card("SIM", title, body);
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
    private static String ok(String output, String displayText, String displayHtml, JSONArray richContent) throws Exception {
        JSONObject r = new JSONObject().put("success", true).put("output", output);
        if (displayText != null && !displayText.isEmpty()) {
            r.put("_displayText", displayText);
        }
        if (displayHtml != null && !displayHtml.isEmpty()) {
            r.put("_displayHtml", displayHtml);
        }
        if (richContent != null && richContent.length() > 0) {
            r.put("_richContent", richContent);
        }
        return r.toString();
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
