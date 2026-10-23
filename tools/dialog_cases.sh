#!/usr/bin/env bash
# QA #2 q2-1 / q2-2 check (v0.1.2): S14 and S20 dialogs with the library in different spots.
#   tools/dialog_cases.sh NAME LEFT_DEG PITCH_DEG
# Levels the emulator camera, relaunches (the library parks 18 deg left / 26 below level), turns the camera
# LEFT_DEG left (negative = right) and to PITCH_DEG, opens S14 (more -> clear all) and S20 (blocked) and
# shoots each twice (panels lag 3-10 s): .scratch/shots/dlg-NAME-S14.png / -S20.png. emulator-5564 only.
set -u
cd "$(dirname "$0")/.."
NAME=$1; LEFT=$2; PITCH=$3
DEV=emulator-5564
EMU=tools/harness/emu.sh
log() { adb -s $DEV logcat -d -s FurnitureFit | grep -E "$1" | tail -${2:-1} | cut -c40-260; }
pitch_now() {
  tools/dev.sh cmd pose >/dev/null; sleep 1
  log POSE | grep -o 'fwd=([^)]*)' | python -I -c "import sys,math,re; v=[float(x) for x in re.findall(r'-?[0-9.]+', sys.stdin.read())]; print(round(math.degrees(math.asin(max(-1,min(1,v[1])))),1))"
}
# right-button drag: 0.044 deg / px; +y = look down, +x = turn right
turn() {  # DX_DEG DY_DEG (right +, down +); one axis per drag (a diagonal drag only turns one axis)
  local dx dy
  dx=$(python -I -c "print(round($1/0.044))"); dy=$(python -I -c "print(round($2/0.044))")
  if [ "$dx" != 0 ]; then $EMU drag $((1080 - dx / 2)) 1079 $((1080 + dx - dx / 2)) 1079 -Button right >/dev/null; sleep 1; fi
  if [ "$dy" != 0 ]; then $EMU drag 1080 $((1079 - dy / 2)) 1080 $((1079 + dy - dy / 2)) -Button right >/dev/null; sleep 1; fi
}
yaw_now() {
  tools/dev.sh cmd pose >/dev/null; sleep 1
  log POSE | grep -o 'fwd=([^)]*)' | python -I -c "import sys,math,re; v=[float(x) for x in re.findall(r'-?[0-9.]+', sys.stdin.read())]; print(round(math.degrees(math.atan2(v[0], v[2])),1))"
}
p=$(pitch_now); turn 0 "$p"; sleep 1
echo "level: pitch $(pitch_now)"
tools/dev.sh relaunch >/dev/null 2>&1 || { adb -s $DEV shell am force-stop io.embodit.furniturefit; tools/dev.sh launch >/dev/null; }
sleep 16
tools/dev.sh cmd dump >/dev/null; sleep 1
if log DUMP | grep -q '"pieces":\[\]'; then tools/dev.sh cmd place sofa3 >/dev/null; sleep 3; fi
turn "$(python -I -c "print(-($LEFT))")" "$(python -I -c "print(-($PITCH))")"; sleep 2
echo "case $NAME: left $LEFT, pitch now $(pitch_now), head yaw $(yaw_now) (180 = start)"
tools/dev.sh cmd ui more >/dev/null; sleep 2; tools/dev.sh cmd ui menu_clear >/dev/null; sleep 12
tools/cap.sh dlg-$NAME-S14-a >/dev/null; sleep 3; tools/cap.sh dlg-$NAME-S14 >/dev/null
log "DIALOG" 1
tools/dev.sh cmd ui dialog_cancel >/dev/null; sleep 4
tools/dev.sh cmd blocked on >/dev/null; sleep 12
tools/cap.sh dlg-$NAME-S20-a >/dev/null; sleep 3; tools/cap.sh dlg-$NAME-S20 >/dev/null
log "DIALOG" 1
tools/dev.sh cmd blocked off >/dev/null; sleep 3
log "DIALOG library back" 1
