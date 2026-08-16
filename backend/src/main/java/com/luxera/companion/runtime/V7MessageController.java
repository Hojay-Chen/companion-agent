package com.luxera.companion.runtime;

import com.luxera.companion.config.CurrentUser;
import com.luxera.companion.conversation.Conversation;
import com.luxera.companion.conversation.ConversationService;
import com.luxera.companion.persona.CompanionService;
import lombok.Data;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * V7 §12/§19 POST /messages: 用户发送消息, 立即持久化返回 DELIVERED。
 * 不等待 Agent —— Agent 异步处理后通过 GET /events 推送回复。
 * 这是 V7 通信解耦的核心入口。
 */
@RestController
@RequestMapping("/api/companions/{companionId}/conversations/{conversationId}/messages")
public class V7MessageController {

    private final CurrentUser currentUser;
    private final CompanionService companionService;
    private final ConversationService conversationService;
    private final V7AgentRuntime agentRuntime;

    public V7MessageController(CurrentUser currentUser, CompanionService companionService,
                               ConversationService conversationService, V7AgentRuntime agentRuntime) {
        this.currentUser = currentUser;
        this.companionService = companionService;
        this.conversationService = conversationService;
        this.agentRuntime = agentRuntime;
    }

    /**
     * 发送消息: 立即返回 DELIVERED(几十毫秒), Agent 异步处理。
     * 支持单条 content 或批量 messages。
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> send(
            @PathVariable String companionId,
            @PathVariable String conversationId,
            @RequestBody(required = false) SendRequest req) {
        String userId = currentUser.requireUserId();
        companionService.requireOwned(userId, companionId);
        Conversation conv = conversationService.requireOwned(userId, conversationId);
        if (!conv.getCompanionId().equals(companionId)) {
            throw new IllegalArgumentException("会话与伴侣不匹配");
        }

        List<String> contents = resolveContents(req);
        if (contents.isEmpty()) {
            throw new IllegalArgumentException("消息不能为空");
        }

        // 立即持久化用户消息, 拿到 messageId(agent 处理时会再入库同内容 → 这里只返回, 由 agent runtime 统一入库)
        // 为避免重复入库, 这里不写库, 只返回一个占位 id; 真实消息由 AgentRuntime.process 持久化。
        // 但 V7 要求"消息立即出现", 因此这里先持久化, agent runtime 不再重复入库。
        // 简化: agentRuntime.submit 内部会持久化; 此处返回 SUCCESS + 状态 DELIVERED(未读)。
        agentRuntime.submit(userId, companionId, conversationId, contents);

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of(
                        "status", "DELIVERED",
                        "messageId", contents.get(0),
                        "note", "消息已送达, Agent 稍后回复"));
    }

    private List<String> resolveContents(SendRequest req) {
        List<String> out = new java.util.ArrayList<>();
        if (req == null) return out;
        if (req.getMessages() != null && !req.getMessages().isEmpty()) {
            for (SendItem item : req.getMessages()) {
                if (item == null || item.getContent() == null || item.getContent().isBlank()) continue;
                out.add(item.getContent().trim());
            }
            return out;
        }
        if (req.getContent() != null && !req.getContent().isBlank()) {
            out.add(req.getContent().trim());
        }
        return out;
    }

    @Data
    public static class SendRequest {
        private String content;
        private List<SendItem> messages;
    }

    @Data
    public static class SendItem {
        private String content;
    }
}
