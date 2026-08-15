package com.luxera.companion.proactive;

import com.luxera.companion.agent.CompanionSchedule;
import com.luxera.companion.config.AppProperties;
import com.luxera.companion.conversation.ConversationRepository;
import com.luxera.companion.conversation.ConversationService;
import com.luxera.companion.conversation.Message;
import com.luxera.companion.conversation.MessageRepository;
import com.luxera.companion.llm.ChatRequest;
import com.luxera.companion.llm.LlmMessage;
import com.luxera.companion.llm.LlmRouter;
import com.luxera.companion.openloop.OpenLoop;
import com.luxera.companion.openloop.OpenLoopService;
import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionRepository;
import com.luxera.companion.persona.Persona;
import com.luxera.companion.persona.PersonaService;
import com.luxera.companion.relationship.Relationship;
import com.luxera.companion.relationship.RelationshipRepository;
import com.luxera.companion.thought.Thought;
import com.luxera.companion.thought.ThoughtService;
import com.luxera.companion.tool.Reminder;
import com.luxera.companion.tool.ReminderRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 主动消息引擎(设计文档 52-55 节): 调度器只负责何时检查,
 * 真正决定是否打扰的是决策引擎(打断成本 vs 预期价值)。
 * 主动消息内容用 LLM 按人格+时间+近期话题生成,让陪伴感真实。
 */
@Slf4j
@Component
public class ProactiveEngine {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("M月d日 HH:mm");

    private final AppProperties props;
    private final CompanionRepository companionRepo;
    private final PersonaService personaService;
    private final RelationshipRepository relationshipRepo;
    private final MessageRepository messageRepo;
    private final ConversationRepository conversationRepo;
    private final ConversationService conversationService;
    private final NotificationService notificationService;
    private final ReminderRepository reminderRepo;
    private final LlmRouter llm;
    private final CompanionSchedule schedule;
    private final OpenLoopService openLoopService;
    private final ThoughtService thoughtService;

    public ProactiveEngine(AppProperties props, CompanionRepository companionRepo, PersonaService personaService,
                           RelationshipRepository relationshipRepo, MessageRepository messageRepo,
                           ConversationRepository conversationRepo, ConversationService conversationService,
                           NotificationService notificationService,
                           ReminderRepository reminderRepo, LlmRouter llm, CompanionSchedule schedule,
                           OpenLoopService openLoopService, ThoughtService thoughtService) {
        this.props = props;
        this.companionRepo = companionRepo;
        this.personaService = personaService;
        this.relationshipRepo = relationshipRepo;
        this.messageRepo = messageRepo;
        this.conversationRepo = conversationRepo;
        this.conversationService = conversationService;
        this.notificationService = notificationService;
        this.reminderRepo = reminderRepo;
        this.llm = llm;
        this.schedule = schedule;
        this.openLoopService = openLoopService;
        this.thoughtService = thoughtService;
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

        for (Companion c : companionRepo.findAll()) {
            if (c.getDeletedAt() != null) continue;
            String userId = c.getUserId();
            if (inDnd) continue;
            // 她的作息: 睡觉时不主动打扰(比 DND 更贴合个人作息)
            if (schedule.activityFor(c.getId(), now) == CompanionSchedule.Activity.SLEEP) continue;

            // V3: 主动消息 = Chat 消息(kind=PROACTIVE), 不再生成 notification
            Message lastProactive = messageRepo
                    .findFirstByCompanionIdAndMessageKindOrderByCreatedAtDesc(c.getId(), "PROACTIVE")
                    .orElse(null);
            int minIntervalHours = props.getProactive().getMinIntervalHours();
            if (lastProactive != null && lastProactive.getCreatedAt().isAfter(now.minusHours(minIntervalHours))) continue;
            long todayCount = messageRepo.countByCompanionIdAndMessageKindAndCreatedAtAfter(
                    c.getId(), "PROACTIVE", now.toLocalDate().atStartOfDay());
            if (todayCount >= props.getProactive().getMaxNotificationsPerDay()) continue;

            Relationship rel = relationshipRepo.findByUserIdAndCompanionId(userId, c.getId()).orElse(null);
            LocalDateTime lastInteraction = rel != null ? rel.getLastInteractionAt() : null;
            boolean responsive = isResponsive(userId, c.getId(), lastProactive, now);

            ProactiveDecision decision = decide(c, now, lastInteraction, todayCount, responsive);
            if (decision.act()) {
                String content = draftMessage(c, decision.trigger(), decision.content(), now);
                // V3: 主动消息只进聊天框(见设计 §二十七~三十), 它是 Chat 消息, 不是 Notification
                injectMessage(c.getId(), content);
                actions.add(c.getName() + " → " + decision.title() + " (预期" + round(decision.expectedValue())
                        + " vs 成本" + round(decision.interruptionCost()) + ")");
                log.info("[主动消息] {}: {} (trigger={})", c.getName(), decision.title(), decision.trigger());
            }
        }
        return actions;
    }

