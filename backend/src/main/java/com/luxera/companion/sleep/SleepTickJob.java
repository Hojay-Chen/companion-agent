package com.luxera.companion.sleep;

import com.luxera.companion.behavior.BehaviorEngine;
import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * V7 §45: 睡眠推进定时任务(Coarse Tick)。
 * 每 1-5 分钟推进一次睡眠压力/节律状态, 并应用睡眠决策。
 *
 * V8 §二十八/§二十九 升级: 睡眠是 Behavior Candidate ——
 * 决策考虑"是否正在陪你聊"(社交参与/动机), 深夜聊天时她会硬撑,
 * 而不是到点就被 tick 强制入睡。
 */
@Slf4j
@Component
public class SleepTickJob {

    private final SleepModel sleepModel;
    private final CompanionRepository companionRepository;
    private final BehaviorEngine behaviorEngine;

    public SleepTickJob(SleepModel sleepModel, CompanionRepository companionRepository,
                        BehaviorEngine behaviorEngine) {
        this.sleepModel = sleepModel;
        this.companionRepository = companionRepository;
        this.behaviorEngine = behaviorEngine;
    }

    @Scheduled(cron = "${app.scheduler.sleep-tick-cron}")
    @Transactional
    public void run() {
        LocalDateTime now = LocalDateTime.now();
        for (Companion c : companionRepository.findAll()) {
            if (c.getDeletedAt() != null) continue;
            try {
                sleepModel.tick(c.getId(), now);
                // V8: 睡眠决策考虑社交参与(深夜陪你聊 → 硬撑)
                var decision = behaviorEngine.sleepDecision(c.getId(), now);
                if (decision == SleepModel.SleepDecision.SLEEP && !sleepModel.isSleeping(c.getId(), now)) {
                    sleepModel.fallAsleep(c.getId(), now, "NATURAL");
                }
            } catch (Exception e) {
                log.warn("[SleepTick] {} 失败: {}", c.getId(), e.getMessage());
            }
        }
    }
}
