package com.luxera.companion.digitalhuman.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.llm.ChatRequest;
import com.luxera.companion.llm.ChatResult;
import com.luxera.companion.llm.LlmRouter;
import com.luxera.companion.llm.LlmMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * V10 §15 Conversation Runtime: **系统唯一的聊天文本生产入口**。
 *
 * 其他模块禁止直接生成最终聊天文本 —— 一切聊天文本必须经本管道:
 * 分层 Prompt(稳定层经缓存) → LLM → 输出契约解析 → 质量验证 → 失败重生成(≤2 次)。
 *
 * 输出契约(V10 §15.3): {"messages":[{"text":"..."}]}; 禁止旁白/舞台动作/AI 腔,
 * 由 OutputValidationChain 强制; 全部失败 → 不生成(像真人没说出口)。
 */
@Slf4j
@Component
public class ConversationRuntime {

    /** 最大生成尝试次数(首次 + 2 次修正重试) */
    public static final int MAX_ATTEMPTS = 3;

    private final LlmRouter llm;
    private final OutputValidationChain validator;
    private final PromptLayerCache promptCache;
    private final ObjectMapper mapper = new ObjectMapper();

    public ConversationRuntime(LlmRouter llm, OutputValidationChain validator,
                               PromptLayerCache promptCache) {
        this.llm = llm;
        this.validator = validator;
        this.promptCache = promptCache;
    }

    /**
     * 生成一条(或连发多条)聊天消息草稿 —— 全部草稿通过质量闸门。
     * 失败(LLM 异常/验证始终不过)返回空列表, 调用方应回退到"不说"或模板。
     */
    public List<ChatMessageDraft> generateDrafts(ConversationRequest request) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String raw;
            try {
                raw = callLlm(request);
            } catch (Exception e) {
                log.warn("[ConversationRuntime] LLM 调用失败 attempt={}: {}", attempt, e.getMessage());
                return List.of();
            }
            List<String> texts = parseOutputContract(raw);
            if (texts.isEmpty()) {
                // 契约解析失败: 结构问题, 重试无意义
                log.warn("[ConversationRuntime] 输出契约解析失败: {}", truncate(raw, 80));
                return List.of();
            }
            List<ChatMessageDraft> drafts = indexDrafts(texts);
            List<ConversationOutputValidator.Issue> issues = validator.validateAll(drafts);
            if (issues.isEmpty()) {
                return drafts;
            }
            log.info("[ConversationRuntime] 验证未通过, 重新生成({}/{}): {}",
                    attempt + 1, MAX_ATTEMPTS, issues.get(0).reason());
            request = request.withCorrection(describeIssues(issues));
        }
        // 重试后仍不过关 → 不说(像真人一样把话咽回去)
        log.info("[ConversationRuntime] 重试后仍未通过质量闸门, 放弃生成");
        return List.of();
    }

    /** 分层组装 + 稳定层缓存 → LLM 调用 */
    private String callLlm(ConversationRequest request) {
        String stable = promptCache.stableLayers(request.stablePrefix(), request.semiStable());
        StringBuilder system = new StringBuilder(stable);
        system.append("\n—— 当前动态 ——\n");
        for (String line : request.dynamicSuffix()) {
            if (line != null && !line.isBlank()) {
                system.append(line).append('\n');
            }
        }
        if (request.correctionHint() != null && !request.correctionHint().isBlank()) {
            system.append("\n—— 上一轮生成未通过检查, 请修正 ——\n").append(request.correctionHint()).append('\n');
        }
        ChatResult result = llm.chat(ChatRequest.builder()
                .messages(List.of(
                        LlmMessage.system(system.toString()),
                        LlmMessage.user(request.userPrompt())))
                .temperature(request.temperature())
                .maxTokens(Math.max(64, request.maxLength() * 2))
                .metadata(Map.of("companionName", request.companionName(),
                        "purpose", "conversation"))
                .build());
        return result.getContent() == null ? "" : result.getContent();
    }

    /** 输出契约解析: {"messages":[{"text":"..."}]}; 容错: 非 JSON 时整段视为一条消息 */
    private List<String> parseOutputContract(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        String trimmed = raw.trim();
        try {
            JsonNode node = mapper.readTree(trimmed);
            JsonNode messages = node.path("messages");
            if (messages.isArray() && !messages.isEmpty()) {
                for (JsonNode m : messages) {
                    String text = m.path("text").asText("").trim();
                    if (!text.isEmpty()) {
                        out.add(text);
                    }
                }
                return out;
            }
        } catch (Exception ignored) {
            // 非 JSON → 按纯文本处理
        }
        out.add(trimmed);
        return out;
    }

    private static List<ChatMessageDraft> indexDrafts(List<String> texts) {
        List<ChatMessageDraft> drafts = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            drafts.add(ChatMessageDraft.of(i, texts.get(i)));
        }
        return drafts;
    }

    private static String describeIssues(List<ConversationOutputValidator.Issue> issues) {
        StringBuilder sb = new StringBuilder();
        for (ConversationOutputValidator.Issue issue : issues) {
            sb.append("第").append(issue.draftIndex() + 1).append("条: ").append(issue.reason()).append("; ");
        }
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        return s == null ? "" : (s.length() > max ? s.substring(0, max) : s);
    }
}
