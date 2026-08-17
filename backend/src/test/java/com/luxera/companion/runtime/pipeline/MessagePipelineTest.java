package com.luxera.companion.runtime.pipeline;

import com.luxera.companion.agent.PerceptionEngine;
import com.luxera.companion.conversation.Conversation;
import com.luxera.companion.conversation.ConversationService;
import com.luxera.companion.conversation.Message;
import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionRepository;
import com.luxera.companion.runtime.AgentTraceRepository;
import com.luxera.companion.runtime.AgentTraceService;
import com.luxera.companion.runtime.ScheduledAction;
import com.luxera.companion.runtime.ScheduledActionRepository;
import com.luxera.companion.runtime.ScheduledActionService;
import com.luxera.companion.state.AgentState;
import com.luxera.companion.state.AgentStateService;
import org.junit.jupiter.api.AfterEach;
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
 * 消息流水线集成测试(真实 DB + 真实 LLM):
 * 白天(LEISURE/手机在手)→ 会注意到; 夜间(SLEEP/勿扰)→ 注意不到。
 * 行为非确定性(LLM), 断言约束在 的结构性不变量上。
 */
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "app.scheduler.event-simulation-cron=0 0 0 1 1 *",
        "app.scheduler.life-tick-cron=0 0 0 1 1 *",
        "app.scheduler.proactive-cron=0 0 0 1 1 *",
        "app.scheduler.thought-maintenance-cron=0 0 0 1 1 *",
        "app.scheduler.emotion-maintenance-cron=0 0 0 1 1 *",
        "app.scheduler.memory-consolidation-cron=0 0 0 1 1 *",
        "app.scheduler.daily-reflection-cron=0 0 0 1 1 *",
        "app.scheduler.weekly-reflection-cron=0 0 0 1 1 *",
        "app.scheduler.birthday-cron=0 0 0 1 1 *",
        "app.scheduler.open-loop-cron=0 0 0 1 1 *",
        "app.scheduler.sleep-tick-cron=0 0 0 1 1 *",
        "app.scheduler.phone-tick-cron=0 0 0 1 1 *"
})
class MessagePipelineTest {

    @Autowired
    MessagePipeline pipeline;
    @Autowired
    ConversationService conversationService;
    @Autowired
    CompanionRepository companionRepository;
    @Autowired
    AgentStateService agentStateService;
    @Autowired
    PerceptionEngine perceptionEngine;
    @Autowired
    PendingMessageService pendingMessageService;
    @Autowired
    ScheduledActionService scheduledActionService;
    @Autowired
    AgentTraceRepository traceRepository;
    @Autowired
    ScheduledActionRepository scheduledActionRepository;
    @Autowired
    com.luxera.companion.agent.CompanionRuntime runtime;
    @Autowired
    com.luxera.companion.runtime.agent.expression.ExpressionAgent expressionAgent;
    @Autowired
    com.luxera.companion.sleep.SleepModel sleepModel;

    /** 周日 20:00 —— 周末休闲, 手机在手 */
    private static final LocalDateTime DAYTIME = LocalDateTime.of(2026, 8, 16, 20, 0);
    /** 周日 01:00 —— 睡觉, 勿扰 */
    private static final LocalDateTime NIGHT = LocalDateTime.of(2026, 8, 16, 1, 0);

    private String userId;
    private String companionId;
    private String conversationId;

    @BeforeEach
    void setUp() {
        userId = "test-user-" + UUID.randomUUID().toString().substring(0, 8);
        companionId = UUID.randomUUID().toString();
        Companion c = new Companion();
        c.setId(companionId);
        c.setUserId(userId);
        c.setName("小满");
        c.setGender("female");
        companionRepository.save(c);
        Conversation conv = conversationService.create(userId, companionId, "测试会话");
        conversationId = conv.getId();
        agentStateService.getOrCreate(companionId);
    }

    @AfterEach
    void tearDown() {
        // 清理测试数据(按 companionId 删除相关排程/待复查)
        for (var a : scheduledActionRepository.findByCompanionIdAndStatus(companionId, ScheduledAction.STATUS_PENDING)) {
            scheduledActionRepository.delete(a);
        }
        for (var p : pendingMessageService.pendingFor(companionId)) {
            pendingMessageRepositoryDelete(p.getId());
        }
        companionRepository.deleteById(companionId);
    }

    // 简化: pending repo 通过服务删除
    private void pendingMessageRepositoryDelete(String id) {
        var repo = pendingMessageRepository();
        repo.findById(id).ifPresent(repo::delete);
    }

    @Autowired
    PendingMessageStateRepository pendingRepo;

    private PendingMessageStateRepository pendingMessageRepository() {
        return pendingRepo;
    }

    private Message send(String content) {
        return conversationService.addMessage(conversationId, "user", content,
                perceptionEngine.perceive(content), false);
    }

    @Test
    void nightMessageIsNotNoticed() {
        // 睡眠是 emergent —— 先让她入睡(睡眠压力累积 + 手动入睡), 再验证消息不被注意到
        LocalDateTime nightTime = NIGHT;
        sleepModel.fallAsleep(companionId, nightTime.minusMinutes(10), "NATURAL");
        Message m = send("在吗?我有话想跟你说");
        MessagePipeline.PipelineResult r = pipeline.process(userId, companionId, conversationId,
                List.of(m), m.getContent(), perceptionEngine.perceive(m.getContent()), nightTime);
        assertEquals(MessagePipeline.PipelineResult.Outcome.IGNORE_NOT_NOTICED, r.outcome(),
                "睡着+勿扰 → 她根本没注意到");
    }

