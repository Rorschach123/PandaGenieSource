package ai.rorsch.moduleplugins.fortune;

import android.content.Context;
import ai.rorsch.pandagenie.module.runtime.ModulePlugin;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * PandaGenie「运势」娱乐模块插件。
 * <p>
 * <b>模块用途：</b>基于公历日期与可选姓名生成<strong>确定性伪随机</strong>的每日运势文案（吉凶等级、吉语、宜忌、幸运数字/颜色），
 * 并提供公历到农历（1900–2100 年范围）的换算，仅供互动展示，无占卜或预测科学依据。
 * </p>
 * <p>
 * <b>对外 API（{@code action}）：</b>{@code getDailyFortune}（今日运势）、{@code getFortuneByDate}（指定日）、
 * {@code getLunarDate}（农历信息）、{@code getFortuneLevel}（按等级查询词库）。
 * </p>
 * <p>
 * 实现 {@link ModulePlugin}，由 {@code ModuleRuntime} 反射实例化并调用 {@link #invoke}。
 * </p>
 */
public class FortunePlugin implements ModulePlugin {

    /** 七个等级的中文名称，与 {@link #FORTUNE_WORDS} 等数组下标一一对应（1-based level 时需减 1） */
    private static final String[] LEVEL_NAMES_ZH = {"大吉", "吉", "中吉", "小吉", "末吉", "凶", "大凶"};
    private static final String[] LEVEL_NAMES_EN = {"Great Luck", "Good Luck", "Medium Luck", "Small Luck", "End Luck", "Bad Luck", "Terrible Luck"};
    private static final String[] LEVEL_SYMBOLS = {"☀", "🌤", "⛅", "🌥", "☁", "🌧", "⛈"};

    private static final String[][] FORTUNE_WORDS = {
        {"万事如意", "心想事成", "鸿运当头", "紫气东来", "春风得意", "锦绣前程", "一帆风顺", "吉星高照"},
        {"步步高升", "称心如意", "顺风顺水", "好事连连", "柳暗花明", "否极泰来", "渐入佳境", "时来运转"},
        {"平稳向好", "稳中有升", "守得云开", "循序渐进", "脚踏实地", "水到渠成", "有条不紊", "按部就班"},
        {"小有收获", "略有起色", "尚可期待", "平淡是福", "知足常乐", "细水长流", "一步一印", "聚沙成塔"},
        {"波澜不惊", "不温不火", "淡然处之", "随遇而安", "静观其变", "以退为进", "韬光养晦", "厚积薄发"},
        {"谨慎行事", "三思后行", "低调为宜", "未雨绸缪", "防患未然", "居安思危", "留有余地", "以静制动"},
        {"暂避锋芒", "蛰伏待机", "退一步海阔天空", "忍一时风平浪静", "养精蓄锐", "卧薪尝胆", "塞翁失马", "否极泰来"}
    };

    private static final String[][] ADVICE_DO = {
        {"开业签约", "投资理财", "表白求婚", "远行出游", "考试面试"},
        {"学习充电", "社交聚会", "运动健身", "整理收纳", "探亲访友"},
        {"读书思考", "早睡早起", "散步冥想", "写日记", "喝茶养生"},
        {"做家务", "逛超市", "看电影", "听音乐", "慢跑散步"},
        {"宅家休息", "回顾总结", "陪伴家人", "整理思绪", "静心阅读"},
        {"保守行事", "检查细节", "备份资料", "储蓄节俭", "养生保健"},
        {"闭门不出", "多加休息", "静养身心", "反思自省", "修炼内功"}
    };

    private static final String[][] ADVICE_DONT = {
        {"无需忌讳", "尽管放手去做", "万事皆宜"},
        {"冲动消费", "过度劳累", "与人争执"},
        {"做重大决定", "过于激进", "轻信他人"},
        {"贪多求快", "盲目跟风", "情绪化行事"},
        {"冒险投机", "强出风头", "仓促行动"},
        {"签约合同", "远行冒险", "高风险操作"},
        {"投资理财", "与人冲突", "做重大决策", "出远门"}
    };

    private static final String[] LUCKY_COLORS = {
        "金色", "红色", "紫色", "蓝色", "绿色", "白色", "橙色",
        "粉色", "银色", "青色", "黄色", "棕色"
    };

    private static final int[] HEAVENLY_STEMS_YEAR_OFFSET = {6, 7, 8, 9, 0, 1, 2, 3, 4, 5};
    private static final String[] HEAVENLY_STEMS = {"甲", "乙", "丙", "丁", "戊", "己", "庚", "辛", "壬", "癸"};
    private static final String[] EARTHLY_BRANCHES = {"子", "丑", "寅", "卯", "辰", "巳", "午", "未", "申", "酉", "戌", "亥"};
    private static final String[] ZODIAC_ANIMALS = {"鼠", "牛", "虎", "兔", "龙", "蛇", "马", "羊", "猴", "鸡", "狗", "猪"};
    private static final String[] LUNAR_MONTHS = {"正", "二", "三", "四", "五", "六", "七", "八", "九", "十", "冬", "腊"};
    private static final String[] LUNAR_DAYS_PREFIX = {"初", "初", "初", "初", "初", "初", "初", "初", "初", "初",
                                                        "十", "十", "十", "十", "十", "十", "十", "十", "十", "十",
                                                        "廿", "廿", "廿", "廿", "廿", "廿", "廿", "廿", "廿", "三十"};
    private static final String[] LUNAR_DAYS_SUFFIX = {"", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十",
                                                        "一", "二", "三", "四", "五", "六", "七", "八", "九", "十",
                                                        "一", "二", "三", "四", "五", "六", "七", "八", "九", ""};

    private static final int[] LUNAR_MONTH_DAYS = {29, 30};
    /**
     * 农历压缩编码表：每年一条整型位图，用于 {@link #lunarYearDays}、{@link #leapMonth}、{@link #monthDays} 等推算。
     * 数据覆盖 1900 年起若干年的朔望月与闰月信息（常见开源农历算法数据源）。
     */
    private static final int[] LUNAR_INFO = {
        0x04bd8, 0x04ae0, 0x0a570, 0x054d5, 0x0d260, 0x0d950, 0x16554, 0x056a0, 0x09ad0, 0x055d2,
        0x04ae0, 0x0a5b6, 0x0a4d0, 0x0d250, 0x1d255, 0x0b540, 0x0d6a0, 0x0ada2, 0x095b0, 0x14977,
        0x04970, 0x0a4b0, 0x0b4b5, 0x06a50, 0x06d40, 0x1ab54, 0x02b60, 0x09570, 0x052f2, 0x04970,
        0x06566, 0x0d4a0, 0x0ea50, 0x16a95, 0x05ad0, 0x02b60, 0x186e3, 0x092e0, 0x1c8d7, 0x0c950,
        0x0d4a0, 0x1d8a6, 0x0b550, 0x056a0, 0x1a5b4, 0x025d0, 0x092d0, 0x0d2b2, 0x0a950, 0x0b557,
        0x06ca0, 0x0b550, 0x15355, 0x04da0, 0x0a5b0, 0x14573, 0x052b0, 0x0a9a8, 0x0e950, 0x06aa0,
        0x0aea6, 0x0ab50, 0x04b60, 0x0aae4, 0x0a570, 0x05260, 0x0f263, 0x0d950, 0x05b57, 0x056a0,
        0x096d0, 0x04dd5, 0x04ad0, 0x0a4d0, 0x0d4d4, 0x0d250, 0x0d558, 0x0b540, 0x0b6a0, 0x195a6,
        0x095b0, 0x049b0, 0x0a974, 0x0a4b0, 0x0b27a, 0x06a50, 0x06d40, 0x0af46, 0x0ab60, 0x09570,
        0x04af5, 0x04970, 0x064b0, 0x074a3, 0x0ea50, 0x06b58, 0x05ac0, 0x0ab60, 0x096d5, 0x092e0,
        0x0c960, 0x0d954, 0x0d4a0, 0x0da50, 0x07552, 0x056a0, 0x0abb7, 0x025d0, 0x092d0, 0x0cab5,
        0x0a950, 0x0b4a0, 0x0baa4, 0x0ad50, 0x055d9, 0x04ba0, 0x0a5b0, 0x15176, 0x052b0, 0x0a930,
        0x07954, 0x06aa0, 0x0ad50, 0x05b52, 0x04b60, 0x0a6e6, 0x0a4e0, 0x0d260, 0x0ea65, 0x0d530,
        0x05aa0, 0x076a3, 0x096d0, 0x04afb, 0x04ad0, 0x0a4d0, 0x1d0b6, 0x0d250, 0x0d520, 0x0dd45,
        0x0b5a0, 0x056d0, 0x055b2, 0x049b0, 0x0a577, 0x0a4b0, 0x0aa50, 0x1b255, 0x06d20, 0x0ada0,
        0x14b63, 0x09370, 0x049f8, 0x04970, 0x064b0, 0x168a6, 0x0ea50, 0x06aa0, 0x1a6c4, 0x0aae0,
        0x092e0, 0x0d2e3, 0x0c960, 0x0d557, 0x0d4a0, 0x0da50, 0x05d55, 0x056a0, 0x0a6d0, 0x055d4,
        0x052d0, 0x0a9b8, 0x0a950, 0x0b4a0, 0x0b6a6, 0x0ad50, 0x055a0, 0x0aba4, 0x0a5b0, 0x052b0,
        0x0b273, 0x06930, 0x07337, 0x06aa0, 0x0ad50, 0x14b55, 0x04b60, 0x0a570, 0x054e4, 0x0d160,
        0x0e968, 0x0d520, 0x0daa0, 0x16aa6, 0x056d0, 0x04ae0, 0x0a9d4, 0x0a4d0, 0x0d150, 0x0f252,
        0x0d520
    };

    /**
     * 模块入口：解析 JSON 参数并分发到具体运势或农历计算逻辑。
     *
     * @param context    Android 上下文（当前实现未使用，为接口统一保留）
     * @param action     操作名称
     * @param paramsJson JSON 参数字符串，空则按 {@code {}}
     * @return 含 {@code success} 与 {@code output} 的 JSON；部分成功结果附带 {@code _displayText}
     * @throws Exception 内部已捕获一般异常并转为 {@code error} 响应；JSON 构造仍可能抛出
     */
    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        try {
            JSONObject params = new JSONObject(emptyJson(paramsJson));
            switch (action) {
                case "getDailyFortune": {
                    JSONObject r = getDailyFortune(params.optString("name", ""));
                    return ok(r, formatFortuneDisplay(r));
                }
                case "getFortuneByDate": {
                    JSONObject r = getFortuneByDate(params.optString("date", ""), params.optString("name", ""));
                    return ok(r, formatFortuneDisplay(r));
                }
                case "getLunarDate":
                    return ok(getLunarDate(params.optString("date", "")));
                case "getFortuneLevel":
                    return ok(getFortuneLevel(params.optInt("level", 0)));
                default:
                    return error("Unsupported action: " + action);
            }
        } catch (Exception e) {
            String msg = e.getMessage();
            return error(msg != null && !msg.isEmpty() ? msg : e.getClass().getSimpleName());
        }
    }

    /**
     * 将运势结果 JSON 格式化为适合聊天窗口展示的多行文本（含农历摘要）。
     *
     * @param r {@link #buildFortune} 等生成的结果对象
     * @return 展示字符串
     * @throws Exception 一般不会；为与调用链签名一致保留
     */
    private String formatFortuneDisplay(JSONObject r) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("\uD83D\uDD2E 每日运势 (").append(r.optString("date", "")).append(")\n");
        sb.append("━━━━━━━━━━━━━━━━━━━\n");
        sb.append(r.optString("symbol", "")).append(" ").append(r.optString("levelName", ""));
        sb.append(" \u2014 ").append(r.optString("fortuneWord", "")).append("\n\n");
        sb.append("\uD83D\uDD22 幸运数字: ").append(r.optInt("luckyNumber", 0)).append("\n");
        sb.append("\uD83C\uDFA8 幸运颜色: ").append(r.optString("luckyColor", "")).append("\n");
        sb.append("\u2705 宜: ").append(r.optString("adviceDo", "")).append("\n");
        sb.append("\u274C 忌: ").append(r.optString("adviceDont", "")).append("\n");

        JSONObject lunar = r.optJSONObject("lunar");
        if (lunar != null) {
            sb.append("\n\uD83D\uDCC5 ").append(lunar.optString("lunarDateString", ""));
        }
        return sb.toString();
    }

    /**
     * 使用设备默认时区的「今天」公历日期生成运势。
     *
     * @param name 可选昵称，参与种子计算使每人同日结果可不同；空则仅按日期
     * @return 完整运势 JSON（含 {@code lunar} 子对象）
     * @throws Exception 日期或农历计算异常
     */
    private JSONObject getDailyFortune(String name) throws Exception {
        Calendar cal = Calendar.getInstance(TimeZone.getDefault());
        String date = String.format(Locale.ROOT, "%04d-%02d-%02d",
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
        return buildFortune(date, name, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
    }

    /**
     * 按指定公历日期（{@code YYYY-MM-DD}）生成运势；日期为空则回退为 {@link #getDailyFortune}。
     *
     * @param date 公历日期字符串；空或 null 表示使用今天
     * @param name 可选姓名种子
     * @return 运势 JSON
     * @throws Exception 日期格式非法或超出农历支持范围时
     */
    private JSONObject getFortuneByDate(String date, String name) throws Exception {
        if (date == null || date.trim().isEmpty()) {
            return getDailyFortune(name);
        }
        int[] parsed = parseDate(date.trim());
        return buildFortune(date.trim(), name, parsed[0], parsed[1], parsed[2]);
    }

    /**
     * 核心组装：用日期与姓名混合种子，在固定词库中选取等级、吉语、宜忌与幸运项，并附上农历。
     *
     * @param dateStr 展示用日期字符串（已与 year/month/day 一致）
     * @param name    用户昵称，影响伪随机序列
     * @param year    公历年
     * @param month   公历月 1–12
     * @param day     公历日
     * @return 包含 level、文案、lucky*、advice*、lunar 等字段的 {@link JSONObject}
     * @throws Exception 农历计算可能抛出
     */
    private JSONObject buildFortune(String dateStr, String name, int year, int month, int day) throws Exception {
        // 种子 = 日期数值 + 姓名散列，保证同一用户同一天结果稳定、不同天或不同名可变化
        long seed = dateSeed(year, month, day) + nameHash(name);
        int level = seededInt(seed, 7, 1) + 1;
        int wordIdx = seededInt(seed, FORTUNE_WORDS[level - 1].length, 2);
        int luckyNum = seededInt(seed, 99, 3) + 1;
        int colorIdx = seededInt(seed, LUCKY_COLORS.length, 4);
        int doIdx = seededInt(seed, ADVICE_DO[level - 1].length, 5);
        int dontIdx = seededInt(seed, ADVICE_DONT[level - 1].length, 6);

        JSONObject lunar = computeLunar(year, month, day);

        JSONObject result = new JSONObject();
        result.put("date", dateStr);
        result.put("level", level);
        result.put("levelName", LEVEL_NAMES_ZH[level - 1]);
        result.put("levelName_en", LEVEL_NAMES_EN[level - 1]);
        result.put("symbol", LEVEL_SYMBOLS[level - 1]);
        result.put("fortuneWord", FORTUNE_WORDS[level - 1][wordIdx]);
        result.put("luckyNumber", luckyNum);
        result.put("luckyColor", LUCKY_COLORS[colorIdx]);
        result.put("adviceDo", ADVICE_DO[level - 1][doIdx]);
        result.put("adviceDont", ADVICE_DONT[level - 1][dontIdx]);
        result.put("lunar", lunar);
        return result;
    }

    /**
     * 仅查询公历日期对应的农历信息；{@code date} 为空则取本机当天。
     *
     * @param date {@code YYYY-MM-DD} 或空
     * @return {@link #computeLunar} 的结果
     * @throws Exception 年份或日期非法
     */
    private JSONObject getLunarDate(String date) throws Exception {
        int year, month, day;
        if (date == null || date.trim().isEmpty()) {
            Calendar cal = Calendar.getInstance(TimeZone.getDefault());
            year = cal.get(Calendar.YEAR);
            month = cal.get(Calendar.MONTH) + 1;
            day = cal.get(Calendar.DAY_OF_MONTH);
        } else {
            int[] parsed = parseDate(date.trim());
            year = parsed[0];
            month = parsed[1];
            day = parsed[2];
        }
        return computeLunar(year, month, day);
    }

    /**
     * 按 1–7 的运势等级返回该等级下全部吉语、宜、忌及中英文名称、符号。
     *
     * @param level 1（大吉）到 7（大凶）
     * @return 词库汇总 JSON
     * @throws IllegalArgumentException level 越界
     */
    private JSONObject getFortuneLevel(int level) throws Exception {
        if (level < 1 || level > 7) {
            throw new IllegalArgumentException("level must be 1-7");
        }
        JSONObject result = new JSONObject();
        result.put("level", level);
        result.put("name", LEVEL_NAMES_ZH[level - 1]);
        result.put("name_en", LEVEL_NAMES_EN[level - 1]);
        result.put("symbol", LEVEL_SYMBOLS[level - 1]);
        JSONArray words = new JSONArray();
        for (String w : FORTUNE_WORDS[level - 1]) {
            words.put(w);
        }
        result.put("fortuneWords", words);
        JSONArray dos = new JSONArray();
        for (String d : ADVICE_DO[level - 1]) {
            dos.put(d);
        }
        result.put("adviceDo", dos);
        JSONArray donts = new JSONArray();
        for (String d : ADVICE_DONT[level - 1]) {
            donts.put(d);
        }
        result.put("adviceDont", donts);
        return result;
    }

    /**
     * 公历转农历：以 1900-01-31 为农历正月初一基准，按 {@link #LUNAR_INFO} 逐月累加推算年月日及闰月、干支、生肖。
     *
     * @param solarYear  公历年，支持约 1900–2100（与数据表一致）
     * @param solarMonth 公历月 1–12
     * @param solarDay   公历日
     * @return 含 lunarYear/Month/Day、是否闰月、干支年、生肖、可读 {@code lunarDateString} 等
     * @throws Exception 年份越界或早于基准日
     */
    private JSONObject computeLunar(int solarYear, int solarMonth, int solarDay) throws Exception {
        if (solarYear < 1900 || solarYear > 2100) {
            throw new IllegalArgumentException("Year must be 1900-2100");
        }
        Calendar baseDate = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        baseDate.set(1900, 0, 31, 0, 0, 0);
        baseDate.set(Calendar.MILLISECOND, 0);

        Calendar targetDate = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        targetDate.set(solarYear, solarMonth - 1, solarDay, 0, 0, 0);
        targetDate.set(Calendar.MILLISECOND, 0);

        // 与基准日的天数差，用于在农历年中逐月扣减定位月日
        int offset = (int) ((targetDate.getTimeInMillis() - baseDate.getTimeInMillis()) / 86400000L);
        if (offset < 0) {
            throw new IllegalArgumentException("Date before 1900-01-31 is not supported");
        }

        int lunarYear = 0, lunarMonth = 0, lunarDay = 0;
        boolean isLeap = false;

        for (int y = 0; y < LUNAR_INFO.length; y++) {
            int yearDays = lunarYearDays(y);
            if (offset < yearDays) {
                lunarYear = 1900 + y;
                int leapMonth = leapMonth(y);
                boolean leapProcessed = false;
                for (int m = 1; m <= 12; m++) {
                    int mDays;
                    if (leapMonth > 0 && m == leapMonth + 1 && !leapProcessed) {
                        mDays = leapDays(y);
                        leapProcessed = true;
                        isLeap = true;
                        m--;
                    } else {
                        mDays = monthDays(y, m);
                        isLeap = false;
                    }
                    if (offset < mDays) {
                        lunarMonth = m;
                        lunarDay = offset + 1;
                        break;
                    }
                    offset -= mDays;
                }
                break;
            }
            offset -= yearDays;
        }

        int stemIdx = (lunarYear - 4) % 10;
        int branchIdx = (lunarYear - 4) % 12;

        JSONObject result = new JSONObject();
        result.put("lunarYear", lunarYear);
        result.put("lunarMonth", lunarMonth);
        result.put("lunarDay", lunarDay);
        result.put("isLeapMonth", isLeap);
        result.put("heavenlyStem", HEAVENLY_STEMS[stemIdx]);
        result.put("earthlyBranch", EARTHLY_BRANCHES[branchIdx]);
        result.put("ganZhi", HEAVENLY_STEMS[stemIdx] + EARTHLY_BRANCHES[branchIdx] + "年");
        result.put("zodiac", ZODIAC_ANIMALS[branchIdx]);
        result.put("lunarMonthName", (isLeap ? "闰" : "") + LUNAR_MONTHS[lunarMonth - 1] + "月");
        result.put("lunarDayName", lunarDayName(lunarDay));
        result.put("lunarDateString", HEAVENLY_STEMS[stemIdx] + EARTHLY_BRANCHES[branchIdx] + "年 "
                + (isLeap ? "闰" : "") + LUNAR_MONTHS[lunarMonth - 1] + "月" + lunarDayName(lunarDay));
        return result;
    }

    /**
     * 农历日期的传统读法（初十、二十、三十等）。
     *
     * @param day 农历日 1–30
     * @return 中文日名
     */
    private String lunarDayName(int day) {
        if (day == 10) return "初十";
        if (day == 20) return "二十";
        if (day == 30) return "三十";
        return LUNAR_DAYS_PREFIX[day - 1] + LUNAR_DAYS_SUFFIX[day];
    }

    /**
     * 计算农历年第 {@code y} 条记录对应年的总天数（含闰月天数）。
     *
     * @param y {@link #LUNAR_INFO} 下标，0 表示 1900 年
     * @return 该农历年天数
     */
    private int lunarYearDays(int y) {
        int sum = 348;
        for (int i = 0x8000; i > 0x8; i >>= 1) {
            sum += (LUNAR_INFO[y] & i) != 0 ? 1 : 0;
        }
        return sum + leapDays(y);
    }

    /**
     * 从压缩数据中取闰月月份：低 4 位为闰几月，0 表示无闰月。
     *
     * @param y 农历数据行下标
     * @return 闰月月份，0 表示无
     */
    private int leapMonth(int y) {
        return LUNAR_INFO[y] & 0xf;
    }

    /**
     * 闰月天数：由高位标志决定 29 或 30 天。
     *
     * @param y 农历数据行下标
     * @return 闰月天数，无闰月为 0
     */
    private int leapDays(int y) {
        if (leapMonth(y) == 0) return 0;
        return (LUNAR_INFO[y] & 0x10000) != 0 ? 30 : 29;
    }

    /**
     * 平月天数：根据位图判断该月 29 或 30 天。
     *
     * @param y 年下标
     * @param m 农历月 1–12
     * @return 该月天数
     */
    private int monthDays(int y, int m) {
        return (LUNAR_INFO[y] & (0x10000 >> m)) != 0 ? 30 : 29;
    }

    /**
     * 将公历日期压成单一长整型，作为运势伪随机的主种子分量。
     *
     * @param year  年
     * @param month 月
     * @param day   日
     * @return 形如 {@code yyyymmdd} 的数值
     */
    private long dateSeed(int year, int month, int day) {
        return year * 10000L + month * 100L + day;
    }

    /**
     * 简单字符串散列（类似乘法哈希），空名返回 0。
     *
     * @param name 用户输入姓名
     * @return 非负偏移量，加在日期种子上
     */
    private long nameHash(String name) {
        if (name == null || name.trim().isEmpty()) return 0;
        long hash = 0;
        for (int i = 0; i < name.length(); i++) {
            hash = hash * 31 + name.charAt(i);
        }
        return Math.abs(hash);
    }

    /**
     * 线性同余混合后取模，从同一种子得到不同维度的伪随机整数（依赖 {@code salt} 区分用途）。
     *
     * @param seed  基础种子
     * @param bound 上界（结果范围为 {@code [0, bound)}）
     * @param salt  盐值，避免不同字段取到相同序列
     * @return {@code [0, bound)} 的整数
     */
    private int seededInt(long seed, int bound, int salt) {
        long mixed = seed * 6364136223846793005L + salt * 1442695040888963407L;
        return (int) (Math.abs(mixed) % bound);
    }

    /**
     * 解析 {@code YYYY-MM-DD} 格式的公历日期字符串。
     *
     * @param date 输入字符串
     * @return 长度为 3 的数组 {@code [year, month, day]}
     * @throws IllegalArgumentException 格式或数值非法
     */
    private int[] parseDate(String date) {
        String[] parts = date.split("-");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Date format must be YYYY-MM-DD");
        }
        int y = Integer.parseInt(parts[0]);
        int m = Integer.parseInt(parts[1]);
        int d = Integer.parseInt(parts[2]);
        if (m < 1 || m > 12 || d < 1 || d > 31) {
            throw new IllegalArgumentException("Invalid date: " + date);
        }
        return new int[]{y, m, d};
    }

    /**
     * @param value 原始 JSON 字符串
     * @return 空则 {@code "{}"}，否则原样
     */
    private String emptyJson(String value) {
        return value == null || value.trim().isEmpty() ? "{}" : value;
    }

    /**
     * 成功响应：{@code output} 为对象序列化字符串，无展示文案。
     *
     * @param output 业务 JSON 对象
     * @return 包装后的响应 JSON 字符串
     * @throws Exception JSON 异常
     */
    private String ok(JSONObject output) throws Exception {
        return ok(output, null);
    }

    /**
     * 成功响应：{@code output} 字段存 {@code output.toString()}，可选 {@code _displayText}。
     *
     * @param output      业务结果
     * @param displayText 展示用文本，可 null
     * @return 响应 JSON 字符串
     * @throws Exception JSON 异常
     */
    private String ok(JSONObject output, String displayText) throws Exception {
        JSONObject result = new JSONObject()
                .put("success", true)
                .put("output", output.toString());
        if (displayText != null && !displayText.isEmpty()) {
            result.put("_displayText", displayText);
        }
        return result.toString();
    }

    /**
     * @param message 错误描述
     * @return {@code success=false} 的 JSON
     * @throws Exception JSON 异常
     */
    private String error(String message) throws Exception {
        return new JSONObject()
                .put("success", false)
                .put("error", message)
                .toString();
    }
}
