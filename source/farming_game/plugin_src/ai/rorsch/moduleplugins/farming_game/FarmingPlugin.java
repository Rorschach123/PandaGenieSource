package ai.rorsch.moduleplugins.farming_game;

import android.content.Context;
import android.os.Environment;
import ai.rorsch.pandagenie.module.runtime.ModulePlugin;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.Random;

public class FarmingPlugin implements ModulePlugin {

    private static final int PLOT_COUNT = 6;
    private static final String SAVE_DIR = "PandaGenie/data/farming_game";
    private static final String SAVE_FILE = "save.json";

    private static final String[][] CROP_DATA = {
        // seedType, name, emoji, growStages, baseGrowTime(minutes), harvestYield
        {"tomato",     "番茄", "🍅", "4", "30",  "3"},
        {"carrot",     "胡萝卜", "🥕", "3", "20",  "4"},
        {"cabbage",    "白菜", "🥬", "3", "15",  "5"},
        {"corn",       "玉米", "🌽", "5", "45",  "2"},
        {"watermelon", "西瓜", "🍉", "6", "60",  "1"},
        {"strawberry", "草莓", "🍓", "4", "25",  "6"}
    };

    private static final String[] GROWTH_EMOJIS = {"🌱", "🌿", "☘️", "🪴", "🌳", "🌸", "🌾"};

