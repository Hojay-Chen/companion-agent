# companion-agent 架构说明（V10 多模块）

> 面向运维与二次开发。README 讲"做了什么"，本文讲"东西在哪、为什么这么切、怎么验证边界没被破坏"。

---

## 1. 三个系统，六个 Maven 模块

V10 的世界观是三个系统：

```
   ┌──────────────┐   消息/事件（外部世界）   ┌──────────────────┐
   │ Chat Platform│ ◄──────────────────────► │ Digital Human    │
   │  （软件）     │   DHCP v1 / WebSocket    │  （"人"）         │
   └──────────────┘                          └──────────────────┘
          ▲                                          ▲
          │            contracts（共同语言）           │
          └──────────────────────────────────────────┘
                                                     ▲
                                      ApplicationRuntimePort / ApplicationEventSink
                                                     │
                                          ┌──────────────────────┐
                                          │ Application Platform │
                                          │ （应用；真人与 Agent  │
                                          │   共用同一套 Action） │
                                          └──────────────────────┘
```

数字人**只通过 `contracts` 里的两个端口**看见应用 —— 它不认识任何一个具体应用，也不认识承载它们的
模块。这条单向门是 LAP v1 的核心约束。

物理上落在 `backend/` 的六个模块：

| 模块 | 类型 | 拥有的顶层包（`com.luxera.companion.` 之下） | 说明 |
|---|---|---|---|
| `contracts` | library jar | `contracts` | 纯 DTO / enum / SPI 端口。**无 Spring、无 JPA** |
| `platform-kernel` | library jar | `auth` `common` `config` `outbox`（+ 根下 `HealthController` `ApplicationContextProvider`） | 各平台都要用的最小内核 |
| `chat-platform` | library jar | `conversation` `event` `simulator` | 会话、消息、SSE、Simulator WS **服务端** |
| `digital-human-platform` | library jar | `agent` `appraisal` `attention` `behavior` `cognition` `cognitive` `digitalhuman` `emotion` `eval` `experience` `intention` `interaction` `life` `llm` `memory` `openloop` `person` `persona` `phone` `plan` `proactive` `reality` `reflection` `relationship` `runtime` `selfmodel` `sleep` `state` `thought` `tool` `usermodel` `world` | 数字人的全部认知 / 生活 / 记忆 / 状态 + WS **客户端** |
| `application-platform` | library jar | `application` | 应用的宿主：manifest、能力/应用/动作发现、Resource 统一读模型、Action 网关、权限、安装/会话、内置参考应用、MCP 适配器 |
| `bootstrap-app` | **可执行 jar** | 无（只有启动类与测试） | 唯一同时看得见三方的模块；`spring-boot-maven-plugin:repackage` 只在这里开 |

六组包两两不相交 —— 这既是为了边界清晰，也是 Java 的硬性要求（split package 会让两个模块的同名包在
classpath 上静默合并）。`scripts/check-v10.sh` 第 1 步就是自动化检查这件事。

> **`contracts` 与 `application-platform` 的分界规则**：*contracts 放「平台的调用方」需要的东西，
> application-platform 放「应用的作者 / 宿主」需要的东西*。所以 `ActionSpec` / `ResourceView` /
> `PrincipalType` 这些要用来拼 LLM prompt 的类型在 contracts；`ApplicationManifest` /
> `ManifestParser` / `ManifestValidator` 这些解析与校验在 application-platform —— 数字人没有理由
> 去校验别人的 JSON Schema。

### 三个参考应用（一个迁移 + 两个新增）

`application-platform` 里内置了三个应用，它们**不是**"平台自带的功能"，而是三份用来证明平台成立的
样本。每个都只由三样东西组成：一份 `application-manifest.json`、一个 `LapApplicationModule` 实现
（注册 action handler）、以及它自己的存储（或干脆不存）。

| 应用 | 能力 | 性质 | 它证明什么 |
|---|---|---|---|
| `com.luxera.tictactoe` | `game.play` | 从 DH 迁过来的 | 一个应用可以不认识数字人；棋盘**就是**一条 Resource，不建表 |
| `com.luxera.gomoku` | `game.play` | 新增 | 第二个同域应用。它的 action id 与井字棋**一模一样**（`game.make_move` 等），只有 URI scheme 不同 —— 所以 handler 注册表的键必须是 `(applicationId, version, actionId)`，用 `Map<String, …>` 会让后注册的覆盖先注册的 |
| `com.luxera.reminder` | `reminder.manage` | 新增 | 跨能力域；一个把状态放在**自己表里**的应用（`backing: APP_OWNED` + `ResourceProjector`），证明平台允许"会话型资源"与"主体型资源"两种锚点 |

