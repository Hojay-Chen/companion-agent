package com.luxera.companion.contracts.application;

/**
 * LAP v1 — who is acting.
 *
 * <p>A human client, an Agent, the platform itself and an Application all reach the same
 * {@code ApplicationGateway}; the only thing that differs is the principal. This is why there is
 * no "Agent API" anywhere in the platform: the agent is just another principal.
 *
 * <p>{@link #HUMAN} is the fallback for legacy JWTs that carry no {@code ptype} claim
 * (see {@code PrincipalResolver}). That default exists for backward compatibility only —
 * an in-process digital-human invocation must state {@link #AGENT} explicitly rather than
 * rely on it.
 */
public enum PrincipalType {
    /** A person using a client. */
    HUMAN,
    /** A digital human / autonomous agent. */
    AGENT,
    /** The platform itself (schedulers, maintenance jobs). */
    SYSTEM,
    /** Another application acting on its own behalf. */
    APPLICATION
}
