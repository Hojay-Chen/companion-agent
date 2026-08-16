# Companion Agent V7 — Human Runtime 实施记录

> 本文档是 Claude Code 执行 V7 的实施计划与进度记录。
> 依据: `realman-agent-v7.md`（64 节）。
> 核心目标: **让用户无法仅通过聊天行为判断对方是 AI。**
> 核心原则: **Chat App 是应用, Agent 是人。**

## ✅ 完成状态（2026-08-17）

- **P0 Sleep Runtime** ✅ — 取消固定作息, 睡眠改为 Emergent Behavior:
  - `circadian_states`(chronotype/sleep_pressure/sleep_debt/circadian_phase_shift)
  - `sleep_sessions`(睡眠历史, 时长/质量/类型/原因)
  - `SleepModel`: Process S(压力指数逼近) + Process C(昼夜节律 cos 波) + 身体/环境 + 动机
  - `SleepDecision`: SLEEP/STAY_AWAKE/DELAY_SLEEP/NAP —— 深夜聊天可 STAY_AWAKE
  - `SleepTickJob` 每 2 分钟推进
  - `CompanionSchedule` 重构: 睡眠优先(emergent), 社会活动按 chronotype 派生
- **P1 通信解耦** ✅ — POST /messages 立即返回(<50ms), Agent 完全异步:
  - `V7MessageController`: 立即持久化返回 DELIVERED(不阻塞)
  - `V7AgentRuntime`: 异步处理完整认知链, 回复经事件总线推送
  - 前端 Chat.tsx 改用 POST /messages + GET /events
  - 工程约束: Agent 异常不阻塞消息发送
- **P2 Phone Runtime** ✅ — 通知生命周期:
  - `phone_notifications`(delivered/heard/seen/opened/read 逐步推进)
  - "听到铃声"与"看到消息"分离(洗澡 heard=false / 客厅 heard=true 没拿手机)
  - `PhoneNotificationService.advance` 基于 Attention 判定各阶段
- **P3 Cognitive Wakeup** ✅ — 事件驱动认知分级:
  - `CognitiveWakeupService`: NO_WAKE/MICRO_WAKE/ATTENTION/DELIBERATION/DEEP_THINKING
  - "哈哈"→MICRO_WAKE(不打扰), "被裁了"→DEEP_THINKING
  - 用户消息绝不 NO_WAKE(真人收到消息至少会知道)
- **P4 Activity 惯性** ✅ — `LifeInterruptService.activityInertiaMs`:
  - 看剧(高专注)→5 分钟后才可能看手机; 洗澡(无手机)→15 分钟; 休闲→随手看
- **P5 Intention Runtime** ✅ — 意图记忆:
  - `intentions`(content/importance/expected_time/activation_probability/status)
  - 激活概率随时间演化(到 expected_time 升, 过期衰减→FORGOTTEN)
  - "忙完忘了回复"不是 bug, 而是意图被打断→激活概率下降→后来想起
- **P6 Behavioral Entropy** ✅ — `BehavioralEntropyEvaluator`:
  - 睡眠时间分布(有均值+方差, 不固定)/回复延迟/主动分布
- **P7 验收** ✅ — `scripts/v7_check.sh` 端到端全过 + 115 单元测试全绿

## V7 相比 V6 的核心升级

| # | V7 升级 | 实现 |
|---|---------|------|
| 1 | 取消固定作息(§1/§2) | SleepModel emergent, 不再 if time>=sleepTime→SLEEP |
| 2 | 睡眠模型(§3-§9) | Process S + Process C + Sleep History + Sleep Decision |
| 3 | 意志克服睡意(§5) | 深夜聊天+强动机→STAY_AWAKE(场景3) |
| 4 | 睡眠反哺未来作息(§6) | 午睡→当晚推迟, 习惯从历史涌现 |
| 5 | 通信解耦(§10-§21) | POST /messages 立即返回 + Agent 异步 |
| 6 | Phone Runtime(§14-§17) | 通知→听到→看到→打开→阅读 分离 |
| 7 | Cognitive Wakeup(§22-§23) | 事件分级唤醒, 低价值不调 LLM |
| 8 | 活动惯性(§29) | 看剧被消息打断→等这集看完再看 |
| 9 | Intention(§35-§36) | 意图记忆/衰减/激活/遗忘 |
| 10 | Behavioral Entropy(§50-§51) | 规律是统计规律, 不决定每次行为 |

## 架构决策（记录）

1. **睡眠 emergent**: 彻底删除固定睡眠时段; 睡眠由压力+节律+动机综合决定, 行为历史反哺未来作息。
2. **通信单向依赖**: Chat Server → Event → Agent Runtime, 用户发消息永不阻塞。
3. **用户消息绝不 NO_WAKE**: 真人收到消息至少会"知道"(MICRO_WAKE), 只是可能不深想。
4. **只模拟影响用户可观察行为的内部过程**(§58): 睡眠/通知/惯性都映射到"她会不会回/什么时候回"。
5. **Modular Monolith**: 保持单体, 模块事件化, 不拆微服务。

## 验证

- `mvn clean test` → **115 测试全绿**(含 SleepModel/PhoneNotification/Intention/BehavioralEntropy/CognitiveWakeup/V7MessageController)
- `scripts/v7_check.sh` → 全过(表结构/通信解耦 25ms/Phone Notification/行为熵)
- 端到端: POST /messages 32ms 立即返回 + 异步回复产生
- 生产服务 8081 正常
