package com.luxera.companion.simulator;

import com.luxera.companion.agent.PerceptionEngine;
import com.luxera.companion.conversation.Conversation;
import com.luxera.companion.conversation.ConversationService;
import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V10 §4 Simulator Platform 测试:
 * - Session 生命周期(DISCONNECTED → CONNECTED)与 scope 授权;
 * - Capability Command Pattern 执行(发送/读取/状态更新/列会话);
 * - 数字人侧的一切 Chat 写操作必须经过 SimulatorClient(边界验证)。
 */
@ActiveProfiles("test")
@SpringBootTest
class SimulatorClientTest {

    @Autowired
    SimulatorClient simulatorClient;
    @Autowired
    SimulatorSessionRegistry sessionRegistry;
    @Autowired
    ConversationService conversationService;
    @Autowired
    CompanionRepository companionRepository;
    @Autowired
    PerceptionEngine perceptionEngine;

    private String userId;
    private String companionId;
    private String conversationId;

    @BeforeEach
    void setUp() {
        userId = "sim-test-user-" + UUID.randomUUID().toString().substring(0, 8);
        companionId = UUID.randomUUID().toString();
        Companion c = new Companion();
        c.setId(companionId);
        c.setUserId(userId);
        c.setName("小满");
        c.setGender("female");
        companionRepository.save(c);
        Conversation conv = conversationService.create(userId, companionId, "Simulator 测试");
        conversationId = conv.getId();
    }

    @AfterEach
    void tearDown() {
        companionRepository.deleteById(companionId);
        sessionRegistry.disconnect(companionId);
    }

    @Test
    void openSessionConnectsWithScopes() {
        SimulatorSession session = simulatorClient.openSession(companionId,
                EnumSet.of(CapabilityType.READ_MESSAGES, CapabilityType.SEND_MESSAGE));
        assertEquals(SimulatorSession.ConnectionState.CONNECTED, session.connectionState());
        assertTrue(session.can(CapabilityType.READ_MESSAGES));
        assertTrue(session.can(CapabilityType.SEND_MESSAGE));
        assertFalse(session.can(CapabilityType.UPDATE_DELIVERY_STATUS), "未授予的 scope 必须拒绝");
        assertNotNull(sessionRegistry.find(companionId));
    }

    @Test
    void executeRejectsUnscopedCommand() {
        SimulatorSession session = simulatorClient.openSession(companionId,
                EnumSet.of(CapabilityType.READ_MESSAGES));
        // 未授予 SEND_MESSAGE scope → 拒绝
        CapabilityResult result = simulatorClient.sendMessage(
                session.sessionId(), conversationId, "companion", "你好", "NORMAL", "cmd-1");
        assertFalse(result.success(), "未授权 scope 的命令必须被拒绝");
        assertTrue(result.message().contains("scope"));
    }

    @Test
    void executeRejectsUnknownSession() {
        CapabilityResult result = simulatorClient.sendMessage(
                "no-such-session", conversationId, "companion", "你好", "NORMAL", "cmd-1");
        assertFalse(result.success());
        assertTrue(result.message().contains("会话不存在"));
    }

    @Test
    void sendAndReadMessageRoundTrip() {
        simulatorClient.openSession(companionId);   // 默认全部 scope
        CapabilityResult send = simulatorClient.sendMessage(
                companionId, conversationId, "companion", "这是一条通过 Simulator 发送的消息", "NORMAL",
                "sim-msg-" + System.nanoTime());
        assertTrue(send.success(), send.message());
        String messageId = simulatorClient.messageIdOf(send);
        assertNotNull(messageId);

        // 通过 capability 读回, 验证内容一致
        CapabilityResult read = simulatorClient.readMessages(companionId, conversationId, 10);
        assertTrue(read.success(), read.message());
        assertTrue(read.str("count") != null && Integer.parseInt(read.str("count")) >= 1);
        String all = read.get("messages").toString();
        assertTrue(all.contains("这是一条通过 Simulator 发送的消息"), "读回的消息应包含发送内容");
    }

    @Test
    void updateDeliveryStatusViaCapability() {
        simulatorClient.openSession(companionId);
        // 先由用户发一条消息
        var userMsg = conversationService.addMessage(conversationId, "user", "在吗",
                perceptionEngine.perceive("在吗"), false);
        assertEquals("DELIVERED", userMsg.getDeliveryStatus());

        // 数字人"看到" → 通过 Capability 标记已读
        CapabilityResult result = simulatorClient.updateDeliveryStatus(
                companionId, java.util.Set.of(userMsg.getId()), "READ");
        assertTrue(result.success(), result.message());

        var after = conversationService.messages(conversationId).stream()
                .filter(m -> m.getId().equals(userMsg.getId())).findFirst().orElseThrow();
        assertEquals("READ", after.getDeliveryStatus());
    }

    @Test
    void listConversationsViaCapability() {
        SimulatorSession session = simulatorClient.openSession(companionId);
        CapabilityResult result = simulatorClient.listConversations(session.sessionId(), userId, companionId);
        assertTrue(result.success(), result.message());
        assertTrue(result.get("conversations").toString().contains(conversationId));
    }

    @Test
    void sessionRegistryReusesSessionOnReconnect() {
        SimulatorSession first = simulatorClient.openSession(companionId,
                EnumSet.of(CapabilityType.READ_MESSAGES));
        simulatorClient.disconnect(companionId);
        SimulatorSession second = simulatorClient.openSession(companionId,
                EnumSet.of(CapabilityType.READ_MESSAGES));
        // 重连复用同一 session(断线恢复, V10 §26)
        assertEquals(first.sessionId(), second.sessionId());
        assertEquals(SimulatorSession.ConnectionState.CONNECTED, second.connectionState());
    }
}
