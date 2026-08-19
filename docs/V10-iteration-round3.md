# V10 迭代记录 — 第三轮：Outbox 可靠发布 + stateVersion 乐观锁

> 对应方案：《Companion-Agent-V10-Detailed-Architecture-3.md》§21(一致性)
> 目标：**双写一致性(Outbox) + LLM 旧结果不能覆盖新状态(MVP 验收 12)**。

---

## 本轮交付（2026-08-19）

### 1. Outbox 可靠发布（V10 §21.3）— `digitalhuman.outbox` 包

**模式**：业务状态与事件发布同事务提交，后台异步投递 —— 禁止"先更新数据库再发布事件"。

| 组件 | 职责 |
|---|---|
| `OutboxEvent`（outbox_event 表） | event_key 唯一(幂等入队)；PENDING→PUBLISHED；失败退避重试(10s×2ⁿ)，超 5 次 FAILED |
| `OutboxPublisher.enqueue` | 业务事务内入队(同事务提交)，同 key 不重复 |
| `OutboxRelayJob` | 每 5 秒轮询(配置化 cron) → 重建 ExternalEvent(确定性 eventId) → 事件链投递；COMPLETED/DEDUPLICATED→PUBLISHED，其余→失败重试 |

**接入**：`MessageCoreService.send` —— 用户消息落库**同一事务**内入队 Outbox 兜底；
即时路径(afterCommit → AgentRuntime)保留(低延迟)，进程崩溃时由 Relay 补发。
**幂等闭环**：Outbox 重建的 eventId 与 live 路径 `submitWithPhase` 的确定性 eventId
完全一致(同 dedupKey) → processed_event 短路，重放/补发绝不重复处理。
未来换 MQ 只改 Relay 投递目标。

### 2. StateVersionGate（V10 §21.1）— `digitalhuman.state` 包

LLM 调用前 `snapshot(companionId)` → 调用返回后 `tryCommit(companionId, expected)`：
版本未变 → 条件 UPDATE 递增并提交；版本已变 → **旧结果作废**(丢弃/重评)。
实现复用 cognitive_sessions.state_version 乐观锁(所有状态变更方统一递增)。

**接入**：`AgentRuntime.process` 回复生成路径 —— generate 前快照，发送前校验；
冲突时像真人一样"想了半天但情况已经变了"，不发送(发布 STATE_VERSION_CONFLICT 事件)。
当前单体 per-agent 锁内为纯防御层；未来 Actor 化/多实例/无锁后保证安全。

### 3. 顺带

- 高频 Job cron 全部配置化(第二轮延续)，新增 outbox-relay-cron 同样处理(测试禁用)。

---

## 设计模式对照

| 模式 | 落位 |
|---|---|
| Outbox | OutboxEvent + OutboxPublisher + OutboxRelayJob |
| 乐观锁(版本) | StateVersionGate(条件 UPDATE) |

## 本轮测试（10 个新增）

- `OutboxRelayTest`（5）：入队→Relay→PUBLISHED+事件被处理；同 key 幂等入队；
  重放被 processed_event 短路(不重复处理)；无路由事件失败重试(attempts 递增+退避)；
  eventId 规则与 live 路径一致
- `StateVersionGateTest`（5）：版本未变提交成功/递增；版本已变提交失败(旧结果作废)；
  多次变更后旧快照仍不可提交；缺失 Person 保守拒绝

## 全量回归

**234 测试全绿（0 失败 0 错误）**。

---

## 后续轮次路线图

- **第四轮**：Life Scheduler(活动/计划时间触发) + Relationship Projection(从 Reality Ledger 投影)
- **第五轮**：Conversation Runtime 唯一文本生成入口 + Prompt 分层缓存(V10 §15/§19)
- **第六轮**：AgentRuntime 锁迁移到 PersonActor + 唤醒/行为 Tick 事件化 + 复查路径接入 DecisionPolicyEngine
