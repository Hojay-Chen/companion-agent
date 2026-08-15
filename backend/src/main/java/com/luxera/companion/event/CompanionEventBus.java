package com.luxera.companion.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 伴侣事件总线(V4 §二十七): 内存实现, 每 companion 一组 SSE 订阅者。
 * 前端通过 GET /events 长连接订阅, 后端各模块 publish 事件实时推送。
 * 单实例内存实现; 多实例扩展(Redis)留待后续(设计 §三十六)。
 */
@Slf4j
@Component
public class CompanionEventBus {

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> subscribers = new ConcurrentHashMap<>();
    private final AtomicInteger connectionCount = new AtomicInteger();

    /** 注册订阅: 返回当前连接数(调试/监控) */
    public int subscribe(String companionId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> list = subscribers.computeIfAbsent(companionId, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        connectionCount.incrementAndGet();
        return connectionCount.get();
    }

    /** 发布事件到某 companion 的所有订阅者(失败即移除) */
    public void publish(String companionId, String event, Object data) {
        if (companionId == null) return;
        List<SseEmitter> list = subscribers.get(companionId);
        if (list == null || list.isEmpty()) return;
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name(event).data(data));
            } catch (IOException | IllegalStateException e) {
                list.remove(emitter);
                connectionCount.decrementAndGet();
            }
        }
    }

    /** 清理某连接(断连/超时) */
    public void unsubscribe(String companionId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> list = subscribers.get(companionId);
        if (list != null && list.remove(emitter)) {
            connectionCount.decrementAndGet();
        }
    }

    /** 向所有订阅者发心跳(EventStreamController 定时调用) */
    public void heartbeat() {
        Map<String, CopyOnWriteArrayList<SseEmitter>> snapshot = new ConcurrentHashMap<>(subscribers);
        snapshot.forEach((companionId, list) -> {
            for (SseEmitter emitter : list) {
                try {
                    emitter.send(SseEmitter.event().name("ping").data("{}"));
                } catch (IOException | IllegalStateException e) {
                    unsubscribe(companionId, emitter);
                }
            }
        });
    }

    public int connectionCount() {
        return connectionCount.get();
    }
}
