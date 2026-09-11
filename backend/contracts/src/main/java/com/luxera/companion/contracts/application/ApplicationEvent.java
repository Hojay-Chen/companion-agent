package com.luxera.companion.contracts.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * LAP v1 — one event vocabulary, shared by humans, agents and the platform.
 *
 * <p>Six fields, and deliberately no seventh: a parallel event system is the named failure mode
 * this shape exists to prevent. An application emits these; the platform converts them to its
 * internal {@code ExternalEvent} and feeds the existing processing chain. Nothing downstream
 * invents its own envelope.
 *
 * <p><b>{@code id} must be deterministic</b> for any event an agent should react to. The
 * application derives it from its manifest's {@code events[].idTemplate} (e.g.
 * {@code game://session/{id}#move-4}), never from a random UUID — a fresh id on every retry
 * would defeat deduplication and let the agent act twice on one event. The manifest validator
 * rejects {@code triggersAgent} events that declare no template.
 *
 * @param id         deterministic event id
 * @param type       application-defined event type ({@code game.move}, {@code reminder.due})
 * @param source     applicationId of the emitter
 * @param target     resource URI the event is about
 * @param occurredAt when it happened
 * @param data       event payload; opaque to the platform
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApplicationEvent(
        String id,
        String type,
        String source,
        String target,
        Instant occurredAt,
        JsonNode data
) {}
