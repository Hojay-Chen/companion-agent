# Luxera Companion 技术架构设计文档

| 项 | 值 |
|----|----|
| 文档版本 | v1.0 |
| 日期 | 2026-08-15 |
| 代码版本 | Git `main` @ `1ff99a8` |
| 产品 | Persistent AI Companion（长期陪伴型 AI 数字伴侣） |
| 架构风格 | 前后端分离 · 后端模块化单体（Modular Monolith） |

---

## 1. 引言

### 1.1 目的
本文档描述 Luxera Companion 系统的技术架构与全部功能的实现方式，作为开发、评审、运维与后续演进的权威依据。

### 1.2 范围
覆盖：总体架构、模块组织、数据架构、19 个数据表结构、所有功能的详细实现（算法、流程、关键类、时序）、API 清单、非功能设计、已知限制。

### 1.3 术语
| 术语 | 含义 |
|------|------|
| Companion | 数字人格实例（用户长期相处的对象） |
| Persona | 编译后的人格模型（traits/沟通/行为/价值观/边界） |
| Episodic/Semantic/Shared Memory | 情节/语义/共同记忆 |
| User Model | 系统对用户的长期理解（事实/偏好/模式/推测） |
| Agent State | 伴侣短期动态状态（≠人格） |
| Reflection | 反思引擎（每日/每周异步分析） |
| Proactive | 主动消息（打断成本控制下主动联系用户） |

---

## 2. 系统定位与总体目标

本产品不是 Chatbot：核心是把一个大模型包装成**能够持续存在、持续经历、持续记忆、持续认识用户、持续形成关系、在时间中保持人格连续性的数字人**。

```
LLM = 语言与推理能力   Memory = 记忆   Persona = 人格
Life Event = 经历      User Model = 对你的认识   Relationship = 历史
State = 当下           Time = 让这一切连续起来
```

**核心闭环**（设计文档 §101）：
```
User → Conversation → Perception → [Memory / UserModel / State]
→ Relationship → Intention → Behavior → Response → Experience → Reflection
→ [Memory更新 / UserModel更新 / Persona演化] →（循环）
```

---

## 3. 总体架构

### 3.1 架构风格与选型理由

| 决策 | 选择 | 理由 |
|------|------|------|
| 后端形态 | **模块化单体**（Modular Monolith，14 个业务包） | 单体部署简单、事务一致性强；模块按业务域清晰切分，未来可平滑拆微服务 |
| 后端框架 | Spring Boot 2.7.18 + JDK 17 | 团队既有技术栈（与 blog-platform 一致），生态成熟 |
| 持久层 | Spring Data JPA（Hibernate 5.6） | 快速开发；复杂检索用 @Query |
| 前端 | React 19 + Vite 8 + TS(strict) + Tailwind 3 + Zustand | 团队既有栈；Zustand 轻量状态管理 |
| 数据库 | PostgreSQL 16（19 表） | 关系型 + 时间数据友好；未装 pgvector（见限制） |
| LLM | 统一网关 LlmGateway → DeepSeek(deepseek-chat) | 多提供方可插拔（OpenAI兼容/Anthropic/Mock） |
| 流式 | SSE（text/event-stream） | 服务端单向流，简单可靠；nginx `proxy_buffering off` 支持 |

### 3.2 逻辑分层

```
┌─────────────────────────────────────────────────────────┐
│ 表示层  React SPA（/companions、聊天、抽屉、设置）        │
├─────────────────────────────────────────────────────────┤
│ 接入层  ChatController(SSE) / 各业务 Controller / JWT 认证 │
├─────────────────────────────────────────────────────────┤
│ 编排层  CompanionRuntime（一次对话总编排）                 │
│        AgentPostProcessor（异步后处理）                   │
│        ProactiveEngine / ReflectionJob（定时任务）        │
├─────────────────────────────────────────────────────────┤
│ 领域层  persona / conversation / memory / usermodel      │
│        relationship / state / reflection / proactive     │
│        tool / agent(上下文构建)                            │
├─────────────────────────────────────────────────────────┤
│ 基础层  llm(网关) / config / common / JPA Repository      │
└─────────────────────────────────────────────────────────┘
```

### 3.3 部署拓扑（简要）

