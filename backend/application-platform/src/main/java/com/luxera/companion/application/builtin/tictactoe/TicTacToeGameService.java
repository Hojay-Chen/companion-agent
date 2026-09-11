package com.luxera.companion.application.builtin.tictactoe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.contracts.application.ApplicationEvent;
import com.luxera.companion.contracts.events.ChatEventTypes;
import com.luxera.companion.contracts.spi.ApplicationEventSink;
import com.luxera.companion.contracts.spi.ChatWorldPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * V10 §9.3 TicTacToe Game Service: Real User vs Agent 井字棋。
 *
 * 命令: start / move / finish。每次落子产生两路事件:
 * 1. {@link ChatWorldPort#publishEvent} GAME_EVENT —— 前端 SSE 实时刷新局面;
 * 2. {@link ApplicationEventSink} —— 应用平台那一路, 由数字人侧的 sink 实现翻译成认知链事件。
 *
 * 落子由 Agent 的认知链路产出(LLM 基于局面评估), 无启发式兜底 —— 用户要求直接用 agent。
 *
 * <p><b>R3 起本类只认识两件平台契约</b>({@code ApplicationEvent} 与 {@code ApplicationEventSink}),
 * 不再 import 数字人的 {@code EventProcessingChain} / {@code ExternalEvent} / {@code ExternalEventType}
 * —— 那是"应用平台碰数字人内部事件词汇"的越界, 也是搬迁前最后一个越界点。三条约束照旧:
 * <ul>
 *   <li>(a) {@code agentTrigger} 由应用算出 —— 数字人不再硬编码 "MOVE" 与 {@code turn=="O"};</li>
 *   <li>(b) 事件带资源 URI({@code ApplicationEvent.target}) —— 数字人用统一读模型读状态,
 *       不解析应用自己的 JSON;</li>
 *   <li>(c) 投递发生在**事务提交之后** —— 否则数字人可能读到随后回滚的状态,
 *       据此自信地回应一步从未发生的棋。</li>
 * </ul>
 */
@Slf4j
@Service
public class TicTacToeGameService {

    private static final String EMPTY_BOARD = "{\"board\":[\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\"],\"turn\":\"X\",\"winner\":\"\"}";

    private final GameSessionRepository repository;
    private final ChatWorldPort chatWorld;
    private final ApplicationEventSink eventSink;
    private final ObjectMapper mapper = new ObjectMapper();

    public TicTacToeGameService(GameSessionRepository repository, ChatWorldPort chatWorld,
                                ApplicationEventSink eventSink) {
        this.repository = repository;
        this.chatWorld = chatWorld;
        this.eventSink = eventSink;
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
        publishGameEvent(s, "MOVE", Map.of(
                "player", player, "position", position, "mark", mark,
                "turn", turn, "winner", winner == null ? "" : winner));
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
        publishGameEvent(s, "FINISH", Map.of("result", result, "turn", "", "winner", result));
        return s;
    }

    /** 读取局面 */
    @Transactional(readOnly = true)
    public GameSession get(String roomId) {
        return repository.findByRoomId(roomId).orElse(null);
    }

    /** 当前轮到谁(X=user, O=companion, 空=已结束) */
    @Transactional(readOnly = true)
    public String currentTurn(String roomId) {
        GameSession s = get(roomId);
        if (s == null || !GameSession.STATUS_ACTIVE.equals(s.getStatus())) return "";
        return turnOf(s.getStateJson());
    }

    /** 读取 board(供 Agent 局面评估) */
    public String[] boardOf(GameSession s) {
        return parseBoard(s == null ? null : s.getStateJson());
    }

    private String turnOf(String stateJson) {
        try {
            JsonNode root = mapper.readTree(stateJson);
            return root.path("turn").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private String[] parseBoard(String stateJson) {
        try {
            if (stateJson == null) return new String[9];
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
    static String checkWinner(String[] b) {
        int[][] lines = {
                {0,1,2},{3,4,5},{6,7,8},{0,3,6},{1,4,7},{2,5,8},{0,4,8},{2,4,6}
        };
        for (int[] line : lines) {
            String a = b[line[0]], c = b[line[1]], d = b[line[2]];
            if (!a.isEmpty() && a.equals(c) && c.equals(d)) return a;
        }
        for (String v : b) if (v == null || v.isEmpty()) return "";
        return "DRAW";
    }

    /** 两路事件: 前端 SSE + 应用平台事件(数字人侧自行决定要不要听) */
    private void publishGameEvent(GameSession s, String type, Map<String, Object> extra) {
        // 1. 前端 SSE(GAME_EVENT)—— 契约不变, 前端照旧
        String[] board = parseBoard(s.getStateJson());
        try {
            Map<String, Object> evt = new LinkedHashMap<>();
            evt.put("roomId", s.getRoomId());
            evt.put("type", type);
            evt.put("userId", s.getUserId());
            evt.put("companionId", s.getCompanionId());
            evt.put("status", s.getStatus());
            evt.put("board", board);
            if (extra != null) evt.putAll(extra);
            chatWorld.publishEvent(s.getCompanionId(), ChatEventTypes.GAME_EVENT, evt);
        } catch (Exception e) {
            log.warn("[TicTacToe] 发布前端事件失败: {}", e.getMessage());
        }

        // 2. 应用平台事件 —— 数字人"看到"游戏里的变化
        boolean agentTrigger = "MOVE".equalsIgnoreCase(type)
                && extra != null && "O".equalsIgnoreCase(String.valueOf(extra.get("turn")));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("companionId", s.getCompanionId());   // sink 靠它决定"这是谁的事件"
        data.put("userId", s.getUserId());
        data.put("roomId", s.getRoomId());
        data.put("status", s.getStatus());
        data.put("agentTrigger", agentTrigger);
        data.put("board", board);
        if (extra != null) data.putAll(extra);

        // 确定性 eventId: 同 room 同事件同落点 → 同一个 id → 数字人侧去重不会对同一步行动两次。
        // R4 起这段由 manifest 的 events[].idTemplate 声明并强制。
        String eventId = TicTacToeApplicationAdapter.uriOf(s.getRoomId()) + "#" + type
                + (extra != null && extra.containsKey("position") ? "-" + extra.get("position") : "");
        ApplicationEvent event = new ApplicationEvent(eventId, "game." + type.toLowerCase(),
                TicTacToeApplicationAdapter.APP_CODE,
                TicTacToeApplicationAdapter.uriOf(s.getRoomId()), Instant.now(),
                mapper.valueToTree(data));

        afterCommit(() -> {
            try {
                eventSink.emit(event);
            } catch (Exception e) {
                log.warn("[TicTacToe] 投递应用事件失败: {}", e.getMessage());
            }
        });
    }

    /** 有事务则挂 afterCommit, 无事务(单测/非事务调用)立即执行。 */
    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