    @Test
    void daytimeEmotionalMessageIsNoticed() {
        Message m = send("我今天好难过,项目被砍了,有点撑不住");
        MessagePipeline.PipelineResult r = pipeline.process(userId, companionId, conversationId,
                List.of(m), m.getContent(), perceptionEngine.perceive(m.getContent()), DAYTIME);
        assertNotEquals(MessagePipeline.PipelineResult.Outcome.IGNORE_NOT_NOTICED, r.outcome(),
                "白天休闲+手机在手 → 至少会被注意到");
        assertNotNull(r.emotion(), "EmotionAgent 应有评估结果");
    }

    @Test
    void emotionStateIsUpdatedViaReducer() {
        Message m = send("你怎么这么烦,跟你说话真没意思");
        AgentState before = agentStateService.get(companionId);
        double beforeHurt = before.getHurt() + before.getAnger() + before.getSadness();
        MessagePipeline.PipelineResult r = pipeline.process(userId, companionId, conversationId,
                List.of(m), m.getContent(), perceptionEngine.perceive(m.getContent()), DAYTIME);
        if (r.isIgnored()) return; // 可能忽略, 此时未必有情绪变化
        AgentState after = agentStateService.get(companionId);
        double afterNegative = after.getHurt() + after.getAnger() + after.getSadness() + after.getAnxiety();
        // 冲突消息应增加负面情绪(或至少不降低)
        assertTrue(afterNegative >= beforeHurt - 0.01,
                "负面情绪应通过 Reducer 累积: before=" + beforeHurt + " after=" + afterNegative);
    }

    @Test
    void deferCreatesPendingStateAndSchedule() {
        // 构造一个较可能 DEFER 的场景: 睡前疲惫(但用白天 now 保证能注意到)
        Message m = send("你怎么这么烦,跟你说话真没意思");
        MessagePipeline.PipelineResult r = pipeline.process(userId, companionId, conversationId,
                List.of(m), m.getContent(), perceptionEngine.perceive(m.getContent()), DAYTIME);
        if (!r.isDeferred()) {
            // 若 Brain 决定回复, 该场景同样合法 —— 只验证已读状态
            assertEquals(MessagePipeline.PipelineResult.Outcome.REPLY, r.outcome());
            return;
        }
        assertFalse(pendingMessageService.pendingFor(companionId).isEmpty(),
                "DEFER 后应有待复查消息");
        assertFalse(scheduledActionService.pending(companionId).isEmpty(),
                "DEFER 后应有排程复查");
    }

    @Test
    void tracesAreRecordedForAgents() {
        Message m = send("我今天好难过,项目被砍了");
        pipeline.process(userId, companionId, conversationId,
                List.of(m), m.getContent(), perceptionEngine.perceive(m.getContent()), DAYTIME);
        var traces = traceRepository.findTop50ByCompanionIdOrderByCreatedAtDesc(companionId);
        assertFalse(traces.isEmpty(), "应有 Agent 运行痕迹");
        assertTrue(traces.stream().anyMatch(t -> "emotion".equals(t.getAgentName())),
                "应有 emotion Agent 痕迹");
        assertTrue(traces.stream().anyMatch(t -> "brain".equals(t.getAgentName())),
                "应有 brain Agent 痕迹");
    }

    @Test
    void messageLifecycleReachesNoticedOrBeyond() {
        Message m = send("我今天好难过,项目被砍了");
        pipeline.process(userId, companionId, conversationId,
                List.of(m), m.getContent(), perceptionEngine.perceive(m.getContent()), DAYTIME);
        String status = conversationService.messages(conversationId).stream()
                .filter(x -> x.getId().equals(m.getId()))
                .findFirst().orElseThrow().getDeliveryStatus();
        // 注意到后至少 NOTICED; REPLY 路径推进到 READ/DEFERRED
        assertTrue(List.of(MessageLifecycle.NOTICED, MessageLifecycle.CHECKED,
                        MessageLifecycle.READ, MessageLifecycle.DEFERRED, MessageLifecycle.RESPONDED)
                        .contains(status),
                "生命周期应推进到 NOTICED 之后, 当前: " + status);
    }

    @Test
    void replyPathProducesGeneratedMessage() {
        // 触发一条白天消息, 若 Brain 决定回复 → 走 Expression + 生成, 验证回复文本落库
        Message m = send("我今天好难过,项目被砍了");
        MessagePipeline.PipelineResult r = pipeline.process(userId, companionId, conversationId,
                List.of(m), m.getContent(), perceptionEngine.perceive(m.getContent()), DAYTIME);
        if (!r.shouldReply() || r.brainDecision() == null) {
            // DEFER 也是合法行为, 只验证存在性
            assertTrue(r.isDeferred() || r.shouldReply(), "应进入回复或延后: " + r.outcome());
            return;
        }
        var decision = r.brainDecision().baseline();
        var expr = expressionAgent.execute(new com.luxera.companion.runtime.agent.expression.ExpressionContext(
                companionId, userId, m.getContent(), "respond", "有点难过",
                "温柔独立", "close", 0.5, "休闲",
                List.of("用户: " + m.getContent()), 0.6, 0.4, decision));
        String hint = "语气 " + expr.strategy().tone() + ", 直接 " + Math.round(expr.strategy().directness() * 100) + "%";
        var outcome = runtime.generate(userId, companionId, conversationId, m.getId(),
                m.getContent(), conversationService.recentMessages(conversationId, 30), null, decision, hint);
        assertNotNull(outcome.reply());
        assertFalse(outcome.reply().isBlank(), "生成回复不应为空");
        // 生成后模拟控制器落库(addMessage), 验证回复可写入会话
        Message sent = conversationService.addMessage(conversationId, "companion", outcome.reply(), null, false);
        assertNotNull(sent.getId());
    }
}
