package com.luxera.companion.event;

/**
 * 事件类型(V4 §二十七): 前端持久 Event Stream 可接收的事件。
 */
public final class CompanionEventType {

    /** 用户消息状态变化(DELIVERED/READ), 携带 messageId */
    public static final String USER_MESSAGE_STATUS = "user_message_status";

    /** 她已读你的消息, 携带 messageId */
    public static final String MESSAGE_READ = "message_read";

    /** 她开始/停止输入, 携带 typing:true/false */
    public static final String COMPANION_TYPING = "companion_typing";

    /** 她发的完整消息(主动/deferred 回复/ResponsePlan 后续段), 携带 message */
    public static final String COMPANION_MESSAGE = "companion_message";

    /** V8: 用户消息已持久化(同步落库后发出), 携带 messageId/clientMessageId/content/status */
    public static final String MESSAGE_CREATED = "message_created";

    /** V8: 她此刻状态变化(availability), 携带 availability */
    public static final String COMPANION_STATE = "companion_state";

    /** 系统事件 */
    public static final String SYSTEM_EVENT = "system_event";

    private CompanionEventType() {
    }
}
