package com.luxera.companion.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.luxera.companion.contracts.application.ActionRequest;
import com.luxera.companion.contracts.application.ActionResponse;
import com.luxera.companion.contracts.application.ActionSpec;
import com.luxera.companion.contracts.application.InvocationContext;
import com.luxera.companion.contracts.application.ResourceView;
import com.luxera.companion.contracts.spi.ApplicationRuntimePort;
import com.luxera.companion.digitalhuman.actor.PersonActorRegistry;
import com.luxera.companion.digitalhuman.event.EventRouter;
import com.luxera.companion.digitalhuman.event.ExternalEvent;
import com.luxera.companion.digitalhuman.event.ExternalEventType;
import com.luxera.companion.digitalhuman.reality.RealityEventType;
import com.luxera.companion.digitalhuman.reality.RealityLedger;
import com.luxera.companion.llm.LlmRouter;
import com.luxera.companion.llm.StructuredRequest;
import com.luxera.companion.llm.StructuredResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LAP v1 §11: 数字人"看到应用里发生了什么"之后的通用反应链。
 *
 * <p>它取代了原先写在 {@code AgentRuntime} 里的 {@code onApplicationEvent} —— 那段代码
 * import 了 {@code TicTacToeApplicationAdapter.APP_CODE}、自己解析棋盘 JSON、自己拼幂等键、
 * 自己判断 {@code turn == "O"}。于是"再加一个游戏"必须改认知链。现在不是了:
 *
 * <ol>
 *   <li><b>过滤</b> —— 事件 payload 带 {@code resourceUri} 与 {@code agentTrigger};
 *       {@code agentTrigger} 由应用按自己的 manifest 算出, 数字人不认识 {@code "MOVE"} 这种字面量。</li>
 *   <li><b>读资源</b> —— {@code applicationRuntimePort.read(uri)}; 数字人从此不解析任何应用状态。</li>
 *   <li><b>问"现在能做什么"</b> —— {@code pendingActions(...)}; 空则停。轮到谁、局是否终了,
 *       由应用回答(见 {@code TicTacToeApplicationAdapter.pendingActions})。</li>
 *   <li><b>执行 + 记账</b> —— {@code execute(...)}; 成功后只写自己的账本
 *       ({@code APPLICATION_ACTION_EXECUTED})。应用说什么都不影响账本语义。</li>
 * </ol>
 *
 * <p><b>LLM 优先, 绝不降级到启发式</b>(用户明确要求): LLM 不可用或当前是 mock provider 时
 * 直接不行动 —— 没有启发式、没有随机、没有"随便挑第一个空格"。这条守卫放在第 3 步最前面,
 * 由 {@code AgentApplicationFlowTest} 的"零次 execute"断言守住。
 *
 * <p>整体跑在 {@code personActorRegistry.tell(personId, ...)} 的 mailbox 里: 原先它跑在
 * 用户 HTTP 请求线程上, 与聊天路径没有共享同一把 per-person 串行锁, 存在潜在竞态。
 */
@Slf4j
@Service
public class AgentApplicationFlow {

    private final ApplicationRuntimePort applicationRuntimePort;
    private final LlmRouter llmRouter;
    private final RealityLedger realityLedger;
    private final EventRouter eventRouter;
    private final PersonActorRegistry personActorRegistry;

    public AgentApplicationFlow(ApplicationRuntimePort applicationRuntimePort,
                                LlmRouter llmRouter,
                                RealityLedger realityLedger,
                                EventRouter eventRouter,
                                PersonActorRegistry personActorRegistry) {
        this.applicationRuntimePort = applicationRuntimePort;
        this.llmRouter = llmRouter;
        this.realityLedger = realityLedger;
        this.eventRouter = eventRouter;
        this.personActorRegistry = personActorRegistry;
    }

    /**
     * 用 subscribe 而非 register —— {@code register} 是覆盖语义, 会把别的消费者挤掉,
     * 而 {@code EventProcessingChainTest} / {@code OutboxRelayTest} 依赖那套语义。
     */
    @PostConstruct
    void registerRoutes() {
        eventRouter.subscribe(ExternalEventType.APPLICATION_EVENT, this::onApplicationEvent);
        log.info("[AgentApplicationFlow] 已订阅 APPLICATION_EVENT");
    }

    void onApplicationEvent(ExternalEvent event) {
        if (event == null || !ExternalEventType.APPLICATION_EVENT.equals(event.type())) return;
        String companionId = event.personId();
        String resourceUri = event.str("resourceUri");
        // agentTrigger 由应用算出(它认得自己的事件类型与规则), 数字人只认这个布尔
        if (companionId == null || resourceUri == null) return;
        if (!Boolean.TRUE.equals(event.get("agentTrigger"))) return;

        final String userId = event.str("userId");
        final String correlationId = event.eventId();
        personActorRegistry.tell(companionId, () -> react(companionId, userId, resourceUri, correlationId));
    }

