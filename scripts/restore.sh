#!/usr/bin/env bash
# Re-enable everything disabled by the debloat and launcher swap, and restore the stock home.
source "$(dirname "$0")/lib.sh"
tv_connect

for list in "$ROOT/debloat-list.txt" "$ROOT/launcher-swap-list.txt"; do
  [[ -f "$list" ]] || continue
  while read -r pkg; do
    [[ -z "$pkg" || "$pkg" == \#* ]] && continue
    echo "Enabling $pkg"
    tv shell pm enable "$pkg" < /dev/null || true
  done < "$list"
done

tv shell cmd package set-home-activity com.google.android.apps.tv.launcherx/.home.HomeActivity || true
tv shell settings put secure screensaver_components com.google.android.apps.tv.dreamx/.service.Backdrop || true
tv shell settings put secure screensaver_default_component com.google.android.apps.tv.dreamx/.service.Backdrop || true
echo "Done. Uninstalled apps (Hotstar, DAZN, Twitch, Viu, Doonung) can be reinstalled from the Play Store."