```
浏览器 → https://companion.luxera.top
        → nginx(80/443) ─ / → /var/www/companion(SPA 产物)
                        └─ /api → 127.0.0.1:8081 (jar, systemd 常驻)
                                → PostgreSQL :5432（库 companion）
                                → DeepSeek API（HTTPS 出网）
```

---

## 4. 逻辑架构：模块划分与组织

### 4.1 模块全景

```
com.luxera.companion/
├── auth/         用户系统 + JWT 认证
├── persona/      Companion / Persona / PersonaVersion / LifeEvent
│                 PersonaCompiler(LLM编译) / PersonaEvolutionService(演化)
├── conversation/ Conversation / Message / ChatController(SSE)
├── agent/        ★ 运行时核心
│   ├── PerceptionEngine       启发式实时感知
│   ├── PerceptionRefiner      LLM 同步精炼感知
│   ├── WorkingMemory          会话工作记忆
│   ├── ContextBuilder         上下文聚合
│   ├── PromptAssembler        提示词组装
│   ├── CompanionRuntime       一次对话编排
│   ├── NaturalnessEngine      输出质量/自然度
│   ├── AgentPostProcessor     异步后处理
│   └── CompanionSchedule      日常时间表
├── memory/       Memory / MemoryLink / MemoryService / MemoryExtractor
│                 MemoryAssociationService / MemoryDecayService / EmbeddingProvider
├── usermodel/    UserFact/Preference/Pattern/Hypothesis + UserModelService/Extractor
├── relationship/ Relationship / RelationshipEvent / SharedExperience
│                 RelationshipService / RelationshipEngine
├── state/        AgentState + AgentStateService
├── reflection/   ReflectionRecord + ReflectionService + Daily/WeeklyReflectionJob
├── proactive/    Notification + ProactiveEngine + ProactiveDecision + NotificationService
├── tool/         Reminder + ReminderPlanner + ReminderService + BirthdayService
├── llm/          LlmGateway / LlmRouter / OpenAiCompatible / Anthropic / Mock
├── config/       SecurityConfig / JwtUtil / Cors / AppProperties / CurrentUser
└── common/       JsonCodec / ApiError / BusinessException / GlobalExceptionHandler
```

### 4.2 模块依赖关系（方向）

```
agent（运行时） ──依赖──> persona, memory, usermodel, relationship, state, tool, llm, conversation
proactive      ──依赖──> persona, relationship, conversation, tool, llm, notification
reflection     ──依赖──> conversation, memory, usermodel, llm, persona(演化)
各业务模块      ──依赖──> llm(结构化任务), common, config
```

依赖原则：领域模块只通过 service 方法互相调用，不直接访问对方 Repository；llm 是基础设施层，业务模块依赖其统一接口。

---

## 5. 数据架构

### 5.1 ER 总览

```
users 1─* companions 1─* conversations 1─* messages
                     ├─1 persona_versions 1─* （版本链）
                     ├─* companion_life_events
                     ├─1 relationships 1─* relationship_events
                     │                1─* shared_experiences
                     ├─* memories 1─* memory_links（自关联图谱）
                     ├─* user_facts / user_preferences / user_patterns / user_hypotheses
                     ├─1 agent_states
                     ├─* reminders
                     └─* companion_notifications
```

所有核心表强制携带 `user_id` + `companion_id`（多租户隔离，查询强制过滤）。

### 5.2 数据表详细设计（19 表）

**用户域**

| 表 | 关键字段 |
|----|---------|
| `users` (10) | id(UUID), username, password_hash(bcrypt), email, nickname, timezone, birth_date, gender, created_at, updated_at |
| `companions` (13) | id, user_id, name, gender, birth_date, birth_place(JSON), nationality, timezone, greeting, status, deleted_at, created_at, updated_at |
| `persona_versions` (8) | id, companion_id, version, is_active, persona_json(JSON), change_source, change_reason, created_at |
| `companion_life_events` (12) | id, companion_id, type, subtype, title, description, start_time, end_time, importance, emotional_significance, source, created_at |

**对话域**

