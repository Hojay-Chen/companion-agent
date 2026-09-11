package com.luxera.companion.application.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.application.domain.ApplicationSessionRecord;
import com.luxera.companion.application.principal.ResolvedPrincipal;
import com.luxera.companion.application.repository.ActionInvocationRepository;
import com.luxera.companion.application.session.ApplicationSessionService;
import com.luxera.companion.application.session.InstallationService;
import com.luxera.companion.contracts.application.ActionRequest;
import com.luxera.companion.contracts.application.ActionResponse;
import com.luxera.companion.contracts.application.ActionSpec;
import com.luxera.companion.contracts.application.ActionStatus;
import com.luxera.companion.contracts.application.ApplicationView;
import com.luxera.companion.contracts.application.CapabilityView;
import com.luxera.companion.contracts.application.InvocationContext;
import com.luxera.companion.contracts.application.PrincipalType;
import com.luxera.companion.contracts.application.ResourceView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网关上走一遍完整的七道 —— 真人走的路径和 Agent 走的路径是<em>同一条</em>, 所以这个类里
 * 没有"Agent 专用"的用例, 只有"换个 principal"的用例。那正是 LAP 要说的事。
 *
 * <p>三条断言值得单独指出:
 *
 * <ul>
 *   <li><b>READ 从不产生 invocation。</b>方案原文给所有动作都要求幂等键, 而现有实现里
 *       {@code game.state} 也会带 key。真按那样落地, 同一个房间第二次读棋局会<em>重放</em>
 *       第一次的旧棋盘。断言"读之后行数不变"就是这条修正的保险丝。</li>
 *   <li><b>"该谁走"由应用回答, 不由平台判断。</b>{@code pendingActions} 在轮到 O 时给 O 返回
 *       动作、给 X 返回空 —— 平台不知道任何关于棋的规则, 它只是把问题转给应用。</li>
 *   <li><b>不是对局者也能读, 但不能落子。</b>权限管的是"能不能调这个动作", 参与资格是应用自己的
 *       规则, 两者在不同的层, 断言里分开看。</li>
 * </ul>
 */
@ActiveProfiles("test")
@SpringBootTest
class ActionGatewayTest {

    private static final String APP_ID = "com.luxera.tictactoe";
    private static final String CREATE = "game.create";
    private static final String STATE = "game.state";
    private static final String MAKE_MOVE = "game.make_move";

    @Autowired
    ActionGateway gateway;

    @Autowired
    InstallationService installationService;

    @Autowired
    ApplicationSessionService sessionService;

    @Autowired
    ActionInvocationRepository invocations;

    @Autowired
    ObjectMapper objectMapper;

    // ─────────────────────────── 发现 ───────────────────────────

    @Test
    void capabilitiesApplicationsAndActionsAreAllDiscoverable() {
        List<CapabilityView> capabilities = gateway.capabilities();
        assertTrue(capabilities.stream().anyMatch(c -> "game.play".equals(c.capabilityId())),
                "能力目录里应当有 game.play: " + capabilities);

        List<ApplicationView> candidates = gateway.applicationsFor("game.play");
        assertTrue(candidates.stream().anyMatch(a -> APP_ID.equals(a.applicationId())),
                "game.play 下应当有井字棋");

        List<String> actionIds = gateway.actionsOf(APP_ID).stream().map(ActionSpec::actionId).toList();
        assertEquals(List.of(CREATE, STATE, MAKE_MOVE, "game.surrender"), actionIds,
                "发现链给出的动作必须与 manifest 逐条一致");

        // agentHint 是应用的作者写给 LLM 的, 必须一路带到发现结果里 —— 否则策略又得回到 DH
        ActionSpec makeMove = gateway.actionsOf(APP_ID).stream()
                .filter(a -> MAKE_MOVE.equals(a.actionId())).findFirst().orElseThrow();
        assertNotNull(makeMove.agentHint());
        assertTrue(makeMove.agentHint().contains("中心"), "落子提示应当来自 manifest: " + makeMove.agentHint());
        assertNotNull(makeMove.inputSchema(), "输入 schema 要给到调用方");
    }

    /** 读到的资源要带上 manifest 里声明的 {@code agentHint} —— 数字人靠它读局面。 */
    @Test
    void readingAResourceCarriesTheManifestsAgentHint() {
        Board board = newBoard();

        ResourceView view = gateway.read(board.uri).orElseThrow();

        assertNotNull(view.agentHint());
        assertTrue(view.agentHint().contains("state.board"), "提示应当说明怎么读局面: " + view.agentHint());
        assertEquals(1L, view.version());
    }

