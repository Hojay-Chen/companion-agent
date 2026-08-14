package com.luxera.companion.relationship;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 关系引擎: 随相处逐渐演化。
 * MVP 用消息量 + 关系数值的规则近似(设计文档 34 节强调不能只按次数简单加亲密度,这里数值增量很小,阶段由综合行为决定)。
 */
@Slf4j
@Service
public class RelationshipEngine {

    private final RelationshipRepository relRepo;
    private final RelationshipEventRepository eventRepo;
    private final SharedExperienceRepository sharedRepo;

    public RelationshipEngine(RelationshipRepository relRepo,
                              RelationshipEventRepository eventRepo,
                              SharedExperienceRepository sharedRepo) {
        this.relRepo = relRepo;
        this.eventRepo = eventRepo;
        this.sharedRepo = sharedRepo;
    }

    @Transactional
    public void onMessage(String userId, String companionId, LocalDateTime messageTime, String emotion, String intent) {
        Relationship r = relRepo.findByUserIdAndCompanionId(userId, companionId)
                .orElse(null);
        if (r == null) return;

        r.setMessageCount(r.getMessageCount() + 1);
        r.setLastInteractionAt(messageTime);
        r.setFamiliarity(clamp(r.getFamiliarity() + 0.0012));
        r.setTrust(clamp(r.getTrust() + 0.0006));
        r.setIntimacy(clamp(r.getIntimacy() + 0.0004));
        r.setAffection(clamp(r.getAffection() + 0.0005));

        int msgs = r.getMessageCount();
        if (msgs == 1) {
            addMilestone(r, "first_conversation", "第一次对话",
                    "你们开始了第一段对话,你告诉了她关于你的一些事。", 0.9);
        }
        int hour = messageTime.getHour();
        if ((hour >= 23 || hour < 3) && eventRepo.countByRelationshipIdAndType(r.getId(), "first_late_night") == 0) {
            addMilestone(r, "first_late_night", "第一次深夜聊天",
                    "深夜里你们还在聊,她记住了这次特别的陪伴。", 0.82);
        }
        if ("sad".equals(emotion) && eventRepo.countByRelationshipIdAndType(r.getId(), "first_emotional_support") == 0) {
            addMilestone(r, "first_emotional_support", "第一次在她难过时被安慰",
                    "你在低落的时候愿意找她倾诉,这让她很珍惜。", 0.88);
        }
        if ("share_joy".equals(intent) && eventRepo.countByRelationshipIdAndType(r.getId(), "first_joy_shared") == 0) {
            addMilestone(r, "first_joy_shared", "第一次分享好消息",
                    "你第一时间把好消息分享给了她。", 0.78);
        }
        if ("ask_about_her".equals(intent) && eventRepo.countByRelationshipIdAndType(r.getId(), "first_care_about_her") == 0) {
            addMilestone(r, "first_care_about_her", "第一次主动关心她",
                    "你开始关心她的状态,她心里暖暖的。", 0.74);
        }
        if ("planning".equals(intent) && eventRepo.countByRelationshipIdAndType(r.getId(), "first_plan_together") == 0) {
            addMilestone(r, "first_plan_together", "第一次一起计划未来",
                    "你们开始聊到一起去做点什么。", 0.76);
        }

        String oldStage = r.getRelationshipStage();
        String newStage = stageFor(r);
        if (!oldStage.equals(newStage)) {
            r.setRelationshipStage(newStage);
            addMilestone(r, "milestone", "关系进入「" + zhStage(newStage) + "」",
                    "随着相处,你们的关系悄然进入新阶段。", 0.9);
            log.info("关系阶段变化: {} -> {}", oldStage, newStage);
        }
        relRepo.save(r);
    }

    @Transactional
    public void onUserCorrected(String userId, String companionId) {
        relRepo.findByUserIdAndCompanionId(userId, companionId).ifPresent(r -> {
            if (eventRepo.countByRelationshipIdAndType(r.getId(), "user_corrected") < 5) {
                addMilestone(r, "user_corrected", "你纠正了她",
                        "你愿意纠正她的理解,这让她更懂你。", 0.6);
            }
        });
    }

    private void addMilestone(Relationship r, String type, String title, String desc, double sig) {
        RelationshipEvent e = new RelationshipEvent();
        e.setRelationshipId(r.getId());
        e.setType(type);
        e.setTitle(title);
        e.setDescription(desc);
        e.setSignificance(sig);
        e.setOccurredAt(LocalDateTime.now());
        eventRepo.save(e);

        if (sig >= 0.8) {
            SharedExperience sx = new SharedExperience();
            sx.setRelationshipId(r.getId());
            sx.setType(type);
            sx.setTitle(title);
            sx.setDescription(desc);
            sx.setImportance(sig);
            sx.setOccurredAt(LocalDateTime.now());
            sharedRepo.save(sx);
            r.setSharedExperienceCount((int) sharedRepo.findByRelationshipIdOrderByOccurredAtDesc(r.getId()).size());
        }
    }

    private String stageFor(Relationship r) {
        int msgs = r.getMessageCount();
        double intimacy = r.getIntimacy();
        if (msgs >= 300 || (msgs >= 150 && intimacy > 0.55)) return "deeply_connected";
        if (msgs >= 100 || (msgs >= 50 && intimacy > 0.45)) return "close";
        if (msgs >= 30 || (msgs >= 15 && r.getFamiliarity() > 0.2)) return "familiar";
        return "new";
    }

    private static String zhStage(String stage) {
        switch (stage) {
            case "familiar": return "熟络";
            case "close": return "亲密";
            case "deeply_connected": return "深深相连";
            default: return "初识";
        }
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
