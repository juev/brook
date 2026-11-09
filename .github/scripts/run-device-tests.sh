#!/usr/bin/env bash
# Runs the instrumented suites at compact and genuinely wide emulator viewports.
# The wide classes run separately so each requested class has its own execution receipt.
#
# reactivecircus/android-emulator-runner@v2 splits the multiline `script` input and runs each
# nonempty line in its own shell, so functions, traps and variables cannot span lines. Both the
# canonical CI and the temporary proof call this file as one line:
#
#     bash .github/scripts/run-device-tests.sh
#
# The compact pass covers the accounts and migration suites; LargeTest is excluded there and is
# joined by ReaderTranslationDeviceTest in the wide pass. Results and screenshots of every
# invocation are copied out before the next one, which may replace the reports and device files.
set -uo pipefail

EVIDENCE="device-evidence"
SCREENSHOT_DIR="/sdcard/Android/data/org.evsyukov.brook/files/screenshots"
LARGE_CLASS="org.evsyukov.brook.ui.home.FeedNavigationLargeTest"
TRANSLATION_CLASS="org.evsyukov.brook.ui.reader.ReaderTranslationDeviceTest"
# That option keeps the app and its external files on the device after the run. AGP 9.4.1 reads it
# in DeviceProviderInstrumentTestTask.CreationAction, and TestRunnerFactory passes it to
# UtpTestRunner as uninstallApksAfterTest=false. Without it UTP removes the APKs and with them
# /sdcard/Android/data/org.evsyukov.brook/files/screenshots before the pull below.
GRADLE=(./gradlew --no-daemon --max-workers=2 --no-build-cache --no-configuration-cache
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true)
CHECK=(python3 -I .github/scripts/check-instrumented-results.py)

fail() {
  echo "::error::$*"
}

reset_display() {
  adb shell wm size reset >/dev/null 2>&1 || true
  adb shell wm density reset >/dev/null 2>&1 || true
}
trap reset_display EXIT

mkdir -p "$EVIDENCE/compact"/{results,reports,logs,screenshots} \
  "$EVIDENCE/large"/{results,reports,logs,screenshots}

# AGP writes the JUnit XML either directly under test-results/connected or in a per-device folder
# under outputs/androidTest-results/connected; flatten whatever is there so the checker sees one
# directory of TEST-*.xml files.
collect_xml() {
  local dest="$1"
  shift
  local root
  mkdir -p "$dest"
  for root in "$@"; do
    [ -d "$root" ] || continue
    find "$root" -type f -name 'TEST-*.xml' -exec cp {} "$dest"/ \;
  done
}

pull_screenshots() {
  local dest="$1"
  local pass="$2"
  mkdir -p "$dest"
  adb pull "$SCREENSHOT_DIR/." "$dest/" 2>&1 | tee -a "$EVIDENCE/$pass/logs/adb-pull.log" || true
}

