package ai.rorsch.moduleplugins.snake_game;

import android.content.Context;
import ai.rorsch.pandagenie.module.runtime.HtmlOutputHelper;
import ai.rorsch.pandagenie.module.runtime.ModulePlugin;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class SnakePlugin implements ModulePlugin {

    private static final int BOARD_WIDTH = 20;
    private static final int BOARD_HEIGHT = 15;

    private List<int[]> snake;
    private int[] food;
    private String direction;
    private int score;
    private boolean gameOver;
    private boolean gameStarted;
    private int difficulty;
    private Random random;

    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        try {
            JSONObject params = new JSONObject(emptyJson(paramsJson));
            switch (action) {
                case "startGame":
                    return startGame(params.optInt("difficulty", 2));
                case "move":
                    return move(params.optString("direction", ""));
                case "getState":
                    return getState();
                case "restart":
                    return restart();
                default:
                    return error("Unsupported action: " + action);
            }
        } catch (Exception e) {
            String msg = e.getMessage();
            return error(msg != null && !msg.isEmpty() ? msg : e.getClass().getSimpleName());
        }
    }

    private String startGame(int diff) throws Exception {
        difficulty = Math.max(1, Math.min(3, diff));
        random = new Random();
        initGame();
        return ok(buildState(), formatDisplay(), formatDisplayHtml());
    }

    private void initGame() {
        snake = new ArrayList<>();
        int startX = BOARD_WIDTH / 2;
        int startY = BOARD_HEIGHT / 2;
        snake.add(new int[]{startX, startY});
        snake.add(new int[]{startX - 1, startY});
        snake.add(new int[]{startX - 2, startY});
        direction = "right";
        score = 0;
        gameOver = false;
        gameStarted = true;
        spawnFood();
    }

    private void spawnFood() {
        boolean valid;
        int fx, fy;
        do {
            fx = random.nextInt(BOARD_WIDTH);
            fy = random.nextInt(BOARD_HEIGHT);
            valid = true;
            for (int[] seg : snake) {
                if (seg[0] == fx && seg[1] == fy) {
                    valid = false;
                    break;
                }
            }
        } while (!valid);
        food = new int[]{fx, fy};
    }

    private String move(String dir) throws Exception {
        if (!gameStarted) {
            return error("Game not started. Call startGame first.");
        }
        if (gameOver) {
            return ok(buildState(), formatDisplay(), formatDisplayHtml());
        }

        if (dir == null || dir.isEmpty()) {
            dir = direction;
        }

        if ((dir.equals("up") && direction.equals("down")) ||
            (dir.equals("down") && direction.equals("up")) ||
            (dir.equals("left") && direction.equals("right")) ||
            (dir.equals("right") && direction.equals("left"))) {
            // cannot reverse
        } else {
            direction = dir;
        }

        int[] head = snake.get(0);
        int newX = head[0], newY = head[1];
        switch (direction) {
            case "up":    newY--; break;
            case "down":  newY++; break;
            case "left":  newX--; break;
            case "right": newX++; break;
            default: return error("Invalid direction: " + dir);
        }

        if (newX < 0 || newX >= BOARD_WIDTH || newY < 0 || newY >= BOARD_HEIGHT) {
            gameOver = true;
            return ok(buildState(), formatDisplay(), formatDisplayHtml());
        }

        for (int[] seg : snake) {
            if (seg[0] == newX && seg[1] == newY) {
                gameOver = true;
                return ok(buildState(), formatDisplay(), formatDisplayHtml());
            }
        }

        snake.add(0, new int[]{newX, newY});

        if (newX == food[0] && newY == food[1]) {
            int baseScore = 10;
            score += baseScore * difficulty;
            spawnFood();
        } else {
            snake.remove(snake.size() - 1);
        }

        return ok(buildState(), formatDisplay(), formatDisplayHtml());
    }

    private String getState() throws Exception {
        if (!gameStarted) {
            return error("Game not started. Call startGame first.");
        }
        return ok(buildState(), formatDisplay(), formatDisplayHtml());
    }

    private String restart() throws Exception {
        if (difficulty == 0) difficulty = 2;
        if (random == null) random = new Random();
        initGame();
        return ok(buildState(), formatDisplay(), formatDisplayHtml());
    }

    private JSONObject buildState() throws Exception {
        JSONObject state = new JSONObject();
        state.put("gameStarted", gameStarted);
        state.put("gameOver", gameOver);
        state.put("score", score);
        state.put("difficulty", difficulty);
        state.put("direction", direction);
        state.put("boardWidth", BOARD_WIDTH);
        state.put("boardHeight", BOARD_HEIGHT);
        state.put("snakeLength", snake.size());

        JSONArray snakeArr = new JSONArray();
        for (int[] seg : snake) {
            JSONObject s = new JSONObject();
            s.put("x", seg[0]);
            s.put("y", seg[1]);
            snakeArr.put(s);
        }
        state.put("snake", snakeArr);

        if (food != null) {
            JSONObject f = new JSONObject();
            f.put("x", food[0]);
            f.put("y", food[1]);
            state.put("food", f);
        }

        state.put("board", renderBoard());
        return state;
    }

    private String renderBoard() {
        StringBuilder sb = new StringBuilder();
        char[][] grid = new char[BOARD_HEIGHT][BOARD_WIDTH];
        for (int y = 0; y < BOARD_HEIGHT; y++) {
            for (int x = 0; x < BOARD_WIDTH; x++) {
                grid[y][x] = '·';
            }
        }
        for (int i = 0; i < snake.size(); i++) {
            int[] seg = snake.get(i);
            if (seg[0] >= 0 && seg[0] < BOARD_WIDTH && seg[1] >= 0 && seg[1] < BOARD_HEIGHT) {
                grid[seg[1]][seg[0]] = i == 0 ? '●' : '○';
            }
        }
        if (food != null) {
            grid[food[1]][food[0]] = '★';
        }

        sb.append("┌");
        for (int x = 0; x < BOARD_WIDTH; x++) sb.append("─");
        sb.append("┐\n");

        for (int y = 0; y < BOARD_HEIGHT; y++) {
            sb.append("│");
            for (int x = 0; x < BOARD_WIDTH; x++) {
                sb.append(grid[y][x]);
            }
            sb.append("│\n");
        }

        sb.append("└");
        for (int x = 0; x < BOARD_WIDTH; x++) sb.append("─");
        sb.append("┘");

        return sb.toString();
    }

    private String formatDisplay() throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("🐍 贪吃蛇\n");
        sb.append("━━━━━━━━━━━━━━━━━━━\n");
        String[] diffNames = {"简单", "普通", "困难"};
        sb.append("难度: ").append(diffNames[difficulty - 1]).append(" | ");
        sb.append("得分: ").append(score).append(" | ");
        sb.append("长度: ").append(snake.size()).append("\n\n");
        sb.append(renderBoard()).append("\n");

        if (gameOver) {
            sb.append("\n💀 游戏结束！最终得分: ").append(score);
            sb.append("\n输入 restart 重新开始");
        } else {
            sb.append("\n方向键: up/down/left/right");
        }
        return sb.toString();
    }

    private String formatDisplayHtml() {
        String[] diffNames = {"简单", "普通", "困难"};
        String body = HtmlOutputHelper.metricGrid(new String[][]{
                {String.valueOf(score), "得分"},
                {String.valueOf(snake.size()), "长度"},
                {diffNames[difficulty - 1], "难度"}
        });
        body += HtmlOutputHelper.keyValue(new String[][]{
                {"方向", direction},
                {"状态", gameOver ? "游戏结束" : "进行中"}
        });
        if (gameOver) {
            body += HtmlOutputHelper.errorBadge() + HtmlOutputHelper.p("最终得分: " + score + " · restart 重开");
        } else {
            body += HtmlOutputHelper.muted("move: up / down / left / right");
        }
        return HtmlOutputHelper.card("🐍", "贪吃蛇", body);
    }

    private String emptyJson(String value) {
        return value == null || value.trim().isEmpty() ? "{}" : value;
    }

    private String ok(JSONObject output, String displayText, String displayHtml) throws Exception {
        JSONObject result = new JSONObject()
                .put("success", true)
                .put("output", output.toString());
        if (displayText != null && !displayText.isEmpty()) {
            result.put("_displayText", displayText);
        }
        if (displayHtml != null && !displayHtml.isEmpty()) {
            result.put("_displayHtml", displayHtml);
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
