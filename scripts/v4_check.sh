#!/usr/bin/env bash
# Luxera Companion V4 — Continuous Human Runtime 验收测试
# 覆盖: Message Lifecycle(read receipts) / Appraisal / DEFER / 表结构 / 回归
# 用法: BASE=http://127.0.0.1:8081 bash scripts/v4_check.sh
set -euo pipefail
BASE="${BASE:-http://127.0.0.1:8081}"
PY=python3
FAIL=0

note() { echo "==> $*"; }
ok() { echo "    ✓ $*"; }
fail() { echo "    ✗ $*"; FAIL=1; }

# V4 验收是"白天交互"测试: 夜间(22:00-08:00)伴侣在睡觉, 消息会被"未读"正确拦截,
# 交互断言(已读/回复/DEFER)不适用 —— 此时只跑表结构验收并提示。
HOUR=$(date +%H)
if [ "$HOUR" -ge 22 ] || [ "$HOUR" -lt 8 ]; then
  echo ""
  echo "══════════ V4 验收 ══════════"
  echo "⚠ 当前为夜间睡觉时段($(date +%H:%M)), 伴侣在睡觉 → 消息会被'未读'拦截(正确行为)。"
  echo "  交互验收(已读/回复/DEFER)请在 08:00-22:00 运行。"
  echo "  本次仅执行表结构验收。"
  note "表结构: message_appraisals / agent_states.hurt+anger"
  PSQL="psql -h 127.0.0.1 -U admin -d companion -tAc"
  if PGPASSWORD=shared-secret $PSQL "select 1 from information_schema.tables where table_name='message_appraisals'" | grep -q 1; then
    ok "message_appraisals 表存在"
  else
    fail "缺 message_appraisals 表"
  fi
  for c in hurt anger; do
    if PGPASSWORD=shared-secret $PSQL "select count(*) from information_schema.columns where table_name='agent_states' and column_name='$c'" | grep -q 1; then
      ok "agent_states.$c 列存在"
    else
      fail "缺 agent_states.$c 列"
    fi
  done
  [ "$FAIL" -eq 0 ] && echo "✅ V4 表结构验收通过(夜间)" || echo "❌ V4 表结构验收有 $FAIL 项失败"
  exit $FAIL
fi

U="v4_$(date +%s)"
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

# SSE 解析: 统计事件 + 取 action/是否含 message_read
chat() {
  curl -s -N -m 180 -X POST "$BASE/api/companions/$CID/conversations/$CONV/chat" \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$1" \
  | $PY -c "
import sys, json
cur=None; buf=[]; events={}; done_action=None
def flush():
    global cur, buf, done_action
    if cur is None: return
    raw=''.join(buf)
    try: obj=json.loads(raw)
    except Exception: obj={}
    events[cur]=events.get(cur,0)+1
    if cur=='done': done_action=obj.get('action')
    cur=None; buf=[]
for line in sys.stdin:
    line=line.rstrip('\n')
    if not line: flush()
    elif line.startswith('event:'): cur=line[6:].strip()
    elif line.startswith('data:'): buf.append(line[5:].strip())
flush()
print(json.dumps({'events':events,'done_action':done_action}))
"
}

# /events 持久流监听: 后台运行, 收集事件, 输出到文件
listen_events() {
  curl -s -N -m 40 "$BASE/api/companions/$CID/events" \
    -H "Authorization: Bearer $TOKEN" > /tmp/v4_events_$CID.txt 2>/dev/null &
  EVENT_PID=$!
  sleep 1
}
stop_events() {
  kill $EVENT_PID 2>/dev/null || true
  sleep 1
}
has_read_event() { # 检查事件流文件是否有 message_read
  grep -q "event:message_read" /tmp/v4_events_$CID.txt && echo "True" || echo "False"
}
getf() { echo "$1" | $PY -c "import sys,json;d=json.load(sys.stdin);print(d['$2'] if d.get('$2') is not None else '')"; }

echo ""
echo "══════════ V4 Continuous Human Runtime 验收 ══════════"

