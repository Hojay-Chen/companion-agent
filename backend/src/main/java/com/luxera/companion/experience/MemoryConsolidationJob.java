package com.luxera.companion.experience;

import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 记忆固话定时任务(设计文档 V2.0 §23/§41): 每日把高价值经历固化为长期记忆 */
@Slf4j
@Component
public class MemoryConsolidationJob {

    private final CompanionRepository companionRepo;
    private final ExperienceProcessor experienceProcessor;

    public MemoryConsolidationJob(CompanionRepository companionRepo, ExperienceProcessor experienceProcessor) {
        this.companionRepo = companionRepo;
        this.experienceProcessor = experienceProcessor;
    }

    @Scheduled(cron = "${app.scheduler.memory-consolidation-cron}")
    public void consolidateAll() {
        int total = 0;
        for (Companion c : companionRepo.findAll()) {
            if (c.getDeletedAt() != null) continue;
            try {
                total += experienceProcessor.consolidate(c.getId());
            } catch (Exception e) {
                log.warn("记忆固话失败 companion={}: {}", c.getId(), e.getMessage());
            }
        }
        if (total > 0) {
            log.info("[MemoryConsolidation] 固话 {} 条记忆", total);
        }
    }
}
