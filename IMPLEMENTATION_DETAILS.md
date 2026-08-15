# Luxera Companion 功能实现详解（代码层面）

> 配套文档：`TECHNICAL_ARCHITECTURE.md`（架构总览）、`PRODUCT_STATUS.md`（产品状态）。
> 本文聚焦**每个功能用什么技术、在代码里怎么实现**，含真实代码片段与机制说明。

---

## A. 基础设施技术

### A1. JWT 认证（Spring Security + jjwt）

**技术**：Spring Security Filter Chain + `io.jsonwebtoken:jjwt 0.11.5`（HS256）。

**关键类**：
- `config/SecurityConfig.java`：无状态会话，JWT 过滤器注册在 `UsernamePasswordAuthenticationFilter` 之前；放行 `/api/auth/*`、`/api/health`，其余 `authenticated()`。
- `config/JwtAuthenticationFilter.java`（`OncePerRequestFilter`）：每次请求读 `Authorization: Bearer <token>` → `jwtUtil.isValid(token)` 且用户存在 → 把 `userId` 作为 `Authentication.principal` 写入 `SecurityContextHolder`。
- `config/JwtUtil.java`：`@PostConstruct` 用 secret 构建 HS256 key；`generateToken(userId, username)` 带 7 天过期；`getUserId/isValid` 解析校验。
- `config/CurrentUser.java`：控制器里 `currentUser.requireUserId()` 从 SecurityContext 取当前用户，业务层靠它做归属校验。

**机制**：无状态（不存 Session），每次请求都从 token 恢复身份。密码用 `BCryptPasswordEncoder`。

### A2. JSON 字段存储（JPA AttributeConverter + Jackson）

**技术**：`javax.persistence.AttributeConverter` + Jackson（`common/JsonCodec`）。

**实现**（以人格为例，`common/convert/PersonaJsonConverter.java`）：
```java
@Converter
public class PersonaJsonConverter implements AttributeConverter<Persona, String> {
    public String convertToDatabaseColumn(Persona a) { return JsonCodec.toJson(a); }
    public Persona convertToEntityAttribute(String db) { return JsonCodec.fromJson(db, Persona.class); }
}
```
实体字段标注：
```java
@Convert(converter = PersonaJsonConverter.class)
@Column(name = "persona_json", columnDefinition = "text")
private Persona persona;
```
**机制**：实体内存中是强类型 POJO（Persona/Place/List/Map），落库是 JSON 字符串存 `text` 列。选择 text 而非 jsonb 是为了规避 hibernate-types 在 Hibernate 5.6 下对泛型 Map 报 `propertyClass null` 的兼容问题（已踩坑弃用）。`JsonCodec` 用共享 `ObjectMapper`（注册 JavaTimeModule，忽略未知字段）。

### A3. 异步与定时任务

**技术**：Spring `@EnableAsync` + `@EnableScheduling` + `spring.task.execution` 线程池。

- **异步**：`AgentPostProcessor.afterExchange` 标注 `@Async`，跑在 `applicationTaskExecutor`（core 4 / max 12 / queue 200）。所有 LLM 抽取（记忆/用户模型）与关系/状态演化都不阻塞 SSE。
- **定时**（`application.yml`）：
```yaml
scheduler:
  daily-reflection-cron: 0 17 3 * * *      # 每日 03:17
  weekly-reflection-cron: 0 0 5 * * MON    # 周一 05:00
  proactive-cron: 0 */15 * * * *           # 每 15 分钟
  birthday-cron: 0 5 8 * * *               # 每日 08:05
```
- **手动触发**：`/api/admin/*/run`（`AdminController`），验收/运维用。

---

## B. LLM 网关

### B1. 统一接口与路由

```java
public interface LlmGateway {
    String name();
    boolean available();
    ChatResult chat(ChatRequest request);
    void chatStream(ChatRequest request, Consumer<String> onDelta);
    StructuredResult structured(StructuredRequest request);
}
```
`LlmRouter`（门面）在 `@PostConstruct` 按 `app.llm.provider` 选实现：
- `openai-compatible`（DeepSeek，当前）/ `anthropic` / `mock`；
- **未配 `DEEPSEEK_API_KEY` 时自动降级 mock**（日志告警，避免服务不可用）。

