package ai.rorsch.moduleplugins.sudoku_game;

import android.content.Context;
import ai.rorsch.pandagenie.module.runtime.HtmlOutputHelper;
import ai.rorsch.pandagenie.module.runtime.ModulePlugin;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class SudokuPlugin implements ModulePlugin {

    private static final int SIZE = 9;
    private static final int BOX = 3;

    private int[][] solution;
    private int[][] puzzle;
    private int[][] playerBoard;
    private boolean[][] fixed;
    private boolean gameStarted;
    private boolean gameOver;
    private int difficulty;
    private Random random;

    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        try {
            JSONObject params = new JSONObject(emptyJson(paramsJson));
            switch (action) {
                case "startGame":
                    return startGame(params.optInt("difficulty", 2));
                case "placeNumber":
                    return placeNumber(params.optInt("row", -1), params.optInt("col", -1), params.optInt("number", 0));
                case "clearCell":
                    return clearCell(params.optInt("row", -1), params.optInt("col", -1));
                case "checkBoard":
                    return checkBoard();
                case "getHint":
                    return getHint();
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
        generatePuzzle();
        return ok(buildState(), formatDisplay(), formatDisplayHtml());
    }

    private void generatePuzzle() {
        solution = new int[SIZE][SIZE];
        fillBoard(solution, 0, 0);

        puzzle = new int[SIZE][SIZE];
        playerBoard = new int[SIZE][SIZE];
        fixed = new boolean[SIZE][SIZE];

        for (int r = 0; r < SIZE; r++) {
            for (int c = 0; c < SIZE; c++) {
                puzzle[r][c] = solution[r][c];
                playerBoard[r][c] = solution[r][c];
            }
        }

        int cellsToRemove;
        switch (difficulty) {
            case 1: cellsToRemove = 35; break;
            case 3: cellsToRemove = 55; break;
            default: cellsToRemove = 45; break;
        }

        List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < SIZE * SIZE; i++) positions.add(i);
        Collections.shuffle(positions, random);

        int removed = 0;
        for (int pos : positions) {
            if (removed >= cellsToRemove) break;
            int r = pos / SIZE;
            int c = pos % SIZE;
            puzzle[r][c] = 0;
            playerBoard[r][c] = 0;
            removed++;
        }

        for (int r = 0; r < SIZE; r++)
            for (int c = 0; c < SIZE; c++)
                fixed[r][c] = puzzle[r][c] != 0;

        gameStarted = true;
        gameOver = false;
    }

    private boolean fillBoard(int[][] board, int row, int col) {
        if (row == SIZE) return true;
        int nextRow = col == SIZE - 1 ? row + 1 : row;
        int nextCol = col == SIZE - 1 ? 0 : col + 1;

        List<Integer> nums = new ArrayList<>();
        for (int i = 1; i <= SIZE; i++) nums.add(i);
        Collections.shuffle(nums, random);

        for (int num : nums) {
            if (isValid(board, row, col, num)) {
                board[row][col] = num;
                if (fillBoard(board, nextRow, nextCol)) return true;
                board[row][col] = 0;
            }
        }
        return false;
    }

    private boolean isValid(int[][] board, int row, int col, int num) {
        for (int c = 0; c < SIZE; c++) if (board[row][c] == num) return false;
        for (int r = 0; r < SIZE; r++) if (board[r][col] == num) return false;
        int boxR = (row / BOX) * BOX, boxC = (col / BOX) * BOX;
        for (int r = boxR; r < boxR + BOX; r++)
            for (int c = boxC; c < boxC + BOX; c++)
                if (board[r][c] == num) return false;
        return true;
    }

    private String placeNumber(int row, int col, int number) throws Exception {
        if (!gameStarted) return error("游戏未开始，请先调用 startGame");
        if (gameOver) return ok(buildState(), formatDisplay(), formatDisplayHtml());
        if (row < 0 || row >= SIZE || col < 0 || col >= SIZE) return error("无效位置，行列范围为 0-8");
        if (number < 1 || number > 9) return error("无效数字，范围为 1-9");
        if (fixed[row][col]) return error("该位置是预填数字，不可修改");

        playerBoard[row][col] = number;
        boolean correct = (number == solution[row][col]);

        JSONObject state = buildState();
        state.put("placedCorrect", correct);
        if (!correct) {
            state.put("message", "数字不正确，但已放置");
        }

        if (isBoardComplete()) {
            if (isBoardCorrect()) {
                gameOver = true;
                state.put("completed", true);
                state.put("message", "恭喜！数独已完成！");
            }
        }

        return ok(state, formatDisplay(), formatDisplayHtml());
    }

    private String clearCell(int row, int col) throws Exception {
        if (!gameStarted) return error("游戏未开始，请先调用 startGame");
        if (row < 0 || row >= SIZE || col < 0 || col >= SIZE) return error("无效位置");
        if (fixed[row][col]) return error("该位置是预填数字，不可清除");
        playerBoard[row][col] = 0;
        return ok(buildState(), formatDisplay(), formatDisplayHtml());
    }

    private String checkBoard() throws Exception {
        if (!gameStarted) return error("游戏未开始");
        JSONObject state = buildState();
        boolean complete = isBoardComplete();
        boolean correct = isBoardCorrect();
        state.put("complete", complete);
        state.put("correct", correct);

        int errors = 0;
        for (int r = 0; r < SIZE; r++)
            for (int c = 0; c < SIZE; c++)
                if (playerBoard[r][c] != 0 && playerBoard[r][c] != solution[r][c])
                    errors++;
        state.put("errors", errors);

        int empty = 0;
        for (int r = 0; r < SIZE; r++)
            for (int c = 0; c < SIZE; c++)
                if (playerBoard[r][c] == 0) empty++;
        state.put("emptyCells", empty);

        if (complete && correct) {
            gameOver = true;
            state.put("message", "恭喜！数独已完成！");
        }

        return ok(state, formatDisplay(), formatDisplayHtml());
    }

    private String getHint() throws Exception {
        if (!gameStarted) return error("游戏未开始");
        if (gameOver) return error("游戏已结束");

        List<int[]> emptyCells = new ArrayList<>();
        for (int r = 0; r < SIZE; r++)
            for (int c = 0; c < SIZE; c++)
                if (playerBoard[r][c] == 0 || playerBoard[r][c] != solution[r][c])
                    emptyCells.add(new int[]{r, c});

        if (emptyCells.isEmpty()) return error("没有需要提示的格子");

        int[] cell = emptyCells.get(random.nextInt(emptyCells.size()));
        playerBoard[cell[0]][cell[1]] = solution[cell[0]][cell[1]];

        JSONObject state = buildState();
        state.put("hintRow", cell[0]);
        state.put("hintCol", cell[1]);
        state.put("hintValue", solution[cell[0]][cell[1]]);

        if (isBoardComplete() && isBoardCorrect()) {
            gameOver = true;
            state.put("completed", true);
        }

        return ok(state, formatDisplay(), formatDisplayHtml());
    }

    private boolean isBoardComplete() {
        for (int r = 0; r < SIZE; r++)
            for (int c = 0; c < SIZE; c++)
                if (playerBoard[r][c] == 0) return false;
        return true;
    }

    private boolean isBoardCorrect() {
        for (int r = 0; r < SIZE; r++)
            for (int c = 0; c < SIZE; c++)
                if (playerBoard[r][c] != solution[r][c]) return false;
        return true;
    }

    private String getState() throws Exception {
        if (!gameStarted) return error("游戏未开始，请先调用 startGame");
        return ok(buildState(), formatDisplay(), formatDisplayHtml());
    }

    private String restart() throws Exception {
        if (difficulty == 0) difficulty = 2;
        if (random == null) random = new Random();
        generatePuzzle();
        return ok(buildState(), formatDisplay(), formatDisplayHtml());
    }

    private JSONObject buildState() throws Exception {
        JSONObject state = new JSONObject();
        state.put("gameStarted", gameStarted);
        state.put("gameOver", gameOver);
        state.put("difficulty", difficulty);

        JSONArray boardArr = new JSONArray();
        for (int r = 0; r < SIZE; r++) {
            JSONArray row = new JSONArray();
            for (int c = 0; c < SIZE; c++) row.put(playerBoard[r][c]);
            boardArr.put(row);
        }
        state.put("playerBoard", boardArr);

        JSONArray fixedArr = new JSONArray();
        for (int r = 0; r < SIZE; r++) {
            JSONArray row = new JSONArray();
            for (int c = 0; c < SIZE; c++) row.put(fixed[r][c]);
            fixedArr.put(row);
        }
        state.put("fixedCells", fixedArr);

        int filled = 0, total = 0;
        for (int r = 0; r < SIZE; r++)
            for (int c = 0; c < SIZE; c++) {
                total++;
                if (playerBoard[r][c] != 0) filled++;
            }
        state.put("filledCount", filled);
        state.put("totalCells", total);
        state.put("boardDisplay", renderBoard());
        return state;
    }

    private String renderBoard() {
        StringBuilder sb = new StringBuilder();
        sb.append("    0 1 2   3 4 5   6 7 8\n");
        sb.append("  ┌───────┬───────┬───────┐\n");

        for (int r = 0; r < SIZE; r++) {
            sb.append(r).append(" │");
            for (int c = 0; c < SIZE; c++) {
                if (playerBoard[r][c] == 0) {
                    sb.append(" ·");
                } else if (fixed[r][c]) {
                    sb.append(" ").append(playerBoard[r][c]);
                } else {
                    sb.append(" ").append(playerBoard[r][c]);
                }
                if (c % BOX == BOX - 1 && c < SIZE - 1) sb.append(" │");
            }
            sb.append(" │\n");
            if (r % BOX == BOX - 1 && r < SIZE - 1) {
                sb.append("  ├───────┼───────┼───────┤\n");
            }
        }
        sb.append("  └───────┴───────┴───────┘");
        return sb.toString();
    }

    private String formatDisplay() throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("🔢 数独\n");
        sb.append("━━━━━━━━━━━━━━━━━━━\n");
        String[] diffNames = {"简单", "普通", "困难"};
        sb.append("难度: ").append(diffNames[difficulty - 1]).append("\n\n");
        sb.append(renderBoard()).append("\n");

        int filled = 0, empty = 0;
        for (int r = 0; r < SIZE; r++)
            for (int c = 0; c < SIZE; c++)
                if (playerBoard[r][c] == 0) empty++; else filled++;

        sb.append("\n已填: ").append(filled).append("/81 | 剩余: ").append(empty);

        if (gameOver) {
            sb.append("\n\n🎉 恭喜！数独已完成！");
            sb.append("\n输入 restart 重新开始");
        } else {
            sb.append("\n操作: placeNumber(row,col,number) | clearCell(row,col) | getHint");
        }
        return sb.toString();
    }

    private String formatDisplayHtml() {
        String[] diffNames = {"简单", "普通", "困难"};
        int filled = 0, empty = 0;
        for (int r = 0; r < SIZE; r++) {
            for (int c = 0; c < SIZE; c++) {
                if (playerBoard[r][c] == 0) empty++;
                else filled++;
            }
        }
        String status = gameOver ? "已完成" : "进行中";
        String body = HtmlOutputHelper.keyValue(new String[][]{
                {"难度", diffNames[difficulty - 1]},
                {"进度", filled + "/81"},
                {"空格", String.valueOf(empty)},
                {"状态", status}
        });
        if (gameOver) {
            body += HtmlOutputHelper.successBadge() + HtmlOutputHelper.p("恭喜完成！输入 restart 重新开始");
        } else {
            body += HtmlOutputHelper.muted("placeNumber · clearCell · getHint · checkBoard");
        }
        return HtmlOutputHelper.card("🔢", "数独", body);
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
