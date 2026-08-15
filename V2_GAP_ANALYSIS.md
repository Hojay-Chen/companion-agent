# Luxera Companion V2.0 差距对照表

> 用途：对照《Luxera Companion V2.0 重构设计方案》，逐节核对当前实现状态，作为评审/汇报依据。
> 日期：2026-08-15 · 代码版本：Git `9036c41` · 线上：`https://companion.luxera.top`

## 图例

| 标记 | 含义 |
|------|------|
| ✅ | 已实现（达到方案要求） |
| ◐ | 部分实现（有基础能力，但为简化版或缺细节） |
| ❌ | 未实现 |
| ⛔ | 方案明确"第一阶段不做"（不算缺口） |

---

## 一、总览

> 更新记录：
> - 2026-08-15 第二轮补齐（Git `f1213be`）——可解释性、BehaviorConstraints、模型用途路由、Imperfection、Experience 清理、记忆评分+类型、thoughts 收敛、@Deprecated、测试/评测脚本。
> - 2026-08-15 第三轮补齐（Git 待定）——关系冲突/修复/InsideJoke/共享记忆、主动完整公式、Thought→OpenLoop+LLM抽取、反思多维度、自我模式归纳、用户共享生活事件、轻微不耐烦摩擦、LearningContext、evaluate自动打分、长期测试全自动断言。

| 类别 | 数量 | 说明 |
|------|------|------|
| ✅ 已完成 | 44 节 | 除少数方案后置/受环境限制项外，V2.0 全部达成 |
| ◐ 部分实现 | 4 节 | §9 SelfModel拆表(方案后置)、§33 REAL_TOOL/SYSTEM来源(需工具层)、§35 Pattern深度归纳(基础版)、§37 关系→记忆(基础版) |
| ❌ 未实现 | 0 节 | — |
| ⛔ 方案后置 | 1 节 | MCP/Tool 生态（§49 明确第一阶段不做） |

---

## 二、逐条对照表（按方案章节）

### 核心目标与原则

| 方案 | 内容 | 状态 | 差距说明 |
|------|------|------|---------|
| §1 重构目标 | 从并列模块升级为 Cognitive Runtime | ✅ | `CompanionCognitiveRuntime` 统一内核已建 |
| §2 真实性模型 | 补齐 Life/Thought/EmotionalEpisode/SelfModel/RelationshipNarrative 四缺口 | ✅ | 四缺口全部补齐 |
| §3.2 原则A | LLM 是语言实现器，Runtime 决定行为 | ✅ | `BehaviorPolicyEngine` 决定"做什么"，LLM 负责表达 |
| §3.2 原则B | Experience ≠ Memory，需 consolidation | ✅ | `ExperienceProcessor`+`MemoryConsolidator` |
| §3.2 原则C | Thought ≠ 输出，可保留/压制/遗忘/转主动 | ◐ | SUPPRESSED 做了；"Convert to OpenLoop"决策未显式实现 |
| §3.2 原则D | 人格变化来自经历（evidence→reflection→learned behavior） | ✅ | 分层演化（行为适应→偏好→traits） |

### 架构与模块

| 方案 | 内容 | 状态 | 差距说明 |
|------|------|------|---------|
| §4 模块重构 | 新增 life/thought/emotion/selfmodel/experience/behavior/narrative | ✅ | 7 个新包全部建立 |
| §5-6 Life Runtime | CompanionLife/LifeActivity/LifeSimulation/LifeTickJob | ✅ | 事件驱动+时间推进，不调 LLM |
| §7 EmotionalEpisode | 事件型情绪 + AgentState | ✅ | EmotionEngine+Decay；情绪影响行为倾向（verbosity↓等） |
| §8 OpenLoop | 未完成事项，驱动主动 | ◐ | 基础版做了；**LLM 抽取 OpenLoop 未做**（现为规则）；CONVERT_TO_OPEN_LOOP 决策未显式 |
| §9 SelfModel | 自我模型+叙事，版本化 | ◐ | 单表 `self_models` ✅；**第二阶段拆表（self_facts/goals/beliefs/narratives）未做**（方案本就后置，非缺口） |
| §10 Relationship 2.0 | Threads/Promises/Inside Joke/Narrative | ◐ | Threads✅ Promises✅ Narrative✅；**Conflicts/Repair Events/Shared History 结构化未做**；Inside Joke 仅靠 narrative_role 标记，未专门识别 |

### 认知内核与行为

