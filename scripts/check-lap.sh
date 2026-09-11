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
# 用法: BASE=http://127.0.0.1:8081 LAP_MCP_SERVICE_KEY=<服务启动时用的那个> bash scripts/check-lap.sh
#
# LAP_MCP_SERVICE_KEY 是断言 14(MCP 适配器)需要的, 不是可选的礼貌参数:
# 服务端那把密钥留空 = MCP 完全关闭(每个请求 403), 而一个关闭的 MCP 与一个工作的 MCP 在
# 「没有断言」这件事上长得一模一样。所以脚本拿不到密钥时**报错**而不是跳过 —— 跳过会让
# 这一整轮验收在适配器根本没上线的情况下显示为通过。
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
echo "$CAPS" | grep -q '"reminder.manage"' && ok "⊇ reminder.manage" || fail "缺 reminder.manage"

# ── 断言 3: 能力下的候选应用 ──
note "断言 3: GET /api/v1/capabilities/game.play/applications"
CODE=$(http GET /api/v1/capabilities/game.play/applications)
[ "$CODE" = "200" ] && ok "200" || fail "状态码 $CODE"
COUNT=$(jq_ "len(d)")
if [ "$COUNT" -ge 2 ]; then
  ok "候选应用 $COUNT 个 (≥2)"
else
  fail "候选应用 $COUNT 个 — 少于 2(井字棋 + 五子棋)"
