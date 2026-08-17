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
import com.luxera.companion.runtime.agent.brain.BrainDecision;
import com.luxera.companion.runtime.agent.expression.ExpressionResult;
import com.luxera.companion.relationship.Relationship;
import com.luxera.companion.relationship.RelationshipService;
import com.luxera.companion.runtime.pipeline.MessageDeliveryService;
import com.luxera.companion.runtime.pipeline.MessagePipeline;
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
 * §10-§21 Agent Runtime: 通信解耦。
 *
 * 用户发消息 → POST /messages 立即持久化返回 → Agent Runtime 异步处理。
 * Agent 是否看到/是否回复/什么时候回复, 全部由 Runtime 决定,
 * 回复通过事件总线推送(前端 GET /events 长连接接收)。
 *
 * 用户发送消息永远不会被 Agent 阻塞(§18 工程约束)。
 */
@Slf4j
@Service
public class AgentRuntime {

    private static final String SPLIT = "<split>";

    private final ConversationService conversationService;
    private final PerceptionEngine perceptionEngine;
    private final WorkingMemory workingMemory;
    private final SessionManager sessionManager;
    private final UserChatStyleService userChatStyleService;
    private final BehaviorLearningService behaviorLearningService;
    private final MessagePipeline messagePipeline;
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
    private final com.luxera.companion.cognitive.CognitiveSessionService cognitiveSessionService;
    /** V9: per-agent 单写者锁(同 agent 的写入串行, 防止并发覆盖状态) */
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.locks.ReentrantLock> locks =
            new java.util.concurrent.ConcurrentHashMap<>();

    public AgentRuntime(ConversationService conversationService, PerceptionEngine perceptionEngine,
                          WorkingMemory workingMemory, SessionManager sessionManager,
                          UserChatStyleService userChatStyleService, BehaviorLearningService behaviorLearningService,
                          MessagePipeline messagePipeline, ConversationThreadService threadService,
                          ExpressionAgent expressionAgent, MessageDeliveryService deliveryService,
                          InteractionPolicyEngine interactionPolicy, ResponseLatencyEngine latencyEngine,
                          AgentStateService agentStateService, AvailabilityService availabilityService,
                          RelationshipService relationshipService, PhoneNotificationService phoneNotificationService,
                          CognitiveWakeupService cognitiveWakeupService, IntentionService intentionService,
                          CompanionEventBus eventBus, CompanionSchedule schedule, CompanionRuntime runtime,
                          TaskExecutor taskExecutor,
                          com.luxera.companion.cognitive.CognitiveSessionService cognitiveSessionService) {
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
        this.cognitiveSessionService = cognitiveSessionService;
    }

    /**
     * §十一~§十四: 接收**已持久化**的用户消息并异步触发 Agent 处理。
     * 消息落库已由 {@link com.luxera.companion.conversation.MessageCoreService} 在请求事务内完成;
     * 这里只做 Agent 认知处理(感知/流水线/回复), 永不参与消息的持久化。
     * 立即返回(不阻塞); Agent 的回复通过事件总线推送。
     */
    public void submit(String userId, String companionId, String conversationId, List<Message> userMessages) {
        taskExecutor.execute(() -> {
            try {
                process(userId, companionId, conversationId, userMessages);
            } catch (Exception e) {
                log.error("[AgentRuntime] 处理消息失败 companion={}: {}", companionId, e.getMessage());
            }
        });
    }

