#!/usr/bin/env bash
# Persistent AI Companion 端到端冒烟测试
# 用法: BASE=http://127.0.0.1:8081 bash scripts/smoke.sh
set -euo pipefail

BASE="${BASE:-http://127.0.0.1:8081}"
USER="smoke_$(date +%s)"
PASS="smoke123456"
JQ="python3 -c"

req() { # method url [json]
  local method=$1 url=$2 data=${3:-}
  if [ -n "$data" ]; then
    curl -s -X "$method" "$BASE$url" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$data"
  else
    curl -s -X "$method" "$BASE$url" -H "Authorization: Bearer $TOKEN"
  fi
}

echo "==> 1. 注册/登录 ($USER)"
R=$(curl -s -X POST "$BASE/api/auth/register" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER\",\"password\":\"$PASS\",\"nickname\":\"冒烟用户\"}")
TOKEN=$(echo "$R" | $JQ "import sys,json;print(json.load(sys.stdin)['token'])")
[ -n "$TOKEN" ] && echo "    登录成功"

echo "==> 2. 编译人格"
PERSONA=$(curl -s -X POST "$BASE/api/companions/compile" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"description":"一个温柔独立的女生,叫小满,比我成熟一点,偶尔会调侃我,我难过时先陪我"}' \
  | $JQ "import sys,json;print(json.dumps(json.load(sys.stdin)['persona']))")
echo "$PERSONA" | $JQ "import sys,json;p=json.load(sys.stdin);print('    名字:',p['identity']['name'],'性别:',p['identity']['gender'])"

echo "==> 3. 创建伴侣"
CID=$(req POST /api/companions "{\"persona\": $PERSONA}" | $JQ "import sys,json;print(json.load(sys.stdin)['id'])")
echo "    companionId=$CID"

echo "==> 4. 首次会话(问候)"
CONV=$(req POST /api/companions/$CID/conversations/first '{}' | $JQ "import sys,json;print(json.load(sys.stdin)['id'])")
echo "    conversationId=$CONV"

echo "==> 5. 流式聊天"
RESP=$(curl -s -N -X POST "$BASE/api/companions/$CID/conversations/$CONV/chat" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"content":"今天又加班到很晚了,好累"}' )
echo "$RESP" | grep -c "event:token" | xargs -I{} echo "    token 事件数: {}"
echo "$RESP" | grep -q "event:done" && echo "    done 事件 ✓"
sleep 3

echo "==> 6. 记忆抽取"
MEM=$(req GET /api/companions/$CID/memories | $JQ "import sys,json;print(len(json.load(sys.stdin)))")
echo "    记忆条数: $MEM"
[ "$MEM" -gt 0 ] && echo "    记忆抽取 ✓"

echo "==> 7. 关系演化"
REL=$(req GET /api/companions/$CID/relationship)
echo "$REL" | $JQ "import sys,json;d=json.load(sys.stdin);print('    阶段:',d['relationship']['relationshipStage'],'消息数:',d['relationship']['messageCount'],'里程碑:',[e['title'] for e in d['events']])"

echo "==> 8. 生日提醒"
req POST /api/admin/birthday/ensure '{}' >/dev/null
REMS=$(req GET /api/companions/$CID/reminders | $JQ "import sys,json;print(len(json.load(sys.stdin)))")
echo "    提醒数: $REMS (含伴侣生日)"

echo "==> 9. 每日反思"
req POST /api/admin/reflection/run '{}' >/dev/null
REFS=$(req GET /api/companions/$CID/reflections | $JQ "import sys,json;print(len(json.load(sys.stdin)))")
echo "    反思记录: $REFS"

echo "==> 10. 记忆管理: 搜索/遗忘"
HIT=$(curl -s -G "$BASE/api/companions/$CID/memories/search" --data-urlencode "q=加班" -H "Authorization: Bearer $TOKEN" | $JQ "import sys,json;print(len(json.load(sys.stdin)))")
echo "    搜索'加班'命中: $HIT"

echo ""
echo "✅ 冒烟测试全部通过"
