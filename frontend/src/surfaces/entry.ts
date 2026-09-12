import type { SurfaceType, UiView } from '@/api/lap'

/**
 * `entry` 是一个**模板**, 不是一条 URL。
 *
 * 方案 §68 说清了平台只定义三样东西(surface type / entry / 最低客户端版本), §69 说清了
 * 为什么 —— 一旦平台开始解释 entry 里的内容, 它就在变成 UI 渲染协议。
 *
 * 于是这里做的是**唯一的**一件事: 把两个变量填进去。没有表达式、没有条件、没有函数调用。
 * 平台唯一允许的变量就这两个, 因为平台唯一确定知道的就是这两件事 —— 这是哪个应用、
 * 这是哪一场会话。
 */
const VARIABLES = ['applicationId', 'sessionId'] as const

export type TemplateVariables = Partial<Record<(typeof VARIABLES)[number], string>>

/** 认得出的变量名 —— 供"这个模板用到了什么"这类诊断与测试使用。 */
export function variablesOf(template: string): string[] {
  const found = new Set<string>()
  for (const match of template.matchAll(/\{([^}]*)\}/g)) {
    found.add(match[1])
  }
  return [...found].sort()
}

/**
 * 填模板。**不认识的变量原样留着** —— 把它们替换成空串会让
 * `/applications/{applicationId}/x/{typo}` 变成一条看起来正常的路径, 于是错误在
 * "404 白屏"那里才暴露。留着它, 拼出来的路径本身就是一个显眼的证据。
 *
 * 缺值时同理: `sessionId` 还没拿到(比如应用详情页只有 applicationId)时,
 * `{sessionId}` 不会被悄悄抹掉。
 */
export function resolveEntry(template: string, values: TemplateVariables): string {
  return template.replace(/\{([^}]*)\}/g, (whole, name: string) => {
    const value = values[name as (typeof VARIABLES)[number]]
    return value === undefined || value === '' ? whole : value
  })
}

/**
 * 挑出要渲染的那一条 Surface。
 *
 * 找不到时就退到 `ui.entry` —— 作者声明了 `ui` 却漏了某个 Surface 时, 界面还能用一个
 * 说得过去的入口打开, 而不是白屏。**但这不是静默的**: 调用方拿得到 `requested` 与
 * `resolved` 的差别(见 {@link SurfacePlan} 的 `fallback`), 页面据此说一句
 * "这个应用没有为 MODAL 提供入口, 已按整页打开"。
 */
export interface SurfacePlan {
  requested: SurfaceType
  /** 实际拿到的 Surface 类型。与 `requested` 不同就说明退了一档。 */
  surface: SurfaceType
  template: string
  fallback: boolean
}

export function planSurface(ui: UiView, requested: SurfaceType): SurfacePlan {
  const declared = ui.surfaces?.find((surface) => surface.type === requested)
  if (declared) {
    return { requested, surface: requested, template: declared.entry, fallback: false }
  }

  // 退档时连**摆法**一起退 —— 把一份按整页设计的界面硬塞进弹窗, 得到的是一个
  // 尺寸不对、还可能自己带返回按钮的浮层。宁可整页打开并说明退过档。
  const fullPage = ui.surfaces?.find((surface) => surface.type === 'FULL_PAGE')
  return {
    requested,
    surface: fullPage ? 'FULL_PAGE' : requested,
    template: fullPage?.entry ?? ui.entry,
    fallback: true,
  }
}

/**
 * REMOTE 应用的 entry 必须是一条**绝对的 http(s) 地址** —— 它要被塞进 iframe 的 `src`,
 * 而一条相对路径会变成"在本站里找这个页面", 结果是一个 404 的 iframe。
 *
 * 后端 `ManifestValidator` 已经在发布时挡下了这种清单; 这里再判一次, 是因为前端拿到的
 * 详情可能来自任何一个后端版本 —— 而"发现一条坏数据时拒绝渲染并说清原因"比
 * "把它塞进 iframe 看它白屏"要便宜得多。
 */
export function isAbsoluteHttpUrl(entry: string): boolean {
  return /^https?:\/\//i.test(entry)
}
