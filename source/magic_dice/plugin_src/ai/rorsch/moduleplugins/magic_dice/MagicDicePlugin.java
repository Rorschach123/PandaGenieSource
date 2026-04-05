package ai.rorsch.moduleplugins.magic_dice;

import android.content.Context;
import ai.rorsch.pandagenie.module.runtime.ModulePlugin;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

public class MagicDicePlugin implements ModulePlugin {

    private final Random random = new Random();

    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        try {
            JSONObject params = new JSONObject(emptyJson(paramsJson));
            switch (action) {
                case "roll":
                    return ok(roll(params.optInt("count", 1)));
                case "rollWithSum":
                    return ok(rollWithSum(params.optInt("count", 1), params.optInt("targetSum", 0)));
                case "rollBigOrSmall":
                    return ok(rollBigOrSmall(params.optInt("count", 1)));
                case "rollAllSame":
                    return ok(rollAllSame(params.optInt("count", 2), params.optInt("value", 0)));
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

    private JSONObject roll(int count) throws Exception {
        count = clampCount(count);
        int[] dice = new int[count];
        int total = 0;
        for (int i = 0; i < count; i++) {
            dice[i] = random.nextInt(6) + 1;
            total += dice[i];
        }
        return buildDiceResult(dice, total);
    }

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

        for (int i = chosen.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int temp = chosen[i];
            chosen[i] = chosen[j];
            chosen[j] = temp;
        }

        return buildDiceResult(chosen, targetSum);
    }

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

        double totalOutcomes = Math.pow(6, count);
        int permCount = 0;
        for (int[] combo : combos) {
            permCount += countPermutations(combo);
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

    private List<int[]> generateCombinations(int diceCount, int targetSum) {
        List<int[]> results = new ArrayList<>();
        generateHelper(diceCount, targetSum, 1, new int[diceCount], 0, results);
        return results;
    }

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

    private int factorial(int n) {
        int r = 1;
        for (int i = 2; i <= n; i++) {
            r *= i;
        }
        return r;
    }

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

    private int clampCount(int count) {
        if (count < 1) return 1;
        if (count > 6) return 6;
        return count;
    }

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

    private String emptyJson(String value) {
        return value == null || value.trim().isEmpty() ? "{}" : value;
    }

    private String ok(JSONObject output) throws Exception {
        return new JSONObject()
                .put("success", true)
                .put("output", output.toString())
                .toString();
    }

    private String error(String message) throws Exception {
        return new JSONObject()
                .put("success", false)
                .put("error", message)
                .toString();
    }
}
