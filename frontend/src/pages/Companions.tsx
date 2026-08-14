import { useEffect } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Heart, LogOut, Plus, Settings } from 'lucide-react'
import { useAuthStore } from '@/stores/auth'
import { useCompanionStore } from '@/stores/companion'
import CompanionAvatar from '@/components/CompanionAvatar'

const STAGE_ZH: Record<string, string> = {
  new: '初识',
  familiar: '熟络',
  close: '亲密',
  deeply_connected: '深深相连',
}

export default function Companions() {
  const navigate = useNavigate()
  const { user, logout, fetchMe } = useAuthStore()
  const { companions, loading, load } = useCompanionStore()

  useEffect(() => {
    if (!user) fetchMe()
  }, [user, fetchMe])

  useEffect(() => {
    load()
  }, [load])

  return (
    <div className="min-h-screen bg-cocoa-950">
      <header className="sticky top-0 z-10 border-b border-cocoa-800 bg-cocoa-950/80 backdrop-blur-md">
        <div className="mx-auto flex max-w-5xl items-center justify-between px-5 py-4">
          <div className="flex items-center gap-2">
            <Heart className="text-ember" size={20} fill="currentColor" />
            <span className="font-editorial text-lg text-cocoa-50">Companion</span>
          </div>
          <div className="flex items-center gap-3">
            <span className="text-sm text-cocoa-400">{user?.nickname || user?.username}</span>
            <button onClick={logout} className="btn-ghost !px-3 !py-1.5" title="退出登录">
              <LogOut size={15} />
            </button>
          </div>
        </div>
      </header>

      <main className="mx-auto max-w-5xl px-5 py-10">
        <div className="mb-8 flex items-end justify-between">
          <div>
            <h1 className="page-title">我的伴侣</h1>
            <p className="mt-1 text-sm text-cocoa-400">她们会记住你,陪着你,随时间慢慢了解你。</p>
          </div>
          <Link to="/companions/new" className="btn-primary">
            <Plus size={16} />
            创建新伴侣
          </Link>
        </div>

        {loading && <p className="py-16 text-center text-cocoa-500">加载中…</p>}

        {!loading && companions.length === 0 && (
          <div className="card mt-10 flex flex-col items-center px-8 py-16 text-center">
            <div className="mb-4 text-5xl">🫶</div>
            <h2 className="font-editorial text-2xl text-cocoa-100">还没有伴侣</h2>
            <p className="mt-2 max-w-sm text-sm text-cocoa-400">
              用一两句话描述你想要的她——性格、说话方式、你们的关系——她会从描述里诞生。
            </p>
            <Link to="/companions/new" className="btn-primary mt-6">
              遇见你的第一位
            </Link>
          </div>
        )}

        <div className="mt-6 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {companions.map((c, i) => (
            <button
              key={c.id}
              onClick={() => navigate(`/companions/${c.id}`)}
              className="card group animate-fadeUp p-5 text-left transition hover:border-ember-soft/40 hover:shadow-glow"
              style={{ animationDelay: `${i * 60}ms` }}
            >
              <div className="flex items-start justify-between">
                <CompanionAvatar name={c.name} gender={c.gender} size={52} />
                <Link
                  to={`/companions/${c.id}/settings`}
                  onClick={(e) => e.stopPropagation()}
                  className="rounded-lg p-1.5 text-cocoa-500 opacity-0 transition hover:bg-cocoa-800 hover:text-cocoa-200 group-hover:opacity-100"
                >
                  <Settings size={15} />
                </Link>
              </div>
              <h3 className="mt-4 font-editorial text-xl text-cocoa-50">{c.name}</h3>
              <p className="mt-0.5 text-sm text-cocoa-400">
                {c.age !== null && c.age !== undefined ? `${c.age} 岁` : ''} · {c.relationshipType === 'girlfriend' ? '恋人' : c.relationshipType === 'boyfriend' ? '恋人' : '朋友'}
              </p>
              <div className="mt-3 flex items-center gap-2">
                <span className="chip bg-ember/15 text-ember-soft">
                  {STAGE_ZH[c.relationshipStage || 'new'] || c.relationshipStage}
                </span>
                {c.persona?.personality?.summary && (
                  <span className="truncate text-xs text-cocoa-500">
                    {c.persona.personality.summary}
                  </span>
                )}
              </div>
            </button>
          ))}
        </div>
      </main>
    </div>
  )
}