| 表 | 关键字段 |
|----|---------|
| `conversations` (11) | id, user_id, companion_id, title, started_at, last_message_at, message_count, summary, status, created_at, updated_at |
| `messages` (10) | id, conversation_id, sender_type, content, intent, emotion, topic, is_proactive, metadata(JSON), created_at |

**记忆域**

| 表 | 关键字段 |
|----|---------|
| `memories` (18) | id, user_id, companion_id, type, content, summary, importance, confidence, emotional_weight, relationship_weight, retrieval_count, last_retrieved_at, occurred_at, expires_at, status, source_type, source_id, created_at |
| `memory_links` (6) | id, from_memory_id, to_memory_id, relation(same_topic), strength, created_at |

**用户模型域**

| 表 | 关键字段 |
|----|---------|
| `user_facts` (14) | id, user_id, companion_id, subject, predicate, object, value(JSON), confidence, source_type, source_id, first_observed_at, last_observed_at, status, created_at |
| `user_preferences` (12) | id, user_id, companion_id, category, preference, value(JSON), confidence, source_type, source_id, observed_at, status, created_at |
| `user_patterns` (12) | id, user_id, companion_id, pattern, description, confidence, evidence_count, evidence(JSON), first_observed_at, last_observed_at, status, created_at |
| `user_hypotheses` (10) | id, user_id, companion_id, hypothesis, description, confidence, evidence(JSON), status, created_at, updated_at |

**关系/状态/反思/主动/工具域**

| 表 | 关键字段 |
|----|---------|
| `relationships` (14) | id, user_id, companion_id, relationship_type, relationship_stage, familiarity, trust, intimacy, affection, shared_experience_count, message_count, last_interaction_at, started_at, updated_at |
| `relationship_events` (8) | id, relationship_id, type, title, description, significance, occurred_at, created_at |
| `shared_experiences` (8) | id, relationship_id, type, title, description, importance, occurred_at, created_at |
| `agent_states` (9) | id, companion_id, mood, energy, stress, social_energy, curiosity, emotional_closeness, updated_at |
| `reflection_records` (11) | id, user_id, companion_id, type(daily/weekly), period, summary, insights(JSON), memory_candidates(JSON), user_model_candidates(JSON), relationship_candidates(JSON), created_at |
| `reminders` (10) | id, user_id, companion_id, type(birthday/user_set/check_in), title, content, remind_at, status, payload(JSON), created_at |
| `companion_notifications` (8) | id, user_id, companion_id, type, title, content, is_read, created_at |

> JSON 存储约定：所有 JSON 字段用 `@Convert` + Jackson 转换器存 `text` 列（`common/convert/`），避免 hibernate-types 在 Hibernate 5.6 下对泛型 Map 的兼容问题；未启用 pgvector。

---

## 6. 核心功能详细设计

### 6.1 人格系统

**人格编译（PersonaCompiler）**
```
用户自然语言描述
  → LLM 结构化输出（低温度 + response_format=json_object）
  → Persona POJO：identity / relationship / personality(traits+summary)
                  / communication / behaviors / values / boundaries / life
  → fillDefaults（缺失字段补默认）+ validate
  → 场景预览：以该人格身份在指定场景生成 1-3 句回应（可反复换场景）
```

**版本化（PersonaService）**：每次修改/演化，旧版本 `is_active=false`，新版本 `version+1`，记录 `change_source`（user/evolution）与 `change_reason`。

**身份时间化**：`birth_date` 存库，年龄 = `current_date - birth_date` 动态计算；生日 = 每年一次提醒。

**人生时间线（LifeEvent）**：编译器给出 events 时直接入库；否则按年龄生成默认时间线（小学/中学/大学/第一份工作）。

### 6.2 一次聊天的完整流程（CompanionRuntime）

