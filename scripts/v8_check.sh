#!/usr/bin/env bash
# Luxera Companion V8 — Digital Person / Chat Core 验收测试
# 覆盖: 同步落库(POST /messages 立即持久化, 刷新不丢) / clientMessageId 幂等
#       / Person+多维关系(创建伴侣指定关系类型) / 会话参与者 / 事件日志游标(SSE) / 行为引擎
# 用法: BASE=http://127.0.0.1:8081 bash scripts/v8_check.sh
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
echo "══════════ V8 Digital Person 验收 ══════════"

# ── 测试 1: V8 表结构 ──
note "测试1: V8 数据表/列"
for t in event_log digital_world_events persons conversation_participants; do
  table_exists "$t" && ok "$t 表存在" || fail "缺 $t 表"
done
for c in client_message_id; do
  col_exists messages "$c" && ok "messages.$c 列存在" || fail "缺 messages.$c"
done
for c in tension reciprocity connection_pressure; do
  col_exists relationships "$c" && ok "relationships.$c 列存在" || fail "缺 relationships.$c"
done

# ── 测试 2: 登录 + 创建伴侣(指定关系类型) + 会话 ──
note "测试2: 端到端交互(创建伴侣时指定关系)"
V8_USER="${V8_USER:-haojie.chen.njau@gmail.com}"
V8_PASS="${V8_PASS:-20040719chj}"
TOKEN=$(curl -s -m 15 -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$V8_USER\",\"password\":\"$V8_PASS\"}" | $PY -c "import sys,json;print(json.load(sys.stdin)['token'])" 2>/dev/null)
if [ -z "$TOKEN" ]; then
  fail "登录失败, 无法进行端到端测试(账号: $V8_USER)"
  echo "     提示: 注册已关闭, 需用已有账号或通过 V8_USER/V8_PASS 环境变量指定"
  exit 1
fi
PERSONA=$(curl -s -X POST "$BASE/api/companions/compile" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"description":"一个温柔独立的女生,叫小满,有自己的生活"}' \
  | $PY -c "import sys,json;print(json.dumps(json.load(sys.stdin)['persona']))" 2>/dev/null)
CID=$(curl -s -X POST "$BASE/api/companions" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d "{\"persona\": $PERSONA, \"relationshipType\": \"best_friend\"}" \
  | $PY -c "import sys,json;print(json.load(sys.stdin)['id'])" 2>/dev/null)
