package ai.rorsch.moduleplugins.contacts;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;

import ai.rorsch.pandagenie.module.runtime.ModulePlugin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * PandaGenie 联系人模块插件。
 * <p>
 * <b>模块用途：</b>通过 {@link ContentResolver} 与 {@link ContactsContract} 读取系统通讯录，支持搜索、列表分页、
 * 详情、数量统计、导出 VCard（{@code .vcf}）以及按姓名规范化或电话号码规范化查找可能重复联系人。
 * </p>
 * <p>
 * <b>对外 API（{@code action}）：</b>{@code searchContacts}、{@code getContactDetail}、{@code listContacts}、
 * {@code getContactCount}、{@code exportContacts}、{@code findDuplicates}。
 * </p>
 * <p>
 * 实现 {@link ModulePlugin}，由宿主 {@code ModuleRuntime} 反射加载；需应用具备相应联系人读权限。
 * </p>
 */
public class ContactsPlugin implements ModulePlugin {

    /** 未指定 {@code outputPath} 时导出 VCard 的默认绝对路径 */
    private static final String DEFAULT_EXPORT_PATH = "/sdcard/PandaGenie/output/contacts_export.vcf";
    /** 搜索结果 {@code _displayText} 最多列出的联系人行数 */
    private static final int DISPLAY_MAX_SEARCH_LINES = 50;
    /** 列表结果展示的最大行数 */
    private static final int DISPLAY_MAX_LIST_LINES = 100;
    /** 重复联系人分组在展示文本中的最大组数 */
    private static final int DISPLAY_MAX_DUP_GROUPS = 40;

    private static boolean isZh() {
        try {
            return java.util.Locale.getDefault().getLanguage().toLowerCase(java.util.Locale.ROOT).startsWith("zh");
        } catch (Exception e) {
            return false;
        }
    }

