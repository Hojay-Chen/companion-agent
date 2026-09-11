#!/usr/bin/env bash
# V10 边界守卫 — 跨模块依赖的静态检查。
#
# 与 ArchUnit 的关系:
#   - 本脚本是快速第一道防线(grep 源码 + 读 pom), 在 `mvn compile` 之前就能发现问题;
#   - ModuleBoundaryArchitectureTest(bootstrap-app, 唯一同时看得见所有模块的地方)是结构性第二道,
#     用 ArchUnit 对编译产物做依赖方向断言, 随 `mvn test` 执行。
#   两者都必须通过。
#
# 它守的是什么:
#   1. 四个模块各自"拥有"的包两两不相交(Java 不允许 split package, 跨模块同名包会静默合并);
#   2. chat-platform 的源码不引用 digital-human 拥有的包, 反之亦然;
#   3. Maven 依赖图与声明一致 —— chat 的 pom 里不能出现 digital-human, 反之亦然,
#      contracts 谁都不能依赖。
#
# 包归属不写死, 全部从各模块源码树推导 —— 加了新包不需要改这个脚本, 也就不会过期。
set -euo pipefail

BACKEND="$(cd "$(dirname "$0")/../backend" && pwd)"
BASE_PKG="com/luxera/companion"

fail=0
note() { echo "  $*" >&2; }
ok()   { note "✓ $*"; }
bad()  { note "✗ $*"; fail=1; }

# 模块 → 该模块 src/main/java 下 com.luxera.companion.<X> 的 <X> 集合
owned_packages() {
  local module="$1"
  local dir="$BACKEND/$module/src/main/java/$BASE_PKG"
  [[ -d "$dir" ]] || return 0
  find "$dir" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | sort
}

MODULES=(platform-kernel chat-platform digital-human-platform contracts)

echo "== 1) 包归属互斥(split package 检查) =="
declare -A OWNER
for m in "${MODULES[@]}"; do
  while read -r pkg; do
    [[ -n "$pkg" ]] || continue
    if [[ -n "${OWNER[$pkg]:-}" ]]; then
      bad "包 com.luxera.companion.$pkg 同时存在于 ${OWNER[$pkg]} 与 $m"
    else
      OWNER[$pkg]="$m"
    fi
  done < <(owned_packages "$m")
done
[[ "$fail" -eq 0 ]] && ok "包归属互斥(${#OWNER[@]} 个顶层包分属 4 个模块)"

# 每个模块拥有的包(供第 2 步用)
pkgs_of() {
  local want="$1" out=()
  for pkg in "${!OWNER[@]}"; do
    [[ "${OWNER[$pkg]}" == "$want" ]] && out+=("$pkg")
  done
  printf '%s\n' "${out[@]:-}"
}

echo "== 2) 跨模块源码引用 =="
check_no_import() {
  local src_module="$1" forbidden_module="$2"
  local dir="$BACKEND/$src_module/src"
  [[ -d "$dir" ]] || return 0
  local packages pattern hits
  packages="$(pkgs_of "$forbidden_module" | paste -sd'|' -)"
  [[ -n "$packages" ]] || return 0
  # 匹配 import / 全限定引用 com.luxera.companion.<被禁包>. ; 不匹配该模块自己拥有的包
  pattern="com\\.luxera\\.companion\\.(${packages})\\."
  hits="$(grep -rnE "$pattern" "$dir" --include=*.java 2>/dev/null | grep -v '// CHECK-V10-ALLOW' || true)"
  if [[ -n "$hits" ]]; then
    bad "$src_module 引用了 $forbidden_module 拥有的包:"
    echo "$hits" | head -5 | sed 's|^|      |' >&2
  else
    ok "$src_module 不引用 $forbidden_module 的包"
  fi
}
check_no_import chat-platform digital-human-platform
check_no_import digital-human-platform chat-platform
# contracts 是纯 DTO 模块: 谁都不能依赖
check_no_import contracts chat-platform
check_no_import contracts digital-human-platform
check_no_import contracts platform-kernel

echo "== 3) Maven 依赖图 =="
pom_depends_on() {
  local pom="$1" artifact="$2"
  [[ -f "$pom" ]] || return 1
  # 只看 <dependency> 的 artifactId, 避免注释/描述里出现同名
  grep -A 3 '<dependency>' "$pom" | grep -q "<artifactId>${artifact}</artifactId>"
}
pom_of() { echo "$BACKEND/$1/pom.xml"; }

check_pom_absent() {
  local module="$1" artifact="$2"
  if pom_depends_on "$(pom_of "$module")" "$artifact"; then
    bad "$module/pom.xml 依赖了 $artifact"
  else
    ok "$module/pom.xml 不依赖 $artifact"
  fi
}
check_pom_absent chat-platform companion-platform-digital-human
check_pom_absent digital-human-platform companion-platform-chat
check_pom_absent contracts companion-platform-chat
check_pom_absent contracts companion-platform-digital-human
check_pom_absent contracts companion-platform-kernel

if [[ "$fail" -eq 0 ]]; then
  echo "check-v10 OK"
  exit 0
fi
echo "check-v10 FAILED" >&2
exit 1
