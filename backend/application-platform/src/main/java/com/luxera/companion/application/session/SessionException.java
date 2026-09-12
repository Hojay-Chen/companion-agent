package com.luxera.companion.application.session;

import com.luxera.companion.contracts.application.ActionStatus;

/**
 * 归属链上的拒绝。
 *
 * <p>带 {@link ActionStatus} 而不是只有一个字符串: "不在场"和"参数不合法"对调用方是两件事,
 * 前者该去找人邀请自己, 后者该改请求。把它们都变成 400 会让客户端只能靠猜。
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

    /** 归属链上的失败大多是"请求本身有问题"; 不在场/没资格是明确的拒绝。 */
    private static final class Statuses {
        private Statuses() {
        }

        static ActionStatus forCode(String code) {
            if (code == null) {
                return ActionStatus.INVALID_ARGUMENT;
            }
            return switch (code) {
                // 拒绝: 调用方是"谁"就不对, 改请求没用, 得先改变自己在会话里的位置。
                case "NOT_A_PARTICIPANT", "PARTICIPANT_INACTIVE", "NOT_SESSION_OWNER",
                     "SESSION_INVITE_ONLY", "SESSION_JOIN_CLOSED", "SESSION_FULL" ->
                        ActionStatus.DENIED;
                case "UNKNOWN_APPLICATION", "VERSION_NOT_PUBLISHED", "UNKNOWN_SESSION" ->
                        ActionStatus.NOT_FOUND;
                // 不是"你的请求写错了"(400 会让客户端去改载荷), 而是"目标的状态不允许这件事了" ——
                // 会话结束该另开一个, 会话没进入 ACTIVE 该等一会儿。两者都是换时机, 不是换载荷。
                case "SESSION_ENDED", "SESSION_NOT_ACTIVE" -> ActionStatus.STATE_CONFLICT;
                // 三条归属不变量破了。这不该发生; 真发生了说明库里的数据自相矛盾, 调用方无论
                // 改什么载荷都过不去 —— 409 让它别重试, 500 会让它以为是服务挂了。
                case "SESSION_VERSION_MISMATCH", "SESSION_OWNER_MISMATCH",
                     "SESSION_CAPACITY_EXCEEDED" -> ActionStatus.STATE_CONFLICT;
                default -> ActionStatus.INVALID_ARGUMENT;
            };
        }
    }
}
