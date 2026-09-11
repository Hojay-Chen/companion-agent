# companion-agent 架构说明（V10 多模块）

> 面向运维与二次开发。README 讲"做了什么"，本文讲"东西在哪、为什么这么切、怎么验证边界没被破坏"。

---

## 1. 三个系统，五个 Maven 模块

V10 的世界观是三个系统：

```
   ┌──────────────┐   消息/事件（外部世界）   ┌──────────────────┐
   │ Chat Platform│ ◄──────────────────────► │ Digital Human    │
   │  （软件）     │   DHCP v1 / WebSocket    │  （"人"）         │
   └──────────────┘                          └──────────────────┘
          ▲                                          ▲
          │            contracts（共同语言）           │
          └──────────────────────────────────────────┘
```

物理上落在 `backend/` 的五个模块：

| 模块 | 类型 | 拥有的顶层包（`com.luxera.companion.` 之下） | 说明 |
|---|---|---|---|
| `contracts` | library jar | `contracts` | 纯 DTO / enum / SPI 端口。**无 Spring、无 JPA** |
| `platform-kernel` | library jar | `auth` `common` `config` `outbox`（+ 根下 `HealthController` `ApplicationContextProvider`） | 两个平台都要用的最小内核 |
| `chat-platform` | library jar | `conversation` `event` `simulator` | 会话、消息、SSE、Simulator WS **服务端** |
| `digital-human-platform` | library jar | `agent` `appraisal` `attention` `behavior` `cognition` `cognitive` `digitalhuman` `emotion` `eval` `experience` `intention` `interaction` `life` `llm` `memory` `openloop` `person` `persona` `phone` `plan` `proactive` `reality` `reflection` `relationship` `runtime` `selfmodel` `sleep` `state` `thought` `tool` `usermodel` `world` | 数字人的全部认知 / 生活 / 记忆 / 状态 + WS **客户端** |
| `bootstrap-app` | **可执行 jar** | 无（只有启动类与测试） | 唯一同时依赖两个平台的模块；`spring-boot-maven-plugin:repackage` 只在这里开 |

四组包两两不相交 —— 这既是为了边界清晰，也是 Java 的硬性要求（split package 会让两个模块的同名包在
classpath 上静默合并）。`scripts/check-v10.sh` 第 1 步就是自动化检查这件事。

### 为什么 bootstrap-app 不直接叫 chat-platform

`bootstrap-app` 里没有任何业务代码，只有一个 `@SpringBootApplication`（`CompanionApplication`，
位于根包 `com.luxera.companion`，所以组件扫描 / 实体扫描 / Repository 扫描自动覆盖两个平台）
加上 `spring-boot-maven-plugin` 的 repackage。把它单列出来，是为了让"平台本身"和
"把平台拼起来跑"这两件事在构建层面就是分开的 —— 将来要拆两个进程，删掉 `bootstrap-app`、
各加一个 launcher 即可，两个平台模块一行不用改。

---

## 2. 边界靠什么保证（不是靠自觉）

依赖方向：

```
chat-platform            ──► contracts        （禁止 ──► digital-human-platform）
digital-human-platform   ──► contracts        （禁止 ──► chat-platform）
platform-kernel          ──► （无平台依赖）
bootstrap-app            ──► chat + DH + contracts   （只组装，不产生跨平台依赖）
```

三重守卫，缺一不可：

| 守卫 | 位置 | 性质 | 何时跑 |
|---|---|---|---|
| 包归属互斥 + 源码引用 + pom 依赖图 | `scripts/check-v10.sh` | grep（快速第一道） | 提交前 / CI，`mvn compile` 之前就能发现问题 |
| contracts 自足性（白名单） | `contracts/src/test/java/.../architecture/ArchitectureTest.java` | ArchUnit | `mvn test` |
| 两平台互不依赖 | `bootstrap-app/src/test/java/.../architecture/ModuleBoundaryArchitectureTest.java` | ArchUnit（看字节码） | `mvn test` |

**为什么 ArchUnit 规则要放在两个不同的模块**：

- `contracts` 的 classpath 上只有它自己 —— 所以那里能检查的是**结构性自足**：一旦有人往 contracts 的
  pom 里加了平台依赖，那些类立刻出现在 classpath 上，白名单规则失败。
- "chat 不得引用 DH、DH 不得引用 chat" 需要**同时看得见两个平台**，只有 `bootstrap-app` 做得到。

**规则里的包名必须写全限定前缀**（`com.luxera.companion.conversation..`，不能写 `..conversation..`）：
两个平台各自都有叫 `conversation` / `event` / `state` / `memory` 的包，通配写法会把 DH 自己的
`digitalhuman.conversation` 也算进去，规则立刻变成几百条假阳性，最后只能被删掉 ——
那是最坏的结果：一个看起来在守边界、其实什么都守不住的空壳。这一点在
`ModuleBoundaryArchitectureTest` 的类注释里也写了一遍，改之前请先读。

**顶层包之间的循环依赖在这个 codebase 里是既成事实**（拆分前就存在，与本次拆分无关），
所以 `ModuleBoundaryArchitectureTest` **不做**顶层包循环断言。模块层面的依赖方向由上面三条保证。

