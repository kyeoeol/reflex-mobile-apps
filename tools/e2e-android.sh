#!/usr/bin/env bash
# End-to-end on an Android emulator (docs/health/E2E.md): boots the API 37 AVD if needed, installs the debug app and
# the self-instrumenting UI test APK (granting nothing but exact alarms, the one documented exception), runs the
# journeys E1-E9, then relaunches the DEBUG app and runs tests/reflex-live against it over `adb forward`.
#
#   ./reflex e2e android
#
# Environment: ROOT, JAVA_HOME, ANDROID_HOME (set by ./reflex); REFLEX_E2E_SCREENSHOTS=<dir> to keep the review
# screenshots E9 takes (never commit them); REFLEX_E2E_KEEP_EMULATOR=1 to leave an emulator this script started running;
# REFLEX_LIVE_PORT=<port> for the host side of the forward (default 7777; the iOS simulator's DEBUG app also binds
# 127.0.0.1:7777 on the host, so pick another port when both platforms run at once).
set -euo pipefail

ROOT="${ROOT:-$(cd "$(dirname "$0")/.." && pwd)}"
AVD="health-beta-api37"
IMAGE="system-images;android-37.0;google_apis;arm64-v8a"
PACKAGE="com.beta.health"
ACTIVITY="$PACKAGE/app.reflex.mobile.MainActivity"
TEST_PACKAGE="app.reflex.e2e"
RUNNER="$TEST_PACKAGE/androidx.test.runner.AndroidJUnitRunner"
PORT="${REFLEX_LIVE_PORT:-7777}" # host side; the app always listens on the device's 127.0.0.1:7777
DEVICE_PORT=7777
SCREENS_ON_DEVICE="/data/local/tmp/health-beta-screens"

