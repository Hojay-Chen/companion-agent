package com.luxera.companion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.luxera.companion.contracts.application.ActionRequest;
import com.luxera.companion.contracts.application.ActionResponse;
import com.luxera.companion.contracts.application.ActionSpec;
import com.luxera.companion.contracts.application.ActionStatus;
import com.luxera.companion.contracts.application.ApplicationView;
import com.luxera.companion.contracts.application.AttentionPolicy;
import com.luxera.companion.contracts.application.CapabilityView;
import com.luxera.companion.contracts.application.InvocationContext;
import com.luxera.companion.contracts.application.PermissionLevel;
import com.luxera.companion.contracts.application.PrincipalType;
import com.luxera.companion.contracts.application.ResourceView;
import com.luxera.companion.contracts.application.RiskLevel;
import com.luxera.companion.contracts.spi.ApplicationRuntimePort;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 数字人模块测试自己用的小应用 —— <b>刻意做成真的能下完一局</b>, 不做返回
 * {@code Optional.empty()} 的空壳。
 *
 * <p>理由: {@code AgentApplicationFlow} 的三条核心行为(轮到我才动、不解析应用状态、
 * 空 pendingActions 就不问 LLM)都只有在"对面真的有一个应用"时才被走到。一个空 mock 会让
 * 这些逻辑悄悄腐烂而所有断言仍然全绿 —— 那正是这次重构要根除的病。
 *
 * <p>它只依赖 contracts, 不认识 application-platform 一行代码: 这就是"数字人平台在没有应用
 * 平台的情况下也能独立启动、独立测试"这件事的证据。真实应用(tictactoe / gomoku / reminder)
 * 住在 application-platform 里。
 */
public class InMemoryGameApplication implements ApplicationRuntimePort {

    private static final String APP_ID = "in-memory-game";
    private static final String VERSION = "1.0.0";
    private static final String CAPABILITY = "game.play";
    private static final String ACTION_CREATE = "game.create";
    private static final String ACTION_STATE = "game.state";
    private static final String ACTION_MAKE_MOVE = "game.make_move";

    private final ObjectMapper mapper;
    private final Map<String, ObjectNode> sessions = new ConcurrentHashMap<>();
    private final List<String> installed = new CopyOnWriteArrayList<>();

