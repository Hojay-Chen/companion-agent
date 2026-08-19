# V10 迭代记录 — 第一轮：三系统边界与因果链落地

> 对应方案：《Companion-Agent-V10-Detailed-Architecture-3.md》
> 目标：**让用户无法区分聊天对象是真人还是 agent**。
> 方式：不重建系统，在现有单体中逐轮建立 V10 三大边界，落实固定因果链。

---

## 本轮交付（2026-08-19）

### 1. Simulator Platform（V10 §4）— `simulator` 包

**Command Pattern**：所有客户端操作建模为 `CapabilityCommand` + `SimulatorCapability`：

| Capability | Scope | 说明 |
|---|---|---|
| `SendMessageCapability` | chat.send | 发送消息（clientMessageId 幂等） |
| `ReadMessagesCapability` | chat.read | 读取会话消息（数字人唯一的内容来源） |
| `ListConversationsCapability` | conversation.list | 会话列表 |
| `UpdateDeliveryStatusCapability` | delivery.update | 通知/已读/忽略等状态推进（逐条独立事务，最小锁面） |

**Facade/Adapter**：`SimulatorClient` 是数字人访问外部聊天世界的唯一入口——
session 校验 → scope 校验（禁止万能权限）→ 命令分发 → 执行。
`SimulatorSession`（DISCONNECTED→CONNECTING→CONNECTED + BACKGROUND/FOREGROUND/LOCKED）
由 `SimulatorSessionRegistry` 管理（双索引：accountId + sessionId；断线重连复用）。

**未来替换聊天平台**：只需实现新 Capability 集合，Digital Human 侧零改动。

### 2. 外部事件链（V10 §9）— `digitalhuman.event` 包

**Chain of Responsibility**：`ExternalEvent`（eventId 幂等 + correlationId 因果追踪）
→ `EventProcessingChain`（Validation → Deduplication → Route）：

- `ValidationHandler`：残缺事件拒绝（`REJECT:` 前缀 → REJECTED 状态）
- `DeduplicationHandler`：`processed_event` 表幂等短路（`DEDUP:` → DEDUPLICATED）
- `RoutingHandler`：经 `EventRouter`（Registry Pattern）路由到各 Runtime，成功后写幂等记录

**边界关键**：事件 payload 只携带 messageIds 引用，**不含消息内容**（MVP 验收 3/4）——
数字人通过 ReadMessagesCapability 自行"查看"。同源事件用确定性 eventId
（`ext-chat_message_delivered-{companionId}-{conversationId}-{lastMessageId}-{phase}`），
phase 区分 live（实时）/ catchup（醒来补处理），互不误杀。

### 3. Reality Ledger（V10 §8）— `digitalhuman.reality` 包

**Event Sourcing 心智**：`timeline_event` 表 append-only（实体 `@Immutable` 禁止 UPDATE），
`RealityLedger` 唯一写入口（同 eventId 幂等），支持按时间正序回放（MVP 验收 9）。
已接入事件：MESSAGE_SENT / MESSAGE_READ / MESSAGE_DEFERRED / MESSAGE_IGNORED。

原则：Memory 不能覆盖 Reality（MVP 验收 10）——记忆系统只能投影账本，不能改写账本。

### 4. Person Actor（V10 §20）— `digitalhuman.actor` 包

每 Person 一个 mailbox（BlockingQueue + 单消费者守护线程）：同 Person 严格串行、
不同 Person 并行。`PersonActorRegistry.tell(personId, task)` 统一入口。
（AgentRuntime 现有 ReentrantLock 保持，后续轮次迁移到 Actor。）

### 5. 输出质量闸门（V10 §15.3）— `digitalhuman.conversation` 包

`NarrationRuleValidator`：拦截括号舞台动作（（笑了笑））/ Markdown 动作（*smiles*）/
旁白前缀（她想了想）/ AI 腔（作为AI…）；`OutputValidationChain` 组合验证。
AgentRuntime 发送前必须过闸门，未通过则"没说出口"（像真人一样）。

### 6. AgentRuntime 边界收口

- 用户消息入口：`submit` → 构造 CHAT_MESSAGE_DELIVERED 外部事件 → 事件链 → 路由 → 认知
- 回复发送 / 批量已读 / 延迟 / 忽略：全部走 SimulatorClient（Capability）+ 写入 Reality Ledger
- 输出验证：发送前 OutputValidationChain

---

## 设计模式对照（V10 §24）

| 模式 | 落位 |
|---|---|
| Command | SimulatorCapability + CapabilityCommand |
| Chain of Responsibility | EventProcessingChain（Validation→Dedup→Route） |
| Facade / Adapter | SimulatorClient |
| Actor | PersonActor / PersonActorRegistry |
| Event Sourcing | RealityLedger / timeline_event（@Immutable） |
| Validator Chain | OutputValidationChain |
| Registry | EventRouter / SimulatorSessionRegistry / PersonActorRegistry |
| Builder（后续） | CognitiveContext（ContextBuilder 已有雏形） |

---

## 本轮测试

- `PersonActorTest`（3）：同 Person 串行 / 不同 Person 并行 / Registry 路由
- `NarrationRuleValidatorTest`（6）：旁白/AI 腔拦截 + 自然文本放行
- `SimulatorClientTest`（7）：session 生命周期 / scope 拒绝 / 收发闭环 / 断线重连
- `EventProcessingChainTest`（6）：校验拒绝 / 幂等短路 / 路由 / 确定性 eventId
- `RealityLedgerTest`（7）：append-only / 幂等 / 回放 / 因果链 / 类型过滤

---

## 本轮顺带修复（预先存在的问题，与 V10 无关）

1. `BehaviorEngineTest`：固定日期 `TEST_NOON`（2026-08-18）随时间漂移失败 →
   改为相对当前时间。
2. `LongConversationConsistencyTest`：`@Async afterExchange` 与断言的竞态 +
   "每轮必回复"的错误假设（Brain 决策依赖真实时钟 availability，DEFER 是真实行为）→
   断言改为"异步更新稳定后计数不漂移"。
3. `UpdateDeliveryStatusCapability` 逐条独立事务：避免批量更新扩大行锁冲突面
   （与 PendingMessageReevaluationJob 并发时的偶发死锁）。

---

## 后续轮次路线图

- **第二轮**：PerceptionStrategy（感知策略化）+ DecisionPolicy（Ignore/ReplyNow/ReplyLater/InspectDevice）
- **第三轮**：Outbox 事件发布（双写一致性）+ stateVersion 乐观锁（LLM 旧结果丢弃，MVP 验收 12）
- **第四轮**：Life Scheduler（活动/计划时间触发）+ Relationship Projection（从 Reality Ledger 投影）
- **第五轮**：Conversation Runtime 唯一文本生成入口 + Prompt 分层缓存（V10 §15/§19）
- **第六轮**：AgentRuntime 锁迁移到 PersonActor + 唤醒/行为 Tick 事件化
