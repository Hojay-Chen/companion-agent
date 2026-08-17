package com.luxera.companion.conversation;

import com.luxera.companion.person.Person;
import com.luxera.companion.person.PersonRepository;
import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §五十二 ConversationParticipant 测试:
 * 会话创建后自动注册 Agent + User 两个参与者(一对一聊天即最小参与者图;
 * 未来群聊 = 多参与者 + 各自关系)。
 */
@ActiveProfiles("test")
@SpringBootTest
class ConversationParticipantTest {

    @Autowired
    ConversationService conversationService;
    @Autowired
    ConversationParticipantRepository participantRepository;
    @Autowired
    CompanionRepository companionRepository;
    @Autowired
    PersonRepository personRepository;

    private String companionId;
    private String userId = "cp-user";

    @BeforeEach
    void setUp() {
        companionId = UUID.randomUUID().toString();
        Companion c = new Companion();
        c.setId(companionId);
        c.setUserId(userId);
        c.setName("林夏");
        c.setGender("female");
        companionRepository.save(c);
    }

    @Test
    void conversationSeedsAgentAndUserParticipants() {
        Conversation conv = conversationService.create(userId, companionId, "测试会话");

        List<ConversationParticipant> participants = participantRepository.findByConversationId(conv.getId());
        assertEquals(2, participants.size(), "一对一会话应有 Agent + User 两个参与者");

        boolean hasAgent = participants.stream().anyMatch(p ->
                ConversationParticipant.ROLE_AGENT.equals(p.getRole()) && companionId.equals(p.getPersonId()));
        boolean hasUser = participants.stream().anyMatch(p ->
                ConversationParticipant.ROLE_USER.equals(p.getRole()) && userId.equals(p.getPersonId()));
        assertTrue(hasAgent, "应有 Agent 参与者");
        assertTrue(hasUser, "应有 User 参与者");
    }

    @Test
    void participantsAreIdempotent() {
        Conversation conv = conversationService.create(userId, companionId, "幂等测试");
        conversationService.create(userId, companionId, "另一个会话");
        // 同一 (conversation, person) 只出现一次
        for (ConversationParticipant p : participantRepository.findByConversationId(conv.getId())) {
            assertEquals(1, participantRepository.findByConversationId(conv.getId()).stream()
                    .filter(x -> x.getPersonId().equals(p.getPersonId())).count());
        }
    }
}
