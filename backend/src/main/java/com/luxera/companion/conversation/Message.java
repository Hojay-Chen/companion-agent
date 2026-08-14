package com.luxera.companion.conversation;

import com.luxera.companion.common.convert.StringMapConverter;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import javax.persistence.Column;
import javax.persistence.Convert;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "messages", indexes = @Index(name = "idx_messages_conv", columnList = "conversation_id"))
@Getter
@Setter
public class Message {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "conversation_id", nullable = false, length = 36)
    private String conversationId;

    /** user | companion | system */
    @Column(name = "sender_type", nullable = false, length = 16)
    private String senderType;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(length = 32)
    private String intent;

    @Column(length = 32)
    private String emotion;

    @Column(length = 64)
    private String topic;

    @Column(name = "is_proactive", nullable = false)
    private boolean proactive = false;

    @Convert(converter = StringMapConverter.class)
    @Column(columnDefinition = "text")
    private Map<String, Object> metadata;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }
}
