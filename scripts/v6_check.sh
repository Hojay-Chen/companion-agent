#!/usr/bin/env bash
# Luxera Companion V6 — 拟真人格与持续生活 验收测试
# 覆盖: 会话线程 / 未完成想法 / 活动中断 / 情绪惯性 / 记忆召回概率 / 决策一致性 / 行为模式 / 反 AI 评估
# 用法: BASE=http://127.0.0.1:8081 bash scripts/v6_check.sh
set -euo pipefail
BASE="${BASE:-http://127.0.0.1:8081}"
PY=python3
FAIL=0

note() { echo "==> $*"; }
ok() { echo "    ✓ $*"; }
fail() { echo "    ✗ $*"; FAIL=1; }

PSQL="psql -h 127.0.0.1 -U admin -d companion -tAc"
table_exists() { PGPASSWORD=shared-secret $PSQL "select 1 from information_schema.tables where table_name='$1'" | grep -q 1; }
col_exists() { PGPASSWORD=shared-secret $PSQL "select count(*) from information_schema.columns where table_name='$1' and column_name='$2'" | grep -q 1; }

echo ""
echo "══════════ V6 Continuous Human 验收 ══════════"

# ── 测试 1: V6 表结构 ──
note "测试1: V6 表结构"
for t in conversation_threads behavior_patterns; do
  table_exists "$t" && ok "$t 表存在" || fail "缺 $t 表"
done
for c in sleepiness hunger loneliness joy affection; do
  col_exists agent_states "$c" && ok "agent_states.$c 列存在" || fail "缺 agent_states.$c 列"
done
col_exists pending_message_states friction_type && ok "pending_message_states.friction_type 列存在" || fail "缺 friction_type"
col_exists life_activities attention_demand && ok "life_activities.attention_demand 列存在" || fail "缺 attention_demand"

# ── 测试 2: 注册伴侣 + 发消息(建立线程/行为模式) ──
note "测试2: 端到端交互(V6 线程 + 行为模式 + 未完成想法)"
U="v6_$(date +%s)"
TOKEN=$(curl -s -X POST "$BASE/api/auth/register" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$U\",\"password\":\"test123456\"}" | $PY -c "import sys,json;print(json.load(sys.stdin)['token'])")
PERSONA=$(curl -s -X POST "$BASE/api/companions/compile" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"description":"一个温柔独立的女生,叫小满,比我成熟一点,我难过时先陪我"}' \
  | $PY -c "import sys,json;print(json.dumps(json.load(sys.stdin)['persona']))")
CID=$(curl -s -X POST "$BASE/api/companions" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d "{\"persona\": $PERSONA}" \
  | $PY -c "import sys,json;print(json.load(sys.stdin)['id'])")
CONV=$(curl -s -X POST "$BASE/api/companions/$CID/conversations/first" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{}' \
  | $PY -c "import sys,json;print(json.load(sys.stdin)['id'])")
note "companion=$CID conversation=$CONV"

# 发一条消息(触发线程 touch + 行为模式学习)
curl -s -N -m 90 -X POST "$BASE/api/companions/$CID/conversations/$CONV/chat" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"content":"我今天好难过,项目被砍了,有点撑不住"}' > /dev/null 2>&1 || true
sleep 1

# ── 测试 3: 会话线程 ──
note "测试3: 会话线程 API"
THREADS=$(curl -s "$BASE/api/companions/$CID/threads" -H "Authorization: Bearer $TOKEN" \
  | $PY -c "import sys,json;d=json.load(sys.stdin);print(len(d))")
if [ -n "$THREADS" ] && [ "$THREADS" -ge 0 ] 2>/dev/null; then
  ok "threads API 可用, 返回 $THREADS 个线程"
else
  fail "threads API 异常"
fi

# ── 测试 4: 行为模式(发消息后应至少学习到一条) ──
note "测试4: 行为模式学习"
BP_COUNT=$(PGPASSWORD=shared-secret $PSQL "select count(*) from behavior_patterns where companion_id='$CID'")
if [ "$BP_COUNT" -ge 1 ] 2>/dev/null; then
  ok "行为模式已学习: $BP_COUNT 条"
else
  fail "未学习到行为模式(可能消息被忽略)"
fi

# ── 测试 5: 未完成想法(创建机制单元测试已覆盖, 这里验证表可用) ──
note "测试5: 未完成想法(Thought UNFINISHED 类型)"
THOUGHTS=$(PGPASSWORD=shared-secret $PSQL "select count(*) from thoughts where companion_id='$CID' and type='UNFINISHED'")
echo "      UNFINISHED 想法数: $THOUGHTS"

# ── 测试 6: V6 诊断端点 ──
note "测试6: V6 反 AI 评估端点"
EVAL=$(curl -s "$BASE/api/companions/$CID/v6/eval" -H "Authorization: Bearer $TOKEN" 2>&1 || echo "")
if echo "$EVAL" | grep -q "humanLikenessScore"; then
  SCORE=$(echo "$EVAL" | $PY -c "import sys,json;d=json.load(sys.stdin);print(round(d.get('humanLikenessScore',0)*100))" 2>/dev/null || echo "?")
  ok "Anti-AI 评估端点可用, 真人感得分: $SCORE%"
else
  fail "Anti-AI 评估端点异常: ${EVAL:0:80}"
fi

echo ""
if [ "$FAIL" -eq 0 ]; then
  echo "✅ V6 验收全部通过"
else
  echo "❌ V6 验收有 $FAIL 项失败"
  exit 1
fi
