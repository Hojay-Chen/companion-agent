package com.luxera.companion.application.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.luxera.companion.application.manifest.ApplicationManifest;
import com.luxera.companion.contracts.application.ApplicationEvent;
import com.luxera.companion.contracts.spi.ApplicationEventSink;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;

/**
 * LAP v1: 应用事件出平台的唯一出口。
 *
 * <p><b>三道闸, 缺一不可</b>:
 * <ol>
 *   <li><b>类型级</b>(这里) —— 只有 manifest 里标了 {@code triggersAgent: true} 的事件会被转发。
 *       应用不能自己决定"叫醒数字人", 那是它的 manifest 在<em>发布时</em>就申明过的事。
 *       没声明的事件照常进 {@code ActionResponse.events}(调用方看得见), 只是不推向 DH。</li>
 *   <li><b>实例级</b>(这里 + DH 各查一次) —— {@code data.agentTrigger} 说这一次具体要不要。
 *       井字棋的 {@code game.move} 类型上标了 true, 但轮到谁、局是否终了决定了某一步并不需要
 *       叫醒任何人。这里查是为了不做无用功, DH 那边再查一次是防御 —— 那边是独立消费者。</li>
 *   <li><b>路由</b>({@link AgentRouteResolver}) —— 应用说"还有谁该知道这件事", 平台答"其中谁
 *       是数字人", 答得出来才把 {@code data.companionId} 盖上并投递。
 *       <b>这是应用里不出现 Human / Agent 分支的关键</b>: 它只陈述自己的对局者是谁。</li>
 * </ol>
 *
 * <p><b>投递在事务提交之后</b>, 由 {@code afterCommit} 保证。在事务内投递的话, 数字人可能读到
 * 一个随后回滚的状态, 然后自信地回应一步根本没发生的棋。没有事务时立即投递 —— 那种情况下
 * 没有"以后"可言。
 *
 * <p><b>本轮是进程内直投。</b>{@code delivery_mode=INBOX}(持久投递, 订阅者自己来取)要等 R8 的
 * outbox relay。在那之前 {@code SubscriptionService} 只记录与匹配, 不假装能持久投递 ——
 * 这一条写在类注释里而不是留给人猜, 是因为"订阅看起来生效了但其实没有"是这类系统里最贵的
 * 一类误解。
 *
 * <p>投递失败<em>不影响</em>动作结果: 动作已经提交了, 数字人没接住这条事件是它自己的问题,
 * 不该让一次成功的落子变成 500。
 */
@Slf4j
@Service
public class LapEventPublisher {

    private final List<ApplicationEventSink> sinks;
    private final AgentRouteResolver router;
    private final ObjectMapper objectMapper;

    public LapEventPublisher(List<ApplicationEventSink> sinks,
                             AgentRouteResolver router,
                             ObjectMapper objectMapper) {
        this.sinks = List.copyOf(sinks);
        this.router = router;
        this.objectMapper = objectMapper;
    }

    /** 按 manifest 过滤后, 在事务提交之后交给所有 sink。 */
    public void publishAfterCommit(ApplicationManifest manifest, List<ApplicationEvent> events) {
        List<ApplicationEvent> forward = forwardable(manifest, events);
        if (forward.isEmpty()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deliver(forward);
                }
            });
        } else {
            deliver(forward);
        }
    }

    /** 平台自己的维护任务(回收器、测试)用: 立即投递, 不经事务同步。 */
    public void publishNow(ApplicationManifest manifest, List<ApplicationEvent> events) {
        deliver(forwardable(manifest, events));
    }

    private List<ApplicationEvent> forwardable(ApplicationManifest manifest, List<ApplicationEvent> events) {
        if (events == null || events.isEmpty() || sinks.isEmpty()) {
            return List.of();
        }
        return events.stream()
                .filter(event -> triggersAgent(manifest, event.type()))
                .filter(LapEventPublisher::wantedByInstance)
                .toList();
    }

    private void deliver(List<ApplicationEvent> events) {
        for (ApplicationEvent event : events) {
            Optional<AgentRouteResolver.Route> route = router.resolve(event.source(), event);
            if (route.isEmpty()) {
                log.debug("[LapEventPublisher] {} 没有可投递的数字人, 事件不出平台: {}", event.type(), event.id());
                continue;
            }
            ApplicationEvent routed = withRouting(event, route.get());
            for (ApplicationEventSink sink : sinks) {
                try {
                    sink.emit(routed);
                } catch (Exception e) {
                    log.warn("[LapEventPublisher] 事件投递失败 {} → {}: {}",
                            routed.id(), sink.getClass().getSimpleName(), e.getMessage());
                }
            }
        }
    }

    /**
     * 把路由结果盖进 {@code data}。应用的原始 payload 一个字节都不动 —— 只加两个平台才知道的键
     * ({@code companionId} / {@code userId}), 于是 {@code ActionResponse.events} 里应用自己看见的
     * 那份事件与投递给数字人的那份内容一致, 只是后者多了收件人。
     */
    private ApplicationEvent withRouting(ApplicationEvent event, AgentRouteResolver.Route route) {
        JsonNode data = event.data();
        ObjectNode enriched = data != null && data.isObject()
                ? ((ObjectNode) data).deepCopy()
                : objectMapper.createObjectNode();
        enriched.put("companionId", route.companionId());
        if (route.userId() != null) {
            enriched.put("userId", route.userId());
        }
        return new ApplicationEvent(event.id(), event.type(), event.source(), event.target(),
                event.occurredAt(), enriched);
    }

    /** {@code data.agentTrigger}, 缺省 false —— 见类注释的第 2 道闸。 */
    private static boolean wantedByInstance(ApplicationEvent event) {
        JsonNode data = event.data();
        return data != null && data.path("agentTrigger").asBoolean(false);
    }

    private static boolean triggersAgent(ApplicationManifest manifest, String eventType) {
        return manifest.events().stream()
                .anyMatch(decl -> decl.type().equals(eventType) && decl.triggersAgent());
    }
}
