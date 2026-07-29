# ExpoCheck v0.4.2

Poprawka skanowania cenówek na żywo.

## Co naprawiono

- aplikacja nie zbiera już każdej przypadkowej liczby z kolejnych klatek,
- cena musi zostać odczytana stabilnie w co najmniej dwóch klatkach,
- po pobraniu strony pokazywane są tylko ceny występujące na Komfort.pl,
- rozbite ceny typu `338` + `67` są łączone tylko wtedy, gdy leżą obok siebie i mają własną jednostkę,
- pełna cena `529 zł/szt.` jest odczytywana jako `529,00 zł/szt.`,
- numer produktu musi zostać potwierdzony w trzech klatkach,
- QR może dostarczyć prawidłowy numer katalogowy,
- porównywana jest także najniższa cena z 30 dni, jeśli występuje na stronie.

Dla cenówki produktu 100344378 oczekiwane wartości to `338,67 zł/szt.` oraz `529,00 zł/szt.`.
