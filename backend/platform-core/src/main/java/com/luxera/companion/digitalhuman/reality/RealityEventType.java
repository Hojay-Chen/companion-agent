package com.luxera.companion.digitalhuman.reality;

/**
 * V10 §8.2 Reality Event 类型: 数字人真实发生过的事实。
 *
 * 事实变化(如 PlanChanged / ActivityEnded)不修改旧事件, 而是写入新事件 ——
 * append-only 语义由 RealityLedger 保证。
 */
public enum RealityEventType {

    /** 数字人发送了一条消息 */
    MESSAGE_SENT,

    /** 数字人读到消息(看到了内容) */
    MESSAGE_READ,

    /** 数字人决定延迟回复 */
    MESSAGE_DEFERRED,

    /** 数字人决定忽略消息 */
    MESSAGE_IGNORED,

    /** 活动开始/结束 */
    ACTIVITY_STARTED,
    ACTIVITY_ENDED,

    /** 计划生命周期 */
    PLAN_CREATED,
    PLAN_CHANGED,
    PLAN_EXECUTED,
    PLAN_COMPLETED,
    PLAN_CANCELLED,
    PLAN_POSTPONED,

    /** 关系变化 */
    RELATIONSHIP_CHANGED,

    /** 通用生活事件 */
    LIFE_EVENT
}