```
ChatController.chat() 返回 SseEmitter，线程池执行 streamChat()
  1 保存 user 消息（含启发式感知） + 工作记忆记录
  2 PerceptionEngine.perceive()          → 启发式 intent/emotion/topic（毫秒级）
  3 PerceptionRefiner.refineNow()        → 同步 LLM 精炼（质量优先，失败回退）
  4 若 intent=request_tool → ReminderPlanner 建提醒，toolResult 注入上下文
  5 ContextBuilder.build()               → AgentContext 聚合
  6 PromptAssembler.buildSystem()        → 系统提示
  7 LlmRouter.chatStream()               → DeepSeek 流式，逐 token 推送 SSE
  8 NaturalnessEngine.validate()         → 去 AI 套话/模板，整体替换修正
  9 保存 companion 消息 + 工作记忆记录
  10 send("done")
  11 异步 AgentPostProcessor.afterExchange()
      ├─ MemoryExtractor（LLM 抽 episodic/semantic/shared + 建关联）
      ├─ UserModelExtractor（LLM 抽事实/偏好/推测，检测纠正）
      ├─ RelationshipEngine（计数/里程碑/阶段）
      └─ AgentStateService（状态微演化）
```

**SSE 协议**：`event: meta`(intent/emotion/topic) → `event: token`(delta) → `event: replace`(整段替换，当自然度修正时) → `event: done`(messageId) → `event: error`。

### 6.3 感知引擎

| 层 | 实现 | 用途 |
|----|------|------|
| 启发式（PerceptionEngine） | 关键词规则映射 12 类 intent / 8 类 emotion / 8 类 topic | 秒级兜底，保证首包快 |
| LLM 同步精炼（PerceptionRefiner） | `structured("perception")` 输出 intent/emotion/topic/entities | 质量优先，情绪/话题/实体更准 |

精炼结果写入：用户消息元数据（intent/emotion/topic）、工作记忆（含实体）、供 Prompt 使用。

### 6.4 工作记忆（WorkingMemory）

- 会话级 `companionId:conversationId → Entry{recent(≤12条), currentTopic, currentIntent, currentEmotion, currentEntities}`。
- **TTL 过期**（`working-memory-ttl-minutes: 720`）自动失效。
- 注入 Prompt 的"当前会话状态"块。
- 内存 `ConcurrentHashMap` 实现（接口可替换 Redis）。

### 6.5 上下文构建与提示词组装

**ContextBuilder** 按优先级聚合：
```
1 当前对话（recent messages ≤40）
2 人格（PersonaText.describe：身份/性格/沟通/行为/价值观/边界）
3 时间 + 日常时间表（"现在是周四 15:00，小满正在忙工作"）
4 Agent 状态（心情/精力/压力）
5 工作记忆（话题/情绪/意图/实体）
6 工具结果（刚创建的提醒）
7 关系摘要（阶段/认识天数/消息数/共同经历数）
8 记忆（Top-N，按强度排序，关联扩展）
9 用户模型（事实/偏好/习惯/推测，标注置信度）
10 行为准则（自然口语、2-4句、先陪伴后建议、人格一致、可不同意见）
```

**原则**：数据库是源，Prompt 是运行时投影；不塞全量历史（只最近消息 + 检索记忆）。

### 6.6 记忆系统

**抽取**（MemoryExtractor，每轮异步）：
```
LLM structured("memory-extraction") → {episodic[], semantic[], shared[]}
  → saveBatch：同 content 去重（max(importance)，retrieval_count+1 = 强化）
  → linkBatch（同批互链）+ linkNewMemory（与历史记忆建链）
```

**检索**（MemoryService.retrieve）：
```
候选 = 关键词检索 ∪ (query空时全部) ∪ EmbeddingProvider(Noop)
排序强度 = importance × confidence × recency_decay
           × emotional_weight × relationship_weight
           × (1 + 0.5·log(retrieval_count+1))
  其中 recency_decay = 0.92^天数
取 topN(≥ memory-min-strength 0.02) → 强化（count+1, last_retrieved_at）
→ 关联扩展（沿 memory_links 取邻居，封顶 2N）
```

**关联记忆**（MemoryAssociationService）：无向量库，用**中文二元组重叠比**近似语义相似（阈值 0.3，双向建链，relation=same_topic）。

**衰减**（MemoryDecayService，每周一 04:30）：`occurred_at < now-180天 && importance<0.4 && retrieval_count<3` → 状态 archived。

**透明**（"为什么你知道"）：`/memories/why?q=` 命中记忆 + 定位来源对话（`source_id`=conversationId，按 `occurred_at` 取 ±2 条摘录）。

**控制**：搜索/遗忘单条/清空/导出 JSON。

### 6.7 用户模型

