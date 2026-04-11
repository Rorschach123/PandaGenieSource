package ai.rorsch.moduleplugins.qrcode;

import ai.rorsch.moduleplugins.qrcode.qr.DataTooLongException;
import ai.rorsch.moduleplugins.qrcode.qr.QrCode;
import ai.rorsch.pandagenie.module.runtime.ModulePlugin;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
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

/**
 * PandaGenie 二维码工具模块插件。
 * <p>
 * <b>模块用途：</b>使用 Nayuki QR 编码器生成可扫描的真实 PNG 二维码；对本地图片做可读性检查并返回尺寸等元数据（前端 H5 可用 jsQR 完成实际解码）；
 * 列举已保存到外部存储目录下的历史二维码文件。
 * </p>
 * <p>
 * <b>对外 API：</b>通过 {@link #invoke(Context, String, String)} 的 {@code action} 字符串分发：
 * {@code generateQR}（生成并保存 PNG，可选 Base64）、{@code decodeQR}（校验路径并返回图片信息）、{@code listGenerated}（列出输出目录中的 PNG）。
 * 参数与返回值均为 JSON 字符串，外层成功响应格式由 {@link #ok} / {@link #error} 约定。
 * </p>
 * <p>
 * 本类实现 {@link ModulePlugin}，由宿主 {@code ModuleRuntime} 通过反射加载并调用，无需在编译期直接依赖具体插件类名以外的契约。
 * </p>
 */
public class QrcodePlugin implements ModulePlugin {

    /** 生成位图边长的最小允许像素值（过小无法可靠扫描）。 */
    private static final int SIZE_MIN = 64;
    /** 生成位图边长的最大允许像素值（防止内存过大）。 */
    private static final int SIZE_MAX = 2048;
    /** 未指定 {@code size} 时使用的默认边长（像素）。 */
    private static final int SIZE_DEFAULT = 512;
    /** 当边长不超过此值时，默认附带 PNG 的 Base64，避免大图撑爆 JSON。 */
    private static final int BASE64_MAX_SIDE = 512;

