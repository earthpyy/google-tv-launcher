# google-tv-launcher

Custom made Google TV Launcher based on [Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv).

An Apple TV style home screen for a **Chromecast with Google TV (4K, Android 14)**: pitch black background, a four-app dock, a 4-column app grid below it, one glass focus highlight, a clock in [Outfit](https://github.com/Outfitio/Outfit-Fonts) (SIL OFL), and optional [Home Assistant](https://www.home-assistant.io) controls. No blur, no video, no background polling.

## Setup

Requirements: [mise](https://mise.jdx.dev) (pins Java 17) and the Android SDK (platform-tools, platform 34).

```sh
cp .env.example .env   # then fill in the values
mise install
```

`.env` holds the TV address, the release keystore path and passwords, the dock apps and the Home Assistant settings. It is gitignored and never committed. The keystore lives outside the repo in `~/.keystores/googletvlauncher.jks`, so copy that file and `.env` to any other machine you build on (otherwise reinstalling fails with a signature mismatch).

To create a keystore from scratch:

```sh
keytool -genkeypair -keystore ~/.keystores/googletvlauncher.jks -alias googletvlauncher \
  -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=googletvlauncher"
```

## Connecting to the TV

The TV uses **Wireless debugging** (paired over TLS). Port 5555 is never opened.

1. On the TV: **Settings → System → Developer options → Wireless debugging**, make sure it is **on**. Android turns it off whenever Wi-Fi drops.
2. Choose **Pair device with pairing code**. Leave that screen open.
3. On the Mac: `./scripts/pair.sh`, enter the port shown under the code (`192.168.10.210:<port>`) and the 6-digit code.

If `adb` says "No route to host" even though the TV is reachable, macOS Local Network privacy is blocking it: allow your terminal app in **System Settings → Privacy & Security → Local Network** and restart the terminal. If a connect port looks stale, run `adb kill-server` and pair again.

## Scripts

| Script | What it does |
|---|---|
| `scripts/pair.sh` | Pair and connect over Wireless debugging |
| `scripts/tv.sh` | Run any adb command against the TV (finds the current connect port for you) |
| `scripts/backup.sh` | Save package lists, `getprop` and all settings to `backup/<timestamp>/` (gitignored) |
| `scripts/build-install.sh` | Build the signed release APK, install it, set it as home and as the screensaver |
| `scripts/restore.sh` | Re-enable everything in `debloat-list.txt` and `launcher-swap-list.txt` and restore the stock home and ambient mode |

## What was changed on the TV

- **Uninstalled** (reinstall from the Play Store): Disney+ Hotstar, DAZN, Twitch, Viu, Doonung.
- **Disabled**: everything in [`debloat-list.txt`](debloat-list.txt) (Google TV/Play Movies, Play Games, ambient mode, feedback/telemetry, print spooler, TalkBack, calendar) and [`launcher-swap-list.txt`](launcher-swap-list.txt) (stock launcher, setup wizard).
- **Kept**: Google Assistant, Cast, firmware updates, remote/TV control setup (`tv.axel`), and every system module.

### Re-enabling an app

```sh
./scripts/tv.sh shell pm enable <package>
```

Or run `scripts/restore.sh` to undo everything at once.

### Why the setup wizard is disabled

On Google TV, `com.google.android.tungsten.setupwraith` grabs HOME as soon as the stock launcher is disabled. Disabling it deletes every Wi-Fi network it created during first setup, so before disabling it the Wi-Fi networks were forgotten and re-joined in Settings (now owned by the system). Check with:

```sh
./scripts/tv.sh shell "dumpsys wifi | grep -o 'cname=[^ ]*' | sort | uniq -c"
```

## Customising

All in `.env` (see [`.env.example`](.env.example)), then `./scripts/build-install.sh`. The values are baked into the APK at build time.

- **Dock apps and order**: `DOCK_PACKAGES`, comma-separated, left to right. Find package names with `./scripts/tv.sh shell pm list packages -3`.
- **Hide apps from the grid**: add them to `HIDDEN_PACKAGES`. Play Store and Settings are always hidden; they have their own pills.
- **Wallpaper**: put an image in `app/src/main/res/drawable/` (e.g. `wallpaper.jpg`) and set `WALLPAPER_DRAWABLE` to its name without the extension (`wallpaper`). Leave it empty for pitch black. A dark gradient is drawn over it so the text stays readable.

## Home Assistant

Set `HASS_URL` and a long-lived access token in `HASS_TOKEN` (HA profile → Security). Without both, nothing below is shown.

- **Glance line** under the date: outside, `HASS_WEATHER` (condition icon, temperature and condition) and `HASS_AQI` (amber above 100, red above 150); then inside, `HASS_INSIDE_TEMPERATURE` and `HASS_INSIDE_HUMIDITY`. Icons are [Material Symbols](https://github.com/google/material-design-icons) (Apache 2.0).
- **Lights pill**: `HASS_LIGHT`, any light or switch. OK turns it on or off.
- **AC pill**: `HASS_CLIMATE`. OK opens a panel: Up/Down pick Power, Temperature or Fan; OK toggles power; Left/Right change temperature (`HASS_CLIMATE_MIN_TEMP` to `HASS_CLIMATE_MAX_TEMP`) and fan speed (`HASS_CLIMATE_FAN_MODES`, e.g. `auto,low,mid:Medium,high`). Changes are sent 500 ms after the last press. Back closes it.

Leave any entity empty to hide that part; an unavailable entity shows `–`. States are fetched when the home screen appears and after each action, never in the background. If Home Assistant can't be reached, the last values stay on screen, dimmed.

## Navigation

- Up/Down move exactly one row. Up from the dock goes to the top bar; Down from the dock goes into the grid.
- Left/Right stop at the ends of a row; they never wrap or fall through.
- Back returns to the dock. Home while already home also returns to the dock.
- The last focused tile is remembered when you come back from an app.
