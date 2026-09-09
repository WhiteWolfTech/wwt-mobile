#!/usr/bin/env bash
# Run this repo's Espresso tests on real devices via BrowserStack App Automate.
#
# Why Espresso and not Appium: Compose testTags are not in the accessibility tree,
# so Appium cannot see them and would have to select on visible text. Espresso also
# runs IN the app process, which is the only way to seed a signed-in session now that
# password login is gone.
#
# Usage:
#   scripts/browserstack-espresso.sh [-d "Google Pixel 8-14.0"] [-c tech.whitewolf.app.ShellNavTest]
#
# Requires ~/.browserstack.env with BROWSERSTACK_USERNAME and BROWSERSTACK_ACCESS_KEY.
set -euo pipefail

DEVICE="${BS_DEVICE:-Google Pixel 8-14.0}"
CLASS_FILTER=""
while getopts "d:c:" opt; do
  case "$opt" in
    d) DEVICE="$OPTARG" ;;
    c) CLASS_FILTER="$OPTARG" ;;
    *) echo "usage: $0 [-d device] [-c test.Class]" >&2; exit 2 ;;
  esac
done

ENV_FILE="${BROWSERSTACK_ENV:-$HOME/.browserstack.env}"
[ -r "$ENV_FILE" ] || { echo "missing $ENV_FILE" >&2; exit 1; }
# shellcheck disable=SC1090
set -a; . "$ENV_FILE"; set +a
: "${BROWSERSTACK_USERNAME:?}" "${BROWSERSTACK_ACCESS_KEY:?}"
API="https://api-cloud.browserstack.com/app-automate"

# Credentials go in a 0600 curl config, never on the command line: anything passed as
# an argument is visible in the process table (`ps`) to every other user on the host,
# and lands in shell history. Removed on any exit path.
CURL_CFG=$(mktemp)
chmod 600 "$CURL_CFG"
trap 'rm -f "$CURL_CFG"' EXIT INT TERM
printf 'user = "%s:%s"\n' "$BROWSERSTACK_USERNAME" "$BROWSERSTACK_ACCESS_KEY" > "$CURL_CFG"

APP_APK="app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
for f in "$APP_APK" "$TEST_APK"; do
  [ -f "$f" ] || { echo "missing $f — run: ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest" >&2; exit 1; }
done

echo "==> uploading app"
APP_URL=$(curl -sS -K "$CURL_CFG" -X POST "$API/upload" -F "file=@$APP_APK" | jq -r '.app_url // empty')
[ -n "$APP_URL" ] || { echo "app upload failed" >&2; exit 1; }

echo "==> uploading test suite"
TEST_URL=$(curl -sS -K "$CURL_CFG" -X POST "$API/espresso/v2/test-suite" -F "file=@$TEST_APK" | jq -r '.test_suite_url // empty')
[ -n "$TEST_URL" ] || { echo "test-suite upload failed" >&2; exit 1; }

PAYLOAD=$(jq -n --arg app "$APP_URL" --arg ts "$TEST_URL" --arg dev "$DEVICE" --arg cls "$CLASS_FILTER" '
  {app: $app, testSuite: $ts, devices: [$dev], project: "wwt-mobile", deviceLogs: true}
  + (if $cls == "" then {} else {class: [$cls]} end)')

echo "==> starting build on $DEVICE"
BUILD_ID=$(curl -sS -K "$CURL_CFG" -X POST "$API/espresso/v2/build" \
  -H "Content-Type: application/json" -d "$PAYLOAD" | jq -r '.build_id // empty')
[ -n "$BUILD_ID" ] || { echo "build did not start" >&2; exit 1; }
echo "    build $BUILD_ID"

echo "==> waiting"
for _ in $(seq 1 120); do
  RESP=$(curl -sS -K "$CURL_CFG" "$API/espresso/v2/builds/$BUILD_ID")
  STATUS=$(echo "$RESP" | jq -r '.status // "unknown"')
  case "$STATUS" in
    running|queued) sleep 15 ;;
    *) break ;;
  esac
done

# Shape is devices[].sessions[].testcases — NOT devices[].test_status.
echo "$RESP" | jq '{
  status,
  devices: [.devices[]? | {
    device,
    sessions: [.sessions[]? | {status, counts: .testcases.status}]
  }]
}'

FAILED=$(echo "$RESP" | jq -r '[.devices[]?.sessions[]?.testcases.status.failed // 0] | add // 0')
[ "$FAILED" = "0" ] || echo "    $FAILED failing test(s) — see the build URL for logs and video" >&2

echo "==> https://app-automate.browserstack.com/builds/$BUILD_ID"
case "$STATUS" in
  passed) exit 0 ;;
  *) echo "build status: $STATUS" >&2; exit 1 ;;
esac
