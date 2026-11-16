#!/usr/bin/env bash
# iOS end-to-end (docs/health/E2E.md): the real app on the `Health Beta E2E` simulator.
#
#   ./reflex e2e ios
#
# 1. finds (or creates) and boots the simulator, erases it, pins it to English (the tests answer system alerts by
#    their English labels), and turns off the hardware keyboard and the keyboard tips;
# 2. builds AppMobile + AppMobileUITests once (build-for-testing);
# 3. runs each fresh-install group of journeys in its own pass, uninstalling the app before each one:
#    E1+E2+E3+E5, E4, E6, E8+E7, E9 (dark appearance + the largest accessibility text size, restored on exit);
# 4. relaunches the DEBUG app and runs tests/reflex-live against it (REFLEX_PLATFORMS=ios ./reflex test --live).
#
# Optional: E2E_SCREENSHOT_DIR=<dir> also runs the screen tour in light and dark and saves review screenshots
# (plus E9's) there. Never point it inside the repository.
set -euo pipefail

ROOT="${ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
DEVICE_NAME="Health Beta E2E"
BUNDLE_ID="com.beta.health"
PROJECT_DIR="$ROOT/ios/AppMobile"
SCREENSHOT_DIR="${E2E_SCREENSHOT_DIR:-}"

log() { printf '%s\n' "$*" >&2; }
die() { log "error: $*"; exit 1; }

# --- Preconditions ---------------------------------------------------------------------------------------------