业务代码只依赖 `LlmRouter`，换模型零改动。

### B2. DeepSeek 网关（WebClient 流式 + JSON 结构化）

`OpenAiCompatibleGateway`：
- **基础**：`WebClient`（webflux），baseUrl `https://api.deepseek.com`，POST `/chat/completions`，`Authorization: Bearer <key>`。
- **非流式** `chat()`：组装 `{model, temperature, max_tokens, messages}` → 解析 `choices[0].message.content`。
- **流式** `chatStream()`：`bodyToFlux(DataBuffer)` 拿原始字节流 → 按行累积解析：
```java
// 核心：SSE 行解析
private void processLine(String line, Consumer<String> onDelta) {
    if (!line.startsWith("data:")) return;
    String data = line.substring(5).trim();
    if (data.equals("[DONE]")) return;
    JsonNode n = mapper.readTree(data);
    String delta = n.path("choices").path(0).path("delta").path("content").asText(null);
    if (delta != null && !delta.isEmpty()) onDelta.accept(delta);
}
```
- **结构化** `structured()`（抽取/感知/反思/演化/提醒解析共用）：强制 `response_format:{type:"json_object"}` + 低温度 → 返回 `StructuredResult`（内含 `JsonNode`，业务层直接 `.path("xxx")` 取字段）。

**机制要点**：所有"让模型输出结构化数据"的任务统一走 `structured()`，用 JSON schema 约束 + 低温度保证稳定解析；失败在调用方 try/catch 兜底。

### B3. Mock 网关

`MockLlmGateway`：`available()=true`；聊天按"意图/情绪关键词模板"返回；结构化任务按 `req.getTask()` 返回对应罐装 JSON（persona-compile / memory-extraction / perception / daily-reflection / weekly-reflection / persona-evolution / reminder-extraction…）。保证无 key 时全流程可跑。

---

## C. 聊天全链路

### C1. 后端 SSE 流式（SseEmitter + 线程池）

`ChatController.chat()`：
```java
SseEmitter emitter = new SseEmitter(300_000L);
taskExecutor.execute(() -> streamChat(emitter, userId, companionId, conversationId, content));
return emitter;
```
`streamChat()` 主流程（见"6.2 一次聊天的完整流程"）用 `send()` 发送具名事件：
```java
private void send(SseEmitter emitter, String event, Object data) {
    emitter.send(SseEmitter.event().name(event).data(data));   // data 由 Jackson 序列化
}
```
**SSE 协议**：`event:meta`(intent/emotion/topic) → `event:token`(delta，每段一次) → `event:replace`(自然度修正时整体替换) → `event:done`(messageId) → `event:error`。

**为什么用 SseEmitter**：服务端单向流，Spring MVC 原生支持；配合 nginx `proxy_buffering off` 逐段透传。

### C2. 前端流式解析（fetch + ReadableStream）

`api/client.ts` 的 `streamPost`：不能直接 POST 给 EventSource（EventSource 只支持 GET），所以用 fetch 读流：
```ts
const reader = res.body.getReader()
const decoder = new TextDecoder()
let buffer = ''
for (;;) {
  const { done, value } = await reader.read()
  if (done) break
  buffer += decoder.decode(value, { stream: true })
  while ((idx = buffer.indexOf('\n\n')) !== -1) {   // SSE 块以空行分隔
    const block = buffer.slice(0, idx); buffer = buffer.slice(idx + 2)
    emitBlock(block, onEvent)                        // 解析 event:/data: 行
  }
}
```
`emitBlock` 按 `event:` 名和 `data:` JSON 回调。`Chat.tsx` 里：
- `token` → 追加到流式气泡
- `replace` → 整体替换气泡内容
- `done` → 重新拉取消息列表
- `meta` → 可展示意图/情绪

### C3. CompanionRuntime 编排

