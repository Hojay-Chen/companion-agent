package com.luxera.companion.application.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ActionLogRepository extends JpaRepository<ActionLogRecord, String> {

    List<ActionLogRecord> findByCompanionIdOrderByStartedAtDesc(String companionId);

    List<ActionLogRecord> findByActionIdOrderByStartedAtDesc(String actionId);
}