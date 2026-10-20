#!/usr/bin/env bash
# Snapshot package state, properties and settings before changing anything.
source "$(dirname "$0")/lib.sh"

dir="$ROOT/backup/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$dir"

tv shell pm list packages -f > "$dir/packages.txt"
tv shell pm list packages -d > "$dir/packages-disabled.txt"
tv shell pm list packages -3 > "$dir/packages-user.txt"
tv shell getprop > "$dir/getprop.txt"
for ns in global secure system; do
  tv shell settings list "$ns" > "$dir/settings-$ns.txt"
done
tv shell cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME > "$dir/home-activity.txt"

echo "Backup saved to $dir"
