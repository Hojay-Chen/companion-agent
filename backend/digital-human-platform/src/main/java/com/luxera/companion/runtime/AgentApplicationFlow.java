package com.luxera.companion.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.luxera.companion.contracts.application.ActionRequest;
import com.luxera.companion.contracts.application.ActionResponse;
import com.luxera.companion.contracts.application.ActionSpec;
import com.luxera.companion.contracts.application.ApplicationView;
import com.luxera.companion.contracts.application.CapabilityView;
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
import com.luxera.companion.persona.Persona;
import com.luxera.companion.persona.PersonaService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * LAP v1 — 数字人与应用平台之间的<b>唯一一条认知链</b>。两个方向:
 *
 * <h2>反应(reactive): 应用里发生了什么 → 我该做什么</h2>
 * <p>它取代了原先写在 {@code AgentRuntime} 里的 {@code onApplicationEvent} —— 那段代码把某个
 * 具体游戏的应用常量 import 进认知链、自己解析棋盘 JSON、自己拼幂等键、自己判断
 * {@code turn == "O"}。于是"再加一个游戏"必须改认知链。现在不是了:
 *
 * <ol>
 *   <li><b>过滤</b> —— 事件 payload 带 {@code resourceUri} 与 {@code agentTrigger};
 *       {@code agentTrigger} 由应用按自己的 manifest 算出, 数字人不认识 {@code "MOVE"} 这种字面量。</li>
 *   <li><b>读资源</b> —— {@code applicationRuntimePort.read(uri)}; 数字人从此不解析任何应用状态。</li>
 *   <li><b>问"现在能做什么"</b> —— {@code pendingActions(...)}; 空则停。轮到谁、局是否终了,
 *       由应用回答。</li>
 *   <li><b>选一个 + 执行 + 记账</b> —— 选择的依据是应用写在 manifest 里的
 *       {@code agentHint}(每个应用的策略长在它自己身上), 数字人只负责把它读给 LLM 听。</li>
 * </ol>
 *
 * <h2>主动(initiative): 用户说了一句话 → 这该动用哪个应用</h2>
 * <p>见 {@link #route(String, String)}: 意图 → 能力 → 应用, 逐级收窄。数字人认识的是
 * {@link CapabilityView} 这份<b>能力目录</b>, 不是任何具体应用 —— 这就是"50000 个 action
 * 不塞给 LLM"的落点: 先在一张几十行的目录里选一行, 再谈别的。
 *
 * <h2>LLM 优先, 绝不降级到启发式</h2>
 * <p>(用户明确要求) LLM 不可用或当前是 mock provider 时直接不行动 —— 没有启发式、没有随机、
 * 没有"随便挑第一个空格"。反应路径上这条守卫是显式的 {@code if}; 主动路径上它体现为
 * mock 网关对未知 task 回空对象 ⇒ {@link #route} 得到"没有任何能力适用"。
 * {@code AgentApplicationFlowTest} 的"零次 execute"断言是这条性质的保险丝。
 *
 * <p>反应部分整体跑在 {@code personActorRegistry.tell(personId, ...)} 的 mailbox 里:
 * 原先它跑在用户 HTTP 请求线程上, 与聊天路径没有共享同一把 per-person 串行锁。
 */
@Slf4j
@Service
public class AgentApplicationFlow {

    private final ApplicationRuntimePort applicationRuntimePort;
    private final LlmRouter llmRouter;
    private final RealityLedger realityLedger;
    private final EventRouter eventRouter;
    private final PersonActorRegistry personActorRegistry;
    private final PersonaService personaService;

    /**
     * 能力选择的置信度门槛: 低于它 = "这句话不需要动用任何应用"。
     *
     * <p>这个数字就是设计文档那句「大多数日常聊天不需要任何应用」的可测试版本 ——
     * 调低会让闲聊被当成应用请求, 调高会让明确的请求被漏掉, 所以它是一个配置项而不是常量。
     */
    private final double capabilityThreshold;

    public AgentApplicationFlow(ApplicationRuntimePort applicationRuntimePort,
                                LlmRouter llmRouter,
                                RealityLedger realityLedger,
                                EventRouter eventRouter,
                                PersonActorRegistry personActorRegistry,
                                PersonaService personaService,
                                @Value("${app.lap.capability-threshold:0.6}") double capabilityThreshold) {
        this.applicationRuntimePort = applicationRuntimePort;
        this.llmRouter = llmRouter;
        this.realityLedger = realityLedger;
        this.eventRouter = eventRouter;
        this.personActorRegistry = personActorRegistry;
        this.personaService = personaService;
        this.capabilityThreshold = capabilityThreshold;
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

    // ═══════════════════════════ 反应: 资源变了 → 我该做什么 ═══════════════════════════

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

            Decision decision = decide(pending, resource, companionId);
            if (decision == null) {
                log.debug("[AgentApplicationFlow] LLM 选择不行动: resource={}", resourceUri);
                return;
            }

            ActionResponse response = applicationRuntimePort.execute(
                    new ActionRequest(decision.action().actionId(), resourceUri,
                            decision.input(), resource.version()), ctx);
            if (!response.isSuccess()) {
                log.warn("[AgentApplicationFlow] 动作被拒: action={}, resource={}, status={}, err={}",
                        decision.action().actionId(), resourceUri, response.status(),
                        response.error() == null ? null : response.error().message());
                return;
            }
            log.info("[AgentApplicationFlow] 数字人执行动作: action={}, resource={}",
                    decision.action().actionId(), resourceUri);
            appendReality(companionId, resource, decision.action(), decision.input(), correlationId);
        } catch (Exception e) {
            log.warn("[AgentApplicationFlow] 处理应用事件失败 resource={}: {}", resourceUri, e.getMessage());
        }
    }

    /** LLM 的答复: 做哪一件事, 以及填什么 input。 */
    private record Decision(ActionSpec action, JsonNode input) {}

    /**
     * 问 LLM 该怎么走。返回 null 表示"不行动"。
     *
     * <p>提示词完全由应用给的 {@link ActionSpec} 拼成 —— 数字人不知道自己在下棋还是在别的什么。
     * 候选动作是<b>全部</b> pending 而不是第一个: {@code pending.get(0)} 与"随便挑一个"只是
     * 换了件衣服, 而应用完全可以同时允许"落子"和"认输"。
     */
    private Decision decide(List<ActionSpec> pending, ResourceView resource, String companionId) {
        try {
            StructuredResult result = llmRouter.structured(StructuredRequest.builder()
                    .system(renderActionSystem(pending, resource, companionId))
                    .user("当前状态:\n" + pretty(resource.state()))
                    .task("application-action-selection")
                    // 例子里的动作 id 故意写成一个占位符: 写死一个真实动作名会让模型倾向选它
                    .schemaHint("{\"actionId\":\"<候选里的某个动作 id>\",\"input\":{},\"reason\":\"…\"}")
                    .temperature(0.3)
                    // LlmCallService.record 在 companionId 为空时静默跳过 —— 不设它这条调用就不落库
                    .metadata(meta(companionId, "application"))
                    .build());
            JsonNode json = result.getJson();
            if (json == null) return null;
            JsonNode input = json.path("input");
            if (input.isMissingNode() || input.isNull()) return null;   // 明确表示不行动

            return new Decision(pickAction(pending, json.path("actionId").asText(null)), input);
        } catch (Exception e) {
            log.warn("[AgentApplicationFlow] 动作选择失败 resource={}: {}", resource.uri(), e.getMessage());
            return null;
        }
    }

    /**
     * LLM 说它要执行哪个动作 —— 但它说的是不是真的, 由这里说了算。
     *
     * <p>三种情况:
     * <ul>
     *   <li>它给了一个候选里的 actionId → 用它。</li>
     *   <li>它没给 actionId, 而候选只有一个 → 用它。这时"选哪个"本来就没有信息量,
     *       LLM 的活儿是填 input。</li>
     *   <li>它给了个不在候选里的名字, 或者候选不止一个却没说是哪个 → <b>不行动</b>。
     *       编造出来的动作不能顺手执行, 含糊的回答也不能替它补一个 —— 补的那个就是启发式。</li>
     * </ul>
     */
    private ActionSpec pickAction(List<ActionSpec> pending, String actionId) {
        if (actionId != null && !actionId.isBlank()) {
            for (ActionSpec spec : pending) {
                if (spec.actionId().equals(actionId)) return spec;
            }
            log.warn("[AgentApplicationFlow] LLM 选了一个不在候选里的动作, 不行动: {}", actionId);
            return null;
        }
        if (pending.size() == 1) return pending.get(0);
        log.warn("[AgentApplicationFlow] 有 {} 个候选动作但 LLM 没说选哪个, 不行动", pending.size());
        return null;
    }

    // ═══════════════════════════ 主动: 用户说了一句话 → 用哪个应用 ═══════════════════════════

    /** 意图路由的结果: 该用哪个能力下的哪个应用。 */
    public record Intent(String capabilityId, String applicationId, double confidence, String reason) {}

    /** 能力选择的结果。{@code confidence} 已过门槛 —— 没过门槛的在这里不存在。 */
    public record CapabilityChoice(String capabilityId, String title, double confidence, String reason) {}

    /**
     * 用户的一句话 → (能力, 应用)。没有任何应用该被牵扯进来时返回空。
     *
     * <p>这是"意图 → 能力 → 应用"这条收窄路径的入口, 也是数字人唯一一次<b>主动</b>决定要用
     * 一个应用。它取代了原先那种"每个功能各自认识自己的那个应用"的写法。
     */
    public Optional<Intent> route(String companionId, String userText) {
        Optional<CapabilityChoice> capability = selectCapability(companionId, userText);
        if (capability.isEmpty()) return Optional.empty();
        Optional<String> application = selectApplication(
                companionId, capability.get().capabilityId(), userText);
        if (application.isEmpty()) return Optional.empty();
        CapabilityChoice c = capability.get();
        return Optional.of(new Intent(c.capabilityId(), application.get(), c.confidence(), c.reason()));
    }

    /**
     * 第一级: 意图 → 能力。给 LLM 看的是平台的能力目录(几十行), 不是 action 列表。
     *
     * <p>LLM 可以回答错误的东西, 但不可以回答不存在的东西: 它给的能力若不在目录里,
     * 一律当没选(编造的能力没有对应应用, 硬走下去只会在下一级得到空)。
     */
    public Optional<CapabilityChoice> selectCapability(String companionId, String userText) {
        if (userText == null || userText.isBlank()) return Optional.empty();
        List<CapabilityView> catalogue;
        try {
            catalogue = applicationRuntimePort.capabilities();
        } catch (Exception e) {
            log.warn("[AgentApplicationFlow] 读能力目录失败: {}", e.getMessage());
            return Optional.empty();
        }
        if (catalogue == null || catalogue.isEmpty()) return Optional.empty();

        try {
            StructuredResult result = llmRouter.structured(StructuredRequest.builder()
                    .system(renderCapabilitySystem(catalogue))
                    .user(userText)
                    .task("application-capability-selection")
                    // 同上: 例子里的能力 id 用占位符, 不写死某一个 —— 那会让模型倾向选它
                    .schemaHint("{\"capability\":\"<目录里的某个能力 id>\",\"confidence\":0.8,\"reason\":\"…\"}")
                    .temperature(0.2)
                    // stableHash 是这份目录的指纹: 事后翻 llm_calls 能知道模型当时看到的是哪一版目录
                    .metadata(meta(companionId, "application-capability", "stableHash", hash(catalogue)))
                    .build());
            JsonNode json = result.getJson();
            if (json == null) return Optional.empty();

            String capabilityId = json.path("capability").asText(null);
            if (capabilityId == null || capabilityId.isBlank()) return Optional.empty();

            CapabilityView matched = null;
            for (CapabilityView view : catalogue) {
                if (view.capabilityId().equals(capabilityId)) {
                    matched = view;
                    break;
                }
            }
            if (matched == null) {
                log.warn("[AgentApplicationFlow] LLM 选了一个不在能力目录里的能力, 当没选: {}", capabilityId);
                return Optional.empty();
            }

            double confidence = json.path("confidence").asDouble(0);
            if (confidence < capabilityThreshold) {
                log.info("[AgentApplicationFlow] 能力选择置信度不足({} < {}), 不动用应用: capability={}",
                        confidence, capabilityThreshold, capabilityId);
                return Optional.empty();
            }
            return Optional.of(new CapabilityChoice(matched.capabilityId(), matched.title(), confidence,
                    json.path("reason").asText("")));
        } catch (Exception e) {
            log.warn("[AgentApplicationFlow] 能力选择失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 第二级: 能力 → 具体应用。
     *
     * <p>只有一个候选时<b>不问 LLM</b> —— 没有第二个选项的"选择"只是在花钱听模型复述一遍输入。
     * 多个候选时才问, 而且它选出来的必须在候选里: 让模型挑一个并不存在的应用, 后面整条链都
     * 会在权限校验那里撞墙, 不如在这里就判它不合格。
     */
    public Optional<String> selectApplication(String companionId, String capabilityId, String userText) {
        List<ApplicationView> candidates;
        try {
            candidates = applicationRuntimePort.applicationsFor(capabilityId);
        } catch (Exception e) {
            log.warn("[AgentApplicationFlow] 读能力 {} 的候选应用失败: {}", capabilityId, e.getMessage());
            return Optional.empty();
        }
        if (candidates == null || candidates.isEmpty()) return Optional.empty();
        if (candidates.size() == 1) return Optional.of(candidates.get(0).applicationId());

        try {
            StructuredResult result = llmRouter.structured(StructuredRequest.builder()
                    .system(renderApplicationSystem(capabilityId, candidates))
                    .user(userText == null ? "" : userText)
                    .task("application-selection")
                    .schemaHint("{\"applicationId\":\"" + candidates.get(0).applicationId() + "\",\"reason\":\"…\"}")
                    .temperature(0.2)
                    .metadata(meta(companionId, "application-selection", "candidates", candidates.size()))
                    .build());
            JsonNode json = result.getJson();
            String picked = json == null ? null : json.path("applicationId").asText(null);
            for (ApplicationView view : candidates) {
                if (view.applicationId().equals(picked)) return Optional.of(picked);
            }
            log.warn("[AgentApplicationFlow] LLM 选了一个不在候选里的应用, 不行动: picked={}, candidates={}",
                    picked, candidates.stream().map(ApplicationView::applicationId).toList());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("[AgentApplicationFlow] 应用选择失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    // ═══════════════════════════ 提示词 ═══════════════════════════

    /**
     * 动作选择的 system。
     *
     * <p>「像真人一样下棋: 会赢就赢, 但不要解释算法」这类规则是<b>通用</b>的 —— 它说的是
     * "怎么像一个真人那样行动", 对井字棋、五子棋、以后的任何应用都成立。<b>具体打法</b>
     * (「能三连就三连」)不在这里, 它在应用 manifest 的 {@code agentHint} 里, 由
     * {@link ActionSpec#agentHint()} 带进来。这两者的分界就是这一节的要点: 通用行为准则归数字人,
     * 领域知识归应用。
     */
    private String renderActionSystem(List<ActionSpec> pending, ResourceView resource, String companionId) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个正在与真人互动的数字人。此刻有一个应用里的事情轮到你做。\n\n");
        String persona = personaLine(companionId);
        if (persona != null) {
            sb.append("你是谁: ").append(persona).append('\n');
        }
        sb.append("应用: ").append(applicationLabel(resource.applicationId(), pending)).append('\n');
        sb.append("你正在操作的东西: ").append(resource.uri()).append('\n');
        if (resource.agentHint() != null && !resource.agentHint().isBlank()) {
            sb.append("这个东西怎么读:\n").append(resource.agentHint()).append('\n');
        }
        sb.append("\n你现在可以做的事(只能从里面挑一个):\n");
        for (ActionSpec action : pending) {
            sb.append("- ").append(action.actionId()).append(" —— ").append(action.description()).append('\n');
            if (action.agentHint() != null && !action.agentHint().isBlank()) {
                sb.append("  怎么打:\n");
                for (String line : action.agentHint().split("\n")) {
                    sb.append("    ").append(line).append('\n');
                }
            }
            if (action.inputSchema() != null) {
                sb.append("  这个动作的 input 必须满足:\n")
                        .append(indent(action.inputSchema().toPrettyString())).append('\n');
            }
        }
        sb.append("\n规则:\n");
        sb.append("- 只在<b>轮到你</b>的时候行动 —— 候选里没有你的位置就说明不该你动。\n");
        sb.append("- input 必须满足上面那个 JSON Schema, 字段名一个字都不能改。\n");
        sb.append("- 像真人一样下棋: 会赢就赢, 但不要解释算法, 也不要在 input 里夹带解说。\n");
        sb.append("- 你不知道规则细节时, 以「怎么打」那段为准。\n");
        sb.append("\n只输出 JSON: {\"actionId\": \"<上面某一个动作 id>\", \"input\": <满足 schema 的对象>, "
                + "\"reason\": \"<一句话理由>\"}。\n");
        sb.append("如果你判断现在不该行动, 输出 {\"actionId\": null, \"input\": null, \"reason\": \"<理由>\"}。\n");
        sb.append("不要输出 JSON 以外的任何内容。");
        return sb.toString();
    }

    /** 能力目录 —— 这是 LLM 在"要不要用应用"这一层能看到的全部东西。 */
    private static String renderCapabilitySystem(List<CapabilityView> catalogue) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个数字人。用户在跟你说话。你所在的平台上有一些应用, 装上之后可以帮用户做事。\n\n");
        sb.append("你现在能想到的能力只有下面这些(这是完整目录):\n");
        for (CapabilityView view : catalogue) {
            sb.append("- ").append(view.capabilityId()).append(" —— ").append(view.title());
            if (view.description() != null && !view.description().isBlank()) {
                sb.append(": ").append(view.description());
            }
            if (view.category() != null && !view.category().isBlank()) {
                sb.append("(分类: ").append(view.category()).append(')');
            }
            sb.append('\n');
        }
        sb.append("\n规则:\n");
        sb.append("- 只能从上面这份目录里选, 不要编造能力。\n");
        sb.append("- <b>大多数日常聊天不需要任何应用</b> —— 闲聊、倾诉、提问、说情绪, 都不是要用一个应用。\n");
        sb.append("- 只有用户明确想让某件事被真的做掉(而不是想聊它), 才选一个能力。\n");
        sb.append("- 不确定就不要选。选错会让数字人去做一件用户没要的事, 比不做更糟。\n");
        sb.append("- confidence 是你对自己判断的信心, 0 到 1 之间的小数。\n");
        sb.append("\n只输出 JSON: {\"capability\": \"<能力 id>\", \"confidence\": 0.8, \"reason\": \"<一句话>\"}。\n");
        sb.append("不需要任何应用时输出 {\"capability\": null, \"confidence\": 0, \"reason\": \"<一句话>\"}。\n");
        sb.append("不要输出 JSON 以外的任何内容。");
        return sb.toString();
    }

    /** 应用候选 —— 只有同一能力下有多个应用时才会渲染到这里。 */
    private static String renderApplicationSystem(String capabilityId, List<ApplicationView> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户想做的事属于「").append(capabilityId).append("」, 平台上有这几个应用都能做:\n");
        for (ApplicationView view : candidates) {
            sb.append("- ").append(view.applicationId()).append(" —— ").append(view.name());
            if (view.description() != null && !view.description().isBlank()) {
                sb.append(": ").append(view.description());
            }
            sb.append('\n');
        }
        sb.append("\n规则:\n");
        sb.append("- 只能从上面这几个里选, 不要编造应用 id。\n");
        sb.append("- 按用户这句话更贴合哪一个来选; 看不出区别就选第一个。\n");
        sb.append("\n只输出 JSON: {\"applicationId\": \"<上面的某个 id>\", \"reason\": \"<一句话>\"}。\n");
        sb.append("不要输出 JSON 以外的任何内容。");
        return sb.toString();
    }

    /** 应用名与版本 —— 只是给提示词一点上下文, 拿不到就退回 id。 */
    private String applicationLabel(String applicationId, List<ActionSpec> pending) {
        if (applicationId == null) return "(未知应用)";
        try {
            String capabilityId = pending.get(0).capabilityId();
            if (capabilityId == null) return applicationId;
            for (ApplicationView view : applicationRuntimePort.applicationsFor(capabilityId)) {
                if (applicationId.equals(view.applicationId())) {
                    return view.name() + " v" + view.version();
                }
            }
        } catch (Exception e) {
            log.debug("[AgentApplicationFlow] 读应用名失败: {}", e.getMessage());
        }
        return applicationId;
    }

    /** 人格只取名字与一句性格概述 —— 决策要的是"这个人会怎么下", 不是完整人设。 */
    private String personaLine(String companionId) {
        try {
            Persona persona = personaService.getActive(companionId);
            if (persona == null) return null;
            String name = persona.getIdentity() == null ? null : persona.getIdentity().getName();
            String summary = persona.getPersonality() == null ? null : persona.getPersonality().getSummary();
            if (name == null && summary == null) return null;
            return ((name == null ? "" : name + "。") + (summary == null ? "" : summary)).trim();
        } catch (Exception e) {
            log.debug("[AgentApplicationFlow] 读人格失败: {}", e.getMessage());
            return null;
        }
    }

    private static String pretty(JsonNode state) {
        return state == null ? "(无状态)" : state.toPrettyString();
    }

    private static String indent(String text) {
        return "    " + text.replace("\n", "\n    ");
    }

    // ═══════════════════════════ 记账 ═══════════════════════════

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

    // ═══════════════════════════ 小工具 ═══════════════════════════

    /** {@code Map.of} 遇到 null value 直接 NPE —— 而 companionId 允许为空, 所以自己拼。 */
    private static Map<String, String> meta(String companionId, String purpose) {
        Map<String, String> map = new LinkedHashMap<>();
        if (companionId != null) map.put("companionId", companionId);
        map.put("purpose", purpose);
        return map;
    }

    private static Map<String, String> meta(String companionId, String purpose, String key, Object value) {
        Map<String, String> map = meta(companionId, purpose);
        if (value != null) map.put(key, String.valueOf(value));
        return map;
    }

    /**
     * 能力目录的指纹。目录变了指纹就变 —— 事后翻 {@code llm_calls} 能知道模型当时看的是什么。
     *
     * <p>先按 id 排序再拼: 平台给出的顺序不必是稳定的, 而"目录没变、指纹却变了"会让这个字段
     * 失去全部意义。
     */
    static String hash(List<CapabilityView> catalogue) {
        List<CapabilityView> sorted = new ArrayList<>(catalogue);
        sorted.sort((a, b) -> a.capabilityId().compareTo(b.capabilityId()));
        StringBuilder sb = new StringBuilder();
        for (CapabilityView view : sorted) {
            sb.append(view.capabilityId()).append('\n')
                    .append(view.title()).append('\n')
                    .append(view.description()).append('\n')
                    .append(view.category()).append('\n');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            return "unavailable";
        }
    }
}
