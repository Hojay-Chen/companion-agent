#!/usr/bin/env bash
# LAP v2 — 重建归属链上的表。
#
# 为什么必须有这一步, 而且必须在 R9 的第一步:
#   `application_session` 的形状变了 —— 它不再是"某个 principal 的安装的下游", 而是"多人共用的
#   一个运行实例"。旧列 `installation_id` 是 **NOT NULL**, 而新代码不会再写它。Hibernate 的
#   `ddl-auto=update` 只会加列、不会删列, 所以不重建的话, 每一次开会话的 INSERT 都会直接失败
#   —— 报的不是"形状不对", 而是 `null value in column "installation_id" violates not-null
#   constraint`, 看起来像业务 bug, 实际是迁移没做。
#
#   `action_invocation` 同理: 幂等唯一键从 (principal, key) 变成 (principal, session, key),
#   而 `ddl-auto` 不会去改一个已存在的唯一索引。留着旧索引不会报错, 只会让"同一个人在两局棋里
#   用同一个 key"被误判成重放。
#
# 为什么 DROP 而不是 ALTER ... DROP COLUMN:
#   库里确认有 0 条外键约束、且这些表里没有生产数据(只有测试产生的行)。ALTER 需要逐列写清楚,
#   而漏掉一列就是一个只在特定路径上才炸的 bug; DROP + 让 Hibernate 按实体重建, 保证库里的形状
#   与实体声明**逐字一致** —— 不会存在"实体改了但库没跟上"的中间状态。
#
# 删哪四张:
#   installation      → 概念已消失(原则 1: Application 不需要用户安装)。入口 POST /install 同时 404。
#   permission_grant  → 由 session_permission 取代, 外键从 installation_id 换成 participant_id。
#   application_session / action_invocation → 形状变了, 见上。
#
# 不动哪三张:
#   developer / application / application_version / capability / application_capability /
#   resource / subscription / application_action_log / lap_outbox —— 形状没变, 数据留着。
#   resource.session_id 保持可空: 提醒收件箱(`reminder://owner/{userId}`)本来就不挂会话。
#
# 用法:
#   bash scripts/lap-v2-reset.sh            # 预演, 只打印将执行的语句与行数
#   bash scripts/lap-v2-reset.sh --apply    # 真的删
#
# 前置: 服务**停掉**(否则连接池里那些持有旧语句计划的会话会在重建后报奇怪的错), 然后启动服务,
#       由 Hibernate 按实体把四张表建回来。
set -euo pipefail

APPLY=0
[[ "${1:-}" == "--apply" ]] && APPLY=1

export PGPASSWORD="${PGPASSWORD:-shared-secret}"

# 顺序无关紧要(库里 0 条外键约束), 但仍然按"叶子在前"排 —— 万一以后有人加了约束, 这个顺序是对的。
TABLES=(permission_grant installation action_invocation application_session)

# 两个库都要重建。`companion_test` 不是可选项: 它的表是同一批实体、同一个 ddl-auto 建出来的,
# 形状一模一样地过期 —— 漏掉它的话, 症状会在 `mvn test` 里以同样那句 not-null violation 出现,
# 而那时候人会去翻测试代码, 不会想到是迁移。
DATABASES=(${LAP_DATABASES:-companion companion_test})

echo ""
echo "══════════ LAP v2 归属链表重建 ══════════"
if [[ "$APPLY" -eq 1 ]]; then
  echo "模式: 执行 (--apply)"
else
  echo "模式: 预演 (加 --apply 才真的删)"
fi
echo ""
echo "DROP 之后表是空的, 由服务启动时的 Hibernate ddl-auto=update 按实体重建。"
echo ""

for db in "${DATABASES[@]}"; do
  PSQL="psql -h 127.0.0.1 -U admin -d $db"
  if ! $PSQL -tAc "select 1" >/dev/null 2>&1; then
    echo "── 库 $db: 连不上(多半是还没建), 跳过"
    echo ""
    continue
  fi
  echo "── 库 $db"
  for t in "${TABLES[@]}"; do
    exists=$($PSQL -tAc "select 1 from information_schema.tables where table_name='$t'" | head -1 | tr -d ' ')
    if [[ "$exists" != "1" ]]; then
      echo "  - $t: 不存在, 跳过(首次运行或已重建过)"
      continue
    fi
    rows=$($PSQL -tAc "select count(*) from $t" 2>/dev/null | head -1 | tr -d ' ')
    if [[ "$APPLY" -eq 1 ]]; then
      $PSQL -q -c "DROP TABLE IF EXISTS $t CASCADE" >/dev/null
      echo "  ✓ $t 已删除 (原有 ${rows:-0} 行)"
    else
      echo "  · $t 存在 (当前 ${rows:-0} 行) → 将执行 DROP TABLE IF EXISTS $t CASCADE"
    fi
  done
  echo ""
done

echo ""
echo "── 重建后应当成立的形状(供启动后自查) ──"
cat <<'SHAPE'
  application_session         有 owner_principal_type / join_policy / min_participants ...;
                              没有 installation_id / principal_type / companion_id
  application_session_participant   存在, UQ uq_session_participant(session_id, principal_type, principal_id)
  session_permission          存在, participant_id 非空
  action_invocation           UQ uq_action_invocation_key 含 session_id
  installation / permission_grant   不存在
SHAPE
echo ""
if [[ "$APPLY" -eq 1 ]]; then
  echo "✅ 重建完成 —— 现在启动服务, 让 Hibernate 建表; check-lap.sh 断言 1 会逐列核对"
else
  echo "ℹ️  预演结束, 未做任何修改"
fi
