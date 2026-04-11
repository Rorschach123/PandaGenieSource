package ai.rorsch.moduleplugins.signature_checker;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Environment;
import android.util.Base64;
import ai.rorsch.pandagenie.module.runtime.ModulePlugin;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * PandaGenie 签名检查模块插件。
 * <p>
 * <b>模块用途：</b>校验宿主 APK 的签名信息（指纹、主题、颁发者）；校验外部存储 {@code PandaGenie/modules} 下 {@code .mod} 模块包
 * 的 JAR 条目签名是否与内置「官方证书」一致，并汇总开发者证书指纹等元数据（读取 {@code manifest.json}）。
 * </p>
 * <p>
 * <b>对外 API：</b>{@code verifyApk}（当前应用签名）、{@code verifyModule}（按 {@code moduleId} 查单个模块）、
 * {@code verifyAllModules}（目录内全部 {@code .mod}）、{@code verifyAll}（APK + 全部模块）。
 * 返回 JSON 字符串嵌套于标准 {@code success/output/_displayText} 响应中。
 * </p>
 * <p>
 * 实现 {@link ModulePlugin}，由 {@code ModuleRuntime} 反射加载；官方公钥 PEM 位于 assets 路径 {@code module_signing/official_cert.pem}。
 * </p>
 */
public class SignatureCheckerPlugin implements ModulePlugin {

    /** Assets 中内置的官方 X.509 证书（PEM），用于判断模块是否由官方链签署。 */
    private static final String APK_CERT_ASSET = "module_signing/official_cert.pem";
    /** 外部存储上存放已下载模块包的相对目录名（相对 {@link Environment#getExternalStorageDirectory()}）。 */
    private static final String MODULES_DIR = "PandaGenie/modules";

    private static boolean isZh() {
        try { return java.util.Locale.getDefault().getLanguage().toLowerCase(java.util.Locale.ROOT).startsWith("zh"); }
        catch (Exception e) { return false; }
    }
    private static String pgTable(String title, String[] headers, List<String[]> rows) {
        try {
            org.json.JSONObject t = new org.json.JSONObject();
            t.put("title", title);
            org.json.JSONArray h = new org.json.JSONArray();
            for (String hdr : headers) h.put(hdr);
            t.put("headers", h);
            org.json.JSONArray r = new org.json.JSONArray();
            for (String[] row : rows) { org.json.JSONArray a = new org.json.JSONArray(); for (String c : row) a.put(c); r.put(a); }
            t.put("rows", r);
            return "__pg_table__" + t.toString() + "__pg_table_end__";
        } catch (Exception e) { return title; }
    }

