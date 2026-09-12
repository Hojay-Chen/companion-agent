import BoardApp from './board'
import { registerEmbeddedApp } from '@/surfaces/registry'

/**
 * 内置应用的登记处 —— 全前端唯一一处"哪个应用长什么样"的硬编码。
 *
 * 它刻意被放在文件顶部一次执行完: 登记是**副作用**, 而 `SurfaceHost` 是同步渲染的,
 * 没有"等登记表加载好"这一步可等。漏了这个 import, 表现是内置应用全都退化成
 * "这个应用没有内置界面" —— 一个不崩、不报错、只是什么都不显示的状态。
 *
 * <h2>登记的是应用 id, 不是应用名</h2>
 * `com.luxera.tictactoe` 与 `com.luxera.gomoku` 的界面恰好是同一个组件, 因为它们恰好
 * 都是"一块方阵棋盘 + 一个落子动作"。这个巧合属于**今天这两个应用**, 不属于平台 ——
 * 所以它是两行登记, 而不是一条 `if (id.endsWith('game'))`。下一个棋类应用如果规则不同,
 * 它就该有自己的组件, 而这里会多出第三行。
 */
registerEmbeddedApp('com.luxera.tictactoe', BoardApp)
registerEmbeddedApp('com.luxera.gomoku', BoardApp)
