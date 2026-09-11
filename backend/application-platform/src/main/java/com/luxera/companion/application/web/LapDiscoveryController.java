package com.luxera.companion.application.web;

import com.luxera.companion.application.action.ActionGateway;
import com.luxera.companion.application.resource.ResourceStore;
import com.luxera.companion.contracts.application.ActionSpec;
import com.luxera.companion.contracts.application.ApplicationView;
import com.luxera.companion.contracts.application.CapabilityView;
import com.luxera.companion.contracts.application.ResourceView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * LAP v1: <b>发现链</b> —— 能力 → 应用 → 动作, 外加资源读取。
 *
 * <p>这条链就是"不要把 50000 个 action 塞给 LLM"的全部实现: 每一级都比上一级窄一个数量级。
 * 能力有几十个, 某个能力下的应用有个位数, 某个应用的动作有个位数。Agent 永远只看到当前这一步
 * 该看的那一层。
 *
 * <p><b>全是只读接口, 所以全部不需要幂等键</b> —— 这也正是"READ 不产生 invocation"那条规则
 * 在传输层的体现: 刷新一次应用列表不该在 {@code action_invocation} 里留下任何痕迹。
 */
@RestController
@RequestMapping("/api/v1")
public class LapDiscoveryController {

    private final ActionGateway gateway;
    private final ResourceStore resources;

    public LapDiscoveryController(ActionGateway gateway, ResourceStore resources) {
        this.gateway = gateway;
        this.resources = resources;
    }

    /** 全部能力 —— 发现链的第一级, 也是 Agent 做"要不要用应用"判断时看到的东西。 */
    @GetMapping("/capabilities")
    public List<CapabilityView> capabilities() {
        return gateway.capabilities();
    }

    /** 某个能力下有哪些已发布应用。同一能力多候选是常态(井字棋与五子棋同属 game.play)。 */
    @GetMapping("/capabilities/{capabilityId}/applications")
    public List<ApplicationView> applicationsOfCapability(@PathVariable String capabilityId) {
        return gateway.applicationsFor(capabilityId);
    }

    @GetMapping("/applications")
    public List<ApplicationView> applications() {
        return gateway.applications();
    }

    /**
     * 某个应用的已发布动作。{@code agentHint} 一并返回 ——
     * 那是应用作者写给 LLM 的"怎么做", 也是策略文本离开数字人的落点。
     */
    @GetMapping("/applications/{applicationId}/actions")
    public List<ActionSpec> actions(@PathVariable String applicationId) {
        return gateway.actionsOf(applicationId);
    }

    /**
     * 资源读取。三种查询方式: 按 URI(单个)、按会话、按应用。
     *
     * <p>没有参数时返回空列表而不是全部 —— "把所有资源列出来"不是任何一个调用方需要的能力,
     * 而它一旦存在就会被用上。
     */
    @GetMapping("/resources")
    public ResponseEntity<List<ResourceView>> resources(
            @RequestParam(required = false) String uri,
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) String applicationId) {

        if (uri != null && !uri.isBlank()) {
            return gateway.read(uri)
                    .map(v -> ResponseEntity.ok(List.of(v)))
                    .orElseGet(() -> ResponseEntity.notFound().build());
        }
        // 列表路径也过一遍 gateway.read: agentHint 是资源模板上的声明, 列表与单读给出的
        // 视图必须是同一形状, 否则 Agent 会在两条读路径上看到两种世界。
        if (sessionId != null && !sessionId.isBlank()) {
            return ResponseEntity.ok(withHints(resources.bySession(sessionId)));
        }
        if (applicationId != null && !applicationId.isBlank()) {
            return ResponseEntity.ok(withHints(resources.byApplication(applicationId)));
        }
        return ResponseEntity.ok(List.of());
    }

    private List<ResourceView> withHints(List<ResourceView> views) {
        return views.stream()
                .map(v -> gateway.read(v.uri()).orElse(v))
                .toList();
    }
}