「加一个新应用 = 一份 manifest + 一个 handler 注册，DH 一行不改」这句话的验收方式，就是这三个应用：
五子棋落地时 `digital-human-platform` 的改动为零。

### 为什么 bootstrap-app 不直接叫 chat-platform

`bootstrap-app` 里没有任何业务代码，只有一个 `@SpringBootApplication`（`CompanionApplication`，
位于根包 `com.luxera.companion`，所以组件扫描 / 实体扫描 / Repository 扫描自动覆盖三个平台）
加上 `spring-boot-maven-plugin` 的 repackage。把它单列出来，是为了让"平台本身"和
"把平台拼起来跑"这两件事在构建层面就是分开的 —— 将来要拆两个进程，删掉 `bootstrap-app`、
各加一个 launcher 即可，三个平台模块一行不用改。

---

## 2. 边界靠什么保证（不是靠自觉）

依赖方向：

```
chat-platform            ──► contracts        （禁止 ──► digital-human-platform / application-platform）
digital-human-platform   ──► contracts        （禁止 ──► chat-platform / application-platform）
application-platform     ──► contracts        （禁止 ──► chat-platform / digital-human-platform）
platform-kernel          ──► （无平台依赖）
bootstrap-app            ──► chat + DH + application + contracts   （只组装，不产生跨平台依赖）
```

三个平台两两不相见，各自只认识 `contracts`。**应用事件进入数字人的唯一通道是 DH 侧实现
`ApplicationEventSink`** —— 单向门，反方向没有对应的端口。

三重守卫，缺一不可：

| 守卫 | 位置 | 性质 | 何时跑 |
|---|---|---|---|
| 包归属互斥 + 源码引用 + pom 依赖图 | `scripts/check-v10.sh` | grep（快速第一道） | 提交前 / CI，`mvn compile` 之前就能发现问题 |
| contracts 自足性（白名单） | `contracts/src/test/java/.../architecture/ArchitectureTest.java` | ArchUnit | `mvn test` |
| 三方互不依赖 | `bootstrap-app/src/test/java/.../architecture/ModuleBoundaryArchitectureTest.java` | ArchUnit（看字节码） | `mvn test` |

**为什么 ArchUnit 规则要放在两个不同的模块**：

- `contracts` 的 classpath 上只有它自己 —— 所以那里能检查的是**结构性自足**：一旦有人往 contracts 的
  pom 里加了平台依赖，那些类立刻出现在 classpath 上，白名单规则失败。
- "chat 不得引用 DH、DH 不得引用 application" 需要**同时看得见三方**，只有 `bootstrap-app` 做得到。

**规则里的包名必须写全限定前缀**（`com.luxera.companion.conversation..`，不能写 `..conversation..`）：
chat 与 DH 各自都有叫 `conversation` / `event` / `state` / `memory` 的包，通配写法会把 DH 自己的
`digitalhuman.conversation` 也算进去，规则立刻变成几百条假阳性，最后只能被删掉 ——
那是最坏的结果：一个看起来在守边界、其实什么都守不住的空壳。这一点在
`ModuleBoundaryArchitectureTest` 的类注释里也写了一遍，改之前请先读。

**顶层包之间的循环依赖在这个 codebase 里是既成事实**（拆分前就存在，与本次拆分无关），
所以 `ModuleBoundaryArchitectureTest` **不做**顶层包循环断言。模块层面的依赖方向由上面三条保证。

---

## 3. 跨平台通信：五个 SPI 端口

`contracts.spi` 是唯一的跨平台 Java 接口层。两边各写各的适配器，互相不认识对方的实现类。

