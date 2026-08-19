package com.luxera.companion.simulator;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * V10 §4 Simulator Client(Adapter/Facade):
 * 数字人访问外部聊天世界的唯一入口。
 *
 * 职责边界(V10 §2.1):
 * - Chat Platform 不知道 Agent 的存在;
 * - Digital Human Platform 不直接操作 Chat 数据库 —— 一切读写必须通过本客户端;
 * - 未来替换聊天平台时, 只需替换本 Facade 内部的 Capability 实现(新 Connector)。
 *
 * 执行路径: session 校验 → scope 校验 → Command Pattern 分发 → Capability 执行。
 */
@Slf4j
@Component
public class SimulatorClient {

    private final Map<CapabilityType, SimulatorCapability> capabilities = new ConcurrentHashMap<>();
    private final SimulatorSessionRegistry sessionRegistry;

    public SimulatorClient(List<SimulatorCapability> capabilityList, SimulatorSessionRegistry sessionRegistry) {
        this.sessionRegistry = sessionRegistry;
        for (SimulatorCapability c : capabilityList) {
            capabilities.put(c.type(), c);
        }
    }

    // ── 会话管理 ─────────────────────────────

    /** 打开绑定账号的模拟客户端会话(建立连接 + 授予 scopes) */
    public SimulatorSession openSession(String accountId, Set<CapabilityType> scopes) {
        return sessionRegistry.open(accountId, scopes);
    }

    public SimulatorSession openSession(String accountId) {
        return sessionRegistry.open(accountId, EnumSet.allOf(CapabilityType.class));
    }

    public void disconnect(String accountId) {
        sessionRegistry.disconnect(accountId);
    }

    // ── 命令执行(Command Pattern 入口) ────────

    /**
     * 执行一条 Capability 命令。
     * 校验: 会话存在 → 连接已建立 → scope 已授予 → 命令类型匹配。
     */
    public CapabilityResult execute(CapabilityCommand command) {
        if (command == null) {
            return CapabilityResult.fail("命令不能为空");
        }
        SimulatorSession session = sessionRegistry.require(command.sessionId());
        if (session == null) {
            return CapabilityResult.fail("会话不存在: " + command.sessionId());
        }
        if (session.connectionState() != SimulatorSession.ConnectionState.CONNECTED) {
            return CapabilityResult.fail("会话未连接: " + session.connectionState());
        }
        if (!session.can(command.type())) {
            return CapabilityResult.fail("会话未授予 scope: " + command.type());
        }
        SimulatorCapability capability = capabilities.get(command.type());
        if (capability == null) {
            return CapabilityResult.fail("无此能力: " + command.type());
        }
        return capability.execute(command);
    }

    // ── 便捷门面(常用操作组合, 内部仍走命令路径) ──

    /** 发送一条消息(幂等: 同 idempotencyKey 不重复发送) */
    public CapabilityResult sendMessage(String sessionId, String conversationId, String senderType,
                                        String content, String messageKind, String idempotencyKey) {
        return execute(SendMessageCommand.of(idempotencyKey, sessionId, conversationId,
                senderType, content, messageKind));
    }

    /** 读取最近 N 条消息 */
    public CapabilityResult readMessages(String sessionId, String conversationId, int limit) {
        return execute(ReadMessagesCommand.recent(
                "read-" + conversationId + "-" + System.nanoTime(), sessionId, conversationId, limit));
    }

    /** 批量更新投递状态(已读等) */
    public CapabilityResult updateDeliveryStatus(String sessionId, Set<String> messageIds, String status) {
        return execute(UpdateDeliveryStatusCommand.of(
                "status-" + status + "-" + System.nanoTime(), sessionId, messageIds, status));
    }

    /** 列出会话 */
    public CapabilityResult listConversations(String sessionId, String userId) {
        return execute(ListConversationsCommand.of(
                "list-" + System.nanoTime(), sessionId, userId, null));
    }

    /** 列出指定账号+伴侣的会话 */
    public CapabilityResult listConversations(String sessionId, String userId, String companionId) {
        return execute(ListConversationsCommand.of(
                "list-" + System.nanoTime(), sessionId, userId, companionId));
    }

    /** 便捷: 从结果中取 messageId */
    public String messageIdOf(CapabilityResult result) {
        return result == null || !result.success() ? null : result.str("messageId");
    }

    /** 便捷: 结果是否成功 */
    public boolean ok(CapabilityResult result) {
        return result != null && result.success();
    }
}
