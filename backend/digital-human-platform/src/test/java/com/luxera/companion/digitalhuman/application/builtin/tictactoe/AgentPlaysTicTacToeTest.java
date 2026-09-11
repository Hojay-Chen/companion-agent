package com.luxera.companion.digitalhuman.application.builtin.tictactoe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.llm.LlmRouter;
import com.luxera.companion.llm.StructuredRequest;
import com.luxera.companion.llm.StructuredResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * LAP v1 R2 的端到端证据: 真人落子之后, 数字人<b>经由通用链路</b>应手。
 *
 * <p>这条链上没有一处认识井字棋:
 * {@code TicTacToeGameService} 投递带 {@code resourceUri}/{@code agentTrigger} 的事件 →
 * {@code DhApplicationEventSink} 语义的链 → {@code EventRouter} → {@code AgentApplicationFlow}
 * → {@code ApplicationRuntimePort}({@code LocalApplicationRuntimeAdapter}) →
 * {@code TicTacToeApplicationAdapter} 的 {@code pendingActions} 说"轮到 O 了" →
 * LLM 选点 → {@code game.make_move}。
 *
 * <p>把 LLM 换成固定回一个空位的 stub(而不是 mock provider), 是为了让链路真的跑到底 ——
 * mock provider 下流程会按设计不行动, 那样这条测试就什么也证明不了。
 */
@ActiveProfiles("test")
@SpringBootTest
class AgentPlaysTicTacToeTest {

    @Autowired
    TicTacToeGameService gameService;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    com.luxera.companion.digitalhuman.reality.RealityEventRepository realityEventRepository;

    /** 真实 LLM 太慢也不确定; 这里只关心"链路把 LLM 的选择变成了落子"。 */
    @MockBean
    LlmRouter llmRouter;

    @Test
    void theDigitalHumanAnswersTheHumansMoveWithoutKnowingTheGame() throws Exception {
        when(llmRouter.available()).thenReturn(true);
        when(llmRouter.isMockActive()).thenReturn(false);
        when(llmRouter.structured(any(StructuredRequest.class))).thenReturn(new StructuredResult("""
                {"input":{"position":4,"player":"companion"},"reason":"占据中心"}
                """, objectMapper));

        // user_id / companion_id 是 varchar(36), 别把前缀拼进去
        String userId = UUID.randomUUID().toString();
        String companionId = UUID.randomUUID().toString();
        GameSession session = gameService.start(userId, companionId);

        // 真人执 X 落在左上角 → 轮到 O → agentTrigger 为真
        gameService.move(session.getRoomId(), "user", 0);

        String[] board = awaitBoardWithMark(session.getRoomId(), "O", 8_000);
        assertNotNull(board, "数字人应在真人落子后应手: " + gameService.get(session.getRoomId()).getStateJson());
        assertEquals("O", board[4], "LLM 选了中心(4), 链路应把它变成棋盘上的一枚 O");
        assertEquals("X", board[0], "真人的那一手必须还在");

        // 账本语义只在 DH 侧: 落子这件事必须留下一条真实经历。
        // 这条断言曾经抓到过一个真 bug —— correlation_id 超过 varchar(64) 会让整条写入
        // 静默失败, 棋盘照样更新, 只有数字人的"记忆"悄悄丢了。
        assertFalse(realityEventRepository
                        .findByPersonIdAndType(companionId, "APPLICATION_ACTION_EXECUTED").isEmpty(),
                "数字人落子后应写入现实账本");
    }

    /** 轮询等待异步链路(事件链 → PersonActor mailbox → 落子)完成。 */
    private String[] awaitBoardWithMark(String roomId, String mark, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        String[] last = null;
        while (System.currentTimeMillis() < deadline) {
            GameSession s = gameService.get(roomId);
            last = boardOf(s);
            for (String cell : last) {
                if (mark.equals(cell)) return last;
            }
            Thread.sleep(100);
        }
        return null;
    }

    private String[] boardOf(GameSession s) throws Exception {
        String[] board = new String[9];
        if (s == null || s.getStateJson() == null) return board;
        JsonNode root = objectMapper.readTree(s.getStateJson());
        JsonNode cells = root.path("board");
        for (int i = 0; i < 9; i++) board[i] = cells.path(i).asText("");
        return board;
    }
}