# The clock is real and journeys schedule doses minutes ahead; a run takes about 20 minutes and must not cross
# midnight (a dose would move to another day). Refuse to start within 10 minutes after midnight or 30 before it.
minutes_now=$((10#$(date +%H) * 60 + 10#$(date +%M)))
if ((minutes_now < 10 || minutes_now > 24 * 60 - 30)); then
  die "it is $(date +%H:%M): the journeys would cross local midnight. Run again after 00:10."
fi

if lsof -nP -iTCP:7777 -sTCP:LISTEN >/dev/null 2>&1; then
  die "port 7777 is already in use (another app build or './reflex serve'?): the DEBUG app needs it. Stop it first."
fi

command -v xcodegen >/dev/null || die "xcodegen missing: brew install xcodegen (or ./reflex setup --force)"

runtime="$(xcrun simctl list runtimes available -j | /usr/bin/python3 -c '
import json, sys
runtimes = [r for r in json.load(sys.stdin)["runtimes"] if r["platform"] == "iOS" and r["version"].startswith("27")]
print(runtimes[-1]["identifier"] if runtimes else "")')"
[[ -n "$runtime" ]] || die "no iOS 27 Simulator runtime. Install it with: xcodebuild -downloadPlatform iOS"

udid="$(xcrun simctl list devices available -j | /usr/bin/python3 -c '
import json, sys
name = sys.argv[1]
for runtime, devices in json.load(sys.stdin)["devices"].items():
    for d in devices:
        if d["name"] == name and "iOS-27" in runtime:
            print(d["udid"]); sys.exit()' "$DEVICE_NAME")"
if [[ -z "$udid" ]]; then
  device_type="$(xcrun simctl list devicetypes -j | /usr/bin/python3 -c '
import json, sys
phones = [t for t in json.load(sys.stdin)["devicetypes"] if t["productFamily"] == "iPhone" and "Pro" in t["name"] and "Max" not in t["name"]]
print(phones[-1]["identifier"] if phones else "")')"
  [[ -n "$device_type" ]] || die "no iPhone device type available in this Xcode"
  log "Creating simulator '$DEVICE_NAME' ($device_type, $runtime)"
  udid="$(xcrun simctl create "$DEVICE_NAME" "$device_type" "$runtime")"
fi
log "Simulator: $DEVICE_NAME ($udid)"

# --- Fresh device ----------------------------------------------------------------------------------------------

boot() {
  xcrun simctl boot "$udid" 2>/dev/null || true
  xcrun simctl bootstatus "$udid" -b >/dev/null
}

restore_ui() {
  xcrun simctl ui "$udid" appearance light >/dev/null 2>&1 || true
  xcrun simctl ui "$udid" content_size large >/dev/null 2>&1 || true
}
trap restore_ui EXIT

xcrun simctl shutdown "$udid" >/dev/null 2>&1 || true
xcrun simctl erase "$udid"
boot
# English system UI (the permission alert is answered by "Allow" / "Don’t Allow"), no keyboard tips.
xcrun simctl spawn "$udid" defaults write -g AppleLanguages -array en-US
xcrun simctl spawn "$udid" defaults write -g AppleLocale en_US
xcrun simctl spawn "$udid" defaults write com.apple.keyboard.preferences DidShowContinuousPathIntroduction -bool true
xcrun simctl spawn "$udid" defaults write com.apple.keyboard.preferences KeyboardDidShowProductivityTutorial -bool true
xcrun simctl spawn "$udid" defaults write com.apple.keyboard.preferences DidShowGestureKeyboardIntroduction -bool true
# The software keyboard, as on a phone (a connected hardware keyboard hides it in Simulator.app).
defaults write com.apple.iphonesimulator ConnectHardwareKeyboard -bool false >/dev/null 2>&1 || true
xcrun simctl shutdown "$udid"
boot
restore_ui

# --- Build once ------------------------------------------------------------------------------------------------

derived="$(mktemp -d "${TMPDIR:-/tmp}/health-beta-e2e.XXXXXX")"
log "Building AppMobile + AppMobileUITests (derived data: $derived)"
(cd "$PROJECT_DIR" && xcodegen generate --quiet)
xcodebuild -project "$PROJECT_DIR/AppMobile.xcodeproj" -scheme AppMobile -destination "id=$udid" \
  -derivedDataPath "$derived" build-for-testing -quiet

# --- Journeys --------------------------------------------------------------------------------------------------

results=()
failed=0

# run_group <label> <test identifier> [suffix for screenshots]
run_group() {
  local label="$1" test="$2" suffix="${3:-}" log_file started elapsed status
  log ""
  log "== $label ($test)"
  xcrun simctl terminate "$udid" "$BUNDLE_ID" >/dev/null 2>&1 || true
  xcrun simctl uninstall "$udid" "$BUNDLE_ID" >/dev/null 2>&1 || true
  log_file="$derived/$(echo "$test" | tr '/' '_').log"
  started=$(date +%s)
  if TEST_RUNNER_SCREENSHOT_DIR="$SCREENSHOT_DIR" TEST_RUNNER_SCREENSHOT_SUFFIX="$suffix" \
    xcodebuild -project "$PROJECT_DIR/AppMobile.xcodeproj" -scheme AppMobile -destination "id=$udid" \
      -derivedDataPath "$derived" test-without-building -only-testing:"AppMobileUITests/$test" >"$log_file" 2>&1; then
    status=passed
  else
    status=FAILED
    failed=1
    grep -E "error:|Test Case .*failed" "$log_file" | head -20 >&2 || true
    log "   full log: $log_file"
  fi
  elapsed=$(($(date +%s) - started))
  results+=("$(printf '%-34s %-7s %4ss' "$label" "$status" "$elapsed")")
  log "   $status in ${elapsed}s"
}

run_group "E1+E2+E3+E5 add, take, reminders, persist" HealthJourneys/testAddTakeRemindersPersist
run_group "E4 notification answered Taken" HealthJourneys/testNotificationTaken
run_group "E6 notifications denied" HealthJourneys/testNotificationsDenied
run_group "E8+E7 history, lifecycle" HealthJourneys/testHistoryAndLifecycle

xcrun simctl ui "$udid" appearance dark
xcrun simctl ui "$udid" content_size accessibility-extra-extra-extra-large
run_group "E9 dark, largest text" HealthJourneys/testLargeTextDark dark
restore_ui

if [[ -n "$SCREENSHOT_DIR" ]]; then
  run_group "Screen tour (light)" ScreenTour/testScreens light
  xcrun simctl ui "$udid" appearance dark
  run_group "Screen tour (dark)" ScreenTour/testScreens dark
  restore_ui
fi

# --- Live layer ------------------------------------------------------------------------------------------------

log ""
log "== tests/reflex-live against the running DEBUG app"
xcrun simctl terminate "$udid" "$BUNDLE_ID" >/dev/null 2>&1 || true
xcrun simctl launch "$udid" "$BUNDLE_ID" >/dev/null
for _ in $(seq 1 60); do   # the app opens its socket at launch; poll (up to 30 s) instead of guessing
  lsof -nP -iTCP:7777 -sTCP:LISTEN >/dev/null 2>&1 && break
  sleep 0.5
done
started=$(date +%s)
if (cd "$ROOT" && REFLEX_PLATFORMS=ios ./reflex test --live); then status=passed; else status=FAILED; failed=1; fi
results+=("$(printf '%-34s %-7s %4ss' "tests/reflex-live" "$status" "$(($(date +%s) - started))")")
xcrun simctl terminate "$udid" "$BUNDLE_ID" >/dev/null 2>&1 || true

log ""
log "iOS end-to-end results ($DEVICE_NAME):"
printf '  %s\n' "${results[@]}" >&2
[[ -n "$SCREENSHOT_DIR" ]] && log "Screenshots: $SCREENSHOT_DIR"
if ((failed)); then die "iOS end-to-end failed (logs: $derived)"; fi
rm -rf "$derived"
log "iOS end-to-end: all green"
