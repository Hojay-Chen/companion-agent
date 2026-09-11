package com.luxera.companion.digitalhuman.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * V10 §9.2 Event Router: 事件类型 → 处理回调 的路由注册表(Registry Pattern)。
 *
 * 各 Runtime(Perception/Life/Conversation...) 在启动时注册自己关心的事件类型;
 * 链的 RoutingHandler 通过本注册表把事件转交到正确的 Runtime。
 * 回调在 Person Actor 的串行上下文中执行(由调用方保证), 保证单 Person 状态串行。
 */
@Slf4j
@Component
public class EventRouter {

    private final Map<ExternalEventType, Consumer<ExternalEvent>> routes = new ConcurrentHashMap<>();

    /** 注册某类型事件的处理回调(重复注册覆盖) */
    public void register(ExternalEventType type, Consumer<ExternalEvent> handler) {
        routes.put(type, handler);
        log.debug("[EventRouter] 注册路由: {}", type);
    }

    public void unregister(ExternalEventType type) {
        routes.remove(type);
    }

    /** 是否存在该类型的路由 */
    public boolean hasRoute(ExternalEventType type) {
        return routes.containsKey(type);
    }

    /** 路由并消费事件; 返回 false 表示无路由 */
    public boolean route(ExternalEvent event) {
        Consumer<ExternalEvent> handler = routes.get(event.type());
        if (handler == null) {
            return false;
        }
        handler.accept(event);
        return true;
    }
}
