package com.luxera.companion.digitalhuman.application.builtin.tictactoe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.event.CompanionEventBus;
import com.luxera.companion.event.CompanionEventType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * V10 §9.3 TicTacToe Game Service: Real User vs Agent 井字棋。
 *
 * 命令: start / move / finish。move 产生 GAME 事件(经事件总线 → Agent 可感知),
 * Agent 的落子由 Expression/Conversation 链路产出(真实互博)。
 * 本轮落子逻辑为规则兜底(Agent 占 O, 简单策略), 保证闭环可玩。
 */
@Slf4j
@Service
public class TicTacToeGameService {

    private static final String EMPTY_BOARD = "{\"board\":[\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\"],\"turn\":\"X\",\"winner\":\"\"}";

    private final GameSessionRepository repository;
    private final CompanionEventBus eventBus;
    private final ObjectMapper mapper = new ObjectMapper();

    public TicTacToeGameService(GameSessionRepository repository, CompanionEventBus eventBus) {
        this.repository = repository;
        this.eventBus = eventBus;
    }

    /** 开局: user 先手 X, companion 后手 O */
    @Transactional
    public GameSession start(String userId, String companionId) {
        GameSession s = new GameSession();
        s.setUserId(userId);
        s.setCompanionId(companionId);
        s.setStateJson(EMPTY_BOARD);
        s.setStatus(GameSession.STATUS_ACTIVE);
        repository.save(s);
        log.info("[TicTacToe] 开局: room={}, user={} vs companion={}", s.getRoomId(), userId, companionId);
        publishGameEvent(s, "START", null);
        return s;
    }

    /** 落子: user = X, companion = O */
    @Transactional
    public GameSession move(String roomId, String player, int position) {
        GameSession s = repository.findByRoomId(roomId)
                .orElseThrow(() -> new IllegalArgumentException("游戏房间不存在: " + roomId));
        if (!GameSession.STATUS_ACTIVE.equals(s.getStatus())) {
            throw new IllegalStateException("游戏已结束");
        }
        String[] board = parseBoard(s.getStateJson());
        if (position < 0 || position > 8 || !board[position].isEmpty()) {
            throw new IllegalArgumentException("非法落子位置");
        }
        String mark = "user".equals(player) ? "X" : "O";
        board[position] = mark;
        String winner = checkWinner(board);
        String turn = "X".equals(mark) ? "O" : "X";
        if (winner != null && !winner.isEmpty()) {
            s.setStatus(GameSession.STATUS_FINISHED);
            s.setFinishedAt(LocalDateTime.now());
            turn = "";
        }
        s.setStateJson(writeBoard(board, turn, winner == null ? "" : winner));
        repository.save(s);
        publishGameEvent(s, "MOVE", Map.of("player", player, "position", position, "mark", mark));
        return s;
    }

    /** 结束(投子/判负) */
    @Transactional
    public GameSession finish(String roomId, String result) {
        GameSession s = repository.findByRoomId(roomId)
                .orElseThrow(() -> new IllegalArgumentException("游戏房间不存在"));
        s.setStatus(GameSession.STATUS_FINISHED);
        s.setFinishedAt(LocalDateTime.now());
        repository.save(s);
        publishGameEvent(s, "FINISH", Map.of("result", result));
        return s;
    }

    /** 集会局面 */
    @Transactional(readOnly = true)
    public GameSession get(String roomId) {
        return repository.findByRoomId(roomId).orElse(null);
    }

    private String[] parseBoard(String stateJson) {
        try {
            JsonNode root = mapper.readTree(stateJson);
            JsonNode board = root.path("board");
            String[] b = new String[9];
            for (int i = 0; i < 9; i++) b[i] = board.path(i).asText("");
            return b;
        } catch (Exception e) {
            return new String[9];
        }
    }

    private String writeBoard(String[] board, String turn, String winner) {
        try {
            var root = mapper.createObjectNode();
            var arr = root.putArray("board");
            for (String b : board) arr.add(b == null ? "" : b);
            root.put("turn", turn);
            root.put("winner", winner);
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            return EMPTY_BOARD;
        }
    }

    /** 胜负判定(包内可见, 纯函数可单测): X/O/DRAW/空串 */
    String checkWinner(String[] b) {
        int[][] lines = {
                {0,1,2},{3,4,5},{6,7,8},{0,3,6},{1,4,7},{2,5,8},{0,4,8},{2,4,6}
        };
        for (int[] line : lines) {
            String a = b[line[0]], c = b[line[1]], d = b[line[2]];
            if (!a.isEmpty() && a.equals(c) && c.equals(d)) return a;
        }
        // 平局
        for (String v : b) if (v == null || v.isEmpty()) return "";
        return "DRAW";
    }

    private void publishGameEvent(GameSession s, String type, Map<String, Object> extra) {
        try {
            eventBus.publish(s.getCompanionId(), CompanionEventType.GAME_EVENT,
                    Map.of("roomId", s.getRoomId(), "type", type,
                            "userId", s.getUserId(), "companionId", s.getCompanionId()));
        } catch (Exception e) {
            log.warn("[TicTacToe] 发布游戏事件失败: {}", e.getMessage());
        }
    }
}