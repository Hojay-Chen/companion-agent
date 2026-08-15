package com.luxera.companion.event;

import com.luxera.companion.config.CurrentUser;
import com.luxera.companion.persona.CompanionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * 持久事件流(V4 §二十七): 前端 GET /events 长连接。
 * 心跳每 25s(spring 内置 SseEmitter 默认 30s 超时, 这里用长超时+自管理心跳)。
 * 认证: JWT + 伴侣归属校验。
 */
@Slf4j
@RestController
@RequestMapping("/api/companions/{companionId}/events")
public class EventStreamController {

    private static final long STREAM_TIMEOUT = 24 * 60 * 60 * 1000L; // 24h

    private final CompanionEventBus eventBus;
    private final CompanionService companionService;
    private final CurrentUser currentUser;

    public EventStreamController(CompanionEventBus eventBus, CompanionService companionService,
                                 CurrentUser currentUser) {
        this.eventBus = eventBus;
        this.companionService = companionService;
        this.currentUser = currentUser;
    }

    @GetMapping(produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String companionId) {
        String userId = currentUser.requireUserId();
        companionService.requireOwned(userId, companionId);

        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT);
        int conns = eventBus.subscribe(companionId, emitter);
        emitter.onCompletion(() -> eventBus.unsubscribe(companionId, emitter));
        emitter.onTimeout(() -> eventBus.unsubscribe(companionId, emitter));
        emitter.onError(e -> eventBus.unsubscribe(companionId, emitter));
        log.debug("事件流连接: companion={} 当前连接数={}", companionId, conns);
        return emitter;
    }

    /** 每 25s 向所有订阅者发心跳(防止代理/浏览器断开空闲连接) */
    @Scheduled(fixedRate = 25000, initialDelay = 25000)
    public void heartbeatJob() {
        try {
            eventBus.heartbeat();
        } catch (Exception e) {
            log.debug("事件流心跳异常: {}", e.getMessage());
        }
    }
}
