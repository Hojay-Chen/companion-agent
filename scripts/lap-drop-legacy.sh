#!/usr/bin/env bash
# LAP v1 — 删除被应用平台取代的遗留表。
#
# 为什么是独立脚本而不是启动时自动删表:
#   应用启动时 DROP TABLE 是 footgun —— 一次误配置的 DataSource 就能删掉生产表。
#   建表交给 Hibernate(ddl-auto=update), 删表交给运维, 显式、可审计、可回滚。
#
# 删什么 & 为什么:
#   dh_application            → 被 application / application_version 取代(Manifest 现在属于版本)
#   dh_game_session           → 棋局状态现在就是 resource.state_json, 不再单独建表
#   dh_application_action_log → 被 application_action_log 取代(旧表的 permission_decision 是废字段,
#                               与 execution_status 用同一个参数赋值)
#   reminders                 → 提醒归 com.luxera.reminder 应用所有(reminder_item), DH 只读
#
# 用法:
#   bash scripts/lap-drop-legacy.sh            # 预演, 只打印将执行的语句
#   bash scripts/lap-drop-legacy.sh --apply    # 真的删
#
# 前置: 确认应用平台的 LAP 面已上线且 dh_* 旧表的读路径已全部下线(R3-R8 完成后)。
set -euo pipefail

APPLY=0
[[ "${1:-}" == "--apply" ]] && APPLY=1

PSQL="psql -h 127.0.0.1 -U admin -d companion"
export PGPASSWORD="${PGPASSWORD:-shared-secret}"

TABLES=(dh_application dh_game_session dh_application_action_log reminders)

echo ""
echo "══════════ LAP v1 遗留表清理 ══════════"
if [[ "$APPLY" -eq 1 ]]; then
  echo "模式: 执行 (--apply)"
else
  echo "模式: 预演 (加 --apply 才真的删)"
fi
echo ""

for t in "${TABLES[@]}"; do
  exists=$($PSQL -tAc "select 1 from information_schema.tables where table_name='$t'" | head -1 | tr -d ' ')
  if [[ "$exists" != "1" ]]; then
    echo "  - $t: 不存在, 跳过"
    continue
  fi
  rows=$($PSQL -tAc "select count(*) from $t" 2>/dev/null | head -1 | tr -d ' ')
  if [[ "$APPLY" -eq 1 ]]; then
    $PSQL -q -c "DROP TABLE IF EXISTS $t CASCADE" >/dev/null
    echo "  ✓ $t 已删除 (原有 $rows 行)"
  else
    echo "  · $t 存在 (当前 $rows 行) → 将执行 DROP TABLE IF EXISTS $t CASCADE"
  fi
done

echo ""
if [[ "$APPLY" -eq 1 ]]; then
  echo "✅ 遗留表清理完成"
else
  echo "ℹ️  预演结束, 未做任何修改"
fi
