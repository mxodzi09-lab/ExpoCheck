# ExpoCheck v0.4.1

Poprawka błędu parsera strony Komfortu.

W v0.4 parser mógł uznać cenę „Oszczędzasz” za „najniższą cenę z 30 dni”,
ponieważ wybierał kwotę po samej odległości. Teraz najpierw wybiera cenę
występującą po właściwym opisie:

- Bez montażu
- Przy zakupie montażu
- Oszczędzasz
- Najniższa cena z 30 dni

Pozostałe funkcje v0.4 pozostają bez zmian:
automatyczne wykrycie numeru produktu, pobranie cen ze strony i porównanie
wszystkich cen widocznych na cenówce.
