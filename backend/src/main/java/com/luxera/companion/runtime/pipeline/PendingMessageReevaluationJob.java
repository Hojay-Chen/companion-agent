package com.luxera.companion.runtime.pipeline;

import com.luxera.companion.agent.CompanionRuntime;
import com.luxera.companion.agent.CompanionSchedule;
import com.luxera.companion.agent.PerceptionEngine;
import com.luxera.companion.appraisal.AppraisalService;
import com.luxera.companion.behavior.DrivesService;
import com.luxera.companion.conversation.ConversationService;
import com.luxera.companion.conversation.Message;
import com.luxera.companion.event.CompanionEventBus;
import com.luxera.companion.event.CompanionEventType;
import com.luxera.companion.interaction.InteractionDecision;
import com.luxera.companion.interaction.InteractionPolicyEngine;
import com.luxera.companion.persona.CompanionService;
import com.luxera.companion.phone.PhoneStateService;
import com.luxera.companion.relationship.RelationshipService;
import com.luxera.companion.runtime.AgentTraceService;
import com.luxera.companion.runtime.ScheduledActionService;
import com.luxera.companion.runtime.agent.brain.BrainAgent;
import com.luxera.companion.runtime.agent.brain.BrainContext;
import com.luxera.companion.runtime.agent.brain.BrainDecision;
import com.luxera.companion.state.AgentStateService;
import com.luxera.companion.state.AvailabilityService;
import com.luxera.companion.state.CompanionAvailability;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 已读不回复查 Job(V5 §79/§80): 到复查点的消息唤醒 Brain 重新评估。
 * 可能回复 / 继续冷处理 / 放下这件事 —— 这就是行为连续性。
 */
@Slf4j
@Component
public class PendingMessageReevaluationJob {

    private final PendingMessageService pendingService;
    private final BrainAgent brainAgent;
    private final CompanionRuntime runtime;
    private final ConversationService conversationService;
    private final MessageDeliveryService deliveryService;
    private final ScheduledActionService scheduledActionService;
    private final CompanionEventBus eventBus;
    private final CompanionService companionService;
    private final RelationshipService relationshipService;
    private final AgentStateService agentStateService;
    private final PhoneStateService phoneStateService;
    private final AvailabilityService availabilityService;
    private final CompanionSchedule schedule;
    private final InteractionPolicyEngine interactionPolicy;
    private final DrivesService drivesService;
    private final AgentTraceService traceService;
    private final PerceptionEngine perceptionEngine;

    public PendingMessageReevaluationJob(PendingMessageService pendingService, BrainAgent brainAgent,
                                         CompanionRuntime runtime, ConversationService conversationService,
                                         MessageDeliveryService deliveryService,
                                         ScheduledActionService scheduledActionService,
                                         CompanionEventBus eventBus, CompanionService companionService,
                                         RelationshipService relationshipService, AgentStateService agentStateService,
                                         PhoneStateService phoneStateService, AvailabilityService availabilityService,
                                         CompanionSchedule schedule, InteractionPolicyEngine interactionPolicy,
                                         DrivesService drivesService, AgentTraceService traceService,
                                         PerceptionEngine perceptionEngine) {
        this.pendingService = pendingService;
        this.brainAgent = brainAgent;
        this.runtime = runtime;
        this.conversationService = conversationService;
        this.deliveryService = deliveryService;
        this.scheduledActionService = scheduledActionService;
        this.eventBus = eventBus;
        this.companionService = companionService;
        this.relationshipService = relationshipService;
        this.agentStateService = agentStateService;
        this.phoneStateService = phoneStateService;
        this.availabilityService = availabilityService;
        this.schedule = schedule;
        this.interactionPolicy = interactionPolicy;
        this.drivesService = drivesService;
        this.traceService = traceService;
        this.perceptionEngine = perceptionEngine;
    }

    @Scheduled(cron = "0 */1 * * * *")
    @Transactional
    public void run() {
        List<PendingMessageState> due = pendingService.dueForReview(LocalDateTime.now());
        if (due.isEmpty()) return;
        for (PendingMessageState p : due) {
            try {
                reevaluate(p);
            } catch (Exception e) {
                log.warn("[已读复查] {} 失败: {}", p.getMessageId(), e.getMessage());
            }
        }
    }

