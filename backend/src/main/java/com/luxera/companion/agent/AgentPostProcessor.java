package com.luxera.companion.agent;

import com.luxera.companion.emotion.EmotionEngine;
import com.luxera.companion.experience.ExperienceProcessor;
import com.luxera.companion.memory.MemoryExtractor;
import com.luxera.companion.relationship.PromiseService;
import com.luxera.companion.relationship.Relationship;
import com.luxera.companion.relationship.RelationshipEngine;
import com.luxera.companion.relationship.RelationshipService;
import com.luxera.companion.relationship.RelationshipThreadService;
import com.luxera.companion.state.AgentStateService;
import com.luxera.companion.thought.ThoughtEngine;
import com.luxera.companion.usermodel.UserModelExtractor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 对话后的异步后处理(V2.0): Experience 记录 + Thought/Emotion/OpenLoop/承诺 触发
 * + 记忆/用户模型抽取 + 关系与状态演化。
 * 感知精炼已在回复生成前同步完成,这里不再重复。
 */
@Slf4j
@Component
public class AgentPostProcessor {

    private final ExperienceProcessor experienceProcessor;
    private final ThoughtEngine thoughtEngine;
    private final EmotionEngine emotionEngine;
    private final MemoryExtractor memoryExtractor;
    private final UserModelExtractor userModelExtractor;
    private final RelationshipEngine relationshipEngine;
    private final RelationshipService relationshipService;
    private final RelationshipThreadService threadService;
    private final PromiseService promiseService;
    private final AgentStateService agentStateService;

    public AgentPostProcessor(ExperienceProcessor experienceProcessor, ThoughtEngine thoughtEngine,
                              EmotionEngine emotionEngine, MemoryExtractor memoryExtractor,
                              UserModelExtractor userModelExtractor, RelationshipEngine relationshipEngine,
                              RelationshipService relationshipService, RelationshipThreadService threadService,
                              PromiseService promiseService, AgentStateService agentStateService) {
        this.experienceProcessor = experienceProcessor;
        this.thoughtEngine = thoughtEngine;
        this.emotionEngine = emotionEngine;
        this.memoryExtractor = memoryExtractor;
        this.userModelExtractor = userModelExtractor;
        this.relationshipEngine = relationshipEngine;
        this.relationshipService = relationshipService;
        this.threadService = threadService;
        this.promiseService = promiseService;
        this.agentStateService = agentStateService;
    }

    @Async
    public void afterExchange(String userId, String companionId, String conversationId, String userMessageId,
                              String userText, String reply, PerceptionEngine.Perception perception,
                              List<String> validationIssues) {
        try {
            // 1. 经历层: 一次交流 → Experience(设计文档 V2.0 §11)
            double importance = intentImportance(perception.intent());
            double emotionalWeight = emotionWeight(perception.emotion());
            experienceProcessor.recordConversationExchange(companionId, conversationId, userText, reply,
                    importance, emotionalWeight, 0.6);

            // 2. 想法/未完成事项(设计文档 V2.0 §6/§8)
            thoughtEngine.maybeFromConversation(companionId, userText);

            // 3. 情绪事件(设计文档 V2.0 §7)
            emotionEngine.fromConversation(companionId, perception.emotion(), perception.intent(), userText);

            // 4. 关系 2.0: 承诺识别 + 关系线索更新
            Relationship rel = relationshipService.find(userId, companionId);
            if (rel != null) {
                promiseService.maybeExtractFromText(rel.getId(), userText);
                if (perception.intent() != null && ("share_upset".equals(perception.intent())
                        || "planning".equals(perception.intent()) || "share_joy".equals(perception.intent()))) {
                    String topic = topicFrom(userText);
                    if (topic != null) {
                        threadService.createOrTouch(rel.getId(), topic, userText.length() > 60 ? userText.substring(0, 60) : userText, importance);
                    }
                }
            }

            // 5. 原有异步学习链路
            memoryExtractor.extractFromExchange(userId, companionId, conversationId, userText, reply);
            userModelExtractor.extractFromExchange(userId, companionId, conversationId, userText, reply);
            relationshipEngine.onMessage(userId, companionId, LocalDateTime.now(), perception.emotion(), perception.intent());
            if ("correction".equals(perception.intent())) {
                relationshipEngine.onUserCorrected(userId, companionId);
            }
            agentStateService.onMessage(companionId, perception.emotion());
        } catch (Exception e) {
            log.warn("对话后处理失败: {}", e.getMessage());
        }
    }

    private static String topicFrom(String text) {
        if (text == null) return null;
        for (String kw : new String[]{"面试", "工作", "项目", "考试", "旅行", "搬家", "买房", "健身", "减肥"}) {
            if (text.contains(kw)) return kw;
        }
        return null;
    }

    private static double intentImportance(String intent) {
        if (intent == null) return 0.4;
        return switch (intent) {
            case "share_upset", "share_joy", "correction", "planning" -> 0.7;
            case "request_tool" -> 0.6;
            case "question" -> 0.5;
            default -> 0.4;
        };
    }

    private static double emotionWeight(String emotion) {
        if (emotion == null) return 0.4;
        return switch (emotion) {
            case "sad", "angry", "anxious", "lonely", "happy", "grateful" -> 0.8;
            case "tired" -> 0.6;
            default -> 0.4;
        };
    }
}
