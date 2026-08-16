package com.luxera.companion.life;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * V6 §32 Interrupt System: 生活不是串行任务。
 * 支持 Activity A → Interrupt B → Return to A。
 *
 * 例如: 做饭 → 用户消息 → 看消息回复一句 → 继续做饭。
 * 或: 开会 → 领导点名 → 注意力完全转移 → 会议结束 → 想起用户消息。
 */
@Service
public class LifeInterruptService {

    private final LifeActivityRepository activityRepo;

    public LifeInterruptService(LifeActivityRepository activityRepo) {
        this.activityRepo = activityRepo;
    }

    /**
     * 中断当前活动(例如 用户消息到达时, 若活动可打断性够高)。
     * 活动保持 ACTIVE 但标记 interrupted; 返回是否真的中断了。
     */
    @Transactional
    public boolean interrupt(String companionId, LocalDateTime now, String reason) {
        LifeActivity active = currentActive(companionId, now);
        if (active == null) return false;
        // 可打断性过低(开会/睡觉) → 不中断
        if (active.getInterruptibility() < 0.3) return false;
        active.setInterrupted(true);
        active.setInterruptedAt(now);
        active.setInterruptReason(reason);
        activityRepo.save(active);
        return true;
    }

    /** 恢复被中断的活动 */
    @Transactional
    public void resume(String companionId, LocalDateTime now) {
        List<LifeActivity> interrupted = activityRepo.findTop30ByCompanionIdOrderByPlannedStartDesc(companionId)
                .stream().filter(a -> a.isInterrupted() && "ACTIVE".equals(a.getStatus()))
                .toList();
        for (LifeActivity a : interrupted) {
            a.setInterrupted(false);
            a.setInterruptReason(null);
            a.setInterruptedAt(null);
            activityRepo.save(a);
        }
    }

    /** 推进活动进度(0→1), 由 LifeRuntime tick 调用 */
    @Transactional
    public void advanceProgress(String companionId, LocalDateTime now, double delta) {
        LifeActivity active = currentActive(companionId, now);
        if (active == null) return;
        active.setProgress(Math.max(0, Math.min(1, active.getProgress() + delta)));
        activityRepo.save(active);
    }

    /** 当前 ACTIVE 活动(计划开始≤now≤计划结束 且 状态=ACTIVE) */
    @Transactional(readOnly = true)
    public LifeActivity currentActive(String companionId, LocalDateTime now) {
        java.time.LocalDate date = now.toLocalDate();
        return activityRepo
                .findByCompanionIdAndPlannedStartGreaterThanEqualAndPlannedStartLessThanOrderByPlannedStartAsc(
                        companionId, date.atStartOfDay(), date.plusDays(1).atStartOfDay())
                .stream()
                .filter(a -> "ACTIVE".equals(a.getStatus()))
                .filter(a -> a.getPlannedStart() == null || !a.getPlannedStart().isAfter(now))
                .filter(a -> a.getPlannedEnd() == null || !a.getPlannedEnd().isBefore(now))
                .findFirst()
                .orElse(null);
    }

    /** 活动是否适合查看消息(注意力占用低 + 可打断性高 + 手机可用) */
    public boolean canCheckPhone(LifeActivity activity) {
        if (activity == null) return true;
        return activity.getAttentionDemand() < 0.75 && activity.getPhoneAvailability() >= 0.3;
    }

    /** 活动是否适合回复(回复需要比查看更高的空闲) */
    public boolean canReply(LifeActivity activity) {
        if (activity == null) return true;
        return activity.getAttentionDemand() < 0.55 && activity.getInterruptibility() >= 0.4;
    }
}
