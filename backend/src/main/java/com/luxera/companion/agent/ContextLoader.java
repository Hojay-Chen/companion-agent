package com.luxera.companion.agent;

import com.luxera.companion.conversation.Message;
import com.luxera.companion.life.CompanionLifeService;
import com.luxera.companion.life.LifeContextProvider;
import com.luxera.companion.memory.Memory;
import com.luxera.companion.memory.MemoryService;
import com.luxera.companion.openloop.OpenLoopService;
import com.luxera.companion.persona.CompanionService;
import com.luxera.companion.relationship.RelationshipService;
import com.luxera.companion.selfmodel.SelfModelService;
import com.luxera.companion.state.AgentStateService;
import com.luxera.companion.thought.ThoughtService;
import com.luxera.companion.emotion.EmotionService;
import com.luxera.companion.usermodel.UserModelService;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/** 上下文加载器(设计文档 V2.0 §28/§29): 加载 Runtime 完整上下文 */
@Component
public class ContextLoader {

    private final CompanionService companionService;
    private final CompanionLifeService lifeService;
    private final AgentStateService agentStateService;
    private final EmotionService emotionService;
    private final ThoughtService thoughtService;
    private final OpenLoopService openLoopService;
    private final SelfModelService selfModelService;
    private final UserModelService userModelService;
    private final RelationshipService relationshipService;
    private final MemoryService memoryService;
    private final WorkingMemory workingMemory;
    private final LifeContextProvider lifeContextProvider;
    private final CompanionSchedule schedule;
    private final com.luxera.companion.config.AppProperties props;

    public ContextLoader(CompanionService companionService, CompanionLifeService lifeService,
                         AgentStateService agentStateService, EmotionService emotionService,
                         ThoughtService thoughtService, OpenLoopService openLoopService,
                         SelfModelService selfModelService, UserModelService userModelService,
                         RelationshipService relationshipService, MemoryService memoryService,
                         WorkingMemory workingMemory, LifeContextProvider lifeContextProvider,
                         CompanionSchedule schedule, com.luxera.companion.config.AppProperties props) {
        this.companionService = companionService;
        this.lifeService = lifeService;
        this.agentStateService = agentStateService;
        this.emotionService = emotionService;
        this.thoughtService = thoughtService;
        this.openLoopService = openLoopService;
        this.selfModelService = selfModelService;
        this.userModelService = userModelService;
        this.relationshipService = relationshipService;
        this.memoryService = memoryService;
        this.workingMemory = workingMemory;
        this.lifeContextProvider = lifeContextProvider;
        this.schedule = schedule;
        this.props = props;
    }

    public CompanionContext load(String userId, String companionId, String conversationId,
                                 List<Message> recentMessages,
                                 PerceptionEngine.Perception perception, String toolResult) {
        var companion = companionService.requireOwned(userId, companionId);
        var persona = companionService.getPersona(companionId);
        var life = lifeService.getOrCreate(companionId);
        var state = agentStateService.get(companionId);
        var episodes = emotionService.activeEpisodes(companionId);
        var thoughts = thoughtService.activeThoughts(companionId);
        var loops = openLoopService.activeLoops(companionId);
        var selfModel = selfModelService.get(companionId);
        var userModel = userModelService.summary(userId, companionId);
        var relationship = relationshipService.find(userId, companionId);
        String query = recentMessages.isEmpty() ? "" : recentMessages.get(recentMessages.size() - 1).getContent();
        var memories = memoryService.retrieve(userId, companionId, query, props.getAgent().getMemoryTopN());
        var wm = conversationId != null ? workingMemory.get(companionId, conversationId) : null;
        LocalDateTime now = LocalDateTime.now();
        String scheduleDesc = schedule.describe(companionId, companion.getName(), now);
        return new CompanionContext(companion, persona, life, state, episodes, thoughts, loops,
                selfModel, userModel, relationship, memories, recentMessages, wm, perception,
                scheduleDesc, toolResult, now);
    }
}
