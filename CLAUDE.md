# AK Sports Hub

Aplicație IPTV pentru Android TV (uz personal), cu Sports Hub. Planul și etapele: [docs/PLAN.md](docs/PLAN.md).

## Reguli de lucru

- Utilizatorul scrie în română; răspunsurile, documentația și mesajele din UI sunt în română.
- Munca e împărțită pe etapele din `docs/PLAN.md`; fiecare etapă are firul ei și un PR propriu. Nu începe etapa următoare fără aprobare.
- Repo-ul trebuie să rămână mic: nimic generat în git (APK, build, cache), un singur release (`ultima-versiune`) înlocuit la fiecare build, artefacte cu `retention-days` mic, rulările vechi șterse de workflow-ul `Curățenie`.
- Playlisturile, EPG-urile și orice date de acces ale utilizatorului nu intră niciodată în repo, în teste sau în loguri.
- Workflow-urile noi: `concurrency` cu `cancel-in-progress`, `paths-ignore` pentru `docs/**` și `*.md`, `timeout-minutes`.

## Structură și reguli tehnice

- `app/` (Android TV, Compose + tv-material), `core/model`, `core/common` și `core/playlist` (Kotlin pur, testabile fără Android), `core/database` (Room + FTS4). Build: `./gradlew :core:common:test :core:playlist:test :app:assembleRelease`.
- Playlisturi (`core/playlist`): M3U parsat în flux, doar live (`VodFilter`), Xtream prin `player_api.php` (`get_live_categories`, `get_live_streams`, cereri secvențiale). Descărcarea ține permisul din `ConnectionGate` doar cât durează, apoi parsarea se face din fișier. Un răspuns fără canale live nu șterge canalele vechi.
- În UI, câmpurile de text se editează doar într-o fereastră separată (`TextInputDialog`), ca săgețile telecomenzii să nu intre în câmpuri. Fluxurile Room din composable-uri se țin în `remember`.
- Fără verificarea stream-urilor: calitatea și limba vin doar din metadatele playlistului (nume, grup, atribute).
- Numele normalizat (`ChannelNameParser.groupKey`) e doar cheie de grupare; în UI apar numele original și eticheta de calitate.
- Furnizorii permit o singură conexiune pe playlist: orice acces la rețea al unui playlist trece prin `ConnectionGate`; nimic în fundal cât rulează playerul pe același playlist.
- Playlisturi: M3U și Xtream Codes (`PlaylistSource`).
- Preferințe de surse (`SourcePreferences`, `SourceRanking`): nicio sursă nu e ascunsă, se schimbă doar ordinea. Favorite: EN/RO 4K → EN/RO FHD → altă limbă 4K → altă limbă FHD → EN/RO HD/SD → restul. Alte meciuri: după rezoluție, în orice limbă. Limbile și pragul (FHD) sunt setări, nu constante.
- Ecranul unui meci e o fereastră de previzualizare cu taburile Surse / Pre-meci / Live; playerul pornește abia la alegerea unei surse.
- Semnare: secretele `AK_KEYSTORE_BASE64`, `AK_KEYSTORE_PASSWORD`, `AK_KEY_ALIAS`, `AK_KEY_PASSWORD`; fără ele CI semnează cu cheia de debug.