    /** 决策引擎: 收集触发,计算预期价值与打断成本(设计文档 V2.0 §18) */
    ProactiveDecision decide(Companion c, LocalDateTime now, LocalDateTime lastInteraction,
                             long todayCount, boolean responsive) {
        String userId = c.getUserId();
        int hour = now.getHour();
        Relationship rel = relationshipRepo.findByUserIdAndCompanionId(userId, c.getId()).orElse(null);
        // 按作息调节主动意愿: 忙碌时低, 休闲时高
        double factor = schedule.proactiveFactor(c.getId(), now);
        // 关系相关性(设计文档 V2.0 §18): 越熟悉/越亲密, 主动联系的价值越高
        double relBonus = relationshipBonus(rel);

        // 触发 0: OpenLoop 驱动(未完成事项, 最真实) — "面试怎么样了"
        // 只在预期解决时间临近/已过(±3h)才问, 提前不打扰
        OpenLoop bestLoop = openLoopService.activeLoops(c.getId()).stream()
                .filter(l -> l.getExpectedResolutionAt() != null)
                .filter(l -> !l.getExpectedResolutionAt().isBefore(now.minusHours(3)))
                .filter(l -> l.getExpectedResolutionAt().isBefore(now.plusHours(2)))
                .max(Comparator.comparingDouble(OpenLoop::getImportance))
                .orElse(null);
        if (bestLoop != null && !loopFollowedUp(c.getId(), bestLoop.getTitle())) {
            double timeliness = bestLoop.getExpectedResolutionAt() != null
                    && bestLoop.getExpectedResolutionAt().isBefore(now) ? 0.12 : 0.06;
            double value = 0.68 * factor + relBonus + timeliness;
            double cst = cost(now, lastInteraction, todayCount, responsive);
            if (value > cst) {
                return ProactiveDecision.send("未了结的事",
                        "想问问" + bestLoop.getTitle() + "的事,后来怎么样了。",
                        "open_loop", value, cst);
            }
        }

        // 触发 0.5: Thought 驱动(她"想起了你")
        Thought bestThought = thoughtService.activeThoughts(c.getId()).stream()
                .filter(t -> t.getStrength() >= 0.35)
                .filter(t -> List.of("CURIOSITY", "WORRY", "EXPECTATION", "UNFINISHED").contains(t.getType()))
                .filter(t -> !"SUPPRESSED".equals(t.getStatus()))
                .max(Comparator.comparingDouble(Thought::getStrength))
                .orElse(null);
        if (bestThought != null) {
            // 若想法关联的未完成事项还没到预期时间(提前问=打扰) → 今天不打扰, SUPPRESSED 保留
            if (isPremature(bestThought, now)) {
                thoughtService.suppress(bestThought.getId());
            } else {
                double value = (0.35 + bestThought.getStrength() * 0.4) * factor + relBonus;
                double cst = cost(now, lastInteraction, todayCount, responsive);
                if (value > cst) {
                    thoughtService.act(bestThought.getId());
                    return ProactiveDecision.send("心里想着", bestThought.getContent(), "thought", value, cst);
                } else {
                    // 被成本压制的想法 → SUPPRESSED, 保留次日再激活(形成自然连续性)
                    thoughtService.suppress(bestThought.getId());
                }
            }
        }

        // 触发 1: 深夜加班模式
        List<Message> week = messageRepo.findUserMessagesSince(c.getId(), now.minusDays(7));
        long lateCount = week.stream().filter(m -> {
            int h = m.getCreatedAt().getHour();
            return h >= 23 || h < 2;
        }).count();
        boolean alreadyMentioned = false;
        Message lastP = messageRepo
                .findFirstByCompanionIdAndMessageKindOrderByCreatedAtDesc(c.getId(), "PROACTIVE")
                .orElse(null);
        if (lastP != null) alreadyMentioned = lastP.getContent() != null && lastP.getContent().contains("忙到很晚");
        if (lateCount >= 3 && !alreadyMentioned) {
            return ProactiveDecision.send("深夜提醒",
                    "你最近是不是又忙到很晚了?我不催你睡,只是想知道你还好吗。",
                    "late_work", 0.62 * factor, cost(now, lastInteraction, todayCount, responsive));
        }

        // 触发 2: 早安问候(上午 8-11 点,今天还没聊过,且之前聊过)
        if (hour >= 8 && hour < 11 && todayCount == 0 && rel != null && rel.getMessageCount() > 0) {
            return ProactiveDecision.send("早安问候",
                    "早上好呀,新的一天开始啦。你今天有什么安排吗?",
                    "morning_greeting", 0.52 * factor, cost(now, lastInteraction, todayCount, responsive));
        }

        // 触发 3: 傍晚回访(今天聊过,但 2-8 小时没动静)
        if (hour >= 17 && hour <= 22 && lastInteraction != null
                && lastInteraction.isAfter(now.minusHours(8))
                && lastInteraction.isBefore(now.minusHours(2))) {
            return ProactiveDecision.send("傍晚回访",
                    "这会儿忙完了吗?想听听你今天过得怎么样。",
                    "evening_checkin", 0.55 * factor, cost(now, lastInteraction, todayCount, responsive));
        }

        // 触发 4: 分享好消息后的跟进(48h 内,未跟进过)
        Message lastJoy = null;
        for (Message m : messageRepo.findUserMessagesSince(c.getId(), now.minusDays(2))) {
            if ("share_joy".equals(m.getIntent()) || "happy".equals(m.getEmotion())) {
                lastJoy = m;
            }
        }
        if (lastJoy != null) {
            boolean followedUp = messageRepo.findMessagesBetween(c.getId(), lastJoy.getCreatedAt(), now)
                    .stream().anyMatch(m -> "PROACTIVE".equals(m.getMessageKind())
                            && m.getContent() != null && m.getContent().contains("好消息"));
            if (!followedUp) {
                return ProactiveDecision.send("跟进好消息",
                        "前两天你说的那个好消息,后来怎么样了?我一直惦记着呢。",
                        "follow_up_joy", 0.65 * factor, cost(now, lastInteraction, todayCount, responsive));
            }
        }

        // 触发 5: 长时间沉默(2 天)
        if (rel != null && rel.getFamiliarity() > 0.15 && lastInteraction != null
                && lastInteraction.isBefore(now.minusDays(2))) {
            return ProactiveDecision.send("好久不见",
                    "最近两天你都没怎么找我,我有点想你了。要是忙,就告诉我一声,我不吵你。",
                    "silence", 0.5 * factor, cost(now, lastInteraction, todayCount, responsive));
        }

        return ProactiveDecision.nothing();
    }

