package com.luxera.companion.digitalhuman.application.builtin.tictactoe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V10 §9.3 Game POC 回收测试: 局面 state 为 JSON, 胜负/平局/非法落子检测。
 */
class TicTacToeGameServiceTest {

    private String[] board(String... cells) {
        String[] b = new String[9];
        for (int i = 0; i < 9 && i < cells.length; i++) b[i] = cells[i] == null ? "" : cells[i];
        return b;
    }

    @Test
    void winDetection_threeInARow() {
        String[] b = board("X","X","X", "", "O","", "", "", "O");
        assertEquals("X", TicTacToeGameService.checkWinner(b));
    }

    @Test
    void winDetection_diagonal() {
        String[] b = board("O","","", "", "O","", "", "", "O");
        assertEquals("O", TicTacToeGameService.checkWinner(b));
    }

    @Test
    void noWinYet_returnsEmpty() {
        String[] b = board("X","O","X", "O","X","O", "O","X","");
        assertEquals("", TicTacToeGameService.checkWinner(b));
    }

    @Test
    void draw_detected() {
        String[] b = board("X","O","X", "O","X","X", "O","X","O");
        assertEquals("DRAW", TicTacToeGameService.checkWinner(b)); // 平局返回 "DRAW"
    }
}