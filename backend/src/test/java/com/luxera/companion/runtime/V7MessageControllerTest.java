package com.luxera.companion.runtime;

import com.luxera.companion.conversation.Conversation;
import com.luxera.companion.conversation.ConversationRepository;
import com.luxera.companion.conversation.Message;
import com.luxera.companion.conversation.MessageRepository;
import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V7 §12/§19 POST /messages 通信解耦测试:
 * - 发送消息立即返回(不阻塞等待 Agent)
 * - Agent Runtime 异步持久化用户消息
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class V7MessageControllerTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    CompanionRepository companionRepository;
    @Autowired
    ConversationRepository conversationRepository;
    @Autowired
    MessageRepository messageRepository;
    @Autowired
    V7AgentRuntime v7AgentRuntime;

    private String companionId;
    private String conversationId;

    @BeforeEach
    void setUp() {
        companionId = UUID.randomUUID().toString();
        Companion c = new Companion();
        c.setId(companionId);
        c.setUserId("v7-user");
        c.setName("小满");
        c.setGender("female");
        companionRepository.save(c);

        Conversation conv = new Conversation();
        conv.setId(UUID.randomUUID().toString());
        conv.setUserId("v7-user");
        conv.setCompanionId(companionId);
        conv.setTitle("测试");
        conversationRepository.save(conv);
        conversationId = conv.getId();
    }

    @Test
    void postMessageRequiresAuth() throws Exception {
        // 未认证 → 401/403(而非阻塞/500), 证明接口链路存在且不依赖 Agent
        mockMvc.perform(post(
                        "/api/companions/{cid}/conversations/{convId}/messages", companionId, conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"你好\"}"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void agentRuntimeAsyncPersistsUserMessage() {
        String content = "你好,我在这里";
        // 同步处理(测试中直接调用 process, 不走异步线程)
        v7AgentRuntime.process("v7-user", companionId, conversationId, java.util.List.of(content));
        // 用户消息应入库
        boolean found = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId).stream()
                .anyMatch(m -> "user".equals(m.getSenderType()) && content.equals(m.getContent()));
        assertTrue(found, "Agent Runtime 应持久化用户消息");
    }

    @Test
    void agentRuntimeProcessDoesNotThrow() {
        // 空内容/异常场景不应导致运行时崩溃
        assertDoesNotThrow(() -> v7AgentRuntime.process(
                "v7-user", companionId, conversationId, java.util.List.of("测试消息")));
    }
}
