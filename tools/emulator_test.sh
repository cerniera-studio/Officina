#!/usr/bin/env bash
# Run by the emulator job: the rack read live from GitHub (without a token, so the hourly limit may
# bite on a busy runner), a bench opened, and all four shapes. Fails if the app crashed.
set -x
PKG=com.cerniera.officina
source tools/emu.sh

adb install -r Officina.apk
adb logcat -c
adb shell am start -n $PKG/.MainActivity
sleep 20; shot rack
adb shell input swipe 540 1900 540 700 300; sleep 1; shot rack-lower
adb shell input swipe 540 700 540 1900 300; sleep 1
tap_text "Sveglia"; sleep 8; shot bench-sveglia
adb shell input swipe 540 1900 540 700 300; sleep 3; shot bench-sveglia-lower
adb shell input keyevent KEYCODE_BACK; sleep 1

bash tools/shapes.sh

adb logcat -d > app-log.txt
if crashed; then echo "::error::Officina crashed"; exit 1; fi
