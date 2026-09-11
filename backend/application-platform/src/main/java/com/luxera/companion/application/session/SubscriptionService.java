package com.luxera.companion.application.session;

import com.luxera.companion.application.domain.SubscriptionRecord;
import com.luxera.companion.application.principal.ResolvedPrincipal;
import com.luxera.companion.application.repository.SubscriptionRepository;
import com.luxera.companion.contracts.application.ResourceUriPattern;
import com.luxera.companion.contracts.application.SubscriptionRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * LAP v1: 订阅 —— 订阅者说"这一类资源上的这一类事件我要", 而不是轮询。
 *
 * <p><b>老实说它今天能做什么。</b>{@code delivery_mode=SINK} 在本轮是<em>发射前的过滤器</em>:
 * 事件投递前按 URI pattern 与事件类型筛一遍。{@code INBOX}(持久投递、订阅者自己来取)要等 R8 的
 * outbox relay —— 在那之前 {@code INBOX} 是被<em>接受但降级</em>为 SINK 的, 并且这里会
 * 记一条 WARN。假装它能持久投递, 会让订阅者永远等一条不会来的消息。
 *
 * <p><b>它不是调度器。</b>"15:00 提醒我"是提醒应用自己的到期任务({@code ReminderDispatchJob}),
 * 不是订阅。把两者混为一谈会做出一个从不提醒任何人的 Agent: 订阅只在"有事件发生"时起作用,
 * 而"到点了"不是事件, 是时间。
 */
@Slf4j
@Service
public class SubscriptionService {

    private final SubscriptionRepository subscriptions;

    public SubscriptionService(SubscriptionRepository subscriptions) {
        this.subscriptions = subscriptions;
    }

    @Transactional
    public SubscriptionRecord subscribe(ResolvedPrincipal principal,
                                        String sessionId,
                                        SubscriptionRequest request) {
        if (request == null) {
            throw new SessionException("SUBSCRIPTION_REQUIRED", "缺少订阅请求");
        }
        String mode = request.deliveryMode();
        if (SubscriptionRequest.MODE_INBOX.equals(mode)) {
            log.warn("[Subscription] INBOX 模式要到 R8 的 outbox relay 才真正持久; "
                    + "本次按 SINK 记录 (principal={})", principal.principalId());
            mode = SubscriptionRequest.MODE_SINK;
        }
        SubscriptionRecord row = new SubscriptionRecord();
        row.setSessionId(sessionId);
        row.setPrincipalType(principal.typeName());
        row.setPrincipalId(principal.principalId());
        row.setResourceUriPattern(request.resourceUriPattern() == null
                ? "**" : request.resourceUriPattern());
        row.setEventTypes(request.eventTypes().isEmpty() ? "" : String.join(",", request.eventTypes()));
        row.setDeliveryMode(mode);
        row.setStatus(SubscriptionRecord.STATUS_ACTIVE);
        row.setExpiresAt(request.expiresAt());
        return subscriptions.save(row);
    }

    /** 这个 (资源, 事件类型) 有没有活跃订阅者。R8 的 outbox relay 会用它决定要不要落 INBOX。 */
    public boolean matchesAny(String resourceUri, String eventType) {
        for (SubscriptionRecord row : subscriptions.findByStatus(SubscriptionRecord.STATUS_ACTIVE)) {
            if (!row.active()) {
                continue;
            }
            if (!ResourceUriPattern.matches(row.getResourceUriPattern(), resourceUri)) {
                continue;
            }
            String types = row.getEventTypes();
            if (types == null || types.isBlank() || List.of(types.split(",")).contains(eventType)) {
                return true;
            }
        }
        return false;
    }

    @Transactional
    public void revoke(String subscriptionId, ResolvedPrincipal principal) {
        subscriptions.findById(subscriptionId).ifPresent(row -> {
            if (!row.getPrincipalType().equals(principal.typeName())
                    || !row.getPrincipalId().equals(principal.principalId())) {
                throw new SessionException("NOT_SUBSCRIPTION_OWNER", "不是这条订阅的订阅者");
            }
            row.setStatus(SubscriptionRecord.STATUS_REVOKED);
            subscriptions.save(row);
        });
    }

    public List<SubscriptionRecord> of(ResolvedPrincipal principal) {
        return subscriptions.findByPrincipalTypeAndPrincipalIdAndStatus(
                principal.typeName(), principal.principalId(), SubscriptionRecord.STATUS_ACTIVE);
    }
}
