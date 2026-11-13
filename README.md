# Test Překlad (verze 0.5 – offline, HDMI v plné kvalitě)

Android TV aplikace: titulky ze hry přes PS Remote Play → čeština / slovenština v reálném čase, černý pruh přes anglické titulky.

- `app/src/main/java/cz/testpreklad/core` – logika bez Androidu (čtení jen titulků, stabilizace, nakukování pod pruh, oblasti, paměť, statistika, texty CZ/SK), pokrytá testy
- `app/src/main/java/cz/testpreklad` – Android část (snímání obrazovky, ML Kit OCR, offline překlad (ML Kit) s ochranou jmen a převodem slangu, překryv přes Usnadnění i bez něj, design)
- `app/src/test` – 88 unit testů včetně simulace hraní RDR2
- `.github/workflows/build.yml` – automatické testy a sestavení APK

Návod: NAVOD.md
