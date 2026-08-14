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
 * 主动消息引擎(设计文档 52-55 节): 调度器只负责何时检查,
 * 真正决定是否打扰的是决策引擎(打断成本 vs 预期价值)。
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
        boolean inDnd = inDnd(hour, dndStart, dndEnd);

        // 2. 每个伴侣: 评估主动触发
        for (Companion c : companionRepo.findAll()) {
            if (c.getDeletedAt() != null) continue;
            String userId = c.getUserId();
            if (inDnd) continue;

            Notification lastProactive = notificationRepo.findTopByCompanionIdAndTypeOrderByCreatedAtDesc(c.getId(), "proactive");
            int minIntervalHours = props.getProactive().getMinIntervalHours();
            if (lastProactive != null && lastProactive.getCreatedAt().isAfter(now.minusHours(minIntervalHours))) continue;
            long todayCount = notificationRepo.countByUserIdAndCompanionIdAndCreatedAtAfter(
                    userId, c.getId(), now.toLocalDate().atStartOfDay());
            if (todayCount >= props.getProactive().getMaxNotificationsPerDay()) continue;

            Relationship rel = relationshipRepo.findByUserIdAndCompanionId(userId, c.getId()).orElse(null);
            LocalDateTime lastInteraction = rel != null ? rel.getLastInteractionAt() : null;
            boolean responsive = isResponsive(userId, c.getId(), lastProactive, now);

            ProactiveDecision decision = decide(c, now, lastInteraction, lastProactive, todayCount, responsive);
            if (decision.act()) {
                notificationService.notify(userId, c.getId(), "proactive", decision.title(), decision.content());
                injectMessage(c.getId(), decision.content());
                actions.add(c.getName() + " → " + decision.title() + " (预期" + round(decision.expectedValue())
                        + " vs 成本" + round(decision.interruptionCost()) + ")");
                log.info("[主动消息] {}: {} (trigger={})", c.getName(), decision.title(), decision.trigger());
            }
        }
        return actions;
    }

    /** 决策引擎: 收集触发,计算预期价值与打断成本(设计文档 53-55 节) */
    ProactiveDecision decide(Companion c, LocalDateTime now, LocalDateTime lastInteraction,
                             Notification lastProactive, long todayCount, boolean responsive) {
        String userId = c.getUserId();

        // 触发 1: 深夜加班模式
        List<Message> week = messageRepo.findUserMessagesSince(c.getId(), now.minusDays(7));
        long lateCount = week.stream().filter(m -> {
            int h = m.getCreatedAt().getHour();
            return h >= 23 || h < 2;
        }).count();
        boolean alreadyMentioned = lastProactive != null && lastProactive.getContent().contains("忙到很晚");
        if (lateCount >= 3 && !alreadyMentioned) {
            return ProactiveDecision.send("深夜提醒",
                    "你最近是不是又忙到很晚了?我不催你睡,只是想知道你还好吗。",
                    "late_work", 0.62, cost(now, lastInteraction, todayCount, responsive));
        }

        // 触发 2: 分享好消息后的跟进(48h 内,未跟进过)
        Message lastJoy = null;
        for (Message m : messageRepo.findUserMessagesSince(c.getId(), now.minusDays(2))) {
            if ("share_joy".equals(m.getIntent()) || "happy".equals(m.getEmotion())) {
                lastJoy = m;
            }
        }
        if (lastJoy != null) {
            boolean followedUp = notificationRepo
                    .findTop10ByUserIdAndCompanionIdAndCreatedAtAfterOrderByCreatedAtDesc(userId, c.getId(), lastJoy.getCreatedAt())
                    .stream().anyMatch(n -> "proactive".equals(n.getType()) && n.getTitle().contains("好消息"));
            if (!followedUp) {
                return ProactiveDecision.send("跟进好消息",
                        "前两天你说的那个好消息,后来怎么样了?我一直惦记着呢。",
                        "follow_up_joy", 0.65, cost(now, lastInteraction, todayCount, responsive));
            }
        }

        // 触发 3: 长时间沉默
        Relationship rel = relationshipRepo.findByUserIdAndCompanionId(userId, c.getId()).orElse(null);
        if (rel != null && rel.getFamiliarity() > 0.35 && lastInteraction != null
                && lastInteraction.isBefore(now.minusDays(3))) {
            return ProactiveDecision.send("好久不见",
                    "最近几天你都没怎么找我,我有点想你了。要是忙,就告诉我一声,我不吵你。",
                    "silence", 0.5, cost(now, lastInteraction, todayCount, responsive));
        }

        return ProactiveDecision.nothing();
    }

    private double cost(LocalDateTime now, LocalDateTime lastInteraction, long todayCount, boolean responsive) {
        double cost = 0.15;
        int h = now.getHour();
        if (h >= 0 && h < 8) cost += 0.4;
        if (h >= 22) cost += 0.1;
        if (lastInteraction != null && lastInteraction.isAfter(now.minusHours(4))) cost += 0.35;
        cost += responsive ? -0.1 : 0.1;
        if (todayCount >= props.getProactive().getMaxNotificationsPerDay()) cost += 0.3;
        return cost;
    }

    /** 上次主动消息后 2 小时内用户是否回话(响应率代理) */
    private boolean isResponsive(String userId, String companionId, Notification lastProactive, LocalDateTime now) {
        if (lastProactive == null) return true;
        LocalDateTime windowEnd = lastProactive.getCreatedAt().plusHours(2);
        if (windowEnd.isAfter(now)) windowEnd = now;
        for (Message m : messageRepo.findUserMessagesSince(companionId, lastProactive.getCreatedAt())) {
            if (m.getCreatedAt().isBefore(windowEnd)) return true;
        }
        return false;
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

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
