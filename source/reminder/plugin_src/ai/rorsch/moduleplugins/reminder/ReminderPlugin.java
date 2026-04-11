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

/**
 * PandaGenie 提醒与日历模块插件。
 * <p>
 * <b>模块用途：</b>通过 {@link android.provider.CalendarContract} 读写系统日历事件（创建/列表/更新/删除、生日每年重复、临近查询等）；
 * 通过 {@link android.provider.AlarmClock} 调起系统界面设置闹钟与倒计时；打开系统日历到指定日期。
 * </p>
 * <p>
 * <b>对外 API（{@link #invoke} 的 {@code action}）：</b>
 * {@code createEvent}、{@code listEvents}、{@code updateEvent}、{@code deleteEvent}、{@code getCalendars}、
 * {@code setAlarm}、{@code setTimer}、{@code createBirthdayReminder}、{@code openCalendar}、{@code getUpcoming}。
 * 成功时 {@code output} 内为业务 JSON；部分动作附带 {@code _displayText} 供界面展示。
 * </p>
 * <p>
 * 实现 {@link ModulePlugin}，由宿主 {@code ModuleRuntime} 反射加载并调用；需用户授予日历、闹钟等相关权限（由宿主与系统处理）。
 * </p>
 */
public class ReminderPlugin implements ModulePlugin {

    /** 事件起止时间解析与回显：{@code yyyy-MM-dd HH:mm}。 */
    private static final SimpleDateFormat SDF_DATETIME = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
    /** 仅日期：{@code yyyy-MM-dd}，用于按日查询区间。 */
    private static final SimpleDateFormat SDF_DATE = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
    /** 列表展示用完整日期时间：{@code yyyy-MM-dd HH:mm:ss}。 */
    private static final SimpleDateFormat SDF_DISPLAY = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

