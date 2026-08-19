# V10 迭代记录 — 第四轮：Life Scheduler（时间触发）+ Relationship Projection（账本投影）

> 对应方案：《Companion-Agent-V10-Detailed-Architecture-3.md》§7.4(Life Scheduler) / §17(Relationship)
> 目标：生活**事件驱动 + 时间触发**（不靠轮询猜），关系**事实层只信 Reality Ledger**。

---

## 本轮交付（2026-08-19）

### 1. Life Scheduler（V10 §7.4）— `digitalhuman.life` 包

**Event Driven + Time Trigger**：活动/计划开始时就排程"到点事件"，到点由调度器触发执行。

| 组件 | 职责 |
|---|---|
| `LifeEventScheduler` 接口 | `schedule(event)` / `cancel(scheduleId)` |
| `LifeScheduleStore`（life_schedule 表） | 持久化排程；同 scheduleId 幂等；PENDING→DONE/FAILED |
| `LifeScheduleJob` | 每 20 秒（配置化）轮询到点事件 → 分发 |
| `LifeEventDispatcher` | ACTIVITY_END：**幂等收尾**活动（ACTIVE/PLANNED→DONE + actualEnd）+ 写 Reality Ledger（ACTIVITY_ENDED） |

**接入**：`LifeSimulationService.ensureDayPlanned` —— 每天创建活动时即排程 `ACTIVITY_END`（fireAt=plannedEnd）。
生活"准点"变化（到点收尾），LifeTickJob 轮询退化为兜底；双路径幂等（先到先收尾，后到者跳过）。
PLAN_REMINDER 事件类型已定义（计划到点激活，第五轮接入 Conversation Runtime）。

### 2. Relationship Projection（V10 §17）— `digitalhuman.relationship` 包

**Projection Pattern**：关系的**事实层**（互动次数/模式/最近互动）从 Reality Ledger 投影，不手工维护。

| 组件 | 职责 |
|---|---|
| `InteractionSummary` | totalEvents/sent/read/deferred/ignored/lastInteractionAt/replyRate |
| `RelationshipProjector` | **纯函数**：RealityEvent 列表 → 互动摘要（只读、可重放、幂等） |
| `RelationshipProjectionService.reconcile` | 投影 → 修正 Relationship 事实字段（messageCount/lastInteractionAt），账本为准 |
| `RelationshipProjectionJob` | 每日 04:00（配置化）全量核对 —— 无论增量更新是否发生，事实层回到账本 |

**诊断端点**：`GET /api/v10/relationship/projection?companionId=&userId=` → 投影摘要 + 是否修正。

**原则落地**：Memory/模型输出不能覆盖 Reality（MVP 验收 10 的关系侧延伸）。

### 3. 新增表

- `life_schedule`（生活事件排程）

---

## 设计模式对照

| 模式 | 落位 |
|---|---|
| 时间触发调度 | LifeEventScheduler + LifeScheduleStore + LifeScheduleJob |
| Projection | RelationshipProjector（账本 → 关系事实） |
| 幂等收尾 | LifeEventDispatcher（ACTIVITY_END 双路径安全） |

## 本轮测试（11 个新增）

- `RelationshipProjectorTest`（4）：空投影/各类型计数/回复率/互动口径过滤（纯单元）
- `LifeScheduleJobTest`（3）：排程到点收尾 + 账本记录；重复触发幂等；同 scheduleId 幂等入队
- `RelationshipProjectionServiceTest`（4）：投影摘要；reconcile 修正计数（99→3）；幂等；无互动不修正

## 全量回归

**245 测试全绿（0 失败 0 错误）**。

---

## 后续轮次路线图

- **第五轮**：Conversation Runtime 唯一文本生成入口 + Prompt 分层缓存（V10 §15/§19）+ PLAN_REMINDER 接入
- **第六轮**：AgentRuntime 锁迁移到 PersonActor + 唤醒/行为 Tick 事件化 + 复查路径接入 DecisionPolicyEngine