| 端口 | 方向 | 实现方 | 位置 |
|---|---|---|---|
| `ChatWorldPort` | DH → Chat | 聊天平台 | `chat-platform` : `com.luxera.companion.conversation.ChatWorldAdapter` |
| `CompanionDirectoryPort` | Chat → DH | 数字人平台 | `digital-human-platform` : `com.luxera.companion.runtime.CompanionDirectoryAdapter` |
| `SimulatorAccessPort` | DH → Chat | 聊天平台 | `chat-platform` : `com.luxera.companion.simulator.server.SimulatorAccessAdapter` |
| `ApplicationRuntimePort` | DH → Application | 应用平台 | `application-platform` : `com.luxera.companion.application.action.ActionGateway` |
| `ApplicationEventSink` | Application → DH | 数字人平台（**单向门**） | `digital-human-platform` : `com.luxera.companion.digitalhuman.event.DhApplicationEventSink` |

> 前三个端口各家都写了一个 `*Adapter`；**`ApplicationRuntimePort` 没有** —— 它的实现类就是
> `ActionGateway` 本身。这不是漏了个名字，而是这个端口与其余三个不同：它不是"把本地 Bean 包一层
> 好让对面看不见"，而是平台*唯一*的动作入口（真人 REST、MCP 适配器、DH 进程内调用都从这里过）。
> 再包一个 `ApplicationRuntimeAdapter` 只会多一层什么都不做的委派。
> （曾有一份设计文档写成 `...runtime.ApplicationRuntimeAdapter`，那个类从来没有存在过。）

后两个端口是 LAP v1 的全部接触面。它们合起来只允许两件事：

- 数字人**读**统一读模型（`read(uri)` → `ResourceView`）、**问**现在能做什么
  （`pendingActions(uri, ctx)` → `List<ActionSpec>`）、**做**（`execute(ActionRequest, ctx)`）。
  数字人自己不解析任何应用状态、不判断任何业务规则 —— 棋盘长什么样、轮到谁，都由应用回答。
- 应用在事务提交之后**通知**数字人"有事发生了"。事件 id 由应用按 manifest 里的 `idTemplate`
  确定性铸造（如 `game://session/{id}#MOVE-0`），DH 侧的去重才有意义；否则重试会让 Agent
  对同一步行动两次。

DH 侧挂在 `digitalhuman.event.EventRouter` 的 `APPLICATION_EVENT` 上有**两个**消费者，看的是同一条
事件的两个侧面（`EventRouter.subscribe` 是追加语义，所以两者互不遮蔽）：

| 消费者 | 它问的问题 |
|---|---|
| `runtime.AgentApplicationFlow` | 「我该做点什么」—— 过滤 → 读 Resource → 问能做什么 → 执行并记入现实账本，全程跑在 `PersonActorRegistry.tell(personId, …)` 的 per-person 串行邮箱里；它同时也是**主动方向**的入口（`route()`，见「Agent 的 LLM 契约」）|
| `digitalhuman.event.ApplicationNotificationBridge` | 「该不该说给他听」—— 事件载荷里带 `notify` 块就落一条 `companion_notifications`，`type` 原样透传 |

第二条路是 R5 加的，它让「提醒到点」不再需要一个 DH 侧的扫描器：`ReminderDispatchJob` 在应用侧
到点发事件，是否变成用户看得见的通知由 DH 决定。这个类里没有一个字提到提醒 —— 它认识的只是
`notify` 这个**形状**。

> **LLM 优先、绝不降级到启发式**：LLM 不可用（或处于 mock）时 `AgentApplicationFlow` 直接返回，
> 不落子、不随机、不"取第一个空格"。`AgentApplicationFlowTest` 断言此时 `execute()` 调用次数为 0 ——
> 这条断言是这条性质在整个重构过程中的保险丝。

### LAP v1 的协议面（`/api/v1`，R4 起）

真人和 Agent 走的是**同一条**路，协议里不存在"Agent 专用接口"：

```
GET  /api/v1/capabilities                        能力目录（发现链第 1 级）
GET  /api/v1/capabilities/{capabilityId}/applications   候选应用（第 2 级）
GET  /api/v1/applications/{applicationId}/actions       动作 + agentHint（第 3 级）
POST /api/v1/applications/{id}/install           安装（顺带开一个会话）
POST /api/v1/sessions   /  GET /api/v1/sessions   /  DELETE /api/v1/sessions/{id}
POST /api/v1/subscriptions                       会话之内的事件订阅
GET  /api/v1/resources?uri=                      统一读模型（只读，无幂等键）
POST /api/v1/actions:execute                     唯一的动作入口（Canonical；/actions/execute 是别名）
```

