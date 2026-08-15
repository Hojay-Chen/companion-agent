package com.luxera.companion.emotion;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 情绪维护定时任务 */
@Component
public class EmotionMaintenanceJob {

    private final EmotionDecayService decayService;

    public EmotionMaintenanceJob(EmotionDecayService decayService) {
        this.decayService = decayService;
    }

    @Scheduled(cron = "${app.scheduler.emotion-maintenance-cron}")
    public void maintain() {
        decayService.decay();
    }
}
