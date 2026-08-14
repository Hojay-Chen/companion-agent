package com.luxera.companion.agent;

import com.luxera.companion.conversation.Message;
import com.luxera.companion.config.AppProperties;
import com.luxera.companion.memory.Memory;
import com.luxera.companion.memory.MemoryService;
import com.luxera.companion.persona.CompanionService;
import com.luxera.companion.relationship.Relationship;
import com.luxera.companion.relationship.RelationshipService;
import com.luxera.companion.state.AgentState;
import com.luxera.companion.state.AgentStateService;
import com.luxera.companion.usermodel.UserModelService;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** 上下文构建: 按优先级把数据库内容聚合成运行时上下文(设计文档 40-41 节) */
@Component
public class ContextBuilder {

    private final CompanionService companionService;
    private final MemoryService memoryService;
    private final RelationshipService relationshipService;
    private final AgentStateService agentStateService;
    private final UserModelService userModelService;
    private final AppProperties props;

    public ContextBuilder(CompanionService companionService, MemoryService memoryService,
                          RelationshipService relationshipService, AgentStateService agentStateService,
                          UserModelService userModelService, AppProperties props) {
        this.companionService = companionService;
        this.memoryService = memoryService;
        this.relationshipService = relationshipService;
        this.agentStateService = agentStateService;
        this.userModelService = userModelService;
        this.props = props;
    }

    public AgentContext build(String userId, String companionId, List<Message> recentMessages) {
        var companion = companionService.requireOwned(userId, companionId);
        var persona = companionService.getPersona(companionId);
        var state = agentStateService.get(companionId);
        var relationship = relationshipService.find(userId, companionId);
        String query = recentMessages.isEmpty() ? "" : recentMessages.get(recentMessages.size() - 1).getContent();
        var memories = memoryService.retrieve(userId, companionId, query, props.getAgent().getMemoryTopN());
        var userModel = userModelService.summary(userId, companionId);
        return new AgentContext(companion, persona, state, relationship, memories, userModel, recentMessages, LocalDateTime.now());
    }

    public static String relationshipSummary(Relationship r) {
        if (r == null) return "你们刚刚认识。";
        long days = r.getStartedAt() == null ? 0 : Math.max(0, ChronoUnit.DAYS.between(r.getStartedAt().toLocalDate(), LocalDateTime.now().toLocalDate()));
        return String.format("你们处于「%s」阶段,已认识 %d 天,累计聊了 %d 条消息,有过 %d 段共同经历。",
                zhStage(r.getRelationshipStage()), days, r.getMessageCount(), r.getSharedExperienceCount());
    }

    public static String zhStage(String stage) {
        switch (stage == null ? "" : stage) {
            case "familiar": return "熟络";
            case "close": return "亲密";
            case "deeply_connected": return "深深相连";
            default: return "初识";
        }
    }
}