fi
body | grep -q "$APP_ID" && ok "含 $APP_ID" || fail "缺 $APP_ID"
# 同 capability 的两个应用靠各自的 URI scheme 消歧 —— 它们的 action id 是<em>完全一样</em>的,
# 所以"发现到了两个"这件事必须真的落在数据上, 不能只看数量。
body | grep -q 'com.luxera.gomoku' && ok "含 com.luxera.gomoku" || fail "缺 com.luxera.gomoku"

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
INSTALL_ID=""; SESSION_ID=""; URI=""; PRINCIPAL_ID=""
install() {
  local payload="$1" code
  code=$(http POST "/api/v1/applications/$APP_ID/install" "$payload")
  if [ "$code" != "200" ]; then fail "安装状态码 $code"; return; fi
  INSTALL_ID=$(jq_ "d['installationId']")
  SESSION_ID=$(jq_ "d['sessionId']")
  PRINCIPAL_ID=$(jq_ "d['principalId']")
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

# 9b 未安装: 这个 principal 装了游戏、没装提醒 —— 从 R5 起它有一个自然的 target
#    (提醒是"主体型资源", URI 里没有 {sessionId}, 所以不需要先开会话就能构造)。
#    判据是 NOT_INSTALLED 而不是 NOT_AUTHORIZED: 这两行对应的是权限模型的第一维, 缺了就得先去装。
#
#    但"没装过"这个状态在 HTTP 面上够不到: 装过一次就永远留着一条 UNINSTALLED 的安装行, 那走的是
#    9c 的 INSTALLATION_INACTIVE。更要紧的是 check.sh 会经数字人的提醒链路把提醒应用装给同一个人,
#    所以这里先把痕迹清掉 —— 与 9a 摘授权同一个套路: 没有入口的中间状态, 只能从数据造。
#    (断言 10 会重新装上, 世界随后复原。)
exec_sql "delete from application_session where application_id='com.luxera.reminder'"
exec_sql "delete from installation where application_id='com.luxera.reminder'"
RURI="reminder://owner/$PRINCIPAL_ID"
CODE=$(http POST /api/v1/actions:execute \
  "{\"action\":\"reminder.create\",\"target\":\"$RURI\",\"input\":{\"title\":\"不该建出来\",\"dueAt\":\"2026-09-12T15:00\"}}" \
  "check-lap-ni-$(stamp)")
[ "$CODE" = "403" ] && ok "未安装提醒应用 → 403" || fail "未安装状态码 $CODE"
GOT=$(jq_ "d['error']['code']")
[ "$GOT" = "NOT_INSTALLED" ] && ok "code=NOT_INSTALLED" || fail "code=$GOT"

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

# ── 断言 10: 跨能力域 —— 装与不装之间那一步 ──
# 计划里 9b 与 10 描述的是<em>同一次拒绝</em>(只装了游戏时调 reminder.create)。上面 9b 已经把
# 那次拒绝钉住了, 这里改成它的正面对照: 补上安装之后, 同一个 principal 走<em>同一个</em>
# execute 端点把提醒建出来, 并且从同一个资源读路径读回来。
# 少了这一条, 9b 的 403 也可能只是因为整条提醒链路根本是死的。
note "断言 10: 装上提醒应用后, 同一个 execute 端点把提醒建出来并读回收件箱"
CODE=$(http POST "/api/v1/applications/com.luxera.reminder/install" '{}')
[ "$CODE" = "200" ] && ok "安装提醒应用 200" || fail "安装状态码 $CODE"

CODE=$(http POST /api/v1/actions:execute \
  "{\"action\":\"reminder.create\",\"target\":\"$RURI\",\"input\":{\"type\":\"user_set\",\"title\":\"验收提醒\",\"dueAt\":\"2026-09-12T15:00\"}}" \
  "check-lap-rc-$(stamp)")
[ "$CODE" = "200" ] && ok "reminder.create 200" || fail "创建状态码 $CODE"
RID=$(jq_ "d['result']['id']")
[ -n "$RID" ] && ok "拿到提醒 id=$RID" || fail "响应里没有 result.id"

CODE=$(http GET "/api/v1/resources?uri=$(encoded "$RURI")")
[ "$CODE" = "200" ] && ok "读回收件箱 200" || fail "读收件箱状态码 $CODE"
# 读路径走的是 ResourceProjector(提醒状态在 reminder_item 表里, 不在 resource 表里) ——
# resource 表里没有这一行, 所以这一次 200 就是 "APP_OWNED backing 真的能用" 的端到端证明。
FOUND=$(jq_ "sum(1 for i in d[0]['state']['items'] if i['id']=='$RID')")
[ "$FOUND" = "1" ] && ok "刚建的提醒出现在同一个 URI 读出来的 items 里" \
  || fail "收件箱里找不到 $RID"

# 收尾: 把提醒应用卸掉, 让后面(以及重跑)的世界回到"只装了游戏"的干净状态。
http DELETE "/api/v1/applications/com.luxera.reminder/install" >/dev/null

# ── 断言 11: 数字人链路 ──
# 这条断言的判据随服务的 LLM 而不同, 但**两种模式下都断言**:
#   默认(服务跑 mock LLM, 见 application.yml 的 mock-fallback): 断言"保险丝通着电"——
#     事件确实走完了 应用 → 平台 → 数字人 整条链, 数字人确实读了资源、确实看见有动作可做,
#     然后**故意**不动手, 并在日志里留下那句话。只断言"棋盘上没有多出 O"是不够的 ——
#     事件从没送到也能满足它。日志是唯一能区分"故意不动手"与"链路没通"的东西。
#   真实 LLM(LAP_EXPECT_AGENT_MOVE=1): 断言完整往返 —— 应手落在同一行 resource 上, 且
#     llm_calls 留下这次动作选择的记录。
# 两种模式都需要日志文件: 服务是 nohup java -jar ... > <log> 起的, 默认取 /tmp/companion-run.log。
DH_AGENT="check-lap-dh-$(stamp)"
DH_CREATE_KEY="check-lap-dh-create-$(stamp)"
DH_MOVE_KEY="check-lap-dh-move-$(stamp)"
DH_MODE=""

dh_cleanup() {
  exec_sql "delete from permission_grant where installation_id in
            (select id from installation where principal_type='AGENT' and principal_id='$DH_AGENT')" || true
  exec_sql "delete from action_invocation where principal_type='AGENT' and principal_id='$DH_AGENT'" || true
  exec_sql "delete from application_session where principal_type='AGENT' and principal_id='$DH_AGENT'" || true
  exec_sql "delete from installation where principal_type='AGENT' and principal_id='$DH_AGENT'" || true
}
trap 'dh_cleanup; rm -rf "$TMP"' EXIT

note "断言 11: 真人走一步 → 数字人的应手 (R7)"
LAP_LOG="${LAP_LOG:-/tmp/companion-run.log}"
if [ ! -f "$LAP_LOG" ]; then
  fail "找不到服务日志 $LAP_LOG —— 没有它就无法区分'数字人故意不动手'与'事件根本没送到'。
        用 LAP_LOG=<服务 stdout 重定向到的文件> 再跑。"
else
  # 数字人得先"装了"这个应用, 平台才认得出它是数字人: AgentRouteResolver 查的就是 installation
  # 表。与断言 14 同一个理由 —— AGENT 在 HTTP 面上装不了应用, 只能从数据造。
  exec_sql "insert into installation (id, application_id, application_version_id, principal_type,
                                     principal_id, status, created_at)
            select gen_random_uuid()::text, application_id, application_version_id, 'AGENT',
                   '$DH_AGENT', 'ACTIVE', now()
            from installation
            where application_id='$APP_ID' and principal_type='HUMAN' and principal_id='$PRINCIPAL_ID'
            limit 1"

  # 新开一局: 对手座位上坐着这位数字人 —— 应用因此会把"轮到你了"写进事件的 notifyPrincipalIds。
  # 这一步同时验证了那个分工: 应用只说"还有谁", 谁是数字人由平台查安装表决定。
  PREV_URI="$URI"
  install '{}'
  DH_URI="$URI"
  if [ "$DH_URI" = "$PREV_URI" ]; then
    fail "重新安装没有开出新会话 ($DH_URI) —— 这一局会带着前面几手棋, 断言 11 不成立"
  fi
  CODE=$(http POST /api/v1/actions:execute \
    "{\"action\":\"game.create\",\"target\":\"$DH_URI\",\"input\":{\"opponentPrincipalId\":\"$DH_AGENT\"}}" \
    "$DH_CREATE_KEY")
  [ "$CODE" = "200" ] && ok "开局 200, 对手座位 = $DH_AGENT" || fail "开局状态码 $CODE"

  LINES_BEFORE=$(wc -l < "$LAP_LOG")
  CODE=$(http POST /api/v1/actions:execute \
    "{\"action\":\"game.make_move\",\"target\":\"$DH_URI\",\"input\":{\"position\":0}}" "$DH_MOVE_KEY")
  [ "$CODE" = "200" ] && ok "真人落子 board[0] (X)" || fail "真人落子状态码 $CODE"

  # 数字人那条链跑在 mailbox 线程上, 给它最多 15 秒
  for _ in $(seq 1 30); do
    NEW=$(tail -n +"$((LINES_BEFORE + 1))" "$LAP_LOG")
    if echo "$NEW" | grep -qF "数字人不行动: resource=$DH_URI"; then DH_MODE="declined"; break; fi
    if echo "$NEW" | grep -qF "数字人执行动作: action=game.make_move, resource=$DH_URI"; then DH_MODE="moved"; break; fi
    sleep 0.5
  done

  case "$DH_MODE" in
    declined)
      ok "事件走完了整条链: 应用 → 平台 → 数字人; 数字人读了资源、看见有动作可做, 然后拒绝" ;;
    moved)
      ok "事件走完了整条链, 数字人执行了动作" ;;
    *)
      fail "15 秒内没有看到 AgentApplicationFlow 处理这条事件 —— 事件链路断了? 日志: $LAP_LOG" ;;
  esac

  if [ -n "${LAP_EXPECT_AGENT_MOVE:-}" ] && [ "$DH_MODE" != "moved" ]; then
    fail "LAP_EXPECT_AGENT_MOVE=1 但数字人没有行动 —— 服务跑的多半还是 mock LLM"
  fi

  CODE=$(http GET "/api/v1/resources?uri=$(encoded "$DH_URI")")
  [ "$CODE" = "200" ] && ok "真人读得到这一局" || fail "读资源状态码 $CODE"
  DH_BOARD=$(jq_ "' '.join(x or '.' for x in d[0]['state']['board'])")
  DH_O=$(jq_ "sum(1 for x in d[0]['state']['board'] if x=='O')")

  if [ "$DH_MODE" = "moved" ]; then
    [ "$DH_O" = "1" ] && ok "数字人的应手在同一行 resource 上: $DH_BOARD" \
      || fail "数字人执行了动作, 棋盘上却有 $DH_O 个 O: $DH_BOARD"
    # 落库的这一刻就是"契约接通了"的凭据: LlmCallService 在 companionId 为空时静默跳过,
    # 所以这一行存在 = AgentApplicationFlow 设了 metadata, 且用途路由没有把它弄丢。
    LLM_ROWS=$(sql "select count(*) from llm_calls where companion_id='$DH_AGENT' and task='application-action-selection'")
    [ "${LLM_ROWS:-0}" -ge 1 ] && ok "llm_calls 里有 $LLM_ROWS 行 application-action-selection" \
      || fail "llm_calls 里没有这次动作选择的记录 —— 契约没接通或调用没落库"
  else
    [ "$DH_O" = "0" ] && ok "数字人没有动手 (LLM 不可用 ⇒ 绝不降级到启发式): $DH_BOARD" \
      || fail "数字人在 mock LLM 下仍然落了子 —— 有人加了一条启发式兜底: $DH_BOARD"
  fi

  # 复原: 断言 11 把 $URI 换成了数字人那一局, 而后面(附加/14)假设 $URI 是一盘没下过的棋。
  # 再装一次拿一个新会话还给它们 —— 这一局留给断言 15 用。
  install '{}'
