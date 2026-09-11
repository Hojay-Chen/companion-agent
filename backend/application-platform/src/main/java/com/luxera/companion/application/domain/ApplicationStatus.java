package com.luxera.companion.application.domain;

import java.util.Set;

/**
 * LAP v1 §Lifecycle: 应用的生命周期状态机。
 *
 * <pre>
 * DRAFT → DEVELOPING → TESTING → SUBMITTED → REVIEWING
 *                                   ↑              │
 *                                   └── REJECTED ──┘
 *                                                  │ APPROVED
 *                                                  ▼
 *              SUSPENDED ◄──────────────────── PUBLISHED ────► DEPRECATED
 *                                                  ▲
 *                                                  └── SUSPENDED
 * </pre>
 *
 * <p>只有 {@link #PUBLISHED} 的应用会出现在发现链里 —— 一个还在 {@code DEVELOPING} 的应用
 * 能被用户看到并点击, 是这个状态机存在的全部理由。
 *
 * <p>{@link #isMutable()} 决定 manifest 还能不能改: 一旦 PUBLISHED, manifest / hash /
 * runtime_type 全部冻结。要改就发 1.0.1 —— 于是 Manifest、Action、Handler、Resource schema
 * 与版本稳定绑定, {@code action_invocation} 指向的历史版本永远可解释。
 */
public enum ApplicationStatus {

    DRAFT,
    DEVELOPING,
    TESTING,
    SUBMITTED,
    REVIEWING,
    REJECTED,
    APPROVED,
    PUBLISHED,
    SUSPENDED,
    DEPRECATED;

    /** 只有这两个状态允许改写 manifest(发布后不可变)。 */
    public boolean isMutable() {
        return this == DRAFT || this == DEVELOPING;
    }

    /** 是否出现在发现链里。 */
    public boolean isDiscoverable() {
        return this == PUBLISHED;
    }

    private static final Set<ApplicationStatus> LEGAL_SUCCESSORS = Set.of(
            DRAFT, DEVELOPING, TESTING, SUBMITTED, REVIEWING, REJECTED,
            APPROVED, PUBLISHED, SUSPENDED, DEPRECATED);

    /**
     * 合法迁移: 只能往前走一步, 或从 PUBLISHED 挂起/废弃。
     * R8 的 {@code ApplicationLifecycleService} 用它拒绝跳步 —— 但表里没有任何一行是靠
     * "记得检查"保证的, 所以这里给的是唯一的判定入口。
     */
    public boolean canMoveTo(ApplicationStatus next) {
        if (next == null || next == this || !LEGAL_SUCCESSORS.contains(next)) return false;
        return switch (this) {
            case DRAFT -> next == DEVELOPING;
            case DEVELOPING -> next == TESTING || next == DRAFT;
            case TESTING -> next == SUBMITTED || next == DEVELOPING;
            case SUBMITTED -> next == REVIEWING || next == DEVELOPING;
            case REVIEWING -> next == APPROVED || next == REJECTED;
            case REJECTED -> next == DEVELOPING;
            case APPROVED -> next == PUBLISHED;
            case PUBLISHED -> next == SUSPENDED || next == DEPRECATED;
            case SUSPENDED -> next == PUBLISHED || next == DEPRECATED;
            case DEPRECATED -> false;
        };
    }
}
