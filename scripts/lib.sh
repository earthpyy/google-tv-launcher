#!/usr/bin/env bash
# Shared helpers: loads .env and resolves the paired TV's adb serial.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -f "$ROOT/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$ROOT/.env"
  set +a
fi

ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
TV_HOST="${TV_HOST:?TV_HOST is not set (see .env.example)}"

# Wireless debugging uses a random connect port, so find it via mDNS or adb devices.
tv_serial() {
  local serial
  serial="$("$ADB" devices | awk -v h="$TV_HOST:" 'index($1, h) == 1 && $2 == "device" { print $1; exit }')"
  if [[ -z "$serial" ]]; then
    local port
    port="$("$ADB" mdns services | awk -v h="$TV_HOST:" '$2 ~ /_adb-tls-connect/ && index($3, h) == 1 { split($3, a, ":"); print a[2]; exit }')"
    if [[ -z "$port" ]]; then
      echo "TV not found. Run scripts/pair.sh first." >&2
      return 1
    fi
    "$ADB" connect "$TV_HOST:$port" >&2
    serial="$TV_HOST:$port"
  fi
  echo "$serial"
}

tv() {
  "$ADB" -s "$(tv_serial)" "$@"
}
