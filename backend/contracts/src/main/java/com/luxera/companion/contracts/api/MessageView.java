package com.luxera.companion.contracts.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * V10 §44 Cross-module read-only view of a chat message. DH-side gets this from
 * {@code SimulatorClient.readMessages} — never by reading the chat DB directly. The set of
 * fields is intentionally minimal; richer domain objects stay inside chat-platform.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MessageView(
        String id,
        String conversationId,
        String senderType,
        String senderId,
        String content,
        String messageKind,
        Instant createdAt,
        String deliveryStatus
) {}
