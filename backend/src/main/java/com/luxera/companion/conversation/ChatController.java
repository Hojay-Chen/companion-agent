package com.luxera.companion.conversation;

import com.luxera.companion.agent.CompanionRuntime;
import com.luxera.companion.agent.CompanionSchedule;
import com.luxera.companion.agent.PerceptionEngine;
import com.luxera.companion.agent.WorkingMemory;
import com.luxera.companion.appraisal.AppraisalService;
import com.luxera.companion.attention.AttentionService;
import com.luxera.companion.config.CurrentUser;
import com.luxera.companion.event.CompanionEventBus;
import com.luxera.companion.event.CompanionEventType;
import com.luxera.companion.interaction.InteractionAction;
import com.luxera.companion.interaction.InteractionDecision;
import com.luxera.companion.interaction.InteractionPolicyEngine;
import com.luxera.companion.interaction.ResponseLatencyEngine;
import com.luxera.companion.persona.CompanionService;
import com.luxera.companion.relationship.Relationship;
import com.luxera.companion.relationship.RelationshipService;
import com.luxera.companion.runtime.agent.expression.ExpressionAgent;
import com.luxera.companion.runtime.agent.expression.ExpressionContext;
import com.luxera.companion.runtime.agent.expression.ExpressionResult;
import com.luxera.companion.runtime.pipeline.MessageDeliveryService;
import com.luxera.companion.runtime.pipeline.MessageLifecycle;
import com.luxera.companion.runtime.pipeline.V5MessagePipeline;
import com.luxera.companion.state.AgentState;
import com.luxera.companion.state.AgentStateService;
import com.luxera.companion.state.AvailabilityService;
import com.luxera.companion.state.CompanionAvailability;
import com.luxera.companion.usermodel.UserChatStyleService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@RestController
@RequestMapping("/api/companions/{companionId}/conversations")
public class ChatController {

    /** ResponsePlan 多段分隔符(设计文档 V3 §十九): 她也可以连发, 低频) */
    private static final String SPLIT = "<split>";

    private final ConversationService conversationService;
    private final CompanionService companionService;
    private final CompanionRuntime runtime;
    private final PerceptionEngine perceptionEngine;
    private final WorkingMemory workingMemory;
    private final SessionManager sessionManager;
    private final InteractionPolicyEngine interactionPolicy;
    private final ResponseLatencyEngine latencyEngine;
    private final AgentStateService agentStateService;
    private final AvailabilityService availabilityService;
    private final RelationshipService relationshipService;
    private final UserChatStyleService userChatStyleService;
    private final CompanionEventBus eventBus;
    private final CompanionSchedule schedule;
    private final CurrentUser currentUser;
    private final TaskExecutor taskExecutor;
    /** V5: 消息流水线 / 表达规划 / 消息生命周期 */
    private final V5MessagePipeline messagePipeline;
    private final ExpressionAgent expressionAgent;
    private final MessageDeliveryService deliveryService;
    /** V6: 会话线程(话题状态) */
    private final ConversationThreadService threadService;
    /** V6: 行为模式学习 */
    private final com.luxera.companion.behavior.BehaviorLearningService behaviorLearningService;
    /** 每会话一个锁, 串行化同会话的消息处理(消息归并 + 生成) */
    private final java.util.concurrent.ConcurrentHashMap<String, ReentrantLock> conversationLocks = new java.util.concurrent.ConcurrentHashMap<>();

