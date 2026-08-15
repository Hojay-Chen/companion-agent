# Luxera Companion — 长期陪伴型 AI 数字伴侣平台

> **不是 Chatbot**：拥有稳定人格、连续人生、持续记忆，随时间与用户建立关系，并在合适的时候主动找你。
>
> 设计依据：《Persistent AI Companion 产品与技术设计方案》v1.0（107 节）+ V2.0 重构方案 + V3 Interaction Runtime + V4 Continuous Human Runtime。当前完成 **V2.0(44/44) + V3 P0/P1/P2 + V4 P0/P1/P2/P3**。

| 项 | 值 |
|----|----|
| 后端 | Spring Boot 2.7.18 · JDK 17 · 模块化单体（210+ 个 Java 类，25 个业务模块） |
| 前端 | React 19 · Vite 8 · TypeScript(strict) · Tailwind CSS 3 · Zustand |
| 数据库 | PostgreSQL 16 · 36 张表（含 pgvector） |
| 大模型 | 统一 LLM 网关 → DeepSeek(deepseek-chat)，无 key 自动降级 Mock；模型用途路由可配 |
| 线上 | `https://companion.luxera.top`（nginx + systemd jar :8081） |
| 代码 | GitHub `Hojay-Chen/companion-agent` |
| 当前数据量 | 54 用户 / 54 伴侣 / 449 消息 / 507 记忆 / 251 反思记录 |

## 📖 目录

