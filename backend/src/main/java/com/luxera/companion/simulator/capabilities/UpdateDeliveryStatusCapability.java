package com.luxera.companion.simulator.capabilities;

import com.luxera.companion.conversation.ConversationService;
import com.luxera.companion.simulator.CapabilityCommand;
import com.luxera.companion.simulator.CapabilityResult;
import com.luxera.companion.simulator.CapabilityType;
import com.luxera.companion.simulator.SimulatorCapability;
import com.luxera.companion.simulator.UpdateDeliveryStatusCommand;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * UpdateDeliveryStatusCapability: 客户端动作导致的投递状态推进。
 *
 * "她拿起手机看到了消息" 在 V10 因果链中是 Action(客户端动作) 的结果,
 * 因此通过本 Capability 落库(通知/注意到/已读/忽略), 而不是被 Agent 直接写库。
 */
@Component
public class UpdateDeliveryStatusCapability implements SimulatorCapability {

    private final ConversationService conversationService;

    public UpdateDeliveryStatusCapability(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @Override
    public CapabilityType type() {
        return CapabilityType.UPDATE_DELIVERY_STATUS;
    }

    @Override
    public CapabilityResult execute(CapabilityCommand command) {
        UpdateDeliveryStatusCommand cmd = (UpdateDeliveryStatusCommand) command;
        try {
            int updated = 0;
            // 逐条独立事务更新: 与 PendingMessageReevaluationJob 等并发更新方保持最小锁面,
            // 避免批量事务扩大行锁冲突范围(偶发死锁)。
            for (String messageId : cmd.messageIds()) {
                conversationService.updateDeliveryStatus(messageId, cmd.status());
                updated++;
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("updated", updated);
            data.put("status", cmd.status());
            return CapabilityResult.ok("已更新 " + updated + " 条消息状态为 " + cmd.status(), data);
        } catch (Exception e) {
            return CapabilityResult.fail("状态更新失败: " + e.getMessage());
        }
    }
}
