package com.luxera.companion.digitalhuman.application.builtin.tictactoe;

import com.luxera.companion.digitalhuman.application.domain.ActionDescriptor;
import com.luxera.companion.digitalhuman.application.domain.PermissionLevel;
import com.luxera.companion.digitalhuman.application.domain.RiskLevel;
import com.luxera.companion.digitalhuman.application.runtime.ActionRuntime;
import com.luxera.companion.digitalhuman.application.runtime.DefaultActionsRuntime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.Map;

/**
 * V10 §14/LAP §44 TicTacToe 应用适配器: 把现有 GameService 包装成 LAP 动作。
 *
 * 边界: Agent 认知链只调 {@link ActionRuntime}, 不直接碰 GameService ——
 * 未来换语言/换数据库/换服务器, Agent 侧无感知。
 *
 * 动作:
 *   game.create     开局(WRITE, LOW)
 *   game.state      读局面(READ, NONE)
 *   game.make_move  落子(WRITE, LOW)
 *   game.surrender  投降(WRITE, LOW)
 */
@Slf4j
@Component
public class TicTacToeApplicationAdapter {

    public static final String APP_CODE = "tictactoe";

    public static final String ACTION_CREATE = "game.create";
    public static final String ACTION_STATE = "game.state";
    public static final String ACTION_MAKE_MOVE = "game.make_move";
    public static final String ACTION_SURRENDER = "game.surrender";

    private final TicTacToeGameService gameService;
    private final DefaultActionsRuntime actionRuntime;

    public TicTacToeApplicationAdapter(TicTacToeGameService gameService,
                                       DefaultActionsRuntime actionRuntime) {
        this.gameService = gameService;
        this.actionRuntime = actionRuntime;
    }

    @PostConstruct
    void registerActions() {
        actionRuntime.register(ACTION_CREATE, this::create, ActionDescriptor.of(
                ACTION_CREATE, APP_CODE, "开局(真人先手)", PermissionLevel.WRITE, RiskLevel.LOW));
        actionRuntime.register(ACTION_STATE, this::readState, ActionDescriptor.of(
                ACTION_STATE, APP_CODE, "读取当前棋盘局面", PermissionLevel.READ, RiskLevel.NONE));
        actionRuntime.register(ACTION_MAKE_MOVE, this::makeMove, ActionDescriptor.of(
                ACTION_MAKE_MOVE, APP_CODE, "落子", PermissionLevel.WRITE, RiskLevel.LOW));
        actionRuntime.register(ACTION_SURRENDER, this::surrender, ActionDescriptor.of(
                ACTION_SURRENDER, APP_CODE, "认输/投降", PermissionLevel.WRITE, RiskLevel.LOW));
        log.info("[TicTacToeAdapter] 已注册 4 个 LAP 动作");
    }

    private ActionRuntime.ActionResult create(String actionId, Map<String, Object> input,
                                              ActionRuntime.ActionContext ctx) {
        String userId = ctx.userId();
        String companionId = ctx.companionId();
        GameSession s = gameService.start(userId, companionId);
        return ActionRuntime.ActionResult.success(
                Map.of("roomId", s.getRoomId(), "status", s.getStatus()),
                Map.of("appCode", APP_CODE, "action", ACTION_CREATE, "roomId", s.getRoomId()),
                "game.started");
    }

    private ActionRuntime.ActionResult readState(String actionId, Map<String, Object> input,
                                                 ActionRuntime.ActionContext ctx) {
        String roomId = (String) input.get("roomId");
        GameSession s = gameService.get(roomId);
        if (s == null) return ActionRuntime.ActionResult.fail("NOT_FOUND", "房间不存在: " + roomId);
        return ActionRuntime.ActionResult.success(Map.of(
                "roomId", s.getRoomId(), "status", s.getStatus(), "state", s.getStateJson()));
    }

    private ActionRuntime.ActionResult makeMove(String actionId, Map<String, Object> input,
                                                ActionRuntime.ActionContext ctx) {
        String roomId = (String) input.get("roomId");
        String player = (String) input.get("player");
        Object posObj = input.get("position");
        int position = posObj instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(posObj));
        try {
            GameSession s = gameService.move(roomId, player, position);
            return ActionRuntime.ActionResult.success(
                    Map.of("roomId", s.getRoomId(), "status", s.getStatus(), "state", s.getStateJson()),
                    Map.of("appCode", APP_CODE, "action", ACTION_MAKE_MOVE,
                            "roomId", roomId, "player", player, "position", position),
                    "game.move_made");
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ActionRuntime.ActionResult.fail("INVALID_MOVE", e.getMessage());
        }
    }

    private ActionRuntime.ActionResult surrender(String actionId, Map<String, Object> input,
                                                 ActionRuntime.ActionContext ctx) {
        String roomId = (String) input.get("roomId");
        GameSession s = gameService.finish(roomId, "SURRENDER");
        return ActionRuntime.ActionResult.success(
                Map.of("roomId", s.getRoomId(), "status", s.getStatus()),
                Map.of("appCode", APP_CODE, "action", ACTION_SURRENDER, "roomId", roomId),
                "game.finished");
    }
}