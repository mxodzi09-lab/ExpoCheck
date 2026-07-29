# ExpoCheck v0.5.0 — Minimalistyczny Premium

Wybrany wariant GUI nr 3 został wdrożony jako prawdziwy interfejs aplikacji:

- biały, uporządkowany ekran,
- ciemny granat i subtelny złoty akcent,
- prowadzenie krokami: Produkt → Ceny → Zapis,
- duże, czytelne wartości online i z cenówki,
- spokojne karty i mniej zbędnego tekstu,
- przeprojektowane ekrany startu, skanera, podsumowania i historii.

## Naprawiony błąd parsera

Cena rozbita, np. `149 26 zł/m²`, jest zapisywana wyłącznie jako
`149,26 zł/m²`. Fragment `26 zł/m²` nie może już stać się oddzielną ceną.

Workflow buduje artefakt `ExpoCheck-v0.5.0-APK`.
