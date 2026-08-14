package com.luxera.companion.tool;

import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 生日服务: 每日确保每位伴侣有一条待触发的生日提醒。
 * (伴侣生日是"出生日期",年龄动态计算,生日提醒每年一次)
 */
@Component
public class BirthdayService {

    private final CompanionRepository companionRepo;
    private final ReminderRepository reminderRepo;

    public BirthdayService(CompanionRepository companionRepo, ReminderRepository reminderRepo) {
        this.companionRepo = companionRepo;
        this.reminderRepo = reminderRepo;
    }

    @Scheduled(cron = "${app.scheduler.birthday-cron}")
    @Transactional
    public void ensureBirthdayReminders() {
        for (Companion c : companionRepo.findAll()) {
            if (c.getDeletedAt() != null || c.getBirthDate() == null) continue;
            boolean hasPending = reminderRepo.findByCompanionIdAndStatusOrderByRemindAtAsc(c.getId(), "pending")
                    .stream().anyMatch(r -> "birthday".equals(r.getType()));
            if (!hasPending) {
                Reminder r = new Reminder();
                r.setUserId(c.getUserId());
                r.setCompanionId(c.getId());
                r.setType("birthday");
                r.setTitle("今天是" + c.getName() + "的生日");
                r.setContent("祝" + c.getName() + "生日快乐,一年又一年,你都在。");
                r.setRemindAt(nextBirthday(c.getBirthDate(), 8));
                reminderRepo.save(r);
            }
        }
    }

    private static LocalDateTime nextBirthday(LocalDate birth, int hour) {
        LocalDate today = LocalDate.now();
        LocalDate next = birth.withYear(today.getYear());
        if (next.isBefore(today) || next.isEqual(today)) {
            next = next.plusYears(1);
        }
        return next.atTime(hour, 0);
    }
}
