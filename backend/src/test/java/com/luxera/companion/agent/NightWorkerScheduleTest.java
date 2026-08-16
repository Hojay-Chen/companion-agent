package com.luxera.companion.agent;

import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionRepository;
import com.luxera.companion.persona.Persona;
import com.luxera.companion.persona.PersonaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 夜班作息识别测试: persona 描述含"酒吧/夜班" → 生成颠倒作息(白天睡觉、晚上上班)。
 * 白班伴侣 → 默认作息(晚上睡觉)。
 */
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "app.scheduler.life-tick-cron=0 0 0 1 1 *",
        "app.scheduler.thread-maintenance-cron=0 0 0 1 1 *",
        "app.scheduler.unfinished-thought-cron=0 0 0 1 1 *"
})
class NightWorkerScheduleTest {

    @Autowired
    CompanionSchedule schedule;
    @Autowired
    CompanionRepository companionRepository;
    @Autowired
    PersonaService personaService;

    private String nightCompanionId;
    private String dayCompanionId;

    @BeforeEach
    void setUp() {
        nightCompanionId = UUID.randomUUID().toString();
        dayCompanionId = UUID.randomUUID().toString();

        saveCompanion(nightCompanionId);
        saveCompanion(dayCompanionId);

        // 夜班: 酒吧工作
        Persona night = new Persona();
        night.setPersonality(persona("温柔体贴,在酒吧工作让她见惯世故却依然保持真诚"));
        personaService.saveInitial(nightCompanionId, night);

        // 白班: 普通上班族
        Persona day = new Persona();
        day.setPersonality(persona("一个安静的上班族,喜欢看书"));
        personaService.saveInitial(dayCompanionId, day);
    }

    private void saveCompanion(String id) {
        Companion c = new Companion();
        c.setId(id);
        c.setUserId("night-test-user");
        c.setName("测试");
        c.setGender("female");
        companionRepository.save(c);
    }

    private Persona.Personality persona(String summary) {
        Persona.Personality p = new Persona.Personality();
        p.setSummary(summary);
        return p;
    }

    @Test
    void nightWorkerDetectedFromBarPersona() {
        assertTrue(schedule.isNightWorker(nightCompanionId), "酒吧工作应识别为夜班");
        assertFalse(schedule.isNightWorker(dayCompanionId), "普通上班族不应识别为夜班");
    }

    @Test
    void nightWorkerIsAwakeAtNightAndSleepsByDay() {
        // 23:00 夜班伴侣应该在工作(不是睡觉)
        LocalDateTime nightTime = LocalDateTime.of(2026, 8, 16, 23, 0);
        assertNotEquals(CompanionSchedule.Activity.SLEEP, schedule.activityFor(nightCompanionId, nightTime),
                "酒吧夜班伴侣 23 点应在上班, 不是睡觉");
        assertEquals(CompanionSchedule.Activity.WORK_BUSY, schedule.activityFor(nightCompanionId, nightTime));

        // 10:00 白天她在补觉
        LocalDateTime dayTime = LocalDateTime.of(2026, 8, 17, 10, 0);
        assertEquals(CompanionSchedule.Activity.SLEEP, schedule.activityFor(nightCompanionId, dayTime),
                "酒吧夜班伴侣白天应在补觉");
    }

    @Test
    void dayWorkerSleepsAtNight() {
        // 23:00 白班伴侣应该睡觉
        LocalDateTime nightTime = LocalDateTime.of(2026, 8, 16, 23, 0);
        assertEquals(CompanionSchedule.Activity.SLEEP, schedule.activityFor(dayCompanionId, nightTime),
                "白班伴侣 23 点应在睡觉");
    }

    @Test
    void nightWorkerDescribeReflectsBarWork() {
        LocalDateTime nightTime = LocalDateTime.of(2026, 8, 16, 23, 0);
        String desc = schedule.describe(nightCompanionId, "林晚晴", nightTime);
        assertTrue(desc.contains("酒吧上班"), "描述应体现酒吧上班: " + desc);
    }
}