    private static String pgTable(String title, String[] headers, java.util.List<String[]> rows) {
        try {
            org.json.JSONObject t = new org.json.JSONObject();
            t.put("title", title);
            org.json.JSONArray h = new org.json.JSONArray();
            for (String hdr : headers) {
                h.put(hdr);
            }
            t.put("headers", h);
            org.json.JSONArray r = new org.json.JSONArray();
            for (String[] row : rows) {
                org.json.JSONArray a = new org.json.JSONArray();
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

    /**
     * 模块统一入口：解析 JSON 参数并分发；成功时 {@code output} 为业务 JSON 字符串，并常附带 {@code _displayText}。
     *
     * @param context    Android 上下文，用于 {@link Context#getContentResolver()}
     * @param action     操作名，与各 case 对应
     * @param paramsJson 各 action 所需字段见各私有方法实现（空则按 {@code {}}）
     * @return 标准包装 JSON 字符串
     * @throws Exception 查询或 JSON 处理异常
     */
    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        JSONObject params = new JSONObject(emptyJson(paramsJson));
        switch (action) {
            case "searchContacts": {
                String out = searchContacts(context, params);
                return ok(out, formatSearchContactsDisplay(new JSONObject(out)));
            }
            case "getContactDetail": {
                String contactId = params.optString("contactId", "").trim();
                if (contactId.isEmpty()) {
                    return error("contactId required");
                }
                String detailJson = getContactDetailJson(context, contactId);
                JSONObject detailObj = new JSONObject(detailJson);
                if (detailObj.has("error")) {
                    return error(detailObj.getString("error"));
                }
                return ok(detailJson, formatGetContactDetailDisplay(detailObj));
            }
            case "listContacts": {
                String out = listContacts(context, params);
                return ok(out, formatListContactsDisplay(new JSONObject(out)));
            }
            case "getContactCount": {
                String out = getContactCount(context);
                return ok(out, formatGetContactCountDisplay(new JSONObject(out)));
            }
            case "exportContacts": {
                String exportJson = exportContacts(context, params);
                JSONObject exportObj = new JSONObject(exportJson);
                if (exportObj.has("error")) {
                    return error(exportObj.getString("error"));
                }
                return ok(exportJson, formatExportContactsDisplay(exportObj));
            }
            case "findDuplicates": {
                String out = findDuplicates(context);
                return ok(out, formatFindDuplicatesDisplay(new JSONObject(out)));
            }
            default:
                return error("Unsupported action: " + action);
        }
    }

    /**
     * 按关键字搜索联系人：优先通过系统「姓名过滤」URI 匹配显示名；若关键字含数字且结果未满，再按电话号码 LIKE 补充。
     *
     * @param context 上下文
     * @param params  {@code query} 关键字；{@code limit} 默认 20， clamp 到 [1,500]
     * @return JSON：{@code contacts} 数组（每项含 id、name、primaryPhone），{@code total} 为本次返回条数
     * @throws Exception 查询异常
     */
    private String searchContacts(Context context, JSONObject params) throws Exception {
        String query = params.optString("query", "").trim();
        int limit = params.optInt("limit", 20);
        if (limit < 1) {
            limit = 20;
        }
        if (limit > 500) {
            limit = 500;
        }
        if (query.isEmpty()) {
            return new JSONObject().put("contacts", new JSONArray()).put("total", 0).toString();
        }

        ContentResolver resolver = context.getContentResolver();
        // 使用 LinkedHashSet 保持插入顺序并去重（姓名匹配与电话匹配可能指向同一联系人）
        LinkedHashSet<String> idOrder = new LinkedHashSet<>();

        Uri nameFilterUri = Uri.withAppendedPath(
                ContactsContract.Contacts.CONTENT_FILTER_URI,
                Uri.encode(query));
        Cursor nameC = null;
        try {
            nameC = resolver.query(
                    nameFilterUri,
                    new String[]{ContactsContract.Contacts._ID},
                    ContactsContract.Contacts.IN_VISIBLE_GROUP + "=1",
                    null,
                    null);
            if (nameC != null) {
                while (nameC.moveToNext() && idOrder.size() < limit) {
                    idOrder.add(nameC.getString(0));
                }
            }
        } finally {
            if (nameC != null) {
                nameC.close();
            }
        }

        String digits = digitsOnly(query);
        if (!digits.isEmpty() && idOrder.size() < limit) {
            Cursor phoneC = null;
            try {
                phoneC = resolver.query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        new String[]{ContactsContract.CommonDataKinds.Phone.CONTACT_ID},
                        ContactsContract.CommonDataKinds.Phone.NUMBER + " LIKE ?",
                        new String[]{"%" + digits + "%"},
                        null);
                if (phoneC != null) {
                    while (phoneC.moveToNext() && idOrder.size() < limit) {
                        String cid = phoneC.getString(0);
                        if (cid != null) {
                            idOrder.add(cid);
                        }
                    }
                }
            } finally {
                if (phoneC != null) {
                    phoneC.close();
                }
            }
        }

        JSONArray arr = new JSONArray();
        for (String id : idOrder) {
            if (arr.length() >= limit) {
                break;
            }
            JSONObject row = contactSummary(resolver, id);
            if (row != null) {
                arr.put(row);
            }
        }
        return new JSONObject().put("contacts", arr).put("total", arr.length()).toString();
    }

    /**
     * 读取单个联系人的完整详情：显示名、lookupKey、电话/邮箱/地址/公司职务等结构化数组。
     *
     * @param context   上下文
     * @param contactId {@link ContactsContract.Contacts#_ID}
     * @return 成功为详情 JSON 字符串；找不到联系人时为 {@code {"error":"Contact not found"}}
     * @throws Exception 查询异常
     */
    private String getContactDetailJson(Context context, String contactId) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        Cursor c = null;
        try {
            c = resolver.query(
                    ContactsContract.Contacts.CONTENT_URI,
                    new String[]{
                            ContactsContract.Contacts._ID,
                            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                            ContactsContract.Contacts.LOOKUP_KEY
                    },
                    ContactsContract.Contacts._ID + "=?",
                    new String[]{contactId},
                    null);
            if (c == null || !c.moveToFirst()) {
                return new JSONObject().put("error", "Contact not found").toString();
            }

            JSONObject out = new JSONObject();
            out.put("contactId", c.getString(0));
            out.put("displayName", nullToEmpty(c.getString(1)));
            out.put("lookupKey", nullToEmpty(c.getString(2)));
            out.put("phones", loadPhones(resolver, contactId));
            out.put("emails", loadEmails(resolver, contactId));
            out.put("addresses", loadAddresses(resolver, contactId));
            out.put("organizations", loadOrganizations(resolver, contactId));
            return out.toString();
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    /**
     * 分页列出「可见分组」中的联系人，按显示名本地化排序；每条含主号码（若有）。
     *
     * @param context 上下文
     * @param params  {@code limit} 默认 50、最大 2000；{@code offset} 默认 0
     * @return JSON：{@code contacts} 当前页，{@code total} 为符合条件的总条数（游标 count）
     * @throws Exception 查询异常
     */
    private String listContacts(Context context, JSONObject params) throws Exception {
        int limit = params.optInt("limit", 50);
        int offset = params.optInt("offset", 0);
        if (limit < 1) {
            limit = 50;
        }
        if (limit > 2000) {
            limit = 2000;
        }
        if (offset < 0) {
            offset = 0;
        }

        ContentResolver resolver = context.getContentResolver();
        Cursor c = null;
        try {
            c = resolver.query(
                    ContactsContract.Contacts.CONTENT_URI,
                    new String[]{
                            ContactsContract.Contacts._ID,
                            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                            ContactsContract.Contacts.HAS_PHONE_NUMBER
                    },
                    ContactsContract.Contacts.IN_VISIBLE_GROUP + "=1",
                    null,
                    ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " COLLATE LOCALIZED ASC");
            if (c == null) {
                return new JSONObject().put("contacts", new JSONArray()).put("total", 0).toString();
            }

            int total = c.getCount();
            JSONArray arr = new JSONArray();
            if (!c.moveToPosition(offset)) {
                return new JSONObject().put("contacts", arr).put("total", total).toString();
            }
            int n = 0;
            while (n < limit && !c.isAfterLast()) {
                String id = c.getString(0);
                String name = nullToEmpty(c.getString(1));
                String primaryPhone = "";
                if (c.getInt(2) != 0) {
                    primaryPhone = getPrimaryPhone(resolver, id);
                }
                JSONObject row = new JSONObject();
                row.put("id", id);
                row.put("name", name);
                row.put("primaryPhone", primaryPhone);
                arr.put(row);
                c.moveToNext();
                n++;
            }
            return new JSONObject().put("contacts", arr).put("total", total).toString();
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    /**
     * 统计可见分组中的联系人总数（与列表接口相同的可见性过滤）。
     *
     * @param context 上下文
     * @return JSON：{@code count}
     * @throws Exception 查询异常
     */
    private String getContactCount(Context context) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        Cursor c = null;
        try {
            c = resolver.query(
                    ContactsContract.Contacts.CONTENT_URI,
                    new String[]{ContactsContract.Contacts._ID},
                    ContactsContract.Contacts.IN_VISIBLE_GROUP + "=1",
                    null,
                    null);
            int n = c != null ? c.getCount() : 0;
            return new JSONObject().put("count", n).toString();
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    /**
     * 将所有可见联系人逐条写成 VCard 3.0 写入指定文件（UTF-8）。
     *
     * @param context 上下文
     * @param params  {@code outputPath} 可选，默认 {@link #DEFAULT_EXPORT_PATH}
     * @return 成功：{@code path}、{@code exported} 数量；失败：JSON 内含 {@code error}
     * @throws Exception 一般被内部捕获并写入 error
     */
    private String exportContacts(Context context, JSONObject params) throws Exception {
        String outputPath = params.optString("outputPath", "").trim();
        if (outputPath.isEmpty()) {
            outputPath = DEFAULT_EXPORT_PATH;
        }

        File outFile = new File(outputPath);
        File parent = outFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            return new JSONObject().put("error", "Cannot create output directory: " + parent.getAbsolutePath()).toString();
        }

        ContentResolver resolver = context.getContentResolver();
        Cursor c = null;
        Writer writer = null;
        int exported = 0;
        try {
            c = resolver.query(
                    ContactsContract.Contacts.CONTENT_URI,
                    new String[]{
                            ContactsContract.Contacts._ID,
                            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
                    },
                    ContactsContract.Contacts.IN_VISIBLE_GROUP + "=1",
                    null,
                    ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " COLLATE LOCALIZED ASC");
            if (c == null) {
                return new JSONObject().put("error", "Query returned null cursor").toString();
            }

            writer = new OutputStreamWriter(new FileOutputStream(outFile), StandardCharsets.UTF_8);
            while (c.moveToNext()) {
                String id = c.getString(0);
                String name = nullToEmpty(c.getString(1));
                writeVcard(writer, resolver, id, name);
                exported++;
            }
            writer.flush();
            return new JSONObject()
                    .put("path", outFile.getAbsolutePath())
                    .put("exported", exported)
                    .toString();
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "export failed";
            return new JSONObject().put("error", msg).toString();
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (Exception ignored) {
                }
            }
            if (c != null) {
                c.close();
            }
        }
    }

    /**
     * 查找重复联系人：一类为规范化显示名相同且人数&gt;1 的分组；一类为去掉非数字后电话号码相同且对应多个 contactId 的分组。
     *
     * @param context 上下文
     * @return JSON：{@code duplicateNameGroups}、{@code duplicatePhoneGroups}，每组含规范化键、id 列表与 count
     * @throws Exception 查询异常
     */
    private String findDuplicates(Context context) throws Exception {
        ContentResolver resolver = context.getContentResolver();

        Map<String, List<String>> byName = new HashMap<>();
        Cursor c = null;
        try {
            c = resolver.query(
                    ContactsContract.Contacts.CONTENT_URI,
                    new String[]{
                            ContactsContract.Contacts._ID,
                            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
                    },
                    ContactsContract.Contacts.IN_VISIBLE_GROUP + "=1",
                    null,
                    null);
            if (c != null) {
                while (c.moveToNext()) {
                    String id = c.getString(0);
                    String raw = c.getString(1);
                    String key = normalizeName(raw);
                    if (key.isEmpty()) {
                        continue;
                    }
                    List<String> list = byName.get(key);
                    if (list == null) {
                        list = new ArrayList<>();
                        byName.put(key, list);
                    }
                    list.add(id);
                }
            }
        } finally {
            if (c != null) {
                c.close();
            }
        }

        JSONArray nameDup = new JSONArray();
        for (Map.Entry<String, List<String>> e : byName.entrySet()) {
            List<String> ids = e.getValue();
            if (ids.size() > 1) {
                JSONObject g = new JSONObject();
                g.put("normalizedName", e.getKey());
                g.put("contactIds", new JSONArray(ids));
                g.put("count", ids.size());
                nameDup.put(g);
            }
        }

        Map<String, List<String>> byPhone = new HashMap<>();
        Cursor p = null;
        try {
            p = resolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{
                            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                            ContactsContract.CommonDataKinds.Phone.NUMBER
                    },
                    null,
                    null,
                    null);
            if (p != null) {
                while (p.moveToNext()) {
                    String cid = p.getString(0);
                    String num = p.getString(1);
                    String norm = digitsOnly(num);
                    // 过短数字多为分机或片段，避免误聚类
                    if (norm.length() < 5) {
                        continue;
                    }
                    List<String> list = byPhone.get(norm);
                    if (list == null) {
                        list = new ArrayList<>();
                        byPhone.put(norm, list);
                    }
                    if (!list.contains(cid)) {
                        list.add(cid);
                    }
                }
            }
        } finally {
            if (p != null) {
                p.close();
            }
        }

        JSONArray phoneDup = new JSONArray();
        for (Map.Entry<String, List<String>> e : byPhone.entrySet()) {
            List<String> ids = e.getValue();
            if (ids.size() > 1) {
                JSONObject g = new JSONObject();
                g.put("normalizedPhone", e.getKey());
                g.put("contactIds", new JSONArray(ids));
                g.put("count", ids.size());
                phoneDup.put(g);
            }
        }

        return new JSONObject()
                .put("duplicateNameGroups", nameDup)
                .put("duplicatePhoneGroups", phoneDup)
                .toString();
    }

    /**
     * 将单个联系人写成一段 VCard：FN、TEL、EMAIL、ADR、ORG/TITLE，字段值按 vCard 规则转义。
     *
     * @param w           输出 Writer（调用方负责换行与编码）
     * @param resolver    用于查询电话、邮箱等 RawContact 数据
     * @param contactId   联系人 ID
     * @param displayName 显示名，写入 FN
     * @throws Exception IO 或查询异常
     */
    private static void writeVcard(Writer w, ContentResolver resolver, String contactId, String displayName)
            throws Exception {
        w.write("BEGIN:VCARD\r\n");
        w.write("VERSION:3.0\r\n");
        w.write("FN:" + escapeVcardValue(displayName) + "\r\n");

        Cursor ph = null;
        try {
            ph = resolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{
                            ContactsContract.CommonDataKinds.Phone.NUMBER,
                            ContactsContract.CommonDataKinds.Phone.TYPE,
                            ContactsContract.CommonDataKinds.Phone.LABEL
                    },
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID + "=?",
                    new String[]{contactId},
                    null);
            if (ph != null) {
                while (ph.moveToNext()) {
                    String num = nullToEmpty(ph.getString(0)).trim();
                    if (num.isEmpty()) {
                        continue;
                    }
                    int type = ph.getInt(1);
                    String label = ph.getString(2);
                    String typeStr = phoneTypeToVcard(type, label);
                    w.write("TEL" + typeStr + ":" + escapeVcardValue(num) + "\r\n");
                }
            }
        } finally {
            if (ph != null) {
                ph.close();
            }
        }

        Cursor em = null;
        try {
            em = resolver.query(
                    ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                    new String[]{
                            ContactsContract.CommonDataKinds.Email.ADDRESS,
                            ContactsContract.CommonDataKinds.Email.TYPE,
                            ContactsContract.CommonDataKinds.Email.LABEL
                    },
                    ContactsContract.CommonDataKinds.Email.CONTACT_ID + "=?",
                    new String[]{contactId},
                    null);
            if (em != null) {
                while (em.moveToNext()) {
                    String addr = nullToEmpty(em.getString(0)).trim();
                    if (addr.isEmpty()) {
                        continue;
                    }
                    int type = em.getInt(1);
                    String label = em.getString(2);
                    String typeStr = emailTypeToVcard(type, label);
                    w.write("EMAIL" + typeStr + ":" + escapeVcardValue(addr) + "\r\n");
                }
            }
        } finally {
            if (em != null) {
                em.close();
            }
        }

        Cursor ad = null;
        try {
            ad = resolver.query(
                    ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_URI,
                    new String[]{
                            ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS,
                            ContactsContract.CommonDataKinds.StructuredPostal.STREET,
                            ContactsContract.CommonDataKinds.StructuredPostal.CITY,
                            ContactsContract.CommonDataKinds.StructuredPostal.REGION,
                            ContactsContract.CommonDataKinds.StructuredPostal.POSTCODE,
                            ContactsContract.CommonDataKinds.StructuredPostal.COUNTRY
                    },
                    ContactsContract.CommonDataKinds.StructuredPostal.CONTACT_ID + "=?",
                    new String[]{contactId},
                    null);
            if (ad != null) {
                while (ad.moveToNext()) {
                    String formatted = nullToEmpty(ad.getString(0)).trim();
                    String street = nullToEmpty(ad.getString(1));
                    String city = nullToEmpty(ad.getString(2));
                    String region = nullToEmpty(ad.getString(3));
                    String postcode = nullToEmpty(ad.getString(4));
                    String country = nullToEmpty(ad.getString(5));
                    if (formatted.isEmpty()
                            && street.isEmpty() && city.isEmpty() && region.isEmpty()
                            && postcode.isEmpty() && country.isEmpty()) {
                        continue;
                    }
                    String adrValue = formatted.isEmpty()
                            ? (";;" + street + ";" + city + ";" + region + ";" + postcode + ";" + country)
                            : formatted.replace("\r\n", "\n").replace("\n", ", ");
                    w.write("ADR;TYPE=HOME:" + escapeVcardValue(adrValue) + "\r\n");
                }
            }
        } finally {
            if (ad != null) {
                ad.close();
            }
        }

        Cursor org = null;
        try {
            org = resolver.query(
                    ContactsContract.Data.CONTENT_URI,
                    new String[]{
                            ContactsContract.CommonDataKinds.Organization.COMPANY,
                            ContactsContract.CommonDataKinds.Organization.TITLE
                    },
                    ContactsContract.Data.MIMETYPE + "=? AND "
                            + ContactsContract.Data.CONTACT_ID + "=?",
                    new String[]{
                            ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
                            contactId
                    },
                    null);
            if (org != null) {
                while (org.moveToNext()) {
                    String company = nullToEmpty(org.getString(0)).trim();
                    String title = nullToEmpty(org.getString(1)).trim();
                    if (!company.isEmpty()) {
                        w.write("ORG:" + escapeVcardValue(company) + "\r\n");
                    }
                    if (!title.isEmpty()) {
                        w.write("TITLE:" + escapeVcardValue(title) + "\r\n");
                    }
                }
            }
        } finally {
            if (org != null) {
                org.close();
            }
        }

        w.write("END:VCARD\r\n");
    }

    /**
     * 将 {@link ContactsContract.CommonDataKinds.Phone} 的 type 转为 vCard TEL 的 TYPE 参数片段。
     *
     * @param type  {@link ContactsContract.CommonDataKinds.Phone#TYPE_HOME} 等
     * @param label 自定义类型时的标签
     * @return 形如 {@code ;TYPE=CELL} 的字符串
     */
    private static String phoneTypeToVcard(int type, String label) {
        switch (type) {
            case ContactsContract.CommonDataKinds.Phone.TYPE_HOME:
                return ";TYPE=HOME";
            case ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE:
                return ";TYPE=CELL";
            case ContactsContract.CommonDataKinds.Phone.TYPE_WORK:
                return ";TYPE=WORK";
            case ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK:
            case ContactsContract.CommonDataKinds.Phone.TYPE_FAX_HOME:
                return ";TYPE=FAX";
            case ContactsContract.CommonDataKinds.Phone.TYPE_MAIN:
                return ";TYPE=MAIN";
            case ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM:
                if (label != null && !label.trim().isEmpty()) {
                    return ";TYPE=" + escapeVcardParam(label.trim());
                }
                return ";TYPE=VOICE";
            default:
                return ";TYPE=VOICE";
        }
    }

    /**
     * 将邮箱类型转为 vCard EMAIL 的 TYPE 参数片段。
     *
     * @param type  系统预定义类型
     * @param label 自定义标签
     * @return {@code ;TYPE=...} 片段
     */
    private static String emailTypeToVcard(int type, String label) {
        switch (type) {
            case ContactsContract.CommonDataKinds.Email.TYPE_HOME:
                return ";TYPE=HOME";
            case ContactsContract.CommonDataKinds.Email.TYPE_WORK:
                return ";TYPE=WORK";
            case ContactsContract.CommonDataKinds.Email.TYPE_MOBILE:
                return ";TYPE=CELL";
            case ContactsContract.CommonDataKinds.Email.TYPE_CUSTOM:
                if (label != null && !label.trim().isEmpty()) {
                    return ";TYPE=" + escapeVcardParam(label.trim());
                }
                return ";TYPE=INTERNET";
            default:
                return ";TYPE=INTERNET";
        }
    }

    /**
     * 转义 vCard 参数值中的特殊字符（用于 TYPE 自定义等）。
     *
     * @param s 原始参数
     * @return 转义后字符串
     */
    private static String escapeVcardParam(String s) {
        return s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,");
    }

    /**
     * 转义 vCard 属性值中的反斜杠、换行、分号、逗号。
     *
     * @param s 原始值，可为 null
     * @return 转义后；null 视为空串
     */
    private static String escapeVcardValue(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\")
                .replace("\r\n", "\\n")
                .replace("\n", "\\n")
                .replace("\r", "\\n")
                .replace(";", "\\;")
                .replace(",", "\\,");
    }

    /**
     * 加载联系人全部电话号码，主号码优先（按 IS_PRIMARY DESC 排序）。
     *
     * @param resolver  ContentResolver
     * @param contactId 联系人 ID
     * @return JSON 数组，元素含 number、type、label、isPrimary
     * @throws Exception 查询异常
     */
    private static JSONArray loadPhones(ContentResolver resolver, String contactId) throws Exception {
        JSONArray arr = new JSONArray();
        Cursor c = null;
        try {
            c = resolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{
                            ContactsContract.CommonDataKinds.Phone.NUMBER,
                            ContactsContract.CommonDataKinds.Phone.TYPE,
                            ContactsContract.CommonDataKinds.Phone.LABEL,
                            ContactsContract.CommonDataKinds.Phone.IS_PRIMARY
                    },
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID + "=?",
                    new String[]{contactId},
                    ContactsContract.CommonDataKinds.Phone.IS_PRIMARY + " DESC");
            if (c != null) {
                while (c.moveToNext()) {
                    JSONObject o = new JSONObject();
                    o.put("number", nullToEmpty(c.getString(0)));
                    o.put("type", c.getInt(1));
                    o.put("label", c.isNull(2) ? "" : nullToEmpty(c.getString(2)));
                    o.put("isPrimary", c.getInt(3) != 0);
                    arr.put(o);
                }
            }
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return arr;
    }

    /**
     * 加载联系人全部邮箱地址及类型信息。
     *
     * @param resolver  ContentResolver
     * @param contactId 联系人 ID
     * @return JSON 数组
     * @throws Exception 查询异常
     */
    private static JSONArray loadEmails(ContentResolver resolver, String contactId) throws Exception {
        JSONArray arr = new JSONArray();
        Cursor c = null;
        try {
            c = resolver.query(
                    ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                    new String[]{
                            ContactsContract.CommonDataKinds.Email.ADDRESS,
                            ContactsContract.CommonDataKinds.Email.TYPE,
                            ContactsContract.CommonDataKinds.Email.LABEL,
                            ContactsContract.CommonDataKinds.Email.IS_PRIMARY
                    },
                    ContactsContract.CommonDataKinds.Email.CONTACT_ID + "=?",
                    new String[]{contactId},
                    null);
            if (c != null) {
                while (c.moveToNext()) {
                    JSONObject o = new JSONObject();
                    o.put("address", nullToEmpty(c.getString(0)));
                    o.put("type", c.getInt(1));
                    o.put("label", c.isNull(2) ? "" : nullToEmpty(c.getString(2)));
                    o.put("isPrimary", c.getInt(3) != 0);
                    arr.put(o);
                }
            }
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return arr;
    }

    /**
     * 加载结构化邮政地址（格式化地址与各字段）。
     *
     * @param resolver  ContentResolver
     * @param contactId 联系人 ID
     * @return JSON 数组
     * @throws Exception 查询异常
     */
    private static JSONArray loadAddresses(ContentResolver resolver, String contactId) throws Exception {
        JSONArray arr = new JSONArray();
        Cursor c = null;
        try {
            c = resolver.query(
                    ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_URI,
                    new String[]{
                            ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS,
                            ContactsContract.CommonDataKinds.StructuredPostal.STREET,
                            ContactsContract.CommonDataKinds.StructuredPostal.CITY,
                            ContactsContract.CommonDataKinds.StructuredPostal.REGION,
                            ContactsContract.CommonDataKinds.StructuredPostal.POSTCODE,
                            ContactsContract.CommonDataKinds.StructuredPostal.COUNTRY,
                            ContactsContract.CommonDataKinds.StructuredPostal.TYPE,
                            ContactsContract.CommonDataKinds.StructuredPostal.LABEL
                    },
                    ContactsContract.CommonDataKinds.StructuredPostal.CONTACT_ID + "=?",
                    new String[]{contactId},
                    null);
            if (c != null) {
                while (c.moveToNext()) {
                    JSONObject o = new JSONObject();
                    o.put("formatted", nullToEmpty(c.getString(0)));
                    o.put("street", nullToEmpty(c.getString(1)));
                    o.put("city", nullToEmpty(c.getString(2)));
                    o.put("region", nullToEmpty(c.getString(3)));
                    o.put("postcode", nullToEmpty(c.getString(4)));
                    o.put("country", nullToEmpty(c.getString(5)));
                    o.put("type", c.getInt(6));
                    o.put("label", c.isNull(7) ? "" : nullToEmpty(c.getString(7)));
                    arr.put(o);
                }
            }
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return arr;
    }

    /**
     * 加载组织信息：公司、职务、部门。
     *
     * @param resolver  ContentResolver
     * @param contactId 联系人 ID
     * @return JSON 数组
     * @throws Exception 查询异常
     */
    private static JSONArray loadOrganizations(ContentResolver resolver, String contactId) throws Exception {
        JSONArray arr = new JSONArray();
        Cursor c = null;
        try {
            c = resolver.query(
                    ContactsContract.Data.CONTENT_URI,
                    new String[]{
                            ContactsContract.CommonDataKinds.Organization.COMPANY,
                            ContactsContract.CommonDataKinds.Organization.TITLE,
                            ContactsContract.CommonDataKinds.Organization.DEPARTMENT
                    },
                    ContactsContract.Data.MIMETYPE + "=? AND "
                            + ContactsContract.Data.CONTACT_ID + "=?",
                    new String[]{
                            ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
                            contactId
                    },
                    null);
            if (c != null) {
                while (c.moveToNext()) {
                    JSONObject o = new JSONObject();
                    o.put("company", nullToEmpty(c.getString(0)));
                    o.put("title", nullToEmpty(c.getString(1)));
                    o.put("department", nullToEmpty(c.getString(2)));
                    arr.put(o);
                }
            }
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return arr;
    }

    /**
     * 构建搜索/列表用的单行摘要：id、显示名、主号码（若有）。
     *
     * @param resolver  ContentResolver
     * @param contactId 联系人 ID
     * @return JSON 对象；联系人不存在返回 null
     * @throws Exception 查询异常
     */
    private static JSONObject contactSummary(ContentResolver resolver, String contactId) throws Exception {
        Cursor c = null;
        try {
            c = resolver.query(
                    ContactsContract.Contacts.CONTENT_URI,
                    new String[]{
                            ContactsContract.Contacts._ID,
                            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                            ContactsContract.Contacts.HAS_PHONE_NUMBER
                    },
                    ContactsContract.Contacts._ID + "=?",
                    new String[]{contactId},
                    null);
            if (c == null || !c.moveToFirst()) {
                return null;
            }
            String id = c.getString(0);
            String name = nullToEmpty(c.getString(1));
            String primaryPhone = "";
            if (c.getInt(2) != 0) {
                primaryPhone = getPrimaryPhone(resolver, id);
            }
            JSONObject row = new JSONObject();
            row.put("id", id);
            row.put("name", name);
            row.put("primaryPhone", primaryPhone);
            return row;
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    /**
     * 取该联系人「主号码」优先的第一条电话号码字符串。
     *
     * @param resolver  ContentResolver
     * @param contactId 联系人 ID
     * @return 号码文本，无则空串
     */
    private static String getPrimaryPhone(ContentResolver resolver, String contactId) {
        Cursor c = null;
        try {
            c = resolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{
                            ContactsContract.CommonDataKinds.Phone.NUMBER,
                            ContactsContract.CommonDataKinds.Phone.IS_PRIMARY
                    },
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID + "=?",
                    new String[]{contactId},
                    ContactsContract.CommonDataKinds.Phone.IS_PRIMARY + " DESC, "
                            + ContactsContract.CommonDataKinds.Phone._ID + " ASC");
            if (c != null && c.moveToFirst()) {
                return nullToEmpty(c.getString(0));
            }
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return "";
    }

    /**
     * 重复检测用姓名键：去空白并转小写，便于合并仅大小写/空格不同的条目。
     *
     * @param raw 原始显示名
     * @return 规范化键，null 输入得到空串
     */
    private static String normalizeName(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 从字符串中提取连续数字，用于电话匹配与重复检测中的「规范化号码」。
     *
     * @param s 任意字符串，可为 null
     * @return 仅含 0-9 的字符串
     */
    private static String digitsOnly(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= '0' && ch <= '9') {
                b.append(ch);
            }
        }
        return b.toString();
    }

    /**
     * @param s 可能为 null 的数据库字符串
     * @return null 转为 ""，否则原样
     */
    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * @param v 参数字符串
     * @return 空则 {@code "{}"}
     */
    private String emptyJson(String v) {
        return v == null || v.trim().isEmpty() ? "{}" : v;
    }

    /**
     * @param output 业务输出 JSON 字符串
     * @return 成功包装，无 {@code _displayText}
     * @throws Exception JSON 异常
     */
    private String ok(String output) throws Exception {
        return new JSONObject().put("success", true).put("output", output).toString();
    }

    /**
     * @param output      业务输出
     * @param displayText 人类可读摘要
     * @return 成功包装 JSON
     * @throws Exception JSON 异常
     */
    private String ok(String output, String displayText) throws Exception {
        return new JSONObject()
                .put("success", true)
                .put("output", output)
                .put("_displayText", displayText)
                .toString();
    }

    /**
     * 格式化搜索结果用于 {@code _displayText}。
     *
     * @param result {@link #searchContacts} 返回解析后的对象
     * @return 多行展示字符串
     * @throws Exception JSON 异常
     */
    private static String formatSearchContactsDisplay(JSONObject result) throws Exception {
        JSONArray contacts = result.optJSONArray("contacts");
        int total = result.optInt("total", contacts != null ? contacts.length() : 0);
        if (contacts == null) {
            contacts = new JSONArray();
        }
        boolean zh = isZh();
        String[] headers = zh ? new String[]{"姓名", "电话"} : new String[]{"Name", "Phone"};
        List<String[]> rows = new ArrayList<>();
        int show = Math.min(contacts.length(), DISPLAY_MAX_SEARCH_LINES);
        for (int i = 0; i < show; i++) {
            JSONObject c = contacts.getJSONObject(i);
            String name = c.optString("name", "").trim();
            if (name.isEmpty()) {
                name = zh ? "(无名称)" : "(no name)";
            }
            String phone = c.optString("primaryPhone", "").trim();
            if (phone.isEmpty()) {
                phone = "—";
            }
            rows.add(new String[]{name, phone});
        }
        StringBuilder sb = new StringBuilder();
        sb.append(zh ? "🔍 联系人搜索\n" : "🔍 Contact Search\n");
        sb.append(zh ? ("找到 " + total + " 个联系人\n\n") : ("Found " + total + " contacts\n\n"));
        sb.append(pgTable("", headers, rows));
        if (contacts.length() > DISPLAY_MAX_SEARCH_LINES) {
            int rest = contacts.length() - DISPLAY_MAX_SEARCH_LINES;
            sb.append("\n\n… (+").append(rest).append(zh ? " 更多)" : " more)");
        }
        return sb.toString().trim();
    }

    /**
     * 详情页式摘要：姓名、首选电话、首选邮箱。
     *
     * @param d {@link #getContactDetailJson} 解析后的对象
     * @return 展示文本
     * @throws Exception JSON 异常
     */
    private static String formatGetContactDetailDisplay(JSONObject d) throws Exception {
        boolean zh = isZh();
        String name = d.optString("displayName", "").trim();
        if (name.isEmpty()) {
            name = zh ? "(无名称)" : "(no name)";
        }
        String phone = firstPhoneNumberForDisplay(d.optJSONArray("phones"));
        String email = firstEmailAddressForDisplay(d.optJSONArray("emails"));
        String contactId = d.optString("contactId", "").trim();
        String lookupKey = d.optString("lookupKey", "").trim();
        String[] headers = zh ? new String[]{"项目", "值"} : new String[]{"Item", "Value"};
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{zh ? "联系人 ID" : "Contact ID", contactId.isEmpty() ? "—" : contactId});
        rows.add(new String[]{zh ? "姓名" : "Name", name});
        rows.add(new String[]{zh ? "查找键" : "Lookup key", lookupKey.isEmpty() ? "—" : lookupKey});
        rows.add(new String[]{zh ? "电话" : "Phone", phone.isEmpty() ? "—" : phone});
        rows.add(new String[]{zh ? "邮箱" : "Email", email.isEmpty() ? "—" : email});
        StringBuilder sb = new StringBuilder();
        sb.append(zh ? "👤 联系人详情\n\n" : "👤 Contact Detail\n\n");
        sb.append(pgTable("", headers, rows));
        return sb.toString().trim();
    }

    /**
     * 从电话数组中取主号码，若无标记主号码则取第一条。
     *
     * @param phones {@link #loadPhones} 生成的数组
     * @return 号码字符串
     * @throws Exception JSON 异常
     */
    private static String firstPhoneNumberForDisplay(JSONArray phones) throws Exception {
        if (phones == null || phones.length() == 0) {
            return "";
        }
        int primaryIdx = -1;
        for (int i = 0; i < phones.length(); i++) {
            if (phones.getJSONObject(i).optBoolean("isPrimary")) {
                primaryIdx = i;
                break;
            }
        }
        int idx = primaryIdx >= 0 ? primaryIdx : 0;
        return phones.getJSONObject(idx).optString("number", "").trim();
    }

    /**
     * 从邮箱数组中取主邮箱，若无则取第一条。
     *
     * @param emails {@link #loadEmails} 生成的数组
     * @return 邮箱地址
     * @throws Exception JSON 异常
     */
    private static String firstEmailAddressForDisplay(JSONArray emails) throws Exception {
        if (emails == null || emails.length() == 0) {
            return "";
        }
        int primaryIdx = -1;
        for (int i = 0; i < emails.length(); i++) {
            if (emails.getJSONObject(i).optBoolean("isPrimary")) {
                primaryIdx = i;
                break;
            }
        }
        int idx = primaryIdx >= 0 ? primaryIdx : 0;
        return emails.getJSONObject(idx).optString("address", "").trim();
    }

    /**
     * 列表结果展示：总人数与当前页姓名枚举（有行数上限）。
     *
     * @param result {@link #listContacts} 输出解析对象
     * @return 展示文本
     * @throws Exception JSON 异常
     */
    private static String formatListContactsDisplay(JSONObject result) throws Exception {
        JSONArray contacts = result.optJSONArray("contacts");
        int total = result.optInt("total", 0);
        if (contacts == null) {
            contacts = new JSONArray();
        }
        boolean zh = isZh();
        String[] headers = zh ? new String[]{"姓名", "电话"} : new String[]{"Name", "Phone"};
        List<String[]> rows = new ArrayList<>();
        int show = Math.min(contacts.length(), DISPLAY_MAX_LIST_LINES);
        for (int i = 0; i < show; i++) {
            JSONObject c = contacts.getJSONObject(i);
            String name = c.optString("name", "").trim();
            if (name.isEmpty()) {
                name = zh ? "(无名称)" : "(no name)";
            }
            String phone = c.optString("primaryPhone", "").trim();
            if (phone.isEmpty()) {
                phone = "—";
            }
            rows.add(new String[]{name, phone});
        }
        StringBuilder sb = new StringBuilder();
        sb.append(zh ? "📇 联系人列表\n" : "📇 Contact List\n");
        sb.append(zh ? ("共 " + total + " 个联系人\n\n") : ("[" + total + "] contacts total\n\n"));
        sb.append(pgTable("", headers, rows));
        if (contacts.length() > DISPLAY_MAX_LIST_LINES) {
            int rest = contacts.length() - DISPLAY_MAX_LIST_LINES;
            sb.append("\n\n… (+").append(rest).append(zh ? " 更多)" : " more in this page)");
        }
        return sb.toString().trim();
    }

    /**
     * @param result {@link #getContactCount} 输出
     * @return 单行总人数展示
     */
    private static String formatGetContactCountDisplay(JSONObject result) {
        int n = result.optInt("count", 0);
        boolean zh = isZh();
        String[] headers = zh ? new String[]{"项目", "值"} : new String[]{"Item", "Value"};
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{zh ? "联系人总数" : "Total contacts", String.valueOf(n)});
        String head = zh ? "📇 联系人数量\n\n" : "📇 Contact Count\n\n";
        return head + pgTable("", headers, rows);
    }

    /**
     * @param result 导出成功时的 JSON（path、exported）
     * @return 导出摘要展示
     */
    private static String formatExportContactsDisplay(JSONObject result) {
        int exported = result.optInt("exported", 0);
        String path = result.optString("path", "").trim();
        boolean zh = isZh();
        String[] headers = zh ? new String[]{"项目", "值"} : new String[]{"Item", "Value"};
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{zh ? "文件" : "File", path.isEmpty() ? "—" : path});
        StringBuilder sb = new StringBuilder();
        sb.append(zh ? "📤 已导出 " : "📤 Exported ").append(exported).append(zh ? " 个联系人\n\n" : " contacts\n\n");
        sb.append(pgTable("", headers, rows));
        return sb.toString().trim();
    }

    /**
     * 重复分组展示：分别列出姓名重复与电话重复的若干组（有组数上限）。
     *
     * @param result {@link #findDuplicates} 输出
     * @return 展示文本
     * @throws Exception JSON 异常
     */
    private static String formatFindDuplicatesDisplay(JSONObject result) throws Exception {
        JSONArray nameGroups = result.optJSONArray("duplicateNameGroups");
        JSONArray phoneGroups = result.optJSONArray("duplicatePhoneGroups");
        if (nameGroups == null) {
            nameGroups = new JSONArray();
        }
        if (phoneGroups == null) {
            phoneGroups = new JSONArray();
        }
        int groupCount = nameGroups.length() + phoneGroups.length();
        boolean zh = isZh();
        String[] headers = zh
                ? new String[]{"类型", "关键字", "数量", "联系人 ID"}
                : new String[]{"Type", "Key", "Count", "Contact IDs"};
        List<String[]> rows = new ArrayList<>();
        int lines = 0;
        for (int i = 0; i < nameGroups.length() && lines < DISPLAY_MAX_DUP_GROUPS; i++) {
            JSONObject g = nameGroups.getJSONObject(i);
            String norm = g.optString("normalizedName", "").trim();
            if (norm.isEmpty()) {
                norm = zh ? "(未命名)" : "(unnamed)";
            }
            int cnt = g.optInt("count", 0);
            String ids = joinContactIds(g.optJSONArray("contactIds"));
            rows.add(new String[]{zh ? "姓名" : "Name", norm, String.valueOf(cnt), ids});
            lines++;
        }
        for (int i = 0; i < phoneGroups.length() && lines < DISPLAY_MAX_DUP_GROUPS; i++) {
            JSONObject g = phoneGroups.getJSONObject(i);
            String norm = g.optString("normalizedPhone", "").trim();
            if (norm.isEmpty()) {
                norm = "—";
            }
            int cnt = g.optInt("count", 0);
            String ids = joinContactIds(g.optJSONArray("contactIds"));
            rows.add(new String[]{zh ? "电话" : "Phone", norm, String.valueOf(cnt), ids});
            lines++;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(zh ? "🔁 重复联系人\n" : "🔁 Duplicate Contacts\n");
        sb.append(zh ? ("找到 " + groupCount + " 组\n\n") : ("Found " + groupCount + " groups\n\n"));
        sb.append(pgTable("", headers, rows));
        int totalLines = nameGroups.length() + phoneGroups.length();
        if (totalLines > DISPLAY_MAX_DUP_GROUPS) {
            sb.append("\n\n… (+").append(totalLines - DISPLAY_MAX_DUP_GROUPS).append(zh ? " 更多组)" : " more groups)");
        }
        return sb.toString().trim();
    }

    private static String joinContactIds(JSONArray ids) throws Exception {
        if (ids == null || ids.length() == 0) {
            return "—";
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < ids.length(); i++) {
            if (i > 0) {
                b.append(", ");
            }
            b.append(ids.get(i).toString());
        }
        return b.toString();
    }

    /**
     * @param msg 错误信息
     * @return {@code success=false} 的 JSON
     * @throws Exception JSON 异常
     */
    private String error(String msg) throws Exception {
        return new JSONObject().put("success", false).put("error", msg).toString();
    }
}
