#!/usr/bin/env bash
# Luxera Companion V3 — Interaction Runtime 验收测试(设计文档 §七十四)
# 覆盖 P0 五件事: 回复决策 / 时机 / 预算 / 连发合并 / 主动进聊天框(表结构)
# 用法: BASE=http://127.0.0.1:8081 bash scripts/v3_check.sh
set -euo pipefail
BASE="${BASE:-http://127.0.0.1:8081}"
PY=python3
FAIL=0

note() { echo "==> $*"; }
ok() { echo "    ✓ $*"; }
fail() { echo "    ✗ $*"; FAIL=1; }

# ── 1. 注册 / 登录 ──────────────────────────
U="v3_$(date +%s)"
R=$(curl -s -X POST "$BASE/api/auth/register" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$U\",\"password\":\"test123456\"}")
TOKEN=$(echo "$R" | $PY -c "import sys,json;print(json.load(sys.stdin)['token'])")
[ -n "$TOKEN" ] && note "登录成功 ($U)"

# ── 2. 建伴 + 会话 ──────────────────────────
PERSONA=$(curl -s -X POST "$BASE/api/companions/compile" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"description":"一个温柔独立的女生,叫小满,比我成熟一点,偶尔会调侃我,我难过时先陪我"}' \
  | $PY -c "import sys,json;print(json.dumps(json.load(sys.stdin)['persona']))")
CID=$(curl -s -X POST "$BASE/api/companions" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d "{\"persona\": $PERSONA}" \
  | $PY -c "import sys,json;print(json.load(sys.stdin)['id'])")
CONV=$(curl -s -X POST "$BASE/api/companions/$CID/conversations/first" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{}' \
  | $PY -c "import sys,json;print(json.load(sys.stdin)['id'])")
note "companion=$CID conversation=$CONV"

# ── SSE 解析器: 统计事件/拼 token/取 action/boundary ──
# 注意: 不能用 heredoc(会覆盖 stdin 导致管道数据丢失), 必须 -c 内联
sse_parse() {
  $PY -c '
import sys, json, collections
cur=None; buf=[]; events=collections.Counter(); tokens=[]; meta=[]; done_action=None; boundary=None
def flush():
    global cur, buf, done_action, boundary
    if cur is None: return
    raw="".join(buf)
    try: obj=json.loads(raw)
    except Exception: obj={}
    events[cur]+=1
    if cur=="token": tokens.append(obj.get("delta",""))
    elif cur=="meta": meta.append(obj.get("action"))
    elif cur=="done": done_action=obj.get("action")
    elif cur=="boundary": boundary=obj.get("type")
    cur=None; buf=[]
for line in sys.stdin:
    line=line.rstrip("\n")
    if not line:
        flush()
    elif line.startswith("event:"):
        cur=line[6:].strip()
    elif line.startswith("data:"):
        buf.append(line[5:].strip())
flush()
print(json.dumps({"events":dict(events),"reply":"".join(tokens),"meta":meta,
                  "done_action":done_action,"boundary":boundary,
                  "done_count":events.get("done",0)}))
'
}

chat() { # 发送 SSE 聊天, 返回解析后的 JSON
  curl -s -N -m 180 -X POST "$BASE/api/companions/$CID/conversations/$CONV/chat" \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$1" | sse_parse
}

getfield() { # json <field>
  echo "$1" | $PY -c "import sys,json;d=json.load(sys.stdin);v=d['$2'];print(v if v is not None else '')"
}

len() { echo "$1" | $PY -c "import sys;print(len(sys.stdin.read().strip()))"; }

echo ""
echo "══════════ V3 Interaction Runtime 验收 ══════════"

# ── 测试 1: 连发 3 条 → 恰好 1 次回复, 回复短 ──
note "测试1: 连发 3 条(气死了/老板改需求/服了) → 一次回复"
R=$(chat '{"messages":[{"content":"我今天真的气死了"},{"content":"老板又临时改需求"},{"content":"真的服了"}]}')
ACT=$(getfield "$R" done_action)
CNT=$(getfield "$R" done_count)
REPLY=$(getfield "$R" reply)
REPLY_LEN=$(len "$REPLY")
[ -n "$REPLY" ] && ok "有回复($REPLY_LEN 字)" || fail "无回复"
[ "$CNT" = "1" ] && ok "恰好 1 次 done(合并为一次回复)" || fail "done 次数=$CNT"
case "$ACT" in
  REPLY_NOW|SHORT_ACK|END_CONVERSATION) ok "动作=$ACT" ;;
  *) fail "动作异常: $ACT" ;;
