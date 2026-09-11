#!/usr/bin/env bash
# LAP v1 应用平台 — 端到端验收。
#
# 为什么必须有这个脚本:
#   scripts/check.sh 覆盖的是聊天/数字人链路, 对应用平台「零覆盖」——
#   这就是为什么「mvn test 全绿」在应用平台上什么也保护不了。
#   本脚本是 ApplicationGateway / Manifest / Resource / 幂等 / MCP 唯一的端到端守卫。
#
# 断言清单见实施计划; R4 起逐步打开, R8 全部打开(含旧表不存在)。
#
# 用法: BASE=http://127.0.0.1:8081 bash scripts/check-lap.sh
set -euo pipefail

BASE="${BASE:-http://127.0.0.1:8081}"
PY=python3
FAIL=0

note() { echo "==> $*"; }
ok()   { echo "    ✓ $*"; }
fail() { echo "    ✗ $*"; FAIL=1; }

echo ""
echo "══════════ LAP v1 应用平台验收 ══════════"
note "R0: 脚本骨架已就位; 断言将在 R4 起逐步实现"
fail "尚未实现 —— 应用平台 LAP 面未上线 (R4 起填充)"
echo ""
echo "❌ 验收未通过"
exit 1
