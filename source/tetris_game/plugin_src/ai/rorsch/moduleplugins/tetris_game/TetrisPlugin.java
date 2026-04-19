package ai.rorsch.moduleplugins.tetris_game;

import android.content.Context;
import ai.rorsch.pandagenie.module.runtime.HtmlOutputHelper;
import ai.rorsch.pandagenie.module.runtime.ModulePlugin;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Random;

public class TetrisPlugin implements ModulePlugin {

    private static final int BOARD_WIDTH = 10;
    private static final int BOARD_HEIGHT = 20;

    private static final int[][][][] PIECES = {
        // I
        {{{0,0},{1,0},{2,0},{3,0}}, {{0,0},{0,1},{0,2},{0,3}}},
        // O
        {{{0,0},{1,0},{0,1},{1,1}}},
        // T
        {{{0,0},{1,0},{2,0},{1,1}}, {{0,0},{0,1},{0,2},{1,1}},
         {{1,0},{0,1},{1,1},{2,1}}, {{1,0},{1,1},{1,2},{0,1}}},
        // S
        {{{1,0},{2,0},{0,1},{1,1}}, {{0,0},{0,1},{1,1},{1,2}}},
        // Z
        {{{0,0},{1,0},{1,1},{2,1}}, {{1,0},{0,1},{1,1},{0,2}}},
        // L
        {{{0,0},{0,1},{0,2},{1,2}}, {{0,0},{1,0},{2,0},{0,1}},
         {{0,0},{1,0},{1,1},{1,2}}, {{2,0},{0,1},{1,1},{2,1}}},
        // J
        {{{1,0},{1,1},{0,2},{1,2}}, {{0,0},{0,1},{1,1},{2,1}},
         {{0,0},{1,0},{0,1},{0,2}}, {{0,0},{1,0},{2,0},{2,1}}}
    };

    private static final char[] PIECE_CHARS = {'I', 'O', 'T', 'S', 'Z', 'L', 'J'};