动作请求是刻意做小的：`{"action","target","input"}` + `Idempotency-Key` 头；
响应是 `{"status","result","resource","events","error"}`（顶层 `status`/`error` 是对设计稿的修正 ——
`{"result","resource","events"}` 这个形状里 `DENIED` 和"成功但无事可报"分不开）。

> **发现链就是"不要把 50000 个 action 塞给 LLM"的全部实现。** 每一级都比上一级窄一个数量级：
> 能力有几十个、某能力下的应用有个位数、某应用的动作有个位数。Agent 永远只看到当前这一步该看的那一层，
> 而"怎么做"（策略文本）是应用作者写在 `actions[].agentHint` 里的 —— 不在 DH 里。

### Agent 的 LLM 契约：能力 → 应用 → 动作（R7 起）

`AgentApplicationFlow` 有两个方向，契约不同：

| 方向 | 触发 | LLM 任务 | 它能看到的东西 |
|---|---|---|---|
| 反应 | 应用发来一条 `APPLICATION_EVENT` 且 `agentTrigger=true` | `application-action-selection` | 该资源**此刻**的 pending 动作（每条带 `agentHint` + `inputSchema`）与资源自身的 `agentHint` |
| 主动 | 用户说了一句话 → `route()` | `application-capability-selection` →（候选多于一个时）`application-selection` | 平台的**能力目录**（几十行）；选完能力才收窄到候选应用 |

**反应方向刻意不做能力选择** —— 事件已经点名了 resource，再问一遍"这该用哪个应用"是多余的，
每次都多花一次 LLM 调用，答案还永远是"就是它"。能力选择是**用户说了一句话**时的前门。

**模型可以答错，但不能答不存在的东西。** 四个幻觉出口一律拦下：能力不在目录里、应用不在候选里、
动作不在候选里、含糊不点名（候选不止一个却没说选哪个）。最后一条最要紧 ——
替模型补一个就是启发式，而"绝不降级到启发式"是明确要求的性质。反过来，候选**只有一个**时不问
第二次 LLM：一个候选的"选择"只是在花钱让模型复述输入。

**门槛**：`capability != null && confidence >= app.lap.capability-threshold`（默认 0.6）。
这个阈值就是设计稿那句「大多数日常聊天不需要任何应用」的可测试版本 —— 做成配置项，因为调低会让
闲聊变成应用调用，调高会让明确的请求被漏掉。能力目录的指纹（按 id 排序后 SHA-256）进 metadata，
事后翻 `llm_calls` 能知道模型当时看的是哪一版目录。

**「具体打法」归应用，通用行为准则归数字人。** 「会赢就赢、不要解释算法」在 `AgentApplicationFlow`
的提示词里（对任何应用都成立）；「能三连就三连、否则阻断对手」在 `tictactoe` 的
`actions[game.make_move].agentHint` 里。这条分界由 `DhApplicationKnowledgeArchitectureTest` 钉住：
DH 的源码里不许出现任何一个具体应用的名字、动作 id 或状态字段。

`scripts/check-lap.sh` 是这一面唯一的端到端守卫：`check.sh` 覆盖的是聊天/数字人链路，
对应用平台**零覆盖** —— 这就是为什么"测试全绿"在这里什么也保护不了。

### MCP 适配器（`POST /mcp`，R6 起）

MCP 是**适配器，不是第二个平台**。它只做两件翻译，两件都通向已经存在的那条路：

| MCP 方法 | 通向哪里 |
|---|---|
| `initialize` / `notifications/*` / `ping` | 什么都不通 —— 纯协议状态，不碰数据库 |
| `tools/list` | 动作发现（能力 → 应用 → 动作这条链，可由 `capabilityId` / `applicationId` 收窄） |
| `tools/call` | `ActionGateway.execute()` —— 与真人 `POST /api/v1/actions:execute` **同一个**入口 |

协议版本 `2025-06-18`，JSON-RPC 2.0。`DELETE /mcp` 关掉一个协议会话。

