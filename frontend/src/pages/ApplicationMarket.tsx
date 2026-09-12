import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { ArrowLeft, Boxes, Play, Sparkles } from 'lucide-react'
import {
  lap,
  LapError,
  type ApplicationView,
  type CapabilityView,
  type SessionResponse,
} from '@/api/lap'

/**
 * 「应用市场」—— §16 那张图的第一层。
 *
 * <pre>
 *   应用
 *    ├── 五子棋
 *    ├── 井字棋
 *    └── 日程
 * </pre>
 *
 * 两种逛法, 因为来的人有两种:
 * <ul>
 *   <li><b>知道要做什么</b>("我想下棋") —— 按能力筛。能力就是"我想干什么"的词表。</li>
 *   <li><b>只是想看看</b> —— 直接列全部在架应用。</li>
 * </ul>
 * 能力筛选是<b>客户端</b>筛的(拿应用列表与能力列表在本地对), 不是多一次请求: 市场本来
 * 就一次把在架应用拿全了, 再为每次点能力跑一趟服务端只会让切换变慢。
 *
 * <h2>"打开"在这里只是"进去看看"</h2>
 * 市场页不直接开会话 —— 它会跳到应用详情页。理由: 开一局是**有后果**的动作(留下一条会话、
 * 可能给对手发事件), 而"点一张卡片"表达的只是好奇。让人先看见"这是什么、能不能开",
 * 再决定, 比点错了再退出来便宜。
 */
export default function ApplicationMarket() {
  const navigate = useNavigate()
  const [applications, setApplications] = useState<ApplicationView[]>([])
  const [capabilities, setCapabilities] = useState<CapabilityView[]>([])
  const [capability, setCapability] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const report = useCallback((e: unknown) => {
    if (e instanceof LapError) setError(`${e.code} — ${e.message}`)
    else setError(e instanceof Error ? e.message : String(e))
  }, [])

  useEffect(() => {
    Promise.all([lap.market(), lap.capabilities()])
      .then(([apps, caps]) => {
        setApplications(apps)
        setCapabilities(caps)
      })
      .catch(report)
  }, [report])

  /**
   * 直接开一局 —— 给"我已经知道要下棋, 别让我再看一页"的人。
   *
   * 它走的端点与详情页里的"打开"完全一样; 这里只是少了一次跳转, 不是另一条捷径。
   */
  const openNow = async (applicationId: string) => {
    setBusy(true)
    setError(null)
    try {
      const session: SessionResponse = await lap.openSession(applicationId)
      navigate(`/applications/${encodeURIComponent(applicationId)}/sessions/${session.sessionId}`)
    } catch (e) {
      report(e)
    } finally {
      setBusy(false)
    }
  }

  const shown = capability
    ? applications.filter((a) => a.capabilities?.includes(capability))
    : applications

  return (
    <div className="min-h-screen bg-cocoa-950">
      <header className="sticky top-0 z-10 border-b border-cocoa-800 bg-cocoa-950/80 backdrop-blur-md">
        <div className="mx-auto flex max-w-5xl items-center gap-3 px-5 py-4">
          <Link to="/companions" className="btn-ghost !px-3 !py-1.5" title="回到伴侣">
            <ArrowLeft size={15} />
          </Link>
          <Boxes className="text-ember" size={20} />
          <span className="font-editorial text-lg text-cocoa-50">应用市场</span>
        </div>
      </header>

      <main className="mx-auto max-w-5xl px-5 py-10">
        <h1 className="page-title">发现应用</h1>
        <p className="mt-1 text-sm text-cocoa-400">
          打开一个应用就是开一场会话 —— 可以邀请真人和数字人一起进来。
        </p>

        {error && (
          <div className="card mt-6 border-red-900/60 bg-red-950/30 text-sm text-red-300">
            {error}
          </div>
        )}

        <section className="mt-8">
          <h2 className="text-sm font-medium text-cocoa-300">按能力筛选</h2>
          <div className="mt-3 flex flex-wrap gap-2">
            <button
              type="button"
              onClick={() => setCapability(null)}
              className={capability === null ? 'btn-primary' : 'btn-ghost'}
            >
              全部
            </button>
            {capabilities.map((c) => (
              <button
                key={c.capabilityId}
                type="button"
                onClick={() => setCapability(c.capabilityId)}
                className={c.capabilityId === capability ? 'btn-primary' : 'btn-ghost'}
                title={c.description ?? undefined}
              >
                {c.title || c.capabilityId}
              </button>
            ))}
          </div>
        </section>

        <section className="mt-8">
          <h2 className="text-sm font-medium text-cocoa-300">
            {capability ? `${capability} 下的应用` : '在架的应用'}
          </h2>
          <div className="mt-3 grid gap-3 sm:grid-cols-2">
            {shown.map((a) => (
              <div key={a.applicationId} className="card flex flex-col gap-3">
                <Link to={`/applications/${encodeURIComponent(a.applicationId)}`} className="block">
                  <div className="text-cocoa-100">{a.name || a.applicationId}</div>
                  <div className="mt-1 text-xs text-cocoa-500">
                    {a.applicationId} v{a.version}
                  </div>
                  {a.description && <p className="mt-2 text-sm text-cocoa-400">{a.description}</p>}
                </Link>
                <div className="flex items-center gap-2">
                  <button
                    type="button"
                    disabled={busy}
                    onClick={() => openNow(a.applicationId)}
                    className="btn-primary !px-3 !py-1 text-xs"
                  >
                    <Play size={13} />
                    打开
                  </button>
                  <Link
                    to={`/applications/${encodeURIComponent(a.applicationId)}`}
                    className="btn-ghost !px-3 !py-1 text-xs"
                  >
                    详情
                  </Link>
                </div>
              </div>
            ))}
            {shown.length === 0 && (
              <p className="text-sm text-cocoa-500">
                {capability ? '这个能力下暂时没有在架的应用。' : '市场里还没有应用。'}
              </p>
            )}
          </div>
        </section>

        <p className="mt-10 flex items-center gap-2 text-xs text-cocoa-600">
          <Sparkles size={13} />
          数字人走的是同一个市场、同一条打开链路 —— 它们没有专用接口。
        </p>
      </main>
    </div>
  )
}
