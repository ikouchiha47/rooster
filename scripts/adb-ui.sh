#!/usr/bin/env bash
# Drive the Personal Radar UI through uiautomator text/bounds, not fixed taps.
set -euo pipefail

SERIAL="${ANDROID_SERIAL:-$(adb devices | awk 'NR==2 && $2=="device" {print $1; exit}')}"
if [[ -z "${SERIAL:-}" ]]; then
  echo "No adb device. Set ANDROID_SERIAL or connect one." >&2
  exit 1
fi

adb_() { adb -s "$SERIAL" "$@"; }

dump() {
  local out=""
  for _ in 1 2 3 4 5; do
    adb_ shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true
    out="$(adb_ exec-out cat /sdcard/ui.xml 2>/dev/null || true)"
    [[ "$out" == *"<hierarchy"* ]] && { printf '%s' "$out"; return 0; }
    sleep 1
  done
  echo "uiautomator produced no XML" >&2
  return 1
}

tap_text() {
  local wanted="$1"
  local index="${2:-0}"
  local xml
  xml="$(dump)"
  local point
  printf '%s' "$xml" > /tmp/personalos-ui.xml
  point="$(python3 - "$wanted" "$index" /tmp/personalos-ui.xml <<'PY'
import re, sys
import xml.etree.ElementTree as ET
wanted, index, path = sys.argv[1], int(sys.argv[2]), sys.argv[3]
root = ET.parse(path).getroot()
hits = []
for node in root.iter('node'):
    if node.get('text') == wanted or node.get('content-desc') == wanted:
        m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.get('bounds', ''))
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            hits.append(((x1 + x2) // 2, (y1 + y2) // 2))
if index >= len(hits):
    raise SystemExit(f'No node with text={wanted!r} at index {index}; found {len(hits)}')
print(*hits[index])
PY
)"
  adb_ shell input tap $point
}

open_app() {
  adb_ shell am force-stop com.personalos.app
  adb_ shell am start -W -n com.personalos.app/.MainActivity >/dev/null
  for _ in 1 2 3 4 5 6; do
    if dump 2>/dev/null | grep -q 'Personal Radar'; then return 0; fi
    sleep 1
  done
  return 0
}

home() {
  open_app
  tap_text HOME
  sleep 2
}

climate() {
  home
  tap_text CLIMATE
  sleep 3
  tap_text Regional
  sleep 3
}

climate_fires() {
  climate
  tap_text FIRES
  sleep 4
}

weather() {
  home
  tap_text WEATHER
  sleep 3
  tap_text Regional
  sleep 3
}

shot() {
  adb_ exec-out screencap -p > "${1:-/tmp/personalos.png}"
}

case "${1:-}" in
  dump) dump ;;
  tap) tap_text "${2:?text}" "${3:-0}" ;;
  home) home ;;
  climate) climate ;;
  fires) climate_fires ;;
  weather) weather ;;
  shot) shot "${2:-/tmp/personalos.png}" ;;
  *) echo "usage: $0 {dump|tap <text> [index]|home|climate|fires|weather|shot [path]}" >&2; exit 2 ;;
esac
