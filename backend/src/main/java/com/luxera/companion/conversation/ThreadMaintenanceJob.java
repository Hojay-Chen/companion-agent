package com.luxera.companion.conversation;

import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * V6 §30 Thread 维护 Job: 周期衰减线程状态。
 * ACTIVE/PAUSED 太久没消息 → RESUMABLE(可恢复); RESUMABLE 太久 → ABANDONED(被遗忘)。
 * 模拟真人"聊到一半去做别的事情, 过一段时间回来继续, 再久就忘了"。
 */
@Component
public class ThreadMaintenanceJob {

    private final ConversationThreadService threadService;
    private final CompanionRepository companionRepository;

    public ThreadMaintenanceJob(ConversationThreadService threadService,
                                CompanionRepository companionRepository) {
        this.threadService = threadService;
        this.companionRepository = companionRepository;
    }

    @Scheduled(cron = "${app.scheduler.thread-maintenance-cron}")
    @Transactional
    public void maintain() {
        LocalDateTime now = LocalDateTime.now();
        for (Companion c : companionRepository.findAll()) {
            try {
                threadService.decayForCompanion(c.getId(), now);
            } catch (Exception ignored) {
                // 单个伴侣失败不影响整体
            }
        }
    }
}
