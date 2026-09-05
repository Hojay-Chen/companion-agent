#!/usr/bin/env bash
# V10 boundary guard — grep-based cross-module dependency check.
# Sourced by mvn test via `bash scripts/check-v10.sh` and by `mvn verify` (R1.5+).
#
# Conventions:
#   chat-platform  → ..chatplatform..
#   digital-human-platform → ..digitalhuman..  (or ..dh..  — R1.5 TBD)
#   contracts      → ..contracts..
#   bootstrap-app  → ..bootstrapapp..  (R1.6 TBD)
#
# This script is a fast first-pass check. The ArchUnit ArchitectureTest in
# contracts/src/test/.../ArchitectureTest.java is the structural companion and is run as
# part of `mvn test`. Both must pass.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../backend" && pwd)"

fail=0
note() { echo "  $*" >&2; }
ok()   { note "✓ $*"; }
bad()  { note "✗ $*"; fail=1; }

# Allow either layout: after R1.1 the modules may not exist yet (this script must still pass
# during the transitional platform-core stage), so we conditionally check each module dir.
chat_dir="$ROOT/chat-platform"
dh_dir="$ROOT/digital-human-platform"
boot_dir="$ROOT/bootstrap-app"

# Only run cross-module checks once chat-platform/ and digital-human-platform/ exist.
if [[ ! -d "$chat_dir" && ! -d "$dh_dir" ]]; then
  echo "check-v10: platform-core monolith — nothing to check yet (R1 baseline)"
  exit 0
fi

# 1) chat-platform must not import digitalhuman (DH-side classes that live in platform-core today
#    live under com.luxera.companion.digitalhuman.* and stay there until R1.5).
if [[ -d "$chat_dir" ]]; then
  if grep -rn 'com\.luxera\.companion\.digitalhuman' "$chat_dir/src" 2>/dev/null \
      | grep -v '// CHECK-V10-ALLOW' >/dev/null; then
    bad "chat-platform imports com.luxera.companion.digitalhuman.*"
    grep -rn 'com\.luxera\.companion\.digitalhuman' "$chat_dir/src" 2>/dev/null | head -5 >&2
  else
    ok "chat-platform does not import digitalhuman"
  fi
fi

# 2) digital-human-platform must not import chatplatform
if [[ -d "$dh_dir" ]]; then
  if grep -rn 'com\.luxera\.companion\.chatplatform' "$dh_dir/src" 2>/dev/null >/dev/null; then
    bad "digital-human-platform imports com.luxera.companion.chatplatform.*"
    grep -rn 'com\.luxera\.companion\.chatplatform' "$dh_dir/src" 2>/dev/null | head -5 >&2
  else
    ok "digital-human-platform does not import chatplatform"
  fi
fi

# 3) neither side may reference the legacy simulator package directly (use contracts.dhcp)
for d in "$chat_dir" "$dh_dir"; do
  if [[ -d "$d" ]] && grep -rn 'com\.luxera\.companion\.simulator\.CapabilityCommand' "$d/src" 2>/dev/null >/dev/null; then
    bad "$(basename "$d") references legacy simulator.CapabilityCommand"
  fi
done

# 4) DH must not directly @Entity reference chatplatform domain entities.
if [[ -d "$dh_dir" ]]; then
  if grep -rn 'import com\.luxera\.companion\.chatplatform\.domain\.' "$dh_dir/src" 2>/dev/null >/dev/null; then
    bad "digital-human-platform imports chat DB domain entity"
  fi
fi

if [[ "$fail" -eq 0 ]]; then
  echo "check-v10 OK"
  exit 0
else
  echo "check-v10 FAILED" >&2
  exit 1
fi
