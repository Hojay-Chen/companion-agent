package com.luxera.companion.tool;

import com.luxera.companion.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class ReminderService {

    private final ReminderRepository repo;

    public ReminderService(ReminderRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public Reminder create(String userId, String companionId, String type, String title,
                           String content, LocalDateTime remindAt, Map<String, Object> payload) {
        if (remindAt == null) throw BusinessException.badRequest("提醒时间不能为空");
        Reminder r = new Reminder();
        r.setUserId(userId);
        r.setCompanionId(companionId);
        r.setType(type == null ? "user_set" : type);
        r.setTitle(title);
        r.setContent(content);
        r.setRemindAt(remindAt);
        r.setPayload(payload);
        return repo.save(r);
    }

    @Transactional(readOnly = true)
    public List<Reminder> list(String userId, String companionId) {
        return repo.findByUserIdAndCompanionIdOrderByRemindAtAsc(userId, companionId);
    }

    @Transactional(readOnly = true)
    public List<Reminder> pending(String companionId) {
        return repo.findByCompanionIdAndStatusOrderByRemindAtAsc(companionId, "pending");
    }

    @Transactional
    public Reminder markDone(String userId, String reminderId) {
        Reminder r = repo.findById(reminderId)
                .filter(x -> x.getUserId().equals(userId))
                .orElseThrow(() -> new javax.persistence.EntityNotFoundException("提醒不存在"));
        r.setStatus("done");
        return repo.save(r);
    }

    @Transactional
    public void delete(String userId, String reminderId) {
        Reminder r = repo.findById(reminderId)
                .filter(x -> x.getUserId().equals(userId))
                .orElseThrow(() -> new javax.persistence.EntityNotFoundException("提醒不存在"));
        r.setStatus("cancelled");
        repo.save(r);
    }
}
