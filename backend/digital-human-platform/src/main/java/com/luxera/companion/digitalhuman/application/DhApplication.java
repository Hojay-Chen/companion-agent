package com.luxera.companion.digitalhuman.application;

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
 * V10 §32 Application Platform 应用骨架。
 *
 * 一个应用(如游戏/工具)是数字人可以"打开并使用"的外部能力。
 * 本轮(骨架+Game POC)不验收 Agent 玩游戏, 但建立 minifest/权限/会话三件套,
 * 让"聊到游戏 Agent 仍像真人"有数据落点。
 */
@Entity
@Table(name = "dh_application")
@Getter
@Setter
public class DhApplication {

    @Id
    @Column(length = 36)
    private String id;

    /** 应用唯一编码, 如 "hello-world" / "tictactoe" */
    @Column(nullable = false, unique = true, length = 64)
    private String code;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 32)
    private String version = "1.0.0";

    /** manifest JSON(权限/生命周期钩子) — 用 @Convert 或直接 text */
    @Column(columnDefinition = "text")
    private String manifestJson;

    @Column(length = 32)
    private String status = "ACTIVE";

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    void assignId() {
        if (id == null) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}