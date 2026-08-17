package com.luxera.companion.conversation;

import com.luxera.companion.person.Person;
import com.luxera.companion.person.PersonService;
import com.luxera.companion.persona.Companion;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * V8 §五十二: 会话参与者服务。
 * 创建会话时自动加入 Agent + User 两个参与者(一对一聊天即最小图)。
 */
@Service
public class ConversationParticipantService {

    private final ConversationParticipantRepository repo;
    private final PersonService personService;

    public ConversationParticipantService(ConversationParticipantRepository repo,
                                          PersonService personService) {
        this.repo = repo;
        this.personService = personService;
    }

    /** 会话创建后调用: 注册 Agent 与 User 参与者(幂等) */
    @Transactional
    public void seed(Conversation conv, String userId, Companion companion) {
        Person agent = personService.getOrCreateAgent(companion);
        Person user = personService.getOrCreateUser(userId);
        addIfMissing(conv.getId(), agent.getId(), ConversationParticipant.ROLE_AGENT, agent.getName());
        addIfMissing(conv.getId(), user.getId(), ConversationParticipant.ROLE_USER, user.getName());
    }

    @Transactional
    public void addIfMissing(String conversationId, String personId, String role, String displayName) {
        if (repo.existsByConversationIdAndPersonId(conversationId, personId)) return;
        ConversationParticipant p = new ConversationParticipant();
        p.setConversationId(conversationId);
        p.setPersonId(personId);
        p.setRole(role);
        p.setDisplayName(displayName);
        repo.save(p);
    }

    @Transactional(readOnly = true)
    public java.util.List<ConversationParticipant> participants(String conversationId) {
        return repo.findByConversationId(conversationId);
    }
}
