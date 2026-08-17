package com.luxera.companion.runtime;

import com.luxera.companion.agent.CompanionRuntime;
import com.luxera.companion.agent.CompanionSchedule;
import com.luxera.companion.agent.PerceptionEngine;
import com.luxera.companion.agent.WorkingMemory;
import com.luxera.companion.attention.AttentionService;
import com.luxera.companion.behavior.BehaviorLearningService;
import com.luxera.companion.cognition.CognitiveWakeupService;
import com.luxera.companion.conversation.ConversationService;
import com.luxera.companion.conversation.ConversationThreadService;
import com.luxera.companion.conversation.Message;
import com.luxera.companion.conversation.SessionManager;
import com.luxera.companion.event.CompanionEventBus;
import com.luxera.companion.event.CompanionEventType;
import com.luxera.companion.intention.IntentionService;
import com.luxera.companion.interaction.InteractionAction;
import com.luxera.companion.interaction.InteractionDecision;
import com.luxera.companion.interaction.InteractionPolicyEngine;
import com.luxera.companion.interaction.ResponseLatencyEngine;
import com.luxera.companion.phone.PhoneNotification;
import com.luxera.companion.phone.PhoneNotificationService;
import com.luxera.companion.runtime.agent.expression.ExpressionAgent;
import com.luxera.companion.runtime.agent.expression.ExpressionContext;
import com.luxera.companion.runtime.agent.expression.ExpressionResult;
import com.luxera.companion.relationship.Relationship;
import com.luxera.companion.relationship.RelationshipService;
import com.luxera.companion.runtime.pipeline.MessageDeliveryService;
import com.luxera.companion.runtime.pipeline.V5MessagePipeline;
import com.luxera.companion.state.AgentStateService;
import com.luxera.companion.state.AvailabilityService;
import com.luxera.companion.state.CompanionAvailability;
import com.luxera.companion.usermodel.UserChatStyleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * V7 §10-§21 Agent Runtime: 通信解耦。
 *
 * 用户发消息 → POST /messages 立即持久化返回 → Agent Runtime 异步处理。
 * Agent 是否看到/是否回复/什么时候回复, 全部由 Runtime 决定,
 * 回复通过事件总线推送(前端 GET /events 长连接接收)。
 *
 * 用户发送消息永远不会被 Agent 阻塞(§18 工程约束)。
 */
@Slf4j
@Service
public class V7AgentRuntime {

    private static final String SPLIT = "<split>";

    private final ConversationService conversationService;
    private final PerceptionEngine perceptionEngine;
    private final WorkingMemory workingMemory;
    private final SessionManager sessionManager;
    private final UserChatStyleService userChatStyleService;
    private final BehaviorLearningService behaviorLearningService;
    private final V5MessagePipeline messagePipeline;
    private final ConversationThreadService threadService;
    private final ExpressionAgent expressionAgent;
    private final MessageDeliveryService deliveryService;
    private final InteractionPolicyEngine interactionPolicy;
    private final ResponseLatencyEngine latencyEngine;
    private final AgentStateService agentStateService;
    private final AvailabilityService availabilityService;
    private final RelationshipService relationshipService;
    private final PhoneNotificationService phoneNotificationService;
    private final CognitiveWakeupService cognitiveWakeupService;
    private final IntentionService intentionService;
    private final CompanionEventBus eventBus;
    private final CompanionSchedule schedule;
    private final CompanionRuntime runtime;
    private final TaskExecutor taskExecutor;
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.locks.ReentrantLock> locks =
            new java.util.concurrent.ConcurrentHashMap<>();

    public V7AgentRuntime(ConversationService conversationService, PerceptionEngine perceptionEngine,
                          WorkingMemory workingMemory, SessionManager sessionManager,
                          UserChatStyleService userChatStyleService, BehaviorLearningService behaviorLearningService,
                          V5MessagePipeline messagePipeline, ConversationThreadService threadService,
                          ExpressionAgent expressionAgent, MessageDeliveryService deliveryService,
                          InteractionPolicyEngine interactionPolicy, ResponseLatencyEngine latencyEngine,
                          AgentStateService agentStateService, AvailabilityService availabilityService,
                          RelationshipService relationshipService, PhoneNotificationService phoneNotificationService,
                          CognitiveWakeupService cognitiveWakeupService, IntentionService intentionService,
                          CompanionEventBus eventBus, CompanionSchedule schedule, CompanionRuntime runtime,
                          TaskExecutor taskExecutor) {
        this.conversationService = conversationService;
        this.perceptionEngine = perceptionEngine;
        this.workingMemory = workingMemory;
        this.sessionManager = sessionManager;
        this.userChatStyleService = userChatStyleService;
        this.behaviorLearningService = behaviorLearningService;
        this.messagePipeline = messagePipeline;
        this.threadService = threadService;
        this.expressionAgent = expressionAgent;
        this.deliveryService = deliveryService;
        this.interactionPolicy = interactionPolicy;
        this.latencyEngine = latencyEngine;
        this.agentStateService = agentStateService;
        this.availabilityService = availabilityService;
        this.relationshipService = relationshipService;
        this.phoneNotificationService = phoneNotificationService;
        this.cognitiveWakeupService = cognitiveWakeupService;
        this.intentionService = intentionService;
        this.eventBus = eventBus;
        this.schedule = schedule;
        this.runtime = runtime;
        this.taskExecutor = taskExecutor;
    }

