#!/usr/bin/env bash
# Luxera Companion V5 — Continuous Human Runtime 验收测试
# 覆盖: Runtime 基础设施(表) / 消息生命周期 / Emotion Agent / Brain / Trace / 排程 / 待复查
# 用法: BASE=http://127.0.0.1:8081 bash scripts/v5_check.sh
set -euo pipefail
BASE="${BASE:-http://127.0.0.1:8081}"
PY=python3
FAIL=0

note() { echo "==> $*"; }
ok() { echo "    ✓ $*"; }
fail() { echo "    ✗ $*"; FAIL=1; }

HOUR=$(date +%H)
NIGHT=0
if [ "$HOUR" -ge 22 ] || [ "$HOUR" -lt 8 ]; then NIGHT=1; fi

PSQL="psql -h 127.0.0.1 -U admin -d companion -tAc"
table_exists() { PGPASSWORD=shared-secret $PSQL "select 1 from information_schema.tables where table_name='$1'" | grep -q 1; }
col_exists() { PGPASSWORD=shared-secret $PSQL "select count(*) from information_schema.columns where table_name='$1' and column_name='$2'" | grep -q 1; }

echo ""
echo "══════════ V5 Continuous Human Runtime 验收 ══════════"

# ── 测试 1: V5 表结构 ──
note "测试1: V5 运行时表结构"
for t in agent_traces scheduled_actions pending_message_states world_events; do
  table_exists "$t" && ok "$t 表存在" || fail "缺 $t 表"
done
for c in sadness anxiety warmth; do
  col_exists agent_states "$c" && ok "agent_states.$c 列存在" || fail "缺 agent_states.$c 列"
done

# ── 测试 2: Agent 注册 ──
note "测试2: Agent 注册(诊断端点)"
V7_USER="${V7_USER:-haojie.chen.njau@gmail.com}"
V7_PASS="${V7_PASS:-20040719chj}"
TOKEN=$(curl -s -m 15 -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$V7_USER\",\"password\":\"$V7_PASS\"}" | $PY -c "import sys,json;print(json.load(sys.stdin)['token'])")
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

AGENTS=$(curl -s "$BASE/api/companions/$CID/v5/agents" -H "Authorization: Bearer $TOKEN" \
  | $PY -c "import sys,json;d=json.load(sys.stdin);print(','.join(sorted(d.get('registered',[]))))")
if echo "$AGENTS" | grep -q "brain" && echo "$AGENTS" | grep -q "emotion" \
   && echo "$AGENTS" | grep -q "memory" && echo "$AGENTS" | grep -q "expression" \
   && echo "$AGENTS" | grep -q "event"; then
  ok "5 个 Agent 已注册: $AGENTS"
else
  fail "Agent 注册不完整: $AGENTS"
fi

# ── 测试 3: 消息生命周期(夜间睡觉 → 未读拦截) ──
note "测试3: 发消息 → 生命周期状态"
chat() {
  curl -s -N -m 90 -X POST "$BASE/api/companions/$CID/conversations/$CONV/chat" \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$1" \
  | $PY -c "
import sys, json
cur=None; buf=[]; events={}; done_action=None; meta_reason=None
def flush():
    global cur, buf, done_action, meta_reason
    if cur is None: return
    raw=''.join(buf)
    try: obj=json.loads(raw)
    except Exception: obj={}
    events[cur]=events.get(cur,0)+1
    if cur=='done': done_action=obj.get('action')
    if cur=='meta': meta_reason=obj.get('reason')
    cur=None; buf=[]
for line in sys.stdin:
    line=line.rstrip('\n')
    if not line: flush()
    elif line.startswith('event:'): cur=line[6:].strip()
    elif line.startswith('data:'): buf.append(line[5:].strip())
flush()
print(json.dumps({'events':events,'action':done_action,'reason':meta_reason}))
"
}
R=$(chat '{"content":"在吗?我有话想跟你说"}')
ACT=$(echo "$R" | $PY -c "import sys,json;print(json.load(sys.stdin)['action'])")
REASON=$(echo "$R" | $PY -c "import sys,json;print(json.load(sys.stdin)['reason'])")
if [ "$NIGHT" -eq 1 ]; then
  [ "$ACT" = "IGNORE" ] && ok "夜间消息 → IGNORE(她睡了, reason: $REASON)" || fail "夜间消息动作: $ACT"
else
  case "$ACT" in
    REPLY_NOW|SHORT_ACK|DEFER|IGNORE) ok "白天消息 → $ACT" ;;
    *) fail "消息动作异常: $ACT" ;;
  esac
