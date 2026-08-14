import { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import {
  Bell,
  Brain,
  Heart,
  MessageSquarePlus,
  Send,
  Settings,
  Sparkles,
} from 'lucide-react'
import { api, streamPost } from '@/api/client'
import CompanionAvatar from '@/components/CompanionAvatar'
import ChatBubble from '@/components/ChatBubble'
import Drawer from '@/components/Drawer'
import type {
  AgentState,
  Companion,
  Conversation,
  Memory,
  Message,
  Notification,
  Relationship,
  RelationshipEvent,
  Reminder,
  SharedExperience,
  UserFact,
  UserHypothesis,
  UserPattern,
  UserPreference,
} from '@/types'
import { format } from 'date-fns'

type DrawerTab = 'memories' | 'usermodel' | 'relationship' | 'reminders' | 'notifications' | null

const STAGE_ZH: Record<string, string> = {
  new: '初识',
  familiar: '熟络',
  close: '亲密',
  deeply_connected: '深深相连',
}

export default function Chat() {
  const { id } = useParams<{ id: string }>()
  const companionId = id!
  const navigate = useNavigate()

  const [companion, setCompanion] = useState<Companion | null>(null)
  const [conversations, setConversations] = useState<Conversation[]>([])
  const [activeConvId, setActiveConvId] = useState<string>('')
  const [messages, setMessages] = useState<Message[]>([])
  const [input, setInput] = useState('')
  const [streamingText, setStreamingText] = useState('')
  const [streaming, setStreaming] = useState(false)
  const [error, setError] = useState('')
  const [drawer, setDrawer] = useState<DrawerTab>(null)
  const [unread, setUnread] = useState(0)

  const bottomRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)

  // ── 数据加载 ─────────────────────────────
  const loadCompanion = useCallback(async () => {
    const c = await api.get<Companion>(`/api/companions/${companionId}`)
    setCompanion(c)
  }, [companionId])

  const refreshConversations = useCallback(async () => {
    const list = await api.get<Conversation[]>(`/api/companions/${companionId}/conversations`)
    setConversations(list)
    if (list.length > 0 && !list.some((c) => c.id === activeConvId)) {
      setActiveConvId(list[0].id)
    }
  }, [companionId, activeConvId])

  const loadMessages = useCallback(
    async (convId: string) => {
      const list = await api.get<Message[]>(`/api/companions/${companionId}/conversations/${convId}/messages`)
      setMessages(list)
    },
    [companionId],
  )

  const loadUnread = useCallback(async () => {
    const r = await api.get<{ count: number }>(`/api/companions/${companionId}/notifications/unread-count`)
    setUnread(r.count)
  }, [companionId])

  useEffect(() => {
    loadCompanion()
  }, [loadCompanion])

  useEffect(() => {
    ;(async () => {
      await loadCompanion()
      // 保证至少有一个带问候语的会话
      const first = await api.post<Conversation>(`/api/companions/${companionId}/conversations/first`, {})
      setActiveConvId(first.id)
      await refreshConversations()
      await loadMessages(first.id)
    })()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [companionId])

  useEffect(() => {
    if (activeConvId) loadMessages(activeConvId)
  }, [activeConvId, loadMessages])

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages, streamingText])

  useEffect(() => {
    loadUnread()
    const t = setInterval(loadUnread, 30000)
    return () => clearInterval(t)
  }, [loadUnread])

  // ── 发送消息 ─────────────────────────────
  async function send() {
    const content = input.trim()
    if (!content || streaming || !activeConvId) return
    setInput('')
    setError('')
    setStreaming(true)
    setStreamingText('')

    const tempUser: Message = {
      id: `temp-${Date.now()}`,
      conversationId: activeConvId,
      senderType: 'user',
      content,
      createdAt: new Date().toISOString(),
    }
    setMessages((prev) => [...prev, tempUser])

    try {
      await streamPost(
        `/api/companions/${companionId}/conversations/${activeConvId}/chat`,
        { content },
        (event, data) => {
          const d = data as Record<string, unknown>
          if (event === 'token') {
            setStreamingText((t) => t + String(d.delta ?? ''))
          } else if (event === 'replace') {
            setStreamingText(String(d.content ?? ''))
          } else if (event === 'error') {
            setError(String(d.message ?? '生成失败'))
          }
        },
      )
      await loadMessages(activeConvId)
      await refreshConversations()
    } catch (err) {
      setError((err as Error).message)
      await loadMessages(activeConvId)
    } finally {
      setStreaming(false)
      setStreamingText('')
    }
  }

  async function newConversation() {
    const conv = await api.post<Conversation>(`/api/companions/${companionId}/conversations`, { title: '新的对话' })
    setActiveConvId(conv.id)
    setMessages([])
    await refreshConversations()
  }

  function switchConversation(convId: string) {
    setActiveConvId(convId)
  }

  if (!companion) {
    return <div className="flex min-h-screen items-center justify-center text-cocoa-500">加载中…</div>
  }

  return (
    <div className="flex h-screen overflow-hidden bg-cocoa-950">
      {/* 侧栏 */}
      <aside className="flex w-72 shrink-0 flex-col border-r border-cocoa-800 bg-cocoa-900">
        <div className="border-b border-cocoa-800 p-4">
          <button onClick={() => navigate('/companions')} className="flex w-full items-center gap-3 text-left">
            <CompanionAvatar name={companion.name} gender={companion.gender} size={44} />
            <div className="min-w-0 flex-1">
              <div className="flex items-center gap-2">
                <span className="font-editorial text-lg text-cocoa-50">{companion.name}</span>
                <span className="text-xs text-cocoa-500">{companion.age ?? ''}岁</span>
              </div>
              <div className="mt-0.5 flex items-center gap-2 text-xs text-cocoa-400">
                <span className="chip bg-ember/15 text-ember-soft">
                  {STAGE_ZH[companion.relationshipStage || 'new']}
                </span>
              </div>
            </div>
            <button onClick={() => navigate(`/companions/${companionId}/settings`)} className="text-cocoa-500 hover:text-cocoa-200">
              <Settings size={16} />
            </button>
          </button>
        </div>

        <div className="flex-1 overflow-y-auto p-3">
          <button
            onClick={newConversation}
            className="mb-2 flex w-full items-center gap-2 rounded-xl border border-dashed border-cocoa-600 px-3 py-2 text-sm text-cocoa-400 transition hover:border-ember-soft/50 hover:text-ember-soft"
          >
            <MessageSquarePlus size={15} />
            新的对话
          </button>
          {conversations.map((c) => (
            <button
              key={c.id}
              onClick={() => switchConversation(c.id)}
              className={`mb-1 w-full rounded-xl px-3 py-2.5 text-left transition ${
                c.id === activeConvId ? 'bg-cocoa-800 text-cocoa-100' : 'text-cocoa-400 hover:bg-cocoa-850'
              }`}
            >
              <div className="truncate text-sm">{c.title}</div>
              <div className="mt-0.5 text-xs text-cocoa-500">{c.messageCount} 条消息</div>
            </button>
          ))}
        </div>

        <div className="border-t border-cocoa-800 p-3">
          <button
            onClick={() => setDrawer('notifications')}
            className="relative flex w-full items-center gap-2 rounded-xl px-3 py-2 text-sm text-cocoa-400 transition hover:bg-cocoa-850"
          >
            <Bell size={15} />
            消息与提醒
            {unread > 0 && (
              <span className="ml-auto rounded-full bg-ember px-2 py-0.5 text-xs text-cocoa-950">{unread}</span>
            )}
          </button>
          <button
            onClick={() => setDrawer('memories')}
            className="mt-1 flex w-full items-center gap-2 rounded-xl px-3 py-2 text-sm text-cocoa-400 transition hover:bg-cocoa-850"
          >
            <Brain size={15} />
            她的记忆
          </button>
          <button
            onClick={() => setDrawer('relationship')}
            className="mt-1 flex w-full items-center gap-2 rounded-xl px-3 py-2 text-sm text-cocoa-400 transition hover:bg-cocoa-850"
          >
            <Heart size={15} />
            你们的关系
          </button>
        </div>
      </aside>

      {/* 主区 */}
      <main className="flex min-w-0 flex-1 flex-col">
        <header className="flex items-center gap-3 border-b border-cocoa-800 bg-cocoa-950/80 px-5 py-3 backdrop-blur">
          <div className="flex items-center gap-2 text-sm">
            <Sparkles size={14} className="text-ember-soft" />
            <span className="text-cocoa-300">
              {companion.name} · {STAGE_ZH[companion.relationshipStage || 'new']}
            </span>
          </div>
          <button onClick={() => setDrawer('usermodel')} className="ml-auto text-cocoa-500 hover:text-ember-soft">
            <Brain size={16} />
          </button>
        </header>

        <div className="flex-1 overflow-y-auto px-4 py-6 sm:px-8">
          <div className="mx-auto flex max-w-2xl flex-col gap-3">
            {messages.map((m) => (
              <ChatBubble key={m.id} sender={m.senderType} content={m.content} />
            ))}
            {streaming && <ChatBubble sender="companion" content={streamingText} streaming />}
            <div ref={bottomRef} />
          </div>
        </div>

        {error && (
          <div className="mx-auto mb-2 w-full max-w-2xl rounded-xl border border-rosewood/30 bg-rosewood/10 px-4 py-2 text-sm text-rose-soft">
            {error}
          </div>
        )}

        <div className="border-t border-cocoa-800 px-4 py-3">
          <div className="mx-auto flex max-w-2xl items-end gap-2">
            <input
              ref={inputRef}
              className="input flex-1"
              placeholder={`想和${companion.name}说点什么…`}
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && !e.shiftKey) {
                  e.preventDefault()
                  send()
                }
              }}
            />
            <button onClick={send} disabled={streaming || !input.trim()} className="btn-primary !px-3.5">
              <Send size={16} />
            </button>
          </div>
        </div>
      </main>

      <Drawer
        open={drawer !== null}
        onClose={() => setDrawer(null)}
        title={drawerTitle(drawer, companion)}
      >
        {drawer === 'memories' && <MemoriesPanel companionId={companionId} />}
        {drawer === 'usermodel' && <UserModelPanel companionId={companionId} />}
        {drawer === 'relationship' && <RelationshipPanel companionId={companionId} />}
        {drawer === 'reminders' && <RemindersPanel companionId={companionId} />}
        {drawer === 'notifications' && <NotificationsPanel companionId={companionId} onRead={loadUnread} />}
      </Drawer>
    </div>
  )
}

