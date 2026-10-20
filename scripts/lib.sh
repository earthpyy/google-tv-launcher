#!/usr/bin/env bash
# Shared helpers for the scripts in this folder: loads .env and resolves the paired TV's adb serial.
# Bash only; run the scripts rather than sourcing this into an interactive shell.
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
TV_SERIAL=""

# Wireless debugging uses a random connect port, advertised over mDNS
tv_mdns_port() {
  "$ADB" mdns services | awk -v h="$TV_HOST:" '$2 ~ /_adb-tls-connect/ && index($3, h) == 1 { split($3, a, ":"); print a[2]; exit }'
}

# Find (or connect to) the TV once and remember its serial; exits if it can't be reached
tv_connect() {
  TV_SERIAL="$("$ADB" devices | awk -v h="$TV_HOST:" 'index($1, h) == 1 && $2 == "device" { print $1; exit }')"
  if [[ -z "$TV_SERIAL" ]]; then
    local port
    port="$(tv_mdns_port)"
    if [[ -z "$port" ]]; then
      echo "TV not found. Run scripts/pair.sh first." >&2
      exit 1
    fi
    "$ADB" connect "$TV_HOST:$port" >&2
    TV_SERIAL="$TV_HOST:$port"
  fi
}

tv() {
  [[ -n "$TV_SERIAL" ]] || tv_connect
  "$ADB" -s "$TV_SERIAL" "$@"
}
