package com.luxera.companion.agent;

import com.luxera.companion.behavior.BehaviorDecision;
import com.luxera.companion.behavior.BehaviorPolicyEngine;
import com.luxera.companion.config.AppProperties;
import com.luxera.companion.conversation.Message;
import com.luxera.companion.llm.ChatRequest;
import com.luxera.companion.llm.LlmMessage;
import com.luxera.companion.llm.LlmRouter;
import com.luxera.companion.life.LifeRuntime;
import com.luxera.companion.tool.ReminderPlanner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 默认认知内核实现(设计文档 V2.0 §27):
 * processUserMessage 统一编排 感知→上下文→行为策略→编译→生成→校验→异步学习。
 */
@Slf4j
@Component
public class DefaultCompanionCognitiveRuntime implements CompanionCognitiveRuntime {

    private final PerceptionEngine perceptionEngine;
    private final PerceptionRefiner perceptionRefiner;
    private final ContextLoader contextLoader;
    private final BehaviorPolicyEngine behaviorPolicyEngine;
    private final ContextCompiler contextCompiler;
    private final ReminderPlanner reminderPlanner;
    private final LlmRouter llm;
    private final NaturalnessEngine naturalnessEngine;
    private final AgentPostProcessor postProcessor;
    private final LifeRuntime lifeRuntime;
    private final AppProperties props;

    public DefaultCompanionCognitiveRuntime(PerceptionEngine perceptionEngine, PerceptionRefiner perceptionRefiner,
                                            ContextLoader contextLoader, BehaviorPolicyEngine behaviorPolicyEngine,
                                            ContextCompiler contextCompiler, ReminderPlanner reminderPlanner,
                                            LlmRouter llm, NaturalnessEngine naturalnessEngine,
                                            AgentPostProcessor postProcessor, LifeRuntime lifeRuntime,
                                            AppProperties props) {
        this.perceptionEngine = perceptionEngine;
        this.perceptionRefiner = perceptionRefiner;
        this.contextLoader = contextLoader;
        this.behaviorPolicyEngine = behaviorPolicyEngine;
        this.contextCompiler = contextCompiler;
        this.reminderPlanner = reminderPlanner;
        this.llm = llm;
        this.naturalnessEngine = naturalnessEngine;
        this.postProcessor = postProcessor;
        this.lifeRuntime = lifeRuntime;
        this.props = props;
    }

    @Override
    public CognitiveResult processUserMessage(String userId, String companionId, String conversationId,
                                              String userMessageId, String userText,
                                              List<Message> recentMessages, Consumer<String> onDelta) {
        // 1. 感知(质量优先: 同步 LLM 精炼, 失败回退启发式)
        PerceptionEngine.Perception heuristic = perceptionEngine.perceive(userText);
        PerceptionEngine.Perception perception = perceptionRefiner.refineNow(
                userMessageId, companionId, conversationId, userText, heuristic);

        // 2. 工具(请求提醒)
        String toolResult = "request_tool".equals(perception.intent())
                ? reminderPlanner.tryCreateFromMessage(userId, companionId, userText)
                : null;

        // 3. 加载统一上下文
        CompanionContext ctx = contextLoader.load(userId, companionId, conversationId, recentMessages,
                perception, toolResult);

        // 4. 行为策略: Runtime 决定"现在应该做什么"
        BehaviorDecision decision = behaviorPolicyEngine.decide(ctx);

        // 5. 上下文编译: 压缩为 LLM 可消费的提示
        String system = contextCompiler.buildSystem(ctx, decision);

        // 6. 组装消息
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.system(system));
        for (Message m : recentMessages) {
            String role = "user".equals(m.getSenderType()) ? "user" : "assistant";
            messages.add(new LlmMessage(role, m.getContent()));
        }

        // 7. 流式生成
        StringBuilder raw = new StringBuilder();
        Map<String, String> meta = Map.of(
                "companionName", ctx.companion.getName(),
                "intent", perception.intent(),
                "emotion", perception.emotion());
        Consumer<String> stream = delta -> {
            raw.append(delta);
            if (onDelta != null) onDelta.accept(delta);
        };
        try {
            llm.chatStream(ChatRequest.builder()
                    .messages(messages)
                    .temperature(props.getLlm().getTemperature())
                    .maxTokens(props.getLlm().getMaxTokens())
                    .metadata(meta)
                    .build(), stream);
        } catch (Exception e) {
            log.warn("LLM 流式失败,回退非流式: {}", e.getMessage());
            var r = llm.chat(ChatRequest.builder().messages(messages)
                    .temperature(props.getLlm().getTemperature()).metadata(meta).build());
            raw.append(r.getContent());
            if (onDelta != null) onDelta.accept(r.getContent());
        }

        // 8. 自然度校验
        var validation = naturalnessEngine.validate(raw.toString());
        String reply = validation.cleaned();
        if (!validation.issues().isEmpty()) {
            log.debug("自然度校验问题: {}", validation.issues());
        }

        // 9. 异步学习(记忆/用户模型/关系/状态/经历/思想/情绪)
        postProcessor.afterExchange(userId, companionId, conversationId, userMessageId, userText, reply,
                perception, validation.issues());

        return new CognitiveResult(reply, raw.toString(), perception, decision, ctx);
    }

    @Override
    public void tick(String companionId, LocalDateTime now) {
        // 无人时: 生活推进(Phase1) + 主动评估(Phase7 接入)
        lifeRuntime.tick(companionId, now);
    }
}
