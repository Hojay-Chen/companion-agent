/**
 * LAP Application SDK (TypeScript) — 给第三方应用作者(浏览器/Node 侧)的参考实现。
 *
 * 它与 `sdk/python` 那一份回答同一个问题——"怎么接 LAP 平台"——但面向的是
 * 另一类作者: 前端 Surface(host)、以及用 Node 写远端应用的人。三个成员:
 *
 * - `LapClient`: 调用平台的 REST 面(登录、发现、开 Session、执行 Action)。
 *   前端应用页用的就是它, 所以它只依赖 fetch —— 浏览器与 Node 18+ 都有。
 * - `verifySignature` / `signPayload`: HMAC 验签/签名, 与 Java `RemoteSignature`
 *   和 Python `luxera_application.signature` 逐字对应(同一个拼接、同一个前缀、
 *   常量时间比较)。Node 侧用 `node:crypto`; 浏览器没有 HMAC 时抛出并
 *   说明缺什么 —— 浏览器端本来就不该持有服务密钥, 那是远端服务的职责。
 * - `IdempotencyStore`: "已答问题的记忆", 与 Python 版同语义。
 *
 * 零依赖是刻意的: SDK 的安装成本越低, "协议优先、SDK 非必须"(§75/§104)
 * 这句话越像真的。
 */

import { createHmac, timingSafeEqual } from "node:crypto";

export const SIGNATURE_HEADER = "X-Lap-Signature";
export const TIMESTAMP_HEADER = "X-Lap-Timestamp";
export const SIGNATURE_SCHEME = "sha256=";

/** 平台一次转发给远端应用的载荷形状。 */
export interface LapActionRequest {
  action: string;
  applicationId?: string;
  version?: string;
  target?: string;
  input?: Record<string, unknown>;
  expectedResourceVersion?: number;
  correlationId?: string;
  principal?: { type?: string; id?: string; companionId?: string; userId?: string };
}

/** 平台认的响应形状: 裸 result, 或带 error 的信封。 */
export interface LapActionResponse {
  result?: Record<string, unknown>;
  error?: { code: string; message?: string; status?: string };
}

// ─────────────────────────── 签名 ───────────────────────────

export function signPayload(secret: string, timestamp: string, body: string): string {
  const digest = createHmac("sha256", secret)
    .update(`${timestamp}.${body}`)
    .digest("hex");
  return SIGNATURE_SCHEME + digest;
}

export function verifySignature(
  secret: string,
  timestamp: string | null | undefined,
  body: string,
  signature: string | null | undefined,
  replayWindowSeconds = 300,
  nowSeconds: number = Math.floor(Date.now() / 1000),
): boolean {
  if (!secret || !timestamp || !signature) return false;
  const ts = Number(timestamp);
  if (!Number.isFinite(ts)) return false;
  if (Math.abs(nowSeconds - ts) > replayWindowSeconds) return false;
  const expected = Buffer.from(signPayload(secret, timestamp, body), "utf-8");
  const received = Buffer.from(signature, "utf-8");
  if (expected.length !== received.length) return false;
  return timingSafeEqual(expected, received);
}

// ─────────────────────────── 幂等 ───────────────────────────

export class IdempotencyStore<T> {
  private entries = new Map<string, T>();
  private capacity: number;

  constructor(capacity = 1024) {
    this.capacity = capacity;
  }

  get(key: string | null | undefined): T | undefined {
    if (!key) return undefined;
    return this.entries.get(key);
  }

  put(key: string | null | undefined, response: T): void {
    if (!key) return;
    if (!this.entries.has(key)) {
      if (this.entries.size >= this.capacity) {
        const oldest = this.entries.keys().next().value;
        if (oldest !== undefined) this.entries.delete(oldest);
      }
    }
    this.entries.set(key, response);
  }
}

// ─────────────────────────── 客户端 ───────────────────────────

export interface LapClientOptions {
  baseUrl: string;
  /** 平台的 JWT(真人侧登录拿到); MCP/服务调用不需要它。 */
  token?: string;
  fetchImpl?: typeof fetch;
}

/** 平台 REST 面的最小子集 —— 前端应用页要的那几件事, 全在这里。 */
export class LapClient {
  private readonly base: string;
  private token: string | undefined;
  private readonly fetchImpl: typeof fetch;

  constructor(options: LapClientOptions) {
    this.base = options.baseUrl.replace(/\/$/, "");
    this.token = options.token;
    this.fetchImpl = options.fetchImpl ?? fetch;
  }

  setToken(token: string | undefined): void {
    this.token = token;
  }

  async capabilities(): Promise<Record<string, unknown>[]> {
    return this.get("/api/v1/capabilities");
  }

  async applicationsOf(capabilityId: string): Promise<Record<string, unknown>[]> {
    return this.get(`/api/v1/capabilities/${encodeURIComponent(capabilityId)}/applications`);
  }

  async application(applicationId: string): Promise<Record<string, unknown>> {
    return this.get(`/api/v1/applications/${encodeURIComponent(applicationId)}`);
  }

  async actionsOf(applicationId: string): Promise<Record<string, unknown>[]> {
    return this.get(`/api/v1/applications/${encodeURIComponent(applicationId)}/actions`);
  }

  /** 打开一个应用 = 开一个会话。响应是 §16 的形状(嵌套 application / participant)。 */
  async openSession(applicationId: string): Promise<Record<string, unknown>> {
    return this.post(`/api/v1/applications/${encodeURIComponent(applicationId)}/sessions`, {});
  }

  /** 执行动作。`idempotencyKey` 由调用方生成(写动作必带); 平台同键同答。 */
  async execute(
    action: string,
    target: string,
    input: Record<string, unknown>,
    idempotencyKey: string,
  ): Promise<Record<string, unknown>> {
    return this.post("/api/v1/actions:execute", { action, target, input }, idempotencyKey);
  }

  async readResource(uri: string): Promise<Record<string, unknown>[]> {
    return this.get(`/api/v1/resources?uri=${encodeURIComponent(uri)}`);
  }

  // ─────────────────────── 内部 ───────────────────────

  private async request(
    method: "GET" | "POST",
    path: string,
    body?: unknown,
    idempotencyKey?: string,
  ): Promise<any> {
    const headers: Record<string, string> = { "Content-Type": "application/json" };
    if (this.token) headers.Authorization = `Bearer ${this.token}`;
    if (idempotencyKey) headers["Idempotency-Key"] = idempotencyKey;
    const response = await this.fetchImpl(`${this.base}${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const text = await response.text();
    const parsed = text ? JSON.parse(text) : {};
    if (!response.ok) {
      const code = parsed?.error?.code ?? `HTTP_${response.status}`;
      const message = parsed?.error?.message ?? text.slice(0, 256);
      throw new LapHttpError(response.status, code, message);
    }
    return parsed;
  }

  private get(path: string): Promise<any> {
    return this.request("GET", path);
  }

  private post(path: string, body: unknown, idempotencyKey?: string): Promise<any> {
    return this.request("POST", path, body, idempotencyKey);
  }
}

export class LapHttpError extends Error {
  readonly status: number;
  readonly code: string;

  constructor(status: number, code: string, message: string) {
    super(message);
    this.status = status;
    this.code = code;
    this.name = "LapHttpError";
  }
}
