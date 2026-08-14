package com.luxera.companion.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.luxera.companion.config.AppProperties;
import com.luxera.companion.conversation.Message;
import com.luxera.companion.conversation.MessageRepository;
import com.luxera.companion.llm.LlmRouter;
import com.luxera.companion.llm.StructuredRequest;
import com.luxera.companion.state.AgentStateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * LLM 感知精炼(异步): 用大模型更准确地识别意图/情绪/话题/实体,
 * 更新消息元数据、工作记忆与 Agent 状态。启发式先保证实时回复,精度由此补齐。
 * (设计文档 39 节;app.agent.intent-extraction=llm 时启用)
 */
@Slf4j
@Component
public class PerceptionRefiner {

    private static final String SYSTEM = """
            你是对话感知引擎,分析用户这条消息的状态。输出严格 JSON,不要输出其他内容:
            {
              "intent": "greeting|farewell|question|share_upset|share_joy|share_tired|request_tool|correction|planning|gratitude|ask_about_her|chat",
              "emotion": "tired|sad|anxious|angry|happy|lonely|grateful|neutral",
              "topic": "work|study|health|relationship|entertainment|food|travel|weather|daily",
              "entities": ["提到的关键对象,如咖啡/具体项目/人名/地点,最多3个"]
            }
            依据对话语境判断,不要只看关键词。""";

    private final LlmRouter llm;
    private final MessageRepository messageRepo;
    private final WorkingMemory workingMemory;
    private final AgentStateService agentStateService;
    private final AppProperties props;

    public PerceptionRefiner(LlmRouter llm, MessageRepository messageRepo, WorkingMemory workingMemory,
                             AgentStateService agentStateService, AppProperties props) {
        this.llm = llm;
        this.messageRepo = messageRepo;
        this.workingMemory = workingMemory;
        this.agentStateService = agentStateService;
        this.props = props;
    }

    /** 在异步后处理链路中调用(上层已是 @Async) */
    public void refineAfterExchange(String userId, String companionId, String conversationId,
                                    String userMessageId, String userText, String assistantText,
                                    PerceptionEngine.Perception heuristic) {
        // 未开启 LLM 感知时,仅把启发式结果记入工作记忆
        WorkingMemory.RecentLine userLine = new WorkingMemory.RecentLine("user", userText, LocalDateTime.now());
        WorkingMemory.RecentLine assistantLine = new WorkingMemory.RecentLine("companion", assistantText, LocalDateTime.now());
        workingMemory.record(companionId, conversationId, userLine, heuristic);
        workingMemory.record(companionId, conversationId, assistantLine, null);

        if (!"llm".equals(props.getAgent().getIntentExtraction())) {
            return;
        }
        try {
            String excerpt = "用户: " + userText + "\n伴侣: " + assistantText;
            var res = llm.structured(StructuredRequest.builder()
                    .task("perception")
                    .system(SYSTEM)
                    .user(excerpt)
                    .temperature(0.2)
                    .build());
            JsonNode root = res.getJson();

            String refinedIntent = root.path("intent").asText("");
            String refinedEmotion = root.path("emotion").asText("");
            String refinedTopic = root.path("topic").asText("");
            List<String> entities = new ArrayList<>();
            for (JsonNode e : root.path("entities")) {
                String s = e.asText("");
                if (!s.isBlank()) entities.add(s);
            }

            // 更新用户消息元数据
            if (userMessageId != null) {
                messageRepo.findById(userMessageId).ifPresent(m -> {
                    if (!refinedIntent.isBlank()) m.setIntent(refinedIntent);
                    if (!refinedEmotion.isBlank()) m.setEmotion(refinedEmotion);
                    if (!refinedTopic.isBlank()) m.setTopic(refinedTopic);
                    messageRepo.save(m);
                });
            }

            // 更新工作记忆
            if (!refinedIntent.isBlank() || !refinedEmotion.isBlank() || !refinedTopic.isBlank()) {
                workingMemory.record(companionId, conversationId, userLine,
                        new PerceptionEngine.Perception(refinedIntent.isBlank() ? null : refinedIntent,
                                refinedEmotion.isBlank() ? null : refinedEmotion,
                                refinedTopic.isBlank() ? null : refinedTopic));
            }
            if (!entities.isEmpty()) {
                workingMemory.setEntities(companionId, conversationId, entities);
            }

            // 精炼情绪比启发式更显著时,同步 Agent 状态
            if (!refinedEmotion.isBlank() && !refinedEmotion.equals(heuristic.emotion())) {
                agentStateService.onMessage(companionId, refinedEmotion);
            }
        } catch (Exception e) {
            log.debug("LLM 感知精炼失败,保留启发式结果: {}", e.getMessage());
        }
    }
}