    public ChatController(ConversationService conversationService, CompanionService companionService,
                          CompanionRuntime runtime, PerceptionEngine perceptionEngine, WorkingMemory workingMemory,
                          SessionManager sessionManager, InteractionPolicyEngine interactionPolicy,
                          ResponseLatencyEngine latencyEngine, AgentStateService agentStateService,
                          AvailabilityService availabilityService, RelationshipService relationshipService,
                          UserChatStyleService userChatStyleService,
                                                    CompanionEventBus eventBus, CompanionSchedule schedule,
                          CurrentUser currentUser, TaskExecutor taskExecutor,
                          V5MessagePipeline messagePipeline, ExpressionAgent expressionAgent,
                          MessageDeliveryService deliveryService, ConversationThreadService threadService,
                          com.luxera.companion.behavior.BehaviorLearningService behaviorLearningService) {
        this.conversationService = conversationService;
        this.companionService = companionService;
        this.runtime = runtime;
        this.perceptionEngine = perceptionEngine;
        this.workingMemory = workingMemory;
        this.sessionManager = sessionManager;
        this.interactionPolicy = interactionPolicy;
        this.latencyEngine = latencyEngine;
        this.agentStateService = agentStateService;
        this.availabilityService = availabilityService;
        this.relationshipService = relationshipService;
        this.userChatStyleService = userChatStyleService;
        this.eventBus = eventBus;
        this.schedule = schedule;
        this.currentUser = currentUser;
        this.taskExecutor = taskExecutor;
        this.messagePipeline = messagePipeline;
        this.expressionAgent = expressionAgent;
        this.deliveryService = deliveryService;
        this.threadService = threadService;
        this.behaviorLearningService = behaviorLearningService;
    }

    @GetMapping
    public List<Conversation> list(@PathVariable String companionId) {
        String userId = currentUser.requireUserId();
        companionService.requireOwned(userId, companionId);
        return conversationService.list(userId, companionId);
    }

