package com.luxera.companion.application.web;

import com.luxera.companion.application.ApplicationRegistry;
import com.luxera.companion.application.builtin.tictactoe.GameSession;
import com.luxera.companion.application.builtin.tictactoe.TicTacToeGameService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * V10 §58/§9.3 应用与游戏 API(前端 Real Client 经 /api/v10 访问 DH 平台)。
 */
@RestController
@RequestMapping("/api/v10")
@RequiredArgsConstructor
public class LegacyApplicationGameController {

    private final ApplicationRegistry appRegistry;
    private final TicTacToeGameService gameService;

    /** 列出可用应用 */
    @GetMapping("/applications")
    public List<Map<String, Object>> listApplications() {
        return appRegistry.list().stream()
                .map(a -> Map.<String, Object>of(
                        "code", a.getCode(),
                        "name", a.getName(),
                        "version", a.getVersion()))
                .toList();
    }

    /** 开局(Real User vs Agent) */
    @PostMapping("/games/tictactoe/start")
    public Map<String, Object> start(@RequestBody Map<String, String> body) {
        GameSession s = gameService.start(body.get("userId"), body.get("companionId"));
        return Map.of("roomId", s.getRoomId(), "status", s.getStatus(), "state", s.getStateJson());
    }

    /** 落子 */
    @PostMapping("/games/tictactoe/{roomId}/move")
    public Map<String, Object> move(@PathVariable String roomId, @RequestBody Map<String, Object> body) {
        GameSession s = gameService.move(roomId,
                String.valueOf(body.get("player")),
                ((Number) body.get("position")).intValue());
        return Map.of("roomId", s.getRoomId(), "status", s.getStatus(), "state", s.getStateJson());
    }

    /** 获取局面 */
    @GetMapping("/games/tictactoe/{roomId}")
    public Map<String, Object> get(@PathVariable String roomId) {
        GameSession s = gameService.get(roomId);
        if (s == null) return Map.of("error", "not_found");
        return Map.of("roomId", s.getRoomId(), "status", s.getStatus(), "state", s.getStateJson());
    }

    /** 结束 */
    @PostMapping("/games/tictactoe/{roomId}/finish")
    public Map<String, Object> finish(@PathVariable String roomId, @RequestBody Map<String, String> body) {
        GameSession s = gameService.finish(roomId, body.getOrDefault("result", "DRAW"));
        return Map.of("roomId", s.getRoomId(), "status", s.getStatus(), "state", s.getStateJson());
    }
}