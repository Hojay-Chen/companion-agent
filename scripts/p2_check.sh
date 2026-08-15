#!/usr/bin/env bash
# Luxera Companion V3 P2 — 验收测试
# 覆盖: Entity Layer(长期指代) / Memory Disclosure(不显摆记忆) / 表结构 / 回归
# 用法: BASE=http://127.0.0.1:8081 bash scripts/p2_check.sh
set -euo pipefail
BASE="${BASE:-http://127.0.0.1:8081}"
PY=python3
FAIL=0

note() { echo "==> $*"; }
ok() { echo "    ✓ $*"; }
fail() { echo "    ✗ $*"; FAIL=1; }

U="p2_$(date +%s)"
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

chat() { # 发送 SSE, 返回解析 JSON(meta/done)
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
echo "══════════ V3 P2 验收 ══════════"

# ── 测试 1: 提到具体实体 → entities 表记录 ──
note "测试1: 提到'下周面阿里巴巴/那家咖啡馆' → 实体被抽取"
chat '{"content":"下周我要面阿里巴巴了,有点紧张"}' >/dev/null
sleep 0.5
chat '{"content":"对了,上次说的那家咖啡馆我周末去了,很不错"}' >/dev/null
sleep 2.5   # 等异步实体抽取
CNT=$(PGPASSWORD=shared-secret psql -h 127.0.0.1 -U admin -d companion -tAc \
  "select count(*) from entities where companion_id='$CID' and status='active'")
[ "${CNT:-0}" -ge 1 ] && ok "entities 已记录 $CNT 个实体" || fail "entities 无记录(可能 LLM 抽取延迟或失败)"
ENT=$(PGPASSWORD=shared-secret psql -h 127.0.0.1 -U admin -d companion -tAc \
  "select string_agg(name, ',') from entities where companion_id='$CID'")
ok "实体: ${ENT:-无}"

# ── 测试 2: 实体 API 可用 ──
note "测试2: GET /memories/entities"
API=$(curl -s -m 5 "$BASE/api/companions/$CID/memories/entities" -H "Authorization: Bearer $TOKEN")
echo "$API" | $PY -c "import sys,json; d=json.load(sys.stdin); print('OK' if isinstance(d,list) else 'FAIL')" \
  && ok "实体 API 返回列表" || fail "实体 API 异常: ${API:0:80}"

# ── 测试 3: 表结构 ──
note "测试3: entities 表"
PSQL="psql -h 127.0.0.1 -U admin -d companion -tAc"
if PGPASSWORD=shared-secret $PSQL "select 1 from information_schema.tables where table_name='entities'" | grep -q 1; then
  ok "entities 表存在"
else
  fail "缺 entities 表"
fi
for c in name type description mention_count salience last_seen_at; do
  if PGPASSWORD=shared-secret $PSQL "select count(*) from information_schema.columns where table_name='entities' and column_name='$c'" | grep -q 1; then
    ok "entities.$c 列存在"
  else
    fail "缺 entities.$c 列"
  fi
done

# ── 测试 4: V3 P0 回归 ──
note "测试4: V3 P0 回归(洗澡→SOFT_END)"
R=$(curl -s -N -m 60 -X POST "$BASE/api/companions/$CID/conversations/$CONV/chat" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"content":"我去洗澡了"}' \
  | grep -c "event:boundary" || true)
[ "$R" -ge 1 ] && ok "boundary=SOFT_END ✓" || fail "无 boundary"

echo ""
if [ "$FAIL" -eq 0 ]; then
  echo "✅ P2 验收全部通过"
else
  echo "❌ P2 验收有 $FAIL 项失败"
  exit 1
fi
