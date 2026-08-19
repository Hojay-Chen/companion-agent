package com.luxera.companion.simulator.capabilities;

import com.luxera.companion.conversation.ConversationService;
import com.luxera.companion.conversation.Message;
import com.luxera.companion.simulator.CapabilityCommand;
import com.luxera.companion.simulator.CapabilityResult;
import com.luxera.companion.simulator.CapabilityType;
import com.luxera.companion.simulator.ReadMessagesCommand;
import com.luxera.companion.simulator.SimulatorCapability;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ReadMessagesCapability: 模拟客户端读取会话消息。
 * 数字人只能通过它"看到"消息内容 —— 消息内容永远不会直接注入 Agent 逻辑。
 */
@Component
public class ReadMessagesCapability implements SimulatorCapability {

    private final ConversationService conversationService;

    public ReadMessagesCapability(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @Override
    public CapabilityType type() {
        return CapabilityType.READ_MESSAGES;
    }

    @Override
    public CapabilityResult execute(CapabilityCommand command) {
        ReadMessagesCommand cmd = (ReadMessagesCommand) command;
        try {
            List<Message> messages = cmd.limit() > 0
                    ? conversationService.recentMessages(cmd.conversationId(), cmd.limit())
                    : conversationService.messages(cmd.conversationId());
            List<Map<String, Object>> views = new ArrayList<>();
            for (Message m : messages) {
                Map<String, Object> view = new LinkedHashMap<>();
                view.put("id", m.getId());
                view.put("conversationId", m.getConversationId());
                view.put("senderType", m.getSenderType());
                view.put("content", m.getContent());
                view.put("deliveryStatus", m.getDeliveryStatus());
                view.put("createdAt", m.getCreatedAt() == null ? null : m.getCreatedAt().toString());
                view.put("messageKind", m.getMessageKind());
                view.put("sessionId", m.getSessionId());
                view.put("exchangeId", m.getExchangeId());
                views.add(view);
            }
            return CapabilityResult.ok("读取 " + views.size() + " 条消息",
                    Map.of("messages", views, "count", views.size()));
        } catch (Exception e) {
            return CapabilityResult.fail("读取失败: " + e.getMessage());
        }
    }
}
