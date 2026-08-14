# Luxera Companion — Persistent AI Companion Platform

长期陪伴型 AI Agent。不是 Chatbot,而是**拥有稳定人格、连续人生、持续记忆、随时间与用户建立关系**的数字伴侣。

> 实现范围: 设计文档 **完整 MVP**(用户系统 / 伴侣创建 / 人格编译器 / 流式聊天 / 会话历史 / 记忆抽取·检索·衰减·遗忘 / 用户模型 / 关系演化 / 生日 / 提醒 / 每日反思 / 基础主动消息 / 记忆管理)。

---

## 技术栈

| 端 | 选型 |
|----|------|
| 后端 | Spring Boot 2.7.18 · JDK 17 · Spring Data JPA · PostgreSQL 16 |
| 前端 | React 19 · Vite 8 · TypeScript(strict) · Tailwind CSS 3 · Zustand · react-router-dom 7 |
| LLM | 统一网关(默认 **DeepSeek** OpenAI 兼容;未配 key 自动降级 **Mock**,离线可跑通全流程) |

---

## 目录结构

```
companion-agent/
├── backend/                    # Spring Boot 模块化单体 (com.luxera.companion)
│   └── src/main/java/com/luxera/companion/
│       ├── auth/               # 用户系统 + JWT
│       ├── persona/            # Companion / Persona 版本化 / LifeEvent / PersonaCompiler
│       ├── conversation/       # 会话 + 消息 + SSE 流式聊天
│       ├── agent/              # CompanionRuntime / ContextBuilder / PromptAssembler / Perception / Naturalness
│       ├── memory/             # 记忆(抽取/检索/排序/衰减/强化)
│       ├── usermodel/          # Fact / Preference / Pattern / Hypothesis + 纠正机制
│       ├── relationship/       # Relationship / 里程碑事件 / 共同经历 + 阶段演化
│       ├── state/              # AgentState(短期状态,≠人格)
│       ├── reflection/         # 每日反思
│       ├── proactive/          # 主动消息引擎(打断成本控制) + 通知
│       ├── tool/               # 提醒 + 生日
│       ├── llm/                # LlmGateway (OpenAI兼容 / Anthropic / Mock)
│       └── common/ config/     # 异常/JSON/安全/CORS/属性
├── frontend/                   # React SPA
│   └── src/
│       ├── api/client.ts       # fetch 封装 + SSE 流式解析
│       ├── stores/             # auth / companion (Zustand)
│       ├── components/         # Avatar / ChatBubble / Drawer / AuthShell
│       └── pages/              # Login / Register / Companions / CompanionCreate / Chat / Settings
└── scripts/smoke.sh            # 端到端冒烟测试
```

---

## 快速开始

**环境要求**: JDK 17 · Maven 3.8+ · Node 18+ · 本地 PostgreSQL(有 `admin`/`shared-secret` 账号)。

```bash
# 1. 建库
psql -h 127.0.0.1 -U admin -d postgres -c "CREATE DATABASE companion;"

# 2. 启动后端 (端口 8081)
cd companion-agent/backend
./run.sh                          # 或: mvn spring-boot:run

# 3. 启动前端 (端口 5173, 代理 /api → 8081)
cd companion-agent/frontend
npm install
npm run dev

# 4. 浏览器打开 http://127.0.0.1:5173
```

### LLM 配置

默认走 DeepSeek(OpenAI 兼容)。**不配 key 也能跑**,自动降级 Mock 网关:

```bash
export DEEPSEEK_API_KEY=sk-xxx          # 配了即用真实模型
export LLM_BASE_URL=https://api.deepseek.com
export LLM_CHAT_MODEL=deepseek-chat
```

也可切换 `LLM_PROVIDER=anthropic` 或 `mock`(见 `backend/src/main/resources/application.yml` 的 `app.llm.*`)。

### 冒烟测试

```bash
BASE=http://127.0.0.1:8081 bash companion-agent/scripts/smoke.sh
```

覆盖: 注册 → 编译人格 → 创建伴侣 → 问候会话 → SSE 流式聊天 → 记忆抽取 → 关系里程碑 → 生日提醒 → 每日反思 → 记忆搜索。

---

## 核心设计落地

- **人格是数据,不是 Prompt**: 人格存 `persona_versions`(JSON,版本化,带变更来源/原因);Prompt 只是运行时投影(`PromptAssembler`)。
- **年龄动态计算**: `birth_date` 存时间线,年龄/生日一律推算,不存"当前年龄"。
- **事实与推测分离**: `user_facts`(明确告知,高置信) vs `user_hypotheses`(推测,带证据,置信不足);用户纠正 → 旧推测降置信、写入新事实。
- **记忆强度**: `importance × confidence × recency_decay × emotional_weight × relationship_weight × retrieval_frequency`;被检索即强化,低活性旧记忆每周归档。
- **时间是一等公民**: 人生事件/关系里程碑/共同经历/记忆都带时间,Prompt 注入当前时间与关系时长。
- **主动消息有打断控制**: DND 时段、频率上限、打断成本 vs 预期价值,成本高则 DO_NOTHING。
- **自然度引擎**: 拦截"作为AI"套话、过度 emoji、说教式建议;流式输出 + 整体替换修正。

## 主要 API

```
POST /api/auth/register | /api/auth/login          # 认证
POST /api/companions/compile                        # 自然语言 → 编译人格 + 预览
POST /api/companions                                # 创建伴侣
GET  /api/companions                                # 列表
POST /api/companions/{cid}/conversations/first      # 首个会话(带问候)
POST /api/companions/{cid}/conversations/{id}/chat  # SSE 流式聊天
GET  /api/companions/{cid}/memories                 # 记忆
GET  /api/companions/{cid}/memories/search?q=       # 记忆透明(为什么你知道)
DELETE /api/companions/{cid}/memories/{mid}         # 遗忘
GET  /api/companions/{cid}/relationship             # 关系 + 里程碑 + 状态
GET  /api/companions/{cid}/user-model/*             # 她对你了解的事实/偏好/模式/推测
POST /api/admin/reflection/run                      # 手动触发每日反思
POST /api/admin/proactive/run                       # 手动触发主动消息
```

## 设计文档对应

本文档对应你提供的《Persistent AI Companion 产品与技术设计方案》v1.0 的第 1-101 节核心内容;MVP 边界按第 95 节,暂缓项按第 96 节(V2 起的 Daily/Weekly 深度反思、日历、复杂多模态、K8s 等均留作后续)。
