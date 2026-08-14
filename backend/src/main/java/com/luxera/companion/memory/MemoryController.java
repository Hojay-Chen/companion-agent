package com.luxera.companion.memory;

import com.luxera.companion.config.CurrentUser;
import com.luxera.companion.persona.CompanionService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/companions/{companionId}/memories")
public class MemoryController {

    private final MemoryService memoryService;
    private final CompanionService companionService;
    private final CurrentUser currentUser;

    public MemoryController(MemoryService memoryService, CompanionService companionService,
                            CurrentUser currentUser) {
        this.memoryService = memoryService;
        this.companionService = companionService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<Memory> list(@PathVariable String companionId,
                             @RequestParam(required = false) String type) {
        String userId = currentUser.requireUserId();
        companionService.requireOwned(userId, companionId);
        return memoryService.list(userId, companionId, type);
    }

    /** 记忆透明: 搜索记忆并按相关度返回(附发生时间/来源) */
    @GetMapping("/search")
    public List<Memory> search(@PathVariable String companionId, @RequestParam String q) {
        String userId = currentUser.requireUserId();
        companionService.requireOwned(userId, companionId);
        return memoryService.retrieve(userId, companionId, q, 20);
    }

    @GetMapping("/export")
    public Map<String, Object> export(@PathVariable String companionId) {
        String userId = currentUser.requireUserId();
        companionService.requireOwned(userId, companionId);
        return Map.of(
                "companionId", companionId,
                "count", memoryService.count(userId, companionId),
                "memories", memoryService.list(userId, companionId, null)
        );
    }

    @DeleteMapping("/{memoryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void forget(@PathVariable String companionId, @PathVariable String memoryId) {
        String userId = currentUser.requireUserId();
        companionService.requireOwned(userId, companionId);
        memoryService.forget(userId, companionId, memoryId);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearAll(@PathVariable String companionId) {
        String userId = currentUser.requireUserId();
        companionService.requireOwned(userId, companionId);
        memoryService.clearAll(userId, companionId);
    }
}
