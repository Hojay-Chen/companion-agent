package com.luxera.companion.agent;

import com.luxera.companion.memory.MemoryExtractor;
import com.luxera.companion.relationship.RelationshipEngine;
import com.luxera.companion.state.AgentStateService;
import com.luxera.companion.usermodel.UserModelExtractor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/** 对话后的异步后处理: 感知精炼 + 记忆/用户模型抽取 + 关系与状态演化(设计文档 47/101 节) */
@Slf4j
@Component
public class AgentPostProcessor {

    private final PerceptionRefiner perceptionRefiner;
    private final MemoryExtractor memoryExtractor;
    private final UserModelExtractor userModelExtractor;
    private final RelationshipEngine relationshipEngine;
    private final AgentStateService agentStateService;

    public AgentPostProcessor(PerceptionRefiner perceptionRefiner, MemoryExtractor memoryExtractor,
                              UserModelExtractor userModelExtractor, RelationshipEngine relationshipEngine,
                              AgentStateService agentStateService) {
        this.perceptionRefiner = perceptionRefiner;
        this.memoryExtractor = memoryExtractor;
        this.userModelExtractor = userModelExtractor;
        this.relationshipEngine = relationshipEngine;
        this.agentStateService = agentStateService;
    }

    @Async
    public void afterExchange(String userId, String companionId, String conversationId, String userMessageId,
                              String userText, String reply, PerceptionEngine.Perception perception,
                              List<String> validationIssues) {
        try {
            perceptionRefiner.refineAfterExchange(userId, companionId, conversationId, userMessageId,
                    userText, reply, perception);
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
}
