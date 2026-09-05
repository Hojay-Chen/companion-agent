package com.luxera.companion.contracts.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * V10 §44 Cross-module read-only view of a conversation. DH-side gets this from
 * {@code SimulatorClient.listConversations}. The persona-specific title is not exposed here
 * — DH owns persona state.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConversationView(
        String id,
        String title,
        Instant lastMessageAt,
        long messageCount,
        boolean unread
) {}
