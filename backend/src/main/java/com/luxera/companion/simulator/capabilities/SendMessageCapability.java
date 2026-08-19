package com.luxera.companion.simulator.capabilities;

import com.luxera.companion.conversation.ConversationService;
import com.luxera.companion.conversation.Message;
import com.luxera.companion.simulator.CapabilityCommand;
import com.luxera.companion.simulator.CapabilityResult;
import com.luxera.companion.simulator.CapabilityType;
import com.luxera.companion.simulator.SendMessageCommand;
import com.luxera.companion.simulator.SimulatorCapability;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SendMessageCapability: 通过 Chat Platform 发送一条消息。
 * Chat Platform 侧利用 clientMessageId 幂等(同会话内唯一), 重试不重复发送。
 */
@Component
public class SendMessageCapability implements SimulatorCapability {

    private final ConversationService conversationService;

    public SendMessageCapability(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @Override
    public CapabilityType type() {
        return CapabilityType.SEND_MESSAGE;
    }

    @Override
    public CapabilityResult execute(CapabilityCommand command) {
        SendMessageCommand cmd = (SendMessageCommand) command;
        try {
            Message m = conversationService.addMessage(
                    cmd.conversationId(), cmd.senderType(), cmd.content(), null,
                    false, cmd.messageKind(), null, null, cmd.clientMessageId());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("messageId", m.getId());
            data.put("conversationId", m.getConversationId());
            data.put("senderType", m.getSenderType());
            data.put("createdAt", m.getCreatedAt() == null ? null : m.getCreatedAt().toString());
            return CapabilityResult.ok("消息已发送", data);
        } catch (Exception e) {
            return CapabilityResult.fail("发送失败: " + e.getMessage());
        }
    }
}
