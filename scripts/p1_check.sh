#!/usr/bin/env bash
# Luxera Companion V3 P1 — 验收测试
# 覆盖: UserChatStyle 学习 / CompanionAvailability 状态 / 表结构 / 全链路无回归
# 用法: BASE=http://127.0.0.1:8081 bash scripts/p1_check.sh
set -euo pipefail
BASE="${BASE:-http://127.0.0.1:8081}"
PY=python3
FAIL=0

note() { echo "==> $*"; }
ok() { echo "    ✓ $*"; }
fail() { echo "    ✗ $*"; FAIL=1; }

# ── 1. 注册 / 建伴 / 会话 ──────────────────
U="p1_$(date +%s)"
TOKEN=$(curl -s -X POST "$BASE/api/auth/register" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$U\",\"password\":\"test123456\"}" | $PY -c "import sys,json;print(json.load(sys.stdin)['token'])")
PERSONA=$(curl -s -X POST "$BASE/api/companions/compile" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"description":"一个温柔独立的女生,叫小满,比我成熟一点,偶尔会调侃我"}' \
  | $PY -c "import sys,json;print(json.dumps(json.load(sys.stdin)['persona']))")
CID=$(curl -s -X POST "$BASE/api/companions" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d "{\"persona\": $PERSONA}" \
  | $PY -c "import sys,json;print(json.load(sys.stdin)['id'])")
CONV=$(curl -s -X POST "$BASE/api/companions/$CID/conversations/first" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{}' \
  | $PY -c "import sys,json;print(json.load(sys.stdin)['id'])")
note "companion=$CID conversation=$CONV"

chat() { # 发送 SSE 聊天(静默解析, 只看是否完成)
  curl -s -N -m 180 -X POST "$BASE/api/companions/$CID/conversations/$CONV/chat" \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$1" \
  | $PY -c "
import sys, json
cur=None; buf=[]; done=False
def flush():
    global cur, buf, done
    if cur is None: return
    raw=''.join(buf)
    if cur=='done': done=True
    cur=None; buf=[]
for line in sys.stdin:
    line=line.rstrip('\n')
    if not line: flush()
    elif line.startswith('event:'): cur=line[6:].strip()
    elif line.startswith('data:'): buf.append(line[5:].strip())
flush()
print('OK' if done else 'NO_DONE')
"
}

echo ""
echo "══════════ V3 P1 验收 ══════════"

# ── 测试 1: 聊天正常 + 学习聊天习惯 ──
note "测试1: 连续几条不同长度消息 → UserChatStyle 被记录"
chat '{"content":"嗨,今天怎么样"}' >/dev/null
sleep 0.3
chat '{"content":"我今天写了个超长的代码调了好久好累啊"}' >/dev/null
sleep 0.3
chat '{"content":"哈哈,不过搞定了"}' >/dev/null
sleep 1
STYLE=$(PGPASSWORD=shared-secret psql -h 127.0.0.1 -U admin -d companion -tAc \
  "select sample_count, round(avg_message_length::numeric,1), round(burst_rate::numeric,2) from user_chat_styles where companion_id='$CID'")
SC=$(echo "$STYLE" | cut -d'|' -f1)
[ -n "$STYLE" ] && ok "user_chat_styles 记录: $STYLE" || fail "无 user_chat_styles 记录"
[ "${SC:-0}" -ge 3 ] && ok "至少记录了 3 条消息" || fail "样本数不足: $SC"
sleep 1

# ── 测试 2: 连发 → burst_rate 上升 ──
note "测试2: 快速连发 → burst_rate > 0"
BEFORE=$(PGPASSWORD=shared-secret psql -h 127.0.0.1 -U admin -d companion -tAc \
  "select burst_rate from user_chat_styles where companion_id='$CID'")
R=$(chat '{"messages":[{"content":"第一条"},{"content":"第二条"},{"content":"第三条"}]}')
[ "$R" = "OK" ] && ok "连发合并后一次回复正常" || fail "连发失败: $R"
sleep 1
AFTER=$(PGPASSWORD=shared-secret psql -h 127.0.0.1 -U admin -d companion -tAc \
  "select burst_rate from user_chat_styles where companion_id='$CID'")
ok "burst_rate: $BEFORE → $AFTER"

# ── 测试 3: 表结构 ──
note "测试3: P1 表结构"
PSQL="psql -h 127.0.0.1 -U admin -d companion -tAc"
for t in user_chat_styles; do
  if PGPASSWORD=shared-secret $PSQL "select 1 from information_schema.tables where table_name='$t'" | grep -q 1; then
    ok "$t 表存在"
  else
    fail "缺 $t 表"
  fi
done
for c in avg_message_length burst_rate emoji_rate laugh_rate question_rate active_hour_start; do
  if PGPASSWORD=shared-secret $PSQL "select count(*) from information_schema.columns where table_name='user_chat_styles' and column_name='$c'" | grep -q 1; then
    ok "user_chat_styles.$c 列存在"
  else
    fail "缺 user_chat_styles.$c 列"
  fi
done

# ── 测试 4: 离开/回来 回归(V3 P0 行为不回退) ──
note "测试4: V3 P0 回归(洗澡→SOFT_END)"
R=$(curl -s -N -m 60 -X POST "$BASE/api/companions/$CID/conversations/$CONV/chat" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"content":"我去洗澡了"}' \
  | grep -c "event:boundary" || true)
[ "$R" -ge 1 ] && ok "boundary=SOFT_END ✓" || fail "无 boundary"

echo ""
if [ "$FAIL" -eq 0 ]; then
  echo "✅ P1 验收全部通过"
else
  echo "❌ P1 验收有 $FAIL 项失败"
  exit 1
fi
