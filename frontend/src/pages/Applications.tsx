import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { ArrowLeft, Boxes, Play, RefreshCw } from 'lucide-react'
import {
  lap,
  LapError,
  newIdempotencyKey,
  type ActionSpec,
  type ApplicationView,
  type CapabilityView,
  type InstallResponse,
  type ResourceView,
} from '@/api/lap'

/**
 * 「应用」页 —— 一个<b>普通的 LAP 调用方</b>, 没有一行真人特供的代码。
 *
 * 它走的就是数字人走的那条链: 能力 → 候选应用 → 动作 → `actions:execute`。
 * 页面上每次点落子都自带一把新的 `Idempotency-Key`, 每次失败都把 `error.code` 原样显示出来
 * (DENIED / STATE_CONFLICT / IDEMPOTENCY_KEY_REQUIRED ...) —— 这些码就是平台对调用方的全部交代,
 * 藏起来反而让人以为是"点了没反应"。
 *
 * 棋盘只是资源的一种画法: `resource.state.board` 是长度 9 的数组时画格子, 否则原样显示 JSON。
 * 平台对井字棋一无所知, 这个页面也不该知道得更多。
 */

export default function Applications() {
  const [capabilities, setCapabilities] = useState<CapabilityView[]>([])
  const [capability, setCapability] = useState<CapabilityView | null>(null)
  const [applications, setApplications] = useState<ApplicationView[]>([])
  const [application, setApplication] = useState<ApplicationView | null>(null)
  const [actions, setActions] = useState<ActionSpec[]>([])

  const [installation, setInstallation] = useState<InstallResponse | null>(null)
  const [resource, setResource] = useState<ResourceView | null>(null)

  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)

  const report = useCallback((e: unknown) => {
    setError(e instanceof Error ? `${e.message}` : String(e))
  }, [])

  useEffect(() => {
    lap.capabilities().then(setCapabilities).catch(report)
  }, [report])

  const pickCapability = async (next: CapabilityView) => {
    setCapability(next)
    setApplication(null)
    setInstallation(null)
    setResource(null)
    setError(null)
    try {
      setApplications(await lap.applicationsOf(next.capabilityId))
    } catch (e) {
      report(e)
    }
  }

  const pickApplication = async (next: ApplicationView) => {
    setApplication(next)
    setInstallation(null)
    setResource(null)
    setError(null)
    try {
      setActions(await lap.actionsOf(next.applicationId))
    } catch (e) {
      report(e)
    }
  }

  /** 安装会顺带开一个会话 —— 那一段 id 就是后面所有 target 里的 `game://session/{id}`。 */
  const install = async () => {
    if (!application) return
    setBusy(true)
    setError(null)
    setNotice(null)
    try {
      const result = await lap.install(application.applicationId)
      setInstallation(result)
      setNotice(`已安装 ${result.applicationId} v${result.version ?? '?'}, 授权 ${result.capabilities.join(', ')}`)
    } catch (e) {
      report(e)
    } finally {
      setBusy(false)
    }
  }

  const targetUri = installation ? resourceUriOf(installation.sessionId) : null

  const run = async (spec: ActionSpec, input: unknown, key?: string) => {
    if (!targetUri) return
    setBusy(true)
    setError(null)
    try {
      const response = await lap.execute(spec.actionId, targetUri, input, key)
      if (response.resource) setResource(response.resource)
      // 成功的写入不再解释; 失败把码与话原样摆出来, 那才是调用方真正需要的信息
      setNotice(response.status === 'SUCCESS' ? `${spec.actionId} → SUCCESS` : null)
    } catch (e) {
      // LapError 带着平台给的 error.code —— 那才是"为什么被拒"的答案
      if (e instanceof LapError) {
        setError(`${spec.actionId} 被拒绝: ${e.code} — ${e.message}`)
      } else {
        report(e)
      }
    } finally {
      setBusy(false)
    }
  }

  const refresh = async () => {
    if (!targetUri) return
    setBusy(true)
    setError(null)
    try {
      const views = await lap.readResource(targetUri)
      setResource(views[0] ?? null)
    } catch (e) {
      report(e)
    } finally {
      setBusy(false)
    }
  }

  const board = boardOf(resource)

  return (
    <div className="min-h-screen bg-cocoa-950">
      <header className="sticky top-0 z-10 border-b border-cocoa-800 bg-cocoa-950/80 backdrop-blur-md">
        <div className="mx-auto flex max-w-5xl items-center justify-between px-5 py-4">
          <div className="flex items-center gap-3">
            <Link to="/companions" className="btn-ghost !px-3 !py-1.5" title="回到伴侣">
              <ArrowLeft size={15} />
            </Link>
            <div className="flex items-center gap-2">
              <Boxes className="text-ember" size={20} />
              <span className="font-editorial text-lg text-cocoa-50">应用</span>
            </div>
          </div>
        </div>
      </header>

      <main className="mx-auto max-w-5xl px-5 py-10">
        <h1 className="page-title">发现并操作应用</h1>
        <p className="mt-1 text-sm text-cocoa-400">
          能力 → 应用 → 动作。数字人走的是同一条链、同一个接口。
        </p>

        {error && (
          <div className="card mt-6 border-red-900/60 bg-red-950/30 text-sm text-red-300">
            {error}
          </div>
        )}
        {notice && !error && (
          <div className="card mt-6 border-cocoa-800 text-sm text-cocoa-300">{notice}</div>
        )}

        {/* 一级: 能力 */}
        <section className="mt-8">
          <h2 className="text-sm font-medium text-cocoa-300">能力</h2>
          <div className="mt-3 flex flex-wrap gap-2">
            {capabilities.map((c) => (
              <button
                key={c.capabilityId}
                onClick={() => pickCapability(c)}
                className={c.capabilityId === capability?.capabilityId ? 'btn-primary' : 'btn-ghost'}
              >
                {c.title || c.capabilityId}
              </button>
            ))}
            {capabilities.length === 0 && <p className="text-sm text-cocoa-500">没有可用的能力。</p>}
          </div>
        </section>

        {/* 二级: 候选应用 */}
        {capability && (
          <section className="mt-8">
            <h2 className="text-sm font-medium text-cocoa-300">
              {capability.capabilityId} 下的应用
            </h2>
            <div className="mt-3 grid gap-3 sm:grid-cols-2">
              {applications.map((a) => (
                <button
                  key={a.applicationId}
                  onClick={() => pickApplication(a)}
                  className={`card text-left transition ${
                    a.applicationId === application?.applicationId
                      ? 'border-ember/60'
                      : 'hover:border-cocoa-700'
                  }`}
                >
                  <div className="text-cocoa-100">{a.name || a.applicationId}</div>
                  <div className="mt-1 text-xs text-cocoa-500">
                    {a.applicationId} v{a.version}
                  </div>
                  {a.description && (
                    <p className="mt-2 text-sm text-cocoa-400">{a.description}</p>
                  )}
                </button>
              ))}
            </div>
          </section>
        )}

        {/* 三级: 动作 */}
        {application && (
          <section className="mt-8">
            <div className="flex items-center justify-between">
              <h2 className="text-sm font-medium text-cocoa-300">
                {application.name} 的动作
              </h2>
              {!installation && (
                <button onClick={install} disabled={busy} className="btn-primary">
                  安装并开会话
                </button>
              )}
            </div>

            <div className="mt-3 space-y-2">
              {actions.map((spec) => (
                <div key={spec.actionId} className="card flex items-center justify-between gap-4">
                  <div>
                    <div className="text-cocoa-100">{spec.actionId}</div>
                    <div className="mt-1 text-xs text-cocoa-500">
                      {spec.permissionLevel} · 风险 {spec.riskLevel}
                    </div>
                    {spec.description && (
                      <p className="mt-1 text-sm text-cocoa-400">{spec.description}</p>
                    )}
                  </div>
                  <button
                    className="btn-ghost shrink-0"
                    disabled={!installation || busy}
                    onClick={() => run(spec, {}, spec.permissionLevel === 'READ' ? undefined : newIdempotencyKey())}
                  >
                    <Play size={14} />
                    执行
                  </button>
                </div>
              ))}
            </div>
          </section>
        )}

        {/* 资源: 统一读模型的一种画法 */}
        {installation && (
          <section className="mt-8">
            <div className="flex items-center gap-3">
              <h2 className="text-sm font-medium text-cocoa-300">资源</h2>
              <span className="text-xs text-cocoa-500">{targetUri}</span>
              <button onClick={refresh} disabled={busy} className="btn-ghost !px-3 !py-1">
                <RefreshCw size={14} />
                读一次
              </button>
            </div>

            {board ? (
              <div className="mt-3 grid w-fit grid-cols-3 gap-1">
                {board.map((cell, index) => (
                  <button
                    key={index}
                    disabled={busy || cell !== null}
                    onClick={() =>
                      run(
                        actions.find((a) => a.actionId === 'game.make_move') ?? { actionId: 'game.make_move' },
                        { position: index },
                        newIdempotencyKey(),
                      )
                    }
                    className="h-14 w-14 rounded bg-cocoa-900 text-lg text-cocoa-100 disabled:opacity-60"
                  >
                    {cell ?? ''}
                  </button>
                ))}
              </div>
            ) : (
              <pre className="card mt-3 overflow-x-auto text-xs text-cocoa-400">
                {resource ? JSON.stringify(resource.state, null, 2) : '还没有资源 —— 先执行一个写动作。'}
              </pre>
            )}

            {resource && (
              <p className="mt-2 text-xs text-cocoa-500">
                version {resource.version} · {resource.resourceType}
              </p>
            )}
          </section>
        )}
      </main>
    </div>
  )
}

/** 会话 id 是 target 里的那一段 —— 应用自己的 uriTemplate 决定了它的形状。 */
function resourceUriOf(sessionId: string): string {
  return `game://session/${sessionId}`
}

function boardOf(resource: ResourceView | null): (string | null)[] | null {
  const state = resource?.state as { board?: unknown } | null | undefined
  const board = state?.board
  if (!Array.isArray(board) || board.length !== 9) return null
  return board.map((cell) => (cell === null || cell === undefined ? null : String(cell)))
}
