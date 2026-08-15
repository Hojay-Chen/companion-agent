package com.luxera.companion.conversation;

import com.luxera.companion.agent.CompanionRuntime;
import com.luxera.companion.agent.CompanionSchedule;
import com.luxera.companion.agent.PerceptionEngine;
import com.luxera.companion.agent.WorkingMemory;
import com.luxera.companion.config.CurrentUser;
import com.luxera.companion.interaction.InteractionAction;
import com.luxera.companion.interaction.InteractionDecision;
import com.luxera.companion.interaction.InteractionPolicyEngine;
import com.luxera.companion.interaction.ResponseLatencyEngine;
import com.luxera.companion.persona.CompanionService;
import com.luxera.companion.relationship.RelationshipService;
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
    private final CompanionSchedule schedule;
    private final CurrentUser currentUser;
    private final TaskExecutor taskExecutor;
    /** 每会话一个锁, 串行化同会话的消息处理(消息归并 + 生成) */
    private final java.util.concurrent.ConcurrentHashMap<String, ReentrantLock> conversationLocks = new java.util.concurrent.ConcurrentHashMap<>();

    public ChatController(ConversationService conversationService, CompanionService companionService,
                          CompanionRuntime runtime, PerceptionEngine perceptionEngine, WorkingMemory workingMemory,
                          SessionManager sessionManager, InteractionPolicyEngine interactionPolicy,
                          ResponseLatencyEngine latencyEngine, AgentStateService agentStateService,
                          AvailabilityService availabilityService, RelationshipService relationshipService,
                          UserChatStyleService userChatStyleService, CompanionSchedule schedule,
                          CurrentUser currentUser, TaskExecutor taskExecutor) {
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
        this.schedule = schedule;
        this.currentUser = currentUser;
        this.taskExecutor = taskExecutor;
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

            // 1. 批量入库: 每条用户消息 → 感知 + 会话归属 + 工作记忆 + 聊天习惯学习
            Message last = null;
            for (String content : contents) {
                PerceptionEngine.Perception perception = perceptionEngine.perceive(content);
                Message m = conversationService.addMessage(conversationId, "user", content, perception, false);
                sessionManager.assign(m, userId, companionId, now);
                workingMemory.record(companionId, conversationId,
                        new WorkingMemory.RecentLine("user", content, m.getCreatedAt()), perception);
                // V3 P1: 学习用户的聊天习惯
                userChatStyleService.record(companionId, userId, content, m.getCreatedAt());
                last = m;
            }

            // 2. 交互决策: 基于整个 burst 的合并文本(最后一条决定当前感受)
            PerceptionEngine.Perception burstPerception = perceptionEngine.perceive(decisionText);
            InteractionDecision decision = decide(userId, companionId, burstPerception, decisionText, now);

            // 3. 不回复是合法行为(WAIT = 等/不打断, 与 IGNORE 一样本次不出消息)
            if (decision.action == InteractionAction.IGNORE || decision.action == InteractionAction.WAIT) {
                send(emitter, "meta", Map.of("action", decision.action.name(), "reason", decision.reason));
                send(emitter, "done", Map.of("ignored", true, "action", decision.action.name(), "reason", decision.reason));
                emitter.complete();
                return;
            }

            List<Message> recent = conversationService.recentMessages(conversationId, 40);
            String kind = decision.action == InteractionAction.SHORT_ACK ? "SHORT_ACK" : "NORMAL";
            send(emitter, "meta", Map.of(
                    "intent", burstPerception.intent(),
                    "emotion", burstPerception.emotion(),
                    "topic", burstPerception.topic(),
                    "action", decision.action.name(),
                    "commitment", decision.commitment.name()));

            // 4. typing + 延迟(真人节奏; 短应和不显示"正在输入", 见设计 §十五)
            var state = agentStateService.get(companionId);
            var availability = availabilityService.current(companionId, now, state);
            long latency = latencyEngine.computeDelayMs(decision, decisionText,
                    state != null ? state.getEnergy() : 0.6,
                    state != null ? state.getStress() : 0.3, now, availability);
            boolean showTyping = decision.commitment.level >= com.luxera.companion.interaction.ResponseCommitment.CASUAL.level;
            if (showTyping) {
                send(emitter, "typing_start", Map.of("conversationId", conversationId));
            }
            if (latency > 0) {
                Thread.sleep(latency);
            }
            if (showTyping) {
                send(emitter, "typing_stop", Map.of());
            }

            // 5. 生成一次(带预算)
            CompanionRuntime.ChatOutcome outcome = runtime.generate(userId, companionId, conversationId,
                    last.getId(), decisionText, recent,
                    delta -> send(emitter, "token", Map.of("delta", delta)), decision);

            // 6. ResponsePlan: 她也可以连发(低频, 用 <split> 分隔)
            String reply = outcome.reply();
            List<String> chunks = splitReply(reply);
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
            }

            // 7. 对方要走 → 记录边界, 不主动续聊
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

    /** 计算交互决策 */
    private InteractionDecision decide(String userId, String companionId,
                                       PerceptionEngine.Perception perception, String text, LocalDateTime now) {
        AgentState state = agentStateService.get(companionId);
        var rel = relationshipService.find(userId, companionId);
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
                intimate, busy, availability));
    }

    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException e) {
            throw new RuntimeException("SSE 发送失败", e);
        }
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
