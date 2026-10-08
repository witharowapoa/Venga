#!/usr/bin/env bash
# Installs the APK on the emulator, walks through the main screens, and saves screenshots.
# Fails if any check fails or the app crashes.
set -uo pipefail

APK="$1"
PKG=au.nick.venga
OUT=shots
WORDS=app/src/main/assets/words.txt
mkdir -p "$OUT"
FAILS=0

log()  { echo "== $*"; }
fail() { echo "FAIL: $*"; FAILS=$((FAILS + 1)); }
shot() { adb exec-out screencap -p > "$OUT/$1.png"; }
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml ui.xml >/dev/null 2>&1; }
has()  { dump; python3 ci/ui.py has ui.xml "$1"; }
tap()  {
  dump
  local xy
  xy=$(python3 ci/ui.py tap ui.xml "$1") || { fail "couldn't find '$1' on screen"; return 1; }
  adb shell input tap $xy
  sleep "${2:-2}"
}
scroll_down() { adb shell input swipe 540 1500 540 900 400; sleep 1; }
scroll_top()  { for i in 1 2 3 4 5 6 7 8; do adb shell input swipe 540 500 540 1700 200; done; sleep 1; }
# Scroll down until "text" is on screen, then tap it.
find_tap() {
  for i in $(seq 1 15); do
    if has "$1"; then tap "$1" "${2:-2}"; return 0; fi
    scroll_down
  done
  fail "couldn't find '$1' after scrolling"; return 1
}
type_answer() {
  local prompt ans
  dump
  prompt=$(python3 ci/ui.py after ui.xml "How do you say")
  ans=$(python3 ci/ui.py answer "$WORDS" "$prompt")
  echo "card: '$prompt' → typing '$ans'"
  tap "Type it" 2
  adb shell input text "$ans"
  sleep 1
  adb shell input keyevent 66
  sleep 2
}

adb install -r "$APK" || { echo "install failed"; exit 1; }
adb shell pm grant $PKG android.permission.RECORD_AUDIO || true
adb logcat -c
adb shell am start -n $PKG/.MainActivity
sleep 8

log "Home screen"
shot 01-home
has "Venga" || fail "home screen didn't show the title"
has "Empezar" || fail "no start button"

log "Card 1: correct typed answer"
tap "Empezar" 3
shot 02-card
has "How do you say" || fail "card screen didn't open"
type_answer
shot 03-correct
has "Muy bien" || has "Close enough" || fail "correct answer not marked correct"

log "Card 2: wrong answer"
tap "Siguiente" 2
tap "Type it" 2
adb shell input text "xyzzy"
adb shell input keyevent 66
sleep 2
shot 04-wrong
has "Not quite" || fail "wrong answer not marked wrong"

log "Card 3: mic button (no real speech here, must not crash)"
tap "Siguiente" 2
dump
adb shell input tap $(python3 ci/ui.py tap ui.xml "Speak your answer" || echo "540 1300")
sleep 6
shot 05-mic
log "Card 3: show answer"
tap "Show answer" 2
shot 06-shown
has "Siguiente" || fail "show answer didn't reveal the answer"

log "Back home, word list"
tap "Salir" 3
has "Empezar" || fail "Salir didn't return home"
find_tap "Word list" 3
shot 07-words
has "Tap" || fail "word list didn't open"
adb shell input keyevent 4 ; sleep 2
has "Empezar" || fail "back button didn't return home"

log "Dark mode"
find_tap "Match phone" 2   # → Light
shot 08-settings
tap "Light" 3              # → Dark
scroll_top
shot 09-home-dark
tap "Empezar" 3
shot 10-card-dark
type_answer
shot 11-result-dark

log "Crash check"
adb logcat -d > "$OUT/logcat.txt"
if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt" && grep -A5 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -q "$PKG"; then
  fail "the app crashed (see logcat.txt)"
  grep -A20 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -40
fi
adb shell pidof $PKG >/dev/null || fail "app is no longer running"

echo "Smoke test finished with $FAILS failure(s)"
exit $FAILS
