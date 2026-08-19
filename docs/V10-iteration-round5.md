# V10 迭代记录 — 第五轮：Conversation Runtime 唯一文本入口 + Prompt 分层缓存

> 对应方案：《Companion-Agent-V10-Detailed-Architecture-3.md》§15(Conversation) / §19(Prompt Cache)
> 目标：**系统中禁止其他模块生成最终聊天文本**(MVP 验收 14)；稳定层 Prompt 可缓存。

---

## 本轮交付（2026-08-19）

### 1. Conversation Runtime（V10 §15）— `digitalhuman.conversation`

**唯一聊天文本生产管道**：

```
ConversationRequest(分层) → 稳定层(缓存) + 动态层 → LLM → 输出契约解析
→ OutputValidationChain 质量闸门 → 失败重生成(≤2 次) → List<ChatMessageDraft>
```

| 组件 | 职责 |
|---|---|
| `ConversationRequest`（Builder Pattern，V10 §15.2） | Stable Prefix（System Contract/Identity/Personality/Expression Rules/Output Contract）+ Semi-Stable（关系/生活）+ Dynamic Suffix（活动/心智/消息）；`withCorrection` 携带验证失败原因重试 |
| `ConversationRuntime.generateDrafts` | 管道执行：LLM 调用 → `{"messages":[{"text":...}]}` 契约解析（容错非 JSON）→ 全量验证 → 重试 ≤3 次 → 仍失败**不生成**（像真人没说出口） |
| `PromptLayerCache`（V10 §19） | 稳定层（Stable+SemiStable）SHA-256 哈希缓存：同 hash 不重复渲染；动态内容绝不进缓存；人格版本变化 → hash 失效自动重建；命中率统计 |

### 2. 文本生成收口

- **`ProactiveEngine.draftMessage`**（主动消息）：从直接 `llm.chat` 改为 ConversationRuntime 管道 ——
  主动消息也必须过质量闸门（禁旁白/AI 腔）；失败回退模板保留。
- 回复路径（AgentRuntime → CompanionRuntime）已在第一轮接入输出闸门，本管道为其统一抽象。

### 3. PLAN_REMINDER 接入（V10 §7.3/§7.4 补全）

- `LifeEventDispatcher.PLAN_REMINDER`：计划到点 → `PlanService.activate`（PLANNED → ACTIVE）——
  计划到点**开始执行**（概率性计划：激活 ≠ 完成，是否真的去做由行为决策决定）。
- `LifeRuntime.syncPlan`：创建计划（expectedTime 非空）即排程 PLAN_REMINDER。

### 4. 诊断端点

`GET /api/v10/conversation/cache-stats` → 稳定层缓存条目/命中率。

---

## 设计模式对照

| 模式 | 落位 |
|---|---|
| Builder | ConversationRequest（分层上下文组装） |
| 唯一入口(门面) | ConversationRuntime（一切聊天文本经此管道） |
| 缓存 | PromptLayerCache（稳定层哈希缓存） |

## 本轮测试（11 个新增）

- `PromptLayerCacheTest`（4）：同稳定层命中/变化失效/动态不缓存/清空
- `ConversationRuntimeTest`（5，@MockBean LlmRouter）：契约输出通过；**旁白输出自动重试**（2 次调用）；始终不过闸门放弃（3 次后空）；多消息契约；LLM 失败空
- `PlanReminderDispatchTest`（2）：计划到点激活（PLANNED→ACTIVE）；planId 缺失优雅失败

## 全量回归

**256 测试全绿（0 失败 0 错误）**。

---

## V10 MVP 验收进度（14 条）

| # | 验收 | 状态 |
|---|---|---|
| 1 | Chat Platform 完全脱离 Agent 运行 | ✅ Simulator 边界 |
| 2 | Agent 不直接访问 Chat Database | ✅ 写入收口 SimulatorClient |
| 3 | Agent 不直接收到消息内容 | ✅ 事件只带 messageIds |
| 4 | 消息先成为设备/环境事件 | ✅ CHAT_MESSAGE_DELIVERED 链 |
| 5 | Agent 可以没有感知到消息 | ✅ Perception 4 级 |
| 6 | 感知但不查看 | ✅ Ignore/决策策略 |
| 7 | 查看但不回复 | ✅ InspectDevice |
| 8 | 自主决定延迟回复 | ✅ DelayReply + 复查 |
| 9 | 真实经历可在 Reality Ledger 回放 | ✅ append-only 回放 |
| 10 | Memory 不能覆盖 Reality | ✅ @Immutable + 关系投影 |
| 11 | 同 Person 不允许并发修改状态 | ✅ PersonActor + 锁 |
| 12 | LLM 旧状态结果不能覆盖新状态 | ✅ StateVersionGate |
| 13 | 重试不能导致重复发送 | ✅ processed_event + Outbox |
| 14 | 最终聊天文本只有 Conversation Runtime 生成 | ✅ 本轮回流(入口+闸门) |

## 后续轮次路线图

- **第六轮**：AgentRuntime 锁迁移到 PersonActor + 复查路径接入 DecisionPolicyEngine + 唤醒/行为 Tick 事件化
