#!/bin/bash
# Called on API33+ after the existing independent permission cycle has completed.
set -euo pipefail
report_dir=${1:-app/build/reports/androidTests}
mkdir -p "$report_dir"
permission_log="$report_dir/regional-watch-permission-cycle.txt"
restore_permission() {
  adb shell pm clear-permission-flags ru.tgwatch android.permission.POST_NOTIFICATIONS user-set user-fixed
  adb shell pm grant ru.tgwatch android.permission.POST_NOTIFICATIONS
}
trap restore_permission EXIT
adb shell am force-stop ru.tgwatch
adb shell pm revoke ru.tgwatch android.permission.POST_NOTIFICATIONS
adb shell pm set-permission-flags ru.tgwatch android.permission.POST_NOTIFICATIONS user-set user-fixed
permission_status=0
adb shell am instrument -w -r -e class \
  'ru.tgwatch.RegionalWatchIntegrationTest#deniedAppNotificationsDoNotConsumeTheArmedWatch' \
  ru.tgwatch.test/androidx.test.runner.AndroidJUnitRunner > "$permission_log" || permission_status=$?
python3 - "$permission_log" "$permission_status" <<'PY_PERMISSION'
import pathlib,sys
lines=pathlib.Path(sys.argv[1]).read_text().splitlines()
codes=[int(line.split(':',1)[1]) for line in lines if line.startswith('INSTRUMENTATION_STATUS_CODE:')]
if sys.argv[2]!='0' or 'OK (1 test)' not in [line.strip() for line in lines]:
    raise SystemExit('Regional watch permission cycle did not finish')
if codes.count(0)!=1 or any(code not in (0,1) for code in codes):
    raise SystemExit('Regional watch permission test failed or was skipped')
print('Regional watch permission cycle: 1 passed, 0 failed/skipped')
PY_PERMISSION
