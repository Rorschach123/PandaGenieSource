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
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.Enumeration;

public class SignatureCheckerPlugin implements ModulePlugin {

    private static final String APK_CERT_ASSET = "module_signing/official_cert.pem";
    private static final String MODULES_DIR = "PandaGenie/modules";

    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        JSONObject params = new JSONObject(emptyJson(paramsJson));
        switch (action) {
            case "verifyApk":
                return ok(verifyApkSignature(context).toString());
            case "verifyModule":
                return ok(verifySingleModule(context, params).toString());
            case "verifyAllModules":
                return ok(verifyAllModules(context).toString());
            case "verifyAll":
                return ok(verifyAll(context).toString());
            default:
                return error("Unsupported action: " + action);
        }
    }

    private String emptyJson(String value) {
        return value == null || value.trim().isEmpty() ? "{}" : value;
    }

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

    private JSONObject verifyAll(Context context) throws Exception {
        JSONObject result = new JSONObject();
        result.put("apk", verifyApkSignature(context));
        result.put("modules", verifyAllModules(context));
        return result;
    }

    private JSONObject verifyApkSignature(Context context) throws Exception {
        JSONObject apkResult = new JSONObject();
        try {
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

    private void verifyModSignature(File modFile, X509Certificate trustedCert, JSONObject modResult) throws Exception {
        JarFile jarFile = null;
        try {
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
            if (text.startsWith("\uFEFF")) text = text.substring(1);
            return new JSONObject(text);
        } catch (Exception e) {
            return null;
        } finally {
            if (jarFile != null) {
                try { jarFile.close(); } catch (Exception ignored) { }
            }
        }
    }

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

    private String ok(String output) throws Exception {
        return new JSONObject().put("success", true).put("output", output).toString();
    }

    private String error(String message) throws Exception {
        return new JSONObject().put("success", false).put("error", message).toString();
    }
}
