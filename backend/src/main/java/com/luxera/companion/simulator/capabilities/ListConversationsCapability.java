package com.luxera.companion.simulator.capabilities;

import com.luxera.companion.conversation.Conversation;
import com.luxera.companion.conversation.ConversationService;
import com.luxera.companion.simulator.CapabilityCommand;
import com.luxera.companion.simulator.CapabilityResult;
import com.luxera.companion.simulator.CapabilityType;
import com.luxera.companion.simulator.ListConversationsCommand;
import com.luxera.companion.simulator.SimulatorCapability;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ListConversationsCapability: 列出账号下的会话列表(客户端会话视图)。
 */
@Component
public class ListConversationsCapability implements SimulatorCapability {

    private final ConversationService conversationService;

    public ListConversationsCapability(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @Override
    public CapabilityType type() {
        return CapabilityType.LIST_CONVERSATIONS;
    }

    @Override
    public CapabilityResult execute(CapabilityCommand command) {
        ListConversationsCommand cmd = (ListConversationsCommand) command;
        try {
            List<Conversation> conversations = conversationService.list(cmd.userId(), cmd.companionId());
            List<Map<String, Object>> views = new ArrayList<>();
            for (Conversation c : conversations) {
                Map<String, Object> view = new LinkedHashMap<>();
                view.put("id", c.getId());
                view.put("title", c.getTitle());
                view.put("companionId", c.getCompanionId());
                view.put("messageCount", c.getMessageCount());
                view.put("lastMessageAt", c.getLastMessageAt() == null ? null : c.getLastMessageAt().toString());
                views.add(view);
            }
            return CapabilityResult.ok("列出 " + views.size() + " 个会话",
                    Map.of("conversations", views, "count", views.size()));
        } catch (Exception e) {
            return CapabilityResult.fail("列会话失败: " + e.getMessage());
        }
    }
}