| 类型 | 置信 | 来源 | 用途 |
|------|------|------|------|
| UserFact | 高（≥0.85） | 明确告知 | 作为事实 |
| UserPreference | 中高 | 明确/推断 | 沟通/行为偏好 |
| UserPattern | 随证据增 | 观察统计（evidence_count） | 行为模式 |
| UserHypothesis | 低（0.5±） | 推断 + evidence | 仅供 Prompt 标注"可能是" |

**纠正机制**：LLM 抽取检测到"不是/其实/你错了" → `UserModelService.correct()` 将相关推测置信度 -0.25（下限 0.1）+ 写入新 explicit fact + 记录"你纠正了她"关系事件。

### 6.8 关系引擎

`RelationshipEngine.onMessage`：
- 数值微增：familiarity+0.0012 / trust+0.0006 / intimacy+0.0004 / affection+0.0005（缓慢，避免"聊几次就熟"）。
- **里程碑**（一次性，significance≥0.8 同时写入 shared_experiences）：
  `first_conversation / first_late_night(23-3点) / first_emotional_support(sad) / first_joy_shared(share_joy) / first_care_about_her(ask_about_her) / first_plan_together(planning)`。
- **阶段机**：`new→familiar→close→deeply_connected`，由消息数与关系数值综合判定（避免纯计数）。

### 6.9 Agent 状态

`AgentStateService.onMessage`：energy-0.004 / stress+0.001 / socialEnergy-0.002 / emotionalCloseness+0.0009 / mood 按情绪映射（sad→"有点心疼"等）。单行/伴侣，`updated_at` 时间戳。

### 6.10 反思引擎（ReflectionService）

**每日反思**（03:17）：
```
规则层：统计近7天深夜消息数 → 写 user_patterns（user_often_works_late）
LLM层：当天对话摘录(≤3000字) → structured("daily-reflection")
  → summary / insights / memory_candidates→入库 / user_insights / relationship_candidates
```

**每周反思**（周一 05:00）：
```
近7天对话(≤5000字) → structured("weekly-reflection")
  → summary / long_term_user_understanding / behavioral_patterns→userModel 入库 / relationship_changes
```

### 6.11 人格演化（PersonaEvolutionService）

```
每周反思后触发：
  证据 = 用户模型摘要 + 关系数值
  LLM structured("persona-evolution") → {adjustments:[{field,delta,reason}]}
  限幅：|delta|≤0.05，每次≤3处，traits∈[0.1,0.95]
  有改动 → personaService.update(changeSource="evolution") 生成新版本
约束：values/boundaries/身份 不可变
```

### 6.12 主动消息引擎（ProactiveEngine + ProactiveDecision）

```
每15分钟：
  1 到期 Reminder → 转 Notification（提醒优先，不受打扰控制）
  2 每伴侣过滤：DND(23-8) / 作息=SLEEP / 最小间隔(1h) / 每日上限(5)
  3 决策 decide()：按优先级评估触发
     late_work(深夜加班) / morning_greeting(8-11,今天没聊过)
     evening_checkin(17-22,聊过但2-8h没动静) / follow_up_joy(好消息48h内)
     silence(熟悉度>0.15且2天没联系)
     每个触发给 expected_value × 作息factor(忙碌0.35/休闲1.2)
  4 打断成本 cost = 0.15 + 深夜(0.4) + 22点后(0.1) + 4h内聊过(0.35)
                  ± 响应率(±0.1) + 今日已达上限(0.3)
  5 cost ≥ expected_value → DO_NOTHING；否则发送
  6 发送内容 = LLM 按"人格+当前作息+场景"生成（draftMessage，失败回退模板）
     写入 Notification + 注入最新会话（is_proactive=true）
```

### 6.13 日常时间表（CompanionSchedule）

```
按 companionId 确定性派生（同一个人每次一致）：
  上班 8-10点（hash 决定） / 下班 17-19点 / 睡觉 23-24点 / 起床 6-7点
活动分类：SLEEP / MORNING / WORK_BUSY / LUNCH / WORK_AFTERNOON
         / EVENING / LEISURE / LATE_NIGHT
周末：睡到自然醒 + 全天休闲（无工作档）
用途：① 主动消息因子（忙碌0.35/休闲1.2/睡觉0）② Prompt 注入"此刻在做什么"
```

