package com.luxera.companion.conversation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SessionSummaryRepository extends JpaRepository<SessionSummary, String> {
    Optional<SessionSummary> findByConversationId(String conversationId);
}
