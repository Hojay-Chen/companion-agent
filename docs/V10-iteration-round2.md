# V10 迭代记录 — 第二轮：感知策略化 + 决策策略化

> 对应方案：《Companion-Agent-V10-Detailed-Architecture-3.md》§10(Perception) / §13(Decision)
> 目标延续：**让用户无法区分聊天对象是真人还是 agent** —— 感知是"过滤器"而非"转发器"，
> 决策是"行动空间"而非"回/不回"二元。

---

## 本轮交付（2026-08-19）

### 1. Perception Runtime（V10 §10）— `digitalhuman.perception` 包

**Strategy Pattern**：不同事件类型使用不同感知策略，全程**规则 + 评分，不调用 LLM**：

| 策略 | 事件类型 | 逻辑 |
|---|---|---|
| `MessageNotificationStrategy` | DEVICE_NOTIFICATION | 声音0.65/震动0.4/静音0.15/勿扰0；手边+0.2、其他房间-0.25；嘈杂-0.2；忙-0.25；沉浸-0.2；睡着=0 |
| `LifeEventStrategy` | LIFE_EVENT | 与自己直接相关 → 基础0.75（沉浸/嘈杂略降） |
| `TimeEventStrategy` | TIME_EVENT | 自设排程 → 基础0.8（睡着降为0.5） |

**4 级感知**（`PerceptionLevel`）：NONE(<0.2) / SUBCONSCIOUS(0.2~0.5) / AWARE(0.5~0.8) / FOCUSED(≥0.8)
（`PerceptionRuntime` 聚合 + 阈值映射，阈值可配置）。

**快照体系**（轻量值对象 + `SnapshotFactory` Adapter）：
`LifeSnapshot`(activity/attentionDemand/sleeping) / `MindSnapshot`(focus/arousal/energy) /
`DeviceSnapshot`(mode/dnd/distance) / `EnvironmentSnapshot`(noise) ——
`SnapshotFactory` 从 AgentState/PhoneState/CompanionSchedule 提取，未来替换状态来源只改这里。

**验收达成**：同一消息在不同环境下可能被感知或完全不知道（勿扰/睡着→NONE；声音+在手+休闲→FOCUSED）。

### 2. Decision Runtime（V10 §13）— `digitalhuman.decision` 包

**Policy Pattern + sealed Decision**（State Pattern 心智）：

```
PersonDecision(sealed)
├── IgnoreDecision           — 没感知/潜意识不重要/琐碎且疲惫
├── InspectDeviceDecision    — 感知到但不必立即回: 先看看是什么事
├── ReplyDecision            — FOCUSED 且重要/亲密+联系压力
├── DelayReplyDecision       — FOCUSED 但忙/疲惫(延迟 30~120min, 复查补回)
└── ChangeActivityDecision   — 生活事件 → 调整活动
```

`DecisionPolicyEngine` 按 `@Order` 确定性执行：Ignore(1) → ReplyLater(2) → ReplyNow(3)
→ ChangeActivity(4) → InspectDevice(5)；复杂冲突才交给 LLM，常规情境全部确定性覆盖（可解释可测试）。

**验收达成**：Agent 可以决定忽略、查看、立即回复或稍后回复（方案 Phase 5 验收）。

### 3. 完整链路编排 — `PerceptionDecisionOrchestrator`

V10 因果链局部落地：`ExternalEvent → Perception(评分) → Decision(策略)`。
真实运行时可直接工作（SnapshotFactory 接现有状态服务）。

### 4. 诊断端点 — `GET /api/v10/perception/explain`

输入 companionId + eventType + importance → 返回感知等级/评分/策略 + 决策类型/理由 + 生活/设备/心智快照。
验收可视化：同一事件在不同环境下感知与决策不同。

### 5. 顺带修复（既有问题）

**高频定时 Job 硬编码 cron**（不读配置，测试期间抢占共享连接池 → 偶发连接耗尽）：
- `ScheduledActionJob`（每 20 秒）、`PendingMessageReevaluationJob`（每分钟）、
  `MemoryDecayService`（周一 04:30）→ 全部改为 `${app.scheduler.xxx-cron:默认}`，
  测试环境（application-test.yml）禁用。

---

## 设计模式对照

| 模式 | 落位 |
|---|---|
| Strategy | PerceptionStrategy（按事件类型评分） |
| Policy | DecisionPolicy（按情境决策） |
| State 心智 | PerceptionLevel 状态迁移 + sealed Decision |
| Adapter | SnapshotFactory（状态来源解耦） |
| Registry/编排 | PerceptionDecisionOrchestrator |

## 本轮测试（23 个新增）

- `PerceptionRuntimeTest`（8）：勿扰/睡着→NONE；声音在手→FOCUSED；静音+远+嘈杂→不触发认知；
  忙时震动→降级；生活/时间事件策略；阈值边界
- `DecisionPolicyEngineTest`（11）：忽略/立即回/延迟回/查看/生活事件/回退
- `PerceptionDecisionOrchestratorTest`（4）：完整链路 + 勿扰→忽略 + 正常→立即回（@MockBean 快照，确定性）

## 全量回归

**224 测试全绿（0 失败 0 错误）**。

---

## 后续轮次路线图

- **第三轮**：Outbox 事件发布（双写一致性）+ stateVersion 乐观锁（LLM 旧结果丢弃，MVP 验收 12）
- **第四轮**：Life Scheduler（活动/计划时间触发）+ Relationship Projection（从 Reality Ledger 投影）
- **第五轮**：Conversation Runtime 唯一文本生成入口 + Prompt 分层缓存（V10 §15/§19）
- **第六轮**：AgentRuntime 锁迁移到 PersonActor + 唤醒/行为 Tick 事件化 + 复查路径接入 DecisionPolicyEngine