### 6.14 工具与提醒

- **ReminderPlanner（聊天内建提醒）**：intent=request_tool 时，LLM 解析 `{remind, title, content, remind_at}`；注入"今天日期"防止时间幻觉；过去时间兜底 +1h；建提醒后 toolResult 进 Prompt，回复自然确认。
- **BirthdayService**（每日 08:05）：确保每位伴侣有下一年待触发生日提醒。
- **ProactiveEngine** 兜底：到期提醒统一转 Notification。

### 6.15 自然度与输出质量（NaturalnessEngine）

规则检测并修复：
- AI 套话（"作为AI/我的训练数据/我理解你的感受"等 13 类）→ 直接剔除
- 模板安慰（"一切都会好起来的/别想太多"）→ 标记
- 说教式建议（"你应该/建议你每天"）→ 标记
- 过度道歉（≥2 次）→ 标记
- 过度 emoji（>3 个）→ 标记
- 回应过长（>500 字）→ 标记
修正后文本经 SSE `replace` 事件整体替换。

---

## 7. 接口设计（API 清单）

### 认证
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/auth/register | 注册（返回 JWT） |
| POST | /api/auth/login | 登录 |
| GET | /api/auth/me | 当前用户 |

### 伴侣 / 人格
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/companions/compile | 自然语言→编译人格+预览 |
| POST | /api/companions/preview | 任意场景预览 |
| GET | /api/companions | 伴侣列表 |
| POST | /api/companions | 创建伴侣 |
| GET | /api/companions/{id} | 伴侣详情（含动态年龄/下个生日/人格） |
| DELETE | /api/companions/{id} | 删除 |
| PUT | /api/companions/{id}/persona | 重新描述→新版本人格 |
| GET | /api/companions/{id}/life-events | 人生时间线 |
| GET | /api/companions/{id}/persona/versions | 人格版本历史 |

### 会话 / 聊天
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/companions/{cid}/conversations | 会话列表 |
| POST | /api/companions/{cid}/conversations/first | 首个会话（含问候） |
| POST | /api/companions/{cid}/conversations | 新建会话 |
| GET | /api/companions/{cid}/conversations/{id}/messages | 消息列表 |
| POST | /api/companions/{cid}/conversations/{id}/chat | **SSE 流式聊天** |

### 记忆
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/companions/{cid}/memories | 记忆列表 |
| GET | /api/companions/{cid}/memories/search?q= | 检索 |
| GET | /api/companions/{cid}/memories/export | 导出 JSON |
| GET | /api/companions/{cid}/memories/graph | 记忆图谱 |
| GET | /api/companions/{cid}/memories/why?q= | 为什么你知道（含来源） |
| GET | /api/companions/{cid}/memories/{id}/source | 单条记忆来源摘录 |
| DELETE | /api/companions/{cid}/memories/{id} | 遗忘 |
| DELETE | /api/companions/{cid}/memories | 清空 |

### 用户模型 / 关系 / 状态 / 提醒 / 通知 / 反思
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/companions/{cid}/user-model/{facts,preferences,patterns,hypotheses} | 她懂你 |
| DELETE | /api/companions/{cid}/user-model/clear | 清空了解 |
| GET | /api/companions/{cid}/relationship | 关系+事件+共同经历+状态 |
| GET | /api/companions/{cid}/relationship/{events,shared-experiences} | 明细 |
| GET | /api/companions/{cid}/state | Agent 状态 |
| GET/POST | /api/companions/{cid}/reminders | 提醒列表/创建 |
| PUT/DELETE | /api/companions/{cid}/reminders/{id}/done 等 | 完成/删除 |
| GET | /api/companions/{cid}/notifications | 通知列表 |
| PUT | /api/companions/{cid}/notifications/{id}/read · /read-all | 已读 |
| GET | /api/companions/{cid}/notifications/unread-count | 未读数 |
| GET | /api/companions/{cid}/reflections | 反思记录 |

### 管理（验收/运维）
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/admin/reflection/run | 手动每日反思 |
| POST | /api/admin/reflection/run-weekly | 手动每周反思 |
| POST | /api/admin/persona/evolve | 手动人格演化 |
| POST | /api/admin/proactive/run | 手动主动消息 |
| POST | /api/admin/birthday/ensure | 补生日提醒 |

