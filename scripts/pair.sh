#!/usr/bin/env bash
# Pair with the TV over Wireless debugging and connect.
# On the TV: Settings > System > Developer options > Wireless debugging > Pair device with pairing code.
source "$(dirname "$0")/lib.sh"

read -rp "Pairing port shown on the TV (IP address & Port, e.g. 40367): " pair_port
read -rp "Wi-Fi pairing code: " code
"$ADB" pair "$TV_HOST:$pair_port" "$code"

port="$("$ADB" mdns services | awk -v h="$TV_HOST:" '$2 ~ /_adb-tls-connect/ && index($3, h) == 1 { split($3, a, ":"); print a[2]; exit }')"
if [[ -z "$port" ]]; then
  read -rp "Connect port (the IP address & Port on the Wireless debugging screen): " port
fi
"$ADB" connect "$TV_HOST:$port"
"$ADB" devices