---

## 3. 跨平台通信：三个 SPI 端口

`contracts.spi` 是唯一的跨平台 Java 接口层。两边各写各的适配器，互相不认识对方的实现类。

| 端口 | 方向 | 实现方 | 位置 |
|---|---|---|---|
| `ChatWorldPort` | DH → Chat | 聊天平台 | `chat-platform` : `com.luxera.companion.conversation.ChatWorldAdapter` |
| `CompanionDirectoryPort` | Chat → DH | 数字人平台 | `digital-human-platform` : `com.luxera.companion.runtime.CompanionDirectoryAdapter` |
| `SimulatorAccessPort` | DH → Chat | 聊天平台 | `chat-platform` : `com.luxera.companion.simulator.server.SimulatorAccessAdapter` |

数据面另走 **DHCP v1**（`contracts.dhcp`：`DhcpFrame` + 11 种帧类型）经 `/ws/simulator`。
聊天平台在这条链路上只看见一台"机器用户设备"（`simulator_devices` 表），完全不知道 Companion 的存在。

`contracts.provision` 里的 `CompanionProvisioningPort` / `ChatProvisioningPort` 是伴侣创建时的
跨平台编排端口（DH 拥有 companion，chat 拥有 user account + simulator device）。

---

## 4. 测试怎么在"没有另一个平台"的情况下跑

这是解耦是否彻底的**试金石**：`digital-human-platform` 的 228 个测试在 classpath 上
**没有 chat-platform** 的情况下全部跑通。

- `digital-human-platform/src/test/java/com/luxera/companion/DigitalHumanTestApplication.java`
  —— 测试专用启动类，`@SpringBootApplication` + 两个假端口 Bean。
- `.../InMemoryChatWorld.java` —— 完整的内存版 `ChatWorldPort`（会话/消息/幂等索引），
  让"她写下的回复"能被读回来。`publishEvent` / `recordBoundary` / `touchThread` 是刻意的空实现，
  代码里有注释说明为什么（这些语义属于聊天平台，跨平台行为在 `bootstrap-app` 里对真实适配器测）。

跨平台的端到端行为在 `bootstrap-app` 的测试里验证 —— 那里两个平台都在。

---

## 5. 构建与部署

### 构建

```bash
cd backend
mvn clean test                       # 全模块 294 测试
mvn -DskipTests package              # 产出可执行 jar
```

> `mvn -pl chat-platform` 会因离线仓库缺少同级模块的已安装 jar 而失败，**必须带 `-am`**
> （`mvn -pl chat-platform -am ...`）。

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

当前是**单进程**：一个 Spring context 同时装两个平台，`bootstrap-app` 组装。
两个平台的代码边界已经切干净，改成两个进程不需要动 `chat-platform` / `digital-human-platform`
一行代码，只需：

1. 各加一个 launcher 模块（各自的 `@SpringBootApplication` + profile yml + repackage）；
2. 让 SPI 端口走 HTTP/WS 而不是本地 Bean（当前三个端口都已有本地适配器实现，远程适配器尚未写）；
3. `app.simulator.chat-ws-url` 指向 chat 进程的 `/ws/simulator`（当前默认
   `ws://127.0.0.1:8081/ws/simulator`）；数据面若要走 WS 而非进程内直调，
   另开 `app.simulator.backend=websocket`（默认 `inprocess`，由 `ChatSimulatorClient` 的
   `@ConditionalOnProperty` 控制）；
4. nginx：`/api/chat/*` 与 `/ws/simulator` → chat 进程；`/api/v10/*` → DH 进程；
   SSE 需要 `proxy_buffering off` + `proxy_http_version 1.1`。

单进程是当前的**部署选择**，不是架构约束。

---

## 6. 数据库

同一个 PG 实例，同一个 `companion` 库。单进程下两个平台共用一个 DataSource，
**表不重叠、跨模块无 FK** —— 这条靠的是"各平台的实体只映射自己的表"这一约定，
加新实体时请确认表名没有和另一个平台撞车（`ddl-auto=update` 不会替你报错）。
跨平台状态同步只有三种合法通道：

1. DHCP v1 WebSocket 协议（实时主路径）；
2. 共享 `outbox_event` 表（生产者 = chat，消费者 = DH 的 `OutboxRelayJob`；可靠兜底）；
3. `event_log` SSE 表（前端唯一事件源）。

需要 `vector` 扩展（`memory` 实体有 `vector` 列）：

```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

---

## 7. 加新代码之前

- **放哪个模块？** 看它是否依赖另一个平台。依赖了 → 说明边界破了，先想清楚是不是该走 SPI 端口。
- **新包放哪？** 加进任一模块都会自动被 `check-v10.sh` 纳入包归属检查；若与已有模块重名会立刻报错。
- **需要在 DH 里读会话/发消息？** 注入 `ChatWorldPort`，别去 import chat 的类（编译期也 import 不到）。
- **改完跑什么？** `mvn clean test` + `bash scripts/check-v10.sh` + 起服务 `bash scripts/check.sh`。
