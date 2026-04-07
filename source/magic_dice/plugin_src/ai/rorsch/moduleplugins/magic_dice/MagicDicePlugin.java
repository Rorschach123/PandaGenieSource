package ai.rorsch.moduleplugins.magic_dice;

import android.content.Context;
import ai.rorsch.pandagenie.module.runtime.ModulePlugin;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * PandaGenie「魔法骰子」模块插件。
 * <p>
 * <b>模块用途：</b>提供标准六面骰的随机投掷、指定总和投掷、大小判定、豹子（全同点）演示，
 * 以及组合枚举、概率与总和分布统计，便于娱乐或教学场景。
 * </p>
 * <p>
 * <b>对外 API（{@link #invoke} 的 {@code action}）：</b>
 * </p>
 * <ul>
 *   <li>{@code roll} — 投掷 {@code count} 个骰子（1–6，默认 1）</li>
 *   <li>{@code rollWithSum} — 随机抽取一种骰子组合，使总和等于 {@code targetSum}</li>
 *   <li>{@code rollBigOrSmall} — 投掷后与期望值比较判「大/小/和」</li>
 *   <li>{@code rollAllSame} — 所有骰子同点；{@code value} 为 0 时随机 1–6</li>
 *   <li>{@code getCombinations} — 列出满足目标和的无序组合及排列数、概率</li>
 *   <li>{@code getStats} — 给定骰子个数下的总和分布与期望</li>
 * </ul>
 * <p>
 * 由宿主 {@code ModuleRuntime} 反射实例化；骰子个数在内部被限制在 1–6 枚。
 * </p>
 */
public class MagicDicePlugin implements ModulePlugin {

    /** 用于生成骰子点数的伪随机源（每实例独立）。 */
    private final Random random = new Random();

    /** Unicode 骰子面 1–6 对应展示符号。 */
    private static final String[] DICE_EMOJI = {"\u2680", "\u2681", "\u2682", "\u2683", "\u2684", "\u2685"};

    /**
     * 模块入口：根据 action 执行对应骰子逻辑并返回 JSON。
     *
     * @param context    Android 上下文（当前逻辑未直接使用）
     * @param action     操作名
     * @param paramsJson JSON 参数，空视为 {@code "{}"}
     * @return 成功时含 {@code success}、{@code output}、部分 action 含 {@code _displayText}
     * @throws Exception 构造 JSON 或业务校验失败时抛出
     */
    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        try {
            JSONObject params = new JSONObject(emptyJson(paramsJson));
            switch (action) {
                case "roll": {
                    JSONObject r = roll(params.optInt("count", 1));
                    return ok(r, formatDiceDisplay(r, null));
                }
                case "rollWithSum": {
                    JSONObject r = rollWithSum(params.optInt("count", 1), params.optInt("targetSum", 0));
                    return ok(r, formatDiceDisplay(r, null));
                }
                case "rollBigOrSmall": {
                    JSONObject r = rollBigOrSmall(params.optInt("count", 1));
                    return ok(r, formatDiceDisplay(r, r.optString("size", "") + " (" + r.optString("size_en", "") + ")"));
                }
                case "rollAllSame": {
                    JSONObject r = rollAllSame(params.optInt("count", 2), params.optInt("value", 0));
                    String extra = r.optBoolean("isLeopard", false) ? "\uD83C\uDFB0 豹子！" : null;
                    return ok(r, formatDiceDisplay(r, extra));
                }
                case "getCombinations":
                    return ok(getCombinations(params.optInt("count", 1), params.optInt("targetSum", 0)));
                case "getStats":
                    return ok(getStats(params.optInt("count", 1)));
                default:
                    return error("Unsupported action: " + action);
            }
        } catch (Exception e) {
            String msg = e.getMessage();
            return error(msg != null && !msg.isEmpty() ? msg : e.getClass().getSimpleName());
        }
    }

    /**
     * 将骰子结果格式化为带 emoji 与总和的展示文本。
     *
     * @param result    含 {@code dice} 数组与 {@code total} 的 JSON
     * @param extraLine 可选附加一行（如大小说明、豹子提示）
     * @return 多行字符串
     * @throws Exception 读取 JSON 数组时异常
     */
    private String formatDiceDisplay(JSONObject result, String extraLine) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("\uD83C\uDFB2 骰子结果\n");
        sb.append("━━━━━━━━━━━━━━\n");

        JSONArray dice = result.optJSONArray("dice");
        if (dice != null) {
            StringBuilder diceStr = new StringBuilder();
            for (int i = 0; i < dice.length(); i++) {
                int val = dice.getInt(i);
                if (i > 0) diceStr.append("  ");
                diceStr.append(DICE_EMOJI[val - 1]);
            }
            sb.append(diceStr).append("\n");
        }

        sb.append("总和: ").append(result.optInt("total", 0));
        if (extraLine != null && !extraLine.isEmpty()) {
            sb.append("\n").append(extraLine);
        }
        return sb.toString();
    }

    /**
     * 独立随机投掷多枚六面骰。
     *
     * @param count 骰子枚数（会被 {@link #clampCount} 限制到 1–6）
     * @return 含 dice 数组、total、count 的 JSON
     * @throws Exception 构造 JSON 失败时抛出
     */
    private JSONObject roll(int count) throws Exception {
        count = clampCount(count);
        int[] dice = new int[count];
        int total = 0;
        for (int i = 0; i < count; i++) {
            dice[i] = random.nextInt(6) + 1; // nextInt(6) ∈ [0,5]，+1 得到 1–6
            total += dice[i];
        }
        return buildDiceResult(dice, total);
    }

    /**
     * 在满足「非降序」约束的所有组合中随机选一种，再打乱顺序，使总和恰为 {@code targetSum}。
     *
     * @param count      骰子枚数
     * @param targetSum  目标总和，合法范围为 [count, 6*count]
     * @return 打乱后的骰子结果 JSON，total 等于 targetSum
     * @throws Exception 目标和不可达或无组合时抛出
     */
    private JSONObject rollWithSum(int count, int targetSum) throws Exception {
        count = clampCount(count);
        if (targetSum < count || targetSum > count * 6) {
            throw new IllegalArgumentException(
                    String.format(Locale.ROOT, "Target sum must be between %d and %d for %d dice", count, count * 6, count));
        }

        List<int[]> combos = generateCombinations(count, targetSum);
        if (combos.isEmpty()) {
            throw new IllegalArgumentException("No valid combination found");
        }
        int[] chosen = combos.get(random.nextInt(combos.size()));

        // Fisher–Yates 洗牌：打破非降序，呈现为随机顺序的投掷结果
        for (int i = chosen.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int temp = chosen[i];
            chosen[i] = chosen[j];
            chosen[j] = temp;
        }

        return buildDiceResult(chosen, targetSum);
    }

    /**
     * 投掷后与阈值 {@code count * 3.5} 比较：大于为「大」，小于为「小」，等于为「和」。
     *
     * @param count 骰子枚数
     * @return 在基础结果上增加 threshold、size（中文）、size_en（英文）
     * @throws Exception 构造 JSON 失败时抛出
     */
    private JSONObject rollBigOrSmall(int count) throws Exception {
        count = clampCount(count);
        int[] dice = new int[count];
        int total = 0;
        for (int i = 0; i < count; i++) {
            dice[i] = random.nextInt(6) + 1;
            total += dice[i];
        }

        double threshold = count * 3.5;
        String size = total > threshold ? "大" : (total < threshold ? "小" : "和");
        String sizeEn = total > threshold ? "big" : (total < threshold ? "small" : "tie");

        JSONObject result = buildDiceResult(dice, total);
        result.put("threshold", threshold);
        result.put("size", size);
        result.put("size_en", sizeEn);
        return result;
    }

    /**
     * 构造所有骰子均为同一面值的「豹子」结果；{@code value==0} 时在 1–6 中随机选面值。
     *
     * @param count 骰子枚数
     * @param value 指定点数，0 表示随机
     * @return 含 isLeopard、leopardValue 及骰子数组
     * @throws Exception value 非法时抛出
     */
    private JSONObject rollAllSame(int count, int value) throws Exception {
        count = clampCount(count);
        if (value == 0) {
            value = random.nextInt(6) + 1;
        }
        if (value < 1 || value > 6) {
            throw new IllegalArgumentException("Value must be 1-6");
        }

        int[] dice = new int[count];
        int total = 0;
        for (int i = 0; i < count; i++) {
            dice[i] = value;
            total += value;
        }

        JSONObject result = buildDiceResult(dice, total);
        result.put("isLeopard", true);
        result.put("leopardValue", value);
        return result;
    }

    /**
     * 枚举所有非降序的骰子组合使总和为 targetSum，并统计排列数与近似概率。
     *
     * @param count      骰子枚数
     * @param targetSum  目标和
     * @return 含组合列表、combinationCount、permutationCount、probability 百分比字符串等
     * @throws Exception 目标和非法时抛出
     */
    private JSONObject getCombinations(int count, int targetSum) throws Exception {
        count = clampCount(count);
        if (targetSum < count || targetSum > count * 6) {
            throw new IllegalArgumentException(
                    String.format(Locale.ROOT, "Target sum must be between %d and %d for %d dice", count, count * 6, count));
        }

        List<int[]> combos = generateCombinations(count, targetSum);
        JSONArray comboArray = new JSONArray();
        for (int[] combo : combos) {
            JSONArray arr = new JSONArray();
            for (int v : combo) {
                arr.put(v);
            }
            comboArray.put(arr);
        }

        double totalOutcomes = Math.pow(6, count); // 每枚独立 6 种，总样本空间 6^count
        int permCount = 0;
        for (int[] combo : combos) {
            permCount += countPermutations(combo); //  multiset 排列数之和
        }

        JSONObject result = new JSONObject();
        result.put("diceCount", count);
        result.put("targetSum", targetSum);
        result.put("combinationCount", combos.size());
        result.put("combinations", comboArray);
        result.put("permutationCount", permCount);
        result.put("probability", String.format(Locale.ROOT, "%.4f%%", permCount / totalOutcomes * 100));
        return result;
    }

    /**
     * 计算指定骰子个数下「总和」的完整分布（每种总和的出现方式数与占比）。
     *
     * @param count 骰子枚数
     * @return 含 min/max/expected/totalOutcomes/distribution 数组
     * @throws Exception JSON 构造失败时抛出
     */
    private JSONObject getStats(int count) throws Exception {
        count = clampCount(count);
        int min = count;
        int max = count * 6;
        double expected = count * 3.5;
        double totalOutcomes = Math.pow(6, count);

        JSONArray distribution = new JSONArray();
        for (int sum = min; sum <= max; sum++) {
            int ways = countWays(count, sum);
            JSONObject entry = new JSONObject();
            entry.put("sum", sum);
            entry.put("ways", ways);
            entry.put("probability", String.format(Locale.ROOT, "%.4f%%", ways / totalOutcomes * 100));
            distribution.put(entry);
        }

        JSONObject result = new JSONObject();
        result.put("diceCount", count);
        result.put("min", min);
        result.put("max", max);
        result.put("expected", expected);
        result.put("totalOutcomes", (long) totalOutcomes);
        result.put("distribution", distribution);
        return result;
    }

    /**
     * 生成所有非降序、每项 1–6 且和为 targetSum 的长度 diceCount 的整数数组列表。
     *
     * @param diceCount  骰子个数
     * @param targetSum 目标和
     * @return 组合列表（每个 int[] 为升序或非降序）
     */
    private List<int[]> generateCombinations(int diceCount, int targetSum) {
        List<int[]> results = new ArrayList<>();
        generateHelper(diceCount, targetSum, 1, new int[diceCount], 0, results);
        return results;
    }

    /**
     * 回溯递归：保证 current 非降序，剪枝剩余骰子可达的和区间。
     *
     * @param diceCount 总骰子数
     * @param remaining 当前还需凑齐的和
     * @param minVal    下一位置允许的最小面值（维持非降序）
     * @param current   当前部分解
     * @param idx       当前填充下标
     * @param results   收集完整解
     */
    private void generateHelper(int diceCount, int remaining, int minVal, int[] current, int idx, List<int[]> results) {
        if (idx == diceCount) {
            if (remaining == 0) {
                results.add(current.clone());
            }
            return;
        }
        int diceLeft = diceCount - idx;
        for (int v = minVal; v <= 6; v++) {
            if (remaining - v < (diceLeft - 1) || remaining - v > (diceLeft - 1) * 6) {
                continue;
            }
            current[idx] = v;
            generateHelper(diceCount, remaining - v, v, current, idx + 1, results);
        }
    }

    /**
     * 计算多重集合 combo 的全排列个数：n! / (各重复元素阶乘之积)。
     *
     * @param combo 非降序骰子面值数组
     * @return 不同排列数
     */
    private int countPermutations(int[] combo) {
        int n = combo.length;
        int result = factorial(n);
        int i = 0;
        while (i < n) {
            int j = i + 1;
            while (j < n && combo[j] == combo[i]) {
                j++;
            }
            result /= factorial(j - i);
            i = j;
        }
        return result;
    }

    /**
     * 计算 n 的阶乘（n 较小，适用于骰子枚数限制内的组合数学）。
     *
     * @param n 非负整数
     * @return n!
     */
    private int factorial(int n) {
        int r = 1;
        for (int i = 2; i <= n; i++) {
            r *= i;
        }
        return r;
    }

    /**
     * 动态规划：用 diceCount 枚 1–6 的骰子凑出总和 targetSum 的方案数（有序投掷等价于方案数）。
     *
     * @param diceCount  骰子个数
     * @param targetSum  目标和
     * @return 方案数
     */
    private int countWays(int diceCount, int targetSum) {
        if (diceCount == 0) return targetSum == 0 ? 1 : 0;
        if (targetSum < diceCount || targetSum > diceCount * 6) return 0;

        int[][] dp = new int[diceCount + 1][targetSum + 1];
        dp[0][0] = 1;
        for (int d = 1; d <= diceCount; d++) {
            for (int s = d; s <= Math.min(targetSum, d * 6); s++) {
                for (int v = 1; v <= 6 && v <= s; v++) {
                    dp[d][s] += dp[d - 1][s - v];
                }
            }
        }
        return dp[diceCount][targetSum];
    }

    /**
     * 将骰子个数限制在 [1, 6]。
     *
     * @param count 原始个数
     * @return 夹紧后的个数
     */
    private int clampCount(int count) {
        if (count < 1) return 1;
        if (count > 6) return 6;
        return count;
    }

    /**
     * 将 int[] 骰子结果封装为标准输出 JSON。
     *
     * @param dice  各骰子 1–6
     * @param total 总和（应与 dice 元素和一致）
     * @return JSONObject
     * @throws Exception JSON 异常
     */
    private JSONObject buildDiceResult(int[] dice, int total) throws Exception {
        JSONArray diceArray = new JSONArray();
        for (int v : dice) {
            diceArray.put(v);
        }
        JSONObject result = new JSONObject();
        result.put("dice", diceArray);
        result.put("total", total);
        result.put("count", dice.length);
        return result;
    }

    /**
     * 空参数规范为 {@code "{}"}。
     *
     * @param value 原始字符串
     * @return JSON 对象字面量
     */
    private String emptyJson(String value) {
        return value == null || value.trim().isEmpty() ? "{}" : value;
    }

    /**
     * 成功包装，无额外展示文案。
     *
     * @param output 业务 JSON
     * @return 响应字符串
     * @throws Exception JSON 异常
     */
    private String ok(JSONObject output) throws Exception {
        return ok(output, null);
    }

    /**
     * 成功包装，可选 {@code _displayText}。
     *
     * @param output      业务 JSON（写入 output 字段时为字符串形式）
     * @param displayText 展示文案
     * @return 响应字符串
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
     * 失败响应。
     *
     * @param message 错误信息
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