    /**
     * V8 §十一~§十四: 接收**已持久化**的用户消息并异步触发 Agent 处理。
     * 消息落库已由 {@link com.luxera.companion.conversation.MessageCoreService} 在请求事务内完成;
     * 这里只做 Agent 认知处理(感知/流水线/回复), 永不参与消息的持久化。
     * 立即返回(不阻塞); Agent 的回复通过事件总线推送。
     */
    public void submit(String userId, String companionId, String conversationId, List<Message> userMessages) {
        taskExecutor.execute(() -> {
            try {
                process(userId, companionId, conversationId, userMessages);
            } catch (Exception e) {
                log.error("[V7AgentRuntime] 处理消息失败 companion={}: {}", companionId, e.getMessage());
            }
        });
    }

    /** Agent 异步处理已入库的用户消息(完整认知链) */
    public void process(String userId, String companionId, String conversationId, List<Message> userMessages) {
        if (userMessages == null || userMessages.isEmpty()) return;
        var lock = locks.computeIfAbsent(conversationId, k -> new java.util.concurrent.locks.ReentrantLock());
        lock.lock();
        try {
            LocalDateTime now = LocalDateTime.now();
            List<String> contents = new ArrayList<>();
            for (Message um : userMessages) {
                if (um.getContent() != null && !um.getContent().isBlank()) contents.add(um.getContent());
            }
            if (contents.isEmpty()) return;
            String decisionText = String.join("。", contents);
            Message last = userMessages.get(userMessages.size() - 1);

            // 1. 消息已在请求线程落库(MessageCoreService)。这里只做感知后处理:
            //    会话归属 + 工作记忆 + 聊天风格 + 行为学习(不入库消息本身)
            for (Message m : userMessages) {
                sessionManager.assign(m, userId, companionId, now);
                PerceptionEngine.Perception p = perceptionEngine.perceive(m.getContent());
                workingMemory.record(companionId, conversationId,
                        new WorkingMemory.RecentLine("user", m.getContent(), m.getCreatedAt()), p);
                userChatStyleService.record(companionId, userId, m.getContent(), m.getCreatedAt());
                try {
                    behaviorLearningService.onUserMessage(companionId, now, p.emotion());
                } catch (Exception ignored) { }
            }

            // 2. V5 消息流水线
            PerceptionEngine.Perception burstPerception = perceptionEngine.perceive(decisionText);
            V5MessagePipeline.PipelineResult pipelineResult = messagePipeline.process(
                    userId, companionId, conversationId, userMessages, decisionText, burstPerception, now);

            // V7 §15-§17 Phone Notification: 消息到达 → 手机通知 → heard/seen/opened/read 逐步推进
            PhoneNotification phoneNotif = null;
            try {
                phoneNotif = phoneNotificationService.create(companionId, conversationId, last.getId(),
                        truncate(decisionText, 50), true, true, now);
                AttentionService.Attention att = pipelineResult.attention();
                boolean phoneAvailable = att != null && att.inspectProbability() > 0;
                phoneNotif = phoneNotificationService.advance(phoneNotif, att, phoneAvailable, now);
            } catch (Exception ignored) { }

            // V6 会话线程
            try {
                threadService.touch(conversationId, companionId, userId,
                        burstPerception != null ? burstPerception.topic() : null,
                        burstPerception != null ? burstPerception.emotion() : null, now);
            } catch (Exception ignored) { }

            // V7 §22-§23 + V8 §三十二 Cognitive Wakeup: 低价值消息不唤醒 LLM 认知。
            // 关系权重: 亲密的人发来的消息 → 更敏感(关系影响认知)。
            Relationship wakeRel = relationshipService.find(userId, companionId);
            double relWeight = wakeRel != null
                    ? (wakeRel.getIntimacy() * 0.6 + wakeRel.getAffection() * 0.4) : 0.3;
            CognitiveWakeupService.WakeLevel wake = cognitiveWakeupService.evaluate(
                    new CognitiveWakeupService.WakeupInput(
                            "MESSAGE", decisionText,
                            pipelineResult.emotion() != null
                                    ? Math.abs(pipelineResult.emotion().delta().hurt())
                                    + Math.abs(pipelineResult.emotion().delta().anger()) + 0.3 : 0.3,
                            0.5,
                            pipelineResult.emotion() != null
                                    ? Math.abs(pipelineResult.emotion().delta().hurt())
                                    + Math.abs(pipelineResult.emotion().delta().anger())
                                    + Math.abs(pipelineResult.emotion().delta().sadness()) : 0,
                            0.3, 0.5), relWeight);
            if (!cognitiveWakeupService.requiresCognition(wake)) {
                // 低价值消息(如"哈哈"): 消息已入库, 但不打断她的生活 → 不生成回复
                eventBus.publish(companionId, CompanionEventType.USER_MESSAGE_STATUS,
                        Map.of("messageId", last.getId(), "status", "DELIVERED", "action", "MICRO_WAKE"));
                return;
            }

            // 3. 没看到 → 保持未读
            if (pipelineResult.isIgnored()) {
                eventBus.publish(companionId, CompanionEventType.USER_MESSAGE_STATUS,
                        Map.of("messageId", last.getId(), "status", "DELIVERED", "action", "IGNORE"));
                return;
            }
            // 4. 看到了但不回(DEFER) → 已读, 后续复查
            if (pipelineResult.isDeferred()) {
                eventBus.publish(companionId, CompanionEventType.USER_MESSAGE_STATUS,
                        Map.of("messageId", last.getId(), "status", "READ", "action", "DEFER"));
                // V7 §35-§36: 创建意图"该回复他" → 之后可能突然想起(Intention Activation)
                try {
                    intentionService.create(companionId, userId,
                            "还没回复那句「" + truncate(decisionText, 30) + "」, 等忙完想补一句",
                            0.6, "内疚", "user",
                            now.plusHours(2), 24);
                } catch (Exception ignored) { }
                return;
            }

            // 5. 回复路径
            InteractionDecision decision = pipelineResult.brainDecision() != null
                    ? pipelineResult.brainDecision().baseline() : null;
            if (decision == null) {
                decision = interactionPolicy.decide(buildInteractionInput(userId, companionId, decisionText, now, pipelineResult));
            }
            AttentionService.Attention attention = pipelineResult.attention();
            var state = agentStateService.get(companionId);

            List<Message> recent = conversationService.recentMessages(conversationId, 40);
            String kind = decision.action == InteractionAction.SHORT_ACK ? "SHORT_ACK" : "NORMAL";

            // Expression: 决定怎么说 + 打字节奏
            ExpressionResult expression = expressionAgent.execute(buildExpressionContext(
                    userId, companionId, decisionText, pipelineResult, decision, recent, now));

            // 已读延迟(忙/疲劳 → 慢)
            long readDelay = attention != null ? attention.inspectDelayMs() : 0;
            if (readDelay > 0) sleep(readDelay);
            for (Message um : userMessages) {
                deliveryService.read(companionId, um.getId());
            }
            // V7: 通知标记已读
            if (phoneNotif != null) {
                try {
                    phoneNotificationService.markRead(last.getId(), now);
                } catch (Exception ignored) { }
            }
            eventBus.publish(companionId, CompanionEventType.USER_MESSAGE_STATUS,
                    Map.of("messageId", last.getId(), "status", "READ"));

            // typing + 延迟
            long latency = latencyEngine.computeDelayMs(decision, decisionText,
                    state != null ? state.getEnergy() : 0.6,
                    state != null ? state.getStress() : 0.3, now,
                    availabilityService.current(companionId, now, state));
            // V8 §八: 关系影响回复节奏 —— 熟悉/亲密 → 略快(更随意); 张力高/心情低落 → 更慢
            Relationship latencyRel = relationshipService.find(userId, companionId);
            if (latencyRel != null) {
                double famIntim = latencyRel.getFamiliarity() * 0.5 + latencyRel.getIntimacy() * 0.5;
                latency = (long) (latency * (1.15 - famIntim * 0.35 + latencyRel.getTension() * 0.3));
            }
            boolean showTyping = decision.commitment.level >= com.luxera.companion.interaction.ResponseCommitment.CASUAL.level;
            if (showTyping) {
                eventBus.publish(companionId, CompanionEventType.COMPANION_TYPING, Map.of("typing", true));
            }
            if (latency > 0) sleep(latency);
            if (showTyping) {
                eventBus.publish(companionId, CompanionEventType.COMPANION_TYPING, Map.of("typing", false));
            }

            // 生成
            String expressionHint = describeExpression(expression);
            CompanionRuntime.ChatOutcome outcome = runtime.generate(userId, companionId, conversationId,
                    last.getId(), decisionText, recent, null, decision, expressionHint);
            String reply = outcome.reply();
            if (reply == null || reply.isBlank()) return;

            // 拆分回复段
            List<String> chunks = splitReply(reply);
            String first = chunks.get(0).trim();
            Message assistant = conversationService.addMessage(conversationId, "companion", first, null, false,
                    kind, last.getSessionId(), last.getExchangeId());
            workingMemory.record(companionId, conversationId,
                    new WorkingMemory.RecentLine("companion", first, assistant.getCreatedAt()), null);
            eventBus.publish(companionId, CompanionEventType.COMPANION_MESSAGE,
                    Map.of("messageId", assistant.getId(), "conversationId", conversationId,
                            "content", first, "senderType", "companion"));

            // 后续段: 延迟后逐条写库 + 推送(像真人隔一下又补一句)
            for (int i = 1; i < chunks.size(); i++) {
                String seg = chunks.get(i).trim();
                if (seg.isEmpty()) continue;
                sleep(900 + (long) (Math.random() * 900));
                Message m = conversationService.addMessage(conversationId, "companion", seg, null, false,
                        kind, last.getSessionId(), last.getExchangeId());
                workingMemory.record(companionId, conversationId,
                        new WorkingMemory.RecentLine("companion", seg, m.getCreatedAt()), null);
                eventBus.publish(companionId, CompanionEventType.COMPANION_MESSAGE,
                        Map.of("messageId", m.getId(), "conversationId", conversationId,
                                "content", seg, "senderType", "companion"));
            }

            // 对方要走 → 记录边界
            if (decision.action == InteractionAction.END_CONVERSATION) {
                sessionManager.boundary(userId, companionId, conversationId, "SOFT_END", decision.reason);
            }
        } finally {
            lock.unlock();
        }
    }

