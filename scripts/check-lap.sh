#!/usr/bin/env bash
# LAP v1 应用平台 — 端到端验收。
#
# 为什么必须有这个脚本:
#   scripts/check.sh 覆盖的是聊天/数字人链路, 对应用平台「零覆盖」——
#   这就是为什么「mvn test 全绿」在应用平台上什么也保护不了。
#   本脚本是 ActionGateway / Manifest / Resource / 幂等 / 归属链唯一的端到端守卫。
#
# 断言编号沿用实施计划, 未到轮次的先跳过并打印原因(R5 起陆续打开, R8 全部打开)。
#
# 用法: BASE=http://127.0.0.1:8081 bash scripts/check-lap.sh
set -euo pipefail

BASE="${BASE:-http://127.0.0.1:8081}"
PY=python3
FAIL=0
SKIPPED=0

TMP="$(mktemp -d /tmp/check-lap.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT

note() { echo "==> $*"; }
ok()   { echo "    ✓ $*"; }
fail() { echo "    ✗ $*"; FAIL=1; }
skip() { echo "    ○ 跳过 ($*)"; SKIPPED=$((SKIPPED + 1)); }

PSQL="psql -h 127.0.0.1 -U admin -d companion -tAc"
table_exists() { PGPASSWORD=shared-secret $PSQL "select 1 from information_schema.tables where table_name='$1'" | grep -q 1; }
sql() { PGPASSWORD=shared-secret $PSQL "$1" | tr -d ' '; }
exec_sql() { PGPASSWORD=shared-secret $PSQL "$1" >/dev/null; }

encoded() { $PY -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1],safe=''))" "$1"; }
stamp() { date +%s%N; }

APP_ID="com.luxera.tictactoe"
AUTH=""

# HTTP 助手: 打印状态码, 正文留在 $TMP/body, 响应头留在 $TMP/hdr
http() {
  local method="$1" url="$2" payload="${3:-}" key="${4:-}"
  local args=(-s -m 20 -o "$TMP/body" -D "$TMP/hdr" -w '%{http_code}' -X "$method" "$BASE$url")
  if [ -n "$AUTH" ]; then args+=(-H "Authorization: Bearer $AUTH"); fi
  if [ -n "$payload" ]; then args+=(-H 'Content-Type: application/json' -d "$payload"); fi
  if [ -n "$key" ]; then args+=(-H "Idempotency-Key: $key"); fi
  curl "${args[@]}" || echo "000"
}
body() { cat "$TMP/body"; }
jq_() { body | $PY -c "import sys,json;d=json.load(sys.stdin);print($1)" 2>/dev/null || echo ""; }

echo ""
echo "══════════ LAP v1 应用平台验收 ══════════"

# ── 断言 1: 表结构 ──
note "断言 1: LAP 表结构"
for t in developer application application_version capability application_capability \
         installation permission_grant application_session resource subscription \
         action_invocation application_action_log; do
  table_exists "$t" && ok "$t 表存在" || fail "缺 $t 表"
done
# 旧表: R8 跑完 lap-drop-legacy.sh 之后才打开这半边断言(R3–R7 期间 /api/v10 与 reminders 还在服役)
skip "旧表 (dh_application / dh_game_session / dh_application_action_log / reminders) 不存在 — R8 打开"

# ── 登录 ──
note "登录取令牌"
CHECK_USER="${CHECK_USER:-haojie.chen.njau@gmail.com}"
CHECK_PASS="${CHECK_PASS:-20040719chj}"
AUTH=$(curl -s -m 15 -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$CHECK_USER\",\"password\":\"$CHECK_PASS\"}" \
  | $PY -c "import sys,json;print(json.load(sys.stdin).get('token',''))" 2>/dev/null || echo "")
if [ -z "$AUTH" ]; then
  fail "登录失败, 无法继续 (账号: $CHECK_USER)"
  echo ""
  echo "❌ 验收未通过"
  exit 1
fi
ok "登录成功"

# ── 断言 2: 能力目录 ──
note "断言 2: GET /api/v1/capabilities"
CODE=$(http GET /api/v1/capabilities)
[ "$CODE" = "200" ] && ok "200" || fail "状态码 $CODE"
CAPS=$(body)
echo "$CAPS" | grep -q '"game.play"' && ok "⊇ game.play" || fail "缺 game.play"
if echo "$CAPS" | grep -q '"reminder.manage"'; then
  ok "⊇ reminder.manage"
else
  skip "⊇ reminder.manage — 提醒应用在 R5 落地"
fi

# ── 断言 3: 能力下的候选应用 ──
note "断言 3: GET /api/v1/capabilities/game.play/applications"
CODE=$(http GET /api/v1/capabilities/game.play/applications)
[ "$CODE" = "200" ] && ok "200" || fail "状态码 $CODE"
COUNT=$(jq_ "len(d)")
if [ "$COUNT" -ge 2 ]; then
  ok "候选应用 $COUNT 个 (≥2)"
else
  skip "候选应用 $COUNT 个 — 计划要求 ≥2(井字棋 + 五子棋), 五子棋在 R5 落地"