    private static boolean isZh() {
        try {
            return Locale.getDefault().getLanguage().toLowerCase(Locale.ROOT).startsWith("zh");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 模块入口：按 {@code action} 分发到日历或闹钟相关逻辑。
     *
     * @param context    用于 {@link ContentResolver} 与启动 Activity
     * @param action     支持的动作名见类说明
     * @param paramsJson 各动作对应的 JSON 参数字符串；空则按 {@code {}} 处理
     * @return 标准成功/失败 JSON；业务数据在 {@code output} 字符串内嵌套 JSON
     */
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
                return ok(out, formatListEventsDisplay(out, false));
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
                return ok(out, formatListEventsDisplay(out, true));
            }
            default:
                return error("Unsupported action: " + action);
        }
    }

    /**
     * 在默认可见日历中创建一条事件，可选重复规则与提前提醒分钟数。
     *
     * @param context 上下文
     * @param params  JSON：{@code title}、{@code startTime} 必填；{@code endTime}、{@code description}、{@code location}、
     *                {@code rrule}（{@code daily|weekly|monthly|yearly|none}）、{@code reminderMinutes} 等
     * @return 成功 JSON（含 {@code eventId} 等）或 {@link #errJson} 错误 JSON 字符串
     */
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
        // 未指定结束时间则默认持续 1 小时
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
        // 重复事件使用 DURATION 而非 DTEND（与 Android 日历契约一致）
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

    /**
     * 按日期区间列出日历实例（展开重复规则后的具体发生段）。
     *
     * @param context 上下文
     * @param params  JSON：{@code startDate} 必填（{@code yyyy-MM-dd}）；{@code endDate} 可选，默认 start 后一天
     * @return {@link #queryEvents} 的 JSON 字符串，或错误 JSON
     */
    private String listEvents(Context context, JSONObject params) throws Exception {
        String startStr = params.optString("startDate", "").trim();
        String endStr = params.optString("endDate", "").trim();
        if (startStr.isEmpty()) return errJson("Missing parameter: startDate");

        long startMs = parseDateStart(startStr);
        // 未指定结束日期则查询 start 当天整天
        long endMs = endStr.isEmpty() ? startMs + 86400000L : parseDateEnd(endStr);

        return queryEvents(context, startMs, endMs).toString();
    }

    /**
     * 查询 {@link CalendarContract.Instances} 在 [{@code startMs},{@code endMs}] 内的所有实例。
     *
     * @param context 上下文
     * @param startMs 区间起点（毫秒）
     * @param endMs   区间终点（毫秒）
     * @return 含 {@code count} 与 {@code events} 数组的 JSON 对象
     */
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

    /**
     * 按 {@code eventId} 更新事件的标题、描述、地点或起止时间（仅更新传入的非空字段）。
     *
     * @param context 上下文
     * @param params  JSON：{@code eventId} 必填；其它字段可选
     * @return 含 {@code updated} 布尔与提示文案的 JSON 字符串
     */
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

    /**
     * 按 {@code eventId} 删除一条日历事件。
     *
     * @param context 上下文
     * @param params  JSON：{@code eventId} 必填
     * @return 含 {@code deleted} 与 {@code message} 的 JSON 字符串
     */
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

    /**
     * 列出设备上可见的日历账户（id、显示名、账号、是否主日历、颜色等）。
     *
     * @param context 上下文
     * @return 含 {@code calendars} 数组与 {@code count} 的 JSON 字符串
     */
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

    /**
     * 调起系统「设置闹钟」界面（{@link AlarmClock#ACTION_SET_ALARM}），由用户确认保存。
     *
     * @param context 上下文
     * @param params  JSON：{@code hour}（0–23）、{@code minute}（0–59）必填；{@code label} 可选；
     *                {@code daysOfWeek} 可选，逗号分隔 1=周一…7=周日
     * @return 回显所设时间与标签的 JSON 字符串
     */
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
                // 入参 1=周一…7=周日 → Calendar 常量（周一=2 … 周日=1）
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

    /**
     * 调起系统「倒计时」界面（{@link AlarmClock#ACTION_SET_TIMER}）。
     *
     * @param context 上下文
     * @param params  JSON：{@code seconds} 正整数时长；{@code label} 可选
     * @return 含秒数、可读时长与提示的 JSON 字符串
     */
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

    /**
     * 创建每年重复的全天生日提醒事件，并附加提前 {@code reminderMinutes} 的提醒。
     *
     * @param context 上下文
     * @param params  JSON：{@code name}、{@code date} 必填（{@code MM-dd} 或 {@code yyyy-MM-dd}）；{@code reminderMinutes} 默认 1440（一天）
     * @return 成功时含 {@code eventId}、月日、重复方式等；失败为 {@link #errJson}
     */
    private String createBirthdayReminder(Context context, JSONObject params) throws Exception {
        String name = params.optString("name", "").trim();
        String dateStr = params.optString("date", "").trim();
        int reminderMin = params.optInt("reminderMinutes", 1440);
        if (name.isEmpty()) return errJson("Missing parameter: name");
        if (dateStr.isEmpty()) return errJson("Missing parameter: date");

        Calendar cal = Calendar.getInstance();
        if (dateStr.length() <= 5) {
            // 短格式：月-日，不含年份
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

        // 固定为当前公历年；若今年该日已过，仍创建当年日期，由 RRULE 每年重复覆盖后续年份
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

    /**
     * 使用系统日历应用打开指定日期视图；未传日期则打开「今天」。
     *
     * @param context 上下文
     * @param params  JSON：{@code date} 可选，{@code yyyy-MM-dd}
     * @return 含 {@code opened}、{@code date} 描述与 {@code message} 的 JSON
     */
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

    /**
     * 查询从当前时刻起未来若干天内的日历实例（含今天之后）。
     *
     * @param context 上下文
     * @param params  JSON：{@code days} 默认 7，最大 365
     * @return 在 {@link #queryEvents} 结果上附加 {@code daysAhead}
     */
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

    /**
     * 获取用于写入事件的默认日历 ID：优先主日历，否则取第一个可见日历。
     *
     * @param context 上下文
     * @return 日历 {@code _ID}；无可用日历时返回 -1
     */
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

    /**
     * 为指定事件插入一条弹出提醒（{@link CalendarContract.Reminders#METHOD_ALERT}）。
     *
     * @param context 上下文
     * @param eventId 事件 ID
     * @param minutes 提前多少分钟提醒（可为 0 表示准时）
     */
    private void addReminder(Context context, long eventId, int minutes) {
        ContentValues rv = new ContentValues();
        rv.put(CalendarContract.Reminders.EVENT_ID, eventId);
        rv.put(CalendarContract.Reminders.MINUTES, minutes);
        rv.put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT);
        context.getContentResolver().insert(CalendarContract.Reminders.CONTENT_URI, rv);
    }

    /**
     * 解析 {@code yyyy-MM-dd HH:mm} 为 UTC 毫秒时间戳（按默认时区解释）。
     */
    private long parseDateTime(String s) throws ParseException {
        return SDF_DATETIME.parse(s).getTime();
    }

    /**
     * 解析日期字符串为当天 00:00:00 的起始毫秒。
     */
    private long parseDateStart(String s) throws ParseException {
        return SDF_DATE.parse(s).getTime();
    }

    /**
     * 解析日期字符串为该日最后一毫秒（用于区间上界包含整天）。
     */
    private long parseDateEnd(String s) throws ParseException {
        return SDF_DATE.parse(s).getTime() + 86400000L - 1;
    }

    /**
     * 将简写重复类型转为 iCalendar RRULE 字符串；{@code none} 等返回 null 表示不重复。
     *
     * @param rule {@code daily|weekly|monthly|yearly|none} 等（已小写）
     * @return RRULE 或 null
     */
    private String buildRRule(String rule) {
        switch (rule) {
            case "daily":   return "FREQ=DAILY";
            case "weekly":  return "FREQ=WEEKLY";
            case "monthly": return "FREQ=MONTHLY";
            case "yearly":  return "FREQ=YEARLY";
            default:        return null;
        }
    }

    /**
     * 若 {@code value} 非空则 {@code trim} 后写入 {@link ContentValues}。
     */
    private void putIfNotEmpty(ContentValues cv, String key, String value) {
        if (value != null && !value.trim().isEmpty()) cv.put(key, value.trim());
    }

    /**
     * 从 {@link Cursor} 读取字符串列，null 转为空串。
     */
    private String strVal(Cursor c, int idx) {
        String v = c.getString(idx);
        return v != null ? v : "";
    }

    /**
     * 将 null 或仅空白的 {@code paramsJson} 规范化为 {@code "{}"}，避免 {@link JSONObject} 构造失败。
     *
     * @param v 原始参数字符串
     * @return 非空则原样返回，否则 {@code "{}"}
     */
    private String emptyJson(String v) { return v == null || v.trim().isEmpty() ? "{}" : v; }

    /**
     * 业务层错误 JSON（仅含 {@code error}），随后会被包在 {@code success=true} 的 {@code output} 里由上层展示。
     */
    private String errJson(String msg) throws Exception {
        return new JSONObject().put("error", msg).toString();
    }

    /**
     * 成功响应，无额外展示文案。
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
     * @param displayText 可为 null；非空则写入 {@code _displayText}
     */
    private String ok(String output, String displayText) throws Exception {
        JSONObject r = new JSONObject().put("success", true).put("output", output);
        if (displayText != null && !displayText.isEmpty()) r.put("_displayText", displayText);
        return r.toString();
    }

    /**
     * 顶层失败：{@code success=false} 与 {@code error}。
     */
    private String error(String message) throws Exception {
        return new JSONObject().put("success", false).put("error", message).toString();
    }

    /**
     * 根据 {@code createEvent} 的 output 生成简短成功展示文案。
     *
     * @param out {@code createEvent} 返回的 JSON 字符串
     * @return 展示文本；若含 {@code error} 则返回空串
     */
    private String formatCreateEventDisplay(String out) {
        try {
            JSONObject o = new JSONObject(out);
            if (o.has("error")) return "";
            String title = o.optString("title", "");
            String time = o.optString("startTime", "");
            if (isZh()) {
                return "📅 事件已创建\n━━━━━━━━━━━━━━\n▸ 标题: " + title + "\n▸ 时间: " + time;
            }
            return "📅 Event Created\n━━━━━━━━━━━━━━\n▸ Title: " + title + "\n▸ Time: " + time;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 将事件列表 JSON 格式化为多行展示。
     *
     * @param out         {@code listEvents} 或 {@code getUpcoming} 的 output JSON
     * @param nextEventsLabelEnglish {@code true} 时英文标题为 Next Events；{@code false} 时为 Upcoming Events。中文均用「即将到来的事件」。
     * @return 展示文本；错误时为空串
     */
    private String formatListEventsDisplay(String out, boolean nextEventsLabelEnglish) {
        try {
            JSONObject o = new JSONObject(out);
            if (o.has("error")) return "";
            int count = o.optInt("count", 0);
            JSONArray events = o.optJSONArray("events");
            StringBuilder sb = new StringBuilder();
            String titlePrefix;
            if (isZh()) {
                titlePrefix = "📅 即将到来的事件";
            } else {
                titlePrefix = nextEventsLabelEnglish ? "📅 Next Events" : "📅 Upcoming Events";
            }
            sb.append(titlePrefix).append(" (").append(count).append(")\n━━━━━━━━━━━━━━\n");
            if (events != null) {
                for (int i = 0; i < events.length(); i++) {
                    JSONObject ev = events.getJSONObject(i);
                    String t = ev.optString("title", isZh() ? "（无标题）" : "(no title)");
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

    /**
     * 删除成功时返回简短提示。
     *
     * @param out {@code deleteEvent} 的 output
     */
    private String formatDeleteEventDisplay(String out) {
        try {
            JSONObject o = new JSONObject(out);
            if (o.has("error")) return "";
            if (o.optBoolean("deleted", false)) return isZh() ? "🗑️ 事件已删除" : "🗑️ Event deleted";
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 闹钟设置后的时间展示文案。
     *
     * @param out {@code setAlarm} 的 output
     */
    private String formatSetAlarmDisplay(String out) {
        try {
            JSONObject o = new JSONObject(out);
            if (o.has("error")) return "";
            int hour = o.optInt("hour", 0);
            int minute = o.optInt("minute", 0);
            if (isZh()) {
                return String.format(Locale.getDefault(), "⏰ 闹钟已设置\n▸ 时间: %02d:%02d", hour, minute);
            }
            return String.format(Locale.getDefault(), "⏰ Alarm Set\n▸ Time: %02d:%02d", hour, minute);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 倒计时启动后的时长展示文案。
     *
     * @param out {@code setTimer} 的 output
     */
    private String formatSetTimerDisplay(String out) {
        try {
            JSONObject o = new JSONObject(out);
            if (o.has("error")) return "";
            int seconds = o.optInt("seconds", 0);
            int mins = seconds / 60;
            int secs = seconds % 60;
            String duration;
            if (isZh()) {
                if (secs == 0) {
                    duration = mins + " 分钟";
                } else if (mins == 0) {
                    duration = secs + " 秒";
                } else {
                    duration = mins + " 分钟 " + secs + " 秒";
                }
                return "⏱️ 定时器已设置\n▸ 时长: " + duration;
            }
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

    /**
     * 生日提醒创建成功的固定短提示。
     *
     * @param out {@code createBirthdayReminder} 的 output
     */
    private String formatBirthdayReminderDisplay(String out) {
        try {
            JSONObject o = new JSONObject(out);
            if (o.has("error")) return "";
            return isZh() ? "🎂 生日提醒已创建" : "🎂 Birthday Reminder Created";
        } catch (Exception e) {
            return "";
        }
    }
}