    // ─────────────────────────── 开一局, 读一局 ───────────────────────────

    @Test
    void createThenReadTheSameResource() {
        Human alice = newHuman();
        Board board = newBoard(alice);

        assertEquals(ActionStatus.SUCCESS, board.create.status());
        assertEquals(board.uri, board.create.resource().uri());

        ActionResponse state = gateway.execute(
                ActionRequest.of(STATE, board.uri, null), ctx(alice));
        assertEquals(ActionStatus.SUCCESS, state.status());
        assertEquals(9, boardRow(board.uri).state().path("board").size(),
                "读完应当看到一整张空棋盘: " + boardRow(board.uri).state());
    }

    /** <b>READ 从不写 invocation。</b>否则"读一眼棋局"会被幂等层重放成旧棋盘。 */
    @Test
    void readActionsNeverRecordAnInvocation() {
        Human alice = newHuman();
        Board board = newBoard(alice);
        long before = invocationCount(alice.principalId);

        for (int i = 0; i < 3; i++) {
            assertEquals(ActionStatus.SUCCESS,
                    gateway.execute(ActionRequest.of(STATE, board.uri, null), ctx(alice)).status());
        }

        assertEquals(before, invocationCount(alice.principalId),
                "读动作不该在 action_invocation 里留下任何一行");
    }

    // ─────────────────────────── 幂等 ───────────────────────────

    @Test
    void writeWithoutAnIdempotencyKeyIsRejected() {
        Human alice = newHuman();
        Board board = newBoard(alice);
        long before = invocationCount(alice.principalId);   // 开局那一步本身占一行

        ActionGateway.ActionExecution execution = gateway.execute(
                move(board, 4), alice.principal(), null);

        assertEquals(ActionStatus.IDEMPOTENCY_KEY_REQUIRED, execution.response().status());
        assertEquals("IDEMPOTENCY_KEY_REQUIRED", execution.response().error().code());
        assertEquals(before, invocationCount(alice.principalId), "被拒的请求不该占用幂等键");
    }

    @Test
    void theSameKeyReplaysTheSameBody() {
        Human alice = newHuman();
        Board board = newBoard(alice);
        long before = invocationCount(alice.principalId);
        String key = "move-" + UUID.randomUUID();

        ActionGateway.ActionExecution first = gateway.execute(move(board, 4), alice.principal(), key);
        ActionGateway.ActionExecution second = gateway.execute(move(board, 4), alice.principal(), key);

        assertEquals(ActionStatus.SUCCESS, first.response().status());
        assertFalse(first.replayed());
        assertTrue(second.replayed(), "第二次必须被识别为重放");
        assertEquals(serialize(first.response()), serialize(second.response()));
        assertEquals(before + 1, invocationCount(alice.principalId),
                "两次请求只该落下<em>一行</em>调用记录");
    }

    @Test
    void theSameKeyWithADifferentBodyIsRefused() {
        Human alice = newHuman();
        Board board = newBoard(alice);
        String key = "move-" + UUID.randomUUID();

        gateway.execute(move(board, 4), alice.principal(), key);
        ActionGateway.ActionExecution reused = gateway.execute(move(board, 8), alice.principal(), key);

        assertEquals(ActionStatus.IDEMPOTENCY_KEY_REUSED, reused.response().status());
        assertEquals("IDEMPOTENCY_KEY_REUSED", reused.response().error().code());
        assertNull(boardRow(board.uri).state().path("board").get(8).textValue(),
                "被拒的载荷不该落盘");
    }

    // ─────────────────────────── 权限 ───────────────────────────

    @Test
    void aPrincipalWithoutAnInstallationIsDenied() {
        Human alice = newHuman();
        Board board = newBoard(alice);
        Human stranger = stranger();

        ActionGateway.ActionExecution execution = gateway.execute(
                move(board, 4), stranger.principal(), "key-" + UUID.randomUUID());

        assertEquals(ActionStatus.DENIED, execution.response().status());
        assertEquals("NOT_INSTALLED", execution.response().error().code());
    }

    /** 没装的人向 {@code pendingActions} 提问, 得到空列表而不是异常 —— "我不想做任何事"。 */
    @Test
    void pendingActionsIsEmptyForAPrincipalWithoutAnInstallation() {
        Human alice = newHuman();
        Board board = newBoard(alice);
        Human stranger = stranger();

        assertTrue(gateway.pendingActions(board.uri, ctx(stranger)).isEmpty());
    }

