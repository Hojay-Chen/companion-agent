package com.luxera.companion.llm;

import com.luxera.companion.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.function.Consumer;

/**
 * LLM 路由: 根据配置选择实际网关。
 * 未配置 key 且 mock-fallback=true 时自动降级 Mock,保证离线可跑通全流程。
 */
@Slf4j
@Component
public class LlmRouter implements LlmGateway {

    private final AppProperties props;
    private final OpenAiCompatibleGateway openAi;
    private final AnthropicGateway anthropic;
    private final MockLlmGateway mock;

    private LlmGateway active;

    public LlmRouter(AppProperties props, OpenAiCompatibleGateway openAi,
                     AnthropicGateway anthropic, MockLlmGateway mock) {
        this.props = props;
        this.openAi = openAi;
        this.anthropic = anthropic;
        this.mock = mock;
    }

    @PostConstruct
    void init() {
        String provider = props.getLlm().getProvider();
        switch (provider == null ? "" : provider) {
            case "mock" -> active = mock;
            case "anthropic" -> {
                if (anthropic.available()) {
                    active = anthropic;
                } else if (props.getLlm().isMockFallback()) {
                    log.warn("[LLM] anthropic 未配置 api-key,降级为 mock 网关");
                    active = mock;
                } else {
                    throw new IllegalStateException("LLM provider=anthropic 但未配置 app.llm.api-key");
                }
            }
            case "openai-compatible" -> {
                if (openAi.available()) {
                    active = openAi;
                } else if (props.getLlm().isMockFallback()) {
                    log.warn("[LLM] openai-compatible(DeepSeek) 未配置 DEEPSEEK_API_KEY,降级为 mock 网关。配置后自动切换真实模型。");
                    active = mock;
                } else {
                    throw new IllegalStateException("LLM provider=openai-compatible 但未配置 app.llm.api-key");
                }
            }
            default -> throw new IllegalStateException("未知 LLM provider: " + provider);
        }
        log.info("[LLM] 网关已启用: {} (provider={})", active.name(), provider);
    }

    @Override public String name() { return active.name(); }
    @Override public boolean available() { return active.available(); }

    public boolean isMockActive() { return active == mock; }
    public String activeProvider() { return active.name(); }

    @Override
    public ChatResult chat(ChatRequest request) {
        return active.chat(request);
    }

    @Override
    public void chatStream(ChatRequest request, Consumer<String> onDelta) {
        active.chatStream(request, onDelta);
    }

    @Override
    public StructuredResult structured(StructuredRequest request) {
        // 模型用途路由(设计文档 §25): 按 task 指定模型/温度, 缺省用 chat-model
        StructuredRequest routed = applyPurpose(request);
        return active.structured(routed);
    }

    /** 按任务类型应用用途路由(感知/抽取用轻模型, 反思/演化用强模型等) */
    private StructuredRequest applyPurpose(StructuredRequest request) {
        String purposeKey = purposeFor(request.getTask());
        AppProperties.Purpose purpose = props.getLlm().getPurpose().get(purposeKey);
        if (purpose == null) return request;
        return StructuredRequest.builder()
                .system(request.getSystem())
                .user(request.getUser())
                .task(request.getTask())
                .schemaHint(request.getSchemaHint())
                .temperature(request.getTemperature() != null ? request.getTemperature() : purpose.getTemperature())
                .model(purpose.getModel())
                .build();
    }

    /** 任务 → 用途 key */
    private static String purposeFor(String task) {
        if (task == null) return "extraction";
        return switch (task) {
            case "perception" -> "perception";
            case "daily-reflection", "weekly-reflection" -> "reflection";
            case "persona-evolution" -> "persona_evolution";
            case "persona-compile" -> "extraction";
            case "memory-extraction", "user-model-extraction", "self-model-extraction",
                 "relationship-narrative", "reminder-extraction" -> "extraction";
            default -> "extraction";
        };
    }
}