    /** 用 LLM 按人格+作息+场景生成主动消息,失败时回退到固定文案 */
    private String draftMessage(Companion c, String trigger, String fallback, LocalDateTime now) {
        try {
            Persona persona = personaService.getActive(c.getId());
            String personaDesc = "";
            if (persona != null && persona.getPersonality() != null && persona.getPersonality().getSummary() != null) {
                personaDesc = persona.getPersonality().getSummary();
            }
            String scheduleDesc = schedule.describe(c.getId(), c.getName(), now);
            String system = "你是" + c.getName() + ",一个真实的人。" + personaDesc
                    + "。" + scheduleDesc
                    + "。你打算主动给用户发一条消息,"
                    + "场景:" + triggerDesc(trigger) + "。请自然地说 2-3 句,像发微信,不要解释你是 AI,不要问号堆砌。";
            var r = llm.chat(ChatRequest.builder()
                    .messages(List.of(LlmMessage.system(system), LlmMessage.user("现在给用户发这条主动消息吧。")))
                    .temperature(0.9)
                    .metadata(Map.of("companionName", c.getName()))
                    .build());
            String content = r.getContent() == null ? "" : r.getContent().trim();
            if (content.length() >= 5 && content.length() <= 120) {
                return content;
            }
        } catch (Exception e) {
            log.debug("主动消息 LLM 生成失败,回退模板: {}", e.getMessage());
        }
        return fallback;
    }

