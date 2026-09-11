package com.luxera.companion.application.action;

import com.luxera.companion.contracts.application.ActionStatus;
import org.springframework.http.HttpStatus;

/**
 * LAP v1: {@link ActionStatus} ↔ HTTP 的<em>唯一</em>一份映射。
 *
 * <p>为什么必须只有一份: 同一个失败在 REST 上返回 409、在 MCP 里返回 200 加一个
 * {@code isError}, 在 {@code ApplicationRuntimePort} 上返回一个枚举 —— 三者对同一个语义给出
 * 不同说法时, 客户端就只能靠猜。映射集中在这里, REST 控制器、MCP 适配器、进程内端口全部调它,
 * 谁都不许自己 switch。
 *
 * <p>注意 {@link ActionStatus#DENIED} → <b>403</b> 而不是 401: 调用方身份是有效的, 只是不被
 * 允许做这件事。401 会让客户端去重新登录 —— 一个正好错误的动作。
 */
public final class ActionStatusMapper {

    private ActionStatusMapper() {
    }

    public static HttpStatus httpStatus(ActionStatus status) {
        if (status == null) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return switch (status) {
            case SUCCESS -> HttpStatus.OK;
            case INVALID_ARGUMENT -> HttpStatus.BAD_REQUEST;
            case IDEMPOTENCY_KEY_REQUIRED -> HttpStatus.BAD_REQUEST;
            case DENIED -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case STATE_CONFLICT -> HttpStatus.CONFLICT;
            case IDEMPOTENCY_IN_PROGRESS -> HttpStatus.CONFLICT;
            case IDEMPOTENCY_KEY_REUSED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case EXPIRED -> HttpStatus.CONFLICT;
            case REQUIRE_CONFIRMATION -> HttpStatus.PRECONDITION_REQUIRED;
            case FAILED -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    /** MCP 的 {@code tools/call} 把失败装在 {@code isError} 里, 但语义仍由这张表决定。 */
    public static boolean isError(ActionStatus status) {
        return status != ActionStatus.SUCCESS;
    }
}