`CompanionRuntime.generate(userId, companionId, conversationId, userMessageId, userText, recent, onDelta)`：
```
启发式感知 → LLM 同步精炼感知 → (若 request_tool) 建提醒拿 toolResult
→ ContextBuilder.build(...)  → PromptAssembler.buildSystem(ctx)
→ LlmRouter.chatStream(...)  → NaturalnessEngine.validate(全文)
→ 异步 AgentPostProcessor.afterExchange(...)
返回 ChatOutcome(reply, rawReply, perception, context)
```

### C4. Prompt 组装（PromptAssembler）

系统提示按优先级拼装：身份人格(`PersonaText.describe`) → 时间+作息 → Agent状态 → 工作记忆 → 工具结果 → 关系摘要 → 记忆(Top-N) → 用户模型 → 行为准则。
**原则**：DB 是源、Prompt 是投影；只注入最近消息 + 检索记忆，不塞全量历史。

---

## D. 感知引擎

### D1. 启发式（PerceptionEngine）

关键词规则映射，纯字符串匹配、毫秒级：
- intent：12 类（greeting/correction/say_goodnight/farewell/gratitude/request_tool/ask_about_her/compliment/question/share_tired/share_upset/share_joy/planning/chat）
- emotion：8 类（tired/sad/anxious/angry/happy/lonely/grateful/neutral）
- topic：8 类（work/study/health/relationship/entertainment/food/travel/weather/daily）

实现即 `containsAny(text, "好累","加班",...)` 短路匹配。

### D2. LLM 同步精炼（PerceptionRefiner）

`refineNow(userMessageId, companionId, conversationId, userText, heuristic)`：
```java
if (!"llm".equals(props.getAgent().getIntentExtraction())) return heuristic;
var res = llm.structured(StructuredRequest.builder().task("perception")
        .system(SYSTEM).user(userText).temperature(0.2).build());
// 解析 intent/emotion/topic/entities
// 更新用户消息元数据(setIntent/setEmotion/setTopic + save)
// 更新工作记忆(含 entities)
return refined;   // 失败 catch → return heuristic
```
**机制**：质量优先（用户接受延迟）。精炼结果直接进入本轮 Prompt 与消息元数据，关系/状态也用它。

---

## E. 记忆系统

### E1. 抽取（MemoryExtractor，@Async）

每轮对话后 LLM 结构化：
```
"memory-extraction" → {episodic:[{content,summary,importance,emotional_weight,occurred_at}],
                       semantic:[...], shared:[...]}
```
→ `MemoryService.saveBatch(userId, companionId, "conversation", conversationId, list)`
→ `save(m)` 内去重：`findTopByUserIdAndCompanionIdAndContentAndStatusOrderByCreatedAtDesc`，命中则 `max(importance)` 且 `retrieval_count+1`（强化）。
→ 建关联：`linkBatch(同批互链)` + `linkNewMemory(与历史互链)`。

### E2. 检索排序（MemoryService.retrieve）

```java
// Memory.java —— 检索强度公式
public double retrievalStrength(int daysSinceOccurred) {
    double recencyDecay = Math.pow(0.92, Math.max(0, daysSinceOccurred));
    double frequencyBoost = 1 + 0.5 * Math.log(1 + retrievalCount);
    return importance * confidence * recencyDecay
            * emotionalWeight * relationshipWeight * frequencyBoost;
}
```
`retrieve` 流程：
```
候选 = searchByKeyword(query) ∪ (query空→全部) ∪ EmbeddingProvider(Noop)
按 retrievalStrength 降序 → 过滤 ≥ memory-min-strength(0.02) → 取 topN
对每条 reinforce(count+1, lastRetrievedAt=now)   ← 被检索=再次回忆
→ 关联扩展 associationService.expand(top)，封顶 2N
```

### E3. 关联记忆（MemoryAssociationService）