fi

# ── 断言 12: 现实账本 ──
# 账本条目是"数字人真的动了手"的产物, 所以它和断言 15 是同一个前提, 不是同一轮次的事。
if [ "$DH_MODE" = "moved" ] && [ -n "$DH_AGENT" ]; then
  ROWS=$(sql "select count(*) from timeline_event where person_id='$DH_AGENT' and event_type='APPLICATION_ACTION_EXECUTED'")
  [ "${ROWS:-0}" -ge 1 ] && ok "现实账本(timeline_event)里有一条 APPLICATION_ACTION_EXECUTED" \
    || fail "数字人落了子, 账本里却没有这条经历 (rows=$ROWS)"
else
  skip "reality ledger 新增 APPLICATION_ACTION_EXECUTED — 需要真实 LLM (当前 $DH_MODE)"
fi

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

# ── 断言 14: MCP 适配器 ──
# MCP 只是一个适配器: tools/list 走动作发现, tools/call 走 ActionGateway.execute —— 与真人
# **同一条**路。所以这一段的判据不是"MCP 返回了 200", 而是"真人从 REST 读同一个 URI 时,
# 看见 MCP 客户端刚下的那一手"。前者在适配器自己伪造响应时也会绿。
#
# 两条前提, 缺一条这一段就什么也测不到:
#   1. **服务必须带 LAP_MCP_SERVICE_KEY 启动**(见 McpPrincipalResolver: 密钥留空 = MCP 完全关闭,
#      不是"无鉴权")。脚本自己没有这个密钥就没法鉴权, 那是配置缺失, 报错而不是跳过。
#   2. AGENT 装不了 REST 那一面(那个面只认 JWT, MCP 客户端没有 JWT), 所以安装行只能从数据造 ——
#      与断言 9a/9b 同一套路: HTTP 面上够不到的状态, 只能从数据造。
MCP_KEY="${LAP_MCP_SERVICE_KEY:-}"
MCP_AGENT="check-lap-agent-$(stamp)"
MCP_SQL_AGENT_INSTALL="application_id='$APP_ID' and principal_type='AGENT' and principal_id='$MCP_AGENT'"