log() { printf '\033[1m==>\033[0m %s\n' "$*" >&2; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

# Prerequisites (never installed silently) -------------------------------------------------------------------------
[[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME" ]] || die "ANDROID_HOME is not set; run ./reflex setup --force"
[[ -n "${JAVA_HOME:-}" ]] || die "JAVA_HOME is not set; run ./reflex setup --force"
ADB="$ANDROID_HOME/platform-tools/adb"
EMULATOR="$ANDROID_HOME/emulator/emulator"
AVDMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
[[ -x "$ADB" ]] || die "adb missing: $SDKMANAGER platform-tools"
[[ -x "$EMULATOR" ]] || die "emulator missing: $SDKMANAGER emulator"
[[ -d "$ANDROID_HOME/${IMAGE//;//}" ]] || die "system image missing: $SDKMANAGER \"$IMAGE\""
if ! "$EMULATOR" -list-avds 2>/dev/null | grep -qx "$AVD"; then
  [[ -x "$AVDMANAGER" ]] || die "avdmanager missing: $SDKMANAGER \"cmdline-tools;latest\""
  log "Creating AVD $AVD (Pixel profile, $IMAGE)"
  echo no | JAVA_HOME="$JAVA_HOME" "$AVDMANAGER" create avd -n "$AVD" -k "$IMAGE" -d pixel_9a >/dev/null
fi
if lsof -nP -iTCP:$PORT -sTCP:LISTEN >/dev/null 2>&1; then
  die "port $PORT is already in use on this machine (another reflex serve or forward, or the iOS DEBUG app?); free it or set REFLEX_LIVE_PORT"
fi

# Device -------------------------------------------------------------------------------------------------------------
STARTED_EMULATOR=""
running_emulator() { "$ADB" devices | awk '/^emulator-[0-9]+\tdevice$/ {print $1; exit}'; }
SERIAL="$(running_emulator)"
if [[ -z "$SERIAL" ]]; then
  log "Cold-booting $AVD (no snapshot, headless)"
  "$EMULATOR" -avd "$AVD" -no-window -no-audio -no-snapshot -no-boot-anim >"${TMPDIR:-/tmp}/reflex-e2e-emulator.log" 2>&1 &
  STARTED_EMULATOR=1
  "$ADB" -e wait-for-device
  SERIAL="$(running_emulator)"
  [[ -n "$SERIAL" ]] || die "emulator didn't come up (see ${TMPDIR:-/tmp}/reflex-e2e-emulator.log)"
fi
adb() { "$ADB" -s "$SERIAL" "$@"; }
log "Waiting for $SERIAL to finish booting"
# Polled on the device (bounded: 300 s).
adb shell 'for i in $(seq 1 300); do [ "$(getprop sys.boot_completed)" = 1 ] && exit 0; sleep 1; done; exit 1' ||
  die "$SERIAL didn't finish booting"

cleanup() {
  local status=$?
  "$ADB" -s "$SERIAL" forward --remove tcp:$PORT >/dev/null 2>&1 || true
  "$ADB" -s "$SERIAL" shell settings put system font_scale 1.0 >/dev/null 2>&1 || true
  "$ADB" -s "$SERIAL" shell cmd uimode night no >/dev/null 2>&1 || true
  if [[ -n "$STARTED_EMULATOR" && "${REFLEX_E2E_KEEP_EMULATOR:-}" != "1" ]]; then
    "$ADB" -s "$SERIAL" emu kill >/dev/null 2>&1 || true
  fi
  exit $status
}
trap cleanup EXIT INT TERM

# The clock is real: it must match the host, and no journey may cross a day boundary.
host_epoch="$(date +%s)"
device_epoch="$(adb shell date +%s | tr -d '\r')"
drift=$((device_epoch - host_epoch)); drift=${drift#-}
((drift <= 30)) || die "emulator clock is ${drift}s off the host clock; cold boot it or sync its time"
device_minutes=$((10#$(adb shell date +%H | tr -d '\r') * 60 + 10#$(adb shell date +%M | tr -d '\r')))
if ((device_minutes < 10 || device_minutes >= 24 * 60 - 20)); then
  die "too close to the device's local midnight ($(adb shell date +%H:%M | tr -d '\r')): the journeys must not cross a day; retry later"
fi

log "Animations off, screen stays on"
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell svc power stayon true
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard >/dev/null 2>&1 || true

# Build and install (no runtime permission is granted) --------------------------------------------------------------
log "Building the debug app and the UI test APK"
(cd "$ROOT/android" && ./gradlew --quiet :app:assembleDebug :e2e:assembleDebug)
APP_APK="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$ROOT/android/e2e/build/outputs/apk/debug/e2e-debug.apk"
log "Installing fresh"
adb uninstall "$PACKAGE" >/dev/null 2>&1 || true
adb uninstall "$TEST_PACKAGE" >/dev/null 2>&1 || true
adb install "$APP_APK" >/dev/null
adb install "$TEST_APK" >/dev/null
# The one exception (docs/health/E2E.md): API 37 denies exact alarms by default.
adb shell appops set "$PACKAGE" SCHEDULE_EXACT_ALARM allow
adb shell rm -rf "$SCREENS_ON_DEVICE"
adb logcat -c

# UI journeys ------------------------------------------------------------------------------------------------------
log "Running the UI journeys (E4 waits for a real notification, up to 4 minutes)"
results="$(mktemp)"
started=$(date +%s); test_started=$started; current=""; failed=0
while IFS= read -r line; do
  line="${line%$'\r'}"
  printf '%s\n' "$line" >>"$results"
  case "$line" in
    "INSTRUMENTATION_STATUS: test="*) current="${line#*test=}" ;;
    "INSTRUMENTATION_STATUS_CODE: 1") test_started=$(date +%s) ;;
    "INSTRUMENTATION_STATUS_CODE: 0") printf '  PASS  %-55s %4ss\n' "$current" "$(($(date +%s) - test_started))" ;;
    "INSTRUMENTATION_STATUS_CODE: -2" | "INSTRUMENTATION_STATUS_CODE: -1")
      failed=1; printf '  FAIL  %-55s %4ss\n' "$current" "$(($(date +%s) - test_started))" ;;
  esac
done < <(adb shell am instrument -w -r -e class "$TEST_PACKAGE.HealthJourneysTest" "$RUNNER" 2>&1)
adb logcat -d -s HealthE2E:I | sed -n 's/.*HealthE2E: /  /p'
summary="$(grep -E '^(OK \(|FAILURES!!!|Tests run:|INSTRUMENTATION_RESULT: shortMsg)' "$results" || true)"
printf '%s\n' "$summary"
log "UI journeys took $(($(date +%s) - started))s"
if ((failed)) || ! grep -q '^OK (' "$results"; then
  grep -A12 '^INSTRUMENTATION_STATUS: stack=' "$results" | head -60 >&2 || true
  die "UI journeys failed (full output: $results)"
fi

if [[ -n "${REFLEX_E2E_SCREENSHOTS:-}" ]]; then
  mkdir -p "$REFLEX_E2E_SCREENSHOTS"
  adb pull "$SCREENS_ON_DEVICE/." "$REFLEX_E2E_SCREENSHOTS" >/dev/null
  log "Review screenshots: $REFLEX_E2E_SCREENSHOTS"
fi

# Live layer against the running DEBUG build ---------------------------------------------------------------------------
log "Relaunching the app on Today for tests/reflex-live"
adb shell input keyevent KEYCODE_HOME
# A new process, so Core starts on Today (bounded: 10 s). Never force-stop: that would also clear the alarms.
adb shell "for i in \$(seq 1 50); do am kill $PACKAGE; pidof $PACKAGE >/dev/null || exit 0; sleep 0.2; done; exit 1" ||
  die "couldn't stop the app's process before the live run"
adb shell am start -W -n "$ACTIVITY" >/dev/null # returns once Today is drawn; the socket server starts with the app
lsof -nP -iTCP:$PORT -sTCP:LISTEN >/dev/null 2>&1 && die "port $PORT was taken during the run; set REFLEX_LIVE_PORT"
adb forward tcp:$PORT tcp:$DEVICE_PORT >/dev/null
# Wait (bounded: ~20 s) until the DEBUG server answers through the forward with its `ready` event.
for _ in $(seq 1 10); do
  nc -w 2 127.0.0.1 $PORT </dev/null 2>/dev/null | head -1 | grep -q '"ready"' && break
done
REFLEX_LIVE_PORT=$PORT REFLEX_PLATFORMS=android "$ROOT/reflex" test --live
