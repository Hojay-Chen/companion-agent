package com.luxera.companion.runtime.pipeline;

import com.luxera.companion.conversation.ConversationService;
import com.luxera.companion.conversation.Message;
import com.luxera.companion.event.CompanionEventBus;
import com.luxera.companion.event.CompanionEventType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 消息投递服务(V5 §11): 消息生命周期的状态转换 + 事件发布。
 * 让"送达 / 通知 / 注意到 / 打开 / 读到 / 回复"各阶段可追踪。
 */
@Service
public class MessageDeliveryService {

    private final ConversationService conversationService;
    private final CompanionEventBus eventBus;

    public MessageDeliveryService(ConversationService conversationService, CompanionEventBus eventBus) {
        this.conversationService = conversationService;
        this.eventBus = eventBus;
    }

    @Transactional
    public void setStatus(String companionId, String messageId, String status) {
        conversationService.updateDeliveryStatus(messageId, status);
        String event = MessageLifecycle.READ.equals(status) ? CompanionEventType.MESSAGE_READ
                : CompanionEventType.USER_MESSAGE_STATUS;
        eventBus.publish(companionId, event, Map.of("messageId", messageId, "status", status,
                "at", LocalDateTime.now().toString()));
    }

    @Transactional
    public void delivered(String companionId, Message m) {
        conversationService.updateDeliveryStatus(m.getId(), MessageLifecycle.DELIVERED);
        eventBus.publish(companionId, CompanionEventType.USER_MESSAGE_STATUS,
                Map.of("messageId", m.getId(), "status", MessageLifecycle.DELIVERED));
    }

    @Transactional
    public void notified(String companionId, String messageId) {
        conversationService.updateDeliveryStatus(messageId, MessageLifecycle.NOTIFIED);
        eventBus.publish(companionId, CompanionEventType.USER_MESSAGE_STATUS,
                Map.of("messageId", messageId, "status", MessageLifecycle.NOTIFIED));
    }

    @Transactional
    public void noticed(String companionId, String messageId) {
        conversationService.updateDeliveryStatus(messageId, MessageLifecycle.NOTICED);
        eventBus.publish(companionId, CompanionEventType.USER_MESSAGE_STATUS,
                Map.of("messageId", messageId, "status", MessageLifecycle.NOTICED));
    }

    @Transactional
    public void checked(String companionId, String messageId) {
        conversationService.updateDeliveryStatus(messageId, MessageLifecycle.CHECKED);
        eventBus.publish(companionId, CompanionEventType.USER_MESSAGE_STATUS,
                Map.of("messageId", messageId, "status", MessageLifecycle.CHECKED));
    }

    @Transactional
    public void read(String companionId, String messageId) {
        conversationService.updateDeliveryStatus(messageId, MessageLifecycle.READ);
        eventBus.publish(companionId, CompanionEventType.MESSAGE_READ, Map.of("messageId", messageId));
    }

    @Transactional
    public void responded(String companionId, String messageId) {
        conversationService.updateDeliveryStatus(messageId, MessageLifecycle.RESPONDED);
    }

    @Transactional
    public void deferred(String companionId, String messageId) {
        conversationService.updateDeliveryStatus(messageId, MessageLifecycle.DEFERRED);
    }

    @Transactional
    public void ignored(String companionId, String messageId) {
        conversationService.updateDeliveryStatus(messageId, MessageLifecycle.IGNORED);
    }
}
