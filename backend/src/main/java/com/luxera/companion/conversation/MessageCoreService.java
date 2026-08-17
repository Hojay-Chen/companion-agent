package com.luxera.companion.conversation;

import com.luxera.companion.agent.PerceptionEngine;
import com.luxera.companion.event.CompanionEventBus;
import com.luxera.companion.event.CompanionEventType;
import com.luxera.companion.persona.CompanionService;
import com.luxera.companion.runtime.AgentRuntime;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * §十一~§十四 Chat Core: 用户消息的唯一真相源。
 *
 * 职责链: Controller → MessageCoreService(同步落库) → 事件总线(Outbox) → AgentRuntime(异步)。
 *
 * 关键保证:
 * 1. **同步落库** —— 用户消息在 HTTP 请求事务内写入 messages 表, 返回 canonical messageId。
 *    刷新页面、服务器重启、Agent 崩溃都不丢消息。
 * 2. **clientMessageId 幂等** —— 同会话内重复提交同一 clientMessageId 直接返回已存在消息,
 *    不重复入库、不重复触发 Agent。
 * 3. **Outbox after-commit** —— Agent 处理在事务提交后才异步触发(agent 看到的必然已落库)。
 * 4. **Agent 不参与请求生命周期** —— 用户发送永不被 Agent 阻塞。
 */
@Slf4j
@Service
public class MessageCoreService {

    private final ConversationService conversationService;
    private final MessageRepository messageRepository;
    private final PerceptionEngine perceptionEngine;
    private final CompanionEventBus eventBus;
    private final AgentRuntime agentRuntime;
    private final CompanionService companionService;
    private final com.luxera.companion.world.WorldEventEngine worldEventEngine;

    public MessageCoreService(ConversationService conversationService,
                              MessageRepository messageRepository, PerceptionEngine perceptionEngine,
                              CompanionEventBus eventBus, AgentRuntime agentRuntime,
                              CompanionService companionService,
                              com.luxera.companion.world.WorldEventEngine worldEventEngine) {
        this.conversationService = conversationService;
        this.messageRepository = messageRepository;
        this.perceptionEngine = perceptionEngine;
        this.eventBus = eventBus;
        this.agentRuntime = agentRuntime;
        this.companionService = companionService;
        this.worldEventEngine = worldEventEngine;
    }

    /**
     * 用户发送消息: 同步持久化 + 幂等 + Outbox → Agent。
     *
     * @return 规范化结果(含全部 canonical 消息, 按发送顺序)
     */
    @Transactional
    public SendResult send(String userId, String companionId, String conversationId,
                           List<SendItem> items) {
        companionService.requireOwned(userId, companionId);
        Conversation conv = conversationService.requireOwned(userId, conversationId);
        if (!conv.getCompanionId().equals(companionId)) {
            throw new IllegalArgumentException("会话与伴侣不匹配");
        }
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("消息不能为空");
        }

        List<Message> persisted = new ArrayList<>();
        List<Message> newMessages = new ArrayList<>();
        boolean anyNew = false;

        for (SendItem item : items) {
            if (item == null || item.getContent() == null || item.getContent().isBlank()) continue;
            String content = item.getContent().trim();

            // 幂等: 同会话同 clientMessageId → 返回已存在消息(不重复入库/不重复触发)
            if (item.getClientMessageId() != null && !item.getClientMessageId().isBlank()) {
                Message existing = messageRepository
                        .findByConversationIdAndClientMessageId(conversationId, item.getClientMessageId())
                        .orElse(null);
                if (existing != null) {
                    persisted.add(existing);
                    continue;
                }
            }

            // 同步落库(感知在请求线程完成, 消息立即成为事实)
            PerceptionEngine.Perception perception = perceptionEngine.perceive(content);
            Message m = conversationService.addMessage(conversationId, "user", content, perception,
                    false, null, null, null, item.getClientMessageId());
            persisted.add(m);
            newMessages.add(m);
            anyNew = true;

            // Outbox: 消息已持久化的事件(前端据此 temp → canonical)
            eventBus.publish(companionId, CompanionEventType.MESSAGE_CREATED, Map.of(
                    "messageId", m.getId(),
                    "conversationId", conversationId,
                    "clientMessageId", m.getClientMessageId() == null ? "" : m.getClientMessageId(),
                    "content", m.getContent(),
                    "status", com.luxera.companion.runtime.pipeline.MessageLifecycle.DELIVERED,
                    "at", m.getCreatedAt() == null ? "" : m.getCreatedAt().toString()));

            // §四十五: 用户消息是数字人世界中的一种事件(不是世界的全部)
            try {
                worldEventEngine.publish(companionId, com.luxera.companion.world.WorldEventEngine.TYPE_MESSAGE_CREATED,
                        com.luxera.companion.world.WorldEvent.SRC_COMMUNICATION,
                        m.getId(), conversationId,
                        Map.of("content", content), 0.6);
            } catch (Exception ignored) { }
        }

        if (persisted.isEmpty()) {
            throw new IllegalArgumentException("消息不能为空");
        }

        // Outbox → Agent: 事务提交后才异步处理(保证 Agent 读到的消息已提交)
        if (anyNew && !newMessages.isEmpty()) {
            final List<Message> toProcess = new ArrayList<>(newMessages);
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        agentRuntime.submit(userId, companionId, conversationId, toProcess);
                    }
                });
            } else {
                // 无事务上下文(理论不发生): 直接异步
                agentRuntime.submit(userId, companionId, conversationId, toProcess);
            }
        }

        return new SendResult(com.luxera.companion.runtime.pipeline.MessageLifecycle.DELIVERED, persisted);
    }

    /** 发送条目(前端乐观消息的幂等键) */
    @Data
    public static class SendItem {
        private String content;
        private String clientMessageId;
    }

    /** 规范化发送结果 */
    @Data
    public static class SendResult {
        private final String status;
        private final List<Message> messages;

        public SendResult(String status, List<Message> messages) {
            this.status = status;
            this.messages = messages;
        }

        public Message last() {
            return messages.isEmpty() ? null : messages.get(messages.size() - 1);
        }
    }
}