> **MCP Session ≠ ApplicationSession。** MCP 的协议状态（协商版本、客户端信息）只活在适配器
> 内存里（`McpSessions`），`application_session` 一行都不会因为它而增减 —— 归属链是平台的概念，
> 不是某个传输的概念。这条不变量两边都有测试钉住：进程内的 `McpProtocolTest` 断言整条往返前后
> `application_session` 计数不变，`check-lap.sh` 断言 14 在真实服务上再断言一遍。
> 少了后半句，"没有创建会话"也可能只是因为那条链路根本没执行 —— 所以它同时断言棋盘真的变了。

**工具名**是 `<应用短名>.<动作 id 里的点换成下划线>`（`tictactoe.game_make_move`）。井字棋与五子棋
的**动作 id 完全一样**，靠应用短名分开；若两个应用的短名还撞车，整个目录一起退化成全名
（`com_luxera_a_b.game_make_move`）—— 只给其中一个改的话，工具名会变成"取决于另一个应用存不存在"的
东西。描述文字（`description`）是应用的 `agentHint` 与资源模板的投影，LLM 对应用的了解**只有**这一段。

> **`/mcp` 在 `SecurityConfig` 里是 `permitAll`，这不是漏洞。** MCP 客户端没有 JWT —— 它是外部
> Agent，手里只有 `X-Mcp-Principal` + `X-Mcp-Service-Key`，由 `McpPrincipalResolver` 自己验。
> JWT 那一层**表达不了** MCP 的身份，所以留在 `anyRequest().authenticated()` 后面的结果是每个 MCP
> 请求都在过滤器上变成 Spring 默认的 403 错误体,连 `initialize` 都到不了控制器。
> 真正的门在控制器第一步：服务密钥（`app.lap.mcp.service-key`，生产上由 `LAP_MCP_SERVICE_KEY` 给）
> **留空即 MCP 完全关闭**，每个请求都被拒。
>
> 这个坑是 `check-lap.sh` 断言 14 抓到的 —— 进程内测试抓不到它：`application-platform` 的测试应用
> 没有 `SecurityConfig`（它在 `platform-kernel`），过滤器链压根不在场。补的守卫是
> `bootstrap-app` 的 `McpEndpointSecurityTest`（唯一同时看得见 `SecurityConfig` 与 `McpController`
> 的地方），断言"带正确密钥的握手必须成功"且"无凭据时回的是 JSON-RPC 信封而不是 Spring 错误体"。

> **已知产品缺口（不是测试的将就）**：AGENT 身份目前**没有** HTTP 安装入口 —— `/install` 从 JWT 解析
> principal（那是真人的路），而 MCP 面明确拒绝 `HUMAN` 声明。所以 `check-lap.sh` 断言 14 里的
> AGENT 安装行是用 SQL 造的，`McpProtocolTest` 也直接调 `InstallationService`。将来要支持
> "第三方 MCP Agent 自己装应用"，得有一条属于 AGENT 的安装路径。

数据面另走 **DHCP v1**（`contracts.dhcp`：`DhcpFrame` + 11 种帧类型）经 `/ws/simulator`。
聊天平台在这条链路上只看见一台"机器用户设备"（`simulator_devices` 表），完全不知道 Companion 的存在。

`contracts.provision` 里的 `CompanionProvisioningPort` / `ChatProvisioningPort` 是伴侣创建时的
跨平台编排端口（DH 拥有 companion，chat 拥有 user account + simulator device）。

---

## 4. 测试怎么在"没有另一个平台"的情况下跑

这是解耦是否彻底的**试金石**：`digital-human-platform` 的 275 个测试在 classpath 上
**既没有 chat-platform、也没有 application-platform** 的情况下全部跑通。

- `digital-human-platform/src/test/java/com/luxera/companion/DigitalHumanTestApplication.java`
  —— 测试专用启动类，`@SpringBootApplication` + 三个假端口 Bean。
- `.../InMemoryChatWorld.java` —— 完整的内存版 `ChatWorldPort`（会话/消息/幂等索引），
  让"她写下的回复"能被读回来。`publishEvent` / `recordBoundary` / `touchThread` 是刻意的空实现，
  代码里有注释说明为什么（这些语义属于聊天平台，跨平台行为在 `bootstrap-app` 里对真实适配器测）。
- `.../InMemoryGameApplication.java` —— 一个**真能玩的小应用**（3×3 棋盘、`game.create` /
  `game.state` / `game.make_move`），实现 `ApplicationRuntimePort` 且只用 contracts 的类型。
  刻意**不做**成返回 `Optional.empty()` 的 mock：空 mock 会让 `AgentApplicationFlow` 悄悄腐烂
  而测试全绿 —— 参考应用在 DH 侧必须真的可下，这条测试才有意义。
