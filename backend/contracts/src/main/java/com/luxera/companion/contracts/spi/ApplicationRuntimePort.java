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
}
