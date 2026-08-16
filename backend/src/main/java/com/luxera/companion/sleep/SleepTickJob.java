package com.luxera.companion.sleep;

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
 * 睡眠决策: 高睡意 + 无强动机 → 入睡; 否则保持清醒。
 * 重要: 这里不调用 LLM —— 睡眠是完全确定性的状态演化。
 */
@Slf4j
@Component
public class SleepTickJob {

    private final SleepModel sleepModel;
    private final CompanionRepository companionRepository;

    public SleepTickJob(SleepModel sleepModel, CompanionRepository companionRepository) {
        this.sleepModel = sleepModel;
        this.companionRepository = companionRepository;
    }

    @Scheduled(cron = "${app.scheduler.sleep-tick-cron}")
    @Transactional
    public void run() {
        LocalDateTime now = LocalDateTime.now();
        for (Companion c : companionRepository.findAll()) {
            if (c.getDeletedAt() != null) continue;
            try {
                sleepModel.tick(c.getId(), now);
                // 应用睡眠决策: 高睡意且无动机 → 入睡(平时 tick 动机为 0)
                var decision = sleepModel.decideSleep(c.getId(), now, 0, 0);
                if (decision == SleepModel.SleepDecision.SLEEP && !sleepModel.isSleeping(c.getId(), now)) {
                    sleepModel.fallAsleep(c.getId(), now, "NATURAL");
                }
            } catch (Exception e) {
                log.warn("[SleepTick] {} 失败: {}", c.getId(), e.getMessage());
            }
        }
    }
}