    /** 获取会话列表(无会话时自动创建带问候语的初始会话) */
    @PostMapping("/first")
    public Conversation first(@PathVariable String companionId) {
        String userId = currentUser.requireUserId();
        var companion = companionService.requireOwned(userId, companionId);
        return conversationService.getOrCreateGreeting(userId, companionId, companion);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Conversation create(@PathVariable String companionId, @RequestBody(required = false) CreateRequest req) {
        String userId = currentUser.requireUserId();
        companionService.requireOwned(userId, companionId);
        return conversationService.create(userId, companionId, req != null ? req.getTitle() : null);
    }

    @GetMapping("/{conversationId}/messages")
    public List<Message> messages(@PathVariable String companionId, @PathVariable String conversationId) {
        String userId = currentUser.requireUserId();
        companionService.requireOwned(userId, companionId);
        conversationService.requireOwned(userId, conversationId);
        return conversationService.messages(conversationId);
    }

    /**
     * 流式聊天(SSE, V3 Interaction Runtime):
     * 批量消息(连发归并) → decide(要不要回/投入多少) → typing(仅值得) → latency → token* → done
     * 支持单条 `{content}` 与批量 `{messages:[{content}]}`, 一次请求至多一次回复。
     */
    @PostMapping(value = "/{conversationId}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@PathVariable String companionId, @PathVariable String conversationId,
                           @RequestBody ChatRequest req) {
        String userId = currentUser.requireUserId();
        companionService.requireOwned(userId, companionId);
        var conv = conversationService.requireOwned(userId, conversationId);
        if (!conv.getCompanionId().equals(companionId)) {
            throw new IllegalArgumentException("会话与伴侣不匹配");
        }
        List<String> contents = resolveContents(req);
        if (contents.isEmpty()) {
            throw new IllegalArgumentException("消息不能为空");
        }

        SseEmitter emitter = new SseEmitter(300_000L);
        taskExecutor.execute(() -> streamChat(emitter, userId, companionId, conversationId, contents));
        return emitter;
    }

    /** 解析批量消息: messages 数组优先, 否则单条 content */
    private List<String> resolveContents(ChatRequest req) {
        List<String> out = new ArrayList<>();
        if (req == null) return out;
        if (req.getMessages() != null && !req.getMessages().isEmpty()) {
            for (ChatMessageItem item : req.getMessages()) {
                if (item == null || item.getContent() == null || item.getContent().isBlank()) continue;
                out.add(item.getContent().trim());
            }
            return out;
        }
        if (req.getContent() != null && !req.getContent().isBlank()) {
            out.add(req.getContent().trim());
        }
        return out;
    }

    private void streamChat(SseEmitter emitter, String userId, String companionId,
                            String conversationId, List<String> contents) {
        ReentrantLock lock = conversationLocks.computeIfAbsent(conversationId, k -> new ReentrantLock());
        lock.lock();
        try {
            LocalDateTime now = LocalDateTime.now();
            String decisionText = String.join("。", contents);

            // 1. 批量入库(V4 Message Lifecycle: DELIVERED) + 感知 + 会话归属 + 工作记忆 + 聊天习惯学习
            Message last = null;
            List<Message> userMsgs = new ArrayList<>();
            for (String content : contents) {
                PerceptionEngine.Perception perception = perceptionEngine.perceive(content);
                Message m = conversationService.addMessage(conversationId, "user", content, perception, false);
                sessionManager.assign(m, userId, companionId, now);
                workingMemory.record(companionId, conversationId,
                        new WorkingMemory.RecentLine("user", content, m.getCreatedAt()), perception);
                userChatStyleService.record(companionId, userId, content, m.getCreatedAt());
                // V6 §45/§46: 行为模式学习(深夜/工作时回复慢, 用户开心时她更主动)
                try {
                    behaviorLearningService.onUserMessage(companionId, now, perception.emotion());
                } catch (Exception e) {
                    log.warn("BehaviorPattern 学习失败: {}", e.getMessage());
                }
                // 事件流: 用户消息已送达(未读)
                eventBus.publish(companionId, CompanionEventType.USER_MESSAGE_STATUS,
                        Map.of("messageId", m.getId(), "status", "DELIVERED"));
                userMsgs.add(m);
                last = m;
            }

            // 2. V5 消息流水线: Emotion → Attention → Brain(V5 §9/§10/§13: 消息先改变状态, 再决定行为)
            PerceptionEngine.Perception burstPerception = perceptionEngine.perceive(decisionText);
            V5MessagePipeline.PipelineResult pipelineResult = messagePipeline.process(
                    userId, companionId, conversationId, userMsgs, decisionText, burstPerception, now);

            // V6 §30 Conversation Thread: 记录/复用当前话题线程(话题变化 → 旧线程 PAUSED, 开新线程)
            try {
                threadService.touch(conversationId, companionId, userId,
                        burstPerception != null ? burstPerception.topic() : null,
                        burstPerception != null ? burstPerception.emotion() : null, now);
            } catch (Exception e) {
                log.warn("ConversationThread touch 失败: {}", e.getMessage());
            }

            // 3. 她根本没看到(静音/勿扰/在忙/分心)→ 保持未读(DELIVERED), 不打扰
            if (pipelineResult.isIgnored()) {
                send(emitter, "meta", Map.of("action", "IGNORE", "reason", pipelineResult.reason()));
                send(emitter, "done", Map.of("ignored", true, "action", "IGNORE",
                        "reason", pipelineResult.reason()));
                emitter.complete();
                return;
            }

            // 4. DEFER(V5 §79/§80): 看到了但不回 —— 状态已变(已读未回), 排程复查
            if (pipelineResult.isDeferred()) {
                send(emitter, "meta", Map.of("action", "DEFER", "reason", pipelineResult.reason()));
                send(emitter, "done", Map.of("deferred", true, "action", "DEFER", "reason", pipelineResult.reason()));
                emitter.complete();
                return;
            }

            // 5. 回复路径: 决策来自 Brain(规则回退), 表达来自 ExpressionAgent
            InteractionDecision decision = pipelineResult.brainDecision() != null
                    ? pipelineResult.brainDecision().baseline()
                    : null;
            if (decision == null) {
                decision = decide(userId, companionId, burstPerception, decisionText, now, null);
            }
            AttentionService.Attention attention = pipelineResult.attention();
            var state = agentStateService.get(companionId);

            // 6. 正常回复路径: 已读延迟(忙/疲劳 → 慢) → 标记 READ + 事件
            List<Message> recent = conversationService.recentMessages(conversationId, 40);
            String kind = decision.action == InteractionAction.SHORT_ACK ? "SHORT_ACK" : "NORMAL";
            send(emitter, "meta", Map.of(
                    "intent", burstPerception.intent(),
                    "emotion", burstPerception.emotion(),
                    "topic", burstPerception.topic(),
                    "action", decision.action.name(),
                    "commitment", decision.commitment.name()));

            // V5 Expression: 决定"怎么说" + 分段计划(先有表达策略, 再生成文本)
            ExpressionResult expression = expressionAgent.execute(buildExpressionContext(
                    userId, companionId, decisionText, pipelineResult, decision, recent, now));

            long readDelay = attention != null ? attention.inspectDelayMs() : 0;
            if (readDelay > 0) {
                Thread.sleep(readDelay);
            }
            for (Message um : userMsgs) {
                deliveryService.read(companionId, um.getId());
            }

            // 8. typing + 延迟(真人节奏; 短应和不显示"正在输入")
            long latency = latencyEngine.computeDelayMs(decision, decisionText,
                    state != null ? state.getEnergy() : 0.6,
                    state != null ? state.getStress() : 0.3, now,
                    availabilityService.current(companionId, now, state));
            boolean showTyping = decision.commitment.level >= com.luxera.companion.interaction.ResponseCommitment.CASUAL.level;
            if (showTyping) {
                send(emitter, "typing_start", Map.of("conversationId", conversationId));
                eventBus.publish(companionId, CompanionEventType.COMPANION_TYPING, Map.of("typing", true));
            }
            if (latency > 0) {
                Thread.sleep(latency);
            }
            if (showTyping) {
                send(emitter, "typing_stop", Map.of());
                eventBus.publish(companionId, CompanionEventType.COMPANION_TYPING, Map.of("typing", false));
            }

            // 8. 生成一次(带预算 + V5 表达策略)
            String expressionHint = describeExpression(expression);
            CompanionRuntime.ChatOutcome outcome = runtime.generate(userId, companionId, conversationId,
                    last.getId(), decisionText, recent,
                    delta -> send(emitter, "token", Map.of("delta", delta)), decision, expressionHint);

            // 9. Expression Loop(V5 §35): 先有表达计划(边想边说), 而不是把完整回答拆句
            //    ExpressionAgent 规划段数; LLM 输出 <split> 优先; 深度情绪时按标点兜底展开
            String reply = outcome.reply();
            List<String> chunks = splitReply(reply);
            int planSegments = expression != null && expression.segments() != null && !expression.segments().isEmpty()
                    ? expression.segments().size() : 1;
            double emotionalImpact = pipelineResult.emotion() != null
                    ? Math.abs(pipelineResult.emotion().delta().hurt())
                    + Math.abs(pipelineResult.emotion().delta().anger())
                    + Math.abs(pipelineResult.emotion().delta().sadness()) : 0;
            boolean deepTone = decision.commitment.level >= com.luxera.companion.interaction.ResponseCommitment.DEEP.level
                    || (decision.commitment.level >= com.luxera.companion.interaction.ResponseCommitment.ENGAGED.level
                        && emotionalImpact >= 0.6);
            if (chunks.size() == 1 && (deepTone || planSegments > 1)
                    && reply.length() > 35 && (emotionalImpact >= 0.35 || planSegments > 1)) {
                String mid = splitAtPunctuation(reply);
                if (mid != null) {
                    chunks = List.of(mid, reply.substring(mid.length()).trim());
                }
            }
            String first = chunks.get(0).trim();
            if (!first.equals(outcome.rawReply().trim())) {
                send(emitter, "replace", Map.of("content", first));
            }
            Message assistant = conversationService.addMessage(conversationId, "companion", first, null, false,
                    kind, last.getSessionId(), last.getExchangeId());
            workingMemory.record(companionId, conversationId,
                    new WorkingMemory.RecentLine("companion", first, assistant.getCreatedAt()), null);

            // 后续段: 延迟后逐条写库 + 通知前端(像是隔了一下又补一句)
            for (int i = 1; i < chunks.size(); i++) {
                String seg = chunks.get(i).trim();
                if (seg.isEmpty()) continue;
                try {
                    Thread.sleep(900 + (long) (Math.random() * 900));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                Message m = conversationService.addMessage(conversationId, "companion", seg, null, false,
                        kind, last.getSessionId(), last.getExchangeId());
                workingMemory.record(companionId, conversationId,
                        new WorkingMemory.RecentLine("companion", seg, m.getCreatedAt()), null);
                send(emitter, "message", Map.of("messageId", m.getId(), "content", seg));
                eventBus.publish(companionId, CompanionEventType.COMPANION_MESSAGE,
                        Map.of("messageId", m.getId(), "content", seg, "senderType", "companion"));
            }

            // 10. 对方要走 → 记录边界, 不主动续聊
            if (decision.action == InteractionAction.END_CONVERSATION) {
                sessionManager.boundary(userId, companionId, conversationId, "SOFT_END", decision.reason);
                send(emitter, "boundary", Map.of("type", "SOFT_END"));
            }

            send(emitter, "done", Map.of("messageId", assistant.getId(), "action", decision.action.name()));
            emitter.complete();
        } catch (Exception e) {
            log.error("聊天流式处理失败", e);
            try {
                emitter.send(SseEmitter.event().name("error").data(Map.of("message", "生成回复时出错,请重试")));
            } catch (IOException ignored) {
            }
            emitter.complete();
        } finally {
            lock.unlock();
        }
    }

    /** 按 <split> 拆段(去空段), 无标记时返回单段 */
    private static List<String> splitReply(String reply) {
        List<String> out = new ArrayList<>();
        if (reply == null || reply.isBlank()) {
            out.add(reply == null ? "" : reply);
            return out;
        }
        for (String seg : reply.split(SPLIT)) {
            if (!seg.isBlank()) out.add(seg);
        }
        return out;
    }

    /**
     * 在第二个句号/感叹号处拆分(Expression Loop 兜底): 前半是完整回应, 后半是补充。
     * 找不到第二个句号时, 在第一个句号/感叹号后拆; 仍找不到(口语化长句)则在第一个逗号后拆。
     */
    private static String splitAtPunctuation(String text) {
        int count = 0;
        int firstSentenceEnd = -1;
        for (int i = 0; i < text.length() - 1; i++) {
            char c = text.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '!') {
                count++;
                if (firstSentenceEnd < 0) firstSentenceEnd = i;
                if (count >= 2 && i > 10 && i < text.length() - 3) {
                    return text.substring(0, i + 1).trim();
                }
            }
        }
        // 只有一个句号 → 在其后拆(后半当补充)
        if (firstSentenceEnd > 10 && firstSentenceEnd < text.length() - 3) {
            return text.substring(0, firstSentenceEnd + 1).trim();
        }
        // 完全没有句号 → 第一个逗号后拆
        int comma = text.indexOf('，');
        if (comma > 10 && comma < text.length() - 3) {
            return text.substring(0, comma + 1).trim();
        }
        return null;
    }

    /** 计算交互决策(V4: Appraisal + Drives 竞争) */
    private InteractionDecision decide(String userId, String companionId,
                                       PerceptionEngine.Perception perception, String text, LocalDateTime now,
                                       AppraisalService.AppraisalResult appraisal) {
        AgentState state = agentStateService.get(companionId);
        Relationship rel = relationshipService.find(userId, companionId);
        boolean intimate = rel != null && List.of("close", "deeply_connected").contains(rel.getRelationshipStage());
        var activity = schedule.activityFor(companionId, now);
        boolean busy = activity == CompanionSchedule.Activity.WORK_BUSY
                || activity == CompanionSchedule.Activity.WORK_AFTERNOON;
        CompanionAvailability availability = availabilityService.current(companionId, now, state);
        return interactionPolicy.decide(new InteractionPolicyEngine.InteractionInput(
                text, perception.intent(), perception.emotion(),
                state != null ? state.getEnergy() : 0.6,
                state != null ? state.getStress() : 0.3,
                rel != null ? rel.getRelationshipStage() : "new",
                intimate, busy, availability, appraisal,
                state != null ? state.getEmotionalCloseness() : 0.3,
                rel != null ? rel.getFamiliarity() : 0,
                rel != null ? rel.getIntimacy() : 0));
    }

    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException e) {
            throw new RuntimeException("SSE 发送失败", e);
        }
    }

    // ── V5 Expression 辅助 ──────────────────────────────

    /** 构建 ExpressionContext(Brain 已决定"要不要说/想表达什么") */
    private ExpressionContext buildExpressionContext(String userId, String companionId, String decisionText,
                                                     V5MessagePipeline.PipelineResult pipelineResult,
                                                     InteractionDecision decision,
                                                     List<Message> recent, LocalDateTime now) {
        AgentState state = agentStateService.get(companionId);
        Relationship rel = relationshipService.find(userId, companionId);
        var persona = companionService.getPersona(companionId);
        String personality = persona != null && persona.getPersonality() != null
                ? persona.getPersonality().getSummary() : null;
        var companion = companionService.requireOwned(userId, companionId);
        double emotionalImpact = pipelineResult.emotion() != null
                ? Math.abs(pipelineResult.emotion().delta().hurt())
                + Math.abs(pipelineResult.emotion().delta().anger())
                + Math.abs(pipelineResult.emotion().delta().sadness()) : 0;
        String emotionSummary = state != null ? state.getMood() : "平静";
        return new ExpressionContext(
                companionId, userId, decisionText,
                pipelineResult.brainDecision() != null ? pipelineResult.brainDecision().expressionGoal() : "respond",
                emotionSummary, personality,
                rel != null ? rel.getRelationshipStage() : "new",
                state != null ? state.getEmotionalCloseness() : 0.3,
                schedule.describe(companionId, companion.getName(), now),
                recentLines(recent),
                state != null ? state.getEnergy() : 0.6,
                emotionalImpact,
                decision);
    }

    /** 把表达策略转成给生成阶段的提示(自然地说, 不要说破) */
    private static String describeExpression(ExpressionResult expression) {
        if (expression == null || expression.strategy() == null) return null;
        ExpressionResult.ExpressionStrategy s = expression.strategy();
        StringBuilder sb = new StringBuilder();
        sb.append("语气 ").append(s.tone())
                .append(", 直接程度 ").append(pct(s.directness()))
                .append(", 温度 ").append(pct(s.warmth()))
                .append(", 俏皮 ").append(pct(s.playfulness()))
                .append(", 流露脆弱 ").append(pct(s.vulnerability()));
        if (expression.segments() != null && expression.segments().size() > 1) {
            sb.append("。你想分成 ").append(expression.segments().size())
                    .append(" 条说, 边想边说(先一句, 停一下, 再补一句)");
        }
        return sb.toString();
    }

    private static List<String> recentLines(List<Message> recent) {
        List<String> lines = new ArrayList<>();
        for (Message m : recent) {
            String prefix = "user".equals(m.getSenderType()) ? "用户: " : "我: ";
            lines.add(prefix + m.getContent());
        }
        return lines;
    }

    private static String pct(double v) {
        return (int) (Math.max(0, Math.min(1, v)) * 100) + "%";
    }

    @Data
    public static class ChatRequest {
        /** 单条消息(兼容) */
        private String content;
        /** 批量消息(连发归并) */
        private List<ChatMessageItem> messages;
    }

    @Data
    public static class ChatMessageItem {
        private String content;
    }

    @Data
    public static class CreateRequest {
        private String title;
    }
}
