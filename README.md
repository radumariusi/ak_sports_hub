# AK Sports Hub

Aplicație IPTV pentru Android TV, pentru uz personal: Live TV din mai multe playlisturi, cu EPG, și un **Sports Hub** care găsește meciurile live, de azi și de mâine și le leagă de canalele din playlisturile tale.

- Planul complet și etapele: [docs/PLAN.md](docs/PLAN.md)
- Stare: toate etapele (1–9) sunt gata. Etapa 9 a adus canalele favorite, calendarul echipelor favorite, notificările dinaintea meciurilor favorite, rândul „Meciurile tale de azi” de pe ecranul principal Android TV, catch-up pe serverele Xtream și backup / restaurare.

## Instalare pe TV

După fiecare modificare ajunsă pe `main`, APK-ul se construiește automat și înlocuiește fișierul din release-ul unic `ultima-versiune`. Linkul rămâne mereu același, bun pentru aplicația Downloader:

`https://github.com/radumariusi/ak_sports_hub/releases/download/ultima-versiune/ak-sports-hub.apk`

Repo-ul fiind privat, linkul cere autentificare. Pe TV e mai simplu să descarci APK-ul pe telefon sau PC și să-l trimiți pe TV (de exemplu cu „Send files to TV”), sau să faci repo-ul public.

## Zi de zi

- **Favorite**: canalele (OK ținut apăsat pe canal → „Adaugă la favorite”; apar sus în Live TV), echipele și competițiile (din fereastra meciului). Tabul Favorite din Sports Hub arată calendarul fiecărei echipe favorite (următoarele 5 meciuri).
- **Notificări**: cu 10 minute (setare: 5, 10, 15, 30) înainte de meciurile favorite; „Pornește” deschide direct sursa recomandată. Pe Android 13+ aplicația cere o dată permisiunea.
- **Ecranul principal Android TV** (Android 8+): rândul „Meciurile tale de azi”. Pe Google TV poate fi nevoie să-l activezi din „Personalizează canalele”.
- **Catch-up** (canale Xtream cu arhivă): în programul canalului, OK pe o emisiune începută o pornește de la început; în fereastra unui meci început, „⏮ De la început”.
- **Backup / restaurare** (Setări → Backup și restaurare): setările, favoritele, alias-urile, regulile și corecțiile într-un fișier JSON; playlisturile (cu parolele) doar dacă alegi asta.

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

`./gradlew :core:common:test :core:playlist:test :core:epg:test :core:sports:test :app:assembleDebug` (JDK 17+, Android SDK 36).

Capturile de ecran (date inventate) se fac cu `./gradlew :app:testDebugUnitTest --tests 'ro.ak.sportshub.capturi.*'` (workflow-ul „Capturi”, la cerere); imaginile ajung în `app/build/outputs/roborazzi/` și nu intră în repo.
