package com.luxera.companion.application.builtin.tictactoe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.application.runtime.ActionRuntime;
import com.luxera.companion.application.runtime.DefaultActionsRuntime;
import com.luxera.companion.application.spi.LocalApplicationProvider;
import com.luxera.companion.contracts.application.ActionSpec;
import com.luxera.companion.contracts.application.ApplicationView;
import com.luxera.companion.contracts.application.AttentionPolicy;
import com.luxera.companion.contracts.application.CapabilityView;
import com.luxera.companion.contracts.application.InvocationContext;
import com.luxera.companion.contracts.application.PermissionLevel;
import com.luxera.companion.contracts.application.ResourceView;
import com.luxera.companion.contracts.application.RiskLevel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * V10 §14/LAP §44 TicTacToe 应用适配器: 把现有 GameService 包装成 LAP 动作。
 *
 * <p>它是 LAP 的"应用侧": 实现 {@link LocalApplicationProvider}, 于是数字人只通过
 * {@code ApplicationRuntimePort} 看见它 —— 棋盘长什么样、轮到谁、下一步怎么走才聪明,
 * 全部留在这个类里, 不再渗进 {@code AgentRuntime}。
 *
 * <p>动作:
 *   game.create     开局(WRITE, LOW)
 *   game.state      读局面(READ, NONE)
 *   game.make_move  落子(WRITE, LOW)
 *   game.surrender  投降(WRITE, LOW)
 *
 * <p>资源 URI 形态: {@code game://session/{roomId}}。
 */
@Slf4j
@Component
public class TicTacToeApplicationAdapter implements LocalApplicationProvider {

    public static final String APP_CODE = "tictactoe";
    public static final String VERSION = "1.0.0";

    public static final String ACTION_CREATE = "game.create";
    public static final String ACTION_STATE = "game.state";
    public static final String ACTION_MAKE_MOVE = "game.make_move";
    public static final String ACTION_SURRENDER = "game.surrender";

    public static final String CAPABILITY_GAME_PLAY = "game.play";

    /** 资源 URI 前缀与形态: game://session/{roomId} */
    private static final String URI_SCHEME = "game";
    private static final String URI_HOST = "session";

    /**
     * 井字棋的走子策略。它原先硬编码在 {@code AgentRuntime.evaluateAndDecideMove} 里,
     * R2 起搬到这里随 ActionSpec 一起下发 —— 于是加第二个游戏时数字人一行都不用改。
     * R4 起这段文本的归宿是 manifest 的 {@code actions[].agentHint}。
     */
    private static final String MOVE_HINT =
            "井字棋, 你执 O, 对手执 X 且先手。棋盘编号: 0 1 2 / 3 4 5 / 6 7 8(三行三列)。\n"
                    + "只允许落在空位。选择位置的优先级: "
                    + "① 能让自己立刻三连的必胜位 → ② 能阻断对手三连的位置 → "
                    + "③ 中心(4) → ④ 四个角(0/2/6/8) → ⑤ 四条边(1/3/5/7)。\n"
                    + "落子时 input.player 固定为 \"companion\"。";

    private static final String MOVE_SCHEMA = """
            {"type":"object",
             "properties":{
               "position":{"type":"integer","minimum":0,"maximum":8,
                 "description":"0-8 的空位编号(0 1 2 / 3 4 5 / 6 7 8)"},
               "player":{"type":"string","enum":["companion"],"description":"固定为 companion"}},
             "required":["position","player"],
             "additionalProperties":false}
            """;

    private static final String CREATE_SCHEMA = """
            {"type":"object","properties":{},"additionalProperties":false}
            """;

    private static final String SURRENDER_SCHEMA = """
            {"type":"object",
             "properties":{"roomId":{"type":"string"}},
             "required":["roomId"],
             "additionalProperties":false}
            """;

    private final TicTacToeGameService gameService;
    private final DefaultActionsRuntime actionRuntime;
    private final ObjectMapper objectMapper;

    public TicTacToeApplicationAdapter(TicTacToeGameService gameService,
                                       DefaultActionsRuntime actionRuntime,
                                       ObjectMapper objectMapper) {
        this.gameService = gameService;
        this.actionRuntime = actionRuntime;
        this.objectMapper = objectMapper;
    }

    /**
     * 把 {@link #actions()} 声明的动作绑定到方法上 —— 声明与实现同源, 不会出现
     * "manifest 里写了、代码里没有"的漂移。R4 起这件事由 {@code ManifestRegistrar} 按
     * {@code ActionHandlerKey} 校验并强制(缺 handler 即发布失败)。
     */
    @PostConstruct
    void registerActions() {
        for (ActionSpec spec : actions()) {
            actionRuntime.register(spec.actionId(), handlerFor(spec.actionId()), spec);
        }
        log.info("[TicTacToeAdapter] 已注册 {} 个 LAP 动作", actions().size());
    }

    private DefaultActionsRuntime.ActionHandler handlerFor(String actionId) {
        return switch (actionId) {
            case ACTION_CREATE -> this::create;
            case ACTION_STATE -> this::readState;
            case ACTION_MAKE_MOVE -> this::makeMove;
            case ACTION_SURRENDER -> this::surrender;
            default -> (id, input, ctx) -> ActionRuntime.ActionResult.fail(
                    "NO_HANDLER", "动作未实现: " + id);
        };
    }

    // ─────────────────────────── LocalApplicationProvider ───────────────────────────

    @Override
    public String applicationId() {
        return APP_CODE;
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public CapabilityView capability() {
        return new CapabilityView(CAPABILITY_GAME_PLAY, "对弈", "与真人对手进行回合制棋类对弈", "game");
    }

    @Override
    public ApplicationView application() {
        return new ApplicationView(APP_CODE, VERSION, "井字棋",
                "经典三连棋: 真人执 X 先手, 数字人执 O 后手", "game", List.of(CAPABILITY_GAME_PLAY));
    }

    @Override
    public List<ActionSpec> actions() {
        return List.of(
                new ActionSpec(ACTION_CREATE, APP_CODE, CAPABILITY_GAME_PLAY,
                        "开一局新棋(真人先手)", PermissionLevel.WRITE, RiskLevel.LOW,
                        AttentionPolicy.AWARE, schema(CREATE_SCHEMA), null),
                new ActionSpec(ACTION_STATE, APP_CODE, CAPABILITY_GAME_PLAY,
                        "读取当前棋盘局面", PermissionLevel.READ, RiskLevel.NONE,
                        AttentionPolicy.SUBCONSCIOUS, null, null),
                new ActionSpec(ACTION_MAKE_MOVE, APP_CODE, CAPABILITY_GAME_PLAY,
                        "落子", PermissionLevel.WRITE, RiskLevel.LOW,
                        AttentionPolicy.FOCUSED, schema(MOVE_SCHEMA), MOVE_HINT),
                new ActionSpec(ACTION_SURRENDER, APP_CODE, CAPABILITY_GAME_PLAY,
                        "认输/投降", PermissionLevel.WRITE, RiskLevel.LOW,
                        AttentionPolicy.AWARE, schema(SURRENDER_SCHEMA), null));
    }

    @Override
    public Optional<ResourceView> read(String resourceUri) {
        String roomId = roomIdOf(resourceUri);
        if (roomId == null) return Optional.empty();
        GameSession s = gameService.get(roomId);
        if (s == null) return Optional.empty();
        return Optional.of(toResource(resourceUri, s));
    }

    /**
     * 轮到 O 且局未终 ⇒ 只有 "落子" 一件可做; 其余情况无事可做。
     * 这就是原先 {@code AgentRuntime} 里 {@code if (!"O".equalsIgnoreCase(turn)) return;} 的去处。
     */
    @Override
    public List<ActionSpec> pendingActions(ResourceView resource, InvocationContext ctx) {
        if (resource == null || resource.state() == null) return List.of();
        JsonNode state = resource.state();
        String turn = state.path("turn").asText("");
        String winner = state.path("winner").asText("");
        if (!"O".equalsIgnoreCase(turn) || !winner.isEmpty()) return List.of();
        return actions().stream().filter(a -> ACTION_MAKE_MOVE.equals(a.actionId())).toList();
    }

    // ─────────────────────────── 动作实现 ───────────────────────────

    private ActionRuntime.ActionResult create(String actionId, Map<String, Object> input,
                                              ActionRuntime.ActionContext ctx) {
        GameSession s = gameService.start(ctx.userId(), ctx.companionId());
        return ActionRuntime.ActionResult.success(Map.of(
                "roomId", s.getRoomId(), "status", s.getStatus(), "uri", uriOf(s.getRoomId())));
    }

    private ActionRuntime.ActionResult readState(String actionId, Map<String, Object> input,
                                                 ActionRuntime.ActionContext ctx) {
        String roomId = roomId(input, ctx);
        GameSession s = gameService.get(roomId);
        if (s == null) return ActionRuntime.ActionResult.fail("NOT_FOUND", "房间不存在: " + roomId);
        return ActionRuntime.ActionResult.success(Map.of(
                "roomId", s.getRoomId(), "status", s.getStatus(), "state", s.getStateJson(),
                "uri", uriOf(roomId)));
    }

    private ActionRuntime.ActionResult makeMove(String actionId, Map<String, Object> input,
                                                ActionRuntime.ActionContext ctx) {
        String roomId = roomId(input, ctx);
        // player 缺省即 companion: 一次由 Agent 发起的落子, 按定义就是数字人自己的那一步。
        // 真人路径显式传 "user"(见 LegacyApplicationGameController / TicTacToeGameService 的既有调用)。
        Object playerObj = input.get("player");
        String player = playerObj == null ? "companion" : String.valueOf(playerObj);
        Object posObj = input.get("position");
        if (posObj == null) {
            return ActionRuntime.ActionResult.fail("INVALID_MOVE", "缺少 position");
        }
        int position = posObj instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(posObj));
        try {
            GameSession s = gameService.move(roomId, player, position);
            return ActionRuntime.ActionResult.success(Map.of(
                    "roomId", s.getRoomId(), "status", s.getStatus(),
                    "state", s.getStateJson(), "uri", uriOf(roomId)));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ActionRuntime.ActionResult.fail("INVALID_MOVE", e.getMessage());
        }
    }

    private ActionRuntime.ActionResult surrender(String actionId, Map<String, Object> input,
                                                 ActionRuntime.ActionContext ctx) {
        String roomId = roomId(input, ctx);
        GameSession s = gameService.finish(roomId, "SURRENDER");
        return ActionRuntime.ActionResult.success(Map.of(
                "roomId", s.getRoomId(), "status", s.getStatus(), "uri", uriOf(roomId)));
    }

    // ─────────────────────────── 内部工具 ───────────────────────────

    /** 资源 URI: game://session/{roomId} */
    public static String uriOf(String roomId) {
        return URI_SCHEME + "://" + URI_HOST + "/" + roomId;
    }

    /** 反解 game://session/{roomId}; 非本应用的 URI 返回 null。 */
    public static String roomIdOf(String resourceUri) {
        if (resourceUri == null) return null;
        String prefix = URI_SCHEME + "://" + URI_HOST + "/";
        if (!resourceUri.startsWith(prefix)) return null;
        String roomId = resourceUri.substring(prefix.length()).trim();
        return roomId.isEmpty() ? null : roomId;
    }

    private static String roomId(Map<String, Object> input, ActionRuntime.ActionContext ctx) {
        Object roomId = input.get("roomId");
        if (roomId != null) return String.valueOf(roomId);
        // 落子/投降也可以直接以资源 URI 作为 target
        return TicTacToeApplicationAdapter.roomIdOf(ctx.sessionId());
    }

    private ResourceView toResource(String uri, GameSession s) {
        JsonNode state = null;
        try {
            state = objectMapper.readTree(s.getStateJson());
        } catch (Exception e) {
            log.warn("[TicTacToeAdapter] 局面 JSON 解析失败 room={}: {}", s.getRoomId(), e.getMessage());
        }
        return new ResourceView(uri, "game.session", APP_CODE, s.getRoomId(), state,
                versionOf(state), toInstant(s.getFinishedAt() != null ? s.getFinishedAt() : s.getCreatedAt()),
                MOVE_HINT);
    }

    /**
     * 乐观并发令牌: 棋盘上的落子数(0..9, 单调不减)。
     * R4 起由 {@code resource.state_version} 列取代 —— 那时才有真正的 CAS。
     */
    private static long versionOf(JsonNode state) {
        if (state == null) return 0L;
        JsonNode board = state.path("board");
        long marks = 0;
        for (JsonNode cell : board) {
            if (!cell.asText("").isEmpty()) marks++;
        }
        return marks;
    }

    private static Instant toInstant(LocalDateTime t) {
        return t == null ? null : t.atZone(ZoneId.systemDefault()).toInstant();
    }

    private JsonNode schema(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.warn("[TicTacToeAdapter] schema 解析失败: {}", e.getMessage());
            return null;
        }
    }
}
