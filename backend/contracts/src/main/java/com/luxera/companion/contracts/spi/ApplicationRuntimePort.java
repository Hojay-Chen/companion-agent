package com.luxera.companion.contracts.spi;

import com.luxera.companion.contracts.application.ActionRequest;
import com.luxera.companion.contracts.application.ActionResponse;
import com.luxera.companion.contracts.application.ActionSpec;
import com.luxera.companion.contracts.application.ApplicationView;
import com.luxera.companion.contracts.application.CapabilityView;
import com.luxera.companion.contracts.application.InvocationContext;
import com.luxera.companion.contracts.application.ResourceView;

import java.util.List;
import java.util.Optional;

/**
 * LAP v1 — the digital human's <em>only</em> way to touch an application.
 *
 * <p>Implemented by {@code application-platform}. A human client, an Agent and an external MCP
 * client all end up in the same {@code ApplicationGateway}; this port is simply the in-process
 * face of it. There is no agent-specific entry point anywhere.
 *
 * <p>The four methods answer the four questions an autonomous actor asks, in order:
 * <ol>
 *   <li>{@link #capabilities()} — what kinds of things can be done in this world?</li>
 *   <li>{@link #applicationsFor(String)} — which applications offer that capability?</li>
 *   <li>{@link #read(String)} and {@link #pendingActions(String, InvocationContext)} — what is
 *       true right now, and what may I do about it?</li>
 *   <li>{@link #execute(ActionRequest, InvocationContext)} — do it.</li>
 * </ol>
 *
 * <p><b>{@link #pendingActions} is what keeps application rules out of the digital human.</b> The
 * pre-LAP runtime asked {@code if (turn == "O")} inside {@code AgentRuntime}; now the application
 * answers "here is what you may do" and an empty list means "nothing". Whose turn it is, whether
 * the game is over, whether a reminder is already acknowledged — all of it stays in the
 * application, where it belongs.
 */
public interface ApplicationRuntimePort {

    /** The platform's capability catalogue. */
    List<CapabilityView> capabilities();

    /** Published applications declaring {@code capabilityId}; empty if none. */
    List<ApplicationView> applicationsFor(String capabilityId);

    /** Every action a published application exposes. */
    List<ActionSpec> actionsOf(String applicationId);

    /** The resource as it currently stands, or empty if it does not exist. */
    Optional<ResourceView> read(String resourceUri);

    /**
     * Actions the given principal may perform against this resource right now, annotated with the
     * application author's {@code agentHint}. Empty means "do nothing" — not an error.
     */
    List<ActionSpec> pendingActions(String resourceUri, InvocationContext ctx);

    /**
     * Perform one action. Implementations must enforce the permission model, idempotency and
     * optimistic concurrency; callers may treat a non-{@code SUCCESS} status as final.
     */
    ActionResponse execute(ActionRequest request, InvocationContext ctx);

    /**
     * 保证这个 principal 在某个应用里<b>有一个正在进行的会话</b> —— <b>幂等</b>, 返回它的 id。
     *
     * <p>LAP v2 之前这里叫 {@code ensureInstalled}: 那时的模型是"先安装, 才有会话"。v2 把安装
     * 整个拿掉了 —— 用户无需安装任何东西, 剩下的是"<em>我在这个应用里有一个实例</em>", 那正是
     * Session。于是同一个位置上的问题从"我允许这个应用为我做事吗"变成了"我在哪里做这件事",
     * 答案从一个布尔值变成了一个 id。
     *
     * <p>为什么数字人需要它: 数字人有些功能天然要落到某个应用上(提醒就是第一个), 而"用户开了
     * 什么会话"是用户的选择, 不该由数字人在启动时替所有人批量创建, 也不该由部署脚本往数据库里
     * 塞行。于是改成"用的时候保证一下": 第一次用到时建一个并把调用方记为 OWNER, 之后每次都是
     * 无操作。
     *
     * <p>它<em>只</em>保证会话存在, 不保证会话里发生了什么 —— 动作仍然要另外走
     * {@link #execute}。
     *
     * @return 该 principal 在这个应用里可用的会话 id; 永不返回 null
     * @throws RuntimeException 应用不存在、没有已发布版本、或调用方身份不合法时由实现抛出
     */
    String ensureSession(String applicationId, InvocationContext ctx);
}
