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

    public ConversationService(ConversationRepository convRepo, MessageRepository msgRepo) {
        this.convRepo = convRepo;
        this.msgRepo = msgRepo;
    }

    /** 首次打开聊天时创建带问候语的初始会话 */
    @Transactional
    public Conversation getOrCreateGreeting(String userId, String companionId, Companion companion) {
        List<Conversation> list = convRepo.findByUserIdAndCompanionIdOrderByLastMessageAtDesc(userId, companionId);
        if (!list.isEmpty()) {
            return list.get(0);
        }
        Conversation conv = new Conversation();
        conv.setUserId(userId);
        conv.setCompanionId(companionId);
        conv.setTitle("初见 · " + companion.getName());
        convRepo.save(conv);
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
        return convRepo.save(conv);
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

    @Transactional
    public Message addMessage(String conversationId, String senderType, String content,
                              PerceptionEngine.Perception perception, boolean proactive) {
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
        m = msgRepo.save(m);
        conv.setMessageCount(conv.getMessageCount() + 1);
        conv.setLastMessageAt(m.getCreatedAt());
        convRepo.save(conv);
        return m;
    }
}