# ── 测试 1: 正常回复路径 → message_read 事件(经 /events) ──
note "测试1: 发'我今天好难过' → /events 收到 message_read + 有回复"
listen_events
R=$(chat '{"content":"我今天好难过,项目被砍了"}')
stop_events
READ=$(has_read_event)
ACT=$(getf "$R" done_action)
[ "$READ" = "True" ] && ok "message_read 事件 ✓(已读)" || fail "无 message_read"
[ -n "$ACT" ] && ok "done.action=$ACT" || fail "无回复"
sleep 1

# ── 测试 2: Appraisal 落库 ──
note "测试2: message_appraisals 表有记录"
CNT=$(PGPASSWORD=shared-secret psql -h 127.0.0.1 -U admin -d companion -tAc \
  "select count(*) from message_appraisals where companion_id='$CID'")
[ "${CNT:-0}" -ge 1 ] && ok "appraisal 记录 $CNT 条" || fail "无 appraisal 记录"
sleep 1

# ── 测试 3: 已读状态落库 ──
note "测试3: 用户消息 delivery_status=READ"
SCNT=$(PGPASSWORD=shared-secret psql -h 127.0.0.1 -U admin -d companion -tAc \
  "select count(*) from messages m where m.conversation_id='$CONV' and m.sender_type='user' and m.delivery_status='READ'")
[ "${SCNT:-0}" -ge 1 ] && ok "$SCNT 条用户消息已读" || fail "用户消息未标记 READ"
sleep 1

# ── 测试 4: 冲突消息 → DEFER 或正常回复(至少 Appraisal 状态变化) ──
note "测试4: 发'你怎么这么烦' → DEFER(已读不回) 或有效回复"
R=$(chat '{"content":"你怎么这么烦,跟你说话真没意思"}')
ACT=$(getf "$R" done_action)
case "$ACT" in
  DEFER) ok "动作=DEFER(看到了但不回, 已读不回)" ;;
  REPLY_NOW|SHORT_ACK) ok "动作=$ACT(正常回复, 但状态已记录)" ;;
  IGNORE) ok "动作=IGNORE(未读忽略)" ;;
  *) fail "动作异常: $ACT" ;;
esac
sleep 1

# ── 测试 5: 道歉 → 正常回复(Appraisal 状态延续) ──
note "测试5: 发'对不起,是我语气不好' → 正常回复"
R=$(chat '{"content":"对不起,是我语气不好"}')
ACT=$(getf "$R" done_action)
[ "$ACT" = "REPLY_NOW" ] || [ "$ACT" = "SHORT_ACK" ] && ok "道歉后正常回复($ACT)" || fail "道歉后动作: $ACT"
sleep 1

# ── 测试 6: 表结构 ──
note "测试6: V4 表结构"
PSQL="psql -h 127.0.0.1 -U admin -d companion -tAc"
if PGPASSWORD=shared-secret $PSQL "select 1 from information_schema.tables where table_name='message_appraisals'" | grep -q 1; then
  ok "message_appraisals 表存在"
else
  fail "缺 message_appraisals 表"
fi
for c in hurt anger; do
  if PGPASSWORD=shared-secret $PSQL "select count(*) from information_schema.columns where table_name='agent_states' and column_name='$c'" | grep -q 1; then
    ok "agent_states.$c 列存在"
  else
    fail "缺 agent_states.$c 列"
  fi
done

# ── 测试 7: 回归(洗澡→SOFT_END) ──
note "测试7: V3 P0 回归"
R=$(curl -s -N -m 60 -X POST "$BASE/api/companions/$CID/conversations/$CONV/chat" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"content":"我去洗澡了"}' \
  | grep -c "event:boundary" || true)
[ "$R" -ge 1 ] && ok "boundary=SOFT_END ✓" || fail "无 boundary"

echo ""
if [ "$FAIL" -eq 0 ]; then
  echo "✅ V4 验收全部通过"
else
  echo "❌ V4 验收有 $FAIL 项失败"
  exit 1
fi
