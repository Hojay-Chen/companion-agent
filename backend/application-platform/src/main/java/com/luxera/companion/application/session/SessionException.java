package com.luxera.companion.application.session;

import com.luxera.companion.contracts.application.ActionStatus;

/**
 * LAP v1: 归属链上的拒绝。
 *
 * <p>带 {@link ActionStatus} 而不是只有一个字符串: "未安装"和"参数不合法"对调用方是两件事,
 * 前者该去装, 后者该改请求。把它们都变成 400 会让客户端只能靠猜。
 */
public class SessionException extends RuntimeException {

    private final String code;
    private final ActionStatus status;

    public SessionException(String code, String message) {
        this(code, message, Statuses.forCode(code));
    }

    public SessionException(String code, String message, ActionStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public ActionStatus status() {
        return status;
    }

    /** 归属链上的失败大多是"请求本身有问题"; 未安装/未授权是明确的拒绝。 */
    private static final class Statuses {
        private Statuses() {
        }

        static ActionStatus forCode(String code) {
            if (code == null) {
                return ActionStatus.INVALID_ARGUMENT;
            }
            return switch (code) {
                case "NOT_INSTALLED" -> ActionStatus.DENIED;
                case "UNKNOWN_APPLICATION", "VERSION_NOT_PUBLISHED", "UNKNOWN_SESSION" ->
                        ActionStatus.NOT_FOUND;
                // 会话结束不是"你的请求写错了"(400 会让客户端去改载荷), 而是"目标的状态不允许
                // 这件事了" —— 该做的是另开一个会话, 也就是换一个目标。409 正是这个意思。
                case "SESSION_ENDED" -> ActionStatus.STATE_CONFLICT;
                default -> ActionStatus.INVALID_ARGUMENT;
            };
        }
    }
}