    // ─────────────────────────── 参数与解析 ───────────────────────────

    @Test
    void anUnknownActionIsNotFound() {
        Human alice = newHuman();
        ActionResponse response = gateway.execute(
                ActionRequest.of("game.teleport", "game://session/" + UUID.randomUUID(), null), ctx(alice));

        assertEquals(ActionStatus.NOT_FOUND, response.status());
        assertEquals("ACTION_NOT_FOUND", response.error().code());
    }

    @Test
    void aMissingTargetIsRejected() {
        Human alice = newHuman();
        ActionResponse response = gateway.execute(ActionRequest.of(MAKE_MOVE, null, null), ctx(alice));

        assertEquals(ActionStatus.INVALID_ARGUMENT, response.status());
        assertEquals("TARGET_REQUIRED", response.error().code());
    }

    @Test
    void aMissingActionIsRejected() {
        Human alice = newHuman();
        assertEquals(ActionStatus.INVALID_ARGUMENT,
                gateway.execute(new ActionRequest("  ", "game://session/x", null, null), ctx(alice)).status());
    }

    // ─────────────────────────── 并发写 ───────────────────────────

    /** 拿着一个过期的 {@code expectedResourceVersion} 落子 → 干净的 409, 并带回当前版本。 */
    @Test
    void aStaleExpectedResourceVersionIsAConflict() {
        Human alice = newHuman();
        Board board = newBoard(alice);
        String key = "move-" + UUID.randomUUID();

        ActionResponse response = gateway.execute(
                new ActionRequest(MAKE_MOVE, board.uri, input(4), 99L), alice.principal(), key).response();

        assertEquals(ActionStatus.STATE_CONFLICT, response.status());
        assertEquals("STATE_CONFLICT", response.error().code());
        assertNotNull(response.resource(), "冲突要把当前资源带回去, 调用方才能立刻重试");
        assertEquals(1L, response.resource().version());
        assertNull(boardRow(board.uri).state().path("board").get(4).textValue(), "冲突的落子不落盘");
    }

    // ─────────────────────────── 多参与方 ───────────────────────────

    /**
     * 真人先落一子, 数字人问"我能做什么" —— 应用回答"落子", 而真人再问时得到空。
     * 平台在这里没有任何关于棋的逻辑, 它只是把问题转给应用。
     */
    @Test
    void pendingActionsComeFromTheApplicationNotFromThePlatform() {
        Human alice = newHuman();
        Agent bob = newAgent();
        Board board = newBoard(alice, bob.companionId());

        // 开局先手是 X, 也就是 0 号座位的真人
        assertEquals(List.of(MAKE_MOVE, "game.surrender"), pendingIds(board, ctx(alice)));
        assertTrue(gateway.pendingActions(board.uri, ctx(bob)).isEmpty(),
                "轮到别人时不给动作 —— 否则 Agent 会自问自答连下两步");

        assertEquals(ActionStatus.SUCCESS,
                gateway.execute(move(board, 0), alice.principal(), "k-" + UUID.randomUUID()).response().status());

        assertEquals(List.of(MAKE_MOVE, "game.surrender"), pendingIds(board, ctx(bob)), "轮到数字人了");
        assertTrue(gateway.pendingActions(board.uri, ctx(alice)).isEmpty(), "真人走完了, 该等对方");
    }

    /** 终局之后谁也不该被叫醒 —— 应用说"没人在等下一手", 平台照转。 */
    @Test
    void pendingActionsGoQuietOnceTheGameIsOver() {
        Human alice = newHuman();
        Agent bob = newAgent();
        Board board = newBoard(alice, bob.companionId());

        gateway.execute(move(board, 0), alice.principal(), "a-" + UUID.randomUUID());   // X
        gateway.execute(move(board, 3), bob.principal(), "b-" + UUID.randomUUID());     // O
        gateway.execute(move(board, 1), alice.principal(), "c-" + UUID.randomUUID());   // X
        gateway.execute(move(board, 4), bob.principal(), "d-" + UUID.randomUUID());     // O
        gateway.execute(move(board, 2), alice.principal(), "e-" + UUID.randomUUID());   // X 三连

        assertEquals("X", boardRow(board.uri).state().path("winner").asText());
        assertTrue(gateway.pendingActions(board.uri, ctx(alice)).isEmpty());
        assertTrue(gateway.pendingActions(board.uri, ctx(bob)).isEmpty());
    }

