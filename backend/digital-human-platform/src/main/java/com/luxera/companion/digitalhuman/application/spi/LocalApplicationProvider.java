package com.luxera.companion.digitalhuman.application.spi;

import com.luxera.companion.contracts.application.ActionSpec;
import com.luxera.companion.contracts.application.ApplicationView;
import com.luxera.companion.contracts.application.CapabilityView;
import com.luxera.companion.contracts.application.InvocationContext;
import com.luxera.companion.contracts.application.ResourceView;

import java.util.List;
import java.util.Optional;

/**
 * 一个"住在进程内"的 LAP 应用要回答的问题。
 *
 * <p>这是 R2 的过渡件: 应用平台还没有独立成模块, 应用仍与数字人同进程。
 * {@link com.luxera.companion.digitalhuman.application.runtime.LocalApplicationRuntimeAdapter}
 * 把若干个本接口的实现聚合成一个
 * {@link com.luxera.companion.contracts.spi.ApplicationRuntimePort},
 * 于是 {@code AgentApplicationFlow} 只看见契约, 看不见任何具体应用。
 *
 * <p><b>本接口在 R3 随应用平台一起搬走</b> —— 它的每一项都对应 application-platform 里
 * {@code ApplicationRegistry} / {@code ResourceStore} / {@code CapabilityResolver} 的职责。
 * 现在存在的唯一理由是: 先让数字人侧的解耦成立, 再动物理搬迁。
 *
 * <p>注意 {@link #pendingActions}: 它取代的是原先写在 {@code AgentRuntime} 里的
 * {@code if (!"O".equalsIgnoreCase(turn)) return;} —— 轮到谁、局是否终了, 由应用回答,
 * 数字人不再自己判断。
 */
public interface LocalApplicationProvider {

    /** 稳定的应用 id, 如 {@code tictactoe}(对应 LAP application.id)。 */
    String applicationId();

    /** 当前版本号; R4 起由 ApplicationVersion 承载。 */
    String version();

    /** 本应用提供的粗粒度能力, 如 {@code game.play}。 */
    CapabilityView capability();

    /** 本应用对自己在能力目录中的呈现。 */
    ApplicationView application();

    /** 本应用暴露的全部动作(含 inputSchema 与 agentHint)。 */
    List<ActionSpec> actions();

    /** 该 actionId 是否属于本应用。 */
    default boolean owns(String actionId) {
        return actionId != null && actions().stream().anyMatch(a -> actionId.equals(a.actionId()));
    }

    /** 按资源 URI 读状态; 不存在返回 empty。 */
    Optional<ResourceView> read(String resourceUri);

    /** 此刻该资源上"能做什么"; 空列表表示什么都不该做(不是错误)。 */
    List<ActionSpec> pendingActions(ResourceView resource, InvocationContext ctx);
}
