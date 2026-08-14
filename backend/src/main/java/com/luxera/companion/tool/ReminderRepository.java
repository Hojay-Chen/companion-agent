package com.luxera.companion.tool;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface ReminderRepository extends JpaRepository<Reminder, String> {
    List<Reminder> findByUserIdAndCompanionIdOrderByRemindAtAsc(String userId, String companionId);
    List<Reminder> findByCompanionIdAndStatusOrderByRemindAtAsc(String companionId, String status);
    List<Reminder> findByStatusAndRemindAtBefore(String status, LocalDateTime now);
    long countByCompanionIdAndStatus(String companionId, String status);
}