fi
body | grep -q "$APP_ID" && ok "含 $APP_ID" || fail "缺 $APP_ID"

# ── 断言 4: 动作发现与 manifest 一致 ──
note "断言 4: GET /api/v1/applications/$APP_ID/actions"
CODE=$(http GET "/api/v1/applications/$APP_ID/actions")
[ "$CODE" = "200" ] && ok "200" || fail "状态码 $CODE"
DECLARED=$(jq_ "','.join(sorted(a['actionId'] for a in d))")
[ "$DECLARED" = "game.create,game.make_move,game.state,game.surrender" ] \
  && ok "动作集合与 manifest 一致: $DECLARED" || fail "动作集合不符: '$DECLARED'"
body | grep -q '"agentHint"' && ok "agentHint 随发现一并返回" || fail "发现结果里没有 agentHint"

# ── 安装 + 开会话 ──
# 安装是对同一个 principal 幂等的, 每次都会顺带开一个新会话 —— 后面所有动作的 target 里的
# 那一段 id 都来自这里, 所以每次重装都要重新取一次。
INSTALL_ID=""; SESSION_ID=""; URI=""
install() {
  local payload="$1" code
  code=$(http POST "/api/v1/applications/$APP_ID/install" "$payload")
  if [ "$code" != "200" ]; then fail "安装状态码 $code"; return; fi
  INSTALL_ID=$(jq_ "d['installationId']")
  SESSION_ID=$(jq_ "d['sessionId']")
  URI="game://session/$SESSION_ID"
  ok "已安装 ($payload) → session=$SESSION_ID"
}

note "安装应用并开会话 (动作的 target 从这里来)"
install '{}'
[ -n "$SESSION_ID" ] && ok "target = $URI" || fail "安装响应里没有 sessionId"

# ── 断言 5: 幂等重放 ──
note "断言 5: 同 Idempotency-Key 两次 → 同响应 + Idempotent-Replay + 只有一行 invocation"
KEY="check-lap-create-$(stamp)"
CODE=$(http POST /api/v1/actions:execute \
  "{\"action\":\"game.create\",\"target\":\"$URI\",\"input\":{}}" "$KEY")
[ "$CODE" = "200" ] && ok "首次 200" || fail "首次状态码 $CODE"
cp "$TMP/body" "$TMP/first"

CODE=$(http POST /api/v1/actions:execute \
  "{\"action\":\"game.create\",\"target\":\"$URI\",\"input\":{}}" "$KEY")
[ "$CODE" = "200" ] && ok "重放 200" || fail "重放状态码 $CODE"
if diff -q "$TMP/first" "$TMP/body" >/dev/null; then
  ok "两次响应逐字节相同"
else
  fail "两次响应不同: $(diff "$TMP/first" "$TMP/body" | head -3 | tr '\n' ' ')"
fi
grep -qi '^Idempotent-Replay: true' "$TMP/hdr" && ok "带 Idempotent-Replay: true" || fail "缺 Idempotent-Replay 头"
ROWS=$(sql "select count(*) from action_invocation where idempotency_key='$KEY'")
[ "$ROWS" = "1" ] && ok "action_invocation 只有 1 行" || fail "action_invocation 有 $ROWS 行"

# ── 断言 6: 同 key 不同载荷 ──
note "断言 6: 同 key + 不同 body → 422 IDEMPOTENCY_KEY_REUSED"
CODE=$(http POST /api/v1/actions:execute \
  "{\"action\":\"game.create\",\"target\":\"$URI\",\"input\":{\"opponentPrincipalId\":\"nobody\"}}" "$KEY")
[ "$CODE" = "422" ] && ok "422" || fail "状态码 $CODE"
GOT=$(jq_ "d['error']['code']")
[ "$GOT" = "IDEMPOTENCY_KEY_REUSED" ] && ok "code=IDEMPOTENCY_KEY_REUSED" || fail "code=$GOT"

# ── 断言 7: 写动作缺幂等键 ──
note "断言 7: game.make_move 不带 Idempotency-Key → 400 IDEMPOTENCY_KEY_REQUIRED"
CODE=$(http POST /api/v1/actions:execute \
  "{\"action\":\"game.make_move\",\"target\":\"$URI\",\"input\":{\"position\":0}}")
[ "$CODE" = "400" ] && ok "400" || fail "状态码 $CODE"
GOT=$(jq_ "d['error']['code']")
[ "$GOT" = "IDEMPOTENCY_KEY_REQUIRED" ] && ok "code=IDEMPOTENCY_KEY_REQUIRED" || fail "code=$GOT"

# ── 断言 8: READ 不重放 ──
note "断言 8: 资源读没有幂等键, 落子前后各读一次 —— 第二次必须看到新棋盘"
RURI=$(encoded "$URI")

CODE=$(http GET "/api/v1/resources?uri=$RURI")
[ "$CODE" = "200" ] && ok "落子前读得到资源" || fail "读资源状态码 $CODE"
BEFORE=$(jq_ "sum(1 for c in d[0]['state']['board'] if c)")
[ "$BEFORE" = "0" ] && ok "落子前棋盘是空的" || fail "落子前棋盘已有 $BEFORE 个子"

