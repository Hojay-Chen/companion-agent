package com.luxera.companion.tool;

import com.luxera.companion.config.CurrentUser;
import com.luxera.companion.persona.CompanionService;
import lombok.Data;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/companions/{companionId}/reminders")
public class ReminderController {

    private final ReminderService reminderService;
    private final CompanionService companionService;
    private final CurrentUser currentUser;

    public ReminderController(ReminderService reminderService, CompanionService companionService,
                              CurrentUser currentUser) {
        this.reminderService = reminderService;
        this.companionService = companionService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<Reminder> list(@PathVariable String companionId) {
        String userId = currentUser.requireUserId();
        companionService.requireOwned(userId, companionId);
        return reminderService.list(userId, companionId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Reminder create(@PathVariable String companionId, @RequestBody CreateRequest req) {
        String userId = currentUser.requireUserId();
        companionService.requireOwned(userId, companionId);
        return reminderService.create(userId, companionId,
                req.getType(), req.getTitle(), req.getContent(), req.getRemindAt(), null);
    }

    @PutMapping("/{reminderId}/done")
    public Reminder markDone(@PathVariable String companionId, @PathVariable String reminderId) {
        String userId = currentUser.requireUserId();
        companionService.requireOwned(userId, companionId);
        return reminderService.markDone(userId, reminderId);
    }

    @DeleteMapping("/{reminderId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String companionId, @PathVariable String reminderId) {
        String userId = currentUser.requireUserId();
        companionService.requireOwned(userId, companionId);
        reminderService.delete(userId, reminderId);
    }

    @Data
    public static class CreateRequest {
        private String type;
        private String title;
        private String content;
        private LocalDateTime remindAt;
    }
}
