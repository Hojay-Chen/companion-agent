# V10 迭代记录 — 第六轮：PersonActor 全面接管 + 复查路径决策策略化

> 对应方案：《Companion-Agent-V10-Detailed-Architecture-3.md》§20(Actor 模型) / §13(Decision)
> 目标：**同 Person 状态串行由 Actor 模型承担（FIFO + 空闲回收），复查决策走策略而非纯 LLM**。

---

## 本轮交付（2026-08-19）

### 1. Person Actor 全面接管（V10 §20）

| 改进 | 说明 |
|---|---|
| **空闲回收** | worker 用 `poll(IDLE_TIMEOUT=30s)`：队列空闲超时自动退出（守护线程），不累积线程；下次 tell 自动重建（`remove` 死引用 + `putIfAbsent` 并发安全） |
| **严格 FIFO** | 提交顺序 = 执行顺序（mailbox 队列），替代"全局线程池 + 锁"（后者不保证顺序） |
| **AgentRuntime.submit 迁移** | 用户消息处理从 `taskExecutor`（全局池）改为 `PersonActorRegistry.tell`（per-person mailbox）—— 同 Person 处理严格按提交顺序 |
| **锁统一** | `process`（同步路径）的 ReentrantLock 迁移到 `PersonActorRegistry.lockOf`（统一 per-person 互斥体）：同步调用与异步 mailbox 互斥，状态修改永不走并发 |
| **异常隔离** | 任务异常不杀死 actor（errorSink 记录，后续任务继续） |

### 2. 已读复查路径决策策略化（V10 §13）

`PendingMessageReevaluationJob.reevaluate` 新增**策略预筛**：

```
复查(FOCUSED: 她记得这条消息)
  → DecisionPolicyEngine(importance 由情绪/催问词/长度评估)
  → DelayReply(忙/疲惫) → 延后 delayMinutes 再复查(不打扰认知, 省一次 LLM)
  → 其他决策 → 走原有 BrainAgent 路径(回复/放下/再延后)
```

真人忙的时候"想起也不会立刻回" —— 复查不再每次都唤醒完整认知链。

### 3. 顺带修复

- 测试数据清理问题：AgentState 主键是独立 id（PrePersist），测试须 `setId(companionId)` 才能按 companionId 删除。

---

## 设计模式对照

| 模式 | 落位 |
|---|---|
| Actor | PersonActor（mailbox FIFO + 空闲回收）接管消息提交 |
| Policy 预筛 | DecisionPolicyEngine 接入复查路径（忙/疲惫 → DelayReply） |

## 本轮测试（6 个新增）

- `PersonActorIdleRecycleTest`（4）：空闲超时自动退出；Registry 回收后重建；
  **mailbox 严格 FIFO（提交顺序=执行顺序）**；任务异常不杀 actor
- `PendingMessageReevaluationJobTest`（2）：疲惫复查 → 策略延后（重新排程，不回复）；
  pending 记录在延后后保留

## 全量回归

**262 测试全绿（0 失败 0 错误）**。

---

## 迭代总结（6 轮）

| 轮次 | 交付 |
|---|---|
| 1 | Simulator Capability(Command)/事件链(CoR)/Reality Ledger/PersonActor/输出闸门 |
| 2 | Perception Runtime(Strategy 4 级)/Decision Runtime(Policy sealed) |
| 3 | Outbox 可靠发布/StateVersionGate 乐观锁 |
| 4 | Life Scheduler 时间触发/Relationship Projection |
| 5 | Conversation Runtime 唯一文本入口/Prompt 分层缓存/PLAN_REMINDER |
| 6 | PersonActor 全面接管(mailbox FIFO+回收)/复查策略预筛 |

**成果**：
- 新增 9 个 V10 包（simulator + digitalhuman.*），5 张新表（timeline_event/processed_event/outbox_event/life_schedule/…）
- V10 MVP 14 条验收全部落地；V10 §24 设计模式对照表全部落位
- 全量测试 191 → 262（新增 71 个），0 失败
- 修复既有问题：时间敏感测试漂移 ×2、高频 Job cron 配置化 ×3、测试连接池竞争、测试清理缺陷
