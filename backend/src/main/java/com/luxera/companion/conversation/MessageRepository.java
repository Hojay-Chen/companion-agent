package com.luxera.companion.conversation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface MessageRepository extends JpaRepository<Message, String> {
    List<Message> findByConversationIdOrderByCreatedAtAsc(String conversationId);
    List<Message> findTop200ByConversationIdOrderByCreatedAtDesc(String conversationId);
    long countByConversationId(String conversationId);

    @Query("select m from Message m where m.senderType = 'user' and m.createdAt >= :since "
            + "and m.conversationId in (select c.id from Conversation c where c.companionId = :companionId)")
    List<Message> findUserMessagesSince(@Param("companionId") String companionId,
                                        @Param("since") LocalDateTime since);

    @Query("select m from Message m where m.createdAt >= :since and m.createdAt < :until "
            + "and m.conversationId in (select c.id from Conversation c where c.companionId = :companionId) "
            + "order by m.createdAt asc")
    List<Message> findMessagesBetween(@Param("companionId") String companionId,
                                      @Param("since") LocalDateTime since,
                                      @Param("until") LocalDateTime until);
}
