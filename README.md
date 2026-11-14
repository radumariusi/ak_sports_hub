# AK Sports Hub

Aplicație IPTV pentru Android TV, pentru uz personal: Live TV din mai multe playlisturi, cu EPG, și un **Sports Hub** care găsește meciurile live, de azi și de mâine și le leagă de canalele din playlisturile tale.

- Planul complet și etapele: [docs/PLAN.md](docs/PLAN.md)
- Stare: etapa 3 (playerul: ExoPlayer, zapping, piste audio și subtitrări, reconectare). Înainte: etapa 2 (playlisturi M3U și Xtream).

## Instalare pe TV

După fiecare modificare ajunsă pe `main`, APK-ul se construiește automat și înlocuiește fișierul din release-ul unic `ultima-versiune`. Linkul rămâne mereu același, bun pentru aplicația Downloader:

`https://github.com/radumariusi/ak_sports_hub/releases/download/ultima-versiune/ak-sports-hub.apk`

Repo-ul fiind privat, linkul cere autentificare. Pe TV e mai simplu să descarci APK-ul pe telefon sau PC și să-l trimiți pe TV (de exemplu cu „Send files to TV”), sau să faci repo-ul public.

## Telecomanda în player

| Tasta | Ce face |
|---|---|
| ▲ / CH+ | canalul următor din grup |
| ▼ / CH− | canalul anterior din grup |
| ◀ | lista canalelor din grup |
| ▶ / Meniu | pistă audio, subtitrări, canalul anterior, reconectare |
| OK | arată sau ascunde informațiile (după o eroare: încearcă din nou) |
| cifre | numărul canalului (dacă playlistul nu are numere: poziția în grup) |
| „Last” / ⏮ | comută între ultimele două canale |
| Back | închide panoul deschis sau iese din player |

## Cheia de semnare

Ca o versiune nouă să se instaleze peste cea veche, APK-ul trebuie semnat mereu cu aceeași cheie. Cheia stă doar în Settings → Secrets and variables → Actions, în patru secrete: `AK_KEYSTORE_BASE64` (fișierul `.jks` codat base64), `AK_KEYSTORE_PASSWORD`, `AK_KEY_ALIAS`, `AK_KEY_PASSWORD`. Fără ele build-ul merge, dar semnează cu o cheie temporară și fiecare versiune trebuie instalată după dezinstalarea celei vechi.

## Build local

`./gradlew :core:common:test :core:playlist:test :app:assembleDebug` (JDK 17+, Android SDK 36).