- `.../architecture/DhApplicationKnowledgeArchitectureTest.java` —— 反过来钉住"DH 里**不许**有
  具体应用的知识"（R7 加）。它刻意**不是** ArchUnit：要禁的是字符串与变量名（`game.make_move`、
  `board`、`井字棋`），而 ArchUnit 看的是依赖与类名，两者都看不见 —— 那样写会得到一条
  **通过但什么都没检查**的规则。提醒应用的身份与词汇只允许出现在 `tool/ReminderService.java`
  （DH 侧通往应用的那唯一一道门），并额外断言白名单**不是空壳**。

跨平台的端到端行为在 `bootstrap-app` 的测试里验证 —— 那里三方都在：`LapEndToEndTest`
走的是「真人落子 → 应用发事件 → DH 翻译并路由 → Agent 决策 → 经端口落子」这条完整链路，
链路上没有一处认识井字棋。任何一侧单独在场，都凑不出这条往返。

---

## 5. 构建与部署

### 构建

```bash
cd backend
mvn clean test                       # 全模块 601 测试
mvn -DskipTests package              # 产出可执行 jar
```

> `mvn -pl chat-platform` 只从**本地仓库**取同级模块的 jar，**必须带 `-am`**
> （`mvn -pl chat-platform -am ...`）。
>
> 踩过的坑：本地仓库里那份 `companion-platform-contracts` 是旧的（新增的 `contracts.application.*`
> 与 `contracts.spi.ApplicationEventSink` 不在里面）时，`-pl application-platform` 编译照样能过
> （class 文件是上次 reactor 构建留下的），但 surefire 在挑选测试类时会**静默吞掉**
> `NoClassDefFoundError`，于是签名里碰到新类型的测试类一个都不跑，只报剩下的那几个 —— 看起来全绿，
> 实际少跑了一半。**判断依据是每个模块的 `Tests run` 总数，不是 BUILD SUCCESS。**
> 修法是 `mvn -o install -DskipTests` 把本地仓库刷新一遍。

### 产物

```
backend/bootstrap-app/target/companion-platform-bootstrap-1.0.0.jar   ← 唯一可执行 jar（约 52 MB）
```

### 启动

```bash
cd backend && ./run.sh                 # 打包 + 起在 8081
# 等价于:
java -jar backend/bootstrap-app/target/companion-platform-bootstrap-1.0.0.jar
```

### systemd（生产）

`scripts/deploy.sh` 安装的单元使用上述 jar，工作目录 `backend/`，
**必须带 `EnvironmentFile=/etc/companion/.env`**（`DEEPSEEK_API_KEY`；缺了会静默降级成 Mock LLM，踩过）。

### 当前拓扑 vs 分进程拓扑

当前是**单进程**：一个 Spring context 同时装三个平台，`bootstrap-app` 组装。
三个平台的代码边界已经切干净，改成两个（或三个）进程不需要动 `chat-platform` /
`digital-human-platform` / `application-platform` 一行代码，只需：

1. 各加一个 launcher 模块（各自的 `@SpringBootApplication` + profile yml + repackage）；
2. 让 SPI 端口走 HTTP/WS 而不是本地 Bean（当前五个端口都已有本地适配器实现，远程适配器尚未写）；
3. `app.simulator.chat-ws-url` 指向 chat 进程的 `/ws/simulator`（当前默认
   `ws://127.0.0.1:8081/ws/simulator`）；数据面若要走 WS 而非进程内直调，
   另开 `app.simulator.backend=websocket`（默认 `inprocess`，由 `ChatSimulatorClient` 的
   `@ConditionalOnProperty` 控制）；
4. nginx：`/api/chat/*` 与 `/ws/simulator` → chat 进程；`/api/v10/*` → DH 进程；
   SSE 需要 `proxy_buffering off` + `proxy_http_version 1.1`。

单进程是当前的**部署选择**，不是架构约束。

---

## 6. 数据库

同一个 PG 实例，同一个 `companion` 库。单进程下三个平台共用一个 DataSource，
**表不重叠、跨模块无 FK** —— 这条靠的是"各平台的实体只映射自己的表"这一约定，
加新实体时请确认表名没有和另一个平台撞车（`ddl-auto=update` 不会替你报错）。
跨平台状态同步只有三种合法通道：

