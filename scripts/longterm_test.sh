#!/usr/bin/env bash
# Luxera Companion V2.0 — 4 类长期连续性测试(设计文档 §44)
# 用法: BASE=http://127.0.0.1:8081 bash scripts/longterm_test.sh
set -euo pipefail
BASE="${BASE:-http://127.0.0.1:8081}"
USER="lt_$(date +%s)"
TOKEN=$(curl -s -X POST "$BASE/api/auth/register" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER\",\"password\":\"test123456\"}" | python3 -c "import sys,json;print(json.load(sys.stdin)['token'])")
PERSONA=$(curl -s -X POST "$BASE/api/companions/compile" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"description":"一个温柔贴心的女生,叫小满"}' | python3 -c "import sys,json;print(json.dumps(json.load(sys.stdin)['persona']))")
CID=$(curl -s -X POST "$BASE/api/companions" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"persona\": $PERSONA}" | python3 -c "import sys,json;print(json.load(sys.stdin)['id'])")
CONV=$(curl -s -X POST "$BASE/api/companions/$CID/conversations/first" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{}' \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['id'])")
say() { curl -s -N -X POST "$BASE/api/companions/$CID/conversations/$CONV/chat" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "{\"content\":\"$1\"}" >/dev/null; }
count() { python3 -c "import sys,json;print(len(json.load(sys.stdin)))"; }

echo "══ A. 记忆连续性: 聊一件重要事 → 应进入记忆 ══"
say "我最重要的事: 下个月要养一只猫,叫豆包"
sleep 5
MEM=$(curl -s "$BASE/api/companions/$CID/memories" -H "Authorization: Bearer $TOKEN")
HIT=$(echo "$MEM" | python3 -c "import sys,json;d=json.load(sys.stdin);print(sum(1 for m in d if '猫' in m['content'] or '豆包' in m['content']))")
echo "  提到猫/豆包的记忆: $HIT 条 (期望 ≥1)"
[ "$HIT" -ge 1 ] || { echo "  ✗ FAIL"; FAIL=1; }

echo "══ B. 生活连续性: 她今天有连续的活动 ══"
ACT=$(curl -s "$BASE/api/companions/$CID/life" -H "Authorization: Bearer $TOKEN" | python3 -c "import sys,json;print(len(json.load(sys.stdin)['todayActivities']))")
echo "  今日活动: $ACT 条 (期望 ≥5)"
[ "$ACT" -ge 5 ] || { echo "  ✗ FAIL"; FAIL=1; }

echo "══ C. 关系连续性: 有阶段与里程碑 ══"
REL=$(curl -s "$BASE/api/companions/$CID/relationship" -H "Authorization: Bearer $TOKEN")
echo "$REL" | python3 -c "import sys,json;d=json.load(sys.stdin);print('  阶段:',d['relationship']['relationshipStage'],'| 里程碑:',len(d['events']),'个')"
EVENTS=$(echo "$REL" | python3 -c "import sys,json;print(len(json.load(sys.stdin)['events']))")
[ "$EVENTS" -ge 1 ] || { echo "  ✗ FAIL"; FAIL=1; }

echo "══ D. 主动自然度: 跑两次主动, 不应重复轰炸 ══"
N1=$(curl -s -X POST "$BASE/api/admin/proactive/run" -H "Authorization: Bearer $TOKEN" | python3 -c "import sys,json;d=json.load(sys.stdin);print(d['count'])")
N2=$(curl -s -X POST "$BASE/api/admin/proactive/run" -H "Authorization: Bearer $TOKEN" | python3 -c "import sys,json;d=json.load(sys.stdin);print(d['count'])")
echo "  连续两次主动消息: $N1 → $N2 (第二次应受频率限制≤第一次+1)"
[ "$N2" -le $((N1 + 1)) ] || { echo "  ✗ FAIL"; FAIL=1; }

FAIL="${FAIL:-0}"
echo ""
if [ "$FAIL" = "1" ]; then
  echo "❌ 长期连续性测试有失败项"
  exit 1
else
  echo "✅ 4 类长期连续性测试全部通过"
fi