- [8. 实体关系总览](#8-实体关系总览)
- [9. 数据表详细设计（36 张表）](#9-数据表详细设计36-张表)
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
  - [25.5 测试脚本](#255-测试脚本)
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
- [32. V2.0 重构现状（数字人格内核）](#32-v20-重构现状数字人格内核)
  - [32.1 一句话](#321-一句话)
  - [32.2 架构统一（Strangler Pattern）](#322-架构统一strangler-pattern)
  - [32.3 新增模块与数据](#323-新增模块与数据)
  - [32.4 验收场景（V2.0 §50，全部实测通过）](#324-验收场景v20-50全部实测通过)
  - [32.5 完成度](#325-完成度)
  - [32.6 测试与评测](#326-测试与评测)
  - [32.7 待激活项](#327-待激活项)
- [33. V3.0 Interaction Runtime（P0 完成）](#33-v30-interaction-runtimep0-完成)
  - [33.1 一句话](#331-一句话)
  - [33.2 P0 五件事（全部完成）](#332-p0-五件事全部完成)
  - [33.3 Interaction Runtime 链路](#333-interaction-runtime-链路)
  - [33.4 会话模型（Conversation → Session → Exchange → Message）](#334-会话模型conversation--session--exchange--message)
  - [33.5 验收行为（实测通过，`scripts/v3_check.sh`）](#335-验收行为实测通过scriptsv3_checksh)
  - [33.6 对原方案的两处修正（工程落地）](#336-对原方案的两处修正工程落地)
  - [33.7 完成度](#337-完成度)
- [34. V3.1 P1 — 她更像一个"有自己生活的人"（完成）](#34-v31-p1--她更像一个有自己生活的人完成)
  - [34.1 CompanionAvailability（她不是永远在线）](#341-companionavailability她不是永远在线)
  - [34.2 UserChatStyle（学习你的聊天习惯，但不模仿你）](#342-userchatstyle学习你的聊天习惯但不模仿你)
  - [34.3 SelfDisclosure 增强（双向关系）](#343-selfdisclosure-增强双向关系)
  - [34.4 FollowUp 增强（关系型跟进）](#344-followup-增强关系型跟进)
  - [34.5 ResponsePlan（她也可以连发，低频）](#345-responseplan她也可以连发低频)
  - [34.6 验收（`scripts/p1_check.sh` 全过）](#346-验收scriptsp1_checksh-全过)
- [35. V3.2 P2 — 记忆 3.0 起步（完成）](#35-v32-p2--记忆-30-起步完成)
  - [35.1 Entity Layer（设计 §五十四：长期指代）](#351-entity-layer设计-五十四长期指代)
  - [35.2 Memory Disclosure（设计 §五十八：记得≠每次说出来）](#352-memory-disclosure设计-五十八记得每次说出来)
  - [35.3 验收（`scripts/p2_check.sh` 全过）](#353-验收scriptsp2_checksh-全过)
  - [35.4 一个踩坑记录](#354-一个踩坑记录)
- [36. V4.0 Continuous Human Runtime（P0/P1 核心完成）](#36-v40-continuous-human-runtimep0p1-核心完成)
  - [36.1 一句话](#361-一句话)
  - [36.1.1 V4 完整架构](#3611-v4-完整架构)
  - [36.2 Message Lifecycle（消息状态可见）](#362-message-lifecycle消息状态可见)
  - [36.3 持久 Event Stream（`GET /events`）](#363-持久-event-streamget-events)
  - [36.4 Appraisal（消息先改变内部状态）](#364-appraisal消息先改变内部状态)
  - [36.5 Drives + Behavior 竞争](#365-drives--behavior-竞争)
  - [36.6 DEFER（看到了但不回）](#366-defer看到了但不回)
  - [36.7 验收（`scripts/v4_check.sh` 全过）](#367-验收scriptsv4_checksh-全过)
  - [36.8 对原方案的两处工程修正](#368-对原方案的两处工程修正)
  - [36.9 完成度](#369-完成度)
  - [36.9.1 端到端消息流转（开会场景，验证 V4 因果链）](#3691-端到端消息流转开会场景验证-v4-因果链)
- [37. V4.1 P2/P3 — 完整 Human Behavior + Natural Expression（完成）](#37-v41-p2p3--完整-human-behavior--natural-expression完成)
  - [37.1 Phone Runtime（她也有手机，手机不是总响的）](#371-phone-runtime她也有手机手机不是总响的)
  - [37.2 Attention 动态场（忙 ≠ 永远不看手机）](#372-attention-动态场忙--永远不看手机)
  - [37.3 负面情绪衰减 + Re-engagement](#373-负面情绪衰减--re-engagement)
  - [37.3.1 主动消息机制重构（V4.2）](#3731-主动消息机制重构v42)
  - [37.3.2 Appraisal 正则误判修复（V4.2）](#3732-appraisal-正则误判修复v42)
  - [37.3.3 Expression Loop 触发放宽（V4.2）](#3733-expression-loop-触发放宽v42)
  - [37.4 Expression Loop（她的思想真的在展开）](#374-expression-loop她的思想真的在展开)
  - [37.5 Playwright 实测 + 用户评审发现的 Bug（已修复）](#375-playwright-实测--用户评审发现的-bug已修复)
  - [37.6 Playwright 实测通过（真实 UI）](#376-playwright-实测通过真实-ui)
- [附录](#附录)
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

## 9. 数据表详细设计（36 张表）

> 原有 19 表 + V2.0 新增 10 表 + V3 新增 5 表 + V4 新增 2 表（`message_appraisals` / `companion_phone_states`）。

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
| `messages` | id, conversation_id, sender_type, content, intent, emotion, topic, is_proactive, session_id(V3), exchange_id(V3), message_kind(V3: NORMAL/SHORT_ACK/PROACTIVE/FOLLOW_UP/SYSTEM/TOOL_RESULT), delivery_status(V3), metadata(JSON), created_at |
| `interaction_sessions`(V3) | id, conversation_id, companion_id, user_id, started_at, ended_at, message_count |
| `conversation_exchanges`(V3) | id, session_id, conversation_id, companion_id, user_id, started_at, ended_at, status(OPEN/CLOSED), message_count |
| `conversation_boundaries`(V3) | id, conversation_id, companion_id, user_id, type(SOFT_END/HARD_END/PAUSE/BUSY/SLEEP/DISTRACTED/RETURN_LATER), reason, occurred_at |
| `message_appraisals`(V4) | id, message_id, companion_id, emotional_impact, relationship_impact, urgency, warmth, hurt, anger, personal_relevance, context |
| `companion_phone_states`(V4 P2) | id, companion_id, notification_mode(sound/vibrate/silent/dnd), sound_enabled, vibration_enabled, phone_location(hand/desk/bag/other_room), battery, screen_on, do_not_disturb, last_checked_at |

### 9.3 记忆

| 表 | 字段 |
|----|------|
| `memories` | id, user_id, companion_id, type(episodic/semantic/shared), content, summary, importance, confidence, emotional_weight, relationship_weight, retrieval_count, last_retrieved_at, occurred_at, expires_at, status, source_type, source_id, created_at |
| `memory_links` | id, from_memory_id, to_memory_id, relation(same_topic), strength, created_at |
| `entities`(V3 P2) | id, user_id, companion_id, type(PERSON/COMPANY/PLACE/…), name, description, first_seen_at, last_seen_at, mention_count, last_context, salience, status |

### 9.4 用户模型

| 表 | 字段 |
|----|------|
| `user_facts` | id, user_id, companion_id, subject, predicate, object, value(JSON), confidence, source_type, source_id, first_observed_at, last_observed_at, status |
| `user_preferences` | id, user_id, companion_id, category, preference, value(JSON), confidence, source_type, source_id, observed_at, status |
| `user_patterns` | id, user_id, companion_id, pattern, description, confidence, evidence_count, evidence(JSON), first_observed_at, last_observed_at, status |
| `user_hypotheses` | id, user_id, companion_id, hypothesis, description, confidence, evidence(JSON), status, created_at, updated_at |
| `user_chat_styles`(V3 P1) | id, companion_id, user_id, sample_count, avg_message_length, avg_gap_ms, burst_rate, emoji_rate, laugh_rate, question_rate, active_hour_start/end, hour_distribution(JSON), last_active_at |

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
流式打字机对话；支持多会话；**V3 Interaction Runtime**：收到消息先决定"要不要回、投入多少、怎么回"，连发消息自动合并为一次回复，回复长度/问题/建议由预算决定而非固定模板，她也会有自己的回复节奏（延迟/打字指示）。每条消息带意图/情绪/话题；对话窗口显示真实时间（今天/昨天/日期分隔 + HH:mm）。

### 实现原理
- **SSE 流式（V3 具名事件协议）**：`ChatController.chat()` 返回 `SseEmitter`（300s），线程池执行：
  ```
  event:meta         {intent, emotion, topic, action, commitment}   ← 决策结果
  event:typing_start {conversationId}    ← 仅 commitment≥CASUAL 才发
  event:typing_stop  {}                  ← 真人节奏延迟后
  event:token        {delta}             ← LLM 每个增量
  event:replace      {content}           ← 自然度修正时整体替换
  event:message      {messageId, content}← ResponsePlan 第二条(她连发)
  event:boundary     {type:SOFT_END}     ← "我去洗澡了"→不续聊
  event:done         {messageId, action} ← action 含 IGNORE/SHORT_ACK 等
  event:error        {message}
  ```
- **连发合并**：前端在首个消息后启动自适应静默窗口（按用户近期发送间隔 1.5 倍，限 800~2200ms），把窗口内连发消息作为一批 `POST /chat {messages:[{content}...]}`；后端一次保存、一次回复。
- **决策链**：批量入库（感知 + Session/Exchange 归属 + 聊天习惯学习）→ `InteractionPolicyEngine.decide`（REPLY_NOW / SHORT_ACK / IGNORE / WAIT / END_CONVERSATION，输入含意图/情绪/精力/压力/关系阶段/作息/可用状态）→ typing + 延迟（`ResponseLatencyEngine`，含 Availability 加成）→ 一次生成（带预算）→ `<split>` 拆段（ResponsePlan）→ boundary。
- **前端**：`streamPost`（fetch + ReadableStream，按 `\n\n` 分块解析 `event:`/`data:`）驱动气泡渲染与打字指示器。

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
- `ContextLoader` 读取 → `ContextCompiler` 渲染"当前会话状态"块（V2 起替代旧 `PromptAssembler`）。
- 内存实现（接口可替换 Redis，多实例共享）。

## 15. 记忆系统

### 功能介绍
伴侣的长期记忆，分三类：
- **Episodic**：发生过的事（"8月10日你说项目上线了"）
- **Semantic**：对用户的长期认知（"用户加班多，容易累"）
- **Shared**：双方共同经历/默契（"你们都爱手冲咖啡"）

用户可查看记忆、搜索、看"为什么你知道"（来源对话）、遗忘单条、清空、导出 JSON。记忆会**随时间和使用演化**（衰减/强化）。**V3 P2 新增实体层**：她还会记住你常提的"人/地方/事"（`entities` 表），用于理解"那家公司/上次那个地方"这类长期指代。

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
到期提醒先转通知（系统事件, 不受打扰控制）
每伴侣过滤：DND(23-8) / 作息=SLEEP / 最小间隔1h / 每日上限5
decide() 按优先级评估触发，每个触发 expected_value × 作息因子
  触发: open_loop(未了结事项, V3 P1 价值递减) / thought(想起你) / late_work / morning_greeting / evening_checkin / follow_up_joy / silence
打断成本:
  cost = 0.15 + 深夜(0.4) + 22点后(0.1) + 4h内聊过(0.35) ± 响应率(0.1) + 今日上限(0.3)
cost ≥ expected_value → DO_NOTHING
通过 → draftMessage(): LLM 按"人格+当前作息+场景"生成（失败回退模板）
     → 只写入最新会话 message_kind=PROACTIVE（V3: 主动=Chat 消息, 不再写 Notification）
```

> **V3 变化**：主动消息只进聊天框（`message_kind=PROACTIVE`），不再是 Notification —— 她"主动找你"是关系互动而非系统通知；去重/间隔 bookkeeping 改查 `messages(kind=PROACTIVE)`。提醒（Reminder）仍走 Notification（系统事件）。

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
| POST | `/api/companions/{cid}/conversations/{id}/chat` | **SSE 流式聊天**（单条 `{content}` 或连发合并 `{messages:[{content}]}`；事件含 typing_start/typing_stop/boundary/message） |
| GET | `/api/companions/{cid}/events` | **V4 持久事件流**（长连接，实时推已读/打字/主动消息/心跳，25s ping） |

### 24.4 记忆
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/companions/{cid}/memories` | 列表 |
| GET | `/api/companions/{cid}/memories/search?q=` | 检索 |
| GET | `/api/companions/{cid}/memories/export` | 导出 JSON |
| GET | `/api/companions/{cid}/memories/graph` | 记忆图谱 |
| GET | `/api/companions/{cid}/memories/entities` | 用户常提实体（V3 P2） |
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

### 25.5 测试脚本

```bash
BASE=http://127.0.0.1:8081 bash scripts/smoke.sh      # V2 全链路冒烟（注册→建伴→SSE→记忆→关系→反思）
BASE=http://127.0.0.1:8081 bash scripts/v3_check.sh   # V3 P0 验收（连发一次回/短陪伴/洗澡SOFT_END/自然重开/嗯=不回）
BASE=http://127.0.0.1:8081 bash scripts/p1_check.sh   # V3 P1 验收（聊天习惯学习/连发率/表结构）
BASE=http://127.0.0.1:8081 bash scripts/p2_check.sh   # V3 P2 验收（实体抽取/实体API/表结构）
BASE=http://127.0.0.1:8081 bash scripts/v4_check.sh   # V4 验收（message_read/Appraisal/DEFER/表结构）
```

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
- **真实模型验证**：回复长短随消息价值变化（V3 预算）、提醒时间正确、记忆图谱建链、记忆透明带来源、LLM 反思有洞察、人格演化在跑、主动消息按时段 LLM 生成、作息体现（周六回复"窝在沙发喝茶"）。
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

## 32. V2.0 重构现状（数字人格内核）

> 依据《Luxera Companion V2.0 重构设计方案》逐节落地，采用 Strangler Pattern 演进，不推倒重来。

### 32.1 一句话

**从"记得用户的 AI"升级为"拥有自己连续生命、自我模型、内在思想、关系叙事、主动行为能力的数字人格"。** 验收场景 A-E 全部通过。

### 32.2 架构统一（Strangler Pattern）

```
旧 CompanionRuntime ──委托──> CompanionCognitiveRuntime（统一内核）
                                    ├─ processUserMessage()  一次用户消息全链路
                                    └─ tick()                无交互时的生命推进
数据流: WorldTime → Life → Emotion → Thought → [SelfModel|UserModel|Relationship]
        → Memory → OpenLoops → BehaviorPolicy → ContextCompiler → LLM
        → Response → Experience → Consolidation → 记忆/自我/关系/人格学习
```

旧 `PromptAssembler`/`ContextBuilder` 已标 `@Deprecated`，由 `ContextCompiler`/`ContextLoader` 取代。

### 32.3 新增模块与数据

| 类别 | 内容 |
|------|------|
| 新增包（7） | `life/` `thought/` `emotion/` `experience/` `openloop/` `selfmodel/` `behavior/` |
| 新增表（10，共 29） | companion_life / life_activities / thoughts / emotional_episodes / open_loops / self_models / experiences / relationship_threads / promises / relationship_narratives |
| 核心新类 | CompanionCognitiveRuntime / CompanionContext / ContextLoader / ContextCompiler / BehaviorPolicyEngine / BehaviorConstraints / MemoryConsolidator / SelfModelExtractor / ThoughtEngine / EmotionEngine / LifeRuntime / LearningContext |

### 32.4 验收场景（V2.0 §50，全部实测通过）

| 场景 | 结果 |
|------|------|
| A · 用户说"明天面试" | → OpenLoop=面试 + Thought=curiosity，**今天不打扰**（SUPPRESSED）✅ |
| B · 面试后 | Thought 重新激活 → 主动"面试怎么样"（时间感知，不提前）✅ |
| C · "你今天干嘛了" | 从 Life Runtime 回答（非随机）✅ |
| D · "我最近真的有点累" | EmotionalEpisode(tired/anxious) 持续影响行为 ✅ |
| E · 一周没聊 | 依据 Thought/OpenLoop/Relationship 决定是否/为何/如何联系 ✅ |

### 32.5 完成度

```
✅ 44 节 · ◐ 4 节 · ❌ 0 · ⛔ 1（MCP 工具层, 方案明确第一阶段不做）
```

剩余 ◐ 均为方案自标"第二阶段/后置"：SelfModel 拆表（§9）、REAL_TOOL/SYSTEM 生活事件来源（§33，需工具层）、深层 Pattern/关系记忆归纳（§35/§37 基础版已做）。

### 32.6 测试与评测

- **4 类长期连续性测试**（`scripts/longterm_test.sh`）：记忆/生活/关系/主动连续性，自动断言通过。
- **Human-likeness 评测**（`scripts/evaluate.sh`）：10 维度 1-5 分，自动打分（`/api/admin/explain/evaluate`）。
- **可解释性**（`/api/admin/explain/{proactive,memory,persona}`）：为什么主动/为什么记住/为什么人格变化。

### 32.7 待激活项

- **pgvector 已装+接线**，需配置 `EMBEDDING_API_KEY`（如硅基流动 `BAAI/bge-large-zh-v1.5`）才启用真实语义向量检索；未配时自动回退结构排序。
- 一键配置脚本：`scripts/setup_embedding.sh`。

---

## 33. V3.0 Interaction Runtime（P0 完成）

### 33.1 一句话

> **收到消息 ≠ 回复消息。** 她先决定"要不要回、投入多少、怎么回"，再决定"回复什么"。
> 把"你说一句 → 她立刻回一大段"的 Chatbot 思维，升级为真人相处的运行机制。

V2 已给了她人格/记忆/关系/生活，但主链路仍是 `用户消息 → LLM → 回复`。V3 P0 补上的正是 **Interaction Runtime**：让"不回复、短应、延迟、结束、追问、主动"都成为可解释的行为决策，而不是 LLM 的偶然输出。

### 33.2 P0 五件事（全部完成）

| # | 目标 | 解决 | 核心类 |
|---|------|------|--------|
| 1 | Reply Decision | 为什么我说一句她就回一句 | `interaction/InteractionPolicyEngine` + `InteractionDecision` + `ResponseCommitment` |
| 2 | Response Timing | 为什么秒回 | `interaction/ResponseLatencyEngine` + SSE `typing_start/typing_stop` |
| 3 | Response Budget | 为什么每次都长篇大论 | `interaction/ResponseBudget`（句数/字数/问题/建议/自我暴露上限） |
| 4 | Message Burst | 连发多条还一问一答 | **前端聚合** + `/chat` 批量 `{messages:[...]}`（一次请求至多一次回复） |
| 5 | Proactive → Chat | 主动消息像系统通知 | 主动消息 = `message_kind=PROACTIVE` 的 Chat 消息，**不再生成 Notification** |

### 33.3 Interaction Runtime 链路

```
用户(可连发多条, 前端聚合)
   ↓  POST /chat {messages:[{content}...]}
批量入库(感知+Session/Exchange 归属)
   ↓
InteractionDecision: REPLY_NOW / SHORT_ACK / IGNORE / WAIT / END_CONVERSATION
   + commitment(0=ACK..3=DEEP) + budget(句数/字数/问题/建议)
   ↓
typing_start(仅 commitment≥CASUAL) → latency(真人节奏, 非随机) → typing_stop
   ↓
LLM 一次生成(带预算 Prompt + Naturalness QA)
   ↓
boundary(SOFT_END 等) → done
```

决策输入：`消息文本+意图+情绪 + 精力/压力 + 关系阶段 + 当前作息(忙/闲)`，**不是随机 Ignore**。低精力→投入降档；高压力→更短、不问；新关系→不自我暴露。

### 33.4 会话模型（Conversation → Session → Exchange → Message）

新增 3 张表，`messages` 增加 `session_id/exchange_id/message_kind/delivery_status`：

| 表 | 含义 |
|----|------|
| `interaction_sessions` | 一次连续聊天（如 09:00-09:30），超过 30 分钟为新 Session |
| `conversation_exchanges` | 一次自然互动（连发+回复=一个 Exchange），超过 5 分钟为新 Exchange |
| `conversation_boundaries` | 对话边界：`SOFT_END`（"我去洗澡了"→"去吧"，不续聊）等 |

### 33.5 验收行为（实测通过，`scripts/v3_check.sh`）

| 输入 | 她的行为 |
|------|----------|
| 连发"气死了/老板改需求/服了" | **一次**回复，不逐条回 ✅ |
| "今天又加班到很晚了,好累" | 短陪伴，不长篇大论 ✅ |
| "我去洗澡了" | 短应 + `boundary=SOFT_END`，不再续聊 ✅ |
| "我回来了" | 自然重开（新 Session），非"欢迎回来" ✅ |
| "嗯" | `SHORT_ACK` 或 `IGNORE`（合法地不回）✅ |

### 33.6 对原方案的两处修正（工程落地）

1. **连发合并改为前端聚合，而非后端"等窗口"**。原方案建议后端收到第一条后 sleep 等待后续；这在"流式期间锁定输入"的 UI 下永远合并不了，且 gather+锁会阻塞请求。改为：前端在首个消息后启动自适应静默窗口（按用户近期发送间隔 1.5 倍，限 800~2200ms），窗口内连发消息**一批**发给后端，一次生成一次回复。
2. **主动消息只进聊天框**。落实"主动 = Chat 消息，不是 Notification"：`ProactiveEngine` 不再创建 `type=proactive` 的通知，去重/间隔 bookkeeping 改用 `messages(kind=PROACTIVE)`；提醒（Reminder）仍走 Notification（系统事件）。

### 33.7 完成度

```
V3 P0: ✅ 回复决策 / 时机 / 预算 / 连发合并 / 主动进聊天框 / 会话模型 / 边界   (§33)
V3 P1: ✅ Availability / UserChatStyle / SelfDisclosure / FollowUp / ResponsePlan (§34)
V3 P2: ✅ Entity Layer / Memory Disclosure                                     (§35)
V3 P3(再往后): Relationship Narrative 深化 / Self Model 拆表 / memory_embeddings 分离 / Context L0-L3 分层
```

---

## 34. V3.1 P1 — 她更像一个"有自己生活的人"（完成）

> P1 解决的是"她为什么不像一个有自己生活的人"：她也会忙/累/睡，她记得你聊天的方式，她也会分享自己、也会在合适的时候连发两条。

### 34.1 CompanionAvailability（她不是永远在线）

| 状态 | 触发 | 行为 |
|------|------|------|
| `SLEEPING` | 作息睡觉时段 | 琐碎消息直接忽略（合法不回） |
| `BUSY` | 上班时段 | 回复更慢更短、最多一个问题 |
| `RESTING` | 精力 < 0.25 | 回复更慢更短 |
| `DISTRACTED` | 精力 < 0.4 | 回复短、不追问 |
| `SOCIALIZING` / `TRAVELING` | 晚间/休闲+社交电量高 | 回得慢一点 |

实现：`state/CompanionAvailability` + `AvailabilityService`（由作息+精力/压力/社交电量**实时派生**，不建表）。影响 `ResponseLatencyEngine`（忙/累回得更慢）与 `InteractionPolicyEngine`（睡觉时琐碎忽略、忙时预算降档）。**Busy ≠ 不回复**，只影响节奏。

### 34.2 UserChatStyle（学习你的聊天习惯，但不模仿你）

新增 `user_chat_styles` 表，每条用户消息入库时增量统计：

- 平均消息长度 / 平均发送间隔 / **连发率**（间隔<2s）/ emoji 使用率 / "哈哈"频率 / 提问频率 / 活跃时段

注入 ContextCompiler【他聊天的习惯】："他习惯发 X 字左右的消息,经常一次发好几条…你不需要模仿他的习惯,用自己的方式和他相处,但别在他发短句时回一大段。"——**匹配节奏，保留她自己的性格**。

### 34.3 SelfDisclosure 增强（双向关系）

- 新关系（`new`/`familiar`）：克制，不自我暴露、不追问。
- 亲密关系：Prompt 明确"你可以自然地分享一点自己正在经历的事(像朋友聊天, 不是汇报)"。
- 结合原有 `BehaviorPolicyEngine.shareSelf`（关系亲密才分享）。

### 34.4 FollowUp 增强（关系型跟进）

OpenLoop 跟进时机升级为**价值递减**算法（`openLoopValue`）：
- 到点前：越接近价值越高；
- 刚过点：价值最高；
- 错过但 < 2 天未跟进：仍值得问一次（价值随时间衰减），不因错过窗口而永久丢失。

Reminder（工具→通知）与 Follow-up（关系→聊天内主动问）在 V3 已彻底分开。

### 34.5 ResponsePlan（她也可以连发，低频）

- Prompt 允许 LLM 用 `<split>` 把回复拆成两条消息（"先一句短的,隔一会再补一句"），并强调"只在真正自然时用,不要滥用"。
- 后端：按 `<split>` 拆段，第一条走正常 token 流 + 写库；后续段延迟 ~0.9-1.8s 逐条写库，发 `message` SSE 事件。
- 前端：收到 `message` 事件 → 重载消息（新气泡像"隔了一下又补一句"）。
- 这是低频特性，由 LLM 在自然情境触发，不刻意模拟微信。

### 34.6 验收（`scripts/p1_check.sh` 全过）

| 检查 | 结果 |
|------|------|
| 聊天后 `user_chat_styles` 记录样本数 ≥3 | ✅ |
| 快速连发 → `burst_rate` 上升 | ✅ |
| P1 表/列结构 | ✅ |
| V3 P0 回归（洗澡→SOFT_END） | ✅ |

---

## 35. V3.2 P2 — 记忆 3.0 起步（完成）

> P2 解决"她为什么还不像一个真正持续存在的人"：实体记忆(长期指代) + 记忆披露克制(记得≠每次都说出来)。

### 35.1 Entity Layer（设计 §五十四：长期指代）

新增 `entities` 表 + `MemoryEntityExtractor`(LLM 结构化抽取) + `MemoryEntityService`：

- 每轮对话后异步抽取用户提到的实体（PERSON/COMPANY/PLACE/RESTAURANT/PROJECT/MOVIE/EVENT/TOPIC），同名合并、mention_count 累加、salience 上升
- 注入 ContextCompiler【你记得的这些】："他常提到的东西,当他用'那家/那个/上次的'指代时,你要能对上号"
- 前端「她的记忆」面板新增「她认识的」区块（显示实体 + 提过次数）

验收实测：聊"下周要面阿里巴巴/那家咖啡馆" → `entities` 表记录 `阿里巴巴`、`那家咖啡馆`。

### 35.2 Memory Disclosure（设计 §五十八：记得≠每次说出来）

- 记忆注入改措辞："只有在本回合相关时才自然地引用,不要为了展示记忆而提起"
- 行为准则新增第 9 条："不要为了展示记忆而主动列举旧事('你还记得…吗'/'你之前不是喜欢…吗'这种话少说)"
- 目的：防止"AI 在展示自己的 Memory"的腔调，让记忆只在她相关时自然流露

### 35.3 验收（`scripts/p2_check.sh` 全过）

| 检查 | 结果 |
|------|------|
| 提到实体 → `entities` 表记录 | ✅ 阿里巴巴 / 那家咖啡馆 |
| `GET /memories/entities` 返回列表 | ✅ |
| entities 表/列结构 | ✅ |
| V3 P0 回归（洗澡→SOFT_END） | ✅ |

### 35.4 一个踩坑记录

实体类最初命名为 `Entity`，与 JPA 注解 `@Entity` 同名导致注解解析成自引用、Lombok 连锁失败（所有 getter 消失）。已改名 `MemoryEntity`。**教训：实体类名避开 JPA 注解名。**

---

## 36. V4.0 Continuous Human Runtime（P0/P1 核心完成）

> **V4 不做"让 AI 回复得像真人"，而是"让 AI 的行为由一个持续存在的人类式生活状态产生"。**
> 用户消息只是进入她生活世界的外部事件之一；聊天是她内部状态、关系和当下生活共同作用后产生的行为。

### 36.1 一句话

```
V3: 用户消息 → Interaction Runtime → 决定回不回 → LLM → 回复
V4: 她一直在生活(工作/休息/想他/看手机) → 用户消息到达 → 手机收到 → 注意/查看
    → Appraisal(这句话对我意味着什么, 改变内部状态) → Drives(想不想回) → 行为
```

**关键变化**：LLM 不再负责"这个人现在该干嘛"。它只把已经形成的想法/态度/表达意图变成自然语言。

### 36.1.1 V4 完整架构

```
                         Companion Runtime（持续生命运行时）
                                     │
             ┌───────────────────────┼───────────────────────┐
             ▼                       ▼                       ▼
       Life Runtime            Phone Runtime           Environment
             │                       │                       │
       Activity/Energy/Schedule  Notification/Device         Time
             │                       │
             ▼                       ▼
             └──────►  Attention Runtime ◄──────┘
                         │
                         ▼
                    Perception Runtime
                         │
                         ▼
                    Appraisal Runtime
                         │
                  ┌──────┼──────┐
                  ▼      ▼      ▼
               Emotion  Drives  (Thought/Memory/Relationship)
                  │      │      │
                  └──────┼──────┘
                         ▼
                    Behavior Runtime
                         │
                ┌────────┼────────┐
                ▼        ▼        ▼
            Continue  Inspect  Communicate
                                │
                          ┌─────┴─────┐
                          │           │
                        Reply      Initiate
                                │
                                ▼
                         Expression Runtime
                                │
                                ▼
                               LLM
                                │
                                ▼
                          Message Runtime
                                │
                                ▼
                       Event Stream / SSE
                                │
                                ▼
                              User
```

**确定性世界（时间/活动/手机/注意力）不交给 LLM**；LLM 只负责 Appraisal/Thought/Intention/Expression/Reflection。

### 36.2 Message Lifecycle（消息状态可见）

| 状态 | 含义 | 前端显示 |
|------|------|----------|
| `DELIVERED` | 手机收到了, 但她可能没看 | 已发送 ✓ |
| `READ` | 她看到了(已读延迟由 Attention 决定: 忙/疲劳→慢) | 已读 ✓✓ |
| `DEFERRED` | 看到了但不回(开会/生气/回避) | 已读 ✓✓, 无回复 |
| `IGNORED` | 未读忽略(琐碎/睡觉/dnd 未注意) | 已发送 ✓ |

**真实感核心**：已读 ≠ 会回复。她可能"已读不回"很久，直到你下一句话改变她的状态。

### 36.3 持久 Event Stream（`GET /events`）

前端长连接 `GET /api/companions/{cid}/events`，实时接收所有事件：
```
event:user_message_status  {messageId, status}   ← 你已发送
event:message_read         {messageId}           ← 她已读
event:companion_typing     {typing:true/false}   ← 她开始/停止输入
event:companion_message    {messageId, content}  ← 她主动发来的消息(实时)
event:companion_state      {availability}        ← 她此刻状态
event:ping                                        ← 心跳(25s)
```
后端 `CompanionEventBus`（内存）+ `EventStreamController`。主动消息经此实时推给前端，无需刷新。

### 36.4 Appraisal（消息先改变内部状态）

新增 `message_appraisals` 表 + `AppraisalService`：消息被读到后先判断"这对我意味着什么"，**先改变内部状态，再决定行为**。

**六个识别维度**（关键词正则分组，零额外 LLM 调用，复用 `PerceptionRefiner` 感知）：

| 分组 | 识别什么 | 例句 → 影响 |
|------|---------|------------|
| `APOLOGY` | 道歉 | "对不起" → warmth↑ / hurt↓ / anger↓ |
| `ACCUSATION` | 指责她（必须明确指向"你"） | "你怎么这么烦" → hurt/anger↑ / 关系↓ |
| `AFFECTION` | 表达感情 | "我好想你" → warmth↑ / 关系↑ |
| `DISTRESS` | 自己情绪痛苦 | "我很难受/撑不下去/好烦" → 情绪冲击↑ / urgency↑ |
| `URGENT` | 紧急求助 | "怎么办/救命" → urgency 0.9 |
| `SHARE_JOY` | 分享喜悦 | "我升职了" → warmth↑ / 关系↑ |

产出维度落库：`emotional_impact / relationship_impact / urgency / warmth / hurt / anger / personal_relevance`。

随后：
- 更新 `AgentState`（`hurt`/`anger` 累积，`emotional_closeness` 受 warmth 影响）
- 微调 Relationship（负面 → trust 微降；温暖 → trust/intimacy 微升）
- **零额外 LLM 调用**（复用 `PerceptionRefiner` 的精炼感知）

### 36.5 Drives + Behavior 竞争

新增 `Drives` + `DrivesService`：`desire_to_reply / desire_to_avoid / desire_to_share / desire_to_reconnect / desire_to_rest`，由 Appraisal + 状态 + 关系 + 可用状态实时计算。

`InteractionPolicyEngine` 升级为评分竞争：
```
REPLY 倾向 = reply_drive × 消息价值 + warmth×关系 + urgency
AVOID 倾向 = avoid_drive × 冲突 + anger/hurt 加权
AVOID - REPLY > 0.15 → DEFER(已读不回)     ← V4 核心行为
琐碎且回复欲低        → IGNORE(未读忽略)
其余                 → REPLY_NOW / SHORT_ACK / END_CONVERSATION
```

### 36.6 DEFER（看到了但不回）

- 消息标记 `READ` + 发布 `message_read` → 本次不回复（`done.action=DEFER`）
- **状态已 Appraisal 落盘**：她的 hurt/anger/warmth 已改变 → 你下一句话（如"对不起"）自然带出"……行吧"
- 不需要"会议结束自动回复"——那是刻意的；状态延续才是真实的

### 36.7 验收（`scripts/v4_check.sh` 全过）

| 检查 | 结果 |
|------|------|
| 发"我好难过" → `/events` 收到 `message_read` + 正常回复 | ✅ |
| `message_appraisals` 表记录 | ✅ |
| 用户消息 `delivery_status=READ` 落库 | ✅ |
| 发"你怎么这么烦" → `DEFER`(已读不回) | ✅ |
| 发"对不起" → 正常回复(状态延续) | ✅ |
| 表结构(`agent_states.hurt/anger`) | ✅ |
| V3 P0 回归(洗澡→SOFT_END) | ✅ |

### 36.8 对原方案的两处工程修正

1. **POST /chat 保持 SSE 打字机响应**，`/events` 只推"非打字机"事件（已读/打字/主动消息）。避免双写冗余，老验收脚本兼容，前端打字机体验不变。
2. **Appraisal 零额外 LLM 调用**：基于现有感知 + 关键词规则，而不是设计里每消息一次 LLM Appraisal——成本可控，效果等价。

### 36.9 完成度

```
V4 P0: ✅ 持久 Event Stream / Message Lifecycle / read receipts
V4 P1: ✅ Appraisal / Drives / DEFER / 已读不回
V4 P2: ✅ Phone Runtime / Attention 动态场 / 负面情绪衰减 / Re-engagement   (§37)
V4 P3: ✅ Expression Loop(思维展开连发)                                     (§37)
后续: Wakeup Scheduler(advance) / Redis 事件总线(多实例)
```

### 36.9.1 端到端消息流转（开会场景，验证 V4 因果链）

```
14:00 她正在开会(Phone=silent, Attention 高)
       你发:"你怎么不理我"
       → 消息 DELIVERED(未读, 她根本没看到)      [Phone/Attention]
       → 前端显示: 已发送 ✓

14:35 会议开久了 Attention 下降, 她瞥了一眼手机
       → 消息 READ                            [Attention]
       → 前端显示: 已读 ✓✓
       → Appraisal: 被这句话刺到 → hurt↑        [Appraisal]
       → Drives: avoid > reply → DEFER(已读不回) [Drives]

15:20 会议结束, 但她的 hurt 还在 → 继续不理(不自动回复)

16:40 你发:"刚刚是我语气不好,对不起"
       → Appraisal: warmth↑ / hurt↓ / anger↓     [Appraisal 状态延续]
       → Drives: reply > avoid → REPLY_NOW
       → 她回:"……行吧。"
       → 隔 2 分钟 Expression Loop 补一句:"刚才确实有点生气。"  [Expression Loop]
```

这就是"已读≠会回复""状态延续"而不是"会议结束就自动回"的真实感来源。

---

## 37. V4.1 P2/P3 — 完整 Human Behavior + Natural Expression（完成）

> 通过 **Playwright 真实 UI 实测**发现并修复了 3 个问题，P2/P3 全部落地。

### 37.1 Phone Runtime（她也有手机，手机不是总响的）

新增 `companion_phone_states` 表 + `PhoneStateService`（由作息确定性派生）：

| 时段 | 通知模式 | 手机位置 |
|------|----------|----------|
| 上班 | silent | desk |
| 开会/睡觉 | dnd(勿扰) | other_room |
| 晚间/休闲 | vibrate/sound | hand |

`notificationSalience` 决定"消息能否通过通知触达她"——**手机 dnd 且任务注意力高 → 消息保持未读(DELIVERED)**，她根本没看到。

### 37.2 Attention 动态场（忙 ≠ 永远不看手机）

`AttentionService` 计算 `noticeProbability / inspectProbability / inspectDelayMs`：
- 由 活动注意力 + 精力(stress 代理疲劳) + 手机状态 + 消息显著性 共同决定
- **长时间工作注意力下降 → 反而容易分心看手机**（V4 §七的"14:20→15:10 注意力递减"）
- 已读延迟由 Attention 决定（忙/疲劳 → 慢），替代固定延迟

### 37.3 负面情绪衰减 + Re-engagement

- **负面情绪会随时间愈合**：`EmotionMaintenanceJob` 定时把 `hurt/anger` 衰减 0.08/次——冲突后的生气不会永远挂在脸上
- **Re-engagement**：`ProactiveEngine` 新触发——hurt+anger 高且 1h 无互动 → 她主动低头缓和（"刚才是我不太对……"）

### 37.3.1 主动消息机制重构（V4.2）

> 你指出原实现的两处不符合真人，已重写：

**① 打断成本改为时间正相关曲线（不再是固定加值）**

原实现：深夜 `+0.4`、4h 内聊过 `+0.35`、每日超限 `+0.3` —— 生硬且不合理（你值夜班时深夜不该算打扰；刚聊完不该一律加 0.35）。

新实现：`cost = 0.9·e^(-分钟/55) + 0.15`

| 距上次互动 | 打断成本 | 含义 |
|-----------|---------|------|
| 2 分钟 | 1.00 | 刚聊完，绝不打扰 |
| 30 分钟 | 0.67 | 中等 |
| 1 小时 | 0.45 | 明显回落 |
| 3 小时 | 0.18 | 很低 |
| 隔天 | 0.15 | 几乎为 0，想她了 |

**② 移除机械限流（1h 间隔 + 每日 5 条上限）**

真人没有"每天最多发 5 条"的规矩。改为**自然频率**：上次主动在 2 小时内 → 这次不主动（真人不会连续轰炸）；超过 2 小时 → 完全由成本曲线自然决定，无硬性上限。

**③ 定向触发加 `?force=true`**（测试/运维）：模拟"隔 6 小时没聊"，让 UI 能稳定验证主动消息实时推入。

### 37.3.2 Appraisal 正则误判修复（V4.2）

> 用户指出"深度倾诉被 DEFER 拦截"的根因后，排查并修复了同类隐患：

| 误判场景 | 原行为 | 修复 |
|---------|--------|------|
| "我今天烦死了"（自己烦） | 命中 ACCUSATION `烦死` → 被当指责 → DEFER 已读不回 | `烦死/好烦/烦透了/烦` 移入 DISTRESS（自己烦）；指责只认 `你烦死了/烦不烦` |
| "老板不理我"（第三人称） | 命中 `不理我` → 误判 | 收窄为 `你.*不理我` |
| "他不在乎我"（别人） | 命中 `不在乎` → 误判 | 收窄为 `你.*不在乎` |

**规则**：指责她的关键词必须**明确指向"你"**才触发（`你怎么这么/你真/你.*不理我/你.*说话.*没意思/你烦死了`），自我倾诉（`我好烦/没意义/撑不下去`）一律走 DISTRESS 共情。

### 37.3.3 Expression Loop 触发放宽（V4.2）

原兜底阈值 `>50字 + DEEP + 情绪≥0.5` 偏保守，很少触发"边想边说"。放宽为：

- `>35 字` + `情绪≥0.35`，且
- **DEEP** 或（ENGAGED 且情绪≥0.6）
- 拆分更鲁棒：找不到第二个句号 → 在第一个句号后拆 → 仍没有则第一个逗号后拆

### 37.4 Expression Loop（她的思想真的在展开）

- 深度/情绪强时（阈值见 §37.3.3），即使 LLM 没输出 `<split>`，后端也在标点处**兜底拆成两条**独立消息（先回应，隔 1s 补一句）
- 前端每条独立气泡，配合 typing 三点动画（新增 `typing-dot`）
- 效果：她"边想边说"，而不是一次给一段完整话

### 37.5 Playwright 实测 + 用户评审发现的 Bug（已修复）

| 现象 | 根因 | 修复 |
|------|------|------|
| 深度倾诉"生活没意思" → 她已读不回(DEFER) | ACCUSATION 正则含"没意思"，把"生活没意义"误判成"指责她" | 收窄为正则 `你.*说话.*没意思` 等明确指向；"没意义/没意思"移入 DISTRESS |
| "我今天烦死了" → 被当指责已读不回 | ACCUSATION 含"烦死"（自己烦≠骂她） | `烦死/好烦/烦透了/烦` 移入 DISTRESS；指责只认 `你烦死了/烦不烦`（§37.3.2） |
| 冲突后 hurt/anger 永久累积不愈合 | `decayAllNegative` 存在但从未调用 | `EmotionMaintenanceJob` 调用，每次 -0.08 |
| Expression Loop 从不触发 | 纯靠 LLM 自然输出 `<split>`（低频） | 后端兜底：DEEP/ENGAGED+情绪强+长回复按标点拆两条（§37.3.3） |
| 主动消息"4h内聊过+0.35"生硬 / 深夜+0.4 / 每日5条上限不真人 | cost 用固定加值而非时间曲线 | 改为 `cost=0.9·e^(-分钟/55)+0.15`；移除机械限流（§37.3.1） |
| 深夜睡觉时发消息 → "已读不回"(DEFER) | **顺序 bug**：`decide()`(Drives→DEFER) 先于 Attention 预检；睡觉时 reply 降/avoid 升 → DEFER，走不到"未读"拦截 | **Attention 预检前置**：先判"她有没有看到"再判"回不回"——睡觉/静音+忙+消息不显著 → 保持 `DELIVERED`(未读)，不再 DEFER。夜间验证：3 条消息全 DELIVERED、DEFERRED=0 |

### 37.6 Playwright 实测通过（真实 UI）

| 场景 | 结果 |
|------|------|
| 登录/注册/建伴/聊天 | ✅ |
| read receipts：消息显示"已读" | ✅ |
| 连发 2 条 → 一次回复 | ✅ |
| 冲突消息"你怎么这么烦" → **已读不回**(DEFER) | ✅ |
| 道歉"对不起" → **温和回复**(Appraisal 延续) | ✅ |
| 深度倾诉 → 共情陪伴回复 | ✅ |

---

## 附录

- **设计依据**：《Persistent AI Companion 产品与技术设计方案》v1.0（107 节）
- **代码**：GitHub `Hojay-Chen/companion-agent`
- **运行**：`https://companion.luxera.top`（nginx + systemd jar :8081 + PostgreSQL :5432）
- **规模**：后端 210+ 个 Java 类 · 前端 17 个源文件 · 数据库 36 张表
