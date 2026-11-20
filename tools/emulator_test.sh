#!/usr/bin/env bash
# Run by the emulator job: first the four shapes (tools/shapes.sh), then the Fanale opening held at three moments in each of the three lights
# (daylight, dusk, dark), so the lamps' look can be compared. Fails if the app crashed.
set -x
PKG=com.fergusonconsultancy.foldmotion
adb install -r FoldMotion.apk
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
adb shell wm dismiss-keyguard; adb shell input keyevent 82
adb shell settings put system screen_off_timeout 1800000
adb logcat -c
# The four shapes first, so they always report
bash tools/shapes.sh
# Scatto: the card in view, the motor's report, and the three feels played (recorded by the vibrator service)
# (20 Nov) Played twice: Caveau at Pesante (the heaviest, with the spilled-over hits), then Scrigno at Deciso
: > vibrator.txt
for pass in "CAVEAU PESANTE" "SCRIGNO DECISO"; do
  set -- $pass
  adb shell am force-stop $PKG
  adb shell am start -n $PKG/.MainActivity --es open scatto --es character $1 --es strength $2 --es sound PRESENTE
  sleep 6
  adb exec-out screencap -p > shot-scatto-$1.png
  adb logcat -d -s Scatto:I | tail -3
  echo "=== $1 $2" >> vibrator.txt
  (adb shell dumpsys vibrator_manager 2>&1 || adb shell dumpsys vibrator 2>&1) >> vibrator.txt
done
grep -iE "===|composition|primitive|$PKG" vibrator.txt | tail -30 || true
# Suono: the rendered sounds, brought out for checking (levels, length, spectrum)
for f in $(adb shell run-as $PKG ls files/suono 2>/dev/null | tr -d '\r'); do
  adb exec-out run-as $PKG cat files/suono/$f > suono-$f
done
ls -la suono-*.wav || true
# Fanale chosen before the app opens
printf '<?xml version="1.0" encoding="utf-8" standalone="yes" ?>\n<map><string name="style">FANALE</string><string name="opening_effect">FANALE</string><string name="closing_effect">FANALE</string></map>\n' > fm.xml
adb shell am start -n $PKG/.MainActivity; sleep 4; adb shell am force-stop $PKG
adb push fm.xml /data/local/tmp/fm.xml; adb shell chmod 644 /data/local/tmp/fm.xml
adb shell run-as $PKG cp /data/local/tmp/fm.xml shared_prefs/fold_motion.xml
for light in 0.05 0.5 0.95; do
  for p in 0.75 0.45 0.2; do
    adb shell am force-stop $PKG
    adb shell am start -n $PKG/.MainActivity --es hold "$light,$p"
    sleep 4
    adb shell wm dismiss-keyguard
    sleep 1
    adb exec-out screencap -p > "shot-light$light-p$p.png"
  done
done
adb logcat -d > app-log.txt
if adb logcat -d | grep -A2 "FATAL EXCEPTION" | grep -q "Process: $PKG"; then echo "::error::Fold Motion crashed"; grep -A20 "FATAL EXCEPTION" app-log.txt | head -40; exit 1; fi
echo "No crash"

# Pop-up windows and a turn (19 Nov 2026). It reboots the emulator to turn pop-up windows on, so
# it runs last, with its own log and crash check.
bash tools/windows.sh || exit 1
