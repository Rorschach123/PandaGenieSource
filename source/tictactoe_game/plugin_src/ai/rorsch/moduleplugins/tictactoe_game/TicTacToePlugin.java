package ai.rorsch.moduleplugins.tictactoe_game;

import android.content.Context;
import ai.rorsch.pandagenie.module.runtime.HtmlOutputHelper;
import ai.rorsch.pandagenie.module.runtime.ModulePlugin;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class TicTacToePlugin implements ModulePlugin {

    private static final int SIZE = 3;
    private static final int EMPTY = 0;
    private static final int PLAYER_X = 1;
    private static final int PLAYER_O = 2;

    private int[][] board;
    private boolean gameStarted;
    private boolean gameOver;
    private int winner; // 0=none, 1=X(player), 2=O(AI), 3=draw
    private Random random;

    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        try {
            JSONObject params = new JSONObject(emptyJson(paramsJson));
            switch (action) {
                case "startGame":
                    return startGame();
                case "placeMark":
                    return placeMark(params.optInt("row", -1), params.optInt("col", -1));
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

    private String startGame() throws Exception {
        random = new Random();
        initGame();
        return ok(buildState(), formatDisplay(), formatDisplayHtml());
    }

    private void initGame() {
        board = new int[SIZE][SIZE];
        gameStarted = true;
        gameOver = false;
        winner = 0;
    }

    private String placeMark(int row, int col) throws Exception {
        if (!gameStarted) return error("游戏未开始，请先调用 startGame");
        if (gameOver) return ok(buildState(), formatDisplay(), formatDisplayHtml());
        if (row < 0 || row >= SIZE || col < 0 || col >= SIZE) {
            return error("无效位置，行列范围为 0-2");
        }
        if (board[row][col] != EMPTY) {
            return error("该位置已被占用");
        }

        board[row][col] = PLAYER_X;
        checkWinner();

        if (!gameOver) {
            aiMove();
            checkWinner();
        }

        return ok(buildState(), formatDisplay(), formatDisplayHtml());
    }

    private void aiMove() {
        // Try to win
        int[] move = findBestMove(PLAYER_O);
        if (move != null) { board[move[0]][move[1]] = PLAYER_O; return; }

        // Block player
        move = findBestMove(PLAYER_X);
        if (move != null) { board[move[0]][move[1]] = PLAYER_O; return; }

        // Take center
        if (board[1][1] == EMPTY) { board[1][1] = PLAYER_O; return; }

        // Take corner
        int[][] corners = {{0,0},{0,2},{2,0},{2,2}};
        List<int[]> available = new ArrayList<>();
        for (int[] c : corners) {
            if (board[c[0]][c[1]] == EMPTY) available.add(c);
        }
        if (!available.isEmpty()) {
            int[] c = available.get(random.nextInt(available.size()));
            board[c[0]][c[1]] = PLAYER_O;
            return;
        }

        // Take any
        for (int r = 0; r < SIZE; r++) {
            for (int c = 0; c < SIZE; c++) {
                if (board[r][c] == EMPTY) {
                    board[r][c] = PLAYER_O;
                    return;
                }
            }
        }
    }

    private int[] findBestMove(int player) {
        for (int r = 0; r < SIZE; r++) {
            for (int c = 0; c < SIZE; c++) {
                if (board[r][c] == EMPTY) {
                    board[r][c] = player;
                    if (checkWin(player)) {
                        board[r][c] = EMPTY;
                        return new int[]{r, c};
                    }
                    board[r][c] = EMPTY;
                }
            }
        }
        return null;
    }

    private boolean checkWin(int player) {
        for (int i = 0; i < SIZE; i++) {
            if (board[i][0] == player && board[i][1] == player && board[i][2] == player) return true;
            if (board[0][i] == player && board[1][i] == player && board[2][i] == player) return true;
        }
        if (board[0][0] == player && board[1][1] == player && board[2][2] == player) return true;
        if (board[0][2] == player && board[1][1] == player && board[2][0] == player) return true;
        return false;
    }

    private void checkWinner() {
        if (checkWin(PLAYER_X)) { winner = 1; gameOver = true; return; }
        if (checkWin(PLAYER_O)) { winner = 2; gameOver = true; return; }
        boolean full = true;
        for (int r = 0; r < SIZE; r++)
            for (int c = 0; c < SIZE; c++)
                if (board[r][c] == EMPTY) { full = false; break; }
        if (full) { winner = 3; gameOver = true; }
    }

    private String getState() throws Exception {
        if (!gameStarted) return error("游戏未开始，请先调用 startGame");
        return ok(buildState(), formatDisplay(), formatDisplayHtml());
    }

    private String restart() throws Exception {
        if (random == null) random = new Random();
        initGame();
        return ok(buildState(), formatDisplay(), formatDisplayHtml());
    }

    private JSONObject buildState() throws Exception {
        JSONObject state = new JSONObject();
        state.put("gameStarted", gameStarted);
        state.put("gameOver", gameOver);

        String[] winnerNames = {"none", "player", "ai", "draw"};
        state.put("winner", winnerNames[winner]);

        JSONArray boardArr = new JSONArray();
        for (int r = 0; r < SIZE; r++) {
            JSONArray row = new JSONArray();
            for (int c = 0; c < SIZE; c++) {
                String[] marks = {".", "X", "O"};
                row.put(marks[board[r][c]]);
            }
            boardArr.put(row);
        }
        state.put("board", boardArr);
        state.put("boardDisplay", renderBoard());
        return state;
    }

    private String renderBoard() {
        StringBuilder sb = new StringBuilder();
        String[] marks = {"   ", " X ", " O "};
        sb.append("     0   1   2\n");
        sb.append("   ┌───┬───┬───┐\n");
        for (int r = 0; r < SIZE; r++) {
            sb.append(" ").append(r).append(" │");
            for (int c = 0; c < SIZE; c++) {
                sb.append(marks[board[r][c]]).append("│");
            }
            sb.append("\n");
            if (r < SIZE - 1) sb.append("   ├───┼───┼───┤\n");
        }
        sb.append("   └───┴───┴───┘");
        return sb.toString();
    }

    private String formatDisplay() throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("❌⭕ 井字棋\n");
        sb.append("━━━━━━━━━━━━━━━━━━━\n");
        sb.append("你: X(先手) | 程序: O\n\n");
        sb.append(renderBoard()).append("\n");

        if (gameOver) {
            switch (winner) {
                case 1: sb.append("\n🎉 恭喜你赢了！"); break;
                case 2: sb.append("\n🤖 程序获胜！"); break;
                case 3: sb.append("\n🤝 平局！"); break;
            }
            sb.append("\n输入 restart 重新开始");
        } else {
            sb.append("\n轮到你了，请指定 row(0-2) 和 col(0-2)");
        }
        return sb.toString();
    }

    private String formatDisplayHtml() {
        String statusLine;
        if (gameOver) {
            switch (winner) {
                case 1: statusLine = "你赢了"; break;
                case 2: statusLine = "程序获胜"; break;
                case 3: statusLine = "平局"; break;
                default: statusLine = "已结束";
            }
        } else {
            statusLine = "轮到 X";
        }
        String body = HtmlOutputHelper.keyValue(new String[][]{
                {"状态", statusLine}
        });
        if (gameOver) {
            body += HtmlOutputHelper.p("输入 restart 重新开始");
        } else {
            body += HtmlOutputHelper.muted("你: X · 程序: O · placeMark(row,col)");
        }
        return HtmlOutputHelper.card("❌", "井字棋", body);
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
