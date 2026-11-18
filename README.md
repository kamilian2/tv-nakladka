# TV Nakładka

Nakładka na Android TV sterowana z Home Assistanta: zegar z sekundami, 3 pola na encje w dowolnym miejscu ekranu,
powiadomienia i do kilku kamer RTSP naraz w osobnych okienkach.

## Instalacja

1. Pobierz `tv-nakladka.apk` z zakładki **Releases → latest**.
2. Zainstaluj i nadaj uprawnienie (z folderu platform-tools):
   ```
   adb connect 192.168.1.114:5555
   adb install -r tv-nakladka.apk
   adb shell appops set pl.kamil.tvnakladka SYSTEM_ALERT_WINDOW allow
   adb shell dumpsys deviceidle whitelist +pl.kamil.tvnakladka
   adb shell am start -n pl.kamil.tvnakladka/.MainActivity
   ```
3. Kolejne wersje: tylko `adb install -r tv-nakladka.apk` (ustawienia zostają).

## Ustawienia

Wszystko można zmienić na trzy sposoby:
- **na telewizorze** – otwórz aplikację TV Nakładka (zmiany widać od razu),
- **w przeglądarce** – http://IP_TELEWIZORA:5002,
- **z Home Assistanta** – `POST /settings`.

## API (port 5002, POST z JSON-em)

| Adres | Co robi | Pola |
|---|---|---|
| `GET /` | strona ustawień | – |
| `GET /status`, `GET /config` | stan / ustawienia | – |
| `/settings` | zmiana ustawień | `{"clock":{..}, "e1":{..}, "e2":{..}, "e3":{..}, "camera":{..}, "notify":{..}, "general":{"margin"}}` |
| `/settings/reset` | ustawienia domyślne | `section` (puste = wszystko) |
| `/entity` | treść pola 1–3 | `slot`, `text`, `icon` (`mdi:...` lub emoji), `color`, `expiration` (`30`, `"5m"`), `visible:false` = wyczyść |
| `/entity/clear` | wyczyść pole | `slot` (puste = wszystkie) |
| `/notify` | powiadomienie | `title`, `message`, `icon`, `corner`, `duration`, `color`, `background`, `size` |
| `/camera` | kamery na dole | `streams` (lista adresów lub `{url, label}`), `duration` |
| `/camera/stop` | zamknij kamery | – |
| `/edit` | tryb edycji (widać puste pola) | `on`, `seconds` |

Ustawienia elementu (`clock`, `e1`–`e3`): `visible`, `x`, `y` (0–100 %), `size`, `font`, `bold`, `color`, `background` (`#AARRGGBB`), `format` (tylko zegar).
Czcionki: `sans-serif`, `sans-serif-medium`, `sans-serif-black`, `sans-serif-light`, `sans-serif-thin`, `sans-serif-condensed`, `serif`, `monospace`, `casual`, `cursive`, `asset:dseg7`, `asset:dseg14`.
Kamery (`camera`): `width`, `gap`, `align` (`start`/`center`/`end`), `bottom`, `max_seconds`.

Gotowy kod dla Home Assistanta jest w folderze `home-assistant/`.

Ikony: [Material Design Icons](https://pictogrammers.com/library/mdi/) (Apache 2.0). Czcionki cyfrowe: DSEG (SIL OFL 1.1).
