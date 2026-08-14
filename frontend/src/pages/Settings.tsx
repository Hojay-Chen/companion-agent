import { useCallback, useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { ArrowLeft, Download, Sparkles, Trash2 } from 'lucide-react'
import { api } from '@/api/client'
import CompanionAvatar from '@/components/CompanionAvatar'
import type { Companion, LifeEvent } from '@/types'
import { format } from 'date-fns'

export default function Settings() {
  const { id } = useParams<{ id: string }>()
  const companionId = id!
  const navigate = useNavigate()

  const [companion, setCompanion] = useState<Companion | null>(null)
  const [lifeEvents, setLifeEvents] = useState<LifeEvent[]>([])
  const [description, setDescription] = useState('')
  const [updating, setUpdating] = useState(false)
  const [msg, setMsg] = useState('')
  const [error, setError] = useState('')

  const load = useCallback(async () => {
    const [c, events] = await Promise.all([
      api.get<Companion>(`/api/companions/${companionId}`),
      api.get<LifeEvent[]>(`/api/companions/${companionId}/life-events`),
    ])
    setCompanion(c)
    setLifeEvents(events)
  }, [companionId])

  useEffect(() => {
    load()
  }, [load])

  async function updatePersona() {
    if (!description.trim()) return
    setUpdating(true)
    setMsg('')
    setError('')
    try {
      await api.put(`/api/companions/${companionId}/persona`, {
        description: description.trim(),
        reason: '用户在设置里重新描述',
      })
      setMsg('人格已更新,她以新的方式理解世界。')
      setDescription('')
      await load()
    } catch (err) {
      setError((err as Error).message)
    } finally {
      setUpdating(false)
    }
  }

  async function exportMemories() {
    const data = await api.get(`/api/companions/${companionId}/memories/export`)
    const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `${companion?.name || 'companion'}-memories.json`
    a.click()
    URL.revokeObjectURL(url)
  }

  async function clearMemories() {
    if (!confirm('确定清空她对你的所有记忆吗?这是不可逆的。')) return
    await api.del(`/api/companions/${companionId}/memories`)
    setMsg('她的记忆已清空。')
  }

  async function clearUserModel() {
    if (!confirm('确定让她忘掉对你的所有了解吗?')) return
    await api.del(`/api/companions/${companionId}/user-model/clear`)
    setMsg('她对你的了解已清空。')
  }

  async function deleteCompanion() {
    if (!confirm(`确定删除 ${companion?.name} 吗?这会永远失去她。`)) return
    await api.del(`/api/companions/${companionId}`)
    navigate('/companions', { replace: true })
  }

  if (!companion) {
    return <div className="flex min-h-screen items-center justify-center text-cocoa-500">加载中…</div>
  }

  return (
    <div className="min-h-screen bg-cocoa-950">
      <header className="border-b border-cocoa-800">
        <div className="mx-auto flex max-w-3xl items-center gap-3 px-5 py-4">
          <button onClick={() => navigate(`/companions/${companionId}`)} className="btn-ghost !px-3 !py-1.5">
            <ArrowLeft size={15} />
          </button>
          <span className="font-editorial text-lg text-cocoa-50">设置 · {companion.name}</span>
        </div>
      </header>

      <main className="mx-auto max-w-3xl space-y-6 px-5 py-8">
        {msg && <p className="rounded-xl border border-ember-soft/30 bg-ember/10 px-4 py-2 text-sm text-ember-soft">{msg}</p>}
        {error && <p className="rounded-xl border border-rosewood/30 bg-rosewood/10 px-4 py-2 text-sm text-rose-soft">{error}</p>}

        {/* 基础信息 */}
        <section className="card p-6">
          <div className="flex items-center gap-4">
            <CompanionAvatar name={companion.name} gender={companion.gender} size={60} />
            <div>
              <h2 className="font-editorial text-2xl text-cocoa-50">{companion.name}</h2>
              <p className="mt-1 text-sm text-cocoa-400">
                {companion.age !== null && companion.age !== undefined ? `${companion.age} 岁` : ''} · 生日 {companion.birthDate || '未设置'}
              </p>
              {companion.nextBirthday && (
                <p className="text-xs text-cocoa-500">下一个生日:{format(new Date(companion.nextBirthday), 'M月d日')}</p>
              )}
            </div>
          </div>
          {companion.persona?.personality?.summary && (
            <p className="mt-4 text-sm text-cocoa-300">{companion.persona.personality.summary}</p>
          )}
        </section>

        {/* 人格编辑 */}
        <section className="card p-6">
          <div className="mb-1 text-xs uppercase tracking-widest text-ember-soft">人格</div>
          <h3 className="font-editorial text-xl text-cocoa-50">重新描述她</h3>
          <p className="mt-1 text-sm text-cocoa-400">
            她的性格会重新编译成一个新版本,旧版本会保留。
          </p>
          <textarea
            className="input mt-3 min-h-28 resize-none"
            placeholder="描述你想要的她…"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
          />
          <button className="btn-primary mt-3" onClick={updatePersona} disabled={updating || !description.trim()}>
            <Sparkles size={15} />
            {updating ? '正在重新认识…' : '更新人格'}
          </button>
        </section>

        {/* 人生时间线 */}
        <section className="card p-6">
          <div className="mb-1 text-xs uppercase tracking-widest text-ember-soft">她的经历</div>
          <h3 className="font-editorial text-xl text-cocoa-50">人生时间线</h3>
          <div className="mt-4 space-y-0">
            {lifeEvents.map((e, i) => (
              <div key={e.id} className="relative flex gap-3 pb-4">
                {i < lifeEvents.length - 1 && <span className="absolute left-1.5 top-4 h-full w-px bg-cocoa-700" />}
                <span className="marker-dot mt-1.5 shrink-0 bg-ember-soft" />
                <div>
                  <div className="text-sm text-cocoa-100">{e.title}</div>
                  <div className="text-xs text-cocoa-500">
                    {e.startTime ? format(new Date(e.startTime), 'yyyy') : ''}
                    {e.endTime ? ` - ${format(new Date(e.endTime), 'yyyy')}` : ''}
                    {e.description ? ` · ${e.description}` : ''}
                  </div>
                </div>
              </div>
            ))}
            {lifeEvents.length === 0 && <p className="text-sm text-cocoa-500">还没有经历。</p>}
          </div>
        </section>

        {/* 隐私 */}
        <section className="card p-6">
          <div className="mb-1 text-xs uppercase tracking-widest text-ember-soft">隐私</div>
          <h3 className="font-editorial text-xl text-cocoa-50">记忆与数据</h3>
          <div className="mt-4 flex flex-wrap gap-2">
            <button className="btn-ghost" onClick={exportMemories}>
              <Download size={15} />
              导出记忆
            </button>
            <button className="btn-danger" onClick={clearMemories}>
              清空记忆
            </button>
            <button className="btn-danger" onClick={clearUserModel}>
              清空她对你的了解
            </button>
          </div>
          <p className="mt-3 text-xs text-cocoa-500">记忆可以导出为 JSON,也可以随时遗忘。</p>
        </section>

        {/* 危险区 */}
        <section className="card border-rosewood/30 p-6">
          <h3 className="font-editorial text-xl text-rose-soft">危险区</h3>
          <p className="mt-1 text-sm text-cocoa-400">删除后无法恢复,她会永远消失。</p>
          <button className="btn-danger mt-3" onClick={deleteCompanion}>
            <Trash2 size={15} />
            删除 {companion.name}
          </button>
        </section>
      </main>
    </div>
  )
}