    private static String triggerDesc(String trigger) {
        switch (trigger == null ? "" : trigger) {
            case "late_work": return "用户最近常熬夜加班";
            case "morning_greeting": return "新的一天开始了,想问候一下用户";
            case "evening_checkin": return "用户今天聊过但有一阵没动静了";
            case "follow_up_joy": return "用户前几天分享过好消息,想问问后续";
            case "silence": return "用户两天没联系你了";
            default: return "随口关心一下用户";
        }
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
    private boolean isResponsive(String userId, String companionId, Message lastProactive, LocalDateTime now) {
        if (lastProactive == null) return true;
        LocalDateTime windowEnd = lastProactive.getCreatedAt().plusHours(2);
        if (windowEnd.isAfter(now)) windowEnd = now;
        for (Message m : messageRepo.findUserMessagesSince(companionId, lastProactive.getCreatedAt())) {
            if (m.getCreatedAt().isBefore(windowEnd)) return true;
        }
        return false;
    }

    /** 该未完成事项是否已经被主动跟进过 */
    private boolean loopFollowedUp(String companionId, String title) {
        Message last = messageRepo
                .findFirstByCompanionIdAndMessageKindOrderByCreatedAtDesc(companionId, "PROACTIVE")
                .orElse(null);
        if (last == null) return false;
        String content = last.getContent();
        return content != null && title != null && content.contains(title);
    }

    /** 想法关联的未完成事项是否还没到预期时间(提前问=打扰) */
    private boolean isPremature(Thought t, LocalDateTime now) {
        if (!"OPEN_LOOP".equals(t.getTriggerType()) || t.getTriggerRef() == null) return false;
        return openLoopService.getById(t.getTriggerRef())
                .map(l -> l.getExpectedResolutionAt() != null
                        && l.getExpectedResolutionAt().isAfter(now.plusHours(2)))
                .orElse(false);
    }

    /** 关系相关性加权(设计文档 V2.0 §18): 越熟悉/越亲密, 主动价值越高 */
    private static double relationshipBonus(Relationship rel) {
        if (rel == null) return 0;
        return clamp(rel.getFamiliarity() * 0.1 + rel.getIntimacy() * 0.1);
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private void injectMessage(String companionId, String content) {
        var convs = conversationRepo.findByCompanionIdOrderByLastMessageAtDesc(companionId);
        if (convs.isEmpty()) return;
        // V3: 主动消息进入聊天框, 标记 message_kind=PROACTIVE(仍是 Chat Message, 不是 Notification)
        conversationService.addMessage(convs.get(0).getId(), "companion", content, null, true,
                "PROACTIVE", null, null);
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
