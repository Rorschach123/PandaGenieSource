package ai.rorsch.moduleplugins.password_gen;

import android.content.Context;
import ai.rorsch.pandagenie.module.runtime.ModulePlugin;
import org.json.JSONArray;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * PandaGenie「密码生成器」模块插件。
 * <p>
 * <b>模块用途：</b>使用密码学安全的 {@link SecureRandom} 生成随机密码、批量密码、英文单词助记短语，
 * 并对任意字符串做强度评分（长度、字符类型、熵估计、连续/重复惩罚等），展示文案随系统语言在中英文间切换。
 * </p>
 * <p>
 * <b>对外 API（{@link #invoke} 的 {@code action}）：</b>
 * </p>
 * <ul>
 *   <li>{@code generate} — 按长度与字符集开关生成单条密码，并附带强度摘要</li>
 *   <li>{@code generateMultiple} — 生成多条密码（count、length 等有上下限）</li>
 *   <li>{@code generatePassphrase} — 从内置词表随机选词并用分隔符连接</li>
 *   <li>{@code checkStrength} — 分析已有密码，返回分数、等级、检查项与建议</li>
 * </ul>
 * <p>
 * 由宿主 {@code ModuleRuntime} 反射加载；错误信息在部分校验路径下会随 {@link #isZh()} 返回中英文。
 * </p>
 */
public class PasswordGenPlugin implements ModulePlugin {

    /** 全局安全随机数生成器，用于所有密码与采样。 */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 大写字母池。 */
    private static final String UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    /** 小写字母池。 */
    private static final String LOWER = "abcdefghijklmnopqrstuvwxyz";
    /** 数字池。 */
    private static final String DIGITS = "0123456789";
    /** 常用符号池。 */
    private static final String SYMBOLS = "!@#$%^&*()-_=+[]{};:,.?/";

    /**
     * 助记短语使用的英文常用词表（随机抽取、尽量去重）。
     */
    private static final String[] WORDS = {
            "about", "after", "again", "air", "all", "also", "animal", "another", "answer", "any",
            "apple", "arm", "around", "art", "ask", "away", "baby", "back", "ball", "bank",
            "base", "bath", "bean", "bear", "beat", "bed", "been", "before", "bell", "best",
            "better", "between", "big", "bird", "black", "blue", "boat", "body", "book", "both",
            "box", "boy", "bread", "bring", "brown", "build", "busy", "but", "cake", "call",
            "camp", "can", "car", "card", "care", "carry", "case", "cat", "catch", "chair",
            "chance", "change", "check", "child", "city", "class", "clean", "clear", "climb", "clock",
            "close", "cloud", "coat", "cold", "color", "come", "cook", "cool", "corn", "corner",
            "cost", "cover", "cow", "cross", "crowd", "cry", "cup", "cut", "dance", "dark",
            "day", "deep", "desk", "dinner", "dog", "door", "down", "draw", "dream", "dress",
            "drink", "drive", "drop", "dry", "duck", "each", "ear", "earth", "east", "easy",
            "eat", "edge", "egg", "eight", "end", "even", "ever", "every", "eye", "face",
            "fact", "fair", "fall", "family", "far", "farm", "fast", "father", "feel", "feet",
            "few", "field", "fight", "fill", "final", "find", "fine", "fire", "first", "fish",
            "five", "floor", "flower", "fly", "follow", "food", "foot", "for", "forest", "form",
            "found", "four", "free", "friend", "from", "front", "fruit", "full", "game", "garden",
            "gate", "gave", "girl", "give", "glass", "goat", "gold", "good", "grass", "gray",
            "great", "green", "ground", "group", "grow", "hair", "half", "hand", "happy", "hard",
            "hat", "have", "head", "hear", "heart", "heat", "heavy", "help", "here", "hill",
            "history", "hold", "home", "horse", "hot", "hour", "house", "how", "hundred", "idea",
            "inch", "inside", "into", "iron", "island", "job", "join", "jump", "just", "keep",
            "kept", "key", "king", "know", "lake", "land", "large", "last", "late", "laugh",
            "lead", "leaf", "learn", "leave", "left", "leg", "less", "let", "letter", "life",
            "light", "like", "line", "list", "little", "live", "long", "look", "lost", "lot",
            "loud", "love", "low", "luck", "made", "mail", "main", "make", "man", "many",
            "map", "mark", "may", "mean", "meat", "meet", "men", "mile", "milk", "mind",
            "mine", "minute", "miss", "money", "moon", "more", "morning", "most", "mother", "mountain",
            "mouth", "move", "much", "music", "must", "name", "near", "need", "nest", "never",
            "new", "next", "night", "nine", "north", "nose", "note", "nothing", "notice", "now",
            "number", "ocean", "off", "often", "oil", "old", "once", "one", "only", "open",
            "order", "other", "our", "out", "over", "own", "page", "pair", "paper", "park",
            "part", "party", "pass", "past", "path", "pay", "peace", "pen", "pick", "picture",
            "piece", "place", "plan", "plant", "play", "please", "point", "pond", "poor", "port",
            "pose", "post", "pull", "push", "put", "queen", "quick", "quiet", "race", "radio",
            "rain", "read", "ready", "real", "red", "rest", "rice", "rich", "ride", "right",
            "ring", "river", "road", "rock", "room", "root", "rope", "rose", "round", "rule",
            "run", "safe", "said", "sail", "salt", "same", "sand", "save", "saw", "say",
            "school", "sea", "seat", "second", "see", "seed", "seem", "send", "sense", "serve",
            "set", "seven", "shape", "sheep", "ship", "shirt", "shoe", "shop", "short", "should",
            "show", "side", "sight", "sign", "silver", "sing", "sister", "sit", "six", "size",
            "skin", "sky", "sleep", "slow", "small", "smile", "snow", "so", "soft", "soil",
            "some", "song", "soon", "sound", "south", "space", "speak", "speed", "spell", "spend",
            "sport", "spring", "stand", "star", "start", "stay", "steal", "step", "stick", "still",
            "stone", "stop", "store", "story", "street", "strong", "study", "such", "sugar", "summer",
            "sun", "sure", "swim", "table", "tail", "take", "talk", "tall", "teacher", "team",
            "tell", "ten", "test", "than", "thank", "that", "their", "them", "then", "there",
            "these", "they", "thick", "thing", "think", "this", "those", "three", "throw", "tiger",
            "time", "tiny", "today", "together", "told", "tomorrow", "tone", "took", "top", "touch",
            "toward", "town", "track", "train", "tree", "trip", "true", "try", "turn", "twelve",
            "twenty", "two", "under", "until", "upon", "us", "use", "usual", "valley", "very",
            "voice", "vote", "wait", "walk", "wall", "want", "warm", "wash", "watch", "water",
            "wave", "way", "week", "well", "went", "were", "west", "what", "wheel", "when",
            "where", "which", "while", "white", "who", "whole", "whose", "why", "wide", "wife",
            "wild", "will", "wind", "window", "wing", "winter", "wish", "with", "wolf", "wood",
            "word", "work", "world", "would", "write", "yard", "year", "yellow", "yes", "yet",
            "young", "your", "zebra", "zone", "zoo"
    };

    /**
     * 模块入口：解析参数并分发；根据系统默认语言选择部分文案语言。
     *
     * @param context    Android 上下文（未直接使用）
     * @param action     操作名
     * @param paramsJson JSON 参数
     * @return 成功/失败包装 JSON；成功时常含 {@code _displayText}（掩码展示密码）
     * @throws Exception 构造 JSON 等异常
     */
    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        try {
            JSONObject params = new JSONObject(emptyJson(paramsJson));
            boolean zh = isZh();
            switch (action) {
                case "generate": {
                    int length = clamp(params.optInt("length", 16), 4, 256);
                    boolean upper = params.optBoolean("uppercase", true);
                    boolean lower = params.optBoolean("lowercase", true);
                    boolean numbers = params.optBoolean("numbers", true);
                    boolean symbols = params.optBoolean("symbols", true);
                    String pwd = generatePassword(length, upper, lower, numbers, symbols);
                    JSONObject out = buildPasswordResult(pwd);
                    String alias = params.optString("alias", "").trim();
                    if (alias.isEmpty()) {
                        alias = zh ? "密码 " + new java.text.SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(new java.util.Date())
                                   : "Password " + new java.text.SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(new java.util.Date());
                    }
                    JSONObject vaultSave = new JSONObject();
                    vaultSave.put("moduleId", "password_gen");
                    vaultSave.put("value", pwd);
                    vaultSave.put("defaultAlias", alias);
                    vaultSave.put("category", "password");
                    return okWithVaultSave(out, formatGenerateDisplay(out, zh), vaultSave);
                }
                case "generateMultiple": {
                    int count = clamp(params.optInt("count", 5), 1, 50);
                    int length = clamp(params.optInt("length", 16), 4, 256);
                    boolean upper = params.optBoolean("uppercase", true);
                    boolean lower = params.optBoolean("lowercase", true);
                    boolean numbers = params.optBoolean("numbers", true);
                    boolean symbols = params.optBoolean("symbols", true);
                    JSONArray arr = new JSONArray();
                    for (int i = 0; i < count; i++) {
                        arr.put(generatePassword(length, upper, lower, numbers, symbols));
                    }
                    JSONObject out = new JSONObject();
                    out.put("passwords", arr);
                    out.put("count", count);
                    out.put("length", length);
                    return ok(out, formatMultipleDisplay(out, zh));
                }
                case "generatePassphrase": {
                    int wordCount = clamp(params.optInt("wordCount", 4), 2, 16);
                    String sep = params.optString("separator", "-");
                    if (sep == null) sep = "-";
                    String phrase = generatePassphrase(wordCount, sep);
                    JSONObject out = new JSONObject();
                    out.put("passphrase", phrase);
                    out.put("wordCount", wordCount);
                    out.put("separator", sep);
                    return ok(out, formatPassphraseDisplay(out, zh));
                }
                case "checkStrength": {
                    String password = params.optString("password", "");
                    JSONObject out = analyzeStrength(password);
                    return ok(out, formatStrengthDisplay(out, zh));
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
     * 判断当前系统默认语言是否为中文（用于错误提示与展示文案）。
     *
     * @return 语言以 {@code zh} 开头则为 true
     */
    private boolean isZh() {
        try {
            return Locale.getDefault().getLanguage().toLowerCase(Locale.ROOT).startsWith("zh");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 生成随机密码：先确保每种勾选类型至少出现一次，再用合并字符池填满剩余位置，最后打乱顺序。
     *
     * @param length   密码长度（调用方已 clamp）
     * @param upper    是否允许大写（且至少一个大写）
     * @param lower    是否允许小写
     * @param numbers  是否允许数字
     * @param symbols  是否允许符号
     * @return 生成的密码字符串
     * @throws Exception 长度小于已选类型最少需求时抛出
     */
    private String generatePassword(int length, boolean upper, boolean lower, boolean numbers, boolean symbols)
            throws Exception {
        StringBuilder pool = new StringBuilder();
        if (upper) pool.append(UPPER);
        if (lower) pool.append(LOWER);
        if (numbers) pool.append(DIGITS);
        if (symbols) pool.append(SYMBOLS);
        if (pool.length() == 0) {
            pool.append(LOWER); // 全 false 时回退到小写池，避免空池
        }

        List<Character> required = new ArrayList<>();
        if (upper) required.add(pickChar(UPPER));
        if (lower) required.add(pickChar(LOWER));
        if (numbers) required.add(pickChar(DIGITS));
        if (symbols) required.add(pickChar(SYMBOLS));

        if (length < required.size()) {
            throw new IllegalArgumentException(isZh()
                    ? "长度过小，无法满足已选字符类型"
                    : "Length too small for selected character types");
        }

        char[] chars = new char[length];
        int i = 0;
        for (char c : required) {
            chars[i++] = c;
        }
        String poolStr = pool.toString();
        while (i < length) {
            chars[i++] = poolStr.charAt(RANDOM.nextInt(poolStr.length()));
        }
        shuffleChars(chars); // 打乱以避免「必填字符」总固定在前几位
        return new String(chars);
    }

    /**
     * 从给定字符集中均匀随机取一个字符。
     *
     * @param set 非空字符集
     * @return 随机字符
     */
    private char pickChar(String set) {
        return set.charAt(RANDOM.nextInt(set.length()));
    }

    /**
     * Fisher–Yates 洗牌，打乱 char 数组。
     *
     * @param arr 待打乱数组
     */
    private void shuffleChars(char[] arr) {
        for (int i = arr.length - 1; i > 0; i--) {
            int j = RANDOM.nextInt(i + 1);
            char t = arr[i];
            arr[i] = arr[j];
            arr[j] = t;
        }
    }

    /**
     * 从 {@link #WORDS} 随机选词，尽量互不重复；不足时在兜底逻辑下允许重复以凑满词数。
     *
     * @param wordCount  词数量（已 clamp）
     * @param separator  词与词之间的分隔符
     * @return 助记短语
     */
    private String generatePassphrase(int wordCount, String separator) {
        Set<String> used = new HashSet<>();
        List<String> parts = new ArrayList<>();
        int guard = 0;
        while (parts.size() < wordCount && guard++ < wordCount * 20) {
            String w = WORDS[RANDOM.nextInt(WORDS.length)];
            if (used.add(w)) {
                parts.add(w);
            }
        }
        while (parts.size() < wordCount) {
            parts.add(WORDS[RANDOM.nextInt(WORDS.length)]);
        }
        return String.join(separator, parts);
    }

    /**
     * 封装单条生成结果：密码明文、长度及 {@link #analyzeStrength} 的关键字段。
     *
     * @param password 生成的密码
     * @return JSON 对象
     * @throws Exception 分析或序列化异常
     */
    private JSONObject buildPasswordResult(String password) throws Exception {
        JSONObject strength = analyzeStrength(password);
        JSONObject out = new JSONObject();
        out.put("password", password);
        out.put("length", password.length());
        out.put("strengthScore", strength.optInt("score"));
        out.put("strengthRating", strength.optString("rating"));
        out.put("strengthLabel", strength.optString("ratingLabel"));
        return out;
    }

    /**
     * 对密码进行启发式强度分析：正则检测字符类型、长度与类型加分、简化熵估计、
     * 连续数字/三连重复惩罚，并生成 rating、checks、recommendations。
     *
     * @param password 待分析密码，可为 null（按空串处理）
     * @return 含 score(0–100)、rating、ratingLabel、entropyBits、checks、recommendations 等字段的 JSON
     * @throws Exception JSON 写入异常
     */
    private JSONObject analyzeStrength(String password) throws Exception {
        JSONObject result = new JSONObject();
        JSONArray recommendations = new JSONArray();
        JSONArray checks = new JSONArray();

        if (password == null) password = "";

        boolean hasLower = password.matches(".*[a-z].*");
        boolean hasUpper = password.matches(".*[A-Z].*");
        boolean hasDigit = password.matches(".*[0-9].*");
        boolean hasSymbol = password.matches(".*[^a-zA-Z0-9].*");
        int len = password.length();

        int score = 0;
        if (len >= 8) score += 15;
        if (len >= 12) score += 15;
        if (len >= 16) score += 10;
        if (len >= 20) score += 5;
        if (hasLower) score += 10;
        if (hasUpper) score += 10;
        if (hasDigit) score += 10;
        if (hasSymbol) score += 10;

        int variety = 0;
        if (hasLower) variety++;
        if (hasUpper) variety++;
        if (hasDigit) variety++;
        if (hasSymbol) variety++;
        score += variety * 5;

        double entropyBits = estimateEntropyBits(password, hasLower, hasUpper, hasDigit, hasSymbol);
        if (entropyBits >= 28) score += 5;
        if (entropyBits >= 40) score += 5;
        if (entropyBits >= 56) score += 5;

        if (isSequentialOrRepeated(password)) {
            score -= 15;
            recommendations.put(isZh() ? "避免连续或重复字符（如 123、aaa）" : "Avoid sequential or repeated runs (e.g. 123, aaa)");
        }
        if (len < 8) {
            recommendations.put(isZh() ? "密码长度建议至少 8 位" : "Use at least 8 characters");
        }
        if (!hasUpper && hasLower) {
            recommendations.put(isZh() ? "加入大写字母可提高强度" : "Add uppercase letters");
        }
        if (!hasLower && hasUpper) {
            recommendations.put(isZh() ? "加入小写字母可提高强度" : "Add lowercase letters");
        }
        if (!hasDigit) {
            recommendations.put(isZh() ? "加入数字可提高强度" : "Add numbers");
        }
        if (!hasSymbol) {
            recommendations.put(isZh() ? "加入符号可显著提高强度" : "Add symbols for much stronger passwords");
        }
        if (variety < 3 && len >= 8) {
            recommendations.put(isZh() ? "混合多种字符类型更安全" : "Mix multiple character types");
        }

        score = clamp(score, 0, 100); // 汇总后限制在 0–100

        String rating;
        String ratingLabel;
        if (score < 40) {
            rating = "weak";
            ratingLabel = isZh() ? "弱" : "Weak";
        } else if (score < 60) {
            rating = "fair";
            ratingLabel = isZh() ? "一般" : "Fair";
        } else if (score < 75) {
            rating = "good";
            ratingLabel = isZh() ? "良好" : "Good";
        } else if (score < 90) {
            rating = "strong";
            ratingLabel = isZh() ? "强" : "Strong";
        } else {
            rating = "excellent";
            ratingLabel = isZh() ? "极佳" : "Excellent";
        }

        checks.put(checkJson("length", len >= 12,
                isZh() ? (len >= 12 ? "长度充足" : "建议更长密码") : (len >= 12 ? "Good length" : "Consider a longer password")));
        checks.put(checkJson("mixedCase", hasUpper && hasLower,
                isZh() ? (hasUpper && hasLower ? "大小写混合" : "建议同时使用大小写")
                        : (hasUpper && hasLower ? "Mix of cases" : "Mix upper and lower case")));
        checks.put(checkJson("numbers", hasDigit,
                isZh() ? (hasDigit ? "包含数字" : "建议加入数字") : (hasDigit ? "Contains numbers" : "Add numbers")));
        checks.put(checkJson("symbols", hasSymbol,
                isZh() ? (hasSymbol ? "包含符号" : "建议加入符号") : (hasSymbol ? "Contains symbols" : "Add symbols")));

        result.put("score", score);
        result.put("rating", rating);
        result.put("ratingLabel", ratingLabel);
        result.put("entropyBits", Math.round(entropyBits * 10) / 10.0);
        result.put("recommendations", recommendations);
        result.put("checks", checks);
        result.put("length", len);
        result.put("hasLower", hasLower);
        result.put("hasUpper", hasUpper);
        result.put("hasDigit", hasDigit);
        result.put("hasSymbol", hasSymbol);
        return result;
    }

    /**
     * 构造单条检查项 JSON。
     *
     * @param key     检查项标识
     * @param pass    是否通过
     * @param message 说明文案
     * @return JSON 对象
     * @throws Exception JSON 异常
     */
    private JSONObject checkJson(String key, boolean pass, String message) throws Exception {
        return new JSONObject().put("key", key).put("pass", pass).put("message", message);
    }

    /**
     * 用「字符集大小 × 长度」的 log2 近似估算熵（比特），字符集按是否含四类字符累加池大小。
     *
     * @param password  密码
     * @param hasLower  是否含小写
     * @param upper     是否含大写
     * @param digit     是否含数字
     * @param symbol    是否含非字母数字符号
     * @return 估计熵（比特）
     */
    private double estimateEntropyBits(String password, boolean hasLower, boolean upper, boolean digit, boolean symbol) {
        int pool = 0;
        if (hasLower) pool += 26;
        if (upper) pool += 26;
        if (digit) pool += 10;
        if (symbol) pool += 32;
        if (pool == 0) pool = 26;
        return password.length() * (Math.log(pool) / Math.log(2));
    }

    /**
     * 检测三连相同字符或三位连续递增/递减数字（如 123、321）。
     *
     * @param s 密码
     * @return 存在弱模式则为 true
     */
    private boolean isSequentialOrRepeated(String s) {
        if (s.length() < 3) return false;
        for (int i = 0; i < s.length() - 2; i++) {
            char a = s.charAt(i);
            char b = s.charAt(i + 1);
            char c = s.charAt(i + 2);
            if (a == b && b == c) return true;
            if (Character.isDigit(a) && Character.isDigit(b) && Character.isDigit(c)) {
                int da = b - a;
                int db = c - b;
                if (da == 1 && db == 1) return true;
                if (da == -1 && db == -1) return true;
            }
        }
        return false;
    }

    /**
     * 展示用掩码：避免在 UI 中完整泄露密码。
     *
     * @param pwd 明文密码
     * @return 掩码后的占位字符串
     */
    private String maskForDisplay(String pwd) {
        if (pwd == null || pwd.isEmpty()) return "****";
        if (pwd.length() <= 4) return "****";
        return "****...****";
    }

    private static String mdCell(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("|", "\\|").replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ');
    }

    private static String pgTable(String title, String[] headers, java.util.List<String[]> rows) {
        try {
            JSONObject t = new JSONObject();
            t.put("title", title);
            JSONArray h = new JSONArray();
            for (String hdr : headers) h.put(hdr);
            t.put("headers", h);
            JSONArray r = new JSONArray();
            for (String[] row : rows) { JSONArray a = new JSONArray(); for (String c : row) a.put(c); r.put(a); }
            t.put("rows", r);
            return "__pg_table__" + t.toString() + "__pg_table_end__";
        } catch (Exception e) { return title; }
    }

    /**
     * 单条生成结果的展示文案（掩码 + 长度 + 强度标签）。
     *
     * @param out 业务输出 JSON
     * @param zh  是否中文
     * @return 多行文本
     */
    private String formatGenerateDisplay(JSONObject out, boolean zh) {
        String label = zh ? "密码" : "Password";
        String lenKey = zh ? "长度" : "Length";
        String strKey = zh ? "强度" : "Strength";
        String hint = zh ? "（可使用剪贴板模块复制）" : "(Use clipboard module to copy)";
        String[] headers = { zh ? "项目" : "Item", zh ? "值" : "Value" };
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] { label, mdCell(maskForDisplay(out.optString("password"))) });
        rows.add(new String[] { lenKey, String.valueOf(out.optInt("length")) });
        rows.add(new String[] { strKey, mdCell(out.optString("strengthLabel")) });
        rows.add(new String[] { zh ? "提示" : "Note", mdCell(hint) });
        return "\uD83D\uDD10 " + (zh ? "已生成密码" : "Password Generated") + "\n\n"
                + pgTable(zh ? "详情" : "Details", headers, rows);
    }

    /**
     * 多条生成结果展示：仅显示前 3 条掩码预览。
     *
     * @param out 含 passwords 数组的 JSON
     * @param zh  是否中文
     * @return 多行文本
     */
    private String formatMultipleDisplay(JSONObject out, boolean zh) {
        JSONArray arr = out.optJSONArray("passwords");
        int n = arr != null ? arr.length() : 0;
        String head = zh ? "已生成多条密码" : "Passwords Generated";
        String hint = zh ? "（可使用剪贴板模块复制）" : "(Use clipboard module to copy)";
        String[] headers1 = { zh ? "项目" : "Item", zh ? "值" : "Value" };
        List<String[]> rows1 = new ArrayList<>();
        rows1.add(new String[] { zh ? "数量" : "Count", String.valueOf(out.optInt("count")) });
        rows1.add(new String[] { zh ? "每条长度" : "Length each", String.valueOf(out.optInt("length")) });
        rows1.add(new String[] { zh ? "提示" : "Note", mdCell(hint) });
        StringBuilder sb = new StringBuilder();
        sb.append("\uD83D\uDD10 ").append(head).append("\n\n");
        sb.append(pgTable(zh ? "摘要" : "Summary", headers1, rows1));
        String[] headers2 = { "#", zh ? "密码（预览）" : "Password (preview)" };
        List<String[]> rows2 = new ArrayList<>();
        int show = Math.min(3, n);
        for (int i = 0; i < show; i++) {
            rows2.add(new String[] { String.valueOf(i + 1), mdCell(maskForDisplay(arr.optString(i))) });
        }
        if (n > 3) {
            rows2.add(new String[] { "…", zh ? "其余已省略显示" : "more hidden" });
        }
        sb.append("\n\n").append(pgTable(zh ? "预览" : "Preview", headers2, rows2));
        return sb.toString().trim();
    }

    /**
     * 助记短语展示（完整短语可见，与随机密码掩码策略不同）。
     *
     * @param out 含 passphrase、wordCount 的 JSON
     * @param zh  是否中文
     * @return 多行文本
     */
    private String formatPassphraseDisplay(JSONObject out, boolean zh) {
        String phrase = out.optString("passphrase");
        String[] headers = { zh ? "项目" : "Item", zh ? "值" : "Value" };
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] { zh ? "短语" : "Phrase", mdCell(phrase) });
        rows.add(new String[] { zh ? "词数" : "Words", String.valueOf(out.optInt("wordCount")) });
        rows.add(new String[] { zh ? "分隔符" : "Separator", mdCell(out.optString("separator")) });
        return "\uD83D\uDD10 " + (zh ? "助记短语" : "Passphrase") + "\n\n"
                + pgTable(zh ? "详情" : "Details", headers, rows);
    }

    /**
     * 强度检测结果的展示：总分、各检查项图标、建议列表。
     *
     * @param out {@link #analyzeStrength} 的输出
     * @param zh  是否中文
     * @return 多行文本
     */
    private String formatStrengthDisplay(JSONObject out, boolean zh) {
        int score = out.optInt("score");
        String label = out.optString("ratingLabel");
        StringBuilder sb = new StringBuilder();
        sb.append("\uD83D\uDD0D ").append(zh ? "密码强度" : "Password Strength").append("\n\n");
        String[] headers1 = { zh ? "指标" : "Metric", zh ? "值" : "Value" };
        List<String[]> rows1 = new ArrayList<>();
        rows1.add(new String[] { zh ? "评级" : "Rating", mdCell(label) });
        rows1.add(new String[] { zh ? "分数" : "Score", score + "/100" });
        rows1.add(new String[] { zh ? "熵（比特）" : "Entropy (bits)", String.valueOf(out.optDouble("entropyBits")) });
        rows1.add(new String[] { zh ? "长度" : "Length", String.valueOf(out.optInt("length")) });
        sb.append(pgTable(zh ? "概览" : "Overview", headers1, rows1));

        String[] headers2 = { zh ? "检查项" : "Check", zh ? "通过" : "Pass", zh ? "说明" : "Detail" };
        List<String[]> rows2 = new ArrayList<>();
        JSONArray checks = out.optJSONArray("checks");
        if (checks != null) {
            for (int i = 0; i < checks.length(); i++) {
                JSONObject c = checks.optJSONObject(i);
                if (c == null) continue;
                boolean pass = c.optBoolean("pass");
                String passStr = pass ? (zh ? "是" : "yes") : (zh ? "否" : "no");
                rows2.add(new String[] { mdCell(c.optString("key")), passStr, mdCell(c.optString("message")) });
            }
        }
        sb.append("\n\n").append(pgTable(zh ? "检查" : "Checks", headers2, rows2));

        JSONArray rec = out.optJSONArray("recommendations");
        if (rec != null && rec.length() > 0) {
            String[] headers3 = { "#", zh ? "建议" : "Tip" };
            List<String[]> rows3 = new ArrayList<>();
            for (int i = 0; i < rec.length(); i++) {
                rows3.add(new String[] { String.valueOf(i + 1), mdCell(rec.optString(i)) });
            }
            sb.append("\n\n").append(pgTable(zh ? "建议" : "Tips", headers3, rows3));
        }
        return sb.toString().trim();
    }

    /**
     * 将整数限制在 [min, max]。
     *
     * @param v   原值
     * @param min 下限
     * @param max 上限
     * @return 夹紧后的值
     */
    private int clamp(int v, int min, int max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    /**
     * 空 JSON 参数规范为 {@code "{}"}。
     *
     * @param value 原始字符串
     * @return JSON 对象字面量
     */
    private String emptyJson(String value) {
        return value == null || value.trim().isEmpty() ? "{}" : value;
    }

    /**
     * 成功响应，{@code output} 为嵌套 JSON 的字符串形式，可选 {@code _displayText}。
     *
     * @param output      业务 JSONObject
     * @param displayText 展示文案
     * @return 包装后的 JSON 字符串
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

    private String okWithVaultSave(JSONObject output, String displayText, JSONObject vaultSave) throws Exception {
        JSONObject result = new JSONObject()
                .put("success", true)
                .put("output", output.toString());
        if (displayText != null && !displayText.isEmpty()) {
            result.put("_displayText", displayText);
        }
        if (vaultSave != null) {
            result.put("_vaultSave", vaultSave);
        }
        return result.toString();
    }

    /**
     * 失败响应。
     *
     * @param message 错误说明
     * @return JSON 字符串
     * @throws Exception JSON 异常
     */
    private String error(String message) throws Exception {
        return new JSONObject()
                .put("success", false)
                .put("error", message)
                .toString();
    }
}