    private JSONObject gameState;
    private boolean gameLoaded;
    private Random random;

    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        try {
            JSONObject params = new JSONObject(emptyJson(paramsJson));
            switch (action) {
                case "startGame":
                    return startGame(context);
                case "enterGame":
                    return enterGame(context);
                case "plant":
                    return plant(context, params.optInt("plotIndex", -1), params.optString("seedType", ""));
                case "water":
                    return water(context, params.optInt("plotIndex", -1));
                case "fertilize":
                    return fertilize(context, params.optInt("plotIndex", -1));
                case "weed":
                    return weed(context, params.optInt("plotIndex", -1));
                case "harvest":
                    return harvest(context, params.optInt("plotIndex", -1));
                case "getState":
                    return getState(context);
                case "getHarvestHistory":
                    return getHarvestHistory(context);
                case "saveGame":
                    return saveGame(context);
                default:
                    return error("Unsupported action: " + action);
            }
        } catch (Exception e) {
            String msg = e.getMessage();
            return error(msg != null && !msg.isEmpty() ? msg : e.getClass().getSimpleName());
        }
    }

    private String startGame(Context context) throws Exception {
        random = new Random();
        gameState = createNewGame();
        gameLoaded = true;
        saveToFile(context);
        return ok(buildDisplayState(), formatDisplay());
    }

    private String enterGame(Context context) throws Exception {
        random = new Random();
        JSONObject saved = loadFromFile(context);
        if (saved != null) {
            gameState = saved;
            gameLoaded = true;
            updateGrowth();
            saveToFile(context);
            return ok(buildDisplayState(), formatDisplay());
        }
        return startGame(context);
    }

    private JSONObject createNewGame() throws Exception {
        JSONObject state = new JSONObject();
        state.put("startTime", System.currentTimeMillis());
        state.put("lastUpdateTime", System.currentTimeMillis());

        JSONArray plots = new JSONArray();
        for (int i = 0; i < PLOT_COUNT; i++) {
            JSONObject plot = new JSONObject();
            plot.put("index", i);
            plot.put("empty", true);
            plots.put(plot);
        }
        state.put("plots", plots);
        state.put("harvestHistory", new JSONArray());
        state.put("totalHarvests", 0);
        return state;
    }

    private void updateGrowth() throws Exception {
        long now = System.currentTimeMillis();
        long lastUpdate = gameState.optLong("lastUpdateTime", now);
        long elapsedMinutes = (now - lastUpdate) / 60000;

        if (elapsedMinutes <= 0) return;

        JSONArray plots = gameState.getJSONArray("plots");
        for (int i = 0; i < plots.length(); i++) {
            JSONObject plot = plots.getJSONObject(i);
            if (plot.optBoolean("empty", true)) continue;

            int waterLevel = plot.optInt("waterLevel", 0);
            int fertilizerLevel = plot.optInt("fertilizerLevel", 0);
            int weedLevel = plot.optInt("weedLevel", 0);

            long growthMinutes = elapsedMinutes;
            if (waterLevel > 0) growthMinutes = (long)(growthMinutes * 1.2);
            if (fertilizerLevel > 0) growthMinutes = (long)(growthMinutes * 1.5);
            if (weedLevel > 2) growthMinutes = (long)(growthMinutes * 0.5);

            long accumulatedGrowth = plot.optLong("accumulatedGrowth", 0) + growthMinutes;
            plot.put("accumulatedGrowth", accumulatedGrowth);

            int maxStages = plot.optInt("maxStages", 4);
            int baseGrowTime = plot.optInt("baseGrowTime", 30);
            int currentStage = (int) Math.min(maxStages, accumulatedGrowth / baseGrowTime);
            plot.put("currentStage", currentStage);
            plot.put("mature", currentStage >= maxStages);

            int newWaterLevel = Math.max(0, waterLevel - (int)(elapsedMinutes / 60));
            plot.put("waterLevel", newWaterLevel);
            int newFertLevel = Math.max(0, fertilizerLevel - (int)(elapsedMinutes / 120));
            plot.put("fertilizerLevel", newFertLevel);
            int newWeedLevel = Math.min(5, weedLevel + (int)(elapsedMinutes / 180));
            plot.put("weedLevel", newWeedLevel);
        }

        gameState.put("lastUpdateTime", now);
    }

    private String plant(Context context, int plotIndex, String seedType) throws Exception {
        ensureLoaded(context);
        if (plotIndex < 0 || plotIndex >= PLOT_COUNT) return error("无效地块编号，范围 0-" + (PLOT_COUNT - 1));

        String[] cropInfo = findCropInfo(seedType);
        if (cropInfo == null) {
            StringBuilder sb = new StringBuilder("无效种子类型，可选: ");
            for (String[] cd : CROP_DATA) sb.append(cd[0]).append("/");
            return error(sb.toString());
        }

        JSONArray plots = gameState.getJSONArray("plots");
        JSONObject plot = plots.getJSONObject(plotIndex);
        if (!plot.optBoolean("empty", true)) return error("该地块已种植，请先收获或等待");

        plot.put("empty", false);
        plot.put("seedType", cropInfo[0]);
        plot.put("cropName", cropInfo[1]);
        plot.put("cropEmoji", cropInfo[2]);
        plot.put("maxStages", Integer.parseInt(cropInfo[3]));
        plot.put("baseGrowTime", Integer.parseInt(cropInfo[4]));
        plot.put("harvestYield", Integer.parseInt(cropInfo[5]));
        plot.put("currentStage", 0);
        plot.put("mature", false);
        plot.put("plantTime", System.currentTimeMillis());
        plot.put("accumulatedGrowth", 0);
        plot.put("waterLevel", 0);
        plot.put("fertilizerLevel", 0);
        plot.put("weedLevel", 0);

        gameState.put("lastUpdateTime", System.currentTimeMillis());
        saveToFile(context);
        return ok(buildDisplayState(), formatDisplay());
    }

    private String water(Context context, int plotIndex) throws Exception {
        ensureLoaded(context);
        if (plotIndex < 0 || plotIndex >= PLOT_COUNT) return error("无效地块编号");

        JSONObject plot = gameState.getJSONArray("plots").getJSONObject(plotIndex);
        if (plot.optBoolean("empty", true)) return error("该地块为空，请先种植");

        int waterLevel = Math.min(5, plot.optInt("waterLevel", 0) + 1);
        plot.put("waterLevel", waterLevel);
        updateGrowth();
        saveToFile(context);

        JSONObject result = buildDisplayState();
        result.put("message", "浇水成功！💧 当前水分等级: " + waterLevel);
        return ok(result, formatDisplay());
    }

    private String fertilize(Context context, int plotIndex) throws Exception {
        ensureLoaded(context);
        if (plotIndex < 0 || plotIndex >= PLOT_COUNT) return error("无效地块编号");

        JSONObject plot = gameState.getJSONArray("plots").getJSONObject(plotIndex);
        if (plot.optBoolean("empty", true)) return error("该地块为空，请先种植");

        int fertLevel = Math.min(5, plot.optInt("fertilizerLevel", 0) + 1);
        plot.put("fertilizerLevel", fertLevel);
        updateGrowth();
        saveToFile(context);

        JSONObject result = buildDisplayState();
        result.put("message", "施肥成功！🧪 当前肥料等级: " + fertLevel);
        return ok(result, formatDisplay());
    }

    private String weed(Context context, int plotIndex) throws Exception {
        ensureLoaded(context);
        if (plotIndex < 0 || plotIndex >= PLOT_COUNT) return error("无效地块编号");

        JSONObject plot = gameState.getJSONArray("plots").getJSONObject(plotIndex);
        if (plot.optBoolean("empty", true)) return error("该地块为空");

        plot.put("weedLevel", 0);
        updateGrowth();
        saveToFile(context);

        JSONObject result = buildDisplayState();
        result.put("message", "除草完成！🌿 杂草已清除");
        return ok(result, formatDisplay());
    }

    private String harvest(Context context, int plotIndex) throws Exception {
        ensureLoaded(context);
        if (plotIndex < 0 || plotIndex >= PLOT_COUNT) return error("无效地块编号");

        JSONObject plot = gameState.getJSONArray("plots").getJSONObject(plotIndex);
        if (plot.optBoolean("empty", true)) return error("该地块为空");
        if (!plot.optBoolean("mature", false)) return error("作物尚未成熟，请继续照料");

        String cropName = plot.optString("cropName", "未知");
        String cropEmoji = plot.optString("cropEmoji", "🌱");
        int yield = plot.optInt("harvestYield", 1);

        int qualityBonus = 0;
        if (plot.optInt("weedLevel", 0) == 0) qualityBonus++;
        if (plot.optInt("waterLevel", 0) >= 2) qualityBonus++;
        if (plot.optInt("fertilizerLevel", 0) >= 2) qualityBonus++;

        String[] qualities = {"普通", "良好", "优秀", "完美"};
        String quality = qualities[Math.min(qualityBonus, 3)];
        int finalYield = yield + qualityBonus;

        JSONObject record = new JSONObject();
        record.put("cropName", cropName);
        record.put("cropEmoji", cropEmoji);
        record.put("yield", finalYield);
        record.put("quality", quality);
        record.put("harvestTime", System.currentTimeMillis());

        JSONArray history = gameState.optJSONArray("harvestHistory");
        if (history == null) history = new JSONArray();
        history.put(record);
        gameState.put("harvestHistory", history);
        gameState.put("totalHarvests", gameState.optInt("totalHarvests", 0) + 1);

        plot.put("empty", true);
        plot.remove("seedType");
        plot.remove("cropName");
        plot.remove("cropEmoji");
        plot.remove("currentStage");
        plot.remove("maxStages");
        plot.remove("baseGrowTime");
        plot.remove("harvestYield");
        plot.remove("mature");
        plot.remove("plantTime");
        plot.remove("accumulatedGrowth");
        plot.remove("waterLevel");
        plot.remove("fertilizerLevel");
        plot.remove("weedLevel");

        saveToFile(context);

        JSONObject result = buildDisplayState();
        result.put("message", "收获了 " + finalYield + " 个" + cropEmoji + cropName + "！品质: " + quality);
        return ok(result, formatDisplay());
    }

    private String getState(Context context) throws Exception {
        ensureLoaded(context);
        updateGrowth();
        return ok(buildDisplayState(), formatDisplay());
    }

    private String getHarvestHistory(Context context) throws Exception {
        ensureLoaded(context);
        JSONObject result = new JSONObject();
        JSONArray history = gameState.optJSONArray("harvestHistory");
        result.put("harvestHistory", history != null ? history : new JSONArray());
        result.put("totalHarvests", gameState.optInt("totalHarvests", 0));

        StringBuilder sb = new StringBuilder();
        sb.append("📋 收获记录\n");
        sb.append("━━━━━━━━━━━━━━━━━━━\n");
        sb.append("总收获次数: ").append(gameState.optInt("totalHarvests", 0)).append("\n\n");

        if (history == null || history.length() == 0) {
            sb.append("暂无收获记录");
        } else {
            int showCount = Math.min(history.length(), 20);
            for (int i = history.length() - 1; i >= history.length() - showCount; i--) {
                JSONObject r = history.getJSONObject(i);
                sb.append(r.optString("cropEmoji", "")).append(" ");
                sb.append(r.optString("cropName", "")).append(" x");
                sb.append(r.optInt("yield", 0)).append(" [");
                sb.append(r.optString("quality", "")).append("]\n");
            }
        }
        result.put("_historyDisplay", sb.toString());
        return ok(result, sb.toString());
    }

    private String saveGame(Context context) throws Exception {
        ensureLoaded(context);
        gameState.put("lastUpdateTime", System.currentTimeMillis());
        saveToFile(context);
        JSONObject result = buildDisplayState();
        result.put("message", "游戏进度已保存！");
        return ok(result, "✅ 游戏进度已保存！");
    }

    private void ensureLoaded(Context context) throws Exception {
        if (!gameLoaded || gameState == null) {
            if (random == null) random = new Random();
            JSONObject saved = loadFromFile(context);
            if (saved != null) {
                gameState = saved;
                gameLoaded = true;
            } else {
                throw new IllegalStateException("游戏未开始，请先调用 startGame 或 enterGame");
            }
        }
    }

    private JSONObject buildDisplayState() throws Exception {
        updateGrowth();
        JSONObject state = new JSONObject();
        state.put("gameLoaded", gameLoaded);
        state.put("totalHarvests", gameState.optInt("totalHarvests", 0));

        JSONArray plotsDisplay = new JSONArray();
        JSONArray plots = gameState.getJSONArray("plots");
        for (int i = 0; i < plots.length(); i++) {
            JSONObject plot = plots.getJSONObject(i);
            JSONObject pd = new JSONObject();
            pd.put("index", i);
            pd.put("empty", plot.optBoolean("empty", true));
            if (!plot.optBoolean("empty", true)) {
                pd.put("seedType", plot.optString("seedType", ""));
                pd.put("cropName", plot.optString("cropName", ""));
                pd.put("cropEmoji", plot.optString("cropEmoji", ""));
                pd.put("currentStage", plot.optInt("currentStage", 0));
                pd.put("maxStages", plot.optInt("maxStages", 4));
                pd.put("mature", plot.optBoolean("mature", false));
                pd.put("waterLevel", plot.optInt("waterLevel", 0));
                pd.put("fertilizerLevel", plot.optInt("fertilizerLevel", 0));
                pd.put("weedLevel", plot.optInt("weedLevel", 0));
            }
            plotsDisplay.put(pd);
        }
        state.put("plots", plotsDisplay);
        return state;
    }

    private String formatDisplay() throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("🌾 种菜游戏\n");
        sb.append("━━━━━━━━━━━━━━━━━━━\n");
        sb.append("收获总计: ").append(gameState.optInt("totalHarvests", 0)).append(" 次\n\n");

        sb.append("┌─────────────────────────────────┐\n");
        JSONArray plots = gameState.getJSONArray("plots");
        for (int i = 0; i < plots.length(); i++) {
            JSONObject plot = plots.getJSONObject(i);
            sb.append("│ 地块 ").append(i).append(": ");
            if (plot.optBoolean("empty", true)) {
                sb.append("🟫 空地（可种植）");
                sb.append(padTo(30 - 12));
            } else {
                String emoji = plot.optString("cropEmoji", "🌱");
                String name = plot.optString("cropName", "");
                int stage = plot.optInt("currentStage", 0);
                int maxStage = plot.optInt("maxStages", 4);
                boolean mature = plot.optBoolean("mature", false);

                String growthEmoji = stage < GROWTH_EMOJIS.length ? GROWTH_EMOJIS[stage] : "🌾";
                if (mature) growthEmoji = emoji;

                sb.append(growthEmoji).append(" ").append(name);
                sb.append(" [").append(stage).append("/").append(maxStage).append("]");
                if (mature) sb.append(" ✅可收获");

                int water = plot.optInt("waterLevel", 0);
                int fert = plot.optInt("fertilizerLevel", 0);
                int weed = plot.optInt("weedLevel", 0);
                sb.append("\n│        ");
                sb.append("💧").append(water);
                sb.append(" 🧪").append(fert);
                sb.append(" 🌿").append(weed > 0 ? "杂草x" + weed : "无");
            }
            sb.append("\n");
            if (i < plots.length() - 1) sb.append("│─────────────────────────────────│\n");
        }
        sb.append("└─────────────────────────────────┘\n");

        sb.append("\n可用种子: ");
        for (String[] cd : CROP_DATA) {
            sb.append(cd[2]).append(cd[1]).append("(").append(cd[0]).append(") ");
        }
        sb.append("\n操作: plant | water | fertilize | weed | harvest | saveGame");
        return sb.toString();
    }

    private String padTo(int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.max(0, len); i++) sb.append(" ");
        return sb.toString();
    }

    private String[] findCropInfo(String seedType) {
        if (seedType == null || seedType.isEmpty()) return null;
        for (String[] cd : CROP_DATA) {
            if (cd[0].equalsIgnoreCase(seedType)) return cd;
        }
        return null;
    }

    private File getSaveDir() {
        File dir = new File(Environment.getExternalStorageDirectory(), SAVE_DIR);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private void saveToFile(Context context) throws Exception {
        File file = new File(getSaveDir(), SAVE_FILE);
        OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(file), "UTF-8");
        try {
            writer.write(gameState.toString(2));
        } finally {
            writer.close();
        }
    }

    private JSONObject loadFromFile(Context context) throws Exception {
        File file = new File(getSaveDir(), SAVE_FILE);
        if (!file.exists()) return null;

        InputStreamReader reader = new InputStreamReader(new FileInputStream(file), "UTF-8");
        try {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int read;
            while ((read = reader.read(buf)) != -1) {
                sb.append(buf, 0, read);
            }
            return new JSONObject(sb.toString());
        } finally {
            reader.close();
        }
    }

    private String emptyJson(String value) {
        return value == null || value.trim().isEmpty() ? "{}" : value;
    }

    private String ok(JSONObject output, String displayText) throws Exception {
        JSONObject result = new JSONObject()
                .put("success", true)
                .put("output", output.toString());
        if (displayText != null && !displayText.isEmpty()) {
            result.put("_displayText", displayText);
        }
        return result.toString();
    }

    private String error(String message) throws Exception {
        return new JSONObject()
                .put("success", false)
                .put("error", message)
                .toString();
    }
}