# MCP 请求助手: 与 http() 同形, 但带头(而且不是 Authorization —— MCP 没有 JWT)。
mcp() {
  local payload="$1" key="${2:-}"
  local args=(-s -m 20 -o "$TMP/body" -D "$TMP/hdr" -w '%{http_code}' -X POST "$BASE/mcp"
    -H 'Content-Type: application/json'
    -H "X-Mcp-Principal: AGENT:$MCP_AGENT"
    -H "X-Mcp-Service-Key: $MCP_KEY"
    -d "$payload")
  if [ -n "$key" ]; then args+=(-H "Idempotency-Key: $key"); fi
  curl "${args[@]}" || echo "000"
}
mcp_cleanup() {
  exec_sql "delete from permission_grant where installation_id in (select id from installation where $MCP_SQL_AGENT_INSTALL)"
  exec_sql "delete from application_session where $MCP_SQL_AGENT_INSTALL"
  exec_sql "delete from installation where $MCP_SQL_AGENT_INSTALL"
}

note "断言 14: POST /mcp —— tools/list 给目录, tools/call 改的是真人读的同一个 resource"
if [ -z "$MCP_KEY" ]; then
  fail "LAP_MCP_SERVICE_KEY 未提供 —— 脚本无法以 AGENT 身份调用 MCP, 断言 14 无法进行"
