const TOKEN_KEY = 'companion_token'

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}
export function setToken(token: string) {
  localStorage.setItem(TOKEN_KEY, token)
}
export function clearToken() {
  localStorage.removeItem(TOKEN_KEY)
}

async function request<T>(method: string, url: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  const token = getToken()
  if (token) headers['Authorization'] = `Bearer ${token}`

  const res = await fetch(url, {
    method,
    headers,
    body: body !== undefined ? JSON.stringify(body) : undefined,
    cache: 'no-store',
  })

  if (!res.ok) {
    let message = `请求失败 (${res.status})`
    try {
      const data = await res.json()
      if (data?.error) message = data.error
    } catch {
      /* ignore */
    }
    throw new Error(message)
  }
  if (res.status === 204) return undefined as T
  return res.json() as Promise<T>
}

export const api = {
  get: <T>(url: string) => request<T>('GET', url),
  post: <T>(url: string, body?: unknown) => request<T>('POST', url, body),
  put: <T>(url: string, body?: unknown) => request<T>('PUT', url, body),
  del: <T>(url: string) => request<T>('DELETE', url),
}

/** SSE 流式解析: 从 POST 响应流中逐事件回调 */
export async function streamPost(
  url: string,
  body: unknown,
  onEvent: (event: string, data: unknown) => void,
): Promise<void> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  const token = getToken()
  if (token) headers['Authorization'] = `Bearer ${token}`

  const res = await fetch(url, {
    method: 'POST',
    headers,
    body: JSON.stringify(body),
    cache: 'no-store',
  })

  if (!res.ok || !res.body) {
    let message = `请求失败 (${res.status})`
    try {
      const data = await res.json()
      if (data?.error) message = data.error
    } catch {
      /* ignore */
    }
    throw new Error(message)
  }

  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    let idx: number
    while ((idx = buffer.indexOf('\n\n')) !== -1) {
      const block = buffer.slice(0, idx)
      buffer = buffer.slice(idx + 2)
      emitBlock(block, onEvent)
    }
  }
}

function emitBlock(block: string, onEvent: (event: string, data: unknown) => void) {
  let event = 'message'
  let data = ''
  for (const line of block.split('\n')) {
    if (line.startsWith('event:')) event = line.slice(6).trim()
    else if (line.startsWith('data:')) data += line.slice(5).trim()
  }
  if (!data) return
  try {
    onEvent(event, JSON.parse(data))
  } catch {
    onEvent(event, data)
  }
}

/** 持久事件流(GET /events), 断线自动重连 + 指数退避。
 *  §17: 增加 SSE 游标 —— 记录每条事件 id, 重连时携带 Last-Event-ID,
 *  服务器回放断线期间错过的消息(消息永不丢, 不靠整表重载补)。 */
export async function openEventStream(
  companionId: string,
  onEvent: (event: string, data: unknown, eventId?: string) => void,
): Promise<() => void> {
  let closed = false
  let controller: AbortController | null = null
  let retryMs = 1000
  let lastEventIdRef: string | null = null

  async function connect() {
    if (closed) return
    controller = new AbortController()
    const headers: Record<string, string> = {}
    const token = getToken()
    if (token) headers['Authorization'] = `Bearer ${token}`
    // 游标续传 —— 上次收到的事件 id
    if (lastEventIdRef) headers['Last-Event-ID'] = lastEventIdRef

    try {
      const res = await fetch(`/api/companions/${companionId}/events`, {
        method: 'GET',
        headers,
        cache: 'no-store',
        signal: controller.signal,
      })
      if (!res.ok || !res.body) throw new Error(`事件流连接失败 (${res.status})`)
      retryMs = 1000 // 连接成功 → 重置退避

      const reader = res.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      for (;;) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        let idx: number
        while ((idx = buffer.indexOf('\n\n')) !== -1) {
          const block = buffer.slice(0, idx)
          buffer = buffer.slice(idx + 2)
          emitBlockWithId(block, onEvent, (id) => {
            lastEventIdRef = id
          })
        }
      }
    } catch (err) {
      if (closed) return
      // 心跳事件也会触发 onEvent('ping'), 不影响
    }
    // 断线重连(指数退避, 上限 30s)
    if (!closed) {
      setTimeout(connect, retryMs)
      retryMs = Math.min(retryMs * 2, 30000)
    }
  }

  connect()

  return () => {
    closed = true
    if (controller) controller.abort()
  }
}

function emitBlockWithId(
  block: string,
  onEvent: (event: string, data: unknown, eventId?: string) => void,
  onId: (id: string) => void,
) {
  let event = 'message'
  let data = ''
  let id: string | undefined
  for (const line of block.split('\n')) {
    if (line.startsWith('event:')) event = line.slice(6).trim()
    else if (line.startsWith('data:')) data += line.slice(5).trim()
    else if (line.startsWith('id:')) id = line.slice(3).trim()
  }
  if (id) onId(id)
  if (!data) return
  try {
    onEvent(event, JSON.parse(data), id)
  } catch {
    onEvent(event, data, id)
  }
}
