package ai.rorsch.moduleplugins.gomoku_game;

import android.content.Context;
import ai.rorsch.pandagenie.module.runtime.ModulePlugin;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class GomokuPlugin implements ModulePlugin {

    private static final int SIZE = 15;
    private static final int EMPTY = 0;
    private static final int BLACK = 1;
    private static final int WHITE = 2;

    private static final int[][] DIRECTIONS = {{1,0},{0,1},{1,1},{1,-1}};

    private int[][] board;
    private boolean gameStarted;
    private boolean gameOver;
    private int winner; // 0=none, 1=black(player), 2=white(AI), 3=draw
    private int moveCount;
    private Random random;

    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        try {
            JSONObject params = new JSONObject(emptyJson(paramsJson));
            switch (action) {
                case "startGame":
                    return startGame();
                case "placeStone":
                    return placeStone(params.optInt("row", -1), params.optInt("col", -1));
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
        return ok(buildState(), formatDisplay());
    }

    private void initGame() {
        board = new int[SIZE][SIZE];
        gameStarted = true;
        gameOver = false;
        winner = 0;
        moveCount = 0;
    }

    private String placeStone(int row, int col) throws Exception {
        if (!gameStarted) return error("游戏未开始，请先调用 startGame");
        if (gameOver) return ok(buildState(), formatDisplay());
        if (row < 0 || row >= SIZE || col < 0 || col >= SIZE) return error("无效位置，行列范围为 0-14");
        if (board[row][col] != EMPTY) return error("该位置已有棋子");

        board[row][col] = BLACK;
        moveCount++;
        if (checkWin(row, col, BLACK)) {
            winner = 1;
            gameOver = true;
            return ok(buildState(), formatDisplay());
        }

        if (moveCount >= SIZE * SIZE) {
            winner = 3;
            gameOver = true;
            return ok(buildState(), formatDisplay());
        }

        int[] aiMove = findAiMove();
        if (aiMove != null) {
            board[aiMove[0]][aiMove[1]] = WHITE;
            moveCount++;
            if (checkWin(aiMove[0], aiMove[1], WHITE)) {
                winner = 2;
                gameOver = true;
            }
        }

        if (moveCount >= SIZE * SIZE && !gameOver) {
            winner = 3;
            gameOver = true;
        }

        return ok(buildState(), formatDisplay());
    }

    private boolean checkWin(int row, int col, int player) {
        for (int[] dir : DIRECTIONS) {
            int count = 1;
            for (int i = 1; i < 5; i++) {
                int r = row + dir[0] * i, c = col + dir[1] * i;
                if (r < 0 || r >= SIZE || c < 0 || c >= SIZE || board[r][c] != player) break;
                count++;
            }
            for (int i = 1; i < 5; i++) {
                int r = row - dir[0] * i, c = col - dir[1] * i;
                if (r < 0 || r >= SIZE || c < 0 || c >= SIZE || board[r][c] != player) break;
                count++;
            }
            if (count >= 5) return true;
        }
        return false;
    }

    private int[] findAiMove() {
        // 1. Win if possible
        int[] move = findThreat(WHITE, 5);
        if (move != null) return move;

        // 2. Block player's five
        move = findThreat(BLACK, 5);
        if (move != null) return move;

        // 3. Create open four
        move = findThreat(WHITE, 4);
        if (move != null) return move;

        // 4. Block player's four
        move = findThreat(BLACK, 4);
        if (move != null) return move;

        // 5. Create open three
        move = findThreat(WHITE, 3);
        if (move != null) return move;

        // 6. Block player's three
        move = findThreat(BLACK, 3);
        if (move != null) return move;

        // 7. Score-based placement near existing stones
        return findBestScoreMove();
    }

    private int[] findThreat(int player, int targetLen) {
        for (int r = 0; r < SIZE; r++) {
            for (int c = 0; c < SIZE; c++) {
                if (board[r][c] != EMPTY) continue;
                board[r][c] = player;
                if (targetLen == 5 && checkWin(r, c, player)) {
                    board[r][c] = EMPTY;
                    return new int[]{r, c};
                }
                if (targetLen < 5 && countMaxLine(r, c, player) >= targetLen) {
                    board[r][c] = EMPTY;
                    return new int[]{r, c};
                }
                board[r][c] = EMPTY;
            }
        }
        return null;
    }

    private int countMaxLine(int row, int col, int player) {
        int max = 0;
        for (int[] dir : DIRECTIONS) {
            int count = 1;
            for (int i = 1; i < 5; i++) {
                int r = row + dir[0] * i, c = col + dir[1] * i;
                if (r < 0 || r >= SIZE || c < 0 || c >= SIZE || board[r][c] != player) break;
                count++;
            }
            for (int i = 1; i < 5; i++) {
                int r = row - dir[0] * i, c = col - dir[1] * i;
                if (r < 0 || r >= SIZE || c < 0 || c >= SIZE || board[r][c] != player) break;
                count++;
            }
            if (count > max) max = count;
        }
        return max;
    }

    private int[] findBestScoreMove() {
        int bestScore = -1;
        List<int[]> bestMoves = new ArrayList<>();

        for (int r = 0; r < SIZE; r++) {
            for (int c = 0; c < SIZE; c++) {
                if (board[r][c] != EMPTY) continue;
                if (!hasNeighbor(r, c, 2)) continue;

                int score = evaluatePosition(r, c);
                if (score > bestScore) {
                    bestScore = score;
                    bestMoves.clear();
                    bestMoves.add(new int[]{r, c});
                } else if (score == bestScore) {
                    bestMoves.add(new int[]{r, c});
                }
            }
        }

        if (bestMoves.isEmpty()) {
            if (board[SIZE / 2][SIZE / 2] == EMPTY) return new int[]{SIZE / 2, SIZE / 2};
            for (int r = 0; r < SIZE; r++)
                for (int c = 0; c < SIZE; c++)
                    if (board[r][c] == EMPTY) return new int[]{r, c};
            return null;
        }

        return bestMoves.get(random.nextInt(bestMoves.size()));
    }

    private boolean hasNeighbor(int row, int col, int dist) {
        for (int r = Math.max(0, row - dist); r <= Math.min(SIZE - 1, row + dist); r++)
            for (int c = Math.max(0, col - dist); c <= Math.min(SIZE - 1, col + dist); c++)
                if (board[r][c] != EMPTY) return true;
        return false;
    }

    private int evaluatePosition(int row, int col) {
        int score = 0;
        int centerDist = Math.abs(row - SIZE / 2) + Math.abs(col - SIZE / 2);
        score += (SIZE - centerDist);

        for (int player = 1; player <= 2; player++) {
            board[row][col] = player;
            int maxLine = countMaxLine(row, col, player);
            board[row][col] = EMPTY;

            int mult = (player == WHITE) ? 10 : 8;
            score += maxLine * mult;
        }
        return score;
    }

    private String getState() throws Exception {
        if (!gameStarted) return error("游戏未开始，请先调用 startGame");
        return ok(buildState(), formatDisplay());
    }

    private String restart() throws Exception {
        if (random == null) random = new Random();
        initGame();
        return ok(buildState(), formatDisplay());
    }

    private JSONObject buildState() throws Exception {
        JSONObject state = new JSONObject();
        state.put("gameStarted", gameStarted);
        state.put("gameOver", gameOver);
        state.put("moveCount", moveCount);

        String[] winnerNames = {"none", "player", "ai", "draw"};
        state.put("winner", winnerNames[winner]);

        JSONArray boardArr = new JSONArray();
        for (int r = 0; r < SIZE; r++) {
            JSONArray row = new JSONArray();
            for (int c = 0; c < SIZE; c++) {
                String[] marks = {".", "●", "○"};
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
        sb.append("   ");
        for (int c = 0; c < SIZE; c++) {
            sb.append(String.format("%2d", c));
        }
        sb.append("\n");

        sb.append("  ┌");
        for (int c = 0; c < SIZE; c++) sb.append("──");
        sb.append("─┐\n");

        for (int r = 0; r < SIZE; r++) {
            sb.append(String.format("%2d", r)).append("│");
            for (int c = 0; c < SIZE; c++) {
                switch (board[r][c]) {
                    case BLACK: sb.append(" ●"); break;
                    case WHITE: sb.append(" ○"); break;
                    default:    sb.append(" ·"); break;
                }
            }
            sb.append(" │\n");
        }

        sb.append("  └");
        for (int c = 0; c < SIZE; c++) sb.append("──");
        sb.append("─┘");
        return sb.toString();
    }

    private String formatDisplay() throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("⚫⚪ 五子棋\n");
        sb.append("━━━━━━━━━━━━━━━━━━━\n");
        sb.append("你: ●(黑子/先手) | 程序: ○(白子)\n");
        sb.append("落子数: ").append(moveCount).append("\n\n");
        sb.append(renderBoard()).append("\n");

        if (gameOver) {
            switch (winner) {
                case 1: sb.append("\n🎉 恭喜你赢了！"); break;
                case 2: sb.append("\n🤖 程序获胜！"); break;
                case 3: sb.append("\n🤝 平局！"); break;
            }
            sb.append("\n输入 restart 重新开始");
        } else {
            sb.append("\n轮到你了，请指定 row(0-14) 和 col(0-14)");
        }
        return sb.toString();
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
