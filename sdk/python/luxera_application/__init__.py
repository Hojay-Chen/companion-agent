"""LAP Application SDK (Python) — 给第三方应用作者的参考实现。

这个包回答一个问题: <b>怎么写一个能被 LAP 平台调用的远端应用</b>。
它不回答"怎么写任意 HTTP 服务" —— 那是框架的事; 这里只封装 LAP 协议的三件事:

1. <b>验签</b> — 平台每次转发都带 HMAC-SHA256 签名(`X-Lap-Signature`, 签的是
   `timestamp + "." + body`)。SDK 提供 :func:`verify_request`, 常量时间比较。
   密钥由部署侧注入(环境变量 `LAP_SERVICE_SECRET`), 不进代码库。
2. <b>幂等</b> — 平台转发的 `Idempotency-Key` 是"这一次逻辑调用"的确定函数,
   重试会得到同一个键。SDK 提供 :class:`IdempotencyStore` 把"已答过的问题"
   存起来, 应用只要记得"第一次答了什么"。
3. <b>协议形状</b> — 请求体(`action`/`input`/`target`/`principal`)与响应体
   (`result`/`error`)的字段名, 一处定义、两侧共用。

典型用法(与 ``remote-apps/gomoku`` 同形)::

    from luxera_application import LapServer, verify_request

    server = LapServer(secret_env="LAP_SERVICE_SECRET")

    @server.action("demo.ping")
    def ping(request):
        return {"pong": True}

    server.serve(port=8095)

协议本身没有任何 Python 依赖 —— 标准库就够。这是刻意的: 一个远端应用
可能跑在任何地方, SDK 的安装成本越低, "协议优先"这句话越像真的。
"""

__version__ = "1.0.0"

from .server import LapServer, LapRequest, LapError
from .signature import verify_request, sign_payload

__all__ = [
    "LapServer",
    "LapRequest",
    "LapError",
    "verify_request",
    "sign_payload",
    "__version__",
]
