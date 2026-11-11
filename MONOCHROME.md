# Prism Mono – teljesen monochrome téma

A funkciók érintetlenek, csak a színek lettek szürkeárnyalatosak.

## Kapcsoló
`app/src/main/java/com/prism/music/ui/theme/Mono.kt`

- `Mono.ENABLED` – `false`-ra állítva az eredeti, színes Prism tér vissza.
- `Mono.GREY_PICTURES` – `true`: a borítók, előadófotók és minden kép is szürke (a teljes UI egy telítettség-0 szűrőn megy át).
  `false`: csak a felület szürke, a képek színesek maradnak.

## Mi változott
- **Beállítások kényszerítve** (`AppSettings.monochrome()`): semleges (fehér/tinta) kiemelőszín, „White light" a spektrum helyett,
  szürke felületek, nincs szín a borítóból és a háttérképből. Beolvasáskor és a `PrismTheme`-ben is érvényesül, így mentett
  szín, Look vagy backup sem hozhatja vissza a színeket.
- **Borítóból jövő színek** (Palette: téma, Frosted oldalak, lejátszó háttér, widgetek, TV) a forrásnál szürkére váltva.
- **Szó szerinti színek**: ~120 Kotlin- és XML-fájlban minden hex szín azonos világosságú szürkére cserélve
  (launcher ikonok, widgetek, hangulat/mix csempék, Avatarok, évszakos dizájnok, bakelit/kazetta stb.).
- **TV-távirányító weboldal** (`assets/remote`) és a TV-banner PNG szürkeárnyalatos.
- A sheet-ek (`PrismSheet`) külön ablakban futnak, ezért ott külön kapják a szürkítést.

## Megjegyezendő
- A Beállítások → Megjelenés → Szín oldal még látszik, de a színválasztás nincs hatással (a monochrome kényszer felülírja).
- Ez a módosítás nincs lefordítva/tesztelve (nincs Android SDK a környezetben). Az `app/src/test` alatti, konkrét színértéket
  ellenőrző tesztek (pl. `ColorTest`) a megváltozott konstansok miatt frissítést kérhetnek.
