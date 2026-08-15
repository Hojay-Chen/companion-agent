package com.luxera.companion.interaction;

/** 交互行为(设计文档 V3 §四): 收到消息后 Agent 先决定"要不要回复", 再决定"回复什么" */
public enum InteractionAction {
    /** 正常回复 */
    REPLY_NOW,
    /** 极短应和(哈哈→笑死) */
    SHORT_ACK,
    /** 延迟再回 */
    DELAY_REPLY,
    /** 等待/不打断 */
    WAIT,
    /** 合法地不回 */
    IGNORE,
    /** 结束本轮对话 */
    END_CONVERSATION
}
