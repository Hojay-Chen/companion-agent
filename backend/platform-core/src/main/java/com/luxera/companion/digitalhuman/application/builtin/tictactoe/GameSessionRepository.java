package com.luxera.companion.digitalhuman.application.builtin.tictactoe;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GameSessionRepository extends JpaRepository<GameSession, String> {

    Optional<GameSession> findByRoomId(String roomId);

    List<GameSession> findByUserIdAndStatus(String userId, String status);

    List<GameSession> findByCompanionIdAndStatus(String companionId, String status);
}