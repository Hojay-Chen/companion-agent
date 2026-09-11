package com.luxera.companion.contracts.application;

import java.time.Instant;
import java.util.List;

/**
 * LAP v1 — an agent subscribes and waits, rather than polling.
 *
 * <p>Kept honest by being explicit about its limits: with the in-process sink, a subscription is a
 * filter evaluated just before delivery; durable delivery (an {@code INBOX} a subscriber drains)
 * only becomes real once the outbox relay exists.
 *
 * <p>One thing this is <em>not</em>: a scheduler. "Remind me at 15:00" is an application's own
 * due-time job, not a subscription — modelling it here would produce an agent that never reminds
 * anyone.
 *
 * @param resourceUriPattern URI pattern to watch; {@code *} matches one segment, {@code **} any
 * @param eventTypes         event types of interest; empty means all
 * @param deliveryMode       {@code SINK} (push now) or {@code INBOX} (durable, drain later)
 * @param expiresAt          when the subscription lapses; null means no expiry
 */
public record SubscriptionRequest(
        String resourceUriPattern,
        List<String> eventTypes,
        String deliveryMode,
        Instant expiresAt
) {

    public static final String MODE_SINK = "SINK";
    public static final String MODE_INBOX = "INBOX";

    public SubscriptionRequest {
        eventTypes = eventTypes == null ? List.of() : List.copyOf(eventTypes);
        if (deliveryMode == null) {
            deliveryMode = MODE_SINK;
        }
    }

    public boolean matches(String resourceUri, String eventType) {
        return ResourceUriPattern.matches(resourceUriPattern, resourceUri)
                && (eventTypes.isEmpty() || eventTypes.contains(eventType));
    }
}
