import { getToken } from './client'

/**
 * LAP v2 §63–§66 —— 把应用带进某段对话。
 *
 * 这个文件里的三个端点住在聊天平台上(`/api/companions/{c}/conversations/{v}/applications`),
 * 而不是 `lap.ts` 那些 `/api/v1/...` 上。两套路径不是笔误, 而是两个模块的边界:
 * 聊天平台在编译期看不见应用平台(§115), 它只知道一个契约端口 `ApplicationCatalogPort`。
 * 于是"在这段对话里开应用"这件事只能由聊天平台自己暴露 —— 而它暴露的方式恰好就是
 * 这三个端点: 看、开、分享。
 *
 * 为什么不把这三个函数塞进 `lap.ts`: 那个文件描述的是应用平台的 REST 面, 里面的每一个
 * 函数都可以被数字人 / 第三方客户端原样调用。这三个不行 —— 它们的前提是"我正站在某段
 * 对话里", 而那个前提只有聊天页面有。
 *
 * 错误信封与 `lap.ts` 不同:
 * 应用平台的失败是 `{status, error:{code, message}}`, 这里是 `{status, code, message}` ——
 * 因为跨模块那一层(`ApplicationCatalogException`)把 `error` 那层壳脱掉了, 只留最里面的
 * 那个码。两边都读的话, 迟早有人按一个信封去解析另一个的响应, 然后拿到 `undefined`。
 */

/** §64 `ApplicationCard` —— 一张可开的卡片。它不是应用详情, 只说"能不能开"。 */
export interface OpenableApplication {
  applicationId: string
  version?: string | null
  name: string
  description?: string | null
  category?: string | null
  capabilities: string[]
  /** §4.1 第一列: 在不在架。下架的应用 `inMarket` 为 false, 但它仍然认得出自己是谁。 */
  inMarket: boolean
  /** §4.1 第二列: 允不允许开新会话。这才是"能不能按"的判据。 */
  allowsNewSession: boolean
}

/** §64 `ApplicationSessionView` —— 这段对话里已经开着的一场。 */
export interface OpenSessionView {
  sessionId: string
  applicationId: string
  version?: string | null
  status: string
  visibility: string
  joinPolicy: string
  minParticipants: number
  maxParticipants: number
  participantCount: number
  conversationId?: string | null
  capabilities: string[]
  createdAt?: string | null
  startedAt?: string | null
  endedAt?: string | null
  lastActiveAt?: string | null
}

/** §64 `ParticipantView`。`participantId` 是"我在这一场里的位置", 不是我的账号 id。 */
export interface ParticipantRef {
  participantId: string
  principalType: string
  principalId: string
  role: string
  status: string
  owner: boolean
}

export interface ConversationApplications {
  openable: OpenableApplication[]
  open: OpenSessionView[]
}

export interface OpenedApplication {
  session: OpenSessionView
  participant: ParticipantRef
  /** 落在这段对话里的那条卡片消息 —— 客户端不必自己再拉一次消息列表。 */
  messageId: string
}

export interface SharedInvitation {
  invitationId: string
  sessionId: string
  /** 只出现这一次。平台上只有哈希, 丢了只能重铸。 */
  token: string
  joinUrl: string
  role: string
  maxUses?: number | null
  messageId: string
}

/** 聊天侧的错误信封: `{status, code, message}`。 */
export class ChatApplicationError extends Error {
  readonly code: string
  readonly status: string

  constructor(status: string, code: string, message: string) {
    super(message)
    this.status = status
    this.code = code
  }
}

async function send<T>(method: string, url: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  const token = getToken()
  if (token) headers['Authorization'] = `Bearer ${token}`

  const res = await fetch(url, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    cache: 'no-store',
  })

  const text = await res.text()
  const data = text ? JSON.parse(text) : null

  if (!res.ok) {
    const envelope = data as { status?: string; code?: string; message?: string } | null
    throw new ChatApplicationError(
      envelope?.status ?? String(res.status),
      envelope?.code ?? 'HTTP_' + res.status,
      envelope?.message ?? `请求失败 (${res.status})`,
    )
  }
  return data as T
}

function base(companionId: string, conversationId: string) {
  return `/api/companions/${encodeURIComponent(companionId)}/conversations/${encodeURIComponent(
    conversationId,
  )}/applications`
}

export const chatApplications = {
  /** 这段对话里能开什么、已经开着什么。 */
  context: (companionId: string, conversationId: string) =>
    send<ConversationApplications>('GET', base(companionId, conversationId)),

  /**
   * 在这段对话里开一个应用。
   *
   * `conversationId` 不出现在请求体里 —— 它在路径上。这不是省一个字段, 而是把"这一段
   * 对话"变成不可伪造的前提: 请求体里能写的话, 一个拿到别人 conversationId 的人就能把
   * 应用开到别人的对话里。
   *
   * 平台先开应用、再落卡片消息。被拒时(应用下架/不允许新会话)两边都不会留下痕迹 ——
   * 所以这里抛异常之后页面不需要"把那条假卡片删掉"。
   */
  open: (
    companionId: string,
    conversationId: string,
    applicationId: string,
    options?: {
      visibility?: string
      joinPolicy?: string
      minParticipants?: number
      maxParticipants?: number
    },
  ) =>
    send<OpenedApplication>('POST', base(companionId, conversationId), {
      applicationId,
      ...options,
    }),

  /**
   * 把这一场的加入链接作为一条消息发出去。
   *
   * 返回里的 `token` 只出现这一次(库里只存哈希), 同时平台已经落了一条带链接的消息 ——
   * 也就是说, 即使用户此刻没复制, 链接也没有丢: 它在对话里。
   */
  share: (
    companionId: string,
    conversationId: string,
    sessionId: string,
    options?: { role?: string; maxUses?: number },
  ) =>
    send<SharedInvitation>(
      'POST',
      `${base(companionId, conversationId)}/${encodeURIComponent(sessionId)}/share`,
      options ?? {},
    ),
}

/** 把卡片消息的 `metadata` 读成有类型的对象 —— 它来自 JSON, 每一个字段都可能是 undefined。 */
export function cardOf(metadata: Record<string, unknown> | null | undefined) {
  const m = metadata ?? {}
  return {
    applicationId: str(m.applicationId),
    sessionId: str(m.sessionId),
    name: str(m.name),
    description: str(m.description),
    version: str(m.version),
    role: str(m.role),
    status: str(m.status),
  }
}

/** 邀请消息的 `metadata`。`joinUrl` 是点得开的那一个, 其余是给人看的。 */
export function invitationOf(metadata: Record<string, unknown> | null | undefined) {
  const m = metadata ?? {}
  return {
    invitationId: str(m.invitationId),
    sessionId: str(m.sessionId),
    joinUrl: str(m.joinUrl),
    role: str(m.role),
    maxUses: typeof m.maxUses === 'number' ? m.maxUses : null,
  }
}

function str(v: unknown): string | null {
  return typeof v === 'string' && v.length > 0 ? v : null
}
