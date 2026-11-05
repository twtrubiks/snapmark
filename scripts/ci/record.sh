#!/usr/bin/env bash
# 在模擬器上先跑 DeviceTest（Android 9 的 SQLite，不錄影），再一邊錄影一邊跑 DemoRecordingTest，
# 最後覆蓋安裝 release 版做冒煙測試（R8 壓過的程式打得開、首頁畫得出來）。
# 輸出到 $1（預設 out/）：progress.txt、device-test.txt、demo.raw.mp4、instrument.txt、logcat.txt、screenshots/，
# 以及 release 版冒煙測試的 release-smoke.txt、logcat-release.txt、screenshots/release-*.png
# 由 .github/workflows/record.yml 在 android-emulator-runner 裡呼叫，工作目錄是 repo 根目錄。
# 系統畫面的 SystemUiTest 還沒有，做到相機、照片、存檔時再加
set -uo pipefail

OUT=${1:-out}
APP_ID=com.twtrubiks.snapmark
TEST_CLASS=$APP_ID.DemoRecordingTest
DEVICE_TEST_CLASS=$APP_ID.DeviceTest
mkdir -p "$OUT"

# adb 指令都限時：卡住的話早點停下來，後面的步驟還能把 log 與截圖收回來，不用等到整個 job 逾時。
# 預設 120 秒，要更久的在前面加 ADB_TIMEOUT=…。已經自己包 timeout 的（am instrument）呼叫的是執行檔，不經過這裡
ADB_BIN=$(command -v adb)
adb() { timeout "${ADB_TIMEOUT:-120}" "$ADB_BIN" "$@"; }

# 每一段開始時記一行（含時間）：卡住時看 progress.txt 就知道卡在哪
step() { echo "$(date +%T) $*" | tee -a "$OUT/progress.txt"; }

step "安裝 debug 版與測試 APK"
ADB_TIMEOUT=300 adb install -r -t app/build/outputs/apk/debug/app-debug.apk || exit 1
ADB_TIMEOUT=300 adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk || exit 1

# 喚醒並解鎖螢幕、在影片裡顯示點擊位置
# （不用 keyevent 82：MENU 鍵在桌面會叫出長按選單，被錄進影片開頭）
adb shell input keyevent KEYCODE_WAKEUP || true
adb shell wm dismiss-keyguard || true
adb shell settings put system show_touches 1 || true

# 剛開機時系統服務還很忙：System UI 會 ANR、測試要幾十秒才啟動。
# 等 1 分鐘 load average 降到 2.5 以下再開始，最多等 3 分鐘。
for _ in $(seq 1 36); do
  load=$(adb shell cat /proc/loadavg | cut -d' ' -f1)
  echo "loadavg: $load"
  awk -v l="$load" 'BEGIN { exit !(l < 2.5) }' && break
  sleep 5
done

# 開機時卡死的系統程式（Quickstep、System UI 等）會一直跳 ANR 對話框，按 Wait 5 秒後又會再跳，
# 對話框在的時候 UiAutomator 也讀不到 App 畫面。錄影前直接重新啟動它們（系統會自動重開）。
anr_pkgs=$(adb shell dumpsys window windows | grep -o 'Application Not Responding: [A-Za-z0-9_.]*' | sed 's/.*: //' | sort -u)
for pkg in $anr_pkgs; do
  echo "重新啟動 ANR 的程式：$pkg"
  adb shell am force-stop "$pkg" || true
done
if [ -n "$anr_pkgs" ]; then
  sleep 5
fi

# 只有真機才測得到的事：Android 9 內建的 SQLite 跑不跑得動 WorkStore 的 SQL。不錄影
step "DeviceTest"
timeout 300 adb shell am instrument -w -e class "$DEVICE_TEST_CLASS" \
  "$APP_ID.test/androidx.test.runner.AndroidJUnitRunner" | tee "$OUT/device-test.txt"

adb logcat -c || true

