#!/bin/sh
# Build, install on the TV, grant usage access, compile ahead of time (Android 7 installs
# as interpret-only), and launch.
set -e
cd "$(dirname "$0")/.."
if [ -z "$TV" ]; then
  echo "Set your TV's IP first, e.g.:  TV=192.168.1.50:5555 ./tools/install.sh"
  exit 1
fi
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
./gradlew assembleRelease -q
"$ADB" connect "$TV" >/dev/null
"$ADB" -s "$TV" install -r app/build/outputs/apk/release/app-release.apk
# Continue row: usage access can't be granted from the TV's settings UI for this app.
"$ADB" -s "$TV" shell appops set dk.adam.tv GET_USAGE_STATS allow
"$ADB" -s "$TV" shell cmd package compile -m speed -f dk.adam.tv
"$ADB" -s "$TV" shell am force-stop dk.adam.tv
"$ADB" -s "$TV" shell am start -n dk.adam.tv/.MainActivity
