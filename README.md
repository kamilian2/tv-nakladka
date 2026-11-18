# TV Nakładka

Nakładka na Android TV sterowana z Home Assistanta: zegar z sekundami, kafelki z encjami,
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

## API (port 5002, POST z JSON-em)

| Adres | Co robi | Pola |
|---|---|---|
| `GET /` | stan aplikacji | – |
| `/tile` | kafelek pod zegarem | `id`, `text`, `icon` (`mdi:...` lub emoji), `color`, `background`, `size`, `expiration` (`30`, `"5m"`, `"1h"`), `visible:false` = usuń |
| `/tile/clear` | usuń kafelek | `id` (puste = wszystkie) |
| `/notify` | powiadomienie | `id`, `title`, `message`, `icon`, `corner`, `duration`, `color`, `background`, `size` |
| `/camera` | kamery obok siebie | `streams` (lista adresów lub `{url, label}`), `duration` (maks. 30 s), `width` (dp), `position` |
| `/camera/stop` | zamknij kamery | – |
| `/clock` | zegar | `visible`, `corner`, `format` (np. `"HH:mm:ss"`), `size`, `color`, `background`, `icon` |
| `/settings` | wszystko naraz | `{clock:{..}, tiles:{corner,size,color,background}, notify:{corner,duration,size,background}, camera:{max_seconds,width,gap,position}, margin}` |

Rogi: `top_start`, `top_center`, `top_end`, `bottom_start`, `bottom_center`, `bottom_end`.

Gotowy kod dla Home Assistanta jest w folderze `home-assistant/`.

Ikony: [Material Design Icons](https://pictogrammers.com/library/mdi/) (licencja Apache 2.0).
