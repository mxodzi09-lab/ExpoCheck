# ExpoCheck v0.5.3 — naprawiony build

Naprawiono błąd kompilacji w PriceParser.kt:

`gap in 0.0..maxGap`

zostało zmienione na:

`gap.toDouble() in 0.0..maxGap`

Poprzednia wersja próbowała sprawdzić wartość Int w zakresie Double,
co zatrzymywało GitHub Actions przed zbudowaniem APK.

Tryb aplikacji pozostaje bez zmian:
- brak otwierania strony Komfortu,
- skanowanie wyłącznie największych cyfr ceny,
- łączenie dużych złotych z mniejszymi groszami,
- ignorowanie dat, kodów EAN, wymiarów i małego tekstu,
- mały panel na dole ekranu.

Artefakt: `ExpoCheck-v0.5.3-APK`.