    public InMemoryGameApplication(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    // ─────────────────────────── ApplicationRuntimePort ───────────────────────────

    @Override
    public List<CapabilityView> capabilities() {
        return List.of(new CapabilityView(CAPABILITY, "对弈", "回合制棋类对弈", "game"));
    }

    @Override
    public List<ApplicationView> applicationsFor(String capabilityId) {
        if (!CAPABILITY.equals(capabilityId)) return List.of();
        return List.of(new ApplicationView(APP_ID, VERSION, "内存棋局",
                "数字人模块测试自带的极简棋局", "game", List.of(CAPABILITY)));
    }

    @Override
    public List<ActionSpec> actionsOf(String applicationId) {
        return APP_ID.equals(applicationId) ? actions() : List.of();
    }

    /**
     * 内存参考应用没有安装表可写 —— 它对"装过没有"这件事没有意见, 所以只记一笔调用痕迹。
     * {@code AgentApplicationFlowTest} 之外的用例不会碰到它。
     */
    @Override
    public void ensureInstalled(String applicationId, InvocationContext ctx) {
        installed.add(applicationId + ":" + (ctx == null ? "?" : ctx.principalId()));
    }

    /** 测试可读: 谁在什么时候要求过安装。 */
    public List<String> installed() {
        return List.copyOf(installed);
    }

    @Override
    public Optional<ResourceView> read(String resourceUri) {
        ObjectNode state = sessions.get(roomIdOf(resourceUri));
        return state == null ? Optional.empty() : Optional.of(view(resourceUri, state));
    }

    /** 轮到 O 且局未终 ⇒ 只有"落子"一件可做。应用说了算, 数字人不判断。 */
    @Override
    public List<ActionSpec> pendingActions(String resourceUri, InvocationContext ctx) {
        ObjectNode state = sessions.get(roomIdOf(resourceUri));
        if (state == null) return List.of();
        if (!"O".equals(state.path("turn").asText()) || !state.path("winner").asText().isEmpty()) {
            return List.of();
        }
        return actions().stream().filter(a -> ACTION_MAKE_MOVE.equals(a.actionId())).toList();
    }

    @Override
    public ActionResponse execute(ActionRequest request, InvocationContext ctx) {
        String action = request == null ? null : request.action();
        String uri = request == null ? null : request.target();
        if (action == null) {
            return ActionResponse.failure(ActionStatus.INVALID_ARGUMENT, "INVALID_ARGUMENT", "缺少 action");
        }
        return switch (action) {
            case ACTION_CREATE -> create(uri, ctx);
            case ACTION_STATE -> read(uri)
                    .map(v -> ActionResponse.success(mapper.valueToTree(Map.of("uri", v.uri())), v))
                    .orElseGet(() -> notFound(uri));
            case ACTION_MAKE_MOVE -> makeMove(uri, request.input(),
                    ctx != null && ctx.principalType() == PrincipalType.AGENT);
            default -> ActionResponse.failure(ActionStatus.NOT_FOUND, "UNKNOWN_ACTION",
                    "没有这个动作: " + action);
        };
    }

    // ─────────────────────────── 棋局本身 ───────────────────────────

    private ActionResponse create(String uri, InvocationContext ctx) {
        String roomId = roomIdOf(uri);
        if (roomId == null) {
            return ActionResponse.failure(ActionStatus.INVALID_ARGUMENT, "INVALID_ARGUMENT", "target 不是本应用的资源");
        }
        ObjectNode state = mapper.createObjectNode();
        state.putArray("board").add("").add("").add("").add("").add("").add("").add("").add("").add("");
        state.put("turn", "X");
        state.put("winner", "");
        sessions.put(roomId, state);
        return ActionResponse.success(mapper.valueToTree(Map.of("roomId", roomId)), view(uri, state));
    }

    private ActionResponse makeMove(String uri, JsonNode input, boolean byAgent) {
        String roomId = roomIdOf(uri);
        ObjectNode state = sessions.get(roomId);
        if (state == null) return notFound(uri);
        if (!state.path("winner").asText().isEmpty()) {
            return ActionResponse.failure(ActionStatus.STATE_CONFLICT, "GAME_OVER", "棋局已终");
        }
        int position = input == null ? -1 : input.path("position").asInt(-1);
        ArrayNode board = (ArrayNode) state.path("board");
        if (position < 0 || position > 8 || !board.path(position).asText("").isEmpty()) {
            return ActionResponse.failure(ActionStatus.INVALID_ARGUMENT, "INVALID_MOVE", "非法落子位置");
        }
        // 行动的 principal 决定执什么子 —— 应用只认 principal, 不认"这是不是 agent"
        board.set(position, mapper.getNodeFactory().textNode(byAgent ? "O" : "X"));
        state.put("turn", byAgent ? "X" : "O");
        String winner = winnerOf(board);
        state.put("winner", winner);
        return ActionResponse.success(mapper.valueToTree(Map.of("position", position)), view(uri, state));
    }

    private static String winnerOf(ArrayNode board) {
        int[][] lines = {{0,1,2},{3,4,5},{6,7,8},{0,3,6},{1,4,7},{2,5,8},{0,4,8},{2,4,6}};
        for (int[] line : lines) {
            String a = board.path(line[0]).asText("");
            if (!a.isEmpty() && a.equals(board.path(line[1]).asText(""))
                    && a.equals(board.path(line[2]).asText(""))) {
                return a;
            }
        }
        for (JsonNode cell : board) if (cell.asText("").isEmpty()) return "";
        return "DRAW";
    }

    // ─────────────────────────── 夹具 ───────────────────────────

    /** 便于断言: 直接看棋盘。 */
    public String cellAt(String uri, int index) {
        ObjectNode state = sessions.get(roomIdOf(uri));
        return state == null ? null : state.path("board").path(index).asText("");
    }

    private ResourceView view(String uri, ObjectNode state) {
        long marks = 0;
        for (JsonNode cell : state.path("board")) if (!cell.asText("").isEmpty()) marks++;
        return new ResourceView(uri, "game.session", APP_ID, roomIdOf(uri), state.deepCopy(),
                marks, Instant.now(), "轮到你就落在空位上。");
    }

    private static ActionResponse notFound(String uri) {
        return ActionResponse.failure(ActionStatus.NOT_FOUND, "NOT_FOUND", "资源不存在: " + uri);
    }

    private List<ActionSpec> actions() {
        return List.of(
                new ActionSpec(ACTION_CREATE, APP_ID, CAPABILITY, "开一局", PermissionLevel.WRITE,
                        RiskLevel.LOW, AttentionPolicy.AWARE, null, null),
                new ActionSpec(ACTION_STATE, APP_ID, CAPABILITY, "读局面", PermissionLevel.READ,
                        RiskLevel.NONE, AttentionPolicy.SUBCONSCIOUS, null, null),
                new ActionSpec(ACTION_MAKE_MOVE, APP_ID, CAPABILITY, "落子", PermissionLevel.WRITE,
                        RiskLevel.LOW, AttentionPolicy.FOCUSED, null, "落在空位"));
    }

    public static String newRoomUri() {
        return "game://session/" + UUID.randomUUID();
    }

    private static String roomIdOf(String uri) {
        String prefix = "game://session/";
        if (uri == null || !uri.startsWith(prefix)) return null;
        String id = uri.substring(prefix.length()).trim();
        return id.isEmpty() ? null : id;
    }
}