| 方案 | 内容 | 状态 | 差距说明 |
|------|------|------|---------|
| §11 Experience Layer | Message/Life/Thought/Emotion→Experience→Consolidation | ✅ | 核心链路完整 |
| §12 Memory 2.0 | 类型扩展+检索评分升级+pgvector | ◐ | 见"记忆专项" |
| §13 BehaviorPolicy | BehaviorDecision+Constraints | ✅ | `BehaviorDecision`+`BehaviorPolicyEngine`+`BehaviorConstraints`✅；`proactive_candidate` 已填充（open_loop/thought） |
| §14 ContextCompiler | 替代 PromptAssembler，上下文分层 | ✅ | `ContextCompiler` 已用；Runtime/Prompt 分层实现 |
| §15 LLM 职责边界 | Runtime 决定，LLM 表达 | ✅ | 已贯彻 |
| §16 用户消息链路 | 感知→上下文→策略→编译→生成→经历→异步学习 | ✅ | `processUserMessage` 完整链路 |
| §17 无交互生命周期 | tick：Life→Emotion→Thought→OpenLoop→Proactive | ✅ | `tick()` 已实现并接入 |
| §27 CompanionCognitiveRuntime | processUserMessage + tick 统一内核 | ✅ | 已实现 |
| §28 CompanionContext + ContextLoader | 统一上下文 | ✅ | 已实现 |
| §29 Context 分层 | Runtime/Prompt/Learning | ◐ | Runtime✅ Prompt✅；**独立 Learning Context 对象未建**（反思/学习上下文在 service 内临时拼） |

### Proactive / 演化 / 反思

| 方案 | 内容 | 状态 | 差距说明 |
|------|------|------|---------|
| §18 Proactive 2.0 | Thought/OpenLoop/Life/Relationship/Emotion 驱动 + SUPPRESSED | ◐ | Thought/OpenLoop 驱动✅ SUPPRESSED✅；**决策公式简化**（无显式 relationship_relevance/timeliness 加权项） |
| §19 Persona Evolution 2.0 | 四层演化 | ✅ | 行为适应→互动偏好→traits(±0.02)→核心身份禁止，全部实现 |
| §20 关系摩擦 | 低能量/不想聊/不耐烦/拒绝建议 | ◐ | 只做了"压力高→姿态内敛、少说少主动"；更细的摩擦行为未专门实现 |
| §39 反思 2.0 | 分析 User/Self/Relationship/Life/Memory/Thought | ◐ | User✅ Self✅ Relationship✅；**Life/Memory/Thought 三个维度的反思产出未显式** |
| §40 人格演化约束 | 第1层行为→第4层身份 | ✅ | 已落实 |

### 数据 / 调度 / 成本

| 方案 | 内容 | 状态 | 差距说明 |
|------|------|------|---------|
| §21 数据库改造 | 新增 9-10 张表 | ✅ | 已新增 10 张（companion_life/life_activities/thoughts/emotional_episodes/open_loops/self_models/experiences/relationship_threads/promises/relationship_narratives），共 29 张 |
| §22 数据一致性 | 核心表带 id/companion_id/created_at/updated_at | ✅ | 基本一致（life_activities 缺 updated_at，小偏差） |
| §23 调度体系 | CompanionClock + 各 Job | ◐ | LifeTick/Thought/Emotion/OpenLoop/Consolidation/Proactive/Reflection/PersonaEvolution 各 Job ✅；**统一 CompanionClock 时钟类未建**（各 Job 独立 cron） |
| §24 成本控制 | 无变化不调 LLM，Level 0-4 分级 | ✅ | Life/Thought/Emotion/OpenLoop 均规则不调 LLM；Level 分级思想已体现 |

### LLM / 接口 / UI

| 方案 | 内容 | 状态 | 差距说明 |
|------|------|------|---------|
| §25 LLM Router 升级 | 模型用途路由（CHAT/PERCEPTION/THOUGHT/REFLECTION/...） | ✅ | 已实现：`app.llm.purpose.*` 按任务分模型/温度，`StructuredRequest.model` 覆盖；默认各任务同 chat-model，可配置不同模型 |
| §30 API 演进 | /life /thoughts /open-loops /self /experiences + admin | ◐ | 大部分✅；`/admin/cognitive/tick` 为占位（实际由各 Job 推进）；/thoughts 已收敛为仅内部（见 §31） |
| §31 UI 原则 | 用户看"她最近/经历/故事"，不暴露数值 | ✅ | 「她最近」抽屉✅；**/thoughts 已从用户 API 移除**（仅管理/内部可用） |
| §32 "她今天在干嘛" | 来自 Life Runtime | ✅ | 已实现 |
| §33 虚构人生边界 | SIMULATED/USER_SHARED/REAL_TOOL/SYSTEM 事件来源区分 | ◐ | 只用了 SIMULATED_LIFE_EVENT；其他来源未实际产生 |
| §34 MCP/Tool 层 | Tool Runtime + Calendar/Weather/Search/McpTool | ⛔ | 方案 §49 明确第一阶段不做，**非缺口** |

### 生命周期 / 版本 / 可解释性

