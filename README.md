# Test Překlad

**Nejnovější APK ke stažení:** https://github.com/adusa89/Test-Preklad/releases/download/latest/TestPreklad.apk  
Číslo verze je vidět v aplikaci v sekci Nápověda.

Aplikace pro Android TV: sama se po domácí síti připojí k PlayStationu 5, přečte anglické
titulky hry a v reálném čase je ukáže přeložené (čeština, slovenština a dalších 7 jazyků)
v černém pruhu přes obraz. Hra jde do televize dál kabelem HDMI. Překlad je offline a zdarma.

- `app/src/main/java/cz/testpreklad/core` – logika bez Androidu (čtení jen titulků, stabilizace, oblasti, slovník, paměť překladů, texty), pokrytá testy
- `app/src/main/java/cz/testpreklad` – Android část (spojení s PlayStationem, dekódování videa, ML Kit OCR, offline překlad, překryv, obrazovky)
- `app/src/main/java/com/metallic/chiaki/lib`, `app/src/main/cpp`, `third_party/chiaki-ng` – spojení s PlayStationem (knihovna chiaki-ng, AGPL-3.0; naše úpravy jsou popsané v hlavičkách souborů)
- `app/src/test` – unit testy včetně simulace hraní
- `.github/workflows/build.yml`, `ci/emulator-test.sh` – sestavení APK a automatická zkouška v emulátoru Android TV (Android 9, 11, 13, 14); APK se vydá až po úspěšné zkoušce

Návod: NAVOD.md

Aplikace není produktem společnosti Sony Interactive Entertainment. „PlayStation“ a „PS5“ jsou jejich ochranné známky.
