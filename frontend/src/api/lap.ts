import { getToken } from './client'

/**
 * LAP v1 —— 应用平台的 REST 面。
 *
 * 这个文件里没有"真人专用"的任何东西, 因为协议里本来就没有: 真人和数字人调的是同一个
 * `POST /api/v1/actions:execute`, 带同一个 `Idempotency-Key` 头, 拿到同一个 `ActionResponse`。
 * 页面能做的事, 数字人也做得到 —— 这正是这一层值得单独存在的理由。
 *
 * 与 `client.ts` 里的 `request()` 相比, 这里有两处必须不同:
 *
 * 1. **错误信封不一样。** 平台的失败响应是 `{status, error:{code, message}}`, 而 `error` 是个
 *    对象 —— 按 `data.error` 当字符串读会得到 `[object Object]`, 于是"为什么被拒"永远显示不出来。
 * 2. **写动作必须带幂等键。** 没有键就 400, 平台不会替客户端派生一个 ——
 *    「忘了带」是客户端 bug, 应当立刻暴露。
 */

export interface CapabilityView {
  capabilityId: string
  title?: string | null
  description?: string | null
  category?: string | null
}

export interface ApplicationView {
  applicationId: string
  version?: string | null
  name?: string | null
  description?: string | null
  category?: string | null
  capabilities?: string[]
}

export interface ActionSpec {
  actionId: string
  applicationId?: string
  capabilityId?: string
  description?: string | null
  permissionLevel?: 'READ' | 'WRITE' | 'EXECUTE'
  riskLevel?: string
  attention?: string
  inputSchema?: unknown
  agentHint?: string | null
}

export interface ResourceView {
  uri: string
  resourceType?: string
  applicationId?: string
  sessionId?: string
  state?: Record<string, unknown> | null
  version: number
  updatedAt?: string
  agentHint?: string | null
}

export interface ActionError {
  code: string
  message?: string | null
}

export interface ActionResponse<T = unknown> {
  status: string
  result?: T | null
  resource?: ResourceView | null
  events?: unknown[]
  error?: ActionError | null
}

export interface InstallResponse {
  installationId: string
  sessionId: string
  applicationId: string
  version?: string | null
  principalType: string
  principalId: string
  capabilities: string[]
}

export class LapError extends Error {
  readonly code: string
  readonly status: string

  constructor(status: string, error: ActionError) {
    super(error.message || error.code)
    this.status = status
    this.code = error.code
  }
}

async function send<T>(method: string, url: string, body?: unknown, key?: string): Promise<T> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  const token = getToken()
  if (token) headers['Authorization'] = `Bearer ${token}`
  if (key) headers['Idempotency-Key'] = key

  const res = await fetch(url, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    cache: 'no-store',
  })

  const text = await res.text()
  const data = text ? JSON.parse(text) : null

  if (!res.ok) {
    // 平台把失败原因放在 error.code / error.message 里; 没有信封时退回 HTTP 状态。
    const envelope = data as ActionResponse | null
    if (envelope?.error?.code) {
      throw new LapError(envelope.status ?? String(res.status), envelope.error)
    }
    throw new LapError(String(res.status), {
      code: 'HTTP_' + res.status,
      message: typeof data === 'string' ? data : undefined,
    })
  }
  return data as T
}

/** 写动作的键由调用方给 —— 重试时必须原样复用同一个键, 否则幂等无从谈起。 */
export function newIdempotencyKey(): string {
  return crypto.randomUUID()
}

export const lap = {
  capabilities: () => send<CapabilityView[]>('GET', '/api/v1/capabilities'),

  applicationsOf: (capabilityId: string) =>
    send<ApplicationView[]>(
      'GET',
      `/api/v1/capabilities/${encodeURIComponent(capabilityId)}/applications`,
    ),

  actionsOf: (applicationId: string) =>
    send<ActionSpec[]>('GET', `/api/v1/applications/${encodeURIComponent(applicationId)}/actions`),

  install: (applicationId: string, capabilities?: string[]) =>
    send<InstallResponse>(
      'POST',
      `/api/v1/applications/${encodeURIComponent(applicationId)}/install`,
      capabilities ? { capabilities } : {},
    ),

  readResource: (uri: string) =>
    send<ResourceView[]>('GET', `/api/v1/resources?uri=${encodeURIComponent(uri)}`),

  /**
   * 唯一的动作入口。`key` 只对 WRITE / EXECUTE 需要; READ 传空 ——
   * 给读动作配一把键会让第二次读<b>重放</b>第一次的旧结果。
   */
  execute: (action: string, target: string | null, input: unknown, key?: string) =>
    send<ActionResponse>('POST', '/api/v1/actions:execute', { action, target, input }, key),
}
