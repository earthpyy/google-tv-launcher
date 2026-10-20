#!/usr/bin/env bash
# Run any adb command against the TV, e.g. `scripts/tv.sh shell pm enable <package>`.
source "$(dirname "$0")/lib.sh"

tv "$@"
