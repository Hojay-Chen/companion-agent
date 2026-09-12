package com.luxera.companion.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.application.principal.ResolvedPrincipal;
import com.luxera.companion.application.domain.SessionParticipantRecord;
import com.luxera.companion.application.session.ParticipantService;
import com.luxera.companion.auth.User;
import com.luxera.companion.auth.UserRepository;
import com.luxera.companion.config.JwtUtil;
import com.luxera.companion.contracts.application.PrincipalType;
import com.luxera.companion.digitalhuman.reality.RealityEventRepository;
import com.luxera.companion.llm.LlmRouter;
import com.luxera.companion.llm.StructuredRequest;
import com.luxera.companion.llm.StructuredResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * LAP v1 的端到端证据: 真人经<b>真实 HTTP</b>落子之后, 数字人经由通用链路应手。
 *
 * <p>这条链上没有一处认识井字棋 —— 也正因为如此, 它必须跑在 {@code bootstrap-app} 里:
 * 只有这里同时看得见应用平台(棋局与它的 {@code game.make_move})与数字人平台
 * ({@code AgentApplicationFlow} 与认知链)。任何一侧单独在场, 都凑不出这条往返。
 *
 * <p>链路:
 * {@code POST /api/v1/actions:execute} → {@code ActionGateway}(权限 / 幂等 / CAS) →
 * {@code TicTacToeApplication} 提交 → afterCommit → {@code LapEventPublisher}(三道闸) →
 * {@code AgentRouteResolver} 盖 {@code companionId} → {@code DhApplicationEventSink} 翻译成
 * {@code ExternalEvent} → {@code EventProcessingChain} → {@code EventRouter} →
 * {@code AgentApplicationFlow} → {@code ApplicationRuntimePort} →
 * {@code TicTacToeApplication.pendingActions} 说"轮到 O 了" → LLM 选点 →
 * {@code game.make_move} 回到同一个应用 → 同一行 {@code resource} 变了。
 *
 * <p><b>数字人不经 JWT 加入会话, 这里也不假装它走。</b> {@code JwtAuthenticationFilter} 要求
 * 令牌主体在 {@code user} 表里有行, 而数字人不是 user —— 这不是测试的将就, 是系统的实际形状:
 * Agent 的入口是进程内({@code ApplicationRuntimePort})与 MCP(R6, 服务密钥), JWT 那条路是真人
 * 的。所以测试里真人那一段走 MockMvc 全栈(过滤器、控制器、网关一个不少), 数字人那一段用
 * 进程内的 {@link ParticipantService} 加入同一局 —— 正是它真实的样子。
 *
 * <p>把 LLM 换成固定回"落子到 4 号位"的 stub(而不是 mock provider), 是为了让链路真的跑到底 ——
 * mock provider 下流程会按设计不行动, 那样这条测试就什么也证明不了。R7 之后动作选择的契约里
 * 多了 {@code actionId}(应用同时给"落子"和"认输"两个候选), 所以 stub 也要照契约回。
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class LapEndToEndTest {

    private static final String APP_ID = "com.luxera.tictactoe";
    private static final String GAME_URI_PREFIX = "game://session/";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JwtUtil jwtUtil;

    @Autowired
    UserRepository userRepository;

    @Autowired
    ParticipantService participantService;

    @Autowired
    RealityEventRepository realityEventRepository;

    /** 真实 LLM 太慢也不确定; 这里只关心"链路把 LLM 的选择变成了落子"。 */
    @MockBean
    LlmRouter llmRouter;

    @Test
    void theDigitalHumanAnswersTheHumansMoveWithoutKnowingTheGame() throws Exception {
        when(llmRouter.available()).thenReturn(true);
        when(llmRouter.isMockActive()).thenReturn(false);
        // R7 起, 轮到数字人时应用给的是<b>两个</b>候选(落子 / 认输), 所以模型的答复必须点名它要哪个 ——
        // 只回一个 input 而不说动作, 按设计就是不行动(补一个就是启发式)。这里照契约回:
        when(llmRouter.structured(any(StructuredRequest.class))).thenReturn(new StructuredResult("""
                {"actionId":"game.make_move","input":{"position":4},"reason":"占据中心"}
                """, objectMapper));

        // user_id / companion_id 是 varchar(36), 别把前缀拼进去
        String userId = UUID.randomUUID().toString();
        String companionId = UUID.randomUUID().toString();
        String humanToken = humanToken(userId);

        // ── 1. 真人打开应用: 真 HTTP, 过滤器 + 控制器 + 网关全在链上 ──
        MvcResult opened = mockMvc.perform(post("/api/v1/applications/" + APP_ID + "/sessions")
                        .header("Authorization", "Bearer " + humanToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andReturn();
        String sessionId = json(opened).path("sessionId").asText();
        assertFalse(sessionId.isBlank(), "打开应用就该开出一个会话");

        // ── 2. 数字人加入这一局: 进程内, 见类注释 ──
        //   v1 这里是"给它装一次"+ 顺手开它自己的会话; v2 里对手要坐在<em>同一局</em>里 ——
        //   这正是"多个 principal 共用一个 Resource"成立的前提。
        ResolvedPrincipal agent = new ResolvedPrincipal(PrincipalType.AGENT, companionId, companionId,
                userId, null, UUID.randomUUID().toString(), ResolvedPrincipal.SOURCE_INTERNAL);
        participantService.join(sessionId, agent, SessionParticipantRecord.ROLE_MEMBER, true);

        String gameUri = GAME_URI_PREFIX + sessionId;

        // ── 3. 真人开局, 并把对手指定成这位数字人 ──
        assertEquals(200, execute(humanToken, "create-" + sessionId,
                "{\"action\":\"game.create\",\"target\":\"" + gameUri + "\","
                        + "\"input\":{\"opponentPrincipalId\":\"" + companionId + "\"}}").getResponse().getStatus());

        // ── 4. 真人执 X 落在左上角 → 轮到 O ──
        String moveKey = "move-" + sessionId + "-0";
        String moveBody = "{\"action\":\"game.make_move\",\"target\":\"" + gameUri + "\","
                + "\"input\":{\"position\":0}}";
        MvcResult first = execute(humanToken, moveKey, moveBody);
        assertEquals(200, first.getResponse().getStatus(), "真人的一步应当成功");

        // ── 5. 数字人应手: 轮询异步链路(事件链 → PersonActor mailbox → 落子) ──
        String[] board = awaitBoardWithMark(humanToken, gameUri, "O", 15_000);
        assertNotNull(board, "数字人应在真人落子后应手: " + boardToString(board));
        assertEquals("O", board[4], "LLM 选了中心(4), 链路应把它变成棋盘上的一枚 O");
        assertEquals("X", board[0], "真人的那一手必须还在");

        // 账本语义只在数字人侧: 落子这件事必须留下一条真实经历。
        // 这条断言曾经抓到过一个真 bug —— correlation_id 超过 varchar(64) 会让整条写入
        // 静默失败, 棋盘照样更新, 只有数字人的"记忆"悄悄丢了。
        assertFalse(realityEventRepository
                        .findByPersonIdAndType(companionId, "APPLICATION_ACTION_EXECUTED").isEmpty(),
                "数字人落子后应写入现实账本");

        // ── 6. 同一个幂等键再发一次: 重放, 不产生第二手棋 ──
        MvcResult replay = execute(humanToken, moveKey, moveBody);
        assertEquals(200, replay.getResponse().getStatus());
        assertEquals("true", replay.getResponse().getHeader("Idempotent-Replay"),
                "同 key 的第二次必须走重放, 而不是再落一子");
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString(),
                "重放的响应体必须与首次逐字节相同");
    }

    // ─────────────────────────── 夹具 ───────────────────────────

    /**
     * 真人令牌。{@code JwtAuthenticationFilter} 会查 {@code user} 表, 所以先造一行 ——
     * 这是真人真实的样子(注册过才有令牌), 不是为测试开的后门。
     */
    private String humanToken(String userId) {
        User user = new User();
        user.setId(userId);
        user.setUsername("lap-" + userId.substring(0, 8));
        user.setPasswordHash("");
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
        return jwtUtil.generateToken(userId, user.getUsername(), PrincipalType.HUMAN);
    }

    private MvcResult execute(String token, String idempotencyKey, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/actions:execute")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    /** 轮询等待异步链路完成; 拿到含该记号的一行棋盘就返回。 */
    private String[] awaitBoardWithMark(String token, String gameUri, String mark, long timeoutMillis)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        String[] board = null;
        while (System.currentTimeMillis() < deadline) {
            board = readBoard(token, gameUri);
            for (String cell : board) {
                if (mark.equals(cell)) {
                    return board;
                }
            }
            Thread.sleep(100);
        }
        return null;
    }

    /** 走真人读路径 —— 与 Agent 改的是同一个 Resource, 这是"共享世界"最直接的证据。 */
    private String[] readBoard(String token, String gameUri) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/resources")
                        .param("uri", gameUri)
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        String[] board = new String[9];
        if (result.getResponse().getStatus() != 200) {
            return board;
        }
        JsonNode state = json(result).path(0).path("state");
        for (int i = 0; i < 9; i++) {
            board[i] = state.path("board").path(i).isNull() ? "" : state.path("board").path(i).asText("");
        }
        return board;
    }

    private JsonNode json(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        return body == null || body.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(body);
    }

    private static String boardToString(String[] board) {
        if (board == null) {
            return "(读不到棋盘)";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < board.length; i++) {
            sb.append(board[i] == null || board[i].isEmpty() ? "." : board[i]);
            if (i % 3 == 2) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }
}
