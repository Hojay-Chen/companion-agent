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
import com.luxera.companion.state.AgentState;
import com.luxera.companion.state.AgentStateService;
import com.luxera.companion.state.AvailabilityService;
import com.luxera.companion.thought.ThoughtService;
import com.luxera.companion.emotion.EmotionService;
import com.luxera.companion.usermodel.UserChatStyleService;
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
    private final AvailabilityService availabilityService;
    private final EmotionService emotionService;
    private final ThoughtService thoughtService;
    private final OpenLoopService openLoopService;
    private final SelfModelService selfModelService;
    private final UserModelService userModelService;
    private final UserChatStyleService userChatStyleService;
    private final com.luxera.companion.memory.MemoryEntityService entityService;
    private final RelationshipService relationshipService;
    private final MemoryService memoryService;
    private final WorkingMemory workingMemory;
    private final LifeContextProvider lifeContextProvider;
    private final CompanionSchedule schedule;
    private final com.luxera.companion.config.AppProperties props;

    public ContextLoader(CompanionService companionService, CompanionLifeService lifeService,
                         AgentStateService agentStateService, AvailabilityService availabilityService,
                         EmotionService emotionService, ThoughtService thoughtService,
                         OpenLoopService openLoopService, SelfModelService selfModelService,
                         UserModelService userModelService, UserChatStyleService userChatStyleService,
                         com.luxera.companion.memory.MemoryEntityService entityService,
                         RelationshipService relationshipService, MemoryService memoryService,
                         WorkingMemory workingMemory, LifeContextProvider lifeContextProvider,
                         CompanionSchedule schedule, com.luxera.companion.config.AppProperties props) {
        this.companionService = companionService;
        this.lifeService = lifeService;
        this.agentStateService = agentStateService;
        this.availabilityService = availabilityService;
        this.emotionService = emotionService;
        this.thoughtService = thoughtService;
        this.openLoopService = openLoopService;
        this.selfModelService = selfModelService;
        this.userModelService = userModelService;
        this.userChatStyleService = userChatStyleService;
        this.entityService = entityService;
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
        LocalDateTime now = LocalDateTime.now();
        var availability = availabilityService.current(companionId, now, state);
        var episodes = emotionService.activeEpisodes(companionId);
        var thoughts = thoughtService.activeThoughts(companionId);
        var loops = openLoopService.activeLoops(companionId);
        var selfModel = selfModelService.get(companionId);
        var userModel = userModelService.summary(userId, companionId);
        var userChatStyle = userChatStyleService.get(companionId);
        var entities = entityService.recent(userId, companionId, 8);
        var relationship = relationshipService.find(userId, companionId);
        String query = recentMessages.isEmpty() ? "" : recentMessages.get(recentMessages.size() - 1).getContent();
        var memories = memoryService.retrieve(userId, companionId, query, props.getAgent().getMemoryTopN());
        var wm = conversationId != null ? workingMemory.get(companionId, conversationId) : null;
        String scheduleDesc = schedule.describe(companionId, companion.getName(), now);
        return new CompanionContext(companion, persona, life, state, availability, episodes, thoughts, loops,
                selfModel, userModel, userChatStyle, entities, relationship, memories, recentMessages, wm, perception,
                scheduleDesc, toolResult, now);
    }

    /** 加载学习上下文(设计文档 V2.0 §29): 供反思/记忆/人格学习使用 */
    public LearningContext loadLearning(String userId, String companionId, List<String> recentExperienceSummary) {
        var companion = companionService.requireOwned(userId, companionId);
        var userModel = userModelService.summary(userId, companionId);
        String lifeSummary = lifeContextProvider.describeToday(companionId, companion.getName());
        return new LearningContext(companionId, companion.getName(), recentExperienceSummary,
                userModel, lifeSummary, LocalDateTime.now());
    }
}
