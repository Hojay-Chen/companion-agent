package com.luxera.companion.runtime;

import com.luxera.companion.conversation.Conversation;
import com.luxera.companion.conversation.ConversationRepository;
import com.luxera.companion.conversation.Message;
import com.luxera.companion.conversation.MessageRepository;
import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionRepository;
import com.luxera.companion.runtime.pipeline.MessageLifecycle;
import com.luxera.companion.sleep.SleepModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V9 Reality 一致性: 她睡着时收到的消息, 醒来后补处理(看到全部夜间消息)。
 */
@ActiveProfiles("test")
@SpringBootTest
class WakeupCatchUpTest {

    @Autowired
    WakeupCatchUpService wakeupCatchUpService;
    @Autowired
    MessageRepository messageRepository;
    @Autowired
    ConversationRepository conversationRepository;
    @Autowired
    CompanionRepository companionRepository;
    @Autowired
    SleepModel sleepModel;
    @Autowired
    com.luxera.companion.sleep.CircadianStateRepository circadianRepository;

    private String companionId;
    private String conversationId;
    private String userId = "wake-user";

    @BeforeEach
    void setUp() {
        companionId = UUID.randomUUID().toString();
        Companion c = new Companion();
        c.setId(companionId);
        c.setUserId(userId);
        c.setName("小满");
        companionRepository.save(c);

        Conversation conv = new Conversation();
        conv.setId(UUID.randomUUID().toString());
        conv.setUserId(userId);
        conv.setCompanionId(companionId);
        conv.setTitle("测试");
        conversationRepository.save(conv);
        conversationId = conv.getId();

        var circ = sleepModel.getOrCreate(companionId, LocalDateTime.now());
        circ.setSleeping(false);
        circ.setSleepPressure(0.3);
        circadianRepository.save(circ);
    }

    @Test
    void justWokeDetection() {
        assertTrue(wakeupCatchUpService.justWoke(LocalDateTime.now().minusMinutes(2), LocalDateTime.now()));
        assertFalse(wakeupCatchUpService.justWoke(LocalDateTime.now().minusHours(2), LocalDateTime.now()));
        assertFalse(wakeupCatchUpService.justWoke(null, LocalDateTime.now()));
    }

    @Test
    void unreadNightMessagesAreCaughtUp() {
        // 睡眠期间收到 3 条未读消息(模拟睡着时)
        for (int i = 0; i < 3; i++) {
            Message m = new Message();
            m.setConversationId(conversationId);
            m.setSenderType("user");
            m.setContent("夜间消息" + i);
            m.setDeliveryStatus(MessageLifecycle.DELIVERED);
            m.setCreatedAt(LocalDateTime.now().minusMinutes(30));
            messageRepository.save(m);
        }

        // 刚醒 → 补处理触发(异步处理消息, 消息被提交给 Agent)
        int caught = wakeupCatchUpService.catchUp(companionId, userId, LocalDateTime.now());
        assertEquals(3, caught, "3 条夜间消息应全部补处理");
    }

    @Test
    void readMessagesNotCaughtUp() {
        Message read = new Message();
        read.setConversationId(conversationId);
        read.setSenderType("user");
        read.setContent("已经看过的");
        read.setDeliveryStatus(MessageLifecycle.READ);
        messageRepository.save(read);

        assertEquals(0, wakeupCatchUpService.catchUp(companionId, userId, LocalDateTime.now()),
                "已读消息不补处理");
    }
}
