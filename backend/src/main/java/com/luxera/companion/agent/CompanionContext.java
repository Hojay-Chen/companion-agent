package com.luxera.companion.agent;

import com.luxera.companion.conversation.Message;
import com.luxera.companion.emotion.EmotionalEpisode;
import com.luxera.companion.memory.Memory;
import com.luxera.companion.openloop.OpenLoop;
import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.Persona;
import com.luxera.companion.relationship.Relationship;
import com.luxera.companion.selfmodel.SelfModel;
import com.luxera.companion.state.AgentState;
import com.luxera.companion.state.CompanionAvailability;
import com.luxera.companion.thought.Thought;
import com.luxera.companion.usermodel.UserChatStyle;
import com.luxera.companion.usermodel.UserModelService;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 统一运行时上下文(设计文档 V2.0 §28): Runtime 用完整系统数据, Prompt 只取需要部分。
 */
public class CompanionContext {

    public final Companion companion;
    public final Persona persona;
    public final com.luxera.companion.life.CompanionLife life;
    public final AgentState state;
    public final CompanionAvailability availability;
    public final List<EmotionalEpisode> emotionalEpisodes;
    public final List<Thought> activeThoughts;
    public final List<OpenLoop> openLoops;
    public final SelfModel selfModel;
    public final UserModelService.UserModelSummary userModel;
    public final UserChatStyle userChatStyle;
    public final List<com.luxera.companion.memory.MemoryEntity> entities;
    public final Relationship relationship;
    public final List<Memory> memories;
    public final List<Message> recentMessages;
    public final WorkingMemory.WorkingContext workingMemory;
    public final PerceptionEngine.Perception perception;
    public final String scheduleDesc;
    public final String toolResult;
    public final LocalDateTime now;

    public CompanionContext(Companion companion, Persona persona,
                            com.luxera.companion.life.CompanionLife life, AgentState state,
                            CompanionAvailability availability,
                            List<EmotionalEpisode> emotionalEpisodes, List<Thought> activeThoughts,
                            List<OpenLoop> openLoops, SelfModel selfModel,
                            UserModelService.UserModelSummary userModel, UserChatStyle userChatStyle,
                            List<com.luxera.companion.memory.MemoryEntity> entities,
                            Relationship relationship,
                            List<Memory> memories, List<Message> recentMessages,
                            WorkingMemory.WorkingContext workingMemory,
                            PerceptionEngine.Perception perception, String scheduleDesc,
                            String toolResult, LocalDateTime now) {
        this.companion = companion;
        this.persona = persona;
        this.life = life;
        this.state = state;
        this.availability = availability;
        this.emotionalEpisodes = emotionalEpisodes;
        this.activeThoughts = activeThoughts;
        this.openLoops = openLoops;
        this.selfModel = selfModel;
        this.userModel = userModel;
        this.userChatStyle = userChatStyle;
        this.entities = entities;
        this.relationship = relationship;
        this.memories = memories;
        this.recentMessages = recentMessages;
        this.workingMemory = workingMemory;
        this.perception = perception;
        this.scheduleDesc = scheduleDesc;
        this.toolResult = toolResult;
        this.now = now;
    }
}