无向量库，用**中文二元组重叠比**近似语义：
```java
static Set<String> bigrams(String s) {
    Set<String> set = new HashSet<>();
    String clean = s.replaceAll("[\\s\\p{Punct}，。！？、；：【】《》]", "");
    for (int i = 0; i + 2 <= clean.length(); i++) set.add(clean.substring(i, i + 2));
    return set;
}
// 重叠比 = 交集大小 / min(两个集合大小)，≥0.3 → 双向建链(relation=same_topic, strength=overlap)
```
检索时 `expand()`：按 `from/to` 查 `memory_links`，取邻居记忆并入结果集。

### E4. 衰减（MemoryDecayService，每周一 04:30）

`findStaleActive(now-180天)` → `importance<0.4 && retrieval_count<3` → status=`archived`。

### E5. 透明"为什么你知道"（MemoryController）

`/memories/why?q=`：`retrieve(q)` 命中 → 每条取 `sourceExcerpt(m)`：
- `m.sourceType=="conversation"` → 用 `m.sourceId`(conversationId) 查消息
- 以 `m.occurredAt` 为锚点，取 ±2 条消息作"来源对话"

---

## F. 用户模型

### F1. 抽取与去重（UserModelExtractor / UserModelService）

LLM 结构化 `"user-model-extraction"` → `{facts[], preferences[], hypotheses[], corrections[]}`。
`saveFact` 去重：
```java
facts.findTopByUserIdAndCompanionIdAndPredicateAndObjectAndStatus(...)
  .map(existing -> { existing.setConfidence(max(...)); ...; return save(existing); })
  .orElseGet(() -> facts.save(f));
```
即"同一条事实重复出现只强化置信，不重复插入"。

### F2. 纠正机制

抽取结果含 `corrections` → `UserModelService.correct()`：所有 active 推测 `confidence = max(0.1, conf-0.25)`；同时写入新 explicit fact；关系引擎记"你纠正了她"里程碑。

---

## G. 关系引擎

`RelationshipEngine.onMessage(userId, companionId, time, emotion, intent)`：
```java
familiarity += 0.0012; trust += 0.0006; intimacy += 0.0004; affection += 0.0005; // 缓慢增长
// 里程碑(一次性, 用 eventRepo.countByRelationshipIdAndType==0 保证只记一次)
// significance≥0.8 时同时写 shared_experiences
// 阶段机
newStage = msgs>=300||(msgs>=150&&intimacy>0.55) ? "deeply_connected"
         : msgs>=100||(msgs>=50&&intimacy>0.45)  ? "close"
         : msgs>=30||(msgs>=15&&familiarity>0.2) ? "familiar" : "new";
```
**机制**：数值增量很小（防"聊几次就熟"），阶段由消息量+数值综合判定，非纯计数。

---

## H. 反思与人格演化

### H1. 反思（ReflectionService）

- 每日：规则层统计深夜活跃→`user_patterns`；LLM 层 `"daily-reflection"` 对当天对话(≤3000字)产出 summary/insights/memory_candidates(入库)/user_insights/relationship_candidates。
- 每周：`"weekly-reflection"` 对 7 天对话(≤5000字)产出长期理解 + behavioral_patterns(入库)。
- 全部写入 `reflection_records`（period 为日期区间）。

### H2. 人格演化（PersonaEvolutionService）

```java
double delta = Math.max(-0.05, Math.min(0.05, rawDelta));   // 限幅
// 只接受 field 前缀 "personality.traits."
double updated = clamp(cur + delta);                          // [0.1, 0.95]
if (Math.abs(updated - cur) >= 0.01) { traits.put(trait, updated); applied++; }
// applied>0 → personaService.update(companionId, persona, reason, "evolution")
```
**机制**：每次最多改 3 处、±0.05 以内、价值观/边界/身份不可变、生成新版本可回溯。

---

## I. 主动消息与作息

### I1. 主动决策（ProactiveEngine）

每 15 分钟：到期 Reminder 先转通知；每伴侣过滤 DND(23-8)/作息SLEEP/最小间隔1h/每日上限5 → `decide()` 按优先级评估触发，每个触发 `expected_value × schedule.proactiveFactor(activity)`；打断成本：
```java
double cost = 0.15;
if (h < 8) cost += 0.4;  if (h >= 22) cost += 0.1;
if (lastInteraction 在4h内) cost += 0.35;
cost += responsive ? -0.1 : 0.1;      // 响应率
if (今日已达上限) cost += 0.3;
```
`cost >= expected_value → DO_NOTHING`；通过则 `draftMessage()`（LLM 按人格+作息+场景生成，失败回退模板）→ 写 Notification + 注入最新会话(`is_proactive=true`)。

