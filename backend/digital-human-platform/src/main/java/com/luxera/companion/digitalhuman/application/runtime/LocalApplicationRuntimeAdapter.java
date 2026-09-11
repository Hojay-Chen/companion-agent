package com.luxera.companion.digitalhuman.application.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.contracts.application.ActionRequest;
import com.luxera.companion.contracts.application.ActionResponse;
import com.luxera.companion.contracts.application.ActionSpec;
import com.luxera.companion.contracts.application.ActionStatus;
import com.luxera.companion.contracts.application.ApplicationView;
import com.luxera.companion.contracts.application.CapabilityView;
import com.luxera.companion.contracts.application.InvocationContext;
import com.luxera.companion.contracts.application.ResourceView;
import com.luxera.companion.contracts.spi.ApplicationRuntimePort;
import com.luxera.companion.digitalhuman.application.spi.LocalApplicationProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * R2 过渡件: 进程内应用对 {@link ApplicationRuntimePort} 的实现。
 *
 * <p>它把若干 {@link LocalApplicationProvider} 聚合成一个 LAP 端口, 执行仍走今天的
 * {@link DefaultActionsRuntime}(权限决策 + 审计日志), 因此 <b>R2 是纯行为保持</b>:
 * 没有权限语义变化, 没有新的持久化, 也没有真实的幂等与 CAS。
 *
 * <p><b>R3 删除本类</b>: 应用平台独立成模块后, 该端口由 application-platform 的
 * {@code ApplicationGateway} 实现, 数字人侧只剩
 * {@code bootstrap-app} 注入的那一个 Bean。R7 的
 * {@code ApplicationRuntimeImplementationArchitectureTest} 会按类名断言它不存在,
 * 防止它被悄悄加回来。
 */
@Slf4j
@Service
public class LocalApplicationRuntimeAdapter implements ApplicationRuntimePort {

    private final DefaultActionsRuntime actions;
    private final List<LocalApplicationProvider> providers;
    private final ObjectMapper objectMapper;

    public LocalApplicationRuntimeAdapter(DefaultActionsRuntime actions,
                                          List<LocalApplicationProvider> providers,
                                          ObjectMapper objectMapper) {
        this.actions = actions;
        this.providers = List.copyOf(providers);
        this.objectMapper = objectMapper;
        log.info("[LocalApplicationRuntime] 已挂载 {} 个进程内应用: {}", providers.size(),
                providers.stream().map(LocalApplicationProvider::applicationId).toList());
    }

    @Override
    public List<CapabilityView> capabilities() {
        return providers.stream().map(LocalApplicationProvider::capability).distinct().toList();
    }

    @Override
    public List<ApplicationView> applicationsFor(String capabilityId) {
        if (capabilityId == null) return List.of();
        return providers.stream()
                .filter(p -> capabilityId.equals(p.capability().capabilityId()))
                .map(LocalApplicationProvider::application)
                .toList();
    }

    @Override
    public List<ActionSpec> actionsOf(String applicationId) {
        return providerOf(applicationId).map(LocalApplicationProvider::actions).orElse(List.of());
    }

    @Override
    public Optional<ResourceView> read(String resourceUri) {
        if (resourceUri == null) return Optional.empty();
        for (LocalApplicationProvider p : providers) {
            Optional<ResourceView> view = p.read(resourceUri);
            if (view.isPresent()) return view;
        }
        return Optional.empty();
    }

    @Override
    public List<ActionSpec> pendingActions(String resourceUri, InvocationContext ctx) {
        Optional<ResourceView> resource = read(resourceUri);
        if (resource.isEmpty()) return List.of();
        return providerOf(resource.get().applicationId())
                .map(p -> p.pendingActions(resource.get(), ctx))
                .orElse(List.of());
    }

    @Override
    public ActionResponse execute(ActionRequest request, InvocationContext ctx) {
        if (request == null || request.action() == null) {
            return ActionResponse.failure(ActionStatus.INVALID_ARGUMENT, "INVALID_ARGUMENT", "缺少 action");
        }
        LocalApplicationProvider provider = providerFor(request.action());
        if (provider == null) {
            return ActionResponse.failure(ActionStatus.NOT_FOUND, "UNKNOWN_ACTION",
                    "没有应用声明动作: " + request.action());
        }

        Map<String, Object> input = toMap(request.input());
        String idempotencyKey = deriveIdempotencyKey(provider, request, ctx);
        // 资源 URI 借 ActionContext.sessionId 传下去 —— 应用据此把自己那层的业务对象解析出来
        ActionRuntime.ActionContext actionCtx = ActionRuntime.ActionContext.of(
                provider.applicationId(), ctx.companionId(), ctx.userId(),
                request.target(), ctx.correlationId());

        ActionRuntime.ActionResult outcome =
                actions.execute(request.action(), input, idempotencyKey, actionCtx);

        if (!outcome.succeeded()) {
            return ActionResponse.failure(mapStatus(outcome.status()),
                    outcome.errorCode() == null ? "FAILED" : outcome.errorCode(),
                    outcome.errorMessage());
        }
        JsonNode result = objectMapper.valueToTree(outcome.result());
        return ActionResponse.success(result, read(request.target()).orElse(null));
    }

    // ─────────────────────────── 内部工具 ───────────────────────────

    /**
     * 进程内调用没有 HTTP 头可以带幂等键, 于是由 (principal, action, event) 确定性地派生一个。
     * 同一次因果事件重放 → 同一个键 → 可被幂等层短路。R4 起由 ApplicationGateway 用真实键接管。
     */
    private String deriveIdempotencyKey(LocalApplicationProvider provider, ActionRequest request,
                                        InvocationContext ctx) {
        ActionSpec spec = provider.actions().stream()
                .filter(a -> a.actionId().equals(request.action()))
                .findFirst().orElse(null);
        if (spec == null || spec.isRead()) {
            return null;   // READ 从不记录(否则第二次 game.state 会重放第一次的旧棋盘)
        }
        String seed = ctx.correlationId() != null ? ctx.correlationId() : request.target();
        return "inproc:" + ctx.principalType() + ":" + ctx.principalId() + ":" + request.action() + ":" + seed;
    }

    private Optional<LocalApplicationProvider> providerOf(String applicationId) {
        if (applicationId == null) return Optional.empty();
        return providers.stream().filter(p -> applicationId.equals(p.applicationId())).findFirst();
    }

    private LocalApplicationProvider providerFor(String actionId) {
        return providers.stream().filter(p -> p.owns(actionId)).findFirst().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(JsonNode input) {
        if (input == null || input.isNull() || !input.isObject()) return Map.of();
        return objectMapper.convertValue(input, Map.class);
    }

    private static ActionStatus mapStatus(ActionRuntime.ActionResult.Status status) {
        return switch (status) {
            case DENIED -> ActionStatus.DENIED;
            case REQUIRE_CONFIRMATION -> ActionStatus.REQUIRE_CONFIRMATION;
            default -> ActionStatus.FAILED;
        };
    }
}