else
  # 先清后建, 让脚本可以重复跑
  mcp_cleanup
  exec_sql "insert into installation (id, application_id, application_version_id, principal_type, principal_id, status, created_at) \
            select gen_random_uuid()::text, application_id, application_version_id, 'AGENT', '$MCP_AGENT', 'ACTIVE', now() \
            from installation where application_id='$APP_ID' and principal_type='HUMAN' and principal_id='$PRINCIPAL_ID' limit 1"
  exec_sql "insert into permission_grant (id, installation_id, capability_id, action_id, permission_level, risk_ceiling, created_at) \
            select gen_random_uuid()::text, t.id, g.capability_id, g.action_id, g.permission_level, g.risk_ceiling, now() \
            from installation t, permission_grant g \
            where $MCP_SQL_AGENT_INSTALL \
              and g.installation_id=(select id from installation where application_id='$APP_ID' and principal_type='HUMAN' and principal_id='$PRINCIPAL_ID' limit 1)"
  GRANTS=$(sql "select count(*) from installation t join permission_grant g on g.installation_id=t.id where t.principal_id='$MCP_AGENT'")
  [ "${GRANTS:-0}" -ge 1 ] && ok "AGENT 安装 + $GRANTS 条授权 (SQL 造)" || fail "AGENT 授权没造出来"

  CODE=$(mcp '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}')
  [ "$CODE" = "200" ] && ok "initialize 200" || fail "initialize 状态码 $CODE"
  grep -qi '^Mcp-Session-Id:' "$TMP/hdr" && ok "回了 Mcp-Session-Id (协议会话, 不落库)" \
    || fail "缺 Mcp-Session-Id 响应头"
  PROTO=$(jq_ "d['result']['protocolVersion']")
  [ "$PROTO" = "2025-06-18" ] && ok "协商到的协议版本 $PROTO" || fail "协议版本 '$PROTO'"

  CODE=$(mcp '{"jsonrpc":"2.0","id":2,"method":"tools/list"}')
  [ "$CODE" = "200" ] && ok "tools/list 200" || fail "tools/list 状态码 $CODE"
  body | grep -q 'tictactoe.game_make_move' && ok "工具名含 tictactoe.game_make_move" \
    || fail "目录里没有 tictactoe.game_make_move"
  body | grep -q 'gomoku.game_make_move' && ok "工具名含 gomoku.game_make_move (同名动作不撞车)" \
    || fail "目录里没有 gomoku.game_make_move"
  body | grep -q 'game://session/{sessionId}' && ok "工具描述里写明了 target 的形态" \
    || fail "工具描述里没有 target 形态 —— 客户端无从知道该填什么"
  ok "共 $(jq_ "len(d['result']['tools'])") 个工具"

  # 真人开一局、落一子(X)
  CODE=$(http POST /api/v1/actions:execute \
    "{\"action\":\"game.create\",\"target\":\"$URI\",\"input\":{}}" "check-lap-mcp-c-$(stamp)")
  [ "$CODE" = "200" ] && ok "真人 game.create 200" || fail "真人 create 状态码 $CODE"
  CODE=$(http POST /api/v1/actions:execute \
    "{\"action\":\"game.make_move\",\"target\":\"$URI\",\"input\":{\"position\":0}}" "check-lap-mcp-h-$(stamp)")
  [ "$CODE" = "200" ] && ok "真人落子 0 200" || fail "真人落子状态码 $CODE"

  SESSIONS_BEFORE=$(sql "select count(*) from application_session")

  # MCP 客户端应手: 同一个 resource URI, 另一个 principal, 另一条传输
  CODE=$(mcp "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"tictactoe.game_make_move\",\"arguments\":{\"target\":\"$URI\",\"position\":4}}}" \
    "check-lap-mcp-a-$(stamp)")
  [ "$CODE" = "200" ] && ok "MCP tools/call 200" || fail "tools/call 状态码 $CODE"
  ISERR=$(jq_ "d['result']['isError']")
  [ "$ISERR" = "False" ] && ok "isError=false" \
    || fail "动作被拒: isError=$ISERR code=$(jq_ "d['result']['structuredContent']['error']['code']")"
  MARK=$(jq_ "d['result']['structuredContent']['result']['board'][4]")
  [ "$MARK" = "O" ] && ok "Agent 作为 O 落在 board[4]" || fail "board[4]='$MARK' (期望 Agent 的 O)"

  # 真人这一侧: 同一个 URI, 同一个读路径
  CODE=$(http GET "/api/v1/resources?uri=$(encoded "$URI")")
  [ "$CODE" = "200" ] && ok "真人读同一个 URI 200" || fail "读资源状态码 $CODE"
  B0=$(jq_ "d[0]['state']['board'][0]"); B4=$(jq_ "d[0]['state']['board'][4]")
  if [ "$B0" = "X" ] && [ "$B4" = "O" ]; then
    ok "共享世界: 同一行 resource, board[0]=X(真人) / board[4]=O(Agent)"
  else
    fail "两边不是同一盘棋: board[0]='$B0' board[4]='$B4'"
  fi

  # MCP Session ≠ ApplicationSession —— R6 最要紧的那条不变量
  SESSIONS_AFTER=$(sql "select count(*) from application_session")
  [ "$SESSIONS_AFTER" = "$SESSIONS_BEFORE" ] && ok "整条 MCP 链路没有创建 ApplicationSession" \
    || fail "application_session 从 $SESSIONS_BEFORE 涨到 $SESSIONS_AFTER —— MCP 会话污染了归属链"

  mcp_cleanup