fi
sleep 1

# ── 测试 4: Agent 痕迹 ──
note "测试4: Agent 痕迹(Emotion 必记录; Brain/Memory 仅在'注意到'后调用 —— V5 §73)"
# 发第二条消息, 让异步记忆抽取完成后再查 memory trace。
# 注意: 注意力是概率性的 —— 白天也可能"在忙没注意到"(V4 §六/§七),
# 此时不调用 Brain/Memory 正是 V5 §73 的正确行为, 不能误判为缺 trace。
NOTICED=0
if [ "$NIGHT" -eq 0 ]; then
  R2=$(curl -s -N -m 90 -X POST "$BASE/api/companions/$CID/conversations/$CONV/chat" \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d '{"content":"其实我最近有点累,想跟你聊聊"}' \
    | $PY -c "
import sys, json
cur=None; buf=[]; action=None
def flush():
    global cur, buf, action
    if cur is None: return
    raw=''.join(buf)
    try: obj=json.loads(raw)
    except Exception: obj={}
    if cur=='done': action=obj.get('action')
    cur=None; buf=[]
for line in sys.stdin:
    line=line.rstrip('\n')
    if not line: flush()
    elif line.startswith('event:'): cur=line[6:].strip()
    elif line.startswith('data:'): buf.append(line[5:].strip())
flush()
print(action or '')
" 2>/dev/null || echo "")
  # "她没注意到" 或未决断 → 视为未注意到
  if [ -n "$R2" ] && [ "$R2" != "IGNORE" ]; then NOTICED=1; fi
  sleep 2
fi
TRACES=$(curl -s "$BASE/api/companions/$CID/v5/traces" -H "Authorization: Bearer $TOKEN")
EMO=$(echo "$TRACES" | $PY -c "import sys,json;d=json.load(sys.stdin);print('Y' if any(t.get('agent')=='emotion' for t in d) else 'N')")
BRAIN=$(echo "$TRACES" | $PY -c "import sys,json;d=json.load(sys.stdin);print('Y' if any(t.get('agent')=='brain' for t in d) else 'N')")
MEM=$(echo "$TRACES" | $PY -c "import sys,json;d=json.load(sys.stdin);print('Y' if any(t.get('agent')=='memory' for t in d) else 'N')")
[ "$EMO" = "Y" ] && ok "emotion trace 记录" || fail "缺 emotion trace"
if [ "$NIGHT" -eq 1 ] || [ "$NOTICED" -eq 0 ]; then
  # 消息未被注意到 → 不调用 Brain/Memory(正确: 没必要决策没看到的消息, V5 §73)
  [ "$BRAIN" = "N" ] && ok "brain 未唤醒(消息未注意到, 符合 V5 §73)" || fail "未注意到时不应唤醒 brain"
  [ "$MEM" = "N" ] && ok "memory 未唤醒(消息未注意到)" || fail "未注意到时不应唤醒 memory"
else
  [ "$BRAIN" = "Y" ] && ok "brain trace 记录" || fail "缺 brain trace"
  [ "$MEM" = "Y" ] && ok "memory trace 记录" || fail "缺 memory trace"
fi

# ── 测试 5: 已读状态(消息生命周期) ──
note "测试5: 用户消息生命周期状态落库"
STATUSES=$(PGPASSWORD=shared-secret psql -h 127.0.0.1 -U admin -d companion -tAc \
  "select distinct delivery_status from messages where conversation_id='$CONV' order by 1")
echo "      状态: $(echo $STATUSES | tr '\n' ' ')"
[ -n "$STATUSES" ] && ok "消息状态已记录: $STATUSES" || fail "无消息状态"

# ── 测试 6: 诊断端点 ──
note "测试6: 诊断端点可访问"
for ep in scheduled pending-messages world-events; do
  if curl -sf "$BASE/api/companions/$CID/v5/$ep" -H "Authorization: Bearer $TOKEN" >/dev/null 2>&1; then
    ok "/v5/$ep ✓"
  else
    fail "/v5/$ep 不可访问"
  fi
done

echo ""
if [ "$NIGHT" -eq 1 ]; then
  echo "⚠ 当前为夜间($(date +%H:%M)), 交互断言已按'睡觉勿扰'降级; 白天运行将覆盖完整回复链路。"
fi
if [ "$FAIL" -eq 0 ]; then
  echo "✅ V5 验收全部通过"
else
  echo "❌ V5 验收有 $FAIL 项失败"
  exit 1
fi