    /** 不是对局者: 能读, 但落子被应用拒绝 —— 参与资格是应用的规则, 不是平台的动作权限。 */
    @Test
    void aThirdPartyCanReadButCannotPlay() {
        Human alice = newHuman();
        Agent bob = newAgent();
        Board board = newBoard(alice, bob.companionId);
        Human carol = newHuman();

        assertEquals(ActionStatus.SUCCESS,
                gateway.execute(ActionRequest.of(STATE, board.uri, null), ctx(carol)).status());

        ActionResponse response = gateway.execute(
                new ActionRequest(MAKE_MOVE, board.uri, input(4), null), carol.principal(), "k-" + UUID.randomUUID()).response();
        assertEquals(ActionStatus.FAILED, response.status());
        assertEquals("NOT_A_PLAYER", response.error().code());
    }

    @Test
    void playingOutOfTurnIsRefusedByTheApplication() {
        Human alice = newHuman();
        Agent bob = newAgent();
        Board board = newBoard(alice, bob.companionId);

        gateway.execute(move(board, 0), alice.principal(), "k1-" + UUID.randomUUID());
        ActionResponse twice = gateway.execute(move(board, 1), alice.principal(), "k2-" + UUID.randomUUID()).response();

        assertEquals(ActionStatus.FAILED, twice.status());
        assertEquals("NOT_YOUR_TURN", twice.error().code());
    }

    // ─────────────────────────── 夹具 ───────────────────────────

    private record Human(String principalId) {
        ResolvedPrincipal principal() {
            return new ResolvedPrincipal(PrincipalType.HUMAN, principalId, null, principalId,
                    null, UUID.randomUUID().toString(), ResolvedPrincipal.SOURCE_JWT);
        }
    }

    private record Agent(String companionId, String userId) {
        ResolvedPrincipal principal() {
            return new ResolvedPrincipal(PrincipalType.AGENT, companionId, companionId, userId,
                    null, UUID.randomUUID().toString(), ResolvedPrincipal.SOURCE_INTERNAL);
        }
    }

    private record Board(String uri, ApplicationSessionRecord session, ActionResponse create) {
    }

    private Human newHuman() {
        String principalId = UUID.randomUUID().toString();   // 真人 principal id 就是 user id
        installationService.install(APP_ID, new Human(principalId).principal(), null);
        return new Human(principalId);
    }

    /**
     * 有身份、但<em>没装</em>这个应用。{@link #newHuman()} 是"装好的人", 拿它当陌生人用会
     * 让 NOT_INSTALLED 那条断言测到别的东西上去 —— 这个夹具的存在就是为了不犯那个错。
     */
    private Human stranger() {
        return new Human(UUID.randomUUID().toString());
    }

    private Agent newAgent() {
        Agent agent = new Agent(UUID.randomUUID().toString(), UUID.randomUUID().toString());
        installationService.install(APP_ID, agent.principal(), null);
        return agent;
    }

    /** 装好 + 开好会话, 然后开一局。返回的那份 create 响应供用例断言。 */
    private Board newBoard(Human alice, String opponentPrincipalId) {
        ApplicationSessionRecord session = sessionService.open(APP_ID, alice.principal());
        String uri = "game://session/" + session.getId();
        JsonNode input = opponentPrincipalId == null
                ? null : objectMapper.createObjectNode().put("opponentPrincipalId", opponentPrincipalId);
        ActionResponse create = gateway.execute(ActionRequest.of(CREATE, uri, input), ctx(alice));
        return new Board(uri, session, create);
    }

    private Board newBoard(Human alice) {
        return newBoard(alice, null);
    }

    private Board newBoard() {
        return newBoard(newHuman());
    }

    private static InvocationContext ctx(Human human) {
        return human.principal().toInvocationContext();
    }

    private static InvocationContext ctx(Agent agent) {
        return agent.principal().toInvocationContext();
    }

    private ActionRequest move(Board board, int position) {
        return new ActionRequest(MAKE_MOVE, board.uri, input(position), null);
    }

    private JsonNode input(int position) {
        return objectMapper.createObjectNode().put("position", position);
    }

    private ResourceView boardRow(String uri) {
        return gateway.read(uri).orElseThrow(() -> new AssertionError("资源应当存在: " + uri));
    }

    private long invocationCount(String principalId) {
        return invocations.findAll().stream()
                .filter(r -> principalId.equals(r.getPrincipalId()))
                .count();
    }

    private List<String> pendingIds(Board board, InvocationContext ctx) {
        return gateway.pendingActions(board.uri, ctx).stream().map(ActionSpec::actionId).toList();
    }

    private String serialize(ActionResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