CONV=$(curl -s -X POST "$BASE/api/companions/$CID/conversations/first" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{}' \
  | $PY -c "import sys,json;print(json.load(sys.stdin)['id'])" 2>/dev/null)
note "companion=$CID conversation=$CONV"

# ── 测试 3: 关系类型真实落库(不是 Prompt) ──
note "测试3: Person + 关系类型落库"
RTYPE=$(PGPASSWORD=shared-secret $PSQL "select relationship_type from relationships where companion_id='$CID'" 2>/dev/null | head -1 | tr -d ' ')
[ "$RTYPE" = "best_friend" ] && ok "relationship_type=best_friend 已落库" || fail "关系类型未落库: '$RTYPE'"
INTIM=$(PGPASSWORD=shared-secret $PSQL "select intimacy from relationships where companion_id='$CID'" 2>/dev/null | head -1)
echo "    (best_friend 初始亲密 = $INTIM, 应 > 0.3)"
PGPASSWORD=shared-secret $PSQL "select 1 from persons where companion_id='$CID'" 2>/dev/null | grep -q 1 \
  && ok "persons 中已建 Agent 的 Person" || fail "缺 Agent Person"
PGPASSWORD=shared-secret $PSQL "select 1 from conversation_participants where conversation_id='$CONV'" 2>/dev/null | grep -q 1 \
  && ok "conversation_participants 已注册参与者" || fail "缺会话参与者"

# ── 测试 4: POST /messages 同步落库(刷新不丢) ──
note "测试4: 消息同步落库(唯一真相源)"
T0=$(date +%s%N)
RESP=$(curl -s -m 10 -X POST "$BASE/api/companions/$CID/conversations/$CONV/messages" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"content":"我今天被老板骂了,好难过","clientMessageId":"v8-e2e-1"}' 2>&1 || echo '{"error":"timeout"}')
T1=$(date +%s%N)
MS=$(( (T1 - T0) / 1000000 ))
MID=$(echo "$RESP" | $PY -c "import sys,json;print(json.load(sys.stdin).get('messageId',''))" 2>/dev/null || echo "")
if [ -n "$MID" ] && [ "$MID" != "我今天被老板骂了,好难过" ]; then
  ok "POST 返回 canonical messageId=$MID(耗时 ${MS}ms)"
  # 立即查库(不等 Agent) → 消息已存在
  INDB=$(PGPASSWORD=shared-secret $PSQL "select count(*) from messages where id='$MID' and sender_type='user'" 2>/dev/null | head -1 | tr -d ' ')
  [ "$INDB" = "1" ] && ok "消息已同步落库(无需等 Agent, 刷新/重启不丢)" || fail "消息未落库(INDB=$INDB)"
else
  fail "POST 未返回真实 messageId: ${RESP:0:120}"
fi

# ── 测试 5: clientMessageId 幂等 ──
note "测试5: clientMessageId 幂等(重复提交不重复入库)"
RESP2=$(curl -s -X POST "$BASE/api/companions/$CID/conversations/$CONV/messages" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"content":"我今天被老板骂了,好难过","clientMessageId":"v8-e2e-1"}' 2>/dev/null)
MID2=$(echo "$RESP2" | $PY -c "import sys,json;print(json.load(sys.stdin).get('messageId',''))" 2>/dev/null || echo "")
COUNT=$(PGPASSWORD=shared-secret $PSQL "select count(*) from messages where client_message_id='v8-e2e-1' and conversation_id='$CONV'" 2>/dev/null | head -1 | tr -d ' ')
[ "$MID" = "$MID2" ] && ok "重复提交返回同一条消息" || fail "幂等返回不一致: $MID vs $MID2"
[ "$COUNT" = "1" ] && ok "同 clientMessageId 只入库一次(count=$COUNT)" || fail "幂等失效(count=$COUNT)"

# ── 测试 6: 事件日志游标(SSE 断线重连不丢) ──
note "测试6: 事件日志(SSE 游标)"
LOGCOUNT=$(PGPASSWORD=shared-secret $PSQL "select count(*) from event_log where companion_id='$CID'" 2>/dev/null | head -1 | tr -d ' ')
[ "${LOGCOUNT:-0}" -gt 0 ] && ok "event_log 已记录 $LOGCOUNT 条事件" || fail "event_log 为空"
CURSOR=$(PGPASSWORD=shared-secret $PSQL "select coalesce(max(id),0) from event_log where companion_id='$CID'" 2>/dev/null | head -1 | tr -d ' ')
# 断线后发生新事件, 用 Last-Event-ID 重放
curl -s -X POST "$BASE/api/companions/$CID/conversations/$CONV/messages" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"content":"断线期间的消息","clientMessageId":"v8-e2e-2"}' >/dev/null 2>&1
REPLAY=$(curl -s -N -m 5 -X GET "$BASE/api/companions/$CID/events" \
  -H "Authorization: Bearer $TOKEN" -H "Last-Event-ID: $CURSOR" 2>/dev/null | head -5 | grep -c "event:" || true)
[ "$REPLAY" -ge 1 ] && ok "Last-Event-ID 重放成功(补回断线事件)" || fail "游标重放失败"

# ── 测试 7: 行为引擎(中央行为选择器) ──
note "测试7: BehaviorEngine"
BEH=$(curl -s -X POST "$BASE/api/admin/behavior/run/$CID" -H "Authorization: Bearer $TOKEN" 2>/dev/null || echo '{}')
ACTION=$(echo "$BEH" | $PY -c "import sys,json;print(json.load(sys.stdin).get('action',''))" 2>/dev/null || echo "")
[ -n "$ACTION" ] && ok "行为评估产出候选: $ACTION" || fail "行为评估失败: ${BEH:0:100}"
DWE=$(PGPASSWORD=shared-secret $PSQL "select count(*) from digital_world_events where agent_id='$CID'" 2>/dev/null | head -1 | tr -d ' ')
echo "    (digital_world_events 记录: $DWE)"

# ── 测试 8: Anti-AI 评估回归 ──
note "测试8: Anti-AI 评估回归"
EVAL=$(curl -s "$BASE/api/companions/$CID/v6/eval" -H "Authorization: Bearer $TOKEN" 2>&1 || echo "")
if echo "$EVAL" | grep -q "humanLikenessScore"; then
  SCORE=$(echo "$EVAL" | $PY -c "import sys,json;print(round(json.load(sys.stdin).get('humanLikenessScore',0)*100))" 2>/dev/null || echo "?")
  ok "Anti-AI 评估可用, 真人感: $SCORE%"
else
  fail "Anti-AI 评估异常: ${EVAL:0:80}"
fi

echo ""
if [ "$FAIL" -eq 0 ]; then
  echo "✅ V8 验收全部通过"
else
  echo "❌ V8 验收有 $FAIL 项失败"
  exit 1
fi
