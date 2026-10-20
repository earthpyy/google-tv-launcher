#!/usr/bin/env bash
# Build the signed release APK, install it on the TV and make it the default home.
source "$(dirname "$0")/lib.sh"
tv_connect
: "${KEYSTORE_FILE:?KEYSTORE_FILE is not set (see .env.example)}"
export ANDROID_HOME KEYSTORE_FILE KEYSTORE_PASSWORD KEY_ALIAS KEY_PASSWORD

PKG=me.earthpyy.googletvlauncher
APK="$ROOT/app/build/outputs/apk/release/app-release.apk"

cd "$ROOT"
if command -v mise >/dev/null; then
  mise exec -- ./gradlew --quiet assembleRelease
else
  ./gradlew --quiet assembleRelease
fi

# `adb install` can hang on Android 14 waiting for an on-screen prompt, so install through pm
tv push "$APK" /data/local/tmp/launcher.apk
tv shell pm install -r /data/local/tmp/launcher.apk
tv shell rm /data/local/tmp/launcher.apk

# Location permission lets the Wi-Fi pill show the network name
tv shell pm grant "$PKG" android.permission.ACCESS_FINE_LOCATION
tv shell cmd package set-home-activity "$PKG/.MainActivity"
tv shell settings put secure screensaver_components "$PKG/.ClockDreamService"
tv shell settings put secure screensaver_default_component "$PKG/.ClockDreamService"
tv shell am start -W -a android.intent.action.MAIN -c android.intent.category.HOME > /dev/null
echo "Installed $PKG"
