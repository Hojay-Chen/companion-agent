package com.luxera.companion.digitalhuman.application.builtin.tictactoe;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * V10 §9.3 Game POC: 井字棋游戏会话。
 * Real User 与 Agent 同玩一局。局面 state 存 JSON(9 宫格)。
 */
@Entity
@Table(name = "dh_game_session")
@Getter
@Setter
public class GameSession {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_FINISHED = "FINISHED";

    @Id
    @Column(length = 36)
    private String id;

    /** 房号: 同一房间的 user + companion 对弈 */
    @Column(name = "room_id", nullable = false, length = 36)
    private String roomId;

    @Column(name = "application_id", nullable = false, length = 36)
    private String applicationId = "tictactoe";

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "companion_id", nullable = false, length = 36)
    private String companionId;

    /** 局面 JSON: {"board":[...9个格子 (X/O/空)], "turn":"X|O", "winner":"X|O|DRAW|空"} */
    @Column(columnDefinition = "text")
    private String stateJson;

    @Column(length = 16)
    private String status = STATUS_OPEN;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @PrePersist
    void assignId() {
        if (id == null) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (roomId == null) roomId = UUID.randomUUID().toString();
    }
}