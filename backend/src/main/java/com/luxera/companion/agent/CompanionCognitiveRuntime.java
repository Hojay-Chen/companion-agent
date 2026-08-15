package com.luxera.companion.agent;

import com.luxera.companion.conversation.Message;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

/**
 * 统一认知内核(设计文档 V2.0 §27): 用户消息与无人状态运行使用同一个内核。
 * 这是 V2.0 最重要的架构统一。
 */
public interface CompanionCognitiveRuntime {

    /** 处理一条用户消息(同步生成回复, 异步学习) */
    CognitiveResult processUserMessage(String userId, String companionId, String conversationId,
                                       String userMessageId, String userText,
                                       List<Message> recentMessages, Consumer<String> onDelta);

    /** 无用户交互时的生命/思想/情绪/主动推进 */
    void tick(String companionId, LocalDateTime now);
}
