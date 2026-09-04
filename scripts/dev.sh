#!/usr/bin/env bash
# One command for a full test cycle on the Ayaneo, because doing these by hand
# kept producing false "it's broken" readings: reinstalling kills the app
# process (so BottomPanelService is gone until something restarts it) and can
# drop the accessibility service, which looks exactly like a bug in the panel.
#
#   scripts/dev.sh              build, install, re-arm everything, relaunch
#   scripts/dev.sh log          follow our own log lines only
#   scripts/dev.sh log-all      follow everything except known vendor spam
#   scripts/dev.sh shot         grab both screens into ./shots/
#   scripts/dev.sh state        one-shot summary: IME, service, window sizes
#
# Set POCKETDS_DEVICE to pin a device, otherwise the sole attached one is used.
set -euo pipefail

ADB="${ANDROID_HOME:-/c/Users/$USER/AppData/Local/Android/Sdk}/platform-tools/adb.exe"
PKG=com.pocketds.kbm
IME="$PKG/.ime.OverlayInputMethodService"
A11Y="$PKG/.accessibility.CursorAccessibilityService"
# The vendor's screencap wants the display's uniqueId, not the 0/2 index the
# rest of the framework uses. Top screen is the default, so it needs no flag.
BOTTOM_DISPLAY_ID=4630946708815090308
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# The wireless-debugging port is reassigned every time the service restarts, so
# never hardcode it: mDNS discovery finds the device on its own as long as
# Wireless debugging is on and we've been paired once (pairing is permanent).
resolve_device() {
  if [[ -n "${POCKETDS_DEVICE:-}" ]]; then echo "$POCKETDS_DEVICE"; return; fi
  local found
  found="$("$ADB" devices | awk '/_adb-tls-connect|^emulator|:[0-9]+\tdevice/ {print $1}' | head -1)"
  if [[ -z "$found" ]]; then
    "$ADB" mdns services 2>/dev/null | awk '/_adb-tls-connect/ {print $1}' | head -1 \
      | while read -r name; do "$ADB" connect "$name" >/dev/null 2>&1 || true; done
    found="$("$ADB" devices | awk '/_adb-tls-connect|:[0-9]+\tdevice/ {print $1}' | head -1)"
  fi
  if [[ -z "$found" ]]; then
    echo "no device found. Turn on Settings > Developer options > Wireless debugging" >&2
    exit 1
  fi
  echo "$found"
}
DEVICE="$(resolve_device)"
adbx() { "$ADB" -s "$DEVICE" "$@"; }

# Ayaneo runs its own accessibility service (WindowKeyEventService) for hardware
# keys and their dual-screen gestures. This setting is a colon-separated list,
# so writing just ours to it silently switches theirs off — append instead.
arm_accessibility() {
  local current
  current="$(adbx shell settings get secure enabled_accessibility_services | tr -d '\r')"
  [[ "$current" == "null" ]] && current=""
  # The same service can be listed either fully-qualified
  # (pkg/pkg.path.Class) or shorthand (pkg/.path.Class), so match on the class
  # name rather than the exact string — comparing the whole thing appends a
  # duplicate entry when the system wrote it in the other form.
  if [[ "$current" == *"CursorAccessibilityService"* ]]; then
    return
  fi
  local updated
  if [[ -z "$current" ]]; then updated="$A11Y"; else updated="$current:$A11Y"; fi
  adbx shell settings put secure enabled_accessibility_services "$updated"
  adbx shell settings put secure accessibility_enabled 1
}

# Vendor firmware logs thermal sensors and display-compositor housekeeping many
# times a second, which buries anything useful within a couple of seconds.
NOISE='thermal|SDM *:|ANDR-PERF|libdisplayconfigqti|InputDispatcher|xsu|vendor.qti|CoreBackPreview|avc: denied'

case "${1:-deploy}" in
  deploy)
    echo "== build =="
    (cd "$ROOT" && ./gradlew.bat assembleDebug -q)
    echo "== install =="
    adbx install -r "$ROOT/app/build/outputs/apk/debug/app-debug.apk"
    echo "== re-arm IME + accessibility (an install can clear both) =="
    adbx shell ime enable "$IME" >/dev/null
    adbx shell ime set "$IME" >/dev/null
    arm_accessibility
    echo "== relaunch (restarts BottomPanelService, which the install killed) =="
    adbx shell am start -n "$PKG/.MainActivity" >/dev/null
    sleep 2
    "$0" state
    ;;

  log)
    adbx logcat -c
    echo "following PocketDS trace (ctrl-c to stop)"
    adbx logcat -s PocketDS:D
    ;;

  log-all)
    adbx logcat -c
    adbx logcat | grep -Ev "$NOISE"
    ;;

  shot)
    mkdir -p "$ROOT/shots"
    adbx exec-out screencap -p > "$ROOT/shots/top.png"
    adbx exec-out screencap -p -d "$BOTTOM_DISPLAY_ID" > "$ROOT/shots/bottom.png"
    echo "wrote shots/top.png and shots/bottom.png"
    ;;

  state)
    echo "selected IME : $(adbx shell settings get secure default_input_method)"
    echo "a11y enabled : $(adbx shell settings get secure enabled_accessibility_services)"
    if adbx shell dumpsys activity services | grep -q BottomPanelService; then
      echo "panel service: running"
    else
      echo "panel service: NOT RUNNING  <- panel can't appear until it is"
    fi
    echo "panel window : $(adbx shell dumpsys window windows \
      | grep -A6 'ty=PRESENTATION' | grep -m1 'Requested' || echo 'none (collapsed away or torn down)')"
    ;;

  *)
    echo "unknown command: $1" >&2
    exit 1
    ;;
esac
