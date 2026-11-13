# Test Překlad – stručný návod

Kompletní manuál (s obrázky tabulek, řešením problémů a slovenskou částí) dostaneš jako samostatný dokument.
Tady je zkrácená verze.

## 1. APK
1. Založ si účet na github.com → **New repository** → nahraj obsah této složky (včetně `.github`).
2. Záložka **Actions** → počkej na zelenou fajfku (cca 5 min) → **Artifacts** → `TestPreklad-APK`.
3. Hotové APK má asi 25–35 MB.

## 2. Instalace
Do TV nainstaluj **Send Files to TV**, pošli APK, otevři ho a povol *Instalovat neznámé aplikace*.

## 3. První nastavení
- PlayStation: *Nastavení → Systém → Remote Play* zapnout.
- TV: nainstaluj **PS Remote Play** (Android TV 12+), přihlas se, spáruj ovladač.
- Test Překlad → **Nastavení → Zobrazení přes Usnadnění** → v TV zapni *Test Překlad* (funguje i na Chromecastu, bez počítače).

## 4. Hraní
1. Otevři PS Remote Play a připoj se ke konzoli.
2. Test Překlad → **Spustit překlad** → sdílení: *Jedna aplikace → PS Remote Play* (Android 14+, bez probliknutí), jinak *Celá obrazovka*.
3. Ve hře zapni titulky. Poprvé si v **Oblasti titulků** nastav modrý rámeček na titulky.

## 5. Překlad
Vždy offline a zdarma, přímo v televizi. Internet je potřeba jen jednou na stažení jazykového modelu (cca 30 MB).
Jména postav a míst se nepřekládají, westernový slang se před překladem převede do spisovné angličtiny.

## 6. Problémy
Zapni **Nastavení → Diagnostický režim** – ukáže, co aplikace čte, a případné chyby.

Záložní povolení přes ADB: `adb shell appops set cz.testpreklad SYSTEM_ALERT_WINDOW allow`

## 7. Co nejmenší zpoždění
- Překlad naskočí zhruba za 0,2–0,4 s (aplikace kontroluje obraz 10× za sekundu a překládá hned po prvním přečtení).
- PlayStation i TV připoj **kabelem**; jinak Wi-Fi 5 GHz.
- PS Remote Play → Kvalita videa: snímková frekvence **Vysoká**, rozlišení **1080p** (při sekání 720p, níž ne).

## 8. Plná kvalita přes HDMI (televize s Androidem)
Nastavení → Zdroj obrazu:
- **HDMI kabel** – PS5 zapojené jako obvykle. Domů → „Televize čte obraz z HDMI“ → Vyzkoušet. Test řekne, jestli to TV dovolí.
- **USB klíčenka** – když HDMI test neprojde: PS5 → HDMI splitter (4K HDR, downscaler) → výstup 1 do TV, výstup 2 do USB capture klíčenky → USB port TV. Na PS5 vypni HDCP.
- **PS Remote Play** – Android boxy a Chromecast (nižší kvalita obrazu).
