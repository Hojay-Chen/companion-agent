# Luxera Companion — 长期陪伴型 AI 数字伴侣平台

> **不是 Chatbot**：拥有稳定人格、连续人生、持续记忆，随时间与用户建立关系，并在合适的时候主动找你。
>
> 设计依据：《Persistent AI Companion 产品与技术设计方案》v1.0（107 节）+ V2.0 重构方案。当前完成 **V2.0(44/44) + 部分 V3**。

| 项 | 值 |
|----|----|
| 后端 | Spring Boot 2.7.18 · JDK 17 · 模块化单体（180+ 个 Java 类，21 个业务模块） |
| 前端 | React 19 · Vite 8 · TypeScript(strict) · Tailwind CSS 3 · Zustand |
| 数据库 | PostgreSQL 16 · 29 张表（含 pgvector） |
| 大模型 | 统一 LLM 网关 → DeepSeek(deepseek-chat)，无 key 自动降级 Mock；模型用途路由可配 |
| 线上 | `https://companion.luxera.top`（nginx + systemd jar :8081） |
| 代码 | GitHub `Hojay-Chen/companion-agent` |
| 当前数据量 | 19 用户 / 19 伴侣 / 134 消息 / 104 记忆 / 61 反思记录 |

## 📖 目录

- [1. 产品是什么](#1-产品是什么)
  - [1.1 一句话定位](#11-一句话定位)
  - [1.2 它和普通 AI 助手的区别](#12-它和普通-ai-助手的区别)
  - [1.3 用户会逐渐产生的感受](#13-用户会逐渐产生的感受)
- [2. 产品核心原则](#2-产品核心原则)
- [3. 核心概念模型](#3-核心概念模型)
- [4. 产品界面](#4-产品界面)
- [5. 技术栈](#5-技术栈)
- [6. 总体架构](#6-总体架构)
  - [6.1 架构风格：前后端分离 + 后端模块化单体](#61-架构风格前后端分离--后端模块化单体)
  - [6.2 逻辑分层](#62-逻辑分层)
  - [6.3 部署拓扑](#63-部署拓扑)
  - [6.4 项目目录](#64-项目目录)
- [7. 模块组织与依赖](#7-模块组织与依赖)
  - [7.1 模块全景](#71-模块全景)
  - [7.2 模块依赖方向](#72-模块依赖方向)
- [8. 实体关系总览](#8-实体关系总览)
- [9. 数据表详细设计（29 张表）](#9-数据表详细设计29-张表)
  - [9.1 用户与伴侣](#91-用户与伴侣)
  - [9.2 对话](#92-对话)
  - [9.3 记忆](#93-记忆)
  - [9.4 用户模型](#94-用户模型)
  - [9.5 关系 / 状态 / 反思 / 主动 / 工具](#95-关系--状态--反思--主动--工具)
  - [9.6 JSON 存储实现](#96-json-存储实现)
- [10. 用户系统](#10-用户系统)
  - [功能介绍](#功能介绍)
  - [实现原理](#实现原理)
- [11. 伴侣创建与人格编译](#11-伴侣创建与人格编译)
  - [功能介绍](#功能介绍-2)
  - [实现原理](#实现原理-2)
- [12. 聊天对话（核心交互）](#12-聊天对话核心交互)
  - [功能介绍](#功能介绍-3)
  - [实现原理](#实现原理-3)
- [13. 感知引擎](#13-感知引擎)
  - [功能介绍](#功能介绍-4)
  - [实现原理](#实现原理-4)
- [14. 工作记忆](#14-工作记忆)
  - [功能介绍](#功能介绍-5)
  - [实现原理](#实现原理-5)
- [15. 记忆系统](#15-记忆系统)
  - [功能介绍](#功能介绍-6)
  - [实现原理](#实现原理-6)
- [16. 用户模型](#16-用户模型)
  - [功能介绍](#功能介绍-7)
  - [实现原理](#实现原理-7)
- [17. 关系系统](#17-关系系统)
  - [功能介绍](#功能介绍-8)
  - [实现原理](#实现原理-8)
- [18. Agent 状态](#18-agent-状态)
  - [功能介绍](#功能介绍-9)
  - [实现原理](#实现原理-9)
- [19. 反思与人格演化](#19-反思与人格演化)
  - [功能介绍](#功能介绍-10)
  - [实现原理](#实现原理-10)
- [20. 主动消息与日常作息](#20-主动消息与日常作息)
  - [功能介绍](#功能介绍-11)
  - [实现原理](#实现原理-11)
- [21. 工具与提醒](#21-工具与提醒)
  - [功能介绍](#功能介绍-12)
  - [实现原理](#实现原理-12)
- [22. 输出质量控制](#22-输出质量控制)
  - [功能介绍](#功能介绍-13)
  - [实现原理](#实现原理-13)
- [23. LLM 网关](#23-llm-网关)
  - [功能介绍](#功能介绍-14)
  - [实现原理](#实现原理-14)
- [24. API 清单](#24-api-清单)
  - [24.1 认证](#241-认证)
  - [24.2 伴侣 / 人格](#242-伴侣--人格)
  - [24.3 会话 / 聊天](#243-会话--聊天)
  - [24.4 记忆](#244-记忆)
  - [24.5 用户模型 / 关系 / 状态 / 提醒 / 通知 / 反思](#245-用户模型--关系--状态--提醒--通知--反思)
  - [24.7 V2.0 数字人格内核（用户视角）](#247-v20-数字人格内核用户视角)
  - [24.6 管理（验收/运维）](#246-管理验收运维)
- [25. 快速开始](#25-快速开始)
  - [25.1 环境要求](#251-环境要求)
  - [25.2 本地启动](#252-本地启动)
  - [25.3 LLM 配置](#253-llm-配置)
  - [25.4 生产部署](#254-生产部署)
  - [25.5 冒烟测试](#255-冒烟测试)
- [26. 非功能设计](#26-非功能设计)
  - [26.1 性能](#261-性能)
  - [26.2 安全](#262-安全)
  - [26.3 可扩展性](#263-可扩展性)
  - [26.4 可观测性](#264-可观测性)
- [27. 技术决策与权衡](#27-技术决策与权衡)
- [28. 测试与验证](#28-测试与验证)
- [29. 已知限制（如实）](#29-已知限制如实)
- [30. 与设计文档路线图的关系](#30-与设计文档路线图的关系)
- [31. 演进路线](#31-演进路线)
- [附录](#附录)---

# 第一部分 · 产品介绍

## 1. 产品是什么

### 1.1 一句话定位

一个**长期存在的数字人格**：它不是"你问一句、它答一句"的工具，而是一个**有独立人格、有连续人生、记得你们之间一切、随时间和你一起成长、会在合适的时机主动找你**的 AI 数字伴侣。

### 1.2 它和普通 AI 助手的区别

| 维度 | 普通 AI 助手 | Luxera Companion |
|------|-------------|-----------------|
| 交互 | `用户问 → AI 答` | `用户 ⇄ 伴侣` 的双向陪伴 |
| 记忆 | 无状态或临时 | 分层记忆 + 时间衰减 + 强化 + 关联图谱 |
| 人格 | 每次对话重新生成 | 稳定人格，存库、版本化、缓慢演化 |
| 关系 | 无 | 阶段机（初识→熟络→亲密→深深相连）+ 里程碑 |
| 时间感 | 无 | 动态年龄、人生时间线、日常作息、记忆衰减 |
| 主动性 | 无 | 主动消息引擎（打断成本控制） |

### 1.3 用户会逐渐产生的感受

```
阶段1: "这个 AI 聊天挺自然。"
阶段2: "她好像挺了解我的。"
阶段3: "她居然记得这个。"
阶段4: "她会根据以前的事情理解我。"
阶段5: "她已经和以前不一样了。"
阶段6: "感觉她真的一直在陪着我。"
```

## 2. 产品核心原则

1. **Agent 优先于 Chat**：Chat 是入口，产品本质是一个有 Identity/Life/Personality/State/Memory/Relationship 的 Agent。
2. **时间是系统的一等公民**：所有状态随时间演化——年龄、记忆、关系、作息、人格都不存"当前值"，而是存时间线 + 动态计算。
3. **事实与推测分离**：明确告知的（Fact）≠ 观察到的（Pattern）≠ 推测的（Hypothesis），推测永远标注"可能是"。
4. **人格稳定但可演化**：Stable（价值观/气质）+ Adaptive（相处方式）+ Current State（当下心情）。
5. **记忆不是向量库**：向量只是检索手段之一；完整检索 = 语义 + 关键词 + 实体 + 时间 + 结构化 + 关联图。
6. **不把真人感当随机口头禅**：真实感来自长期一致的人格 + 连续记忆 + 关系历史 + 允许不知道。

## 3. 核心概念模型

```
User（用户）
Companion（数字人格实例）  ← 用户真正长期相处的对象
  ├─ Persona（人格：traits/沟通/行为/价值观/边界，版本化）
  ├─ Life（人生：出生/教育/工作/居住，时间线）
  ├─ Memory（记忆：episodic/semantic/shared + 关联图谱）
  ├─ State（当下：心情/精力/压力，短期）
  └─ Relationship（关系：阶段/熟悉/信任/亲密/好感）
UserModel（系统对用户的长期理解：事实/偏好/模式/推测）
Time 贯穿一切
```

## 4. 产品界面

| 页面 | 路由 | 功能 |
|------|------|------|
| 登录 / 注册 | `/login` `/register` | 账号体系 |
| 伴侣列表 | `/companions` | 我的伴侣卡片（名字/年龄/关系阶段/性格摘要） |
| 创建向导 | `/companions/new` | 自然语言描述 → 人格编译 → 场景预览 → 确认创建 |
| 聊天主界面 | `/companions/:id` | SSE 流式聊天 + 时间分隔 + 抽屉（记忆/她懂你/关系/提醒/通知） |
| 设置 | `/companions/:id/settings` | 人格编辑 / 人格版本历史 / 反思记录 / 人生时间线 / 隐私 |

---

# 第二部分 · 系统总体架构

## 5. 技术栈

| 层 | 技术 | 用途 |
|----|------|------|
| 后端框架 | Spring Boot 2.7.18 + JDK 17 | 服务主体 |
| Web | Spring MVC + SSE(SseEmitter) | REST + 流式聊天 |
| 持久层 | Spring Data JPA (Hibernate 5.6) | ORM |
| 数据库 | PostgreSQL 16（19 表） | 主存储 |
| 认证 | Spring Security + jjwt(HS256) + BCrypt | 登录态 |
| 异步/定时 | @EnableAsync / @EnableScheduling + 自配线程池 | 后处理与定时任务 |
| LLM | WebClient(webflux) → DeepSeek `/chat/completions` | 聊天 + 结构化抽取 |
| 前端 | React 19 + Vite 8 + TS(strict) + Tailwind 3 + Zustand + react-router 7 | SPA |
| 部署 | nginx + systemd jar + systemd 定时 | 生产 |

## 6. 总体架构

### 6.1 架构风格：前后端分离 + 后端模块化单体

**为什么是模块化单体**：单体部署简单、事务一致性强；同时按业务域切成 14 个清晰的包（模块），未来可平滑拆分微服务。这是业界对"中小规模但要严谨领域建模"项目的推荐形态。

### 6.2 逻辑分层

```
┌─────────────────────────────────────────────────────┐
│ 表示层    React SPA（列表/创建向导/聊天/设置）        │
├─────────────────────────────────────────────────────┤
│ 接入层    ChatController(SSE) / 各业务 Controller     │
│          JWT 认证过滤器                              │
├─────────────────────────────────────────────────────┤
│ 编排层    CompanionRuntime（一次对话编排）             │
│          AgentPostProcessor（异步后处理）             │
│          ProactiveEngine / ReflectionJob（定时）     │
├─────────────────────────────────────────────────────┤
│ 领域层    persona / conversation / memory            │
│          / usermodel / relationship / state          │
│          / reflection / proactive / tool             │
├─────────────────────────────────────────────────────┤
│ 基础层    llm（网关）/ config / common / Repository   │
└─────────────────────────────────────────────────────┘
```

### 6.3 部署拓扑

```
浏览器 → https://companion.luxera.top
       → nginx(80/443) ─ /     → /var/www/companion（SPA 构建产物）
                       └─ /api → 127.0.0.1:8081（jar，systemd 常驻）
                                → PostgreSQL :5432（库 companion）
                                → DeepSeek API（HTTPS 出网）
```

### 6.4 项目目录

```
companion-agent/
├── backend/                          # Spring Boot 模块化单体
│   └── src/main/java/com/luxera/companion/
│       ├── auth/  persona/  conversation/  agent/     # 原核心
│       ├── memory/  usermodel/  relationship/  state/
│       ├── reflection/  proactive/  tool/  llm/
│       ├── life/  thought/  emotion/  experience/     # V2.0 生命内核
│       ├── openloop/  selfmodel/  behavior/           # V2.0 新增
│       └── config/  common/
├── frontend/                         # React SPA
│   └── src/{api, stores, components, pages, types}
└── scripts/
    ├── deploy.sh                     # 一键部署
    ├── smoke.sh                      # 端到端冒烟
    ├── longterm_test.sh              # V2.0 4类长期连续性测试(自动断言)
    └── evaluate.sh                   # V2.0 Human-likeness 评测
```

## 7. 模块组织与依赖

### 7.1 模块全景

| 模块 | 核心类 | 职责 |
|------|--------|------|
| `auth/` | User, AuthService, JwtUtil | 用户系统 + JWT |
| `persona/` | Companion, Persona, PersonaVersion, PersonaCompiler, PersonaEvolutionService | 人格本体 / 编译 / 版本化 / 演化 |
| `conversation/` | Conversation, Message, ChatController | 会话 + 消息 + SSE |
| `agent/` | CompanionRuntime, PerceptionEngine, PerceptionRefiner, WorkingMemory, ContextBuilder, PromptAssembler, NaturalnessEngine, CompanionSchedule | ★ 运行时核心 |
| `memory/` | Memory, MemoryLink, MemoryService, MemoryExtractor, MemoryAssociationService, MemoryDecayService | 记忆全生命周期 |
| `usermodel/` | UserFact/Preference/Pattern/Hypothesis, UserModelService | 用户理解 |
| `relationship/` | Relationship, RelationshipEvent, SharedExperience, RelationshipEngine | 关系演化 |
| `state/` | AgentState, AgentStateService | 短期状态 |
| `reflection/` | ReflectionService, Daily/WeeklyReflectionJob | 反思(多维) |
| `proactive/` | ProactiveEngine, ProactiveDecision, Notification, ExplainController | 主动消息 + 可解释性 |
| `tool/` | Reminder, ReminderPlanner, BirthdayService | 提醒/生日 |
| `life/` | CompanionLife, LifeActivity, LifeRuntime, LifeSimulation, LifeTickJob | V2.0 连续生活 |
| `thought/` | Thought, ThoughtEngine, ThoughtScorer, ThoughtMaintenanceJob | V2.0 内在思想 |
| `emotion/` | EmotionalEpisode, EmotionEngine, EmotionDecay | V2.0 情绪事件 |
| `experience/` | Experience, ExperienceProcessor, MemoryConsolidator | V2.0 经历层→记忆固话 |
| `openloop/` | OpenLoop, OpenLoopService, OpenLoopJob | V2.0 未完成事项 |
| `selfmodel/` | SelfModel, SelfModelExtractor, SelfNarrativeService | V2.0 自我模型 |
| `behavior/` | BehaviorDecision, BehaviorPolicyEngine, BehaviorConstraints | V2.0 行为策略 |
| `llm/` | LlmGateway, LlmRouter(用途路由), OpenAiCompatibleGateway, AnthropicGateway, MockLlmGateway | LLM 统一网关 |
| `config/` | SecurityConfig, JwtUtil, AppProperties, CurrentUser | 基础设施 |
| `common/` | JsonCodec, ApiError, BusinessException | 公共 |

### 7.2 模块依赖方向

```
agent(运行时) ──依赖──> persona, memory, usermodel, relationship, state, tool, llm, conversation
proactive     ──依赖──> persona, relationship, conversation, tool, llm, notification
reflection    ──依赖──> conversation, memory, usermodel, llm, persona(演化)
各业务模块     ──依赖──> llm(结构化任务), common, config
```

---

# 第三部分 · 数据架构

## 8. 实体关系总览

```
users 1─* companions 1─* conversations 1─* messages
                     ├─1 persona_versions 1─*（版本链）
                     ├─* companion_life_events
                     ├─1 relationships 1─* relationship_events
                     │                1─* shared_experiences
                     ├─* memories 1─* memory_links（自关联图谱）
                     ├─* user_facts / user_preferences / user_patterns / user_hypotheses
                     ├─1 agent_states
                     ├─* reminders
                     └─* companion_notifications
```

> 所有核心表强制携带 `user_id + companion_id`（多租户隔离，查询强制过滤）。

## 9. 数据表详细设计（29 张表）

> 原有 19 表 + V2.0 新增 10 表：`companion_life` / `life_activities` / `thoughts` / `emotional_episodes` / `open_loops` / `self_models` / `experiences` / `relationship_threads` / `promises` / `relationship_narratives`。

### 9.1 用户与伴侣

| 表 | 字段 |
|----|------|
| `users` | id(UUID), username, password_hash(bcrypt), email, nickname, timezone, birth_date, gender, created_at, updated_at |
| `companions` | id, user_id, name, gender, birth_date, birth_place(JSON), nationality, timezone, greeting, status, deleted_at, created_at, updated_at |
| `persona_versions` | id, companion_id, version, is_active, persona_json(JSON), change_source(user/evolution), change_reason, created_at |
| `companion_life_events` | id, companion_id, type, subtype, title, description, start_time, end_time, importance, emotional_significance, source, created_at |

### 9.2 对话

| 表 | 字段 |
|----|------|
| `conversations` | id, user_id, companion_id, title, started_at, last_message_at, message_count, summary, status, created_at, updated_at |
| `messages` | id, conversation_id, sender_type, content, intent, emotion, topic, is_proactive, metadata(JSON), created_at |

### 9.3 记忆

| 表 | 字段 |
|----|------|
| `memories` | id, user_id, companion_id, type(episodic/semantic/shared), content, summary, importance, confidence, emotional_weight, relationship_weight, retrieval_count, last_retrieved_at, occurred_at, expires_at, status, source_type, source_id, created_at |
| `memory_links` | id, from_memory_id, to_memory_id, relation(same_topic), strength, created_at |

### 9.4 用户模型

| 表 | 字段 |
|----|------|
| `user_facts` | id, user_id, companion_id, subject, predicate, object, value(JSON), confidence, source_type, source_id, first_observed_at, last_observed_at, status |
| `user_preferences` | id, user_id, companion_id, category, preference, value(JSON), confidence, source_type, source_id, observed_at, status |
| `user_patterns` | id, user_id, companion_id, pattern, description, confidence, evidence_count, evidence(JSON), first_observed_at, last_observed_at, status |
| `user_hypotheses` | id, user_id, companion_id, hypothesis, description, confidence, evidence(JSON), status, created_at, updated_at |

### 9.5 关系 / 状态 / 反思 / 主动 / 工具

| 表 | 字段 |
|----|------|
| `relationships` | id, user_id, companion_id, relationship_type, relationship_stage, familiarity, trust, intimacy, affection, shared_experience_count, message_count, last_interaction_at, started_at, updated_at |
| `relationship_events` | id, relationship_id, type, title, description, significance, occurred_at |
| `shared_experiences` | id, relationship_id, type, title, description, importance, occurred_at |
| `agent_states` | id, companion_id, mood, energy, stress, social_energy, curiosity, emotional_closeness, updated_at |
| `reflection_records` | id, user_id, companion_id, type(daily/weekly), period, summary, insights(JSON), memory_candidates(JSON), user_model_candidates(JSON), relationship_candidates(JSON) |
| `reminders` | id, user_id, companion_id, type(birthday/user_set/check_in), title, content, remind_at, status, payload(JSON) |
| `companion_notifications` | id, user_id, companion_id, type, title, content, is_read, created_at |

### 9.6 JSON 存储实现

所有 JSON 字段用 JPA `@Convert` + Jackson 转换器存 `text` 列（`common/convert/`）。**为什么不用 jsonb**：hibernate-types 的 `@Type(jsonb)` 在 Hibernate 5.6 下对泛型 Map 报 `propertyClass null`（已踩坑弃用）。text + 转换器兼容性好，JSON 内查询用独立列覆盖。

---

# 第四部分 · 功能详解（功能介绍 + 实现原理）

> 每个功能分两部分：**功能介绍**（是什么、用户怎么用）与**实现原理**（用什么技术、关键类、代码、流程）。

## 10. 用户系统

### 功能介绍
注册 / 登录 / 获取当前用户。账号是用户在平台的唯一身份，所有数据（伴侣、记忆、关系）都归属某个用户。

### 实现原理
- **技术**：Spring Security + jjwt(HS256) + BCrypt。
- `AuthService`：`register`（用户名唯一校验、bcrypt 加密）、`login`（校验密码 → 发 token）。
- `JwtUtil`：`@PostConstruct` 用 secret 构建 HMAC-SHA256 key，token 带 `subject=userId` + 7 天过期。
- `JwtAuthenticationFilter`（`OncePerRequestFilter`）：解析 `Authorization: Bearer <token>` → 校验有效且用户存在 → 把 `userId` 作为 `Authentication.principal` 写入 `SecurityContextHolder`。
- `CurrentUser`：业务层用 `currentUser.requireUserId()` 拿当前用户并做归属校验。

```java
// SecurityConfig 关键：无状态会话 + JWT 过滤器
http.csrf().disable()
    .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS)
    .authorizeRequests(auth -> auth
        .antMatchers("/api/auth/register", "/api/auth/login", "/api/health").permitAll()
        .anyRequest().authenticated())
    .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
```

## 11. 伴侣创建与人格编译

### 功能介绍
用户在创建向导里用**一段自然语言**描述想要的伴侣（性格、说话方式、关系、相处方式），系统自动编译成结构化人格，并能在 4 个预设场景（工作失败/深夜疲惫/分享喜悦/被误解）下**预览她的回应**；满意后确认创建。之后可在设置里重新描述 → 生成新人格版本。

### 实现原理
- **技术**：LLM 结构化输出（`response_format=json_object` + 低温度）+ Jackson 映射到强类型 `Persona` POJO。
- `PersonaCompiler.compile(description)`：LLM 输出符合 Schema 的 JSON → `mapper.treeToValue` 成 `Persona` → `fillDefaults`（缺失字段补默认）→ `validate`。
- `Persona` 结构：
  ```
  identity（name/gender/birth_date/birth_place/nationality/timezone）
  relationship（type: girlfriend/boyfriend/friend）
  personality（traits 10维 + summary）
  communication（formality/verbosity/emoji_usage/teasing/initiative/directness/humor + style）
  behaviors（trigger → tendencies，如 user_is_upset → [listen_first, avoid_immediate_advice,...]）
  values / boundaries / life（background + events + residences）
  ```
- `PersonaCompiler.preview(persona, scenario)`：以该人格身份、在给定场景下让 LLM 生成 1-3 句回应（`PersonaText.describe` 把人格渲染成系统提示）。
- `CompanionService.create`：由 Persona 落 `companions` + 初始 `persona_versions`(v1) + 种子 `companion_life_events` + 初始化 `relationships` + `agent_states`。

**关键设计**：人格是**数据**（存库 JSON），不是写死在 Prompt 里；Prompt 只是它的运行时投影。

## 12. 聊天对话（核心交互）

### 功能介绍
流式打字机对话；支持多会话；每条消息带意图/情绪/话题；回复 2-4 句、有来有往、会引用记忆；对话窗口显示真实时间（今天/昨天/日期分隔 + HH:mm）。

### 实现原理
- **SSE 流式**：`ChatController.chat()` 返回 `SseEmitter`（300s），线程池执行；具名事件协议：
  ```
  event:meta    {intent, emotion, topic}
  event:token   {delta}          ← LLM 每个增量
  event:replace {content}        ← 自然度修正时整体替换
  event:done    {messageId}
  event:error   {message}
  ```
- **前端**：`streamPost`（fetch + ReadableStream，按 `\n\n` 分块解析 `event:`/`data:`）驱动气泡渲染。
- **一次对话完整时序**（`CompanionRuntime.generate`）：
  ```
  1 保存 user 消息 + 工作记忆
  2 启发式感知 → LLM 同步精炼感知
  3 (request_tool) ReminderPlanner 建提醒
  4 ContextBuilder 聚合 → PromptAssembler 组提示
  5 LlmRouter.chatStream → SSE 逐 token
  6 NaturalnessEngine 校验 → replace 修正
  7 保存 companion 消息 + 工作记忆 → done
  8 异步后处理（记忆抽取/用户模型/关系/状态）
  ```

## 13. 感知引擎

### 功能介绍
识别用户每条消息的**意图**（12 类：问候/提问/倾诉开心/倾诉难过/求工具/纠正/计划…）、**情绪**（8 类：累/难过/焦虑/生气/开心/孤单/感激/平静）、**话题**（8 类：工作/学习/健康/关系/娱乐/美食/旅行/天气）。这些是后续关系、状态、记忆、回复的基础。

### 实现原理
- **双层架构**：
  | 层 | 技术 | 说明 |
  |----|------|------|
  | 启发式 `PerceptionEngine` | 关键词短路匹配 | 毫秒级兜底，保证首包快 |
  | LLM 同步精炼 `PerceptionRefiner.refineNow` | `structured("perception")` → intent/emotion/topic/entities | **质量优先**（用户接受延迟），失败回退启发式 |
- 精炼结果写入：用户消息元数据（intent/emotion/topic）+ 工作记忆（含实体），供本轮 Prompt 与后续关系/状态使用。

```java
// PerceptionRefiner.refineNow 核心
var res = llm.structured(StructuredRequest.builder()
        .task("perception").system(SYSTEM).user(userText).temperature(0.2).build());
JsonNode root = res.getJson();
String intent = root.path("intent").asText("");
String emotion = root.path("emotion").asText("");
// ... 更新消息元数据 + 工作记忆；失败 catch → return heuristic
```

## 14. 工作记忆

### 功能介绍
会话级"正在发生什么"：最近消息（≤12 条）、当前话题、当前情绪、当前意图、当前提到的实体。用于让伴侣在**同一会话内**保持连贯（知道刚才在聊什么）。

### 实现原理
- `WorkingMemory`：`ConcurrentHashMap<companionId:conversationId, Entry>`，Entry 含 `recent(Deque)`、`currentTopic/Intent/Emotion`、`currentEntities`、`lastUpdated`。
- **TTL 过期**：超过 `working-memory-ttl-minutes: 720` 自动失效。
- `ChatController` 同步记录每条消息；`PerceptionRefiner` 写入精炼后的话题/情绪/实体。
- `ContextBuilder` 读取 → `PromptAssembler` 渲染"当前会话状态"块。
- 内存实现（接口可替换 Redis，多实例共享）。

## 15. 记忆系统

### 功能介绍
伴侣的长期记忆，分三类：
- **Episodic**：发生过的事（"8月10日你说项目上线了"）
- **Semantic**：对用户的长期认知（"用户加班多，容易累"）
- **Shared**：双方共同经历/默契（"你们都爱手冲咖啡"）

用户可查看记忆、搜索、看"为什么你知道"（来源对话）、遗忘单条、清空、导出 JSON。记忆会**随时间和使用演化**（衰减/强化）。

### 实现原理

**抽取**（`MemoryExtractor`，@Async，每轮对话后）：
```
LLM structured("memory-extraction") → {episodic[], semantic[], shared[]}
→ saveBatch：同 content 去重 → max(importance) + retrieval_count+1（强化）
→ linkBatch(同批互链) + linkNewMemory(与历史互链)
```

**检索排序**（`MemoryService.retrieve`）——核心公式：
```java
public double retrievalStrength(int daysSinceOccurred) {
    double recencyDecay = Math.pow(0.92, Math.max(0, daysSinceOccurred));
    double frequencyBoost = 1 + 0.5 * Math.log(1 + retrievalCount);
    return importance * confidence * recencyDecay
            * emotionalWeight * relationshipWeight * frequencyBoost;
}
```
流程：候选(关键词 ∪ 全部 ∪ 向量接口) → 按强度降序 → 过滤 ≥0.02 → 取 topN → **强化**(count+1, lastRetrievedAt=now) → 关联扩展(封顶 2N)。

**关联记忆**（`MemoryAssociationService`）：无向量库，用**中文二元组重叠比**近似语义：
```java
static Set<String> bigrams(String s) { /* 清洗标点 → 每 2 字符一个集合 */ }
// 重叠比 = |交集| / min(|A|,|B|)，≥0.3 → 双向建链(relation=same_topic, strength=overlap)
```
检索时 `expand()` 沿 `memory_links` 取邻居。

**衰减**（`MemoryDecayService`，每周一 04:30）：`occurred_at<now-180天 && importance<0.4 && retrieval_count<3` → archived。**重要记忆不因时间消失**。

**透明**（`/memories/why`、`/{id}/source`）：命中记忆 + 以 `occurred_at` 为锚取 ±2 条**来源对话摘录**，直接回答"你为什么知道"。

**控制**：搜索 / 遗忘单条 / 清空全部 / 导出 JSON（隐私）。

## 16. 用户模型

### 功能介绍
伴侣对用户的长期理解，分层：**事实**（明确告知，高置信）、**偏好**、**行为模式**（观察统计）、**推测**（低置信 + 证据，Prompt 里标注"可能是"）。用户纠正时（"不是/其实…"）系统会**修正认知**。

### 实现原理
- **抽取**（`UserModelExtractor`，@Async）：LLM `"user-model-extraction"` → facts/preferences/hypotheses/corrections。
- **去重**（`UserModelService.saveFact`）：
  ```java
  facts.findTopBy...map(existing -> { setConfidence(max); return save(existing); })
       .orElseGet(() -> facts.save(f));
  ```
- **纠正机制**：检测到 correction → `correct()` 将相关推测 `confidence = max(0.1, conf-0.25)` + 写入新 explicit fact + 记"你纠正了她"关系里程碑。
- `UserModelService.summary`：产出供 Prompt 注入的高置信摘要（事实/偏好/习惯/推测，各带置信度）。

## 17. 关系系统

### 功能介绍
用户与伴侣之间有一组随时间演化的关系数值（熟悉度/信任/亲密度/好感）和阶段（初识→熟络→亲密→深深相连），以及**里程碑事件**（第一次对话、第一次深夜聊天、第一次被安慰…）和**共同经历**。

### 实现原理
`RelationshipEngine.onMessage(userId, companionId, time, emotion, intent)`：
- **数值缓慢增长**：familiarity+0.0012 / trust+0.0006 / intimacy+0.0004 / affection+0.0005（增量小，防"聊几次就熟"）。
- **一次性里程碑**：用 `eventRepo.countByRelationshipIdAndType()==0` 判重；significance≥0.8 时同时写入 `shared_experiences`。
  - first_conversation / first_late_night(23-3点) / first_emotional_support(sad)
  - first_joy_shared(share_joy) / first_care_about_her(ask_about_her) / first_plan_together(planning)
  - milestone(关系进入新阶段)
- **阶段机**（消息量 + 数值综合，非纯计数）：
  ```
  msgs>=300 || (msgs>=150 && intimacy>0.55) → deeply_connected
  msgs>=100 || (msgs>=50  && intimacy>0.45) → close
  msgs>=30  || (msgs>=15  && familiarity>0.2) → familiar
  否则 → new
  ```

## 18. Agent 状态

### 功能介绍
伴侣的"当下"：心情（平静/轻快/心疼/担心…）、精力、压力、社交能量、好奇心、亲密感。这是短期动态状态，**不等于人格**（人格稳定）。

### 实现原理
`AgentStateService.onMessage(companionId, emotion)`：energy-0.004 / stress+0.001 / socialEnergy-0.002 / emotionalCloseness+0.0009 / curiosity+0.001 / mood 按情绪映射。单行/伴侣，`updated_at` 时间戳。注入 Prompt"你此刻的状态"。

## 19. 反思与人格演化

### 功能介绍
每天凌晨、每周一，系统会**回顾对话**做深度分析（洞察、记忆候选、用户理解、关系变化），并**基于证据缓慢演化人格**（比如相处久了她更暖一点），生成新人格版本可回溯。

### 实现原理

**每日反思**（`ReflectionService.dailyReflect`，03:17）：
- 规则层：统计近 7 天深夜消息数 → 写 `user_patterns`（user_often_works_late）。
- LLM 层：当天对话摘录(≤3000字) → `"daily-reflection"` → summary/insights/memory_candidates(入库)/user_insights/relationship_candidates。

**每周反思**（`weeklyReflect`，周一 05:00）：7 天对话(≤5000字) → `"weekly-reflection"` → 长期用户理解 / behavioral_patterns(入库) / 关系变化。

**人格演化**（`PersonaEvolutionService.evolve`，每周反思后触发）：
```java
// 证据 = 用户模型摘要 + 关系数值
// LLM 提出 adjustments:[{field:"personality.traits.warmth", delta, reason}]
double delta = Math.max(-0.05, Math.min(0.05, rawDelta));  // 限幅
double updated = clamp(cur + delta);                        // [0.1, 0.95]
if (Math.abs(updated - cur) >= 0.01) { traits.put(trait, updated); applied++; }
// applied>0 → personaService.update(..., changeSource="evolution")
```
约束：每次≤3 处、±0.05 以内、**价值观/边界/身份不可变**、新版本可回溯。

## 20. 主动消息与日常作息

### 功能介绍
伴侣会在合适的时段**主动找你**：早安问候、傍晚回访、深夜加班关心、分享好消息后的跟进、很久没联系的想念。并且她**有自己的作息**——工作日在忙、周末在休闲、晚上准备睡，回复会体现她"此刻在做什么"（如周六回"正窝在沙发上喝茶"）。

### 实现原理

**日常时间表**（`CompanionSchedule`）：
```java
// 由 companionId.hashCode() 确定性派生（同一人每次一致，不同人作息不同）
Schedule s = new Schedule(8 + h%3, 17 + (h/3)%3, 23 + (h/18)%2, 6 + (h/9)%2);
// 工作日: MORNING/WORK_BUSY/LUNCH/WORK_AFTERNOON/EVENING/LEISURE/LATE_NIGHT
// 周末:   睡到自然醒 + 全天休闲
```

**主动决策**（`ProactiveEngine`，每 15 分钟）：
```
到期提醒先转通知（优先，不受打扰控制）
每伴侣过滤：DND(23-8) / 作息=SLEEP / 最小间隔1h / 每日上限5
decide() 按优先级评估触发，每个触发 expected_value × 作息因子
  触发: late_work / morning_greeting / evening_checkin / follow_up_joy / silence
打断成本:
  cost = 0.15 + 深夜(0.4) + 22点后(0.1) + 4h内聊过(0.35) ± 响应率(0.1) + 今日上限(0.3)
cost ≥ expected_value → DO_NOTHING
通过 → draftMessage(): LLM 按"人格+当前作息+场景"生成（失败回退模板）
     → 写 Notification + 注入最新会话(is_proactive=true)
```

## 21. 工具与提醒

### 功能介绍
用户可创建自定义提醒；**在聊天里直接说"帮我记得明天上午10点提醒我喝水"**，伴侣会自动创建提醒并自然确认；伴侣生日每年自动生成提醒。

### 实现原理
- `ReminderPlanner.tryCreateFromMessage`（intent=request_tool 时触发）：
  ```
  LLM structured("reminder-extraction") → {remind, title, content, remind_at}
  // SYSTEM 注入"今天是X, 现在是HH:mm" 防模型幻觉错误年份
  LocalDateTime remindAt = parseTime(...);   // 支持 ISO / "yyyy-MM-dd HH:mm" / "HH:mm"
  if (remindAt == null || remindAt.isBefore(now)) remindAt = now.plusHours(1);  // 过去时间兜底
  reminderService.create(...) → 返回 toolResult 注入 Prompt → 回复自然确认
  ```
- `BirthdayService`（每日 08:05）：确保每位伴侣有下一年待触发生日提醒。
- `ProactiveEngine` 兜底：到期提醒统一转 Notification。

## 22. 输出质量控制

### 功能介绍
拦截 AI 腔和套路话，让回复更像真人：不说"作为AI/我的训练数据/我理解你的感受"，不堆模板安慰，不说教，不过度道歉/emoji。

### 实现原理
`NaturalnessEngine.validate(text)` 规则检测并修复：
- AI 套话（13 类）→ 直接剔除
- 模板安慰（"一切都会好起来的/别想太多"）→ 标记
- 说教式建议（"你应该/建议你每天"）→ 标记
- 过度道歉（≥2 次）→ 标记
- 过度 emoji（>3 个）→ 标记
- 回应过长（>500 字）→ 标记

修正后文本经 SSE `replace` 事件整体替换（`ChatController` 检测 `reply != rawReply.trim()` 时发送）。

## 23. LLM 网关

### 功能介绍
统一的大模型接入层：聊天（流式 + 非流式）、结构化 JSON 输出。当前接 DeepSeek，可切换到 Anthropic 或内置 Mock（无 key 时自动降级，保证全流程可跑）。

### 实现原理
```java
public interface LlmGateway {
    ChatResult chat(ChatRequest request);
    void chatStream(ChatRequest request, Consumer<String> onDelta);
    StructuredResult structured(StructuredRequest request);  // 强制 JSON
}
```
- `LlmRouter`（门面）：`@PostConstruct` 按 `app.llm.provider` 选择 openai-compatible/anthropic/mock；未配 key 自动降级 mock（日志告警）。
- `OpenAiCompatibleGateway`：WebClient 调 DeepSeek `/chat/completions`；流式用 `bodyToFlux(DataBuffer)` 逐行解析 `data:` 块（`choices[0].delta.content`）；结构化用 `response_format:{type:"json_object"}` + 低温度。
- **业务层只依赖 `LlmRouter`**，换模型零改动。

---

# 第五部分 · 接口设计

## 24. API 清单

### 24.1 认证
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/auth/register` | 注册（返回 JWT） |
| POST | `/api/auth/login` | 登录 |
| GET | `/api/auth/me` | 当前用户 |

### 24.2 伴侣 / 人格
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/companions/compile` | 自然语言→编译人格+预览 |
| POST | `/api/companions/preview` | 任意场景预览 |
| GET/POST | `/api/companions` | 列表 / 创建 |
| GET/DELETE | `/api/companions/{id}` | 详情（含动态年龄）/ 删除 |
| PUT | `/api/companions/{id}/persona` | 重新描述→新版本人格 |
| GET | `/api/companions/{id}/life-events` | 人生时间线 |
| GET | `/api/companions/{id}/persona/versions` | 人格版本历史 |

### 24.3 会话 / 聊天
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/companions/{cid}/conversations` | 会话列表 |
| POST | `/api/companions/{cid}/conversations/first` | 首个会话（含问候） |
| POST | `/api/companions/{cid}/conversations` | 新建会话 |
| GET | `/api/companions/{cid}/conversations/{id}/messages` | 消息列表 |
| POST | `/api/companions/{cid}/conversations/{id}/chat` | **SSE 流式聊天** |

### 24.4 记忆
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/companions/{cid}/memories` | 列表 |
| GET | `/api/companions/{cid}/memories/search?q=` | 检索 |
| GET | `/api/companions/{cid}/memories/export` | 导出 JSON |
| GET | `/api/companions/{cid}/memories/graph` | 记忆图谱 |
| GET | `/api/companions/{cid}/memories/why?q=` | 为什么你知道（含来源） |
| GET | `/api/companions/{cid}/memories/{id}/source` | 单条来源摘录 |
| DELETE | `/api/companions/{cid}/memories/{id}` | 遗忘 |
| DELETE | `/api/companions/{cid}/memories` | 清空 |

### 24.5 用户模型 / 关系 / 状态 / 提醒 / 通知 / 反思
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/companions/{cid}/user-model/{facts,preferences,patterns,hypotheses}` | 她懂你 |
| DELETE | `/api/companions/{cid}/user-model/clear` | 清空了解 |
| GET | `/api/companions/{cid}/relationship` | 关系+事件+共同经历+状态 |
| GET | `/api/companions/{cid}/relationship/{events,shared-experiences}` | 明细 |
| GET | `/api/companions/{cid}/state` | Agent 状态 |
| GET/POST | `/api/companions/{cid}/reminders` | 提醒列表/创建 |
| PUT/DELETE | `/api/companions/{cid}/reminders/{id}/done` 等 | 完成/删除 |
| GET | `/api/companions/{cid}/notifications` | 通知列表 |
| PUT | `/api/companions/{cid}/notifications/{id}/read` · `/read-all` | 已读 |
| GET | `/api/companions/{cid}/notifications/unread-count` | 未读数 |
| GET | `/api/companions/{cid}/reflections` | 反思记录 |

### 24.7 V2.0 数字人格内核（用户视角）
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/companions/{cid}/life` | 她今天在干嘛（Life Runtime） |
| GET | `/api/companions/{cid}/self` | 她最近觉得自己怎样（Self Model） |
| GET | `/api/companions/{cid}/open-loops` | 未了结的事 |
| GET | `/api/companions/{cid}/experiences` | 最近经历 |
| GET | `/api/companions/{cid}/relationship/{threads,narrative,promises}` | 关系线索/关系故事/承诺 |

### 24.6 管理（验收/运维）
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/admin/reflection/run` | 手动每日反思 |
| POST | `/api/admin/reflection/run-weekly` | 手动每周反思 |
| POST | `/api/admin/persona/evolve` | 手动人格演化 |
| POST | `/api/admin/proactive/run` | 手动主动消息 |
| POST | `/api/admin/birthday/ensure` | 补生日提醒 |
| POST | `/api/admin/life/tick` | 推进生活 |
| POST | `/api/admin/thought/run` | 想法维护/补触发 |
| POST | `/api/admin/memory/consolidate` | 记忆固话 |
| POST | `/api/admin/cognitive/tick` | 统一内核 tick |
| GET | `/api/admin/explain/{proactive,memory,persona}` | 可解释性(为什么主动/记住/人格变) |
| POST | `/api/admin/explain/evaluate` | Human-likeness 自动评测 |

---

# 第六部分 · 工程实践

## 25. 快速开始

### 25.1 环境要求
JDK 17 · Maven 3.8+ · Node 18+ · 本地 PostgreSQL（`admin`/`shared-secret`）。

### 25.2 本地启动
```bash
# 1. 建库
psql -h 127.0.0.1 -U admin -d postgres -c "CREATE DATABASE companion;"

# 2. 后端（端口 8081）
cd companion-agent/backend && ./run.sh            # 或 mvn spring-boot:run

# 3. 前端（端口 5173，代理 /api → 8081）
cd companion-agent/frontend && npm install && npm run dev

# 4. 浏览器打开 http://127.0.0.1:5173
```

### 25.3 LLM 配置
默认 DeepSeek（OpenAI 兼容），不配 key 自动降级 Mock：
```bash
export DEEPSEEK_API_KEY=sk-xxx
export LLM_BASE_URL=https://api.deepseek.com
export LLM_CHAT_MODEL=deepseek-chat
```

### 25.4 生产部署
```bash
sudo bash companion-agent/scripts/deploy.sh
```
自动完成：前端产物 → `/var/www/companion` · nginx 配置 → `/etc/nginx/conf.d/` · `/etc/hosts` · systemd 服务 `luxera-companion-backend` · nginx 重载 · 健康检查。
> ⚠️ `deploy.sh` 会重写 systemd 单元，**必须保留 `EnvironmentFile=/etc/companion/.env`**（内含 `DEEPSEEK_API_KEY`），否则降级 Mock。

### 25.5 冒烟测试
```bash
BASE=http://127.0.0.1:8081 bash companion-agent/scripts/smoke.sh
```
覆盖：注册 → 编译人格 → 创建 → 问候 → SSE 流式 → 记忆抽取 → 关系里程碑 → 生日提醒 → 反思 → 记忆搜索。

## 26. 非功能设计

### 26.1 性能
- 聊天主链路：启发式感知（毫秒）+ LLM 感知（~1-2s）+ 回复流式（首 token 快）；异步后处理不阻塞响应。
- 记忆检索：结构化 SQL + 内存排序，单伴侣数据量 <10ms。
- 定时任务均异步线程池。

### 26.2 安全
- 密码 bcrypt；JWT(HS256) 7 天过期；无状态会话。
- 多租户：所有查询强制 `user_id + companion_id`，删除前校验归属。
- 敏感配置（DeepSeek key）在 `/etc/companion/.env`（root 640），不入 git。
- 生产走 HTTPS（nginx + 泛域名证书）。

### 26.3 可扩展性
- LLM 网关接口化 → 换模型改配置。
- `EmbeddingProvider` 接口 → 接向量库即插即用。
- `WorkingMemory` 接口可换 Redis（多实例共享）。
- 模块化单体 → 边界清晰可拆微服务。

### 26.4 可观测性
- systemd journal 日志；`[LLM] 网关已启用` 标识当前模型（监控降级）。
- 消息元数据存 intent/emotion/topic；自然度 issues 记日志。
- 主动消息决策日志（触发/预期 vs 成本）。

## 27. 技术决策与权衡

| 决策 | 权衡 |
|------|------|
| 模块化单体 vs 微服务 | 单体部署简单、事务强；边界已切分可拆 |
| JSON 存 text 而非 jsonb | 规避 hibernate-types 兼容问题；牺牲 JSON 内查询（独立列覆盖） |
| 无向量库，用二元组重叠 | 规避 pgvector 依赖；语义精度有限，可无缝升级 |
| 感知启发式 + LLM 同步精炼 | 质量优先（用户接受延迟）；失败回退保证可用 |
| 异步后处理 | 不阻塞 SSE；抽取/关系/状态后台沉淀 |
| 人格演化保守（±0.05） | 防人格漂移；版本可回溯 |
| 主动消息打断成本 | 防打扰；按时段/作息调节 |
| DeepSeek + Mock 降级 | 无 key 也能完整演示；key 失效自动降级 |

## 28. 测试与验证

- **冒烟**（scripts/smoke.sh）：全链路通过。
- **4 类长期连续性测试**（scripts/longterm_test.sh，自动断言）：记忆/生活/关系/主动连续性全通过。
- **Human-likeness 评测**（scripts/evaluate.sh，自动打分）：10 维 1-5 分。
- **验收场景 A-E**（V2.0 §50）：面试跟进、今天干嘛、累的情绪持续、一周沉默后自然联系，全部通过。
- **真实模型验证**：回复 2-4 句多样、提醒时间正确、记忆图谱建链、记忆透明带来源、LLM 反思有洞察、人格演化在跑、主动消息按时段 LLM 生成、作息体现（周六回复"窝在沙发喝茶"）。
- **已修 Bug**：
  - LLM 时间幻觉（提醒年份错）→ 注入当前日期 + 过去时间兜底
  - 部署脚本丢 EnvironmentFile 导致降级 Mock → 已修
  - HQL `:type` 保留字冲突 → 改名 `:mtype`
  - hibernate-types JSONB 泛型 propertyClass null → 弃用，改 text+Converter
  - `去`字误判 planning 意图 → 收紧关键词
  - `life_activities.updated_at` 非空列 ddl 失败 → 改可空 + 手动补列

---

# 第七部分 · 限制与演进

## 29. 已知限制（如实）

1. **向量检索需 embedding key 激活**：pgvector 已装+接线，但需配 `EMBEDDING_API_KEY`（DeepSeek 无 embedding 接口）才启用真实向量；未配时回退结构排序。
2. **WorkingMemory 单实例内存**：多实例部署需换 Redis。
3. **主动消息仅站内通知**：无 APNs/FCM 手机推送。
4. **工具层仅提醒**：无 MCP / 日历 / 搜索（V2.0 方案 §49 后置）。
5. **模型 deepseek-chat**：无推理模式 / 语音 / 图片 / 多模态（V3/V4 后置）。
6. **单机部署**：无高可用、无 K8s（方案后置）。
7. **认证简单**：用户名+密码 JWT，无邮箱验证 / OAuth / 找回密码。
8. **多租户应用层过滤**：非数据库 RLS。
9. **反思/演化批量定时**：非实时。
10. **当前数据多为测试数据**。
11. **DeepSeek key 失效降级 Mock**（已配置 key 并监控日志）。

## 30. 与设计文档路线图的关系

| 阶段 | 状态 |
|------|------|
| MVP（§95） | ✅ 完成 |
| V2.0 重构方案（44/44） | ✅ 全部完成（生命内核/认知内核/自我模型/关系叙事/行为策略/主动2.0/记忆2.0/可解释性/评测） |
| V2（§97） | ✅ 完成 |
| V3（§98） | ◐ 部分（关联记忆+人格演化+多模态中的语音/图片未做） |
| V4（§99 生态） | ⏳ 未启动 |

## 31. 演进路线

- **短期**：配 `EMBEDDING_API_KEY` 激活向量检索、手机推送、MCP 工具层（日历/天气/搜索）。
- **中期**：语音对话、用户自定义作息注入、真实用户灰度、Human-likeness 评测接入 CI。
- **长期**：V4 多端共享记忆/身份、K8s 高可用、RLS 加固、审计日志。

---

## 附录

- **设计依据**：《Persistent AI Companion 产品与技术设计方案》v1.0（107 节）
- **代码**：GitHub `Hojay-Chen/companion-agent`
- **运行**：`https://companion.luxera.top`（nginx + systemd jar :8081 + PostgreSQL :5432）
- **规模**：后端 116 个 Java 类 · 前端 17 个源文件 · 数据库 19 张表
