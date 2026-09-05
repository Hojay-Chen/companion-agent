package com.luxera.companion.runtime.pipeline;

import com.luxera.companion.conversation.Conversation;
import com.luxera.companion.conversation.ConversationService;
import com.luxera.companion.conversation.Message;
import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §54 Communication Friction 测试:
 * - defer 记录"看到了没回"(SEEN_NO_REPLY)
 * - 摩擦类型可指定(想回忘了/回一半被打断)
 * - noteWantedToReply 更新摩擦类型 + 复查计数 + 延后复查
 * - noteReviewed 递增复查次数
 * - markExpired 放下这件事(人偶尔会忘记)
 */
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "app.scheduler.thread-maintenance-cron=0 0 0 1 1 *",
        "app.scheduler.unfinished-thought-cron=0 0 0 1 1 *",
        "app.scheduler.event-simulation-cron=0 0 0 1 1 *",
        "app.scheduler.life-tick-cron=0 0 0 1 1 *"
})
class CommunicationFrictionTest {

    @Autowired
    PendingMessageService pendingService;
    @Autowired
    PendingMessageStateRepository pendingRepo;
    @Autowired
    ConversationService conversationService;
    @Autowired
    CompanionRepository companionRepository;

    private String companionId;
    private String userId;
    private String conversationId;

    @BeforeEach
    void setUp() {
        companionId = UUID.randomUUID().toString();
        userId = "friction-user-" + UUID.randomUUID().toString().substring(0, 6);
        Conversation conv = new Conversation();
        conv.setId(UUID.randomUUID().toString());
        conversationId = conv.getId();
        Companion cm = new Companion();
        cm.setId(companionId);
        cm.setUserId(userId);
        cm.setName("小满");
        cm.setGender("female");
        companionRepository.save(cm);
    }

    private Message send(String content) {
        Message m = new Message();
        m.setId(UUID.randomUUID().toString());
        m.setConversationId(conversationId);
        m.setSenderType("user");
        m.setContent(content);
        return m;
    }

    @Test
    void deferRecordsSeenNoReplyFriction() {
        Message m = send("你最近怎么都不理我");
        var p = pendingService.defer(m, companionId, userId, "在忙", LocalDateTime.now().plusHours(1));
        assertEquals("SEEN_NO_REPLY", p.getFrictionType());
        assertEquals(PendingMessageState.STATUS_PENDING, p.getStatus());
        assertEquals(0, p.getReviewCount());
    }

    @Test
    void deferCanSpecifyFrictionType() {
        Message m = send("我有点事想跟你说");
        var p = pendingService.defer(m, companionId, userId, "回复打到一半被打断",
                LocalDateTime.now().plusHours(1), "REPLIED_HALFWAY");
        assertEquals("REPLIED_HALFWAY", p.getFrictionType());
    }

    @Test
    void noteWantedToReplyUpdatesFrictionAndDelays() {
        Message m = send("明天有空吗");
        var p = pendingService.defer(m, companionId, userId, "暂时不想回",
                LocalDateTime.now().plusHours(1));
        pendingService.noteWantedToReply(p.getId());
        var updated = pendingService.findByMessageId(m.getId()).orElseThrow();
        assertEquals("WANTED_TO_REPLY_FORGOT", updated.getFrictionType());
        assertEquals(1, updated.getReviewCount());
        assertTrue(updated.getNextReviewAt().isAfter(LocalDateTime.now()),
                "想回忘了 → 延后复查(人真的会忘)");
    }

    @Test
    void markExpiredLetsItGo() {
        Message m = send("其实也没什么");
        var p = pendingService.defer(m, companionId, userId, "不重要",
                LocalDateTime.now().plusHours(1));
        pendingService.markExpired(m.getId());
        assertEquals(PendingMessageState.STATUS_EXPIRED,
                pendingService.findByMessageId(m.getId()).orElseThrow().getStatus(),
                "放下这件事(人偶尔会忘记回)");
    }
}
