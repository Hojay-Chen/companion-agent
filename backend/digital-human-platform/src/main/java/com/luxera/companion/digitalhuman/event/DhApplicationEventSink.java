package com.luxera.companion.digitalhuman.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.contracts.application.ApplicationEvent;
import com.luxera.companion.contracts.spi.ApplicationEventSink;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LAP v1 §12: 应用事件进入数字人世界的唯一入口。
 *
 * <p>它把平台的 {@link ApplicationEvent} 翻译成数字人的 {@link ExternalEvent} 并交给
 * {@link EventProcessingChain}(Validation → Dedup → Route)。翻译里有三件事值得说明:
 *
 * <ol>
 *   <li><b>personId 从 {@code data.companionId} 取。</b> {@code ApplicationEvent} 的信封被
 *       设计成六个字段, 没有"谁的"这一栏 —— 应用发射一条会唤起 Agent 的事件时, 必须把
 *       {@code companionId} 放进 {@code data}。缺了就丢弃并告警, 绝不猜。</li>
 *   <li><b>幂等由 {@code event.id()} 承担。</b> 平台已按 manifest 的 {@code idTemplate} 保证
 *       它确定性 —— 同一个 {@code ApplicationEvent} 重放会得到同一个 {@code eventId},
 *       于是被 DeduplicationHandler 短路, 数字人不会对同一步行动两次。</li>
 *   <li><b>{@code agentTrigger} 固定为 true。</b> 走到这里的事件, 平台已经按发射方版本的
 *       manifest 过滤过 {@code triggersAgent}; 数字人不必也不该再判断一次。</li>
 * </ol>
 *
 * <p><b>本类永久留在 digital-human-platform</b>: 它是"应用平台"与"数字人平台"之间那道
 * 单向门, 两边都不该知道对方的内部事件词汇。
 */
@Slf4j
@Component
public class DhApplicationEventSink implements ApplicationEventSink {

    private final EventProcessingChain chain;
    private final ObjectMapper objectMapper;

    public DhApplicationEventSink(EventProcessingChain chain, ObjectMapper objectMapper) {
        this.chain = chain;
        this.objectMapper = objectMapper;
    }

    @Override
    public void emit(ApplicationEvent event) {
        if (event == null) return;
        String companionId = companionIdOf(event);
        if (companionId == null || companionId.isBlank()) {
            log.warn("[AppEventSink] 事件 {} 缺少 data.companionId, 无法投递 —— 已丢弃", event.id());
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("applicationId", event.source());
        payload.put("eventType", event.type());
        payload.put("resourceUri", event.target());
        payload.put("agentTrigger", Boolean.TRUE);
        payload.put("source", "application-platform");
        payload.putAll(flatten(event.data()));

        ExternalEvent external = new ExternalEvent(
                event.id(),
                companionId,
                ExternalEventType.APPLICATION_EVENT,
                event.occurredAt() == null ? Instant.now() : event.occurredAt(),
                payload,
                null);

        EventProcessingChain.ChainOutcome outcome = chain.process(external);
        log.debug("[AppEventSink] {} -> {} ({})", event.id(), event.type(), outcome.status());
    }

    private static String companionIdOf(ApplicationEvent event) {
        JsonNode data = event.data();
        if (data == null) return null;
        JsonNode companionId = data.path("companionId");
        return companionId.isMissingNode() || companionId.isNull() ? null : companionId.asText(null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> flatten(JsonNode data) {
        if (data == null || data.isNull() || !data.isObject()) return Map.of();
        try {
            return objectMapper.convertValue(data, Map.class);
        } catch (Exception e) {
            log.warn("[AppEventSink] payload 转换失败: {}", e.getMessage());
            return Map.of();
        }
    }
}
