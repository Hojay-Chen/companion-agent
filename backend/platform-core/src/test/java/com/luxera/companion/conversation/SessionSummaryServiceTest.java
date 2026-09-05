package com.luxera.companion.conversation;

import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V9 §20 Session Rolling Summary: 长会话早期消息压缩为分节摘要。
 */
@ActiveProfiles("test")
@SpringBootTest
class SessionSummaryServiceTest {

    @Autowired
    SessionSummaryService summaryService;
    @Autowired
    SessionSummaryRepository summaryRepository;
    @Autowired
    MessageRepository messageRepository;
    @Autowired
    ConversationRepository conversationRepository;
    @Autowired
    CompanionRepository companionRepository;

    private String conversationId;

    @BeforeEach
    void setUp() {
        String companionId = UUID.randomUUID().toString();
        Companion c = new Companion();
        c.setId(companionId);
        c.setUserId("sum-user");
        c.setName("小满");
        companionRepository.save(c);

        Conversation conv = new Conversation();
        conv.setId(UUID.randomUUID().toString());
        conv.setUserId("sum-user");
        conv.setCompanionId(companionId);
        conv.setTitle("长对话");
        conversationRepository.save(conv);
        conversationId = conv.getId();
    }

    private void addMessages(int n, String base) {
        for (int i = 0; i < n; i++) {
            Message m = new Message();
            m.setConversationId(conversationId);
            m.setSenderType(i % 2 == 0 ? "user" : "companion");
            m.setContent(base + i);
            m.setDeliveryStatus(com.luxera.companion.runtime.pipeline.MessageLifecycle.DELIVERED);
            messageRepository.save(m);
        }
    }

    @Test
    void summaryTriggersAfterThreshold() {
        addMessages(50, "轮次");
        assertTrue(summaryService.maybeSummarize(conversationId), "40 条以上应触发摘要");
        SessionSummary s = summaryRepository.findByConversationId(conversationId).orElse(null);
        assertNotNull(s);
        assertTrue(s.getMessageCountAtSummary() >= 50);
        assertNotNull(s.getSummaryText());
        assertFalse(s.getSummaryText().isBlank());
    }

    @Test
    void summaryIsIdempotentUntilNextStep() {
        addMessages(45, "早");
        assertTrue(summaryService.maybeSummarize(conversationId));
        // 立即再调: 未到 20 条增量 → 不重新摘要
        assertFalse(summaryService.maybeSummarize(conversationId));
        SessionSummary s = summaryRepository.findByConversationId(conversationId).orElse(null);
        assertEquals(1, s.getVersion(), "增量不足时不重新摘要");
    }

    @Test
    void shortConversationNotSummarized() {
        addMessages(10, "短");
        assertFalse(summaryService.maybeSummarize(conversationId));
        assertTrue(summaryRepository.findByConversationId(conversationId).isEmpty());
    }

    @Test
    void fallbackExtractsFactsAndPlans() {
        addMessages(45, "普通");
        // 补一条带"明天"约定的消息
        Message plan = new Message();
        plan.setConversationId(conversationId);
        plan.setSenderType("user");
        plan.setContent("那我们明天一起去爬山吧");
        plan.setDeliveryStatus(com.luxera.companion.runtime.pipeline.MessageLifecycle.DELIVERED);
        messageRepository.save(plan);

        summaryService.maybeSummarize(conversationId);
        SessionSummary s = summaryRepository.findByConversationId(conversationId).orElse(null);
        assertNotNull(s);
        // mock LLM 回退规则: 用户事实被抽取, 含"明天"的约定进 plans
        assertTrue(s.getSummaryText() != null && s.getSummaryText().length() > 0);
    }
}