---

## 8. 非功能设计

### 8.1 性能
- 聊天主链路：启发式感知（毫秒）+ LLM 感知（~1-2s）+ 回复流式（首 token 快）；异步后处理不阻塞响应。
- 记忆检索：结构化 SQL + 内存排序，单伴侣数据量下 <10ms。
- 定时任务：每日/每周反思、每 15 分钟主动评估，均异步线程池。

### 8.2 安全
- 密码 bcrypt；JWT(HS256) 7 天过期；无状态会话。
- 多租户：所有查询强制 `user_id + companion_id`；伴侣/记忆/关系删除前校验归属。
- 敏感配置（DeepSeek key）在 `/etc/companion/.env`（root 640），不入 git。
- CORS 白名单；生产走 HTTPS（nginx + 泛域名证书）。

### 8.3 可扩展性
- LLM 网关接口化 → 换模型改配置。
- EmbeddingProvider 接口 → 接向量库即插即用。
- WorkingMemory 接口可换 Redis（多实例共享）。
- 模块化单体 → 业务边界清晰，可拆微服务。

### 8.4 可观测性
- systemd journal 日志；`[LLM] 网关已启用` 标识当前模型（Mock vs 真实，监控降级）。
- 消息元数据存 intent/emotion/topic；自然度校验 issues 记日志。
- 主动消息决策日志（触发/预期 vs 成本）。

---

## 9. 关键技术决策与权衡

| 决策 | 权衡 |
|------|------|
| 模块化单体 vs 微服务 | 单体部署简单、事务强；边界已切分可拆 |
| JSON 存 text 而非 jsonb | 规避 hibernate-types 兼容问题；牺牲 JSON 内查询（用独立列覆盖） |
| 无向量库，用二元组重叠 | 规避 pgvector 依赖；语义精度有限但够用，可无缝升级 |
| 感知启发式 + LLM 同步精炼 | 质量优先（用户接受延迟）；失败回退保证可用 |
| 异步后处理 | 不阻塞 SSE 响应；抽取/关系/状态后台沉淀 |
| 人格演化保守（±0.05） | 防人格漂移；版本可回溯 |
| 主动消息打断成本 | 防打扰；多触发按时段/作息调节 |

---

## 10. 测试与验证

- **冒烟**（scripts/smoke.sh）：注册→编译→创建→问候→SSE 聊天→记忆→关系→生日→反思→搜索，全链路通过。
- **真实模型验证**：回复 2-4 句多样、提醒时间正确、记忆图谱建链、记忆透明带来源、LLM 反思有洞察、人格演化在跑、主动消息按时段 LLM 生成、时间表体现作息（周六回复"窝在沙发喝茶"）。
- **已修 Bug**：LLM 时间幻觉（年份错）→ 注入当前日期+兜底；部署脚本丢失 EnvironmentFile 导致降级 Mock；HQL `:type` 保留字；hibernate-types JSONB 泛型；`去`字误判 planning 意图。

---

## 11. 已知限制与演进路线

### 限制（如实）
1. 无向量检索（二元组近似）；2. WorkingMemory 单实例内存；3. 无手机推送；4. 工具层仅提醒（无 MCP）；5. 无多模态/语音；6. 单机无高可用；7. 认证无邮箱验证/OAuth；8. 多租户应用层过滤；9. 反思/演化批量定时；10. 当前数据为测试数据；11. DeepSeek key 失效降级 Mock。

### 演进
- 短期：手机推送、向量检索、MCP 工具层（日历/天气/搜索）。
- 中期：语音对话、用户自定义作息注入、真实用户灰度。
- 长期：V4 多端共享记忆/身份、K8s 高可用、RLS 加固、审计日志。

---

## 12. 附录

- 后端：Spring Boot 2.7.18 / JDK17，116 个 Java 类，14 个业务模块。
- 前端：React 19 / Vite 8 / TS strict / Tailwind 3 / Zustand 5。
- 部署：nginx(80/443) + systemd jar(8081) + PostgreSQL(5432)。
- 代码：GitHub `Hojay-Chen/companion-agent`，6 个提交。