    private InteractionPolicyEngine.InteractionInput buildInteractionInput(
            String userId, String companionId, String decisionText, LocalDateTime now,
            V5MessagePipeline.PipelineResult pr) {
        var state = agentStateService.get(companionId);
        Relationship rel = relationshipService.find(userId, companionId);
        com.luxera.companion.appraisal.AppraisalService.AppraisalResult appraisal =
                com.luxera.companion.appraisal.AppraisalService.AppraisalResult.fromValues(
                        state != null ? state.getHurt() : 0, state != null ? state.getAnger() : 0,
                        state != null ? state.getWarmth() : 0, 0.2, -0.1, 0.3);
        boolean intimate = rel != null && List.of("close", "deeply_connected").contains(rel.getRelationshipStage());
        return new InteractionPolicyEngine.InteractionInput(
                decisionText, null, null,
                state != null ? state.getEnergy() : 0.6, state != null ? state.getStress() : 0.3,
                rel != null ? rel.getRelationshipStage() : "new", intimate, false,
                availabilityService.current(companionId, now, state), appraisal,
                state != null ? state.getEmotionalCloseness() : 0.3,
                rel != null ? rel.getFamiliarity() : 0, rel != null ? rel.getIntimacy() : 0);
    }

    private ExpressionContext buildExpressionContext(String userId, String companionId, String decisionText,
                                                     V5MessagePipeline.PipelineResult pr, InteractionDecision decision,
                                                     List<Message> recent, LocalDateTime now) {
        var state = agentStateService.get(companionId);
        String mood = state != null ? state.getMood() : "平静";
        return new ExpressionContext(
                companionId, userId, decisionText, "respond", mood, null, "close", 0.5,
                schedule.describe(companionId, "她", now),
                List.of("用户: " + decisionText), 0.6, 0.4, decision);
    }

    private static String describeExpression(ExpressionResult e) {
        if (e == null || e.strategy() == null) return null;
        return "语气 " + e.strategy().tone() + ", 直接 " + Math.round(e.strategy().directness() * 100) + "%";
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static List<String> splitReply(String reply) {
        List<String> out = new ArrayList<>();
        if (reply == null || reply.isBlank()) { out.add(""); return out; }
        if (reply.contains(SPLIT)) {
            for (String s : reply.split(SPLIT)) {
                String t = s.trim();
                if (!t.isEmpty()) out.add(t);
            }
            return out.isEmpty() ? List.of(reply.trim()) : out;
        }
        out.add(reply.trim());
        return out;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
