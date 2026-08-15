# Luxera Companion — 长期陪伴型 AI 数字伴侣平台

> **不是 Chatbot**：拥有稳定人格、连续人生、持续记忆，随时间与用户建立关系，并在合适的时候主动找你。
>
> 设计依据：《Persistent AI Companion 产品与技术设计方案》v1.0（107 节）；当前完成 **MVP + 大部分 V2 + 部分 V3**。

| 项 | 值 |
|----|----|
| 后端 | Spring Boot 2.7.18 · JDK 17 · 模块化单体（116 个 Java 类） |
| 前端 | React 19 · Vite 8 · TypeScript(strict) · Tailwind CSS 3 · Zustand |
| 数据库 | PostgreSQL 16 · 19 张表 |
| 大模型 | 统一 LLM 网关 → DeepSeek(deepseek-chat)，无 key 自动降级 Mock |
| 线上 | `https://companion.luxera.top`（nginx + systemd jar :8081） |
| 代码 | GitHub `Hojay-Chen/companion-agent`（最新 `ea9a850`） |
| 当前数据量 | 19 用户 / 19 伴侣 / 134 消息 / 104 记忆 / 61 反思记录 |

## 📖 目录

- [1. 产品简介](#1-产品简介)
  - [1.1 产品定位](#11-产品定位)
  - [1.2 核心闭环](#12-核心闭环)
  - [1.3 技术栈](#13-技术栈)
- [2. 总体架构](#2-总体架构)
  - [2.1 架构风格](#21-架构风格)
  - [2.2 逻辑分层](#22-逻辑分层)
  - [2.3 部署拓扑](#23-部署拓扑)
  - [2.4 目录结构](#24-目录结构)
- [3. 模块组织](#3-模块组织)
  - [3.1 模块全景](#31-模块全景)
  - [3.2 模块依赖方向](#32-模块依赖方向)
- [4. 数据架构](#4-数据架构)
  - [4.1 ER 总览](#41-er-总览)
  - [4.2 数据表清单（19 表）](#42-数据表清单19-表)
- [5. 功能实现详解](#5-功能实现详解)
  - [5.1 人格系统](#51-人格系统)
    - [5.1.1 人格编译（PersonaCompiler）](#511-人格编译personacompiler)
    - [5.1.2 版本化与演化](#512-版本化与演化)
  - [5.2 聊天全链路](#52-聊天全链路)
    - [5.2.1 SSE 流式协议](#521-sse-流式协议)
    - [5.2.2 感知引擎](#522-感知引擎)
    - [5.2.3 工作记忆（WorkingMemory）](#523-工作记忆workingmemory)
    - [5.2.4 上下文构建与提示词组装](#524-上下文构建与提示词组装)
    - [5.2.5 输出质量控制（NaturalnessEngine）](#525-输出质量控制naturalnessengine)
    - [5.2.6 一次对话的完整时序](#526-一次对话的完整时序)
  - [5.3 记忆系统](#53-记忆系统)
    - [5.3.1 抽取（每轮异步 LLM）](#531-抽取每轮异步-llm)
    - [5.3.2 检索排序（核心公式）](#532-检索排序核心公式)
    - [5.3.3 关联记忆](#533-关联记忆)
    - [5.3.4 衰减（每周一 04:30）](#534-衰减每周一-0430)
    - [5.3.5 记忆透明与控制](#535-记忆透明与控制)
  - [5.4 用户模型](#54-用户模型)
  - [5.5 关系系统](#55-关系系统)
  - [5.6 Agent 状态](#56-agent-状态)
  - [5.7 反思与人格演化](#57-反思与人格演化)
  - [5.8 主动消息与日常作息](#58-主动消息与日常作息)
    - [5.8.1 主动决策（每 15 分钟）](#581-主动决策每-15-分钟)
    - [5.8.2 日常时间表（CompanionSchedule）](#582-日常时间表companionschedule)
  - [5.9 工具与提醒](#59-工具与提醒)
- [6. 接口设计（API 清单）](#6-接口设计api-清单)
  - [6.1 认证](#61-认证)
  - [6.2 伴侣 / 人格](#62-伴侣--人格)
  - [6.3 会话 / 聊天](#63-会话--聊天)
  - [6.4 记忆](#64-记忆)
  - [6.5 用户模型 / 关系 / 状态 / 提醒 / 通知 / 反思](#65-用户模型--关系--状态--提醒--通知--反思)
  - [6.6 管理（验收/运维）](#66-管理验收运维)
- [7. 快速开始](#7-快速开始)
  - [7.1 环境要求](#71-环境要求)
  - [7.2 本地启动](#72-本地启动)
  - [7.3 LLM 配置](#73-llm-配置)
  - [7.4 生产部署](#74-生产部署)
  - [7.5 冒烟测试](#75-冒烟测试)
- [8. 非功能设计](#8-非功能设计)
  - [8.1 性能](#81-性能)
  - [8.2 安全](#82-安全)
  - [8.3 可扩展性](#83-可扩展性)
  - [8.4 可观测性](#84-可观测性)
- [9. 技术决策与权衡](#9-技术决策与权衡)
- [10. 测试与验证](#10-测试与验证)
- [11. 已知限制与演进路线](#11-已知限制与演进路线)
  - [11.1 限制（如实）](#111-限制如实)
  - [11.2 与设计文档路线图的关系](#112-与设计文档路线图的关系)
  - [11.3 演进路线](#113-演进路线)

---

## 1. 产品简介

### 1.1 产品定位

不是传统 Chatbot（`User → Question → AI → Answer`），而是：

```
User ⇄ Companion
      │
      ├─ Conversation（对话）
      ├─ Memory（记忆）
      ├─ Relationship（关系）
      ├─ Life / Time（人生与时间）
      └─ Shared Experience（共同经历）
```

核心体验追求：**存在感 · 连续性 · 熟悉感 · 人格一致 · 关系成长 · 共同经历 · 适度主动**。

### 1.2 核心闭环

```
User → Conversation → Perception → [Memory / UserModel / State]
→ Relationship → Intention → Behavior → Response → Experience → Reflection
→ [Memory更新 / UserModel更新 / Persona演化] →（循环）
```

### 1.3 技术栈

| 层 | 技术 |
|----|------|
| Web | Spring MVC · SSE(SseEmitter) · nginx 反代(`proxy_buffering off`) |
| 数据 | Spring Data JPA(Hibernate 5.6) · PostgreSQL · JSON 用 `AttributeConverter` 存 text 列 |
| 认证 | Spring Security + jjwt(HS256) + BCrypt |
| 异步/定时 | `@EnableAsync/@EnableScheduling` + 自配线程池 |
| LLM | WebClient(webflux) → DeepSeek `/chat/completions`；结构化用 `response_format=json_object` |
| 前端 | React 19 + Vite 8 + TS strict + Tailwind + Zustand + fetch 流式解析 |

---

## 2. 总体架构

### 2.1 架构风格

**前后端分离 + 后端模块化单体（Modular Monolith）**：单体部署简单、事务一致性强；按业务域清晰切分（14 个包），未来可平滑拆微服务。

### 2.2 逻辑分层

```
表示层   React SPA（/companions · 聊天 · 抽屉 · 设置）
接入层   ChatController(SSE) / 各业务 Controller / JWT 认证
编排层   CompanionRuntime(一次对话) · AgentPostProcessor(异步后处理)
        · ProactiveEngine / ReflectionJob(定时)
领域层   persona / conversation / memory / usermodel / relationship
        / state / reflection / proactive / tool / agent(上下文构建)
基础层   llm(网关) / config / common / JPA Repository
```

### 2.3 部署拓扑

```
浏览器 → https://companion.luxera.top
       → nginx(80/443) ─ /     → /var/www/companion (SPA 产物)
                       └─ /api → 127.0.0.1:8081 (jar, systemd 常驻)
                                → PostgreSQL :5432 (库 companion)
                                → DeepSeek API (HTTPS 出网)
```

### 2.4 目录结构

```
companion-agent/
├── backend/                          # Spring Boot 模块化单体
│   └── src/main/java/com/luxera/companion/
│       ├── auth/  persona/  conversation/  agent/  memory/
│       ├── usermodel/  relationship/  state/  reflection/
│       ├── proactive/  tool/  llm/  config/  common/
├── frontend/                         # React SPA
│   └── src/
│       ├── api/client.ts             # fetch 封装 + SSE 流式解析
│       ├── stores/  components/  pages/  types/
├── scripts/
│   ├── deploy.sh                     # 一键部署(前端+nginx+systemd)
│   └── smoke.sh                      # 端到端冒烟测试
├── PRODUCT_STATUS.md                 # 产品情况报告（面向上级汇报）
└── TECHNICAL_ARCHITECTURE.md         # 架构设计文档（深挖用）
```

---

## 3. 模块组织

### 3.1 模块全景

| 模块 | 职责 |
|------|------|
| `auth/` | 用户系统 + JWT |
| `persona/` | Companion / Persona / 版本化 / LifeEvent / PersonaCompiler / PersonaEvolutionService |
| `conversation/` | Conversation / Message / ChatController(SSE) |
| `agent/` | ★ 运行时核心：感知、工作记忆、上下文、提示词、编排、质量、时间表、异步后处理 |
| `memory/` | 记忆抽取 / 检索 / 衰减 / 强化 / 关联图谱 / 透明 |
| `usermodel/` | 用户事实/偏好/模式/推测 + 纠正机制 |
| `relationship/` | 关系阶段机 + 里程碑 + 共同经历 |
| `state/` | Agent 短期状态（≠人格） |
| `reflection/` | 每日/每周 LLM 反思 |
| `proactive/` | 主动消息决策 + 通知 |
| `tool/` | 提醒 + 生日 + 聊天内建提醒 |
| `llm/` | LLM 统一网关（OpenAI兼容/Anthropic/Mock） |
| `config/` | 安全 / JWT / CORS / 配置绑定 / 当前用户 |
| `common/` | JsonCodec / 异常 / 统一错误 |

### 3.2 模块依赖方向

```
agent(运行时) ──依赖──> persona, memory, usermodel, relationship, state, tool, llm, conversation
proactive     ──依赖──> persona, relationship, conversation, tool, llm, notification
reflection    ──依赖──> conversation, memory, usermodel, llm, persona(演化)
各业务模块     ──依赖──> llm(结构化任务), common, config
```

> 原则：领域模块只通过 service 方法互调，不直接访问对方 Repository；llm 是基础设施层。

---

## 4. 数据架构

### 4.1 ER 总览

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

所有核心表强制携带 `user_id + companion_id`（多租户隔离，查询强制过滤）。

### 4.2 数据表清单（19 表）

| 域 | 表 | 关键字段 |
|----|----|---------|
| 用户 | `users` | username, password_hash(bcrypt), email, timezone, birth_date |
| 用户 | `companions` | name, gender, birth_date, birth_place(JSON), timezone, greeting |
| 用户 | `persona_versions` | version, is_active, persona_json(JSON), change_source, change_reason |
| 用户 | `companion_life_events` | type, title, start_time, end_time, importance, emotional_significance |
| 对话 | `conversations` | title, started_at, last_message_at, message_count, summary |
| 对话 | `messages` | sender_type, content, intent, emotion, topic, is_proactive, metadata(JSON) |
| 记忆 | `memories` | type, content, importance, confidence, emotional_weight, relationship_weight, retrieval_count, occurred_at, source_id |
| 记忆 | `memory_links` | from_memory_id, to_memory_id, relation, strength |
| 用户模型 | `user_facts` / `user_preferences` / `user_patterns` / `user_hypotheses` | predicate/object, category/preference, pattern/evidence, hypothesis/evidence + confidence |
| 关系 | `relationships` | relationship_stage, familiarity, trust, intimacy, affection, message_count |
| 关系 | `relationship_events` / `shared_experiences` | type, title, significance/importance, occurred_at |
| 状态 | `agent_states` | mood, energy, stress, social_energy, curiosity, emotional_closeness |
| 反思 | `reflection_records` | type(daily/weekly), period, summary, insights(JSON), memory_candidates(JSON) |
| 主动 | `companion_notifications` | type, title, content, is_read |
| 工具 | `reminders` | type(birthday/user_set), title, remind_at, status |

> JSON 存储约定：所有 JSON 字段用 `@Convert` + Jackson 转换器存 `text` 列（`common/convert/`），规避 hibernate-types 在 Hibernate 5.6 下对泛型 Map 的兼容问题；未启用 pgvector。

---

## 5. 功能实现详解

### 5.1 人格系统

#### 5.1.1 人格编译（PersonaCompiler）

```
用户自然语言描述 → LLM 结构化输出(低温度+response_format=json_object)
→ Persona POJO（identity/relationship/personality(traits+summary)/communication
              /behaviors/values/boundaries/life）
→ fillDefaults(缺字段补默认) + validate → 场景预览(可反复换场景)
```

#### 5.1.2 版本化与演化

- **版本化**：每次修改/演化，旧版本 `is_active=false`，新版本 `version+1`，记录 `change_source`(user/evolution) 与 `change_reason`。
- **身份时间化**：`birth_date` 存库，**年龄 = current_date − birth_date 动态计算**；生日 = 每年自动提醒。
- **人生时间线**：编译器给 events 则入库，否则按年龄生成默认（小学/中学/大学/第一份工作）。
- **人格演化**（PersonaEvolutionService，每周反思后）：LLM 提出 traits 微调 → **±0.05/次、最多 3 处、traits∈[0.1,0.95]、价值观/边界不可变** → 生成新版本可回溯。

### 5.2 聊天全链路

#### 5.2.1 SSE 流式协议

后端 `ChatController.chat()` 返回 `SseEmitter`（300s 超时），线程池执行；具名事件：

```
event:meta    {intent, emotion, topic}
event:token   {delta}          ← 每个 LLM 增量一次
event:replace {content}        ← 自然度修正时整体替换
event:done    {messageId}
event:error   {message}
```

前端 `api/client.ts` 的 `streamPost`（EventSource 不支持 POST，故用 fetch 读流）：按 `\n\n` 分块 → 解析 `event:`/`data:` 行 → 回调驱动气泡渲染（token 追加 / replace 替换 / done 重拉）。

#### 5.2.2 感知引擎

| 层 | 技术 | 用途 |
|----|------|------|
| 启发式 `PerceptionEngine` | 关键词短路匹配（12 intent / 8 emotion / 8 topic） | 毫秒级兜底 |
| LLM 同步精炼 `PerceptionRefiner` | `structured("perception")` → intent/emotion/topic/entities | **质量优先**，失败回退启发式 |

精炼结果写入消息元数据 + 工作记忆，供本轮 Prompt 使用。

#### 5.2.3 工作记忆（WorkingMemory）

会话级 `companionId:conversationId → {recent≤12条, currentTopic, currentIntent, currentEmotion, currentEntities}`；TTL 720 分钟过期；注入 Prompt"当前会话状态"。内存 `ConcurrentHashMap` 实现（接口可替换 Redis）。

#### 5.2.4 上下文构建与提示词组装

`ContextBuilder` 按优先级聚合：最近消息 → 人格 → 时间+作息 → Agent状态 → 工作记忆 → 工具结果 → 关系摘要 → 记忆(Top-N) → 用户模型 → 行为准则。
**原则**：DB 是源、Prompt 是投影；不塞全量历史。

#### 5.2.5 输出质量控制（NaturalnessEngine）

规则检测并修复：AI 套话（13 类，直接剔除）/ 模板安慰 / 说教式建议 / 过度道歉(≥2) / 过度 emoji(>3) / 回应过长(>500字)。修正后经 SSE `replace` 整体替换。

#### 5.2.6 一次对话的完整时序

```
ChatController(SSE)
  1 保存 user 消息 + 工作记忆
  2 启发式感知 → LLM 同步精炼感知
  3 (request_tool) ReminderPlanner 建提醒
  4 ContextBuilder 聚合 → PromptAssembler 组提示
  5 LlmRouter.chatStream → SSE 逐 token
  6 NaturalnessEngine 校验 → replace 修正
  7 保存 companion 消息 + 工作记忆 → done
  8 异步: MemoryExtractor + UserModelExtractor + RelationshipEngine + AgentStateService
```

### 5.3 记忆系统

#### 5.3.1 抽取（每轮异步 LLM）

`structured("memory-extraction")` → `{episodic[], semantic[], shared[]}` → `saveBatch`：同 content 去重（`max(importance)` + `retrieval_count+1` = **强化**）→ `linkBatch`(同批互链) + `linkNewMemory`(与历史互链)。

#### 5.3.2 检索排序（核心公式）

```java
public double retrievalStrength(int daysSinceOccurred) {
    double recencyDecay = Math.pow(0.92, Math.max(0, daysSinceOccurred));
    double frequencyBoost = 1 + 0.5 * Math.log(1 + retrievalCount);
    return importance * confidence * recencyDecay
            * emotionalWeight * relationshipWeight * frequencyBoost;
}
```

流程：候选(关键词 ∪ 全部 ∪ 向量接口) → 按强度降序 → 过滤 ≥0.02 → 取 topN → **强化(count+1)** → 关联扩展(封顶 2N)。

#### 5.3.3 关联记忆

无向量库，用**中文二元组重叠比**近似语义：清洗标点 → 取每 2 字符的集合 → `交集/最小集 ≥ 0.3` → 双向建链 `relation=same_topic`。检索时沿 `memory_links` 取邻居。

#### 5.3.4 衰减（每周一 04:30）

`occurred_at < now-180天 && importance<0.4 && retrieval_count<3` → 状态 archived。**重要记忆不因时间消失**。

#### 5.3.5 记忆透明与控制

- "为什么你知道"：`/memories/why?q=` 命中 + 以 `occurred_at` 为锚取 ±2 条**来源对话摘录**。
- 控制：搜索 / 遗忘单条 / 清空 / 导出 JSON。

### 5.4 用户模型

- **分层**：事实(explicit 高置信) / 偏好 / 模式(evidence_count 递增) / 推测(低置信 + 证据，Prompt 标注"可能是")。
- **去重**：`findTopBy...map(existing→max(confidence)).orElseGet(save)`。
- **纠正机制**：检测"不是/其实" → 旧推测置信 -0.25(下限0.1) + 写入新事实 + 记"你纠正了她"里程碑。

### 5.5 关系系统

`RelationshipEngine.onMessage`：
- 数值缓慢增长：familiarity+0.0012 / trust+0.0006 / intimacy+0.0004 / affection+0.0005。
- **一次性里程碑**（count==0 判重，significance≥0.8 同时写 shared_experiences）：第一次对话/深夜聊天/难过被安慰/分享好消息/主动关心她/一起计划/关系进入新阶段。
- **阶段机**：`new→familiar→close→deeply_connected`，消息量+数值综合判定（非纯计数）。

### 5.6 Agent 状态

`AgentStateService.onMessage`：energy-0.004 / stress+0.001 / emotionalCloseness+0.0009 / mood 按情绪映射。单行/伴侣，`updated_at` 时间戳。

### 5.7 反思与人格演化

| 任务 | 触发 | 产出 |
|------|------|------|
| 每日反思 | 03:17 | 规则层深夜模式→user_patterns；LLM 对当天对话→summary/insights/记忆候选(入库)/用户洞察 |
| 每周反思 | 周一 05:00 | LLM 对 7 天对话→长期用户理解 / 行为模式(入库) / 关系变化 |
| 人格演化 | 每周反思后 | LLM 提 traits 微调 → 限幅 → 新版本 |

### 5.8 主动消息与日常作息

#### 5.8.1 主动决策（每 15 分钟）

到期提醒先转通知 → 每伴侣过滤(DND 23-8 / 作息SLEEP / 间隔1h / 日上限5) → `decide()` 按优先级评估触发（深夜加班 / 早安 / 傍晚回访 / 好消息跟进 / 沉默回访），每个触发 `expected_value × 作息因子`；打断成本：

```java
double cost = 0.15;
if (h < 8) cost += 0.4;  if (h >= 22) cost += 0.1;
if (4h内聊过) cost += 0.35;
cost += responsive ? -0.1 : 0.1;    // 响应率
if (今日已达上限) cost += 0.3;
// cost ≥ expected_value → DO_NOTHING
```

通过则 `draftMessage()`：**LLM 按"人格+当前作息+场景"生成**（失败回退模板）→ 写通知 + 注入最新会话(`is_proactive=true`)。

#### 5.8.2 日常时间表（CompanionSchedule）

按 `companionId.hashCode()` **确定性派生**作息（每人不同）：工作日 8-10 上班 / 17-19 下班 / 午休 / 休闲 / 23-24 睡；**周末全天休闲**。主动意愿：忙碌×0.35 / 休闲×1.2 / 睡觉=0。作息注入 Prompt"此刻在做什么"（如周六回复"正窝在沙发上喝茶"）。

### 5.9 工具与提醒

- **聊天内建提醒**（ReminderPlanner）：intent=request_tool → LLM 解析 `{remind,title,content,remind_at}`；SYSTEM **注入当前日期**防时间幻觉；过去时间兜底 +1h；建提醒后 toolResult 进 Prompt，回复自然确认。
- **生日系统**（每日 08:05）：确保每位伴侣有下一年待触发生日提醒。

---

## 6. 接口设计（API 清单）

### 6.1 认证

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/auth/register` | 注册（返回 JWT） |
| POST | `/api/auth/login` | 登录 |
| GET | `/api/auth/me` | 当前用户 |

### 6.2 伴侣 / 人格

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/companions/compile` | 自然语言→编译人格+预览 |
| POST | `/api/companions/preview` | 任意场景预览 |
| GET/POST | `/api/companions` | 列表 / 创建 |
| GET/DELETE | `/api/companions/{id}` | 详情（含动态年龄）/ 删除 |
| PUT | `/api/companions/{id}/persona` | 重新描述→新版本人格 |
| GET | `/api/companions/{id}/life-events` | 人生时间线 |
| GET | `/api/companions/{id}/persona/versions` | 人格版本历史 |

### 6.3 会话 / 聊天

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/companions/{cid}/conversations` | 会话列表 |
| POST | `/api/companions/{cid}/conversations/first` | 首个会话（含问候） |
| POST | `/api/companions/{cid}/conversations` | 新建会话 |
| GET | `/api/companions/{cid}/conversations/{id}/messages` | 消息列表 |
| POST | `/api/companions/{cid}/conversations/{id}/chat` | **SSE 流式聊天** |

### 6.4 记忆

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

### 6.5 用户模型 / 关系 / 状态 / 提醒 / 通知 / 反思

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

### 6.6 管理（验收/运维）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/admin/reflection/run` | 手动每日反思 |
| POST | `/api/admin/reflection/run-weekly` | 手动每周反思 |
| POST | `/api/admin/persona/evolve` | 手动人格演化 |
| POST | `/api/admin/proactive/run` | 手动主动消息 |
| POST | `/api/admin/birthday/ensure` | 补生日提醒 |

---

## 7. 快速开始

### 7.1 环境要求

JDK 17 · Maven 3.8+ · Node 18+ · 本地 PostgreSQL（有 `admin`/`shared-secret` 账号）。

### 7.2 本地启动

```bash
# 1. 建库
psql -h 127.0.0.1 -U admin -d postgres -c "CREATE DATABASE companion;"

# 2. 启动后端（端口 8081）
cd companion-agent/backend
./run.sh                              # 或 mvn spring-boot:run

# 3. 启动前端（端口 5173，代理 /api → 8081）
cd companion-agent/frontend
npm install && npm run dev

# 4. 浏览器打开 http://127.0.0.1:5173
```

### 7.3 LLM 配置

默认 DeepSeek（OpenAI 兼容），**不配 key 也能跑**（自动降级 Mock）：

```bash
export DEEPSEEK_API_KEY=sk-xxx
export LLM_BASE_URL=https://api.deepseek.com
export LLM_CHAT_MODEL=deepseek-chat
```

也可 `LLM_PROVIDER=anthropic|mock`（见 `backend/src/main/resources/application.yml` 的 `app.llm.*`）。

### 7.4 生产部署

```bash
sudo bash companion-agent/scripts/deploy.sh
```

自动完成：前端产物 → `/var/www/companion` · nginx 配置 → `/etc/nginx/conf.d/` · `/etc/hosts` · systemd 服务 `luxera-companion-backend` · nginx 重载 · 健康检查。

> ⚠️ `deploy.sh` 会重写 systemd 单元，**必须保留 `EnvironmentFile=/etc/companion/.env`**（内含 `DEEPSEEK_API_KEY`），否则降级 Mock。

### 7.5 冒烟测试

```bash
BASE=http://127.0.0.1:8081 bash companion-agent/scripts/smoke.sh
```

覆盖：注册 → 编译人格 → 创建 → 问候 → SSE 流式聊天 → 记忆抽取 → 关系里程碑 → 生日提醒 → 反思 → 记忆搜索。

---

## 8. 非功能设计

### 8.1 性能

- 聊天主链路：启发式感知（毫秒）+ LLM 感知（~1-2s）+ 回复流式（首 token 快）；异步后处理不阻塞响应。
- 记忆检索：结构化 SQL + 内存排序，单伴侣数据量下 <10ms。
- 定时任务均异步线程池。

### 8.2 安全

- 密码 bcrypt；JWT(HS256) 7 天过期；无状态会话。
- 多租户：所有查询强制 `user_id + companion_id`，删除前校验归属。
- 敏感配置（DeepSeek key）在 `/etc/companion/.env`（root 640），不入 git。
- 生产走 HTTPS（nginx + 泛域名证书）。

### 8.3 可扩展性

- LLM 网关接口化 → 换模型改配置。
- `EmbeddingProvider` 接口 → 接向量库即插即用。
- `WorkingMemory` 接口可换 Redis（多实例共享）。
- 模块化单体 → 边界清晰可拆微服务。

### 8.4 可观测性

- systemd journal 日志；`[LLM] 网关已启用` 标识当前模型（监控降级）。
- 消息元数据存 intent/emotion/topic；自然度 issues 记日志。
- 主动消息决策日志（触发/预期 vs 成本）。

---

## 9. 技术决策与权衡

| 决策 | 权衡 |
|------|------|
| 模块化单体 vs 微服务 | 单体部署简单、事务强；边界已切分可拆 |
| JSON 存 text 而非 jsonb | 规避 hibernate-types 兼容问题；牺牲 JSON 内查询（独立列覆盖） |
| 无向量库，用二元组重叠 | 规避 pgvector 依赖；语义精度有限，可无缝升级 |
| 感知启发式 + LLM 同步精炼 | 质量优先（用户接受延迟）；失败回退保证可用 |
| 异步后处理 | 不阻塞 SSE；抽取/关系/状态后台沉淀 |
| 人格演化保守（±0.05） | 防人格漂移；版本可回溯 |
| 主动消息打断成本 | 防打扰；按时段/作息调节 |

---

## 10. 测试与验证

- **冒烟**（scripts/smoke.sh）：全链路通过。
- **真实模型验证**：回复 2-4 句多样、提醒时间正确、记忆图谱建链、记忆透明带来源、LLM 反思有洞察、人格演化在跑、主动消息按时段 LLM 生成、作息体现（周六回复"窝在沙发喝茶"）。
- **已修 Bug**：LLM 时间幻觉（年份错）→ 注入当前日期+兜底；部署脚本丢 EnvironmentFile 降级 Mock → 已修；HQL `:type` 保留字；hibernate-types JSONB 泛型；`去`字误判 planning 意图。

---

## 11. 已知限制与演进路线

### 11.1 限制（如实）

1. 无向量检索（二元组近似，可升级）。
2. WorkingMemory 单实例内存（多实例需 Redis）。
3. 主动消息仅站内通知，无手机推送。
4. 工具层仅提醒，无 MCP/日历/搜索。
5. 模型 deepseek-chat，无推理/语音/图片/多模态。
6. 单机部署，无高可用、无 K8s。
7. 认证无邮箱验证/OAuth。
8. 多租户应用层过滤，非数据库 RLS。
9. 反思/演化批量定时，非实时。
10. 当前数据多为测试数据。
11. DeepSeek key 失效降级 Mock（已配置 key 并监控）。

### 11.2 与设计文档路线图的关系

| 阶段 | 状态 |
|------|------|
| MVP（§95：用户/伴侣/人格编译/聊天/会话/记忆/用户模型/关系/生日/提醒/基础主动/记忆管理） | ✅ 完成 |
| V2（§97：每日+每周反思/记忆衰减强化/共同经历/人生时间线/高级用户模型/主动Agent/工具调用） | ✅ 大部分（工具调用=提醒；日历/推送未做） |
| V3（§98：关联记忆/人格演化/高级关系引擎） | ◐ 部分（关联记忆+人格演化已做；多模态/语音/实时上下文未做） |
| V4（§99：App/耳机/眼镜生态 + 共享记忆/共享身份） | ⏳ 未启动 |

### 11.3 演进路线

- **短期**：手机推送、向量检索、MCP 工具层（日历/天气/搜索）。
- **中期**：语音对话、用户自定义作息注入、真实用户灰度。
- **长期**：V4 多端共享记忆/身份、K8s 高可用、RLS 加固、审计日志。