    /**
     * 按 {@code action} 执行 APK/模块签名相关校验。
     *
     * @param context    用于 {@link PackageManager}、assets 与外部存储路径
     * @param action     {@code verifyApk|verifyModule|verifyAllModules|verifyAll}
     * @param paramsJson {@code verifyModule} 时需 {@code moduleId}；其它动作可为 {@code {}}
     * @return 标准成功/失败 JSON
     */
    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        JSONObject params = new JSONObject(emptyJson(paramsJson));
        switch (action) {
            case "verifyApk": {
                JSONObject apk = verifyApkSignature(context);
                String out = apk.toString();
                return ok(out, formatVerifyApkDisplay(apk));
            }
            case "verifyModule": {
                JSONObject mod = verifySingleModule(context, params);
                String out = mod.toString();
                return ok(out, formatVerifyModuleDisplay(mod));
            }
            case "verifyAllModules": {
                JSONArray arr = verifyAllModules(context);
                String out = arr.toString();
                return ok(out, formatVerifyAllModulesDisplay(arr));
            }
            case "verifyAll": {
                JSONObject all = verifyAll(context);
                String out = all.toString();
                return ok(out, formatVerifyAllDisplay(all));
            }
            default:
                return error("Unsupported action: " + action);
        }
    }

    /**
     * 将空参数规范化为 {@code "{}"}。
     *
     * @param value {@code paramsJson}
     * @return 非空原样，否则空对象字面量
     */
    private String emptyJson(String value) {
        return value == null || value.trim().isEmpty() ? "{}" : value;
    }

    /**
     * 在模块目录中按 {@code moduleId} 匹配单个 {@code .mod} 文件并校验其签名与清单。
     * <p>匹配规则：manifest 的 {@code id}、文件名（忽略大小写）、或 {@code moduleId-} 前缀。</p>
     *
     * @param context 上下文
     * @param params  JSON：{@code moduleId} 必填
     * @return 单模块结果对象（含 {@code verified}、{@code error}、指纹等）
     */
    private JSONObject verifySingleModule(Context context, JSONObject params) throws Exception {
        String moduleId = params.optString("moduleId", "").trim();
        if (moduleId.isEmpty()) {
            JSONObject r = new JSONObject();
            r.put("verified", false);
            r.put("error", "缺少参数 moduleId");
            return r;
        }

        X509Certificate trustedCert = loadTrustedCert(context);
        File modulesDir = new File(Environment.getExternalStorageDirectory(), MODULES_DIR);
        if (!modulesDir.exists()) {
            JSONObject r = new JSONObject();
            r.put("verified", false);
            r.put("error", "模块目录不存在");
            return r;
        }

        File[] modFiles = modulesDir.listFiles(f -> f.getName().endsWith(".mod"));
        if (modFiles == null) {
            JSONObject r = new JSONObject();
            r.put("verified", false);
            r.put("error", "无法读取模块目录");
            return r;
        }

        for (File modFile : modFiles) {
            JSONObject manifest = readManifestFromMod(modFile);
            String id = manifest != null ? manifest.optString("id", "") : "";
            String fileName = modFile.getName();
            // 兼容：清单 id、完整文件名、或「id-版本」形式文件名
            boolean match = moduleId.equals(id)
                    || moduleId.equalsIgnoreCase(fileName)
                    || fileName.toLowerCase().startsWith(moduleId.toLowerCase() + "-");
            if (!match) continue;

            JSONObject modResult = new JSONObject();
            modResult.put("file", fileName);
            if (manifest != null) {
                modResult.put("id", manifest.optString("id", ""));
                modResult.put("name", manifest.optString("name", fileName));
                modResult.put("version", manifest.optString("version", ""));
                JSONObject devJson = manifest.optJSONObject("developer");
                if (devJson != null) {
                    modResult.put("developer", devJson);
                }
            }

            if (trustedCert == null) {
                modResult.put("verified", false);
                modResult.put("error", "官方证书未找到，无法校验");
                return modResult;
            }
            verifyModSignature(modFile, trustedCert, modResult);
            return modResult;
        }

        JSONObject r = new JSONObject();
        r.put("verified", false);
        r.put("error", "未找到模块: " + moduleId);
        return r;
    }

    /**
     * 组合校验：当前 APK 签名 + 模块目录内全部模块。
     *
     * @param context 上下文
     * @return JSON：{@code apk} 对象与 {@code modules} 数组
     */
    private JSONObject verifyAll(Context context) throws Exception {
        JSONObject result = new JSONObject();
        result.put("apk", verifyApkSignature(context));
        result.put("modules", verifyAllModules(context));
        return result;
    }

    /**
     * 读取当前包第一个 {@link Signature}，解析为 {@link X509Certificate} 并输出 SHA-256 指纹等字段。
     *
     * @param context 上下文
     * @return {@code verified=true} 时含 {@code fingerprint}、{@code subject}、{@code issuer}；失败时 {@code verified=false} 与 {@code error}
     */
    private JSONObject verifyApkSignature(Context context) throws Exception {
        JSONObject apkResult = new JSONObject();
        try {
            // GET_SIGNATURES：兼容旧 API；取 signatures[0] 作为应用签名证书
            PackageInfo pkgInfo = context.getPackageManager().getPackageInfo(
                    context.getPackageName(), PackageManager.GET_SIGNATURES);

            if (pkgInfo.signatures == null || pkgInfo.signatures.length == 0) {
                apkResult.put("verified", false);
                apkResult.put("error", "APK未签名");
                return apkResult;
            }

            Signature apkSignature = pkgInfo.signatures[0];
            byte[] apkCertBytes = apkSignature.toByteArray();

            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate apkCert = (X509Certificate) cf.generateCertificate(
                    new ByteArrayInputStream(apkCertBytes));

            String apkFingerprint = sha256Fingerprint(apkCert.getEncoded());

            apkResult.put("verified", true);
            apkResult.put("packageName", context.getPackageName());
            apkResult.put("fingerprint", apkFingerprint);
            apkResult.put("subject", apkCert.getSubjectDN().getName());
            apkResult.put("issuer", apkCert.getIssuerDN().getName());

        } catch (Exception e) {
            apkResult.put("verified", false);
            apkResult.put("error", "APK签名读取失败: " + e.getMessage());
        }
        return apkResult;
    }

    /**
     * 遍历模块目录下每个 {@code .mod}，读取清单并校验 JAR 签名。
     *
     * @param context 上下文
     * @return 每个文件对应一个 JSON 对象的数组（顺序为目录枚举顺序）
     */
    private JSONArray verifyAllModules(Context context) throws Exception {
        JSONArray results = new JSONArray();

        X509Certificate trustedCert = loadTrustedCert(context);

        File modulesDir = new File(Environment.getExternalStorageDirectory(), MODULES_DIR);
        if (!modulesDir.exists() || !modulesDir.isDirectory()) {
            return results;
        }

        File[] modFiles = modulesDir.listFiles(f -> f.getName().endsWith(".mod"));
        if (modFiles == null) return results;

        for (File modFile : modFiles) {
            JSONObject modResult = new JSONObject();
            String fileName = modFile.getName();
            modResult.put("file", fileName);

            try {
                JSONObject manifest = readManifestFromMod(modFile);
                if (manifest != null) {
                    modResult.put("id", manifest.optString("id", ""));
                    modResult.put("name", manifest.optString("name", fileName));
                    modResult.put("version", manifest.optString("version", ""));
                    JSONObject devJson = manifest.optJSONObject("developer");
                    if (devJson != null) {
                        modResult.put("developer", devJson);
                    }
                } else {
                    modResult.put("id", fileName);
                    modResult.put("name", fileName);
                }

                if (trustedCert == null) {
                    modResult.put("verified", false);
                    modResult.put("error", "官方证书未找到，无法校验");
                    results.put(modResult);
                    continue;
                }

                verifyModSignature(modFile, trustedCert, modResult);

            } catch (Exception e) {
                modResult.put("verified", false);
                modResult.put("error", "校验异常: " + e.getMessage());
            }
            results.put(modResult);
        }

        return results;
    }

    /**
     * 以 {@link JarFile#JarFile(File, boolean)} 开启校验模式，逐条读取非 META-INF 条目以触发签名验证；
     * 收集每条目证书指纹，判断是否与 {@code trustedCert} 匹配（{@code isOfficial}），并记录开发者证书指纹。
     *
     * @param modFile    模块包文件
     * @param trustedCert 官方可信证书；可为 null（上层已处理），此处仍防御性判断
     * @param modResult  输出写入此 JSON（会被修改）
     */
    private void verifyModSignature(File modFile, X509Certificate trustedCert, JSONObject modResult) throws Exception {
        JarFile jarFile = null;
        try {
            // true：读取条目时校验 manifest 中记录的摘要与签名
            jarFile = new JarFile(modFile, true);
            Enumeration<JarEntry> entries = jarFile.entries();
            byte[] buffer = new byte[8192];
            int verifiedEntries = 0;
            java.util.Map<String, X509Certificate> certMap = new java.util.LinkedHashMap<>();

            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                if (entry.getName().startsWith("META-INF/")) continue;

                InputStream is = jarFile.getInputStream(entry);
                // 必须读完流，JAR 校验才会在该条目上完成摘要验证
                while (is.read(buffer) != -1) { }
                is.close();

                Certificate[] certs = entry.getCertificates();
                if (certs == null || certs.length == 0) {
                    modResult.put("verified", false);
                    modResult.put("error", "包含未签名内容: " + entry.getName());
                    return;
                }

                for (Certificate cert : certs) {
                    if (cert instanceof X509Certificate) {
                        X509Certificate x509 = (X509Certificate) cert;
                        String fp = sha256Fingerprint(x509.getEncoded());
                        if (!certMap.containsKey(fp)) {
                            certMap.put(fp, x509);
                        }
                    }
                }
                verifiedEntries++;
            }

            if (verifiedEntries == 0) {
                modResult.put("verified", false);
                modResult.put("error", "模块包中没有可验证的签名内容");
                return;
            }

            if (certMap.isEmpty()) {
                modResult.put("verified", false);
                modResult.put("error", "未提取到签名证书");
                return;
            }

            modResult.put("verified", true);
            modResult.put("signerCount", certMap.size());

            String officialFp = trustedCert != null ? sha256Fingerprint(trustedCert.getEncoded()) : "";
            boolean hasOfficial = !officialFp.isEmpty() && certMap.containsKey(officialFp);
            modResult.put("isOfficial", hasOfficial);

            if (hasOfficial) {
                X509Certificate oCert = certMap.get(officialFp);
                modResult.put("officialFingerprint", officialFp);
                modResult.put("officialSubject", oCert.getSubjectDN() != null ? oCert.getSubjectDN().getName() : "");
            }

            String devFp = "";
            String devSubject = "";
            for (java.util.Map.Entry<String, X509Certificate> e : certMap.entrySet()) {
                if (!e.getKey().equals(officialFp)) {
                    devFp = e.getKey();
                    devSubject = e.getValue().getSubjectDN() != null ? e.getValue().getSubjectDN().getName() : "";
                    break;
                }
            }
            if (devFp.isEmpty() && !certMap.isEmpty()) {
                java.util.Map.Entry<String, X509Certificate> first = certMap.entrySet().iterator().next();
                devFp = first.getKey();
                devSubject = first.getValue().getSubjectDN() != null ? first.getValue().getSubjectDN().getName() : "";
            }
            modResult.put("devFingerprint", devFp);
            modResult.put("devSubject", devSubject);

        } catch (SecurityException e) {
            modResult.put("verified", false);
            modResult.put("error", "文件可能被篡改: " + e.getMessage());
        } finally {
            if (jarFile != null) {
                try { jarFile.close(); } catch (Exception ignored) { }
            }
        }
    }

    /**
     * 从 assets 加载 PEM，去掉头尾行后 Base64 解码并生成 {@link X509Certificate}。
     *
     * @param context 上下文
     * @return 成功返回证书；任一步失败返回 null
     */
    private X509Certificate loadTrustedCert(Context context) {
        try {
            InputStream is = context.getAssets().open(APK_CERT_ASSET);
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("-----")) {
                    sb.append(line.trim());
                }
            }
            reader.close();
            is.close();

            byte[] certBytes = Base64.decode(sb.toString(), Base64.DEFAULT);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(certBytes));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从模块 JAR/ZIP 中读取 {@code manifest.json} 并解析为 {@link JSONObject}。
     *
     * @param modFile 模块文件
     * @return 解析成功返回对象；无条目或解析失败返回 null
     */
    private JSONObject readManifestFromMod(File modFile) {
        JarFile jarFile = null;
        try {
            jarFile = new JarFile(modFile, false);
            JarEntry entry = jarFile.getJarEntry("manifest.json");
            if (entry == null) return null;

            InputStream is = jarFile.getInputStream(entry);
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            is.close();

            String text = sb.toString();
            if (text.startsWith("\uFEFF")) text = text.substring(1); // 去 BOM
            return new JSONObject(text);
        } catch (Exception e) {
            return null;
        } finally {
            if (jarFile != null) {
                try { jarFile.close(); } catch (Exception ignored) { }
            }
        }
    }

    /**
     * 计算证书 DER 编码字节的 SHA-256，格式为十六进制大写、冒号分隔（类似 keytool）。
     *
     * @param data 原始字节（通常为 {@code X509Certificate#getEncoded()}）
     * @return 指纹字符串
     */
    private String sha256Fingerprint(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(data);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < digest.length; i++) {
            if (i > 0) sb.append(':');
            sb.append(String.format("%02X", digest[i]));
        }
        return sb.toString();
    }

    /**
     * 格式化 APK 校验结果供 {@code _displayText} 展示。
     *
     * @param apk {@link #verifyApkSignature} 的返回对象
     * @return 简短多行文本
     */
    private static String mdCell(String s) {
        if (s == null) return "";
        return s.replace("\r", "").replace("\n", " ").replace("|", "\\|");
    }

    private String formatVerifyApkDisplay(JSONObject apk) {
        boolean zh = isZh();
        boolean v = apk.optBoolean("verified", false);
        String status = v
                ? (zh ? "✅ 有效" : "✅ Valid")
                : (zh ? "❌ 无效" : "❌ Invalid");
        String title = zh ? "APK 签名" : "APK Signature";
        String[] headers = new String[]{zh ? "项目" : "Item", zh ? "值" : "Value"};
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{zh ? "状态" : "Status", mdCell(status)});
        if (v) {
            rows.add(new String[]{zh ? "包名" : "Package", mdCell(apk.optString("packageName", ""))});
            rows.add(new String[]{zh ? "指纹" : "Fingerprint", mdCell(apk.optString("fingerprint", ""))});
            rows.add(new String[]{zh ? "主体" : "Subject", mdCell(apk.optString("subject", ""))});
            rows.add(new String[]{zh ? "签发者" : "Issuer", mdCell(apk.optString("issuer", ""))});
        } else {
            rows.add(new String[]{zh ? "错误" : "Error", mdCell(apk.optString("error", ""))});
        }
        return ("🔐 " + title + "\n\n" + pgTable(title, headers, rows)).trim();
    }

    /**
     * 格式化单模块校验摘要。
     *
     * @param mod {@link #verifySingleModule} 返回对象
     */
    private String formatVerifyModuleDisplay(JSONObject mod) {
        boolean zh = isZh();
        String moduleName = mod.optString("name", "");
        if (moduleName.isEmpty()) moduleName = mod.optString("id", mod.optString("file", ""));
        boolean v = mod.optBoolean("verified", false);
        String status = v
                ? (zh ? "✅ 有效" : "✅ Valid")
                : (zh ? "❌ 无效" : "❌ Invalid");
        String title = zh ? "模块签名" : "Module Signature";
        String[] headers = new String[]{zh ? "项目" : "Item", zh ? "值" : "Value"};
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{zh ? "模块" : "Module", mdCell(moduleName)});
        rows.add(new String[]{zh ? "文件" : "File", mdCell(mod.optString("file", ""))});
        rows.add(new String[]{"ID", mdCell(mod.optString("id", ""))});
        rows.add(new String[]{zh ? "版本" : "Version", mdCell(mod.optString("version", ""))});
        rows.add(new String[]{zh ? "状态" : "Status", mdCell(status)});
        if (v) {
            rows.add(new String[]{zh ? "官方" : "Official", mdCell(String.valueOf(mod.optBoolean("isOfficial", false)))});
            rows.add(new String[]{zh ? "签名者数量" : "Signer count", String.valueOf(mod.optInt("signerCount", 0))});
            rows.add(new String[]{zh ? "官方指纹" : "Official fingerprint", mdCell(mod.optString("officialFingerprint", ""))});
            rows.add(new String[]{zh ? "官方主体" : "Official subject", mdCell(mod.optString("officialSubject", ""))});
            rows.add(new String[]{zh ? "开发者指纹" : "Dev fingerprint", mdCell(mod.optString("devFingerprint", ""))});
            rows.add(new String[]{zh ? "开发者主体" : "Dev subject", mdCell(mod.optString("devSubject", ""))});
        } else {
            rows.add(new String[]{zh ? "错误" : "Error", mdCell(mod.optString("error", ""))});
        }
        return ("🔐 " + title + "\n\n" + pgTable(title, headers, rows)).trim();
    }

    /**
     * 统计模块数组中通过/失败数量并生成一行摘要。
     *
     * @param results {@link #verifyAllModules} 的返回值
     */
    private static void appendModulesSummaryAndTable(StringBuilder sb, JSONArray results) {
        boolean zh = isZh();
        int valid = 0;
        int invalid = 0;
        for (int i = 0; i < results.length(); i++) {
            JSONObject o = results.optJSONObject(i);
            if (o != null && o.optBoolean("verified", false)) valid++;
            else invalid++;
        }
        sb.append("✅ ").append(valid).append(zh ? " 有效 / ❌ " : " valid / ❌ ").append(invalid).append(zh ? " 无效\n\n" : " invalid\n\n");
        String title = zh ? "模块列表" : "Module list";
        String[] headers = new String[]{
                zh ? "模块" : "Module", zh ? "文件" : "File", zh ? "状态" : "Status", zh ? "详情" : "Details"};
        List<String[]> rows = new ArrayList<>();
        for (int i = 0; i < results.length(); i++) {
            JSONObject o = results.optJSONObject(i);
            if (o == null) continue;
            String name = o.optString("name", "");
            if (name.isEmpty()) name = o.optString("id", o.optString("file", "?"));
            boolean v = o.optBoolean("verified", false);
            String st = v
                    ? (zh ? "✅ 有效" : "✅ Valid")
                    : (zh ? "❌ 无效" : "❌ Invalid");
            String details;
            if (v) {
                details = o.optBoolean("isOfficial", false)
                        ? (zh ? "官方" : "Official")
                        : (zh ? "已验证" : "Verified");
            } else {
                details = o.optString("error", "—");
            }
            rows.add(new String[]{mdCell(name), mdCell(o.optString("file", "")), mdCell(st), mdCell(details)});
        }
        sb.append(pgTable(title, headers, rows));
    }

    private String formatVerifyAllModulesDisplay(JSONArray results) {
        boolean zh = isZh();
        String head = zh ? "🔐 所有模块已验证" : "🔐 All Modules Verified";
        StringBuilder sb = new StringBuilder();
        sb.append(head).append("\n");
        appendModulesSummaryAndTable(sb, results);
        return sb.toString().trim();
    }

    /**
     * {@code verifyAll} 的展示：复用模块列表统计逻辑。
     *
     * @param all {@link #verifyAll} 返回对象
     */
    private String formatVerifyAllDisplay(JSONObject all) {
        JSONArray modules = all.optJSONArray("modules");
        if (modules != null) {
            return formatVerifyAllModulesDisplay(modules);
        }
        boolean zh = isZh();
        String head = zh ? "🔐 所有模块已验证" : "🔐 All Modules Verified";
        StringBuilder sb = new StringBuilder();
        sb.append(head).append("\n");
        appendModulesSummaryAndTable(sb, new JSONArray());
        return sb.toString().trim();
    }

    /**
     * 成功响应（无展示文案）。
     *
     * @param output 业务 JSON 字符串
     */
    private String ok(String output) throws Exception {
        return new JSONObject().put("success", true).put("output", output).toString();
    }

    /**
     * 成功响应并附带 {@code _displayText}。
     *
     * @param output      业务 JSON 字符串
     * @param displayText 界面展示用短文本
     */
    private String ok(String output, String displayText) throws Exception {
        JSONObject r = new JSONObject().put("success", true).put("output", output);
        if (displayText != null && !displayText.isEmpty()) r.put("_displayText", displayText);
        return r.toString();
    }

    /**
     * 顶层错误响应。
     *
     * @param message 错误描述
     */
    private String error(String message) throws Exception {
        return new JSONObject().put("success", false).put("error", message).toString();
    }
}