# pixel_7_pro 是 1440x3120，直接錄可能超過模擬器編碼器上限，所以錄一半尺寸
# screenrecord 單次最長 180 秒
step "DemoRecordingTest（錄影）"
ADB_TIMEOUT=200 adb shell screenrecord --size 720x1560 --bit-rate 8000000 --time-limit 180 /sdcard/demo.mp4 &
REC_PID=$!
sleep 2

timeout 600 adb shell am instrument -w -e class "$TEST_CLASS" \
  "$APP_ID.test/androidx.test.runner.AndroidJUnitRunner" | tee "$OUT/instrument.txt"

sleep 1
adb shell am force-stop "$APP_ID" || true
adb shell 'pkill -INT screenrecord 2>/dev/null || pkill -l 2 screenrecord' || true
wait "$REC_PID" || true
# 等 screenrecord 把檔案寫完
sleep 2

step "收回錄影"
adb pull /sdcard/demo.mp4 "$OUT/demo.raw.mp4" || true
# DemoRecordingTest 在關鍵畫面存的截圖
adb pull "/sdcard/Android/data/$APP_ID/files/screenshots" "$OUT/" || true
adb logcat -d > "$OUT/logcat.txt" || true

# 給 release 版冒煙測試用：沒有 UiAutomator 測試 APK 可用，靠 uiautomator dump 找元件
# 剛跑完 UiAutomator 測試時，前一個 UiAutomation 連線可能還沒放掉，dump 會回 null root node：隔兩秒再試
ui_dump() {
  local out
  for _ in 1 2 3 4 5; do
    out=$(adb shell uiautomator dump /sdcard/ui.xml 2>&1)
    case "$out" in
      *dumped*) adb shell cat /sdcard/ui.xml; return 0 ;;
    esac
    echo "uiautomator dump：$out" >&2
    sleep 2
  done
  return 1
}

status=0
# 測試失敗時 am instrument 的 exit code 仍是 0，要看輸出判斷
if ! grep -q '^OK (' "$OUT/device-test.txt"; then
  echo "DeviceTest 沒有通過，詳見 $OUT/device-test.txt"
  status=1
fi
if ! grep -q '^OK (' "$OUT/instrument.txt"; then
  echo "DemoRecordingTest 沒有通過，詳見 $OUT/instrument.txt 與 $OUT/logcat.txt"
  status=1
fi

# release 版冒煙測試：R8 壓縮過的程式在 debug 版測不到，覆蓋安裝後打開 App，確認沒有閃退、首頁的分頁列畫得出來。
# 兩個版本都用 debug 金鑰簽，可以直接覆蓋。先清掉 App 資料，從第一次打開的樣子開始。
# 之後每做一個會用到函式庫的功能（讀照片、存 JSON、畫成品圖），就在這裡真的走一次
RELEASE_APK=app/build/outputs/apk/release/app-release.apk
if [ -f "$RELEASE_APK" ]; then
  mkdir -p "$OUT/screenshots"
  step "release 冒煙測試"
  adb shell pm clear "$APP_ID" || true
  ADB_TIMEOUT=300 adb install -r "$RELEASE_APK" || exit 1
  adb logcat -c || true
  adb shell am start -W -n "$APP_ID/.MainActivity" | tee "$OUT/release-smoke.txt"
  sleep 3
  adb exec-out screencap -p > "$OUT/screenshots/release-1-launch.png"

  launched=0
  if home=$(ui_dump) && printf '%s' "$home" | grep -q 'resource-id="tab_blank"'; then
    launched=1
  fi

  adb logcat -d > "$OUT/logcat-release.txt" || true
  if grep -q 'FATAL EXCEPTION' "$OUT/logcat-release.txt"; then
    echo "release 版閃退，詳見 $OUT/logcat-release.txt"
    status=1
  fi
  if [ "$launched" -ne 1 ]; then
    echo "release 版打開後沒看到首頁的分頁列，詳見 $OUT/screenshots/release-1-launch.png 與 $OUT/logcat-release.txt"
    status=1
  fi
fi

exit $status
