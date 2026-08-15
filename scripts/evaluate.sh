#!/usr/bin/env bash
# Luxera Companion V2.0 — Human-likeness 自动评测(设计文档 §45)
# 用法: echo "回复文本" | BASE=http://127.0.0.1:8081 bash scripts/evaluate.sh
set -euo pipefail
BASE="${BASE:-http://127.0.0.1:8081}"
REPLY=$(cat)
[ -z "$REPLY" ] && REPLY="嗯,在呢,刚下班回来。"

TOKEN=$(curl -s -X POST "$BASE/api/auth/register" -H 'Content-Type: application/json' \
  -d "{\"username\":\"eval_$(date +%s)\",\"password\":\"test123456\"}" | python3 -c "import sys,json;print(json.load(sys.stdin)['token'])")

echo "════ Human-likeness 自动评测 ════"
echo "回复: ${REPLY:0:80}${REPLY:+…}"
curl -s -X POST "$BASE/api/admin/explain/evaluate" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"reply\":\"$REPLY\"}" | python3 -c "
import sys,json
d=json.load(sys.stdin)
print('综合分:', d.get('score'))
for k,v in (d.get('dimensions') or {}).items():
    print(f'  {k}: {v}/5')
"
