"""LapServer —— 一个能被 LAP 平台调用的远端应用的 HTTP 骨架。

设计上它像一个极小的路由器, 而不是一个框架: 平台对远端的全部要求是
"在 ``runtime.remote.baseUrl`` 上收 POST, 回 LAP 形状的 JSON", 任何框架
(Flask/FastAPI/甚至另一个 Java 服务)都能做到。这个类的价值在于把
"哪些字段必须验、哪些字段随便用、错误该怎么回"这几个协议判断写对一次。

与框架的取舍: 用标准库 ``http.server`` 而不是 Flask —— SDK 不给远端应用
引入任何第三方依赖, "协议优先、SDK 非必须"这句话才立得住(方案 §75/§104:
连不用本 SDK 的实现者也能照着协议文档对接)。
"""

import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Callable

from .signature import (
    HEADER_SIGNATURE,
    HEADER_TIMESTAMP,
    secret_from_env,
    verify_request,
)


class LapError(Exception):
    """应用侧主动说的"不" —— 回给平台的形状与 LAP 的 ActionStatus 对齐。

    ``status`` 用平台认识的词(见 ``STATUS_BY_CODE`` 的键集), 回 4xx/5xx 之前
    先想想这里是不是更合适: 平台把 4xx 映射成 REMOTE_* 错误码, 而这里的
    code/message 会原样出现在 ActionResponse.error 里, 用户看到的是后者。
    """

    def __init__(self, code: str, message: str, status: str = "FAILED"):
        super().__init__(message)
        self.code = code
        self.message = message
        self.status = status


# SDK 认识的状态词 —— 与 contracts 的 ActionStatus 同一张表。不认识的
# 一律按 FAILED 回, 而不是让一个拼错的词静默变成"成功"。
STATUS_BY_CODE = {
    "DENIED": "DENIED",
    "NOT_FOUND": "NOT_FOUND",
    "STATE_CONFLICT": "STATE_CONFLICT",
    "INVALID_ARGUMENT": "INVALID_ARGUMENT",
    "FAILED": "FAILED",
}


class LapRequest:
    """平台一次转发过来的全部材料。

    ``action`` / ``input`` / ``target`` / ``principal`` / ``idempotency_key``
    —— 这五个就是协议的全部。``raw`` 留给应用自己做协议之外的扩展
    (metadata、自定制的 trace 头之类), SDK 不解释它。
    """

    def __init__(self, payload: dict, idempotency_key: str | None, raw_body: str):
        self.action = payload.get("action", "")
        self.input = payload.get("input") or {}
        self.target = payload.get("target")
        self.principal = payload.get("principal") or {}
        self.correlation_id = payload.get("correlationId")
        self.expected_version = payload.get("expectedResourceVersion")
        self.idempotency_key = idempotency_key
        self.raw = payload
        self.raw_body = raw_body


class IdempotencyStore:
    """已答问题的记忆 —— 幂等三行代码, 但写错的后果是重复落子。

    平台转发的键是"这一次逻辑调用"的确定函数, 重试(网络超时后重发、平台
    抢占重放)会得到同一个键。远端要做的只是: 第一次记住结果, 之后同键直接回。
    ``None`` 表示"没答过" —— 所以存结果时要连"结果是失败"也一起存,
    否则失败的那次会被重试成第二次执行。
    """

    def __init__(self, capacity: int = 1024):
        self._entries: dict[str, dict] = {}
        self._order: list[str] = []
        self._capacity = capacity
        self._lock = threading.Lock()

    def get(self, key: str | None) -> dict | None:
        if not key:
            return None
        with self._lock:
            return self._entries.get(key)

    def put(self, key: str | None, response: dict):
        if not key:
            return
        with self._lock:
            if key not in self._entries:
                self._order.append(key)
            self._entries[key] = response
            while len(self._order) > self._capacity:
                oldest = self._order.pop(0)
                self._entries.pop(oldest, None)