fi

# ── 断言 15: 共享世界 —— 一行 resource, 两个 principal ──
# 这是整个 LAP 最想证明的一句话: 真人和数字人不是各玩各的, 他们操作的是同一个东西。
# 判据不是"两边都返回 200", 而是数据行本身: 一行 resource、一条会话、两个不同的 principal
# 各自在这条会话上留下过 action_invocation。
# 数字人那一半需要真实 LLM(数字人拒绝在 mock 下动手 —— 见断言 11), 所以这一段在 mock 环境下
# 跳过; 真人 + MCP Agent 的那一半由断言 14 在同一个 URI 上证明, 两条合起来覆盖完整的"共享世界"。
note "断言 15: 共享世界 —— 一行 resource, 两个 principal"
if [ "$DH_MODE" = "moved" ] && [ -n "$DH_URI" ]; then
  ROWS=$(sql "select count(*) from resource where uri='$DH_URI'")
  [ "$ROWS" = "1" ] && ok "resource 表里这一局只有一行" || fail "resource 有 $ROWS 行 ($DH_URI)"

  SID=$(sql "select session_id from resource where uri='$DH_URI'")
  ACTORS=$(sql "select count(distinct principal_id) from action_invocation where session_id='$SID'")
  [ "${ACTORS:-0}" -ge 2 ] && ok "同一条会话上有 $ACTORS 个不同的 principal 动过手" \
    || fail "同一条会话上只有 ${ACTORS:-0} 个 principal —— 数字人那一手不在这一局里"
else
  skip "共享世界: 数字人的应手落在同一行 resource — 需要真实 LLM (当前 $DH_MODE)"
fi

echo ""
if [ "$FAIL" = "0" ]; then
  echo "✅ 验收通过 ($SKIPPED 项未到轮次, 已跳过)"
else
  echo "❌ 验收未通过"
  exit 1
fi