# The classes the compact pass must run: every instrumented test source except the large-window
# one. The checker then proves each of them ran real cases and none was skipped or failed.
compact_classes=()
while IFS= read -r source; do
  fqcn=${source#app/src/androidTest/java/}
  fqcn=${fqcn%.kt}
  compact_classes+=("${fqcn//\//.}")
done < <(find app/src/androidTest/java -type f -name '*Test.kt' \
  ! -name 'FeedNavigationLargeTest.kt' | sort)
if [ "${#compact_classes[@]}" -eq 0 ]; then
  fail "no compact instrumented test sources under app/src/androidTest/java"
  exit 1
fi

# --- Compact pass: the emulator's own portrait viewport -------------------------------

"${GRADLE[@]}" connectedDebugAndroidTest --rerun \
  -Pandroid.testInstrumentationRunnerArguments.notClass="$LARGE_CLASS" \
  2>&1 | tee "$EVIDENCE/compact/logs/gradle.log"
compact_status=${PIPESTATUS[0]}

collect_xml "$EVIDENCE/compact/results" \
  app/build/outputs/androidTest-results/connected \
  app/build/test-results/connected
cp -r app/build/reports/androidTests/connected "$EVIDENCE/compact/reports/" 2>/dev/null || true
# Pull now: the wide Gradle run may reinstall the app and clear its external files.
pull_screenshots "$EVIDENCE/compact/screenshots" compact

"${CHECK[@]}" \
  --results "$EVIDENCE/compact/results" \
  --tests "${compact_classes[*]}" 2>&1 | tee "$EVIDENCE/compact/logs/check.txt"
compact_check=${PIPESTATUS[0]}

# --- Wide pass: a real viewport past the production large breakpoint ------------------

adb shell wm size 1920x2560
adb shell wm density 240
sleep 2

# The comma-separated class filter in CI 42570669640 executed only the first class.
# Retain both required classes and the same assertions, using one proven single-class
# invocation each. Separate directories prevent the second device XML replacing the first.
large_status=0
large_check=0
for test_class in "$LARGE_CLASS" "$TRANSLATION_CLASS"; do
  suite=${test_class##*.}
  mkdir -p "$EVIDENCE/large/reports/$suite"
  "${GRADLE[@]}" connectedDebugAndroidTest --rerun \
    -Pandroid.testInstrumentationRunnerArguments.class="$test_class" \
    2>&1 | tee "$EVIDENCE/large/logs/gradle-$suite.log"
  run_status=${PIPESTATUS[0]}
  [ "$large_status" -ne 0 ] || large_status=$run_status

  collect_xml "$EVIDENCE/large/results/$suite" \
    app/build/outputs/androidTest-results/connected \
    app/build/test-results/connected
  cp -r app/build/reports/androidTests/connected "$EVIDENCE/large/reports/$suite/" 2>/dev/null || true
  pull_screenshots "$EVIDENCE/large/screenshots" large

  "${CHECK[@]}" \
    --results "$EVIDENCE/large/results/$suite" \
    --tests "$test_class" 2>&1 | tee "$EVIDENCE/large/logs/check-$suite.txt"
  check_status=${PIPESTATUS[0]}
  [ "$large_check" -ne 0 ] || large_check=$check_status
done

# --- Screenshots: the eight the feature promises, each a written PNG ------------------

shots_ok=0
verify_shot() {
  local path="$1"
  local signature
  if [ ! -s "$path" ]; then
    fail "screenshot $path is missing or empty"
    return 1
  fi
  signature=$(head -c 8 "$path" | od -An -tx1 | tr -d ' \n')
  if [ "$signature" != "89504e470d0a1a0a" ]; then
    fail "screenshot $path is not a PNG"
    return 1
  fi
}

for name in compact-toolbar-light compact-picker-light compact-toolbar-dark compact-picker-dark \
  mode-menu large-font-duplicate-titles long-list; do
  verify_shot "$EVIDENCE/compact/screenshots/$name.png" || shots_ok=1
done
verify_shot "$EVIDENCE/large/screenshots/large-layout.png" || shots_ok=1
verify_shot "$EVIDENCE/compact/screenshots/reader-translation-compact.png" || shots_ok=1
verify_shot "$EVIDENCE/large/screenshots/reader-translation-wide.png" || shots_ok=1

# The first failed step decides the exit status: a real test failure is not replaced by a later
# screenshot or checker error. A pass that ran no test or skipped one fails even when Gradle did not.
status=0
if [ "$compact_status" -ne 0 ]; then
  status=$compact_status
elif [ "$compact_check" -ne 0 ]; then
  status=$compact_check
elif [ "$large_status" -ne 0 ]; then
  status=$large_status
elif [ "$large_check" -ne 0 ]; then
  status=$large_check
elif [ "$shots_ok" -ne 0 ]; then
  status=1
fi

if [ "$status" -ne 0 ]; then
  fail "device tests failed (compact=$compact_status compact-check=$compact_check large=$large_status large-check=$large_check screenshots=$shots_ok)"
fi
exit "$status"
