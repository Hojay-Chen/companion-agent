package com.luxera.companion.conversation;

import com.luxera.companion.agent.PerceptionEngine;
import com.luxera.companion.common.BusinessException;
import com.luxera.companion.persona.Companion;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class ConversationService {

    private final ConversationRepository convRepo;
    private final MessageRepository msgRepo;
    private final ConversationParticipantService participantService;
    private final com.luxera.companion.persona.CompanionRepository companionRepo;

    public ConversationService(ConversationRepository convRepo, MessageRepository msgRepo,
                               ConversationParticipantService participantService,
                               com.luxera.companion.persona.CompanionRepository companionRepo) {
        this.convRepo = convRepo;
        this.msgRepo = msgRepo;
        this.participantService = participantService;
        this.companionRepo = companionRepo;
    }

    /** 首次打开聊天时创建带问候语的初始会话 */
    @Transactional
    public Conversation getOrCreateGreeting(String userId, String companionId, Companion companion) {
        List<Conversation> list = convRepo.findByUserIdAndCompanionIdOrderByLastMessageAtDesc(userId, companionId);
        if (!list.isEmpty()) {
            Conversation existing = list.get(0);
            seedParticipants(existing, userId, companion);
            return existing;
        }
        Conversation conv = new Conversation();
        conv.setUserId(userId);
        conv.setCompanionId(companionId);
        conv.setTitle("初见 · " + companion.getName());
        convRepo.save(conv);
        seedParticipants(conv, userId, companion);
        if (companion.getGreeting() != null && !companion.getGreeting().isBlank()) {
            Message msg = new Message();
            msg.setConversationId(conv.getId());
            msg.setSenderType("companion");
            msg.setContent(companion.getGreeting());
            msg = msgRepo.save(msg);
            conv.setMessageCount(1);
            conv.setLastMessageAt(msg.getCreatedAt());
            convRepo.save(conv);
        }
        return conv;
    }

    @Transactional
    public Conversation create(String userId, String companionId, String title) {
        Conversation conv = new Conversation();
        conv.setUserId(userId);
        conv.setCompanionId(companionId);
        conv.setTitle(title == null || title.isBlank() ? "新的对话" : title);
        convRepo.save(conv);
        // 会话参与者(Agent + User), 幂等
        companionRepo.findById(companionId).ifPresent(c -> seedParticipants(conv, userId, c));
        return conv;
    }

    /** §五十二: 注册会话参与者(群聊数据模型的地基) */
    private void seedParticipants(Conversation conv, String userId, Companion companion) {
        try {
            participantService.seed(conv, userId, companion);
        } catch (Exception e) {
            // 参与者注册失败不影响会话主流程
        }
    }

    @Transactional(readOnly = true)
    public List<Conversation> list(String userId, String companionId) {
        return convRepo.findByUserIdAndCompanionIdOrderByLastMessageAtDesc(userId, companionId);
    }

    @Transactional(readOnly = true)
    public Conversation requireOwned(String userId, String conversationId) {
        Conversation conv = convRepo.findById(conversationId)
                .orElseThrow(() -> new javax.persistence.EntityNotFoundException("会话不存在"));
        if (!conv.getUserId().equals(userId)) {
            throw BusinessException.badRequest("无权访问该会话");
        }
        return conv;
    }

    @Transactional(readOnly = true)
    public List<Message> messages(String conversationId) {
        return msgRepo.findByConversationIdOrderByCreatedAtAsc(conversationId);
    }

    /** 最近 N 条消息(升序) */
    @Transactional(readOnly = true)
    public List<Message> recentMessages(String conversationId, int limit) {
        List<Message> desc = msgRepo.findTop200ByConversationIdOrderByCreatedAtDesc(conversationId);
        List<Message> asc = new ArrayList<>(desc);
        java.util.Collections.reverse(asc);
        if (asc.size() > limit) {
            asc = new ArrayList<>(asc.subList(asc.size() - limit, asc.size()));
        }
        return asc;
    }

    /** Message Lifecycle: 更新消息投递状态(DELIVERED/READ/DEFERRED/IGNORED) */
    @Transactional
    public void updateDeliveryStatus(String messageId, String status) {
        msgRepo.findById(messageId).ifPresent(m -> {
            m.setDeliveryStatus(status);
            msgRepo.save(m);
        });
    }

    @Transactional
    public Message addMessage(String conversationId, String senderType, String content,
                              PerceptionEngine.Perception perception, boolean proactive) {
        return addMessage(conversationId, senderType, content, perception, proactive, null, null, null);
    }

    /** 带会话归属与消息类型 */
    @Transactional
    public Message addMessage(String conversationId, String senderType, String content,
                              PerceptionEngine.Perception perception, boolean proactive,
                              String messageKind, String sessionId, String exchangeId) {
        return addMessage(conversationId, senderType, content, perception, proactive,
                messageKind, sessionId, exchangeId, null);
    }

    /** 带客户端幂等键的消息落库(用户消息同步持久化核心) */
    @Transactional
    public Message addMessage(String conversationId, String senderType, String content,
                              PerceptionEngine.Perception perception, boolean proactive,
                              String messageKind, String sessionId, String exchangeId,
                              String clientMessageId) {
        Conversation conv = convRepo.findById(conversationId)
                .orElseThrow(() -> new javax.persistence.EntityNotFoundException("会话不存在"));
        Message m = new Message();
        m.setConversationId(conversationId);
        m.setSenderType(senderType);
        m.setContent(content);
        if (perception != null) {
            m.setIntent(perception.intent());
            m.setEmotion(perception.emotion());
            m.setTopic(perception.topic());
        }
        m.setProactive(proactive);
        if (messageKind != null) m.setMessageKind(messageKind);
        if (sessionId != null) m.setSessionId(sessionId);
        if (exchangeId != null) m.setExchangeId(exchangeId);
        if (clientMessageId != null) m.setClientMessageId(clientMessageId);
        m = msgRepo.save(m);
        conv.setMessageCount(conv.getMessageCount() + 1);
        conv.setLastMessageAt(m.getCreatedAt());
        convRepo.save(conv);
        return m;
    }
}
