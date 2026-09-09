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
        var service = new TicTacToeGameService(null, null);
        String[] b = board("X","X","X", "", "O","", "", "", "O");
        assertEquals("X", service.checkWinner(b));
    }

    @Test
    void winDetection_diagonal() {
        var service = new TicTacToeGameService(null, null);
        String[] b = board("O","","", "", "O","", "", "", "O");
        assertEquals("O", service.checkWinner(b));
    }

    @Test
    void noWinYet_returnsEmpty() {
        var service = new TicTacToeGameService(null, null);
        String[] b = board("X","O","X", "O","X","O", "O","X","");
        assertEquals("", service.checkWinner(b));
    }

    @Test
    void draw_detected() {
        var service = new TicTacToeGameService(null, null);
        String[] b = board("X","O","X", "O","X","X", "O","X","O");
        assertEquals("", service.checkWinner(b)); // 无三连
    }
}