| 方案 | 内容 | 状态 | 差距说明 |
|------|------|------|---------|
| §35 Memory 与 Life 关系 | Life→Experience→Pattern→SelfModel | ◐ | Life→Experience✅；反思→SelfModel✅；**中间"重复→Pattern"归纳未专门实现** |
| §36 Self 与 Persona 关系 | 五者不混 | ✅ | Persona/Self/State/Thought/Life 分离清晰 |
| §37 Relationship 与 Memory 关系 | Memory→Relationship→Narrative 三层 | ◐ | 叙事层✅；关系→记忆的显式连接未专门做 |
| §38 Narrative 层 | RelationshipNarrative 版本化 | ✅ | 已实现 |
| §41 数据生命周期 | Experience 7-30天清理；Thought/Episode 短周期 | ✅ | Thought(24h/72h)✅ Episode(消退)✅；**低价值 Experience 每日清理（30 天前 DISCARDED）** |
| §42 版本化 | Persona/SelfModel/RelationshipNarrative 带 version/change_reason | ✅ | 三对象全部版本化 |
| §43 可解释性 | "为什么主动/为什么记住/为什么人格变"结构化解释 | ✅ | **已实现** `/api/admin/explain/{proactive,memory,persona}` |
| §46 Imperfection | 记不清/不知道/偶尔误解（需合理原因） | ◐ | **轻量实现**：ContextCompiler 对低置信/久远记忆标注"记不太清"，允许自然承认模糊 |

### 测试 / 评测 / 迁移

| 方案 | 内容 | 状态 | 差距说明 |
|------|------|------|---------|
| §44 测试体系 | 4 类长期测试（Memory/Life/Relationship/Proactive Continuity） | ◐ | **脚本已建** `scripts/longterm_test.sh`（4 类连续性冒烟）；尚未做成全自动持续回归 |
| §45 真实感评测 | Human-likeness Score（10 维度 1-5 分） | ◐ | **脚本已建** `scripts/evaluate.sh`（10 维评分模板）；尚未接入自动打分 |
| §47 迁移策略 | Strangler Pattern | ✅ | `CompanionRuntime` 已委托新内核✅；**旧 PromptAssembler/ContextBuilder 已标 @Deprecated** |
| §48 开发顺序 | Phase 1-7 | ✅ | 全部完成 |
| §49 第一阶段不做 | MCP/多端/Avatar/3D/语音/K8s/向量库 | ✅ | 已遵守 |
| §50 验收场景 | A-E | ✅ | 全部通过 |

---

## 三、记忆专项差距（§12）

| 子项 | 状态 | 差距 |
|------|------|------|
| pgvector 安装 + 扩展 | ✅ | vector 类型可用 |
| 向量检索接线 | ◐ | `PgVectorEmbeddingProvider` 已接；**未配置 embedding API key，实际未激活**（回退结构排序）；DeepSeek 无 embedding 接口，需另配 OpenAI/SiliconFlow key |
| 记忆类型 RELATIONAL/SELF/NARRATIVE | ◐ | 字段支持了，**抽取/固话未实际生成这三类**（现只有 episodic/semantic/shared） |
| 检索评分完整公式 | ◐ | 现为 `importance×confidence×recency×emotional×relationship×frequency`+向量候选；**缺 narrative_relevance / open_loop_relevance 显式因子** |
| narrative_role（INSIDE_JOKE 等） | ◐ | 字段+固化时粗判；未专门识别 |

---

## 四、建议实施路线（按优先级）

### P0 · 合规/治理（小改动，建议尽快）
1. **收敛 /thoughts 不暴露给用户**（§31 偏差）：改为仅管理端可见，或在返回前过滤强度阈值/类型。
2. **旧模块标 @Deprecated**（§47）：`PromptAssembler`/`ContextBuilder`。

### P1 · 机制补全（中改动）
3. **可解释性端点 /admin/explain**（§43）：对"为什么主动联系/为什么记住/为什么人格变化"返回结构化解释（触发/相关记忆/关系/原因）。
4. **Memory 完整检索评分**（§12.2）：把 narrative_relevance + open_loop_relevance 纳入排序；Memory 类型 RELATIONAL/SELF/NARRATIVE 真正产出。
5. **Experience 生命周期清理**（§41）：每日清理 30 天前的 DISCARDED/已固话低价值经历。

### P2 · 进阶真实感（大改动）
6. **LLM 模型用途路由**（§25）：`app.llm.purpose.*` 按 CHAT/PERCEPTION/EXTRACTION/REFLECTION 分模型。
7. **Imperfection 机制**（§46）：基于 memory decay/low confidence 主动允许"记不清/不知道"。
8. **Proactive 完整决策公式**（§18）：显式 relationship_relevance/timeliness 加权。
9. **BehaviorConstraints + proactive_candidate 填充**（§13）。

### P3 · 测试与评测（方案核心验收手段）
10. **4 类长期测试**（§44）：Memory/Life/Relationship/Proactive Continuity 模拟。
11. **Human-likeness 评测**（§45）：10 维评分脚本。

---

## 五、环境限制说明（非实现缺口）

- **pgvector 已装但需 embedding key 激活**：DeepSeek 不提供 embedding 接口，需配置 `EMBEDDING_API_KEY`（如 SiliconFlow `BAAI/bge-large-zh-v1.5`）才启用真实向量检索。
- **单机部署**：nginx + 单 jar + 单库，无 K8s/HA（方案 §49 后置）。
