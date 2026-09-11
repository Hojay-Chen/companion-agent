package com.luxera.companion.runtime.pipeline;

import com.luxera.companion.contracts.api.MessageView;
import com.luxera.companion.conversation.Message;
import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionRepository;
import com.luxera.companion.runtime.ScheduledAction;
import com.luxera.companion.runtime.ScheduledActionRepository;
import com.luxera.companion.runtime.ScheduledActionService;
import com.luxera.companion.state.AgentState;
import com.luxera.companion.state.AgentStateRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V10 §13 复查预筛集成测试:
 * 已读未回复查时, 她"疲惫"(低能量) → 决策策略判定 DelayReply → 再延后排程,
 * 不打扰认知(不进入 Brain 回复路径)。
 */
@ActiveProfiles("test")
@SpringBootTest
class PendingMessageReevaluationJobTest {

    @Autowired
    PendingMessageReevaluationJob job;
    @Autowired
    PendingMessageService pendingMessageService;
    @Autowired
    CompanionRepository companionRepository;
    @Autowired
    AgentStateRepository agentStateRepository;
    @Autowired
    ScheduledActionRepository scheduledActionRepository;

    private String companionId;
    private final String userId = "review-user";

    @BeforeEach
    void setUp() {
        companionId = UUID.randomUUID().toString();
        Companion c = new Companion();
        c.setId(companionId);
        c.setUserId(userId);
        c.setName("小满");
        c.setGender("female");
        companionRepository.save(c);

        AgentState state = new AgentState();
        state.setId(companionId);   // 主键复用 companionId(便于清理)
        state.setCompanionId(companionId);
        state.setEnergy(0.25);   // 疲惫
        state.setStress(0.5);
        agentStateRepository.save(state);
    }

    @AfterEach
    void tearDown() {
        for (ScheduledAction a : scheduledActionRepository.findByCompanionIdAndStatus(companionId,
                ScheduledAction.STATUS_PENDING)) {
            scheduledActionRepository.delete(a);
        }
        for (PendingMessageState p : pendingMessageService.pendingFor(companionId)) {
            pendingMessageService.markExpired(p.getMessageId());
        }
        agentStateRepository.deleteById(companionId);
        companionRepository.deleteById(companionId);
    }

    @Test
    void exhaustedReviewDefersWithoutReplying() {
        // 一条已读未回的消息(复查到期)
        MessageView m = MessageView.builder()
                .id(UUID.randomUUID().toString())
                .conversationId(companionId)
                .senderType("user")
                .content("上次说的事你考虑得怎么样了")
                .deliveryStatus(MessageLifecycle.READ)
                .createdAt(LocalDateTime.now().minusHours(3))
                .build();
        pendingMessageService.defer(m, companionId, userId, "暂时不想回",
                LocalDateTime.now().minusMinutes(1));

        job.run();

        // 疲惫 → 策略延后: 不进入回复路径, 重新排程复查
        List<ScheduledAction> rescheduled = scheduledActionRepository.findByCompanionIdAndStatus(
                companionId, ScheduledAction.STATUS_PENDING);
        assertTrue(rescheduled.stream()
                        .anyMatch(a -> ScheduledActionService.RE_EVALUATE_MESSAGE.equals(a.getActionType())),
                "疲惫时应重新排程复查(延后), 而非立即回复");
    }

    @Test
    void pendingStillTrackedAfterDefer() {
        MessageView m = MessageView.builder()
                .id(UUID.randomUUID().toString())
                .conversationId(companionId)
                .senderType("user")
                .content("在吗")
                .deliveryStatus(MessageLifecycle.READ)
                .createdAt(LocalDateTime.now().minusHours(2))
                .build();
        PendingMessageState pending = pendingMessageService.defer(m, companionId, userId, "暂时不想回",
                LocalDateTime.now().minusMinutes(1));

        job.run();
        // 延后后消息仍在复查队列(pending 未过期、未回复)
        PendingMessageState after = pendingMessageService.pendingFor(companionId).stream()
                .filter(p -> p.getMessageId().equals(m.getId())).findFirst().orElse(null);
        assertNotNull(after, "延后复查后 pending 记录应保留");
        assertEquals(false, after.isReplied());
    }
}