### I2. 日常时间表（CompanionSchedule）

```java
Schedule s = new Schedule(8 + h%3, 17 + (h/3)%3, 23 + (h/18)%2, 6 + (h/9)%2);  // h = companionId.hashCode()
// 工作日分支: MORNING/WORK_BUSY/LUNCH/WORK_AFTERNOON/EVENING/LEISURE/LATE_NIGHT
// 周末分支: 睡到自然醒 + 全天休闲
public double proactiveFactor(...) { return switch(activity) {
    SLEEP->0; WORK_BUSY->0.35; MORNING->0.6; LATE_NIGHT->0.65; LUNCH/EVENING->0.9; LEISURE->1.2; }; }
```
**机制**：同一伴侣每次结果一致（hash 派生），不同伴侣作息不同；注入 Prompt 的"此刻在做什么"让回复带生活感。

---

## J. 工具与提醒

### J1. 聊天内建提醒（ReminderPlanner）

intent=request_tool 时：
```java
// SYSTEM 注入"今天是 X,现在是 HH:mm"防止模型幻觉年份
// LLM structured("reminder-extraction") → {remind, title, content, remind_at}
LocalDateTime remindAt = parseTime(...);   // ISO / "HH:mm" / 空
if (remindAt == null || remindAt.isBefore(now)) remindAt = now.plusHours(1);  // 过去时间兜底
reminderService.create(...) → 返回 toolResult 注入 Prompt → 回复自然确认
```
**机制**：`parseTime` 支持 ISO、`yyyy-MM-dd HH:mm`、`HH:mm` 三种；过去时间一律兜底为 +1h（防幻觉错误年份导致提醒永远在"过去"）。

---

## K. 前端技术细节

### K1. 状态管理（Zustand）

`stores/auth.ts`：`token`(localStorage 持久化) + `user`；`login/register/fetchMe/logout`。`stores/companion.ts`：伴侣列表缓存。
组件通过 `useAuthStore((s)=>s.login)` 订阅，token 失效自动清空跳登录。

### K2. 聊天页渲染（Chat.tsx）

- `streamPost` 回调驱动：`token` 追加到 `streamingText` → 渲染 `<ChatBubble streaming>`；`done` 后 `loadMessages` 重新拉取。
- **时间显示**：消息间插入日期分隔（今天/昨天/yyyy年M月d日，`isSameDay` 判断），每条气泡下 `formatTime` 显示 HH:mm；记忆卡片 `timeAgo`（"3 分钟前/2 小时前/5 天前"）。
- 抽屉（记忆/她懂你/关系/提醒/通知）按 tab 懒加载 API；记忆面板含图谱（graph links 渲染）+ 来源"为什么"（/source 展开）。

### K3. API 客户端

`api/client.ts`：fetch 封装（自动带 `Authorization: Bearer`、错误解析 `{error}`、204 处理），`streamPost` 处理 SSE。

---

## 附：技术栈速查

| 层 | 技术 |
|----|------|
| Web | Spring MVC，SSE via SseEmitter；nginx 反代 `proxy_buffering off` |
| 数据 | Spring Data JPA（Hibernate 5.6）+ PostgreSQL；JSON 用 AttributeConverter |
| 认证 | Spring Security + jjwt(HS256) + BCrypt |
| 异步/定时 | @EnableAsync/@EnableScheduling + 自配线程池 |
| LLM | WebClient(webflux) → DeepSeek `/chat/completions`；结构化用 `response_format=json_object` |
| 前端 | React 19 + Vite 8 + TS strict + Tailwind 3 + Zustand + fetch流式 |
| 运行 | systemd jar(8081) + nginx(80/443) + PostgreSQL(5432) |
