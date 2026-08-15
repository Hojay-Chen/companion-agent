# Companion Agent V5 — 实施计划（执行记录）

> 本文档是 Claude Code 执行 V5 的实施计划与进度记录。
> 依据: `Companion_Agent_V5_工程实现方案.md`（110 节）。
> 原则: 不重写架构、增量重构、保留 API、每 Phase 编译+测试。

## ✅ 完成状态（2026-08-16）

- **P0 Runtime 基础设施** ✅ — WorldEvent/RuntimeEventBus/WakeReason/Agent/StateReducer/AgentTrace/ScheduledAction/WorldEventLog/EmotionReducer
- **P1 Message Pipeline** ✅ — V5MessagePipeline + MessageDeliveryService(生命周期) + PendingMessageState + 复查 Job + SSE 主动推送
- **P2 Memory Agent** ✅ — 两阶段召回(廉价检索→LLM 激活) + 回退
- **P3 Emotion Agent** ✅ — LLM 结构化评估 + 关键词 cheap signal 回退 + Reducer 落状态
- **P4 Brain Executive** ✅ — 结构化决策(REPLY/DEFER/CHECK_PHONE/IGNORE) + 规则回退
- **P5 Expression Agent** ✅ — 策略+段计划注入生成提示, 替代机械拆句
- **P6 Event Simulation** ✅ — LLM 候选 + Runtime 采样 + world_events + 经历/想法抽取
- **P7 Skills** ✅ — resources/skills SKILL.md + SkillLoader/Registry/Composer, Agent 固定注入
- **P8 测试** ✅ — 24 个测试全绿 + scripts/v5_check.sh 全过 + v4 回归通过

## 现状（V4）

- Spring Boot 2.7 / JDK17 / PostgreSQL 15，211 个 Java 文件，无测试。
- 核心链路: `ChatController.streamChat`（消息入库→Appraisal 规则→Attention→InteractionPolicy 规则→生成→按标点拆分）
  + `DefaultCompanionCognitiveRuntime.processUserMessage`（感知→上下文→行为规则→LLM 生成→自然度校验→异步学习）。
- LLM 网关: `LlmRouter`，当前无 API key → **Mock 网关**（离线可跑）。V5 所有新 Agent 必须带"LLM 失败→规则回退"。

## V5 关键缺口（方案 §1）

1. 情绪仍靠关键词规则（`AppraisalService`）。
2. 行为仍靠 if/else（`BehaviorPolicyEngine` / `InteractionPolicyEngine` 的 drives 比较）。
3. 表达与决策耦合（Brain 提示词即表达提示词；按标点拆句）。
4. CHECK_PHONE / READ / REPLY 未分离（无"看到了不回"的持久状态）。

## 实施阶段与交付物

### P0 — Runtime 基础设施（runtime 包）
- `WorldEvent` / `WorldEventType` / `WakeReason` / `RuntimeEventBus`
- `Agent<I,O>` 接口 / `AgentRegistry`
- `StateReducer<E,S>` 接口 + `EmotionDelta` + `EmotionReducer`
- `AgentTrace`（实体+仓库+服务）
- `ScheduledAction`（实体+仓库+服务+Job）—— 持久化主动/延迟行为
- `AgentState` 增加情绪维度列（sadness/anxiety/warmth）

### P1 — Message Pipeline（pipeline 包）
- 消息生命周期状态机: DELIVERED→NOTIFIED→NOTICED→CHECKED→READ→(DEFERRED|RESPONDED|IGNORED)
- `PendingMessageState`（已读未回 + nextReviewAt）+ 定时复查 Job
- `MessageDeliveryService`（生命周期转换 + 事件发布）
- 集成 `ChatController`：先 Emotion 评估 → Attention → Brain 决策 → 回复路径

### P2 — Memory Agent
- 两阶段召回: 廉价检索（现有 MemoryService）→ `MemoryAgent` LLM 激活评分
- `MemoryRecallContext` / `MemoryRecallResult` / `MemoryActivation`
- 回退: 无 LLM 时用检索强度排序（现有逻辑）

### P3 — Emotion Agent（P0 优先级）
- `EmotionAgent.appraise(EmotionContext)` → 结构化 `EmotionAppraisalResult`
- 输入: 消息+近况+关系+当前情绪+活动+注意力+人格+记忆候选
- 输出: appraisal + emotionDelta + memoryTriggers + confidence + reason
- 关键词仅作 cheap signal（现有 AppraisalService 回退）；LLM 有效时接管
- 经 `EmotionReducer` 改状态（所有状态变更走 Reducer）

### P4 — Brain Executive
- `BrainAgent.decide(BrainContext)` → 结构化 `BrainDecision`
- 动作空间: REPLY / SHORT_ACK / CHECK_PHONE_FIRST / READ_NO_REPLY / IGNORE / END_CONVERSATION / SCHEDULE_REEVALUATION
- 输入: 世界状态摘要+消息+情绪摘要+记忆摘要+drives+关系+待办想法
- Drives 只做上下文；回退到现有 `InteractionPolicyEngine`
- DEFER → 创建 PendingMessageState + 排程复查

### P5 — Expression Agent + Cadence
- `ExpressionAgent.plan(ExpressionContext)` → 结构化 `ExpressionResult`
- 策略(tone/directness/warmth/playfulness/vulnerability)注入生成提示词
- 段计划(消息数/延迟/意图)替代"按标点拆句"
- SEND_MESSAGE 走 ScheduledAction（延迟发送）

### P6 — Event Simulation + Causal Chain
- `EventSimulationAgent` 提出候选（含 base probability）
- `EventSimulator`（Runtime）加 modifier 后采样决定
- 事件落 `world_events`，可产生后果（causal chain）
- 事件后 Experience 抽取（习惯倾向）

### P7 — Skills / Prompt Registry
- `resources/skills/**/SKILL.md`（identity/personality/relationship/emotion/memory/event/expression/brain）
- `SkillRegistry` / `SkillLoader` / `SkillPromptComposer`
- Agent 按类型加载固定 Skill；核心身份与 Skill 分离

### P8 — Eval + 测试 + 修复
- 每个新模块 JUnit 单元测试
- 固定场景 Eval（20+ 场景）→ `scripts/v5_check.sh`
- 全量编译 + 现有 v4_check.sh 回归
- 修复至全绿 → README 更新

## 架构决策（记录）

1. **LLM 回退策略**: 所有新 Agent = 规则基准 + LLM 增强。LLM 结构化结果 confidence < 阈值或解析失败 → 用规则基准。Mock 网关返回低置信度占位 → mock 模式下行为与 V4 兼容，配真实 key 后自动升级。
2. **状态变更统一走 Reducer**: LLM/Agent 只产出 delta，StateReducer 负责落状态。
3. **ScheduledAction 持久化**: 主动消息/延迟回复/复查全部落库，服务重启不丢。
4. **新包结构**: `com.luxera.companion.runtime` + `.runtime.agents` + `.runtime.pipeline` + `.runtime.skill`，与 V4 包共存（Strangler）。
5. **保留 API**: 所有现有 REST 端点不变；新增 `/api/companions/{id}/v5/*` 仅用于诊断。