esac
[ "$REPLY_LEN" -le 150 ] && ok "回复简短($REPLY_LEN≤150)" || fail "回复过长($REPLY_LEN>150): $REPLY"
sleep 1

# ── 测试 2: 低落消息 → 陪伴, 不长篇大论 ──
note "测试2: 今天又加班到很晚了,好累 → 短陪伴回复"
R=$(chat '{"content":"今天又加班到很晚了,好累"}')
REPLY=$(getfield "$R" reply)
REPLY_LEN=$(len "$REPLY")
[ -n "$REPLY" ] && ok "有回复" || fail "无回复"
[ "$REPLY_LEN" -le 300 ] && ok "不长篇大论($REPLY_LEN字)" || fail "过长($REPLY_LEN字): $REPLY"
sleep 1

# ── 测试 3: 我去洗澡了 → 短应 + SOFT_END, 不续聊 ──
note "测试3: 我去洗澡了 → 去吧 + boundary SOFT_END"
R=$(chat '{"content":"我去洗澡了"}')
REPLY=$(getfield "$R" reply)
BOUND=$(getfield "$R" boundary)
ACT=$(getfield "$R" done_action)
REPLY_LEN=$(len "$REPLY")
[ "$BOUND" = "SOFT_END" ] && ok "boundary=SOFT_END" || fail "期望 SOFT_END, 实际: ${BOUND:-无}"
[ "$ACT" = "END_CONVERSATION" ] && ok "动作=END_CONVERSATION" || fail "动作异常: $ACT"
[ "$REPLY_LEN" -le 40 ] && ok "短应($REPLY_LEN字): $REPLY" || fail "应极短($REPLY_LEN字): $REPLY"
sleep 1

# ── 测试 4: 两小时后回来 → 自然重开, 非"欢迎回来" ──
note "测试4: 我回来了 → 自然重开"
R=$(chat '{"content":"我回来了"}')
REPLY=$(getfield "$R" reply)
REPLY_LEN=$(len "$REPLY")
case "$REPLY" in
  *欢迎回来*|*很高兴*|*很高兴再次*) fail "仍像客服: $REPLY" ;;
  *) ok "自然($REPLY_LEN字): $REPLY" ;;
esac
sleep 1

# ── 测试 5: 嗯 → SHORT_ACK 或 IGNORE(合法地不回) ──
note "测试5: 嗯 → SHORT_ACK 或 IGNORE"
R=$(chat '{"content":"嗯"}')
ACT=$(getfield "$R" done_action)
REPLY=$(getfield "$R" reply)
REPLY_LEN=$(len "$REPLY")
case "$ACT" in
  SHORT_ACK) ok "动作=SHORT_ACK, 极短回复($REPLY_LEN字)" ;;
  IGNORE) ok "动作=IGNORE(合法地不回)" ;;
  *) fail "动作异常: $ACT (不应长篇回复)" ;;
esac
[ "$REPLY_LEN" -le 30 ] && ok "回复极短($REPLY_LEN字)" || fail "应极短($REPLY_LEN字): $REPLY"
sleep 1

# ── 测试 6: 表结构落地 ──
note "测试6: V3 表结构"
PSQL="psql -h 127.0.0.1 -U admin -d companion -tAc"
for t in interaction_sessions conversation_exchanges conversation_boundaries; do
  if PGPASSWORD=shared-secret $PSQL "select 1 from information_schema.tables where table_name='$t'" | grep -q 1; then
    ok "$t 表存在"
  else
    fail "缺 $t 表"
  fi
done
for c in session_id exchange_id message_kind delivery_status; do
  if PGPASSWORD=shared-secret $PSQL "select count(*) from information_schema.columns where table_name='messages' and column_name='$c'" | grep -q 1; then
    ok "messages.$c 列存在"
  else
    fail "缺 messages.$c 列"
  fi
done

echo ""
if [ "$FAIL" -eq 0 ]; then
  echo "✅ V3 验收全部通过"
else
  echo "❌ V3 验收有 $FAIL 项失败"
  exit 1
fi
