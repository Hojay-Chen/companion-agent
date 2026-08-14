package com.luxera.companion.agent;

import com.luxera.companion.conversation.Message;
import com.luxera.companion.config.AppProperties;
import com.luxera.companion.llm.ChatRequest;
import com.luxera.companion.llm.LlmMessage;
import com.luxera.companion.llm.LlmRouter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 伴侣运行时: 编排一次对话的完整流程。
 * (设计文档 38-39/101 节)
 */
@Slf4j
@Component
public class CompanionRuntime {

    private final ContextBuilder contextBuilder;
    private final PromptAssembler promptAssembler;
    private final PerceptionEngine perceptionEngine;
    private final NaturalnessEngine naturalnessEngine;
    private final LlmRouter llm;
    private final AgentPostProcessor postProcessor;
    private final AppProperties props;

    public CompanionRuntime(ContextBuilder contextBuilder, PromptAssembler promptAssembler,
                            PerceptionEngine perceptionEngine, NaturalnessEngine naturalnessEngine,
                            LlmRouter llm, AgentPostProcessor postProcessor, AppProperties props) {
        this.contextBuilder = contextBuilder;
        this.promptAssembler = promptAssembler;
        this.perceptionEngine = perceptionEngine;
        this.naturalnessEngine = naturalnessEngine;
        this.llm = llm;
        this.postProcessor = postProcessor;
        this.props = props;
    }

    /**
     * 生成回复。onDelta 用于 SSE 逐 token 推送。
     * 返回经自然度校验后的最终文本与感知结果。
     */
    public ChatOutcome generate(String userId, String companionId, String conversationId,
                                String userText, List<Message> recentMessages,
                                Consumer<String> onDelta) {
        PerceptionEngine.Perception perception = perceptionEngine.perceive(userText);
        AgentContext ctx = contextBuilder.build(userId, companionId, recentMessages);
        String system = promptAssembler.buildSystem(ctx);

        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.system(system));
        for (Message m : recentMessages) {
            String role = "user".equals(m.getSenderType()) ? "user" : "assistant";
            messages.add(new LlmMessage(role, m.getContent()));
        }

        StringBuilder raw = new StringBuilder();
        Map<String, String> meta = Map.of(
                "companionName", ctx.companion.getName(),
                "intent", perception.intent(),
                "emotion", perception.emotion());
        Consumer<String> stream = delta -> {
            raw.append(delta);
            if (onDelta != null) {
                onDelta.accept(delta);
            }
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

        var validation = naturalnessEngine.validate(raw.toString());
        String reply = validation.cleaned();
        if (!validation.issues().isEmpty()) {
            log.debug("自然度校验问题: {}", validation.issues());
        }

        postProcessor.afterExchange(userId, companionId, conversationId, userText, reply, perception);
        return new ChatOutcome(reply, raw.toString(), perception, ctx);
    }

    /** 一次对话的产物 */
    public record ChatOutcome(String reply, String rawReply,
                              PerceptionEngine.Perception perception, AgentContext context) {}
}