    /** 第 2–4 步。跑在该 Person 的 mailbox 线程上(包内可见, 便于确定性单测)。 */
    void react(String companionId, String userId, String resourceUri, String correlationId) {
        try {
            ResourceView resource = applicationRuntimePort.read(resourceUri).orElse(null);
            if (resource == null) {
                log.debug("[AgentApplicationFlow] 资源不存在, 不行动: {}", resourceUri);
                return;
            }
            InvocationContext ctx = InvocationContext.agent(companionId, userId, correlationId);

            List<ActionSpec> pending = applicationRuntimePort.pendingActions(resourceUri, ctx);
            if (pending.isEmpty()) return;   // 轮到别人 / 已终局 / 无事可做

            // ── LLM 优先, 绝不降级到启发式 ──
            if (!llmRouter.available() || llmRouter.isMockActive()) {
                log.warn("[AgentApplicationFlow] LLM 不可用, 数字人不行动: resource={}", resourceUri);
                return;
            }

            ActionSpec chosen = pending.get(0);
            JsonNode input = decide(chosen, resource, companionId);
            if (input == null) {
                log.debug("[AgentApplicationFlow] LLM 选择不行动: action={}", chosen.actionId());
                return;
            }

            ActionResponse response = applicationRuntimePort.execute(
                    new ActionRequest(chosen.actionId(), resourceUri, input, resource.version()), ctx);
            if (!response.isSuccess()) {
                log.warn("[AgentApplicationFlow] 动作被拒: action={}, resource={}, status={}, err={}",
                        chosen.actionId(), resourceUri, response.status(),
                        response.error() == null ? null : response.error().message());
                return;
            }
            log.info("[AgentApplicationFlow] 数字人执行动作: action={}, resource={}",
                    chosen.actionId(), resourceUri);
            appendReality(companionId, resource, chosen, input, correlationId);
        } catch (Exception e) {
            log.warn("[AgentApplicationFlow] 处理应用事件失败 resource={}: {}", resourceUri, e.getMessage());
        }
    }

    /**
     * 问 LLM 该怎么走。返回 null 表示"不行动"。
     * 提示词完全由应用给的 {@link ActionSpec} 拼成 —— 数字人不知道自己在下棋还是在别的什么。
     */
    private JsonNode decide(ActionSpec action, ResourceView resource, String companionId) {
        try {
            StructuredResult result = llmRouter.structured(StructuredRequest.builder()
                    .system(renderSystem(action, resource))
                    .user("当前状态:\n" + pretty(resource.state()))
                    .task("application-action-selection")
                    .schemaHint("{\"input\":{\"position\":4},\"reason\":\"占据中心\"}")
                    .temperature(0.3)
                    // LlmCallService.record 在 companionId 为空时静默跳过 —— 不设它这条调用就不落库
                    .metadata(Map.of("companionId", companionId, "purpose", "application"))
                    .build());
            JsonNode json = result.getJson();
            if (json == null) return null;
            JsonNode input = json.path("input");
            if (input.isMissingNode() || input.isNull()) return null;   // 明确表示不行动
            return input;
        } catch (Exception e) {
            log.warn("[AgentApplicationFlow] 动作选择失败 action={}: {}", action.actionId(), e.getMessage());
            return null;
        }
    }

    private static String renderSystem(ActionSpec action, ResourceView resource) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个正在与真人互动的数字人。此刻有一个应用动作可以由你执行。\n\n");
        sb.append("应用: ").append(action.applicationId()).append('\n');
        sb.append("动作: ").append(action.actionId()).append(" —— ").append(action.description()).append('\n');
        if (action.agentHint() != null && !action.agentHint().isBlank()) {
            sb.append("怎么做:\n").append(action.agentHint()).append('\n');
        }
        if (action.inputSchema() != null) {
            sb.append("input 必须满足这个 JSON Schema:\n").append(action.inputSchema().toPrettyString()).append('\n');
        }
        if (resource.agentHint() != null && !resource.agentHint().isBlank()
                && !resource.agentHint().equals(action.agentHint())) {
            sb.append("状态怎么读:\n").append(resource.agentHint()).append('\n');
        }
        sb.append("\n只输出 JSON: {\"input\": <满足 schema 的对象>, \"reason\": \"<一句话理由>\"}。\n");
        sb.append("如果你判断现在不该行动, 输出 {\"input\": null, \"reason\": \"<理由>\"}。\n");
        sb.append("不要解释算法, 不要输出 JSON 以外的任何内容。");
        return sb.toString();
    }

    private static String pretty(JsonNode state) {
        return state == null ? "(无状态)" : state.toPrettyString();
    }

    /** 账本语义只在数字人侧: 应用不知道自己被记了什么。 */
    private void appendReality(String companionId, ResourceView resource, ActionSpec action,
                               JsonNode input, String correlationId) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("applicationId", resource.applicationId());
            payload.put("resource", resource.uri());
            payload.put("action", action.actionId());
            payload.put("input", input);
            realityLedger.append(companionId, RealityEventType.APPLICATION_ACTION_EXECUTED,
                    payload, clip(correlationId), null);
        } catch (Exception e) {
            log.warn("[AgentApplicationFlow] 写现实账本失败: {}", e.getMessage());
        }
    }

    /**
     * reality_event.correlation_id 是 varchar(64), 而事件 id 里含完整 resource id 时会超长 ——
     * 超长会让整条账本写入失败(只剩一条 WARN), 于是"数字人做过什么"就丢了。
     * 相关性 id 本就是不透明串, 截断比丢账本划算。
     */
    private static String clip(String correlationId) {
        if (correlationId == null || correlationId.length() <= 64) return correlationId;
        return correlationId.substring(0, 64);
    }
}