class LapServer:
    """按 action id 分发的极小路由器 + 验签 + 幂等, 见模块注释。"""

    def __init__(self, secret: str | None = None, *, secret_env: str = "LAP_SERVICE_SECRET",
                 replay_window_seconds: int | None = None):
        self.secret = secret if secret is not None else secret_from_env(secret_env)
        self.replay_window_seconds = replay_window_seconds
        self._handlers: dict[str, Callable[[LapRequest], dict]] = {}
        self._idempotency = IdempotencyStore()
        self._seen_nonces: dict[str, float] = {}

    # ─────────────────────────── 注册 ───────────────────────────

    def action(self, action_id: str):
        """装饰器: ``@server.action("game.make_move")`` —— id 与 manifest 里的一致。"""
        def register(func: Callable[[LapRequest], dict]):
            self._handlers[action_id] = func
            return func
        return register

    # ─────────────────────────── 执行 ───────────────────────────

    def dispatch(self, request: LapRequest) -> tuple[int, dict]:
        """跑一次动作, 返回 (http_status, body)。测试直接调它, 不必起端口。"""
        if self.secret is None:
            # 没配密钥 = 这个远端谁都能冒充平台调它。宁可拒绝一切, 不裸奔。
            return 503, self._error("REMOTE_NOT_CONFIGURED", "服务端未配置 LAP_SERVICE_SECRET")
        if request.action not in self._handlers:
            return 404, self._error("UNKNOWN_ACTION",
                                    f"应用不认识动作 {request.action!r} —— manifest 与实现哪个改了?")
        cached = self._idempotency.get(request.idempotency_key)
        if cached is not None:
            # 幂等回放: 同键同答, 连失败也重放(见 IdempotencyStore 的类注释)。
            return 200, cached
        try:
            result = self._handlers[request.action](request)
            body = {"result": result} if result is not None else {"result": {}}
        except LapError as e:
            status = STATUS_BY_CODE.get(e.status, "FAILED")
            return self._status_for(status), self._error(e.code, e.message, status)
        self._idempotency.put(request.idempotency_key, body)
        return 200, body

    def verify(self, timestamp: str | None, body: str, signature: str | None) -> bool:
        if self.replay_window_seconds is not None:
            return verify_request(self.secret, timestamp, body, signature,
                                  replay_window_seconds=self.replay_window_seconds)
        return verify_request(self.secret, timestamp, body, signature)

    # ─────────────────────────── HTTP ───────────────────────────

    def handler(self) -> type:
        server = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):  # noqa: N802 — BaseHTTPRequestHandler 的命名
                length = int(self.headers.get("Content-Length", "0"))
                raw = self.rfile.read(length).decode("utf-8") if length else ""
                if not server.verify(self.headers.get(HEADER_TIMESTAMP), raw,
                                     self.headers.get(HEADER_SIGNATURE)):
                    self._reply(401, {"error": {"code": "BAD_SIGNATURE",
                                                "message": "签名校验失败(伪造/重放/时钟错位)"}})
                    return
                try:
                    payload = json.loads(raw) if raw else {}
                except json.JSONDecodeError:
                    self._reply(400, {"error": {"code": "BAD_REQUEST", "message": "body 不是 JSON"}})
                    return
                request = LapRequest(payload, self.headers.get("Idempotency-Key"), raw)
                status, body = server.dispatch(request)
                self._reply(status, body)

            def _reply(self, status: int, body: dict):
                data = json.dumps(body, ensure_ascii=False).encode("utf-8")
                self.send_response(status)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def log_message(self, fmt, *args):  # 静音: 平台 3 秒超时, 日志不该比业务还吵
                pass

        return Handler

    def serve(self, host: str = "127.0.0.1", port: int = 8095):
        """阻塞式起服务 —— CLI/验收脚本用; 嵌进别的框架时用 :meth:`dispatch`。"""
        http = ThreadingHTTPServer((host, port), self.handler())
        print(f"[LapServer] listening on {host}:{port}", flush=True)
        try:
            http.serve_forever()
        except KeyboardInterrupt:
            pass
        finally:
            http.server_close()

    # ─────────────────────────── 内部 ───────────────────────────

    @staticmethod
    def _status_for(status: str) -> int:
        return {
            "DENIED": 403,
            "NOT_FOUND": 404,
            "STATE_CONFLICT": 409,
            "INVALID_ARGUMENT": 400,
        }.get(status, 500)

    @staticmethod
    def _error(code: str, message: str, status: str = "FAILED") -> dict:
        return {"error": {"code": code, "message": message, "status": status}}