    private int[][] board;
    private int currentPiece;
    private int currentRotation;
    private int currentX, currentY;
    private int nextPiece;
    private int score;
    private int linesCleared;
    private int level;
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
                case "rotate":
                    return rotate();
                case "drop":
                    return drop();
                case "tick":
                    return tick();
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
        board = new int[BOARD_HEIGHT][BOARD_WIDTH];
        for (int y = 0; y < BOARD_HEIGHT; y++)
            for (int x = 0; x < BOARD_WIDTH; x++)
                board[y][x] = -1;
        score = 0;
        linesCleared = 0;
        level = difficulty;
        gameOver = false;
        gameStarted = true;
        nextPiece = random.nextInt(PIECES.length);
        spawnPiece();
    }

    private void spawnPiece() {
        currentPiece = nextPiece;
        nextPiece = random.nextInt(PIECES.length);
        currentRotation = 0;
        currentX = BOARD_WIDTH / 2 - 1;
        currentY = 0;

        if (!canPlace(currentPiece, currentRotation, currentX, currentY)) {
            gameOver = true;
        }
    }

    private boolean canPlace(int piece, int rotation, int px, int py) {
        int[][] shape = getShape(piece, rotation);
        for (int[] cell : shape) {
            int x = px + cell[0];
            int y = py + cell[1];
            if (x < 0 || x >= BOARD_WIDTH || y < 0 || y >= BOARD_HEIGHT) return false;
            if (board[y][x] != -1) return false;
        }
        return true;
    }

    private int[][] getShape(int piece, int rotation) {
        int rotCount = PIECES[piece].length;
        return PIECES[piece][rotation % rotCount];
    }

    private void lockPiece() {
        int[][] shape = getShape(currentPiece, currentRotation);
        for (int[] cell : shape) {
            int x = currentX + cell[0];
            int y = currentY + cell[1];
            if (y >= 0 && y < BOARD_HEIGHT && x >= 0 && x < BOARD_WIDTH) {
                board[y][x] = currentPiece;
            }
        }
        clearLines();
        spawnPiece();
    }

    private void clearLines() {
        int cleared = 0;
        for (int y = BOARD_HEIGHT - 1; y >= 0; y--) {
            boolean full = true;
            for (int x = 0; x < BOARD_WIDTH; x++) {
                if (board[y][x] == -1) { full = false; break; }
            }
            if (full) {
                cleared++;
                for (int row = y; row > 0; row--) {
                    System.arraycopy(board[row - 1], 0, board[row], 0, BOARD_WIDTH);
                }
                for (int x = 0; x < BOARD_WIDTH; x++) board[0][x] = -1;
                y++;
            }
        }
        if (cleared > 0) {
            int[] lineScores = {0, 100, 300, 500, 800};
            score += lineScores[Math.min(cleared, 4)] * level;
            linesCleared += cleared;
            level = difficulty + linesCleared / 10;
        }
    }

    private String move(String dir) throws Exception {
        if (!gameStarted) return error("游戏未开始，请先调用 startGame");
        if (gameOver) return ok(buildState(), formatDisplay(), formatDisplayHtml());

        int dx = 0, dy = 0;
        switch (dir) {
            case "left":  dx = -1; break;
            case "right": dx = 1;  break;
            case "down":  dy = 1;  break;
            default: return error("无效方向，请使用 left/right/down");
        }

        if (canPlace(currentPiece, currentRotation, currentX + dx, currentY + dy)) {
            currentX += dx;
            currentY += dy;
        } else if (dy == 1) {
            lockPiece();
        }
        return ok(buildState(), formatDisplay(), formatDisplayHtml());
    }

    private String rotate() throws Exception {
        if (!gameStarted) return error("游戏未开始，请先调用 startGame");
        if (gameOver) return ok(buildState(), formatDisplay(), formatDisplayHtml());

        int newRot = (currentRotation + 1) % PIECES[currentPiece].length;
        if (canPlace(currentPiece, newRot, currentX, currentY)) {
            currentRotation = newRot;
        } else if (canPlace(currentPiece, newRot, currentX - 1, currentY)) {
            currentRotation = newRot;
            currentX--;
        } else if (canPlace(currentPiece, newRot, currentX + 1, currentY)) {
            currentRotation = newRot;
            currentX++;
        }
        return ok(buildState(), formatDisplay(), formatDisplayHtml());
    }

    private String drop() throws Exception {
        if (!gameStarted) return error("游戏未开始，请先调用 startGame");
        if (gameOver) return ok(buildState(), formatDisplay(), formatDisplayHtml());

        while (canPlace(currentPiece, currentRotation, currentX, currentY + 1)) {
            currentY++;
            score += 2;
        }
        lockPiece();
        return ok(buildState(), formatDisplay(), formatDisplayHtml());
    }

    private String tick() throws Exception {
        if (!gameStarted) return error("游戏未开始，请先调用 startGame");
        if (gameOver) return ok(buildState(), formatDisplay(), formatDisplayHtml());

        if (canPlace(currentPiece, currentRotation, currentX, currentY + 1)) {
            currentY++;
        } else {
            lockPiece();
        }
        return ok(buildState(), formatDisplay(), formatDisplayHtml());
    }

    private String getState() throws Exception {
        if (!gameStarted) return error("游戏未开始，请先调用 startGame");
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
        state.put("level", level);
        state.put("linesCleared", linesCleared);
        state.put("difficulty", difficulty);
        state.put("boardWidth", BOARD_WIDTH);
        state.put("boardHeight", BOARD_HEIGHT);
        state.put("currentPiece", String.valueOf(PIECE_CHARS[currentPiece]));
        state.put("nextPiece", String.valueOf(PIECE_CHARS[nextPiece]));
        state.put("board", renderBoard());
        return state;
    }

    private String renderBoard() {
        char[][] display = new char[BOARD_HEIGHT][BOARD_WIDTH];
        for (int y = 0; y < BOARD_HEIGHT; y++) {
            for (int x = 0; x < BOARD_WIDTH; x++) {
                display[y][x] = board[y][x] == -1 ? '·' : '█';
            }
        }

        if (!gameOver) {
            int[][] shape = getShape(currentPiece, currentRotation);
            for (int[] cell : shape) {
                int x = currentX + cell[0];
                int y = currentY + cell[1];
                if (x >= 0 && x < BOARD_WIDTH && y >= 0 && y < BOARD_HEIGHT) {
                    display[y][x] = '▓';
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("┌");
        for (int x = 0; x < BOARD_WIDTH; x++) sb.append("──");
        sb.append("┐\n");

        for (int y = 0; y < BOARD_HEIGHT; y++) {
            sb.append("│");
            for (int x = 0; x < BOARD_WIDTH; x++) {
                sb.append(display[y][x]).append(' ');
            }
            sb.append("│\n");
        }

        sb.append("└");
        for (int x = 0; x < BOARD_WIDTH; x++) sb.append("──");
        sb.append("┘");
        return sb.toString();
    }

    private String formatDisplay() throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("🧱 俄罗斯方块\n");
        sb.append("━━━━━━━━━━━━━━━━━━━\n");
        String[] diffNames = {"简单", "普通", "困难"};
        sb.append("难度: ").append(diffNames[difficulty - 1]).append(" | ");
        sb.append("等级: ").append(level).append(" | ");
        sb.append("得分: ").append(score).append(" | ");
        sb.append("消行: ").append(linesCleared).append("\n");
        sb.append("当前: ").append(PIECE_CHARS[currentPiece]);
        sb.append(" | 下一个: ").append(PIECE_CHARS[nextPiece]).append("\n\n");
        sb.append(renderBoard()).append("\n");

        if (gameOver) {
            sb.append("\n💀 游戏结束！最终得分: ").append(score);
            sb.append(" | 消除行数: ").append(linesCleared);
            sb.append("\n输入 restart 重新开始");
        } else {
            sb.append("\n操作: left/right/down | rotate | drop");
        }
        return sb.toString();
    }

    private String formatDisplayHtml() {
        String[] diffNames = {"简单", "普通", "困难"};
        String body = HtmlOutputHelper.metricGrid(new String[][]{
                {String.valueOf(score), "得分"},
                {String.valueOf(linesCleared), "消行"},
                {String.valueOf(level), "等级"}
        });
        body += HtmlOutputHelper.keyValue(new String[][]{
                {"难度", diffNames[difficulty - 1]},
                {"当前", String.valueOf(PIECE_CHARS[currentPiece])},
                {"下一个", String.valueOf(PIECE_CHARS[nextPiece])},
                {"状态", gameOver ? "游戏结束" : "进行中"}
        });
        if (gameOver) {
            body += HtmlOutputHelper.errorBadge() + HtmlOutputHelper.p("得分 " + score + " · 消行 " + linesCleared + " · restart 重开");
        } else {
            body += HtmlOutputHelper.muted("left / right / down · rotate · drop · tick");
        }
        return HtmlOutputHelper.card("🧱", "俄罗斯方块", body);
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
