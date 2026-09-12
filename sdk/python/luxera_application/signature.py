"""验签 —— 平台对远端说"这条消息确实是我发的, 且没被改过"。

与 Java 侧 ``RemoteSignature`` 逐字对应: 同一个 HMAC-SHA256, 同一个
``timestamp + "." + body`` 拼接, 同一个 ``sha256=`` 前缀。两侧只要有一边
改了约定, 远端应用就会把平台的每一次调用都当成伪造 —— 所以这里的常量
与比较方式(常量时间)都不许各自发明。
"""

import hashlib
import hmac
import os

SCHEME = "sha256="
HEADER_SIGNATURE = "X-Lap-Signature"
HEADER_TIMESTAMP = "X-Lap-Timestamp"

# 重放窗口。平台只负责"让远端能挡重放"(把时间戳纳入签名), 窗口多宽由远端定 ——
# 这里的 5 分钟是一个远端应用的合理默认, 不是协议的一部分。
DEFAULT_REPLAY_WINDOW_SECONDS = 300


def sign_payload(secret: str, timestamp: str, body: str) -> str:
    """计算平台那一侧会算出的同一个签名。测试与联调用; 远端正常只需要 verify。"""
    digest = hmac.new(
        secret.encode("utf-8"),
        (timestamp + "." + body).encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()
    return SCHEME + digest


def verify_request(secret: str, timestamp: str | None, body: str,
                   signature: str | None,
                   *, replay_window_seconds: int = DEFAULT_REPLAY_WINDOW_SECONDS,
                   now: int | None = None) -> bool:
    """校验一次平台调用。

    三关, 顺序从最便宜的到最贵的:

    1. 头都在 —— 缺任何一个直接 False, 不进 HMAC;
    2. 时间戳在窗口内 —— 过期/超前都是重放或时钟错位, False;
    3. 签名对得上 —— ``hmac.compare_digest`` 常量时间比较, 不给
       "前几位对了"留泄漏的缝。

    任何一关失败都不抛异常: 调用方拿到 False 就该回 401, 两种原因
    (伪造/重放)对它来说处置一样 —— 拒绝 —— 所以不需要区分。
    """
    if not secret or timestamp is None or signature is None:
        return False
    try:
        ts = int(timestamp)
    except ValueError:
        return False
    import time
    current = now if now is not None else int(time.time())
    if abs(current - ts) > replay_window_seconds:
        return False
    expected = sign_payload(secret, timestamp, body)
    return hmac.compare_digest(expected.encode("utf-8"), signature.encode("utf-8"))


def secret_from_env(env_var: str = "LAP_SERVICE_SECRET") -> str | None:
    """部署侧注入密钥的标准入口。密钥不进代码库 —— 这里也只读环境变量。"""
    value = os.environ.get(env_var)
    return value if value else None
