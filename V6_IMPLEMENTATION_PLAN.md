# Companion Agent V6 — 拟真人格与持续生活 实施记录

> 本文档是 Claude Code 执行 V6 的实施计划与进度记录。
> 依据: `Companion_Agent_V6_工程实现方案.md`（77 节）。
> 原则: 不重写架构、增量实现、保留 V5 API、每阶段编译+测试。

## ✅ 完成状态（2026-08-16）

- **P0 Conversation Thread** ✅ — `conversation_threads` 表 + 生命周期(ACTIVE/PAUSED/RESUMABLE/ENDED/ABANDONED) + 话题切换 + 衰减 Job + 集成消息链路
- **P0 Unfinished Thought** ✅ — Thought UNFINISHED 类型 + `createUnfinished` + 冷却期激活 + 过期遗忘 + 激活 Job + DEFER 时记录
- **P0 Activity + Interrupt** ✅ — LifeActivity 增 V6 属性(attentionDemand/interruptibility/phoneAvailability/moodEffect/progress) + 活动中断/恢复/进度推进 + ActivitySpecProvider
- **P0 Emotion Inertia + BodyState** ✅ — 边际递减 + 多维情绪叠加(joy/loneliness/affection) + 身体状态(sleepiness/hunger/discomfort/focus) + 比例衰减
- **P1 Memory Recall Probability** ✅ — recallProbability = 激活 × 显著性 + 阈值过滤 + 检索回退(关键词→最近记忆)
- **P1 Decision Validator** ✅ — Brain 决策一致性校验(睡眠/忙/无手机/未注意/冲突不结束对话) + 修正动作
- **P1 Behavior Pattern** ✅ — `behavior_patterns` 表 + 观测学习(深夜/工作/开心模式) + 置信度/强度更新
- **P2 Expression Time Model** ✅ — MessageSegment 增 typingDurationMs + TypingSimulationService(短/复杂/多段节奏)
- **P2 Communication Friction** ✅ — PendingMessageState 增 frictionType/reviewCount + 想回忘了/回一半打断 + 复查计数
- **P3 Event Chain** ✅ — EventChainService 因果链 + maxDepth=3 + 后果逐层应用 + 情绪显著事件产想法
- **P3 Anti-AI Evaluation** ✅ — AntiAIPatternEvaluator(秒回/从不忽略/从不结束/从不遗忘/主动过多/回复过长) + /v6/eval 端点 + v6_check.sh
- **P3 集成验证** ✅ — clean 后 81 测试全绿 + v5_check + v6_check 全过 + 修复 memory 检索回退

## V6 相比 V5 的核心升级（对照 §73）

| # | V6 增强 | 实现 |
|---|---------|------|
| 1 | Agent 数量收敛 | Brain/Expression 保持 Agent; Emotion/Memory/Event 已是 Agent 接口+规则回退(Engine 化) |
| 2 | Runtime 持续生命周期 | LifeTick + ScheduledAction + Thread 衰减 + UnfinishedThought 激活 |
| 3 | Activity + Interrupt | 活动具体属性 + 中断/恢复(§32) |
| 4 | Attention / Notification | 消息生命周期 + 注意力概率(V5 已有, 增强活动属性联动) |
| 5 | Emotion State Inertia | 比例衰减(§48) + 边际递减(§49) + 身体状态放大(§50) |
| 6 | Memory Recall Probability | 阈值化进入认知(§19) + 检索回退 |
| 7 | Conversation Thread | 全新线程状态机(§30) |
| 8 | Unfinished Thought | 未完成想法激活机制(§31) |
| 9 | Behavioral Pattern Learning | 行为模式存储(§45/§46) |
| 10 | Human-like Anti-Pattern Eval | 反 AI 模式检测(§71/§72) |

## 新增文件清单

### 新实体/表
- `conversation_threads` — ConversationThread(话题/情感基调/未解决意图/状态机)
- `behavior_patterns` — BehaviorPattern(模式/置信度/观察数/影响方向)

### 新组件
- `ConversationThreadService` + `ConversationThreadController` + `ThreadMaintenanceJob`
- `LifeInterruptService`(中断/恢复/进度) + `ActivitySpecProvider`(活动属性映射)
- `MemoryRecallProbabilityService`(召回概率阈值)
- `DecisionValidator`(Brain 一致性校验)
- `BehaviorPatternService` + `BehaviorLearningService`(观测学习)
- `TypingSimulationService`(打字节奏)
- `EventChainService`(因果链 + 深度限制)
- `UnfinishedThoughtActivationJob`(未完成想法激活)
- `AntiAIPatternEvaluator` + `AntiAIController`(反 AI 评估)

### 增强组件
- `AgentState` + body state(sleepiness/hunger/discomfort/focus) + 情绪叠加(joy/loneliness/affection)
- `EmotionReducer` + 边际递减 + 身体放大 + 情绪叠加
- `MemoryService.retrieve` + 关键词检索空时回退最近记忆
- `PendingMessageState`/Service/ReevaluationJob + 通信摩擦
- `ExpressionResult`/Agent + typingDurationMs
- `V5MessagePipeline` + 线程 touch + 未完成想法 + 召回概率过滤

## 架构决策（记录）

1. **不重写 V5**: V6 在既有 Runtime/Agent 上增量实现, 保留全部 V5 REST API。
2. **惯性由定时任务驱动**: EmotionReducer 不做自动衰减(保持 empty-delta no-op 语义), 比例衰减放在 `decayAllNegative`。
3. **决策校验用规则**: DecisionValidator 全规则(不额外调 LLM), 复杂冲突留作未来。
4. **记忆召回回退**: 关键词 LIKE 全文匹配常为空 → 回退到最近记忆, 保证 Memory Agent 有候选。
5. **事件链深度**: consequences 为同一层并行事件, 深度由嵌套决定, maxDepth=3 防无限剧情。

## 验证

- `mvn clean test` → **81 测试全绿**(16 测试类)
- `scripts/v5_check.sh` → 全过(V5 回归)
- `scripts/v6_check.sh` → 全过(V6 新增表/API/评估)
- 端到端: 注册→创建→聊天→线程/行为模式/未完成想法/反 AI 评估 全链路可用