1. DHCP v1 WebSocket 协议（实时主路径）；
2. 共享 `outbox_event` 表（生产者 = chat，消费者 = DH 的 `OutboxRelayJob`；可靠兜底）；
3. `event_log` SSE 表（前端唯一事件源）。

应用平台的表（R4 起全部生效；`dh_` 前缀的是过渡期遗留，R8 由 `scripts/lap-drop-legacy.sh` 删除）：

| 表 | 说明 |
|---|---|
| `developer` | 应用作者；R8 的 Developer API 挂在它上面 |
| `application` | 稳定 id（如 `com.luxera.tictactoe`）+ 10 态生命周期状态 |
| `application_version` | **manifest 挂在版本上**（不是挂在 application 上），`UQ(application_id, version)`，发布后不可变 |
| `capability` / `application_capability` | 能力目录（`game.play` / `reminder.manage`）与版本-能力绑定，从 `capabilities.json` 播种 |
| `installation` | Principal × Application，`UQ(application_id, principal_type, principal_id)`；安装**钉住版本** |
| `permission_grant` | 安装之下的第二维：能力级或动作级授权 + 风险上限 + 过期 |
| `application_session` | 平台级会话；应用自己的业务对象挂在它下面 |
| `resource` | **统一读模型**：`uri` 唯一 + `state_json` + `state_version`（CAS 用） |
| `subscription` | 会话之内的事件订阅（`SINK` 模式；`INBOX` 要等 R8 的 outbox） |
| `action_invocation` | 幂等账本，`UQ(principal_type, principal_id, idempotency_key)` + `request_hash` + `started_at` |
| `application_action_log` | 审计（权限判定 + 执行结果分开记 —— 旧表把两者塞进同一个字段，是废字段） |
| `reminder_item` | **应用自有**（`backing: APP_OWNED`）：提醒的真相在这里，读的时候由 `ReminderResourceProjector` 投影成 `reminder://owner/{ownerId}` 的 `ResourceView`。字段名与旧表不同（`note` / `dueAt`），但 DH 的 REST 面把它们翻译回 `content` / `remindAt` |
| `dh_application` / `dh_game_session` / `dh_application_action_log` | **遗留**：`/api/v10` 应用面自 R4 起已删（实测 404），这三张表从此没有写入方；R8 由 `lap-drop-legacy.sh` 统一 DROP |
| `reminders` | **遗留**：数字人的提醒表。R5 起**也没有写入方**了 —— 提醒（含生日提醒）的创建/完成/取消全部经 `reminder.create` / `reminder.complete` / `reminder.cancel` 落到 `reminder_item`；DH 侧只剩 `ReminderService` 通过 `ApplicationRuntimePort` 的读与调用。R8 一并 DROP |

> **棋局状态最终不建表**：棋盘**就是**一条 Resource（`game://session/{id}` 的 `state_json`），
> 在 action 的同事务内经 `ResourceStore` 写入。这是「Resource 是统一读模型」最直接的证明。

需要 `vector` 扩展（`memory` 实体有 `vector` 列）：

```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

---

## 7. 加新代码之前

- **放哪个模块？** 看它是否依赖另一个平台。依赖了 → 说明边界破了，先想清楚是不是该走 SPI 端口。
- **新包放哪？** 加进任一模块都会自动被 `check-v10.sh` 纳入包归属检查；若与已有模块重名会立刻报错。
- **需要在 DH 里读会话/发消息？** 注入 `ChatWorldPort`，别去 import chat 的类（编译期也 import 不到）。
- **需要在 DH 里操作应用？** 注入 `ApplicationRuntimePort`，别去 import application 的类
  （同样编译期就 import 不到）。要读应用状态就用 `read(uri)` 拿 `ResourceView`，
  **不要在 DH 里解析应用的 JSON** —— 一旦 DH 认识某个应用的字段名，"加第二个应用不改 DH"
  这句话就不成立了。
- **改完跑什么？** `mvn clean test`（看每个模块的 `Tests run` 总数，不是只看 BUILD SUCCESS）
  + `bash scripts/check-v10.sh` + 起服务 `bash scripts/check.sh`。
