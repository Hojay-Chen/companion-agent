package com.luxera.companion.proactive;

import com.luxera.companion.config.AppProperties;
import com.luxera.companion.conversation.ConversationRepository;
import com.luxera.companion.conversation.ConversationService;
import com.luxera.companion.conversation.Message;
import com.luxera.companion.conversation.MessageRepository;
import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionRepository;
import com.luxera.companion.relationship.Relationship;
import com.luxera.companion.relationship.RelationshipRepository;
import com.luxera.companion.tool.Reminder;
import com.luxera.companion.tool.ReminderRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 主动消息引擎: 调度器只决定何时检查,真正决定是否打扰的是决策引擎。
 * (设计文档 52-55 节)
 */
@Slf4j
@Component
public class ProactiveEngine {

    private final AppProperties props;
    private final CompanionRepository companionRepo;
    private final RelationshipRepository relationshipRepo;
    private final MessageRepository messageRepo;
    private final ConversationRepository conversationRepo;
    private final ConversationService conversationService;
    private final NotificationRepository notificationRepo;
    private final NotificationService notificationService;
    private final ReminderRepository reminderRepo;

    public ProactiveEngine(AppProperties props, CompanionRepository companionRepo,
                           RelationshipRepository relationshipRepo, MessageRepository messageRepo,
                           ConversationRepository conversationRepo, ConversationService conversationService,
                           NotificationRepository notificationRepo, NotificationService notificationService,
                           ReminderRepository reminderRepo) {
        this.props = props;
        this.companionRepo = companionRepo;
        this.relationshipRepo = relationshipRepo;
        this.messageRepo = messageRepo;
        this.conversationRepo = conversationRepo;
        this.conversationService = conversationService;
        this.notificationRepo = notificationRepo;
        this.notificationService = notificationService;
        this.reminderRepo = reminderRepo;
    }

    @Scheduled(cron = "${app.scheduler.proactive-cron}")
    public void runScheduled() {
        run();
    }

    @Transactional
    public List<String> run() {
        List<String> actions = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        // 1. 到期的提醒 → 转通知
        for (Reminder r : reminderRepo.findByStatusAndRemindAtBefore("pending", now)) {
            notificationService.notify(r.getUserId(), r.getCompanionId(), r.getType(), r.getTitle(), r.getContent());
            r.setStatus("done");
            reminderRepo.save(r);
            actions.add("提醒已送达: " + r.getTitle());
        }

        int dndStart = props.getProactive().getDndStartHour();
        int dndEnd = props.getProactive().getDndEndHour();
        int hour = now.getHour();

        // 2. 模式触发的主动消息
        for (Companion c : companionRepo.findAll()) {
            if (c.getDeletedAt() != null) continue;
            String userId = c.getUserId();
            if (inDnd(hour, dndStart, dndEnd)) continue;

            // 打扰控制: 频率
            Notification lastProactive = notificationRepo.findTopByCompanionIdAndTypeOrderByCreatedAtDesc(c.getId(), "proactive");
            int minIntervalHours = props.getProactive().getMinIntervalHours();
            if (lastProactive != null && lastProactive.getCreatedAt().isAfter(now.minusHours(minIntervalHours))) continue;
            long todayCount = notificationRepo.countByUserIdAndCompanionIdAndCreatedAtAfter(
                    userId, c.getId(), now.toLocalDate().atStartOfDay());
            if (todayCount >= props.getProactive().getMaxNotificationsPerDay()) continue;

            // 触发条件与预期价值
            double expectedValue = 0;
            String title = null;
            String content = null;

            List<Message> week = messageRepo.findUserMessagesSince(c.getId(), now.minusDays(7));
            long lateCount = week.stream().filter(m -> {
                int h = m.getCreatedAt().getHour();
                return h >= 23 || h < 2;
            }).count();
            boolean alreadyMentioned = lastProactive != null && lastProactive.getContent().contains("忙到很晚");
            if (lateCount >= 3 && !alreadyMentioned) {
                expectedValue = Math.max(expectedValue, 0.62);
                title = "深夜提醒";
                content = "你最近是不是又忙到很晚了?我不催你睡,只是想知道你还好吗。";
            }

            Relationship rel = relationshipRepo.findByUserIdAndCompanionId(userId, c.getId()).orElse(null);
            LocalDateTime lastInteraction = rel != null ? rel.getLastInteractionAt() : null;
            if (rel != null && rel.getFamiliarity() > 0.35 && lastInteraction != null
                    && lastInteraction.isBefore(now.minusDays(3)) && title == null) {
                expectedValue = Math.max(expectedValue, 0.5);
                title = "好久不见";
                content = "最近几天你都没怎么找我,我有点想你了。要是忙,就告诉我一声,我不吵你。";
            }

            if (title == null) continue;

            // 打断成本 > 预期价值 → 不做
            double cost = interruptionCost(now, lastInteraction);
            if (cost >= expectedValue) continue;

            notificationService.notify(userId, c.getId(), "proactive", title, content);
            injectMessage(c.getId(), content);
            actions.add(c.getName() + " → " + title);
            log.info("[主动消息] {}: {}", c.getName(), title);
        }
        return actions;
    }

    private double interruptionCost(LocalDateTime now, LocalDateTime lastInteraction) {
        double cost = 0.15;
        int h = now.getHour();
        if (h >= 0 && h < 8) cost += 0.4;
        if (lastInteraction != null && lastInteraction.isAfter(now.minusHours(4))) cost += 0.35;
        return cost;
    }

    private void injectMessage(String companionId, String content) {
        var convs = conversationRepo.findByCompanionIdOrderByLastMessageAtDesc(companionId);
        if (convs.isEmpty()) return;
        conversationService.addMessage(convs.get(0).getId(), "companion", content, null, true);
    }

    private static boolean inDnd(int hour, int start, int end) {
        if (start > end) {
            return hour >= start || hour < end;
        }
        return hour >= start && hour < end;
    }
}
