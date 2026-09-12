import type { ComponentType } from 'react'
import type { SurfaceType } from '@/api/lap'

/**
 * 内置应用登记表 —— `ui.type = EMBEDDED` 的那些应用, 界面由**平台自己**实现。
 *
 * 这张表是「应用市场」与「应用仓库」的分界线, 也是这条边界上唯一的一处硬编码:
 * 平台认得的是<b>应用的 id</b>, 而不是它有哪些 Surface。
 *
 * <h2>为什么按 applicationId 登记, 而不是按 surface 登记</h2>
 * 一个应用只有<b>一份</b>界面实现; 五种 Surface 是这同一份界面的五种<b>摆法</b>。
 * 如果登记表按 surface 分键, 作者就要为 MODAL 再写一遍棋盘 —— 而两份棋盘很快就会不一样,
 * 于是"同一个会话在弹窗里和整页里看到的东西不同"。所以: 组件拿到的只是容器尺寸的变化,
 * 不是"你现在是弹窗版"。
 */

/** 内置应用组件拿到的 props —— 刻意很小。平台不替应用管状态, 只把身份递进去。 */
export interface EmbeddedAppProps {
  applicationId: string
  sessionId: string
  /** 当前这一份界面被摆在哪种 Surface 上。应用可据此调整密度, 但**不该**据此改变行为。 */
  surface: SurfaceType
}

const registry = new Map<string, ComponentType<EmbeddedAppProps>>()

/** 登记一个内置应用。重复登记同一 id 会覆盖 —— 热更新时这是想要的语义。 */
export function registerEmbeddedApp(
  applicationId: string,
  component: ComponentType<EmbeddedAppProps>,
): void {
  registry.set(applicationId, component)
}

export function embeddedAppOf(applicationId: string): ComponentType<EmbeddedAppProps> | undefined {
  return registry.get(applicationId)
}

export function isEmbedded(applicationId: string): boolean {
  return registry.has(applicationId)
}

/** 已登记的内置应用 id —— 只给"平台认得哪些应用"这类诊断用。 */
export function registeredApplications(): string[] {
  return [...registry.keys()].sort()
}

/**
 * 客户端的 LAP UI 版本。manifest 里的 `minClientVersion` 拿它比。
 *
 * 它不是应用版本、也不是协议版本, 而是**这份界面能理解的最低清单**。比不过时的处置是
 * 拒绝渲染并说清原因 —— 硬着头皮渲染一个自己看不懂的 entry 模板, 表现是"点进去白屏",
 * 而白屏什么信息都没给人留下。
 */
export const CLIENT_VERSION = '1.0.0'

/** 点分十进制比较。`1.10.0 > 1.9.0` —— 字符串比较会在这里答错。 */
export function compareVersions(a: string, b: string): number {
  const left = a.split('.').map((part) => Number.parseInt(part, 10) || 0)
  const right = b.split('.').map((part) => Number.parseInt(part, 10) || 0)
  for (let i = 0; i < Math.max(left.length, right.length); i += 1) {
    const diff = (left[i] ?? 0) - (right[i] ?? 0)
    if (diff !== 0) return diff > 0 ? 1 : -1
  }
  return 0
}

/** 这份 manifest 要求的客户端版本, 本客户端够不够。没写就是没有要求。 */
export function clientSupports(minClientVersion?: string | null): boolean {
  if (!minClientVersion) return true
  return compareVersions(CLIENT_VERSION, minClientVersion) >= 0
}
