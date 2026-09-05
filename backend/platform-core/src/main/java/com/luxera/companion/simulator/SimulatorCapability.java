package com.luxera.companion.simulator;

/**
 * V10 §4.3 Simulator Capability(Command Pattern 执行器)。
 *
 * 每个能力实现只负责一件事:
 * - OpenConversationCapability / ReadMessageCapability / SendMessageCapability /
 *   UpdateDeliveryStatusCapability ...
 *
 * SimulatorClient(Facade)负责: 会话校验 → scope 校验 → 命令分发 → 结果返回。
 * Capability 本身不感知 Digital Human 的任何概念。
 */
public interface SimulatorCapability {

    /** 本能力对应的类型(与 scope 一一对应) */
    CapabilityType type();

    /** 执行命令; 命令类型必须与 type() 匹配(SimulatorClient 分发前校验) */
    CapabilityResult execute(CapabilityCommand command);
}
