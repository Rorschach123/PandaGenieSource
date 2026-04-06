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

public class ContactsPlugin implements ModulePlugin {

    private static final String DEFAULT_EXPORT_PATH = "/sdcard/PandaGenie/output/contacts_export.vcf";
    private static final int DISPLAY_MAX_SEARCH_LINES = 50;
    private static final int DISPLAY_MAX_LIST_LINES = 100;
    private static final int DISPLAY_MAX_DUP_GROUPS = 40;

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

    private static String escapeVcardParam(String s) {
        return s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,");
    }

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

    private static String normalizeName(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

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

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private String emptyJson(String v) {
        return v == null || v.trim().isEmpty() ? "{}" : v;
    }

    private String ok(String output) throws Exception {
        return new JSONObject().put("success", true).put("output", output).toString();
    }

    private String ok(String output, String displayText) throws Exception {
        return new JSONObject()
                .put("success", true)
                .put("output", output)
                .put("_displayText", displayText)
                .toString();
    }

    private static String formatSearchContactsDisplay(JSONObject result) throws Exception {
        JSONArray contacts = result.optJSONArray("contacts");
        int total = result.optInt("total", contacts != null ? contacts.length() : 0);
        if (contacts == null) {
            contacts = new JSONArray();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("🔍 Contact Search\n━━━━━━━━━━━━━━\n");
        sb.append("Found ").append(total).append(" contacts\n");
        int show = Math.min(contacts.length(), DISPLAY_MAX_SEARCH_LINES);
        for (int i = 0; i < show; i++) {
            JSONObject c = contacts.getJSONObject(i);
            String name = c.optString("name", "").trim();
            if (name.isEmpty()) {
                name = "(no name)";
            }
            String phone = c.optString("primaryPhone", "").trim();
            if (phone.isEmpty()) {
                sb.append("▸ Name: ").append(name).append("\n");
            } else {
                sb.append("▸ Name: ").append(name).append(" (Phone: ").append(phone).append(")\n");
            }
        }
        if (contacts.length() > DISPLAY_MAX_SEARCH_LINES) {
            sb.append("… (+").append(contacts.length() - DISPLAY_MAX_SEARCH_LINES).append(" more)\n");
        }
        return sb.toString().trim();
    }

    private static String formatGetContactDetailDisplay(JSONObject d) throws Exception {
        String name = d.optString("displayName", "").trim();
        if (name.isEmpty()) {
            name = "(no name)";
        }
        String phone = firstPhoneNumberForDisplay(d.optJSONArray("phones"));
        String email = firstEmailAddressForDisplay(d.optJSONArray("emails"));
        StringBuilder sb = new StringBuilder();
        sb.append("👤 Contact Detail\n━━━━━━━━━━━━━━\n");
        sb.append("▸ Name: ").append(name).append("\n");
        sb.append("▸ Phone: ").append(phone.isEmpty() ? "—" : phone).append("\n");
        sb.append("▸ Email: ").append(email.isEmpty() ? "—" : email).append("\n");
        return sb.toString().trim();
    }

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

    private static String formatListContactsDisplay(JSONObject result) throws Exception {
        JSONArray contacts = result.optJSONArray("contacts");
        int total = result.optInt("total", 0);
        if (contacts == null) {
            contacts = new JSONArray();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("📇 Contact List\n━━━━━━━━━━━━━━\n");
        sb.append("[").append(total).append("] contacts\n");
        int show = Math.min(contacts.length(), DISPLAY_MAX_LIST_LINES);
        for (int i = 0; i < show; i++) {
            JSONObject c = contacts.getJSONObject(i);
            String name = c.optString("name", "").trim();
            if (name.isEmpty()) {
                name = "(no name)";
            }
            sb.append(i + 1).append(". ").append(name).append("\n");
        }
        if (contacts.length() > DISPLAY_MAX_LIST_LINES) {
            sb.append("… (+").append(contacts.length() - DISPLAY_MAX_LIST_LINES).append(" more in this page)\n");
        }
        return sb.toString().trim();
    }

    private static String formatGetContactCountDisplay(JSONObject result) {
        int n = result.optInt("count", 0);
        return "📇 Total contacts: " + n;
    }

    private static String formatExportContactsDisplay(JSONObject result) {
        int exported = result.optInt("exported", 0);
        String path = result.optString("path", "").trim();
        StringBuilder sb = new StringBuilder();
        sb.append("📤 Exported ").append(exported).append(" contacts\n");
        sb.append("▸ File: ").append(path.isEmpty() ? "—" : path);
        return sb.toString();
    }

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
        StringBuilder sb = new StringBuilder();
        sb.append("🔁 Duplicate Contacts\n━━━━━━━━━━━━━━\n");
        sb.append("Found ").append(groupCount).append(" groups\n");
        int lines = 0;
        for (int i = 0; i < nameGroups.length() && lines < DISPLAY_MAX_DUP_GROUPS; i++) {
            JSONObject g = nameGroups.getJSONObject(i);
            String norm = g.optString("normalizedName", "").trim();
            int cnt = g.optInt("count", 0);
            sb.append("▸ Name match: ").append(norm.isEmpty() ? "(unnamed)" : norm);
            sb.append(" — ").append(cnt).append(" contacts\n");
            lines++;
        }
        for (int i = 0; i < phoneGroups.length() && lines < DISPLAY_MAX_DUP_GROUPS; i++) {
            JSONObject g = phoneGroups.getJSONObject(i);
            String norm = g.optString("normalizedPhone", "").trim();
            int cnt = g.optInt("count", 0);
            sb.append("▸ Phone match: ").append(norm.isEmpty() ? "—" : norm);
            sb.append(" — ").append(cnt).append(" contacts\n");
            lines++;
        }
        int totalLines = nameGroups.length() + phoneGroups.length();
        if (totalLines > DISPLAY_MAX_DUP_GROUPS) {
            sb.append("… (+").append(totalLines - DISPLAY_MAX_DUP_GROUPS).append(" more groups)\n");
        }
        return sb.toString().trim();
    }

    private String error(String msg) throws Exception {
        return new JSONObject().put("success", false).put("error", msg).toString();
    }
}
