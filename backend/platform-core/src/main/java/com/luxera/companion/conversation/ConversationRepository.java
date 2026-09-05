package com.luxera.companion.conversation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConversationRepository extends JpaRepository<Conversation, String> {
    List<Conversation> findByUserIdAndCompanionIdOrderByLastMessageAtDesc(String userId, String companionId);
    List<Conversation> findByCompanionIdOrderByLastMessageAtDesc(String companionId);
    long countByCompanionId(String companionId);
}
