# ExpoCheck v0.1

Pierwsza działająca wersja aplikacji do sprawdzania cen i ekspozycji produktów.

## Co działa w tej wersji

- wpisanie i zapamiętanie nicku pracownika,
- wbudowana przeglądarka Komfort.pl,
- automatyczny odczyt z otwartej strony:
  - nazwy,
  - numeru katalogowego,
  - ceny aktualnej,
  - ceny z montażem,
  - promocji,
  - najniższej ceny z 30 dni,
- skanowanie cenówki aparatem na żywo,
- OCR ceny i numeru produktu,
- skan EAN/UPC/Code 128,
- natychmiastowe porównanie ceny online z cenówką,
- statusy: Zrobione, Brak ceny, Zła cena, Do sprawdzenia, Brak produktu,
- notatka i zdjęcie ekspozycji,
- lokalna historia sprawdzonych produktów.

## Produkty testowe

W aplikacji są trzy produkty startowe sprawdzone 28.07.2026:

- 100630301 — deska tarasowa kompozytowa Natural,
- 100246460 — deska Barlinecka Dąb Salt,
- 100159819 — wykładzina dywanowa Sweet perłowy.

Dane startowe służą tylko do testu. Po otwarciu produktu aplikacja odczytuje aktualne dane bezpośrednio ze strony.

## Jak zbudować APK przez GitHub

1. Rozpakuj paczkę.
2. Zastąp pliki w repozytorium `KomfortFinder` zawartością folderu `ExpoCheck_v0_1` albo utwórz nowe repozytorium `ExpoCheck`.
3. Wykonaj commit i push.
4. Wejdź w GitHub → Actions → `Zbuduj ExpoCheck APK`.
5. Po zielonym wyniku pobierz artefakt `ExpoCheck-v0.1-APK`.
6. Rozpakuj i zainstaluj `app-debug.apk`.

## Aktualizacja istniejącego repozytorium przez Termux

Zakładając, że ZIP znajduje się w Pobranych:

```bash
termux-setup-storage
pkg install git unzip -y
cd ~/storage/downloads
unzip ExpoCheck_v0_1.zip
rm -rf ~/ExpoCheck
cp -r ExpoCheck_v0_1 ~/ExpoCheck
cd ~/ExpoCheck
git init -b main
git config user.name "Konrad"
git config user.email "TWOJ_EMAIL"
git add .
git commit -m "ExpoCheck v0.1"
gh repo create ExpoCheck --public --source=. --remote=origin --push
```

## Ważne ograniczenia v0.1

- Skanowanie strony działa w przeglądarce wbudowanej w aplikację. Pełne przechwytywanie zewnętrznego Chrome przez MediaProjection będzie dodane później.
- OCR może zobaczyć kilka kwot na cenówce. Przed zapisaniem zawsze wyświetla odczytaną kwotę do potwierdzenia.
- Układ strony Komfort.pl może się zmieniać; parser jest oparty na widocznym tekście, dlatego jest odporniejszy niż sztywne selektory, ale może wymagać aktualizacji.
- To prywatny, niezależny prototyp, a nie oficjalna aplikacja Komfortu.
