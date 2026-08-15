package com.luxera.companion.life;

import com.luxera.companion.agent.CompanionSchedule;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/** 生活模拟: 按作息生成当天活动(Level 0, 不调用 LLM) */
@Service
public class LifeSimulationService {

    private final CompanionSchedule schedule;
    private final LifeActivityRepository activityRepo;

    public LifeSimulationService(CompanionSchedule schedule, LifeActivityRepository activityRepo) {
        this.schedule = schedule;
        this.activityRepo = activityRepo;
    }

    /** 若当天还没有活动, 则按作息生成 PLANNED 活动 */
    @Transactional
    public void ensureDayPlanned(String companionId, LocalDate date) {
        boolean exists = !activityRepo
                .findByCompanionIdAndPlannedStartGreaterThanEqualAndPlannedStartLessThanOrderByPlannedStartAsc(
                        companionId, date.atStartOfDay(), date.plusDays(1).atStartOfDay())
                .isEmpty();
        if (exists) return;

        List<CompanionSchedule.TimeBlock> blocks = schedule.dayBlocks(companionId, date);
        for (CompanionSchedule.TimeBlock b : blocks) {
            LifeActivity a = new LifeActivity();
            a.setCompanionId(companionId);
            a.setType(b.type());
            a.setTitle(b.title());
            a.setPlannedStart(date.atTime(b.start()));
            a.setPlannedEnd(endTime(date, b.start(), b.end()));
            a.setImportance(blockImportance(b.type()));
            a.setEmotionalSignificance(blockEmotional(b.type()));
            a.setStatus("PLANNED");
            a.setSource("SIMULATED_LIFE_EVENT");
            activityRepo.save(a);
        }
    }

    private static LocalDateTime endTime(LocalDate date, LocalTime start, LocalTime end) {
        if (end.isBefore(start)) {
            return date.plusDays(1).atTime(end);
        }
        return date.atTime(end);
    }

    private static double blockImportance(String type) {
        return switch (type) {
            case "WORK", "MEAL", "SLEEP" -> 0.5;
            case "LEISURE", "SOCIAL", "HOBBY" -> 0.4;
            default -> 0.3;
        };
    }

    private static double blockEmotional(String type) {
        return switch (type) {
            case "SOCIAL", "HOBBY" -> 0.6;
            case "WORK" -> 0.3;
            default -> 0.4;
        };
    }
}