function drawerTitle(drawer: DrawerTab, companion: Companion) {
  switch (drawer) {
    case 'memories':
      return `${companion.name}的记忆`
    case 'usermodel':
      return `${companion.name}对你的了解`
    case 'relationship':
      return '你们的关系'
    case 'reminders':
      return '提醒'
    case 'notifications':
      return '消息与提醒'
    default:
      return ''
  }
}

// ── 记忆面板 ───────────────────────────────
function MemoriesPanel({ companionId }: { companionId: string }) {
  const [memories, setMemories] = useState<Memory[]>([])
  const [q, setQ] = useState('')

  const load = useCallback(async () => {
    const list = await api.get<Memory[]>(`/api/companions/${companionId}/memories`)
    setMemories(list)
  }, [companionId])

  useEffect(() => {
    load()
  }, [load])

  async function search() {
    if (!q.trim()) return load()
    const list = await api.get<Memory[]>(`/api/companions/${companionId}/memories/search?q=${encodeURIComponent(q)}`)
    setMemories(list)
  }

  async function forget(id: string) {
    await api.del(`/api/companions/${companionId}/memories/${id}`)
    load()
  }

  async function clearAll() {
    if (!confirm('确定让她忘记所有这些记忆吗?')) return
    await api.del(`/api/companions/${companionId}/memories`)
    load()
  }

  const TYPE_ZH: Record<string, string> = { episodic: '经历', semantic: '认知', shared: '共同' }

  return (
    <div className="space-y-3">
      <div className="flex gap-2">
        <input
          className="input"
          placeholder="搜索记忆…(试试'咖啡'或'加班')"
          value={q}
          onChange={(e) => setQ(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && search()}
        />
        <button className="btn-ghost !px-3" onClick={search}>
          搜
        </button>
      </div>
      <p className="text-xs text-cocoa-500">她记得这些,并在聊天时自然地使用它们。你可以删除任何一条。</p>
      <div className="flex justify-end">
        <button className="btn-danger !px-3 !py-1 text-xs" onClick={clearAll}>
          清空全部
        </button>
      </div>
      {memories.length === 0 && <p className="py-8 text-center text-sm text-cocoa-500">还没有记忆,去和她聊聊吧。</p>}
      {memories.map((m) => (
        <div key={m.id} className="rounded-xl border border-cocoa-700 bg-cocoa-850 p-3">
          <div className="flex items-center gap-2 text-xs text-cocoa-500">
            <span className="chip bg-ember/10 text-ember-soft">{TYPE_ZH[m.type] || m.type}</span>
            <span>
              {m.occurredAt ? format(new Date(m.occurredAt), 'M月d日') : ''}
              {m.sourceType === 'conversation' ? ' · 来自你们的对话' : ''}
            </span>
            <button onClick={() => forget(m.id)} className="ml-auto text-cocoa-500 hover:text-rose-soft">
              忘记
            </button>
          </div>
          <p className="mt-1.5 text-sm text-cocoa-100">{m.content}</p>
        </div>
      ))}
    </div>
  )
}

// ── 用户模型面板 ────────────────────────────
function UserModelPanel({ companionId }: { companionId: string }) {
  const [facts, setFacts] = useState<UserFact[]>([])
  const [prefs, setPrefs] = useState<UserPreference[]>([])
  const [patterns, setPatterns] = useState<UserPattern[]>([])
  const [hypos, setHypos] = useState<UserHypothesis[]>([])

  useEffect(() => {
    ;(async () => {
      setFacts(await api.get<UserFact[]>(`/api/companions/${companionId}/user-model/facts`))
      setPrefs(await api.get<UserPreference[]>(`/api/companions/${companionId}/user-model/preferences`))
      setPatterns(await api.get<UserPattern[]>(`/api/companions/${companionId}/user-model/patterns`))
      setHypos(await api.get<UserHypothesis[]>(`/api/companions/${companionId}/user-model/hypotheses`))
    })()
  }, [companionId])

  return (
    <div className="space-y-5">
      <Section title="她知道的" hint="你明确告诉过她的">
        {facts.length === 0 && <Empty>还没有事实</Empty>}
        {facts.map((f) => (
          <Row key={f.id} label={`用户${zhPredicate(f.predicate)}${f.object ?? ''}`} conf={f.confidence} />
        ))}
      </Section>
      <Section title="她注意到的" hint="从你的习惯里观察到的">
        {patterns.length === 0 && <Empty>还没有行为模式</Empty>}
        {patterns.map((p) => (
          <Row key={p.id} label={p.description || p.pattern} conf={p.confidence} />
        ))}
      </Section>
      <Section title="她还在琢磨的" hint="只是推测,她不会把它当事实">
        {hypos.length === 0 && <Empty>还没有推测</Empty>}
        {hypos.map((h) => (
          <Row key={h.id} label={h.description || h.hypothesis} conf={h.confidence} mark="?" />
        ))}
      </Section>
      <Section title="沟通偏好" hint="她倾向怎么和你相处">
        {prefs.length === 0 && <Empty>还没有偏好记录</Empty>}
        {prefs.map((p) => (
          <Row key={p.id} label={p.preference} conf={p.confidence} />
        ))}
      </Section>
    </div>
  )
}

function Section({ title, hint, children }: { title: string; hint?: string; children: React.ReactNode }) {
  return (
    <div>
      <div className="mb-2">
        <h4 className="text-sm font-medium text-cocoa-100">{title}</h4>
        {hint && <p className="text-xs text-cocoa-500">{hint}</p>}
      </div>
      <div className="space-y-1.5">{children}</div>
    </div>
  )
}

function Row({ label, conf, mark }: { label: string; conf: number; mark?: string }) {
  return (
    <div className="flex items-center justify-between gap-2 rounded-lg bg-cocoa-850 px-3 py-2 text-sm">
      <span className="truncate text-cocoa-200">
        {mark && <span className="mr-1 text-ember-soft">{mark}</span>}
        {label}
      </span>
      <span className="shrink-0 text-xs text-cocoa-500">{Math.round(conf * 100)}%</span>
    </div>
  )
}

function Empty({ children }: { children: React.ReactNode }) {
  return <p className="rounded-lg bg-cocoa-900 px-3 py-2 text-xs text-cocoa-500">{children}</p>
}

function zhPredicate(p: string) {
  switch (p) {
    case 'likes':
      return '喜欢'
    case 'prefers':
      return '更喜欢'
    case 'dislikes':
      return '不喜欢'
    case 'works_as':
      return '工作是'
    case 'lives_in':
      return '住在'
    default:
      return p
  }
}

// ── 关系面板 ───────────────────────────────
function RelationshipPanel({ companionId }: { companionId: string }) {
  const [data, setData] = useState<{
    relationship?: Relationship
    events?: RelationshipEvent[]
    sharedExperiences?: SharedExperience[]
    state?: AgentState | null
  }>({})

  useEffect(() => {
    api
      .get<{ relationship: Relationship; events: RelationshipEvent[]; sharedExperiences: SharedExperience[]; state: AgentState | null }>(
        `/api/companions/${companionId}/relationship`,
      )
      .then(setData)
  }, [companionId])

  const rel = data.relationship
  return (
    <div className="space-y-5">
      {rel && (
        <div className="rounded-2xl border border-cocoa-700 bg-cocoa-850 p-4">
          <div className="flex items-center justify-between">
            <span className="font-editorial text-xl text-cocoa-50">{STAGE_ZH[rel.relationshipStage]}</span>
            <span className="text-xs text-cocoa-500">
              {rel.startedAt ? format(new Date(rel.startedAt), 'yyyy年M月') : ''} 开始
            </span>
          </div>
          <div className="mt-3 space-y-1.5 text-xs text-cocoa-400">
            <Meter label="熟悉度" value={rel.familiarity} />
            <Meter label="信任" value={rel.trust} />
            <Meter label="亲密度" value={rel.intimacy} />
            <Meter label="好感" value={rel.affection} />
          </div>
          <p className="mt-3 text-xs text-cocoa-500">
            累计 {rel.messageCount} 条消息 · {rel.sharedExperienceCount} 段共同经历
          </p>
        </div>
      )}

      {data.state && (
        <div className="rounded-2xl border border-cocoa-700 bg-cocoa-850 p-4">
          <h4 className="text-sm font-medium text-cocoa-100">她此刻</h4>
          <div className="mt-2 grid grid-cols-2 gap-1.5 text-xs text-cocoa-400">
            <span>心情:{data.state.mood || '平静'}</span>
            <span>精力:{pct(data.state.energy)}</span>
            <span>压力:{pct(data.state.stress)}</span>
            <span>亲密感:{pct(data.state.emotionalCloseness)}</span>
          </div>
        </div>
      )}

      {(data.sharedExperiences?.length ?? 0) > 0 && (
        <div>
          <h4 className="mb-2 text-sm font-medium text-cocoa-100">共同经历</h4>
          <div className="space-y-1.5">
            {data.sharedExperiences!.map((s) => (
              <div key={s.id} className="rounded-lg bg-cocoa-850 px-3 py-2 text-sm text-cocoa-200">
                <span className="text-ember-soft">◆ </span>
                {s.title}
                <span className="ml-2 text-xs text-cocoa-500">{format(new Date(s.occurredAt), 'M月d日')}</span>
              </div>
            ))}
          </div>
        </div>
      )}

      {(data.events?.length ?? 0) > 0 && (
        <div>
          <h4 className="mb-2 text-sm font-medium text-cocoa-100">关系里程碑</h4>
          <div className="space-y-1.5">
            {data.events!.map((e) => (
              <div key={e.id} className="rounded-lg bg-cocoa-850 px-3 py-2 text-sm text-cocoa-200">
                {e.title}
                <span className="ml-2 text-xs text-cocoa-500">{format(new Date(e.occurredAt), 'M月d日 HH:mm')}</span>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}

function Meter({ label, value }: { label: string; value: number }) {
  return (
    <div className="flex items-center gap-2">
      <span className="w-12">{label}</span>
      <div className="h-1 flex-1 overflow-hidden rounded-full bg-cocoa-800">
        <div className="h-full rounded-full bg-ember-soft" style={{ width: `${Math.round(value * 100)}%` }} />
      </div>
      <span className="w-8 text-right">{Math.round(value * 100)}</span>
    </div>
  )
}

function pct(v: number) {
  return `${Math.round(Math.max(0, Math.min(1, v)) * 100)}%`
}

// ── 提醒面板 ───────────────────────────────
function RemindersPanel({ companionId }: { companionId: string }) {
  const [reminders, setReminders] = useState<Reminder[]>([])
  const [title, setTitle] = useState('')
  const [time, setTime] = useState('')
  const [content, setContent] = useState('')

  const load = useCallback(async () => {
    setReminders(await api.get<Reminder[]>(`/api/companions/${companionId}/reminders`))
  }, [companionId])

  useEffect(() => {
    load()
  }, [load])

  async function create() {
    if (!title.trim() || !time) return
    await api.post(`/api/companions/${companionId}/reminders`, {
      type: 'user_set',
      title: title.trim(),
      content: content.trim() || undefined,
      remindAt: time,
    })
    setTitle('')
    setTime('')
    setContent('')
    load()
  }

  async function done(id: string) {
    await api.put(`/api/companions/${companionId}/reminders/${id}/done`)
    load()
  }

  return (
    <div className="space-y-4">
      <div className="rounded-2xl border border-cocoa-700 bg-cocoa-850 p-3">
        <input className="input mb-2" placeholder="提醒我…(例如:记得喝水)" value={title} onChange={(e) => setTitle(e.target.value)} />
        <div className="flex gap-2">
          <input
            className="input"
            type="datetime-local"
            value={time}
            onChange={(e) => setTime(e.target.value)}
          />
          <button className="btn-primary shrink-0" onClick={create}>
            添加
          </button>
        </div>
      </div>
      <p className="text-xs text-cocoa-500">到时间后,她会主动提醒你(以通知形式)。</p>
      {reminders.map((r) => (
        <div key={r.id} className="rounded-xl border border-cocoa-700 bg-cocoa-850 px-3 py-2.5">
          <div className="flex items-center justify-between">
            <span className={`text-sm ${r.status === 'done' ? 'text-cocoa-500 line-through' : 'text-cocoa-100'}`}>
              {r.title}
            </span>
            {r.status === 'pending' && (
              <button onClick={() => done(r.id)} className="text-xs text-ember-soft hover:underline">
                完成
              </button>
            )}
          </div>
          <div className="mt-0.5 text-xs text-cocoa-500">
            {format(new Date(r.remindAt), 'M月d日 HH:mm')} · {r.type === 'birthday' ? '生日' : r.type === 'user_set' ? '自定义' : r.type}
          </div>
        </div>
      ))}
      {reminders.length === 0 && <p className="text-center text-sm text-cocoa-500">还没有提醒。</p>}
    </div>
  )
}

// ── 通知面板 ───────────────────────────────
function NotificationsPanel({ companionId, onRead }: { companionId: string; onRead: () => void }) {
  const [notifications, setNotifications] = useState<Notification[]>([])

  useEffect(() => {
    api.get<Notification[]>(`/api/companions/${companionId}/notifications`).then((list) => {
      setNotifications(list)
      if (list.some((n) => !n.read)) {
        api.put(`/api/companions/${companionId}/notifications/read-all`)
        onRead()
      }
    })
  }, [companionId, onRead])

  return (
    <div className="space-y-3">
      {notifications.length === 0 && <p className="py-8 text-center text-sm text-cocoa-500">暂时没有新消息。</p>}
      {notifications.map((n) => (
        <div key={n.id} className="rounded-xl border border-cocoa-700 bg-cocoa-850 p-3">
          <div className="flex items-center gap-2 text-xs">
            <span className="chip bg-ember/10 text-ember-soft">
              {n.type === 'proactive' ? '她主动' : n.type === 'birthday' ? '生日' : n.type === 'reminder' ? '提醒' : n.type}
            </span>
            <span className="text-cocoa-500">{format(new Date(n.createdAt), 'M月d日 HH:mm')}</span>
          </div>
          <p className="mt-1.5 text-sm text-cocoa-100">{n.title}</p>
          {n.content && <p className="mt-0.5 text-xs text-cocoa-400">{n.content}</p>}
        </div>
      ))}
    </div>
  )
}
