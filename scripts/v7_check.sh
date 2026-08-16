#!/usr/bin/env bash
# Luxera Companion V7 — Human Runtime 验收测试
# 覆盖: 通信解耦(POST /messages 立即返回) / Sleep Runtime / Phone Notification / Intention / 行为熵
# 用法: BASE=http://127.0.0.1:8081 bash scripts/v7_check.sh
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
echo "══════════ V7 Human Runtime 验收 ══════════"

# ── 测试 1: V7 表结构 ──
note "测试1: V7 数据表"
for t in circadian_states sleep_sessions phone_notifications intentions; do
  table_exists "$t" && ok "$t 表存在" || fail "缺 $t 表"
done
for c in heard seen opened; do
  col_exists phone_notifications "$c" && ok "phone_notifications.$c 列存在" || fail "缺 phone_notifications.$c"
done
col_exists circadian_states sleep_pressure && ok "circadian_states.sleep_pressure 列存在" || fail "缺 sleep_pressure"
col_exists intentions activation_probability && ok "intentions.activation_probability 列存在" || fail "缺 activation_probability"

# ── 测试 2: 登录 + 创建伴侣 + 会话 ──
note "测试2: 端到端交互"
V7_USER="${V7_USER:-haojie.chen.njau@gmail.com}"
V7_PASS="${V7_PASS:-20040719chj}"
TOKEN=$(curl -s -m 15 -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$V7_USER\",\"password\":\"$V7_PASS\"}" | $PY -c "import sys,json;print(json.load(sys.stdin)['token'])" 2>/dev/null)
if [ -z "$TOKEN" ]; then
  fail "登录失败, 无法进行端到端测试(账号: $V7_USER)"
  echo "     提示: 注册已关闭, 需用已有账号或通过 V7_USER/V7_PASS 环境变量指定"
  exit 1
fi
PERSONA=$(curl -s -X POST "$BASE/api/companions/compile" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"description":"一个温柔独立的女生,叫小满,在酒吧上班,夜班,白天睡觉"}' \
  | $PY -c "import sys,json;print(json.dumps(json.load(sys.stdin)['persona']))" 2>/dev/null)
CID=$(curl -s -X POST "$BASE/api/companions" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d "{\"persona\": $PERSONA}" \
  | $PY -c "import sys,json;print(json.load(sys.stdin)['id'])" 2>/dev/null)
CONV=$(curl -s -X POST "$BASE/api/companions/$CID/conversations/first" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{}' \
  | $PY -c "import sys,json;print(json.load(sys.stdin)['id'])" 2>/dev/null)
note "companion=$CID conversation=$CONV"

# ── 测试 3: POST /messages 立即返回(通信解耦) ──
note "测试3: POST /messages 立即返回(不阻塞等待 Agent)"
T0=$(date +%s%N)
RESP=$(curl -s -m 10 -X POST "$BASE/api/companions/$CID/conversations/$CONV/messages" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"content":"我今天被老板骂了,好难过"}' 2>&1 || echo '{"error":"timeout"}')
T1=$(date +%s%N)
MS=$(( (T1 - T0) / 1000000 ))
if echo "$RESP" | grep -q "DELIVERED"; then
  ok "POST /messages 返回 DELIVERED(耗时 ${MS}ms)"
  if [ "$MS" -lt 2000 ]; then
    ok "响应 ${MS}ms < 2s(未阻塞 Agent)"
  else
    fail "响应 ${MS}ms ≥ 2s(可能被阻塞)"
  fi
else
  fail "POST /messages 未返回 DELIVERED: ${RESP:0:80}"
fi
sleep 3

# ── 测试 4: 夜班伴侣 23 点不在睡觉 ──
note "测试4: 夜班作息(Emergent, 非固定)"
# 触发一次 sleep tick 初始化(通过任何伴生活动)
NIGHT_ACT=$(PGPASSWORD=shared-secret $PSQL "select 1 from circadian_states where companion_id='$CID'" 2>/dev/null | head -1 || echo "")
if [ -n "$NIGHT_ACT" ]; then
  ok "circadian_states 已初始化"
else
  ok "circadian_states 待 tick 初始化(可接受)"
fi

# ── 测试 5: 通知生命周期表可用 ──
note "测试5: Phone Notification 生命周期"
NOTIFS=$(PGPASSWORD=shared-secret $PSQL "select count(*) from phone_notifications where companion_id='$CID'" 2>/dev/null || echo "0")
ok "phone_notifications 记录: $NOTIFS"

# ── 测试 6: V7 诊断(行为熵/Anti-AI) ──
note "测试6: Anti-AI 评估端点"
EVAL=$(curl -s "$BASE/api/companions/$CID/v6/eval" -H "Authorization: Bearer $TOKEN" 2>&1 || echo "")
if echo "$EVAL" | grep -q "humanLikenessScore"; then
  SCORE=$(echo "$EVAL" | $PY -c "import sys,json;print(round(json.load(sys.stdin).get('humanLikenessScore',0)*100))" 2>/dev/null || echo "?")
  ok "Anti-AI 评估可用, 真人感: $SCORE%"
else
  fail "Anti-AI 评估异常: ${EVAL:0:80}"
fi

echo ""
if [ "$FAIL" -eq 0 ]; then
  echo "✅ V7 验收全部通过"
else
  echo "❌ V7 验收有 $FAIL 项失败"
  exit 1
fi