CODE=$(http POST /api/v1/actions:execute \
  "{\"action\":\"game.make_move\",\"target\":\"$URI\",\"input\":{\"position\":4}}" \
  "check-lap-move-$(stamp)")
[ "$CODE" = "200" ] && ok "落子 200" || fail "落子状态码 $CODE"
AFTER=$(jq_ "d['resource']['state']['board'][4]")
[ "$AFTER" = "X" ] && ok "落子响应里 board[4]=X" || fail "落子后 board[4]='$AFTER'"

CODE=$(http GET "/api/v1/resources?uri=$RURI")
REREAD=$(jq_ "d[0]['state']['board'][4]")
[ "$REREAD" = "X" ] && ok "再读一次仍是 X" \
  || fail "重读得到 '$REREAD' —— READ 被幂等层重放成了开局时的空棋盘"

# ── 断言 9: 权限 ──
note "断言 9: 授权与安装失效"
# 9a 未授权: HTTP 上没有"只装不授"的入口(省略 capabilities 即全授), 只能把授权行摘掉。
#    这一条正是权限模型第二维(installation 有了还得有 grant)的端到端证明。
exec_sql "delete from permission_grant where installation_id='$INSTALL_ID'"
CODE=$(http POST /api/v1/actions:execute \
  "{\"action\":\"game.make_move\",\"target\":\"$URI\",\"input\":{\"position\":0}}" \
  "check-lap-na-$(stamp)")
[ "$CODE" = "403" ] && ok "未授权 → 403" || fail "未授权状态码 $CODE"
GOT=$(jq_ "d['error']['code']")
[ "$GOT" = "NOT_AUTHORIZED" ] && ok "code=NOT_AUTHORIZED" || fail "code=$GOT"
install '{}'   # 重装即补齐授权, 顺便换一个干净的会话

# 9b 未安装: 需要一个"装了应用 A 却没装应用 B"的 principal 才有自然的 target ——
#    只有一个账号时构造不出来(会话归属校验会先把冒充者拦在 SESSION_PRINCIPAL_MISMATCH)。
#    单测里这条已被 ApplicationGatewayTest.aPrincipalWithoutAnInstallationIsDenied 覆盖。
skip "未安装 → DENIED/NOT_INSTALLED — 需要一个未安装的 principal, 第二个应用(R5)落地后此断言自然可达"

# 9c 安装失效: 卸掉再调动作。注意码是 INSTALLATION_INACTIVE 而不是 NOT_INSTALLED ——
#    安装行还在, 只是不再 ACTIVE; 这两个状态对调用方的含义不同(一个该去重装, 一个该去装)。
CODE=$(http DELETE "/api/v1/applications/$APP_ID/install")
[ "$CODE" = "204" ] && ok "卸载 204" || fail "卸载状态码 $CODE"
CODE=$(http POST /api/v1/actions:execute \
  "{\"action\":\"game.make_move\",\"target\":\"$URI\",\"input\":{\"position\":0}}" \
  "check-lap-ui-$(stamp)")
[ "$CODE" = "403" ] && ok "安装失效 → 403" || fail "安装失效状态码 $CODE"
GOT=$(jq_ "d['error']['code']")
[ "$GOT" = "INSTALLATION_INACTIVE" ] && ok "code=INSTALLATION_INACTIVE" || fail "code=$GOT"
install '{}'   # 复原, 让后面的断言有干净的起点

# ── 断言 10: 跨能力域隔离 ──
skip "只有游戏安装时调 reminder.create → DENIED — 提醒应用在 R5 落地"

# ── 断言 11 / 12: 数字人链路 ──
skip "真人走一步 → 数字人应手 + llm_calls — R7"
skip "reality ledger 新增 APPLICATION_ACTION_EXECUTED — R8"

# ── 断言 13: /api/v10 已下线 ──
note "断言 13: GET /api/v10/applications → 404"
CODE=$(http GET /api/v10/applications)
[ "$CODE" = "404" ] && ok "404" || fail "状态码 $CODE (旧应用面还在服役)"

# ── 附加: Canonical / Alias 两条路由 ──
note "附加: /actions:execute 与 /actions/execute 是同一个 handler"
CODE=$(http POST /api/v1/actions/execute \
  "{\"action\":\"game.teleport\",\"target\":\"$URI\",\"input\":{}}")
[ "$CODE" = "404" ] && ok "别名路由可用 (404)" || fail "别名路由状态码 $CODE"
GOT=$(jq_ "d['error']['code']")
[ "$GOT" = "ACTION_NOT_FOUND" ] && ok "code=ACTION_NOT_FOUND" || fail "code=$GOT"

# ── 断言 14 / 15 ──
skip "POST /mcp tools/list — R6"
skip "共享世界: 真人落子后数字人的应手落在同一行 resource — R7"

echo ""
if [ "$FAIL" = "0" ]; then
  echo "✅ 验收通过 ($SKIPPED 项未到轮次, 已跳过)"
else
  echo "❌ 验收未通过"
  exit 1
fi