    /** Agent 异步处理已入库的用户消息(完整认知链) */
    public void process(String userId, String companionId, String conversationId, List<Message> userMessages) {
        if (userMessages == null || userMessages.isEmpty()) return;
        // V9 §17: per-agent 单写者队列
        var lock = locks.computeIfAbsent(companionId, k -> new java.util.concurrent.locks.ReentrantLock());
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

            // 2. 唤醒评估(前置): 决定"睡着时是否被重要消息吵醒" + 低价值消息不打扰。
            // 关系权重: 亲密的人发来的消息 → 更敏感(关系影响认知)。
            PerceptionEngine.Perception burstPerception = perceptionEngine.perceive(decisionText);
            Relationship wakeRel = relationshipService.find(userId, companionId);
            double relWeight = wakeRel != null
                    ? (wakeRel.getIntimacy() * 0.6 + wakeRel.getAffection() * 0.4) : 0.3;
            boolean sleeping = schedule.activityFor(companionId, now) == CompanionSchedule.Activity.SLEEP;
            boolean urged = beingUrged(conversationId, now, decisionText);   // 追问词/连发(被催问)
            boolean emotionalSignal = burstPerception != null && burstPerception.emotion() != null
                    && !List.of("neutral", "calm", "happy").contains(burstPerception.emotion());
            double wakeImportance = 0.35 + (urged ? 0.25 : 0) + (emotionalSignal ? 0.2 : 0);
            CognitiveWakeupService.WakeLevel wake = cognitiveWakeupService.evaluate(
                    new CognitiveWakeupService.WakeupInput(
                            "MESSAGE", decisionText, wakeImportance, 0.5,
                            emotionalSignal ? 0.4 : 0.1, 0.3, 0.5), relWeight);
            // 被连发催问 → 认真对待(真人被连着问会坐不住)
            if (urged && wake == CognitiveWakeupService.WakeLevel.ATTENTION) {
                wake = CognitiveWakeupService.WakeLevel.DELIBERATION;
            }

            // V9 §4.3: 更新连续心智 —— 他刚才在说什么, 我心里在想什么(不打断处理主流程)
            try {
                String focus = burstPerception != null && burstPerception.topic() != null
                        ? burstPerception.topic() : truncate(decisionText, 30);
                String thought = emotionalSignal
                        ? "他好像不太对劲,想多陪陪他"
                        : (urged ? "他连着找我,是不是有什么事" : "他刚说起" + focus);
                cognitiveSessionService.touchOnMessage(companionId, focus, thought,
                        emotionalSignal ? "有点担心他" : null);
            } catch (Exception ignored) { }

            // V9 §14: Fast/Deep 路径 —— 重要消息(DELIBERATION+)走 Deep 完整上下文, 其余走 Fast 轻量
            String path = (wake == CognitiveWakeupService.WakeLevel.DELIBERATION
                    || wake == CognitiveWakeupService.WakeLevel.DEEP_THINKING)
                    ? com.luxera.companion.agent.CompanionCognitiveRuntime.PATH_DEEP
                    : com.luxera.companion.agent.CompanionCognitiveRuntime.PATH_FAST;

            boolean forceNoticed = false;
            if (sleeping) {
                if (wake == CognitiveWakeupService.WakeLevel.DELIBERATION
                        || wake == CognitiveWakeupService.WakeLevel.DEEP_THINKING) {
                    // 重要消息(深夜的"在吗"/连发催问/情绪强烈)→ 她会被吵醒, 强制造注到
                    forceNoticed = true;
                } else {
                    // 睡着且不是重要消息 → 不打扰(保持未读), 符合真人
                    eventBus.publish(companionId, CompanionEventType.USER_MESSAGE_STATUS,
                            Map.of("messageId", last.getId(), "status", "DELIVERED", "action", "ASLEEP"));
                    log.info("[AgentRuntime] {} 睡着且消息不紧急, 不打扰 (wake={})", companionId, wake);
                    return;
                }
            }
            log.info("[AgentRuntime] {} sleeping={} urged={} wake={} forceNoticed={} text={}",
                    companionId, sleeping, urged, wake, forceNoticed, truncate(decisionText, 20));

            // 3. 消息流水线(forceNoticed: 被吵醒时跳过"没看到"判定)
            MessagePipeline.PipelineResult pipelineResult = messagePipeline.process(
                    userId, companionId, conversationId, userMessages, decisionText, burstPerception, now,
                    forceNoticed);
            log.info("[AgentRuntime] {} pipeline outcome={} reason={}", companionId,
                    pipelineResult.outcome(), pipelineResult.reason());

            // §15-§17 Phone Notification: 消息到达 → 手机通知 → heard/seen/opened/read 逐步推进
            PhoneNotification phoneNotif = null;
            try {
                phoneNotif = phoneNotificationService.create(companionId, conversationId, last.getId(),
                        truncate(decisionText, 50), true, true, now);
                AttentionService.Attention att = pipelineResult.attention();
                boolean phoneAvailable = att != null && att.inspectProbability() > 0;
                phoneNotif = phoneNotificationService.advance(phoneNotif, att, phoneAvailable, now);
            } catch (Exception ignored) { }

            // 会话线程
            try {
                threadService.touch(conversationId, companionId, userId,
                        burstPerception != null ? burstPerception.topic() : null,
                        burstPerception != null ? burstPerception.emotion() : null, now);
            } catch (Exception ignored) { }

            // 低价值消息(前置评估的 wake 已含关系权重与催问信号)→ 不唤醒认知
            if (!cognitiveWakeupService.requiresCognition(wake)) {
                // 低价值消息(如"哈哈"): 消息已入库, 但不打断她的生活 → 不生成回复
                eventBus.publish(companionId, CompanionEventType.USER_MESSAGE_STATUS,
                        Map.of("messageId", last.getId(), "status", "DELIVERED", "action", "MICRO_WAKE"));
                return;
            }

            // 3. 没看到 → 保持未读(被催问时不算"没看到": 真人被催问会看一眼)
            if (pipelineResult.isIgnored() && !urged) {
                eventBus.publish(companionId, CompanionEventType.USER_MESSAGE_STATUS,
                        Map.of("messageId", last.getId(), "status", "DELIVERED", "action", "IGNORE"));
                return;
            }
            // 4. 看到了但不回(DEFER) → 已读(整个会话), 后续复查; 但被连发催问时, 真人会被催着回
            if (pipelineResult.isDeferred() && !urged) {
                // 她看到了全部未读消息 → 全部已读, 但决定稍后回
                markAllConversationRead(companionId, conversationId, userMessages, now);
                eventBus.publish(companionId, CompanionEventType.USER_MESSAGE_STATUS,
                        Map.of("messageId", last.getId(), "status", "READ", "action", "DEFER"));
                // §35-§36: 创建意图"该回复他" → 之后可能突然想起(Intention Activation)
                try {
                    intentionService.create(companionId, userId,
                            "还没回复那句「" + truncate(decisionText, 30) + "」, 等忙完想补一句",
                            0.6, "内疚", "user",
                            now.plusHours(2), 24);
                } catch (Exception ignored) { }
                return;
            }

            // 5. 回复路径(被催问的 IGNORE/DEFER 也落在这里: 用交互策略重新决策)
            InteractionDecision decision = null;
            if (pipelineResult.brainDecision() != null
                    && !pipelineResult.brainDecision().isDefer()
                    && !BrainDecision.IGNORE.equals(pipelineResult.brainDecision().action())) {
                decision = pipelineResult.brainDecision().baseline();
            }
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
            // 她拿起手机看到整个会话 → 本批次 + 该会话其余未读用户消息全部变已读(真人行为)
            markAllConversationRead(companionId, conversationId, userMessages, now);
            // 通知标记已读
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
            // §八: 关系影响回复节奏 —— 熟悉/亲密 → 略快(更随意); 张力高/心情低落 → 更慢
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
                    last.getId(), decisionText, recent, null, decision, expressionHint, path);
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
            MessagePipeline.PipelineResult pr) {
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
                                                     MessagePipeline.PipelineResult pr, InteractionDecision decision,
                                                     List<Message> recent, LocalDateTime now) {
        var state = agentStateService.get(companionId);
        String mood = state != null ? state.getMood() : "平静";
        return new ExpressionContext(
                companionId, userId, decisionText, "respond", mood, null, "close", 0.5,
                schedule.describe(companionId, "她", now),
                List.of("用户: " + decisionText), 0.6, 0.4, decision,
                describeResponseIntent(pr));
    }

    /** V9 §11: Brain 的回应意图 → Expression(Brain 决定想多长/拆几条/节奏, Expression 决定怎么说) */
    private static String describeResponseIntent(MessagePipeline.PipelineResult pr) {
        var bd = pr == null ? null : pr.brainDecision();
        if (bd == null) return null;
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (bd.messageCount() > 0) {
            parts.add("想拆 " + bd.messageCount() + " 条消息");
        }
        if (bd.desiredLength() > 0) {
            parts.add("期望约 " + bd.desiredLength() + " 字");
        }
        if (bd.delayHint() != null && !bd.delayHint().isBlank()) {
            parts.add("节奏:" + bd.delayHint());
        }
        if (bd.styleHint() != null && !bd.styleHint().isBlank()) {
            parts.add("语气:" + bd.styleHint());
        }
        return parts.isEmpty() ? null : String.join(";", parts);
    }

    private static String describeExpression(ExpressionResult e) {
        if (e == null || e.strategy() == null) return null;
        return "语气 " + e.strategy().tone() + ", 直接 " + Math.round(e.strategy().directness() * 100) + "%";
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** 是否被催问: 消息含追问词(在吗/怎么不回/回我/醒了吗/急事… —— 真人被催着回) */
    private boolean beingUrged(String conversationId, LocalDateTime now, String decisionText) {
        if (decisionText != null && containsAny(decisionText,
                "在吗", "怎么不", "不回", "回我", "理我", "醒了吗", "忙吗", "看到吗", "看见吗", "人呢", "急事", "紧急")) {
            return true;
        }
        return false;
    }

    /**
     * 她拿起手机看到整个会话 → 该会话全部未读用户消息一并变已读(真人行为, 不是只读最新一条)。
     * 逐条 deliveryService.read(会发布 message_read 事件, 前端实时更新勾勾)。
     */
    private void markAllConversationRead(String companionId, String conversationId,
                                         List<Message> userMessages, LocalDateTime now) {
        try {
            for (Message m : conversationService.messages(conversationId)) {
                if (!"user".equals(m.getSenderType())) continue;
                if (m.getDeliveryStatus() == null
                        || "READ".equals(m.getDeliveryStatus())
                        || "IGNORED".equals(m.getDeliveryStatus())) continue;  // 已读/已忽略跳过
                deliveryService.read(companionId, m.getId());
                try {
                    phoneNotificationService.markRead(m.getId(), now);
                } catch (Exception ignored) { }
            }
        } catch (Exception e) {
            log.debug("[AgentRuntime] 批量已读失败: {}", e.getMessage());
        }
    }

    private static boolean containsAny(String s, String... keys) {
        for (String k : keys) {
            if (s.contains(k)) return true;
        }
        return false;
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
