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

五组包两两不相交 —— 这既是为了边界清晰，也是 Java 的硬性要求（split package 会让两个模块的同名包在
classpath 上静默合并）。`scripts/check-v10.sh` 第 1 步就是自动化检查这件事。

> **`contracts` 与 `application-platform` 的分界规则**：*contracts 放「平台的调用方」需要的东西，
> application-platform 放「应用的作者 / 宿主」需要的东西*。所以 `ActionSpec` / `ResourceView` /
> `PrincipalType` 这些要用来拼 LLM prompt 的类型在 contracts；`ApplicationManifest` /
> `ManifestParser` / `ManifestValidator` 这些解析与校验在 application-platform —— 数字人没有理由
> 去校验别人的 JSON Schema。

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
| `ApplicationRuntimePort` | DH → Application | 应用平台 | `application-platform` : `com.luxera.companion.application.runtime.ApplicationRuntimeAdapter` |
| `ApplicationEventSink` | Application → DH | 数字人平台（**单向门**） | `digital-human-platform` : `com.luxera.companion.digitalhuman.event.DhApplicationEventSink` |

后两个端口是 LAP v1 的全部接触面。它们合起来只允许两件事：

- 数字人**读**统一读模型（`read(uri)` → `ResourceView`）、**问**现在能做什么
  （`pendingActions(uri, ctx)` → `List<ActionSpec>`）、**做**（`execute(ActionRequest, ctx)`）。
  数字人自己不解析任何应用状态、不判断任何业务规则 —— 棋盘长什么样、轮到谁，都由应用回答。
- 应用在事务提交之后**通知**数字人"有事发生了"。事件 id 由应用按 manifest 里的 `idTemplate`
  确定性铸造（如 `game://session/{id}#MOVE-0`），DH 侧的去重才有意义；否则重试会让 Agent
  对同一步行动两次。

DH 侧只有一个消费者：`digitalhuman.event.EventRouter` 的 `APPLICATION_EVENT` 路由 →
`runtime.AgentApplicationFlow`，四步 = 过滤 → 读 Resource → 问能做什么 → 执行并记入现实账本，
全程跑在 `PersonActorRegistry.tell(personId, …)` 的 per-person 串行邮箱里。

> **LLM 优先、绝不降级到启发式**：LLM 不可用（或处于 mock）时 `AgentApplicationFlow` 直接返回，
> 不落子、不随机、不"取第一个空格"。`AgentApplicationFlowTest` 断言此时 `execute()` 调用次数为 0 ——
> 这条断言是这条性质在整个重构过程中的保险丝。

数据面另走 **DHCP v1**（`contracts.dhcp`：`DhcpFrame` + 11 种帧类型）经 `/ws/simulator`。
聊天平台在这条链路上只看见一台"机器用户设备"（`simulator_devices` 表），完全不知道 Companion 的存在。

`contracts.provision` 里的 `CompanionProvisioningPort` / `ChatProvisioningPort` 是伴侣创建时的
跨平台编排端口（DH 拥有 companion，chat 拥有 user account + simulator device）。

---

## 4. 测试怎么在"没有另一个平台"的情况下跑

这是解耦是否彻底的**试金石**：`digital-human-platform` 的 229 个测试在 classpath 上
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

跨平台的端到端行为在 `bootstrap-app` 的测试里验证 —— 那里三方都在：`LapEndToEndTest`
走的是「真人落子 → 应用发事件 → DH 翻译并路由 → Agent 决策 → 经端口落子」这条完整链路，
链路上没有一处认识井字棋。任何一侧单独在场，都凑不出这条往返。

---

## 5. 构建与部署

### 构建

```bash
cd backend
mvn clean test                       # 全模块 329 测试
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

应用平台当前的表（`dh_` 前缀的是过渡期遗留，R8 由 `scripts/lap-drop-legacy.sh` 删除）：

| 表 | 归属 | 说明 |
|---|---|---|
| `application` / `application_version` | 应用平台 | 稳定 id + 版本；**manifest 挂在版本上**，发布后不可变 |
| `dh_application` / `dh_game_session` / `dh_application_action_log` / `reminders` | 遗留 | 迁移中，R3 仍由 `/api/v10` 与提醒读路径使用 |

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
