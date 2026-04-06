package ai.rorsch.moduleplugins.reminder;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.AlarmClock;
import android.provider.CalendarContract;
import ai.rorsch.pandagenie.module.runtime.ModulePlugin;
import org.json.JSONArray;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public class ReminderPlugin implements ModulePlugin {

    private static final SimpleDateFormat SDF_DATETIME = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
    private static final SimpleDateFormat SDF_DATE = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
    private static final SimpleDateFormat SDF_DISPLAY = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        JSONObject params = new JSONObject(emptyJson(paramsJson));
        switch (action) {
            case "createEvent": {
                String out = createEvent(context, params);
                return ok(out, formatCreateEventDisplay(out));
            }
            case "listEvents": {
                String out = listEvents(context, params);
                return ok(out, formatListEventsDisplay(out, "📅 Upcoming Events"));
            }
            case "updateEvent":             return ok(updateEvent(context, params));
            case "deleteEvent": {
                String out = deleteEvent(context, params);
                return ok(out, formatDeleteEventDisplay(out));
            }
            case "getCalendars":            return ok(getCalendars(context));
            case "setAlarm": {
                String out = setAlarm(context, params);
                return ok(out, formatSetAlarmDisplay(out));
            }
            case "setTimer": {
                String out = setTimer(context, params);
                return ok(out, formatSetTimerDisplay(out));
            }
            case "createBirthdayReminder": {
                String out = createBirthdayReminder(context, params);
                return ok(out, formatBirthdayReminderDisplay(out));
            }
            case "openCalendar":            return ok(openCalendar(context, params));
            case "getUpcoming": {
                String out = getUpcoming(context, params);
                return ok(out, formatListEventsDisplay(out, "📅 Next Events"));
            }
            default:
                return error("Unsupported action: " + action);
        }
    }

    // ==================== createEvent ====================

    private String createEvent(Context context, JSONObject params) throws Exception {
        String title = params.optString("title", "").trim();
        if (title.isEmpty()) return errJson("Missing parameter: title");

        String desc = params.optString("description", "");
        String startStr = params.optString("startTime", "").trim();
        String endStr = params.optString("endTime", "").trim();
        String location = params.optString("location", "");
        String rrule = params.optString("rrule", "none").trim().toLowerCase(Locale.ROOT);
        int reminderMin = params.optInt("reminderMinutes", 10);

        if (startStr.isEmpty()) return errJson("Missing parameter: startTime");
        long startMs = parseDateTime(startStr);
        long endMs = endStr.isEmpty() ? startMs + 3600000L : parseDateTime(endStr);

        long calId = getDefaultCalendarId(context);
        if (calId < 0) return errJson("No calendar account found on this device");

        ContentValues cv = new ContentValues();
        cv.put(CalendarContract.Events.CALENDAR_ID, calId);
        cv.put(CalendarContract.Events.TITLE, title);
        if (!desc.isEmpty()) cv.put(CalendarContract.Events.DESCRIPTION, desc);
        if (!location.isEmpty()) cv.put(CalendarContract.Events.EVENT_LOCATION, location);
        cv.put(CalendarContract.Events.DTSTART, startMs);
        cv.put(CalendarContract.Events.DTEND, endMs);
        cv.put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().getID());
        cv.put(CalendarContract.Events.HAS_ALARM, 1);

        String rruleStr = buildRRule(rrule);
        if (rruleStr != null) {
            cv.put(CalendarContract.Events.RRULE, rruleStr);
            cv.remove(CalendarContract.Events.DTEND);
            cv.put(CalendarContract.Events.DURATION, "PT1H");
        }

        Uri uri = context.getContentResolver().insert(CalendarContract.Events.CONTENT_URI, cv);
        if (uri == null) return errJson("Failed to create event");

        long eventId = ContentUris.parseId(uri);
        addReminder(context, eventId, reminderMin);

        JSONObject result = new JSONObject();
        result.put("eventId", eventId);
        result.put("title", title);
        result.put("startTime", SDF_DATETIME.format(new Date(startMs)));
        result.put("endTime", SDF_DATETIME.format(new Date(endMs)));
        result.put("repeat", rrule);
        result.put("reminderMinutes", reminderMin);
        result.put("message", "Event created successfully");
        return result.toString();
    }

    // ==================== listEvents ====================

    private String listEvents(Context context, JSONObject params) throws Exception {
        String startStr = params.optString("startDate", "").trim();
        String endStr = params.optString("endDate", "").trim();
        if (startStr.isEmpty()) return errJson("Missing parameter: startDate");

        long startMs = parseDateStart(startStr);
        long endMs = endStr.isEmpty() ? startMs + 86400000L : parseDateEnd(endStr);

        return queryEvents(context, startMs, endMs).toString();
    }

    private JSONObject queryEvents(Context context, long startMs, long endMs) throws Exception {
        ContentResolver cr = context.getContentResolver();
        Uri.Builder builder = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(builder, startMs);
        ContentUris.appendId(builder, endMs);

        String[] projection = {
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.RRULE,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME
        };

        Cursor cursor = cr.query(builder.build(), projection, null, null, CalendarContract.Instances.BEGIN + " ASC");
        JSONArray events = new JSONArray();
        if (cursor != null) {
            try {
                while (cursor.moveToNext()) {
                    JSONObject ev = new JSONObject();
                    ev.put("eventId", cursor.getLong(0));
                    ev.put("title", strVal(cursor, 1));
                    ev.put("description", strVal(cursor, 2));
                    long begin = cursor.getLong(3);
                    long end = cursor.getLong(4);
                    ev.put("startTime", SDF_DISPLAY.format(new Date(begin)));
                    ev.put("endTime", SDF_DISPLAY.format(new Date(end)));
                    ev.put("location", strVal(cursor, 5));
                    ev.put("allDay", cursor.getInt(6) == 1);
                    ev.put("rrule", strVal(cursor, 7));
                    ev.put("calendarName", strVal(cursor, 8));
                    events.put(ev);
                }
            } finally {
                cursor.close();
            }
        }

        JSONObject result = new JSONObject();
        result.put("count", events.length());
        result.put("events", events);
        return result;
    }

    // ==================== updateEvent ====================

    private String updateEvent(Context context, JSONObject params) throws Exception {
        long eventId = params.optLong("eventId", -1);
        if (eventId < 0) return errJson("Missing parameter: eventId");

        ContentValues cv = new ContentValues();
        putIfNotEmpty(cv, CalendarContract.Events.TITLE, params.optString("title", ""));
        putIfNotEmpty(cv, CalendarContract.Events.DESCRIPTION, params.optString("description", ""));
        putIfNotEmpty(cv, CalendarContract.Events.EVENT_LOCATION, params.optString("location", ""));

        String startStr = params.optString("startTime", "").trim();
        String endStr = params.optString("endTime", "").trim();
        if (!startStr.isEmpty()) cv.put(CalendarContract.Events.DTSTART, parseDateTime(startStr));
        if (!endStr.isEmpty()) cv.put(CalendarContract.Events.DTEND, parseDateTime(endStr));

        if (cv.size() == 0) return errJson("No fields to update");

        Uri eventUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId);
        int rows = context.getContentResolver().update(eventUri, cv, null, null);

        JSONObject result = new JSONObject();
        result.put("eventId", eventId);
        result.put("updated", rows > 0);
        result.put("message", rows > 0 ? "Event updated successfully" : "Event not found or update failed");
        return result.toString();
    }

    // ==================== deleteEvent ====================

    private String deleteEvent(Context context, JSONObject params) throws Exception {
        long eventId = params.optLong("eventId", -1);
        if (eventId < 0) return errJson("Missing parameter: eventId");

        Uri eventUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId);
        int rows = context.getContentResolver().delete(eventUri, null, null);

        JSONObject result = new JSONObject();
        result.put("eventId", eventId);
        result.put("deleted", rows > 0);
        result.put("message", rows > 0 ? "Event deleted successfully" : "Event not found");
        return result.toString();
    }

    // ==================== getCalendars ====================

    private String getCalendars(Context context) throws Exception {
        String[] projection = {
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_COLOR
        };
        Cursor cursor = context.getContentResolver().query(
            CalendarContract.Calendars.CONTENT_URI, projection, null, null, null);

        JSONArray arr = new JSONArray();
        if (cursor != null) {
            try {
                while (cursor.moveToNext()) {
                    JSONObject cal = new JSONObject();
                    cal.put("id", cursor.getLong(0));
                    cal.put("displayName", strVal(cursor, 1));
                    cal.put("accountName", strVal(cursor, 2));
                    cal.put("accountType", strVal(cursor, 3));
                    cal.put("isPrimary", cursor.getInt(4) == 1);
                    cal.put("color", String.format("#%06X", 0xFFFFFF & cursor.getInt(5)));
                    arr.put(cal);
                }
            } finally {
                cursor.close();
            }
        }

        JSONObject result = new JSONObject();
        result.put("count", arr.length());
        result.put("calendars", arr);
        return result.toString();
    }

    // ==================== setAlarm ====================

    private String setAlarm(Context context, JSONObject params) throws Exception {
        int hour = params.optInt("hour", -1);
        int minute = params.optInt("minute", -1);
        if (hour < 0 || hour > 23) return errJson("Invalid hour (0-23)");
        if (minute < 0 || minute > 59) return errJson("Invalid minute (0-59)");

        String label = params.optString("label", "");
        String daysStr = params.optString("daysOfWeek", "").trim();

        Intent intent = new Intent(AlarmClock.ACTION_SET_ALARM);
        intent.putExtra(AlarmClock.EXTRA_HOUR, hour);
        intent.putExtra(AlarmClock.EXTRA_MINUTES, minute);
        if (!label.isEmpty()) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label);

        if (!daysStr.isEmpty()) {
            ArrayList<Integer> days = new ArrayList<>();
            for (String d : daysStr.split(",")) {
                int day = Integer.parseInt(d.trim());
                // Convert 1=Mon..7=Sun to Calendar constants (Calendar.MONDAY=2..SUNDAY=1)
                days.add(day == 7 ? Calendar.SUNDAY : day + 1);
            }
            intent.putExtra(AlarmClock.EXTRA_DAYS, days);
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);

        JSONObject result = new JSONObject();
        result.put("hour", hour);
        result.put("minute", minute);
        result.put("label", label);
        result.put("message", String.format("Alarm set for %02d:%02d", hour, minute));
        return result.toString();
    }

    // ==================== setTimer ====================

    private String setTimer(Context context, JSONObject params) throws Exception {
        int seconds = params.optInt("seconds", 0);
        if (seconds <= 0) return errJson("Missing or invalid parameter: seconds");

        String label = params.optString("label", "");

        Intent intent = new Intent(AlarmClock.ACTION_SET_TIMER);
        intent.putExtra(AlarmClock.EXTRA_LENGTH, seconds);
        if (!label.isEmpty()) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);

        int mins = seconds / 60;
        int secs = seconds % 60;
        JSONObject result = new JSONObject();
        result.put("seconds", seconds);
        result.put("display", mins > 0 ? mins + "m " + secs + "s" : secs + "s");
        result.put("label", label);
        result.put("message", "Timer started");
        return result.toString();
    }

    // ==================== createBirthdayReminder ====================

    private String createBirthdayReminder(Context context, JSONObject params) throws Exception {
        String name = params.optString("name", "").trim();
        String dateStr = params.optString("date", "").trim();
        int reminderMin = params.optInt("reminderMinutes", 1440);
        if (name.isEmpty()) return errJson("Missing parameter: name");
        if (dateStr.isEmpty()) return errJson("Missing parameter: date");

        Calendar cal = Calendar.getInstance();
        if (dateStr.length() <= 5) {
            // MM-dd format
            String[] parts = dateStr.split("-");
            cal.set(Calendar.MONTH, Integer.parseInt(parts[0]) - 1);
            cal.set(Calendar.DAY_OF_MONTH, Integer.parseInt(parts[1]));
        } else {
            cal.setTime(SDF_DATE.parse(dateStr));
        }
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);

        // If date has passed this year, set to this year still (will recur)
        int thisYear = Calendar.getInstance().get(Calendar.YEAR);
        cal.set(Calendar.YEAR, thisYear);

        long calId = getDefaultCalendarId(context);
        if (calId < 0) return errJson("No calendar account found on this device");

        String title = name + "'s Birthday";
        ContentValues cv = new ContentValues();
        cv.put(CalendarContract.Events.CALENDAR_ID, calId);
        cv.put(CalendarContract.Events.TITLE, title);
        cv.put(CalendarContract.Events.DESCRIPTION, "Birthday reminder for " + name);
        cv.put(CalendarContract.Events.DTSTART, cal.getTimeInMillis());
        cv.put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().getID());
        cv.put(CalendarContract.Events.ALL_DAY, 1);
        cv.put(CalendarContract.Events.RRULE, "FREQ=YEARLY");
        cv.put(CalendarContract.Events.DURATION, "P1D");
        cv.put(CalendarContract.Events.HAS_ALARM, 1);

        Uri uri = context.getContentResolver().insert(CalendarContract.Events.CONTENT_URI, cv);
        if (uri == null) return errJson("Failed to create birthday event");

        long eventId = ContentUris.parseId(uri);
        addReminder(context, eventId, reminderMin);

        JSONObject result = new JSONObject();
        result.put("eventId", eventId);
        result.put("name", name);
        result.put("date", String.format("%02d-%02d", cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)));
        result.put("repeat", "yearly");
        result.put("reminderMinutes", reminderMin);
        result.put("message", "Birthday reminder created for " + name);
        return result.toString();
    }

    // ==================== openCalendar ====================

    private String openCalendar(Context context, JSONObject params) throws Exception {
        String dateStr = params.optString("date", "").trim();

        long timeMs;
        if (dateStr.isEmpty()) {
            timeMs = System.currentTimeMillis();
        } else {
            timeMs = parseDateStart(dateStr);
        }

        Uri.Builder builder = CalendarContract.CONTENT_URI.buildUpon();
        builder.appendPath("time");
        ContentUris.appendId(builder, timeMs);

        Intent intent = new Intent(Intent.ACTION_VIEW).setData(builder.build());
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);

        JSONObject result = new JSONObject();
        result.put("opened", true);
        result.put("date", dateStr.isEmpty() ? "today" : dateStr);
        result.put("message", "Calendar opened");
        return result.toString();
    }

    // ==================== getUpcoming ====================

    private String getUpcoming(Context context, JSONObject params) throws Exception {
        int days = params.optInt("days", 7);
        if (days <= 0) days = 7;
        if (days > 365) days = 365;

        long startMs = System.currentTimeMillis();
        long endMs = startMs + (long) days * 86400000L;

        JSONObject result = queryEvents(context, startMs, endMs);
        result.put("daysAhead", days);
        return result.toString();
    }

    // ==================== Utility methods ====================

    private long getDefaultCalendarId(Context context) {
        String[] projection = {CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY};
        Cursor cursor = context.getContentResolver().query(
            CalendarContract.Calendars.CONTENT_URI, projection,
            CalendarContract.Calendars.VISIBLE + " = 1", null, null);

        long firstId = -1;
        if (cursor != null) {
            try {
                while (cursor.moveToNext()) {
                    long id = cursor.getLong(0);
                    if (firstId < 0) firstId = id;
                    if (cursor.getInt(1) == 1) return id;
                }
            } finally {
                cursor.close();
            }
        }
        return firstId;
    }

    private void addReminder(Context context, long eventId, int minutes) {
        ContentValues rv = new ContentValues();
        rv.put(CalendarContract.Reminders.EVENT_ID, eventId);
        rv.put(CalendarContract.Reminders.MINUTES, minutes);
        rv.put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT);
        context.getContentResolver().insert(CalendarContract.Reminders.CONTENT_URI, rv);
    }

    private long parseDateTime(String s) throws ParseException {
        return SDF_DATETIME.parse(s).getTime();
    }

    private long parseDateStart(String s) throws ParseException {
        return SDF_DATE.parse(s).getTime();
    }

    private long parseDateEnd(String s) throws ParseException {
        return SDF_DATE.parse(s).getTime() + 86400000L - 1;
    }

    private String buildRRule(String rule) {
        switch (rule) {
            case "daily":   return "FREQ=DAILY";
            case "weekly":  return "FREQ=WEEKLY";
            case "monthly": return "FREQ=MONTHLY";
            case "yearly":  return "FREQ=YEARLY";
            default:        return null;
        }
    }

    private void putIfNotEmpty(ContentValues cv, String key, String value) {
        if (value != null && !value.trim().isEmpty()) cv.put(key, value.trim());
    }

    private String strVal(Cursor c, int idx) {
        String v = c.getString(idx);
        return v != null ? v : "";
    }

    private String emptyJson(String v) { return v == null || v.trim().isEmpty() ? "{}" : v; }

    private String errJson(String msg) throws Exception {
        return new JSONObject().put("error", msg).toString();
    }

    private String ok(String output) throws Exception {
        return new JSONObject().put("success", true).put("output", output).toString();
    }

    private String ok(String output, String displayText) throws Exception {
        JSONObject r = new JSONObject().put("success", true).put("output", output);
        if (displayText != null && !displayText.isEmpty()) r.put("_displayText", displayText);
        return r.toString();
    }

    private String error(String message) throws Exception {
        return new JSONObject().put("success", false).put("error", message).toString();
    }

    private String formatCreateEventDisplay(String out) {
        try {
            JSONObject o = new JSONObject(out);
            if (o.has("error")) return "";
            String title = o.optString("title", "");
            String time = o.optString("startTime", "");
            return "📅 Event Created\n━━━━━━━━━━━━━━\n▸ Title: " + title + "\n▸ Time: " + time;
        } catch (Exception e) {
            return "";
        }
    }

    /** @param titlePrefix e.g. "📅 Upcoming Events" or "📅 Next Events" */
    private String formatListEventsDisplay(String out, String titlePrefix) {
        try {
            JSONObject o = new JSONObject(out);
            if (o.has("error")) return "";
            int count = o.optInt("count", 0);
            JSONArray events = o.optJSONArray("events");
            StringBuilder sb = new StringBuilder();
            sb.append(titlePrefix).append(" (").append(count).append(")\n━━━━━━━━━━━━━━\n");
            if (events != null) {
                for (int i = 0; i < events.length(); i++) {
                    JSONObject ev = events.getJSONObject(i);
                    String t = ev.optString("title", "(no title)");
                    String start = ev.optString("startTime", "");
                    sb.append(i + 1).append(". ").append(t).append(" (").append(start).append(")\n");
                }
            }
            String s = sb.toString();
            return s.endsWith("\n") ? s.substring(0, s.length() - 1) : s;
        } catch (Exception e) {
            return "";
        }
    }

    private String formatDeleteEventDisplay(String out) {
        try {
            JSONObject o = new JSONObject(out);
            if (o.has("error")) return "";
            if (o.optBoolean("deleted", false)) return "🗑️ Event deleted";
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    private String formatSetAlarmDisplay(String out) {
        try {
            JSONObject o = new JSONObject(out);
            if (o.has("error")) return "";
            int hour = o.optInt("hour", 0);
            int minute = o.optInt("minute", 0);
            return String.format(Locale.getDefault(), "⏰ Alarm Set\n▸ Time: %02d:%02d", hour, minute);
        } catch (Exception e) {
            return "";
        }
    }

    private String formatSetTimerDisplay(String out) {
        try {
            JSONObject o = new JSONObject(out);
            if (o.has("error")) return "";
            int seconds = o.optInt("seconds", 0);
            int mins = seconds / 60;
            int secs = seconds % 60;
            String duration;
            if (secs == 0) {
                duration = mins + (mins == 1 ? " minute" : " minutes");
            } else if (mins == 0) {
                duration = secs + (secs == 1 ? " second" : " seconds");
            } else {
                duration = mins + " min " + secs + " sec";
            }
            return "⏱️ Timer Set\n▸ Duration: " + duration;
        } catch (Exception e) {
            return "";
        }
    }

    private String formatBirthdayReminderDisplay(String out) {
        try {
            JSONObject o = new JSONObject(out);
            if (o.has("error")) return "";
            return "🎂 Birthday Reminder Created";
        } catch (Exception e) {
            return "";
        }
    }
}