    private static boolean isZh() {
        try {
            return Locale.getDefault().getLanguage().toLowerCase(Locale.ROOT).startsWith("zh");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 模块统一入口：根据 {@code action} 执行对应二维码能力，并返回 JSON 字符串。
     *
     * @param context    Android 上下文（本实现主要使用外部存储，部分场景可忽略）
     * @param action     动作名：{@code generateQR}、{@code decodeQR}、{@code listGenerated}
     * @param paramsJson 动作参数的 JSON 对象字符串；空或非法时按 {@code {}} 解析
     * @return 成功时为 {@code success=true} 且含 {@code output}、可选 {@code _displayText}；失败为 {@code success=false} 与 {@code error}
     * @throws Exception 理论上由内部捕获并转为错误 JSON；接口声明保留与 {@link ModulePlugin} 一致
     */
    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        try {
            // 将空参数规范化为空对象，避免 new JSONObject 抛异常
            JSONObject params = new JSONObject(emptyJson(paramsJson));
            switch (action) {
                case "generateQR": {
                    String out = generateQR(params);
                    return ok(out, formatGenerateDisplay(out));
                }
                case "decodeQR": {
                    String out = decodeQR(params);
                    return ok(out, formatDecodeDisplay(out));
                }
                case "listGenerated": {
                    String out = listGenerated();
                    return ok(out, formatListDisplay(out));
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
     * 根据文本生成 QR 码 PNG 文件，并返回路径、版本信息及可选 Base64。
     *
     * @param params JSON：{@code text} 必填；{@code size} 边长像素；{@code includeBase64} 是否强制附带 Base64
     * @return 描述生成结果的 JSON 字符串（非外层 success 包装）
     * @throws Exception 参数非法、目录创建失败、编码过长或压缩失败时抛出
     */
    private static String generateQR(JSONObject params) throws Exception {
        String text = params.optString("text", "").trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("text is required");
        }
        int size = params.optInt("size", SIZE_DEFAULT);
        // 将请求尺寸钳制在合理区间，避免过小无法扫或过大 OOM
        if (size < SIZE_MIN) {
            size = SIZE_MIN;
        }
        if (size > SIZE_MAX) {
            size = SIZE_MAX;
        }
        boolean includeBase64 = params.optBoolean("includeBase64", false);

        QrCode qr;
        try {
            // 使用中等纠错级别，在容量与可识别性之间折中
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

        // 小图或显式要求时附带 Base64，便于前端直接内嵌展示
        if (includeBase64 || size <= BASE64_MAX_SIDE) {
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos);
                o.put("base64", Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP));
            }
        }

        recycleQuietly(bitmap);
        return o.toString();
    }

    /**
     * 将逻辑 QR 模块矩阵渲染为 ARGB 位图：含 4 模块静区，并按 {@code pixelSize} 缩放单元格。
     *
     * @param qr        Nayuki 编码结果
     * @param pixelSize 目标位图边长（像素）；实际边长可能因整除单元格而略小于请求值
     * @return 未回收的 {@link Bitmap}，调用方负责 {@link #recycleQuietly}
     */
    private static Bitmap renderQrBitmap(QrCode qr, int pixelSize) {
        final int border = 4; // 标准静区：至少 4 个模块宽的白边
        int modCount = qr.size + 2 * border;
        int cell = Math.max(1, pixelSize / modCount); // 每模块像素边长，至少 1
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
                // 静区内始终为浅色模块
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

    /**
     * 校验本地图片路径可读，并返回宽高、MIME 等；解码正文由前端扫描页完成（{@code hint} 提示 UI）。
     *
     * @param params JSON：{@code imagePath} 本地文件绝对路径，必填
     * @return 含 {@code imagePath}、{@code width}、{@code height}、{@code decoded=null}、{@code hint} 等的 JSON 字符串
     * @throws Exception 路径为空、非文件或无法解析边界时抛出
     */
    private static String decodeQR(JSONObject params) throws Exception {
        String path = params.optString("imagePath", "").trim();
        if (TextUtils.isEmpty(path)) {
            throw new IllegalArgumentException("imagePath is required");
        }
        File f = new File(path);
        if (!f.isFile() || !f.canRead()) {
            throw new IllegalArgumentException("file not readable: " + path);
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true; // 只读头信息，不解码整图以省内存
        BitmapFactory.decodeFile(path, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IllegalArgumentException("cannot read image bounds");
        }
        JSONObject o = new JSONObject();
        o.put("imagePath", path);
        o.put("width", bounds.outWidth);
        o.put("height", bounds.outHeight);
        o.put("fileSizeBytes", f.length());
        o.put("mimeType", bounds.outMimeType != null ? bounds.outMimeType : JSONObject.NULL);
        o.put("decoded", JSONObject.NULL);
        o.put("hint", "open_scan_tab"); // 告知前端打开模块内「扫描」页做 jsQR 解码
        return o.toString();
    }

    /**
     * 列出 {@code 外部存储/PandaGenie/qrcode} 下所有 {@code .png} 文件，按修改时间新到旧排序。
     *
     * @return JSON：{@code directory}、{@code files} 数组（name/path/sizeBytes/modified）、{@code count}
     * @throws Exception JSON 构建异常
     */
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
        // 最新生成的文件排在列表前面，便于用户查找
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

    /**
     * 将 {@code generateQR} 的 JSON 输出格式化为面向用户的简短展示文案（写入 {@code _displayText}）。
     *
     * @param outputJson {@code generateQR} 返回的 JSON 字符串
     * @return 多行可读文本；解析失败时可能抛异常由上层处理
     */
    private static String formatGenerateDisplay(String outputJson) throws Exception {
        JSONObject o = new JSONObject(outputJson);
        String text = o.optString("text", "");
        String preview = text.length() > 48 ? text.substring(0, 48) + "…" : text;
        if (isZh()) {
            return "\uD83D\uDCF1 二维码已生成\n"
                    + "━━━━━━━━━━━━━━\n"
                    + "\u25B8 内容: " + preview + "\n"
                    + "\u25B8 图片: " + o.optInt("imageSize") + " px\n"
                    + "\u25B8 QR 版本: " + o.optInt("qrVersion") + "\n"
                    + "\u25B8 已保存: " + o.optString("path");
        }
        return "\uD83D\uDCF1 QR Code generated\n"
                + "━━━━━━━━━━━━━━\n"
                + "\u25B8 Content: " + preview + "\n"
                + "\u25B8 Image: " + o.optInt("imageSize") + " px\n"
                + "\u25B8 QR version: " + o.optInt("qrVersion") + "\n"
                + "\u25B8 Saved: " + o.optString("path");
    }

    /**
     * 将 {@code decodeQR} 的 JSON 输出格式化为提示用户打开扫描页的展示文案。
     *
     * @param outputJson {@code decodeQR} 返回的 JSON 字符串
     * @return 多行可读文本
     */
    private static String formatDecodeDisplay(String outputJson) throws Exception {
        JSONObject o = new JSONObject(outputJson);
        int w = o.optInt("width");
        int h = o.optInt("height");
        if (isZh()) {
            return "\uD83D\uDCF7 图片已准备好扫描\n"
                    + "━━━━━━━━━━━━━━\n"
                    + "\u25B8 大小: " + w + "\u00D7" + h + "\n"
                    + "\u25B8 路径: " + o.optString("imagePath") + "\n"
                    + "\u25B8 请在本模块打开扫描标签页以读取二维码内容。";
        }
        return "\uD83D\uDCF7 Image ready for scan\n"
                + "━━━━━━━━━━━━━━\n"
                + "\u25B8 Size: " + w + "\u00D7" + h + "\n"
                + "\u25B8 Path: " + o.optString("imagePath") + "\n"
                + "\u25B8 Open the Scan tab in this module to read the QR content.";
    }

    /**
     * 将 {@code listGenerated} 的 JSON 输出格式化为列表摘要展示文案。
     *
     * @param outputJson {@code listGenerated} 返回的 JSON 字符串
     * @return 多行可读文本
     */
    private static String formatListDisplay(String outputJson) throws Exception {
        JSONObject o = new JSONObject(outputJson);
        int n = o.optInt("count");
        if (isZh()) {
            return "\uD83D\uDCC2 二维码图库\n"
                    + "━━━━━━━━━━━━━━\n"
                    + "\u25B8 " + n + " 个 PNG 文件\n"
                    + "\u25B8 文件夹: " + o.optString("directory");
        }
        return "\uD83D\uDCC2 QR gallery\n"
                + "━━━━━━━━━━━━━━\n"
                + "\u25B8 " + n + " PNG file(s)\n"
                + "\u25B8 Folder: " + o.optString("directory");
    }

    /**
     * 安全回收位图，忽略已回收或 null。
     *
     * @param b 可能为 null 的位图
     */
    private static void recycleQuietly(Bitmap b) {
        if (b != null && !b.isRecycled()) {
            b.recycle();
        }
    }

    /**
     * 将空或仅空白参数字符串规范化为 JSON 空对象字面量 {@code "{}"}。
     *
     * @param v 调用方传入的 {@code paramsJson}
     * @return 非空则原样返回，否则 {@code "{}"}
     */
    private static String emptyJson(String v) {
        return v == null || v.trim().isEmpty() ? "{}" : v;
    }

    /**
     * 构造标准成功响应：业务结果放在 {@code output} 字符串中，可选 {@code _displayText} 供 UI 直接展示。
     *
     * @param output      业务层 JSON 或其它文本（字符串形式嵌入外层 JSON）
     * @param displayText 可为 null；非空时写入 {@code _displayText}
     * @return 完整响应 JSON 字符串
     */
    private static String ok(String output, String displayText) throws Exception {
        JSONObject r = new JSONObject().put("success", true).put("output", output);
        if (displayText != null && !displayText.isEmpty()) {
            r.put("_displayText", displayText);
        }
        return r.toString();
    }

    /**
     * 构造标准失败响应。
     *
     * @param msg 错误说明（建议为简短英文或本地化文案，由调用方决定）
     * @return {@code success=false} 且含 {@code error} 的 JSON 字符串
     */
    private static String error(String msg) throws Exception {
        return new JSONObject().put("success", false).put("error", msg).toString();
    }
}