    private void reevaluate(PendingMessageState p) {
        LocalDateTime now = LocalDateTime.now();
        String companionId = p.getCompanionId();
        String userId = p.getUserId();
        String conversationId = p.getConversationId();

        // 现在睡觉/勿扰 → 再延后
        CompanionAvailability availability = availabilityService.current(companionId, now, null);
        if (availability == CompanionAvailability.SLEEPING) {
            scheduledActionService.schedule(companionId, ScheduledActionService.RE_EVALUATE_MESSAGE,
                    now.plusHours(1), Map.of("pendingMessageId", p.getMessageId()));
            return;
        }

        var state = agentStateService.get(companionId);
        var rel = relationshipService.find(userId, companionId);
        String relationshipStage = rel != null ? rel.getRelationshipStage() : "new";
        double closeness = state != null ? state.getEmotionalCloseness() : 0.3;
        PerceptionEngine.Perception perception = perceptionEngine.perceive(p.getSenderText());

        InteractionDecision baseline = interactionPolicy.decide(new InteractionPolicyEngine.InteractionInput(
                p.getSenderText(), perception.intent(), perception.emotion(),
                state != null ? state.getEnergy() : 0.6,
                state != null ? state.getStress() : 0.3,
                relationshipStage,
                rel != null && List.of("close", "deeply_connected").contains(rel.getRelationshipStage()),
                false, availability,
                AppraisalService.AppraisalResult.fromValues(
                        state != null ? state.getHurt() : 0, state != null ? state.getAnger() : 0,
                        state != null ? state.getWarmth() : 0, 0.2, -0.1, 0.3),
                closeness, rel != null ? rel.getFamiliarity() : 0, rel != null ? rel.getIntimacy() : 0));

        BrainDecision decision = brainAgent.execute(new BrainContext(
                companionId, userId, p.getMessageId(), p.getSenderText(),
                List.of("(一条之前没回的消息)"), "复查未回消息",
                availability.name(), state != null ? state.getEnergy() : 0.6,
                state != null ? state.getStress() : 0.3,
                state != null ? state.getSocialEnergy() : 0.6,
                state != null ? state.getHurt() : 0, state != null ? state.getAnger() : 0,
                state != null ? state.getSadness() : 0, state != null ? state.getAnxiety() : 0,
                state != null ? state.getWarmth() : 0, state != null ? state.getMood() : "平静",
                1.0, 1.0, true, "vibrate",
                relationshipStage, closeness, perception.intent(), perception.emotion(),
                drivesService.compute(AppraisalService.AppraisalResult.fromValues(
                        state != null ? state.getHurt() : 0, state != null ? state.getAnger() : 0,
                        state != null ? state.getWarmth() : 0, 0.2, -0.1, 0.3),
                        state != null ? state.getEnergy() : 0.6, state != null ? state.getStress() : 0.3,
                        closeness, rel != null ? rel.getFamiliarity() : 0, rel != null ? rel.getIntimacy() : 0,
                        availability, p.getSenderText(), p.getSenderText().length()),
                true, false, baseline));

        if (decision.shouldReply()) {
            reply(p, userId, decision);
        } else if (decision.isDefer()) {
            // 继续冷处理 → 再延后
            scheduledActionService.schedule(companionId, ScheduledActionService.RE_EVALUATE_MESSAGE,
                    LocalDateTime.now().plusHours(2), Map.of("pendingMessageId", p.getMessageId()));
        } else {
            // 放下这件事(人偶尔会忘记回)
            pendingService.markExpired(p.getMessageId());
        }
    }

    private void reply(PendingMessageState p, String userId, BrainDecision decision) {
        String companionId = p.getCompanionId();
        String conversationId = p.getConversationId();
        List<Message> recent = conversationService.recentMessages(conversationId, 30);
        InteractionDecision interaction = decision.baseline();

        try {
            CompanionRuntime.ChatOutcome outcome = runtime.generate(userId, companionId, conversationId,
                    p.getMessageId(), p.getSenderText(), recent, null, interaction);
            String reply = outcome.reply();
            if (reply == null || reply.isBlank()) {
                pendingService.markExpired(p.getMessageId());
                return;
            }
            Message sent = conversationService.addMessage(conversationId, "companion", reply, null, true,
                    "DEFERRED_REPLY", null, null);
            deliveryService.responded(companionId, p.getMessageId());
            pendingService.markReplied(p.getMessageId());
            scheduledActionService.cancelPending(companionId, ScheduledActionService.RE_EVALUATE_MESSAGE);
            eventBus.publish(companionId, CompanionEventType.COMPANION_MESSAGE,
                    Map.of("messageId", sent.getId(), "content", reply, "senderType", "companion",
                            "deferredReply", true));
            log.info("[已读复查] {} 回复了未回消息: {}", companionId, reply.substring(0, Math.min(40, reply.length())));
        } catch (Exception e) {
            log.warn("[已读复查] 回复失败,保持待复查: {}", e.getMessage());
            scheduledActionService.schedule(companionId, ScheduledActionService.RE_EVALUATE_MESSAGE,
                    LocalDateTime.now().plusHours(1), Map.of("pendingMessageId", p.getMessageId()));
        }
    }
}
