package com.luxera.companion.phone;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PhoneNotificationRepository extends JpaRepository<PhoneNotification, String> {

    List<PhoneNotification> findByCompanionIdOrderByCreatedAtDesc(String companionId);

    List<PhoneNotification> findByMessageId(String messageId);
}
