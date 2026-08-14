package com.luxera.companion.conversation;

import com.luxera.companion.agent.CompanionRuntime;
import com.luxera.companion.agent.PerceptionEngine;
import com.luxera.companion.config.CurrentUser;
import com.luxera.companion.persona.CompanionService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/companions/{companionId}/conversations")
public class ChatController {

    private final ConversationService conversationService;
    private final CompanionService companionService;
    private final CompanionRuntime runtime;
    private final PerceptionEngine perceptionEngine;
    private final CurrentUser currentUser;
    private final TaskExecutor taskExecutor;

    public ChatController(ConversationService conversationService, CompanionService companionService,
                          CompanionRuntime runtime, PerceptionEngine perceptionEngine,
                          CurrentUser currentUser, TaskExecutor taskExecutor) {
        this.conversationService = conversationService;
        this.companionService = companionService;
        this.runtime = runtime;
        this.perceptionEngine = perceptionEngine;
        this.currentUser = currentUser;
        this.taskExecutor = taskExecutor;
    }

    @GetMapping
    public List<Conversation> list(@PathVariable String companionId) {
        String userId = currentUser.requireUserId();
        companionService.requireOwned(userId, companionId);
        return conversationService.list(userId, companionId);
    }

    /** 获取会话列表(无会话时自动创建带问候语的初始会话) */
    @PostMapping("/first")
    public Conversation first(@PathVariable String companionId) {
        String userId = currentUser.requireUserId();
        var companion = companionService.requireOwned(userId, companionId);
        return conversationService.getOrCreateGreeting(userId, companionId, companion);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Conversation create(@PathVariable String companionId, @RequestBody(required = false) CreateRequest req) {
        String userId = currentUser.requireUserId();
        companionService.requireOwned(userId, companionId);
        return conversationService.create(userId, companionId, req != null ? req.getTitle() : null);
    }

    @GetMapping("/{conversationId}/messages")
    public List<Message> messages(@PathVariable String companionId, @PathVariable String conversationId) {
        String userId = currentUser.requireUserId();
        companionService.requireOwned(userId, companionId);
        conversationService.requireOwned(userId, conversationId);
        return conversationService.messages(conversationId);
    }

    /** 流式聊天(SSE): meta → token* → replace?(可选) → done */
    @PostMapping(value = "/{conversationId}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@PathVariable String companionId, @PathVariable String conversationId,
                           @RequestBody ChatRequest req) {
        String userId = currentUser.requireUserId();
        companionService.requireOwned(userId, companionId);
        var conv = conversationService.requireOwned(userId, conversationId);
        if (!conv.getCompanionId().equals(companionId)) {
            throw new IllegalArgumentException("会话与伴侣不匹配");
        }
        String content = req == null || req.getContent() == null || req.getContent().isBlank()
                ? "" : req.getContent().trim();
        if (content.isBlank()) {
            throw new IllegalArgumentException("消息不能为空");
        }

        SseEmitter emitter = new SseEmitter(300_000L);
        taskExecutor.execute(() -> streamChat(emitter, userId, companionId, conversationId, content));
        return emitter;
    }

    private void streamChat(SseEmitter emitter, String userId, String companionId,
                            String conversationId, String content) {
        try {
            PerceptionEngine.Perception perception = perceptionEngine.perceive(content);
            conversationService.addMessage(conversationId, "user", content, perception, false);
            List<Message> recent = conversationService.recentMessages(conversationId, 40);

            send(emitter, "meta", Map.of(
                    "intent", perception.intent(),
                    "emotion", perception.emotion(),
                    "topic", perception.topic()));

            CompanionRuntime.ChatOutcome outcome = runtime.generate(userId, companionId, conversationId,
                    content, recent, delta -> send(emitter, "token", Map.of("delta", delta)));

            String reply = outcome.reply();
            if (!reply.equals(outcome.rawReply().trim())) {
                // 自然度校验确实修正了文本(如去掉 AI 套话) → 通知前端整体替换
                send(emitter, "replace", Map.of("content", reply));
            }
            Message assistant = conversationService.addMessage(conversationId, "companion", reply, null, false);
            send(emitter, "done", Map.of("messageId", assistant.getId()));
            emitter.complete();
        } catch (Exception e) {
            log.error("聊天流式处理失败", e);
            try {
                emitter.send(SseEmitter.event().name("error").data(Map.of("message", "生成回复时出错,请重试")));
            } catch (IOException ignored) {
            }
            emitter.complete();
        }
    }

    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException e) {
            throw new RuntimeException("SSE 发送失败", e);
        }
    }

    @Data
    public static class ChatRequest {
        private String content;
    }

    @Data
    public static class CreateRequest {
        private String title;
    }
}
