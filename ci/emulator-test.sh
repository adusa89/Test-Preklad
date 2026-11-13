#!/bin/bash
# Automatická zkouška v emulátoru Android TV: instalace, průvodce, všechny obrazovky,
# spuštění překladu nad ukázkovou „hrou“ a kontrola, že se titulky přeložily.
set -x
OUT=test-results
mkdir -p $OUT
PKG=cz.testpreklad
shot() { adb exec-out screencap -p > "$OUT/$1.png"; }
key() { adb shell input keyevent "$1"; sleep 1; }

adb install -r app/build/outputs/apk/debug/app-debug.apk || { echo "INSTALL FAILED" > $OUT/result.txt; exit 1; }
adb shell getprop ro.build.version.release > $OUT/device.txt
adb shell getprop ro.product.model >> $OUT/device.txt
adb shell wm size >> $OUT/device.txt
adb shell cat /proc/meminfo | head -1 >> $OUT/device.txt
adb logcat -c

# 1) první spuštění: průvodce krok za krokem
adb shell am start -n $PKG/.MainActivity
sleep 10; shot 01_pruvodce_vitej
key 23; sleep 2; shot 02_pruvodce_povoleni
key 23; sleep 2; shot 03_pruvodce_jazyk
sleep 45; shot 03b_pruvodce_jazyk_po_stazeni
key 23; sleep 2; shot 04_pruvodce_pripojeni
key 23; sleep 2; shot 05_pruvodce_hotovo
# přeskočit průvodce (vpravo, vpravo = „Dokončit“) → hlavní obrazovka
key 22; key 22; key 23; sleep 3; shot 06_domu

# 2) všechny stránky menu
for i in 1 2 3 4 5 6 7; do key 20; key 23; sleep 2; shot "07_menu_$i"; done

# 3) povolení a spuštění překladu (povolení snímání udělí test místo člověka)
adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow
adb shell appops set $PKG PROJECT_MEDIA allow
adb shell am force-stop $PKG
adb shell am start -n $PKG/.MainActivity --ez autostart true
sleep 25; shot 08_po_spusteni
# kdyby se přesto ukázal dotaz na snímání, potvrď ho
key 22; key 23; sleep 5; shot 08b_po_potvrzeni

# 4) ukázková hra s titulky – aplikace je má přečíst, přeložit a zakrýt pruhem
adb shell am start -n $PKG/.TestCardActivity
for i in 1 2 3 4 5 6 7 8 9 10 11 12; do sleep 4; shot "09_hra_$i"; done

adb logcat -d -v time > $OUT/logcat_full.txt
grep -E "TP_TEST|TestPreklad|AndroidRuntime|FATAL" $OUT/logcat_full.txt > $OUT/log.txt
SHOWS=$(grep -c "TP_TEST.*SHOW|" $OUT/log.txt)
CRASH=$(grep -c "FATAL EXCEPTION" $OUT/log.txt)
echo "prelozenych_titulku=$SHOWS pady=$CRASH" > $OUT/result.txt
grep "TP_TEST" $OUT/log.txt >> $OUT/result.txt
tail -c 400000 $OUT/logcat_full.txt > $OUT/logcat_tail.txt; rm $OUT/logcat_full.txt
cat $OUT/result.txt
exit 0
