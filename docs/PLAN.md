# AK Sports Hub: planul aplicației

Etapa 0 (analiză și plan). Documentul acesta e sursa de adevăr pentru ce construim și în ce ordine. Fiecare etapă de mai jos se face într-un fir separat și se încheie cu un PR.

## 1. Ce construim

O aplicație IPTV pentru Android TV, pentru uz personal, cu două părți:

1. **Live TV**: doar canalele live din unul sau mai multe playlisturi (M3U sau Xtream Codes), cu EPG din playlist și EPG extern, cu actualizări programate. Grupurile și canalele apar ca **liste** (fără plăci sau butoane mari), totul optimizat pentru telecomandă. Căutare în grupuri, în canale sau în ambele deodată.
2. **Sports Hub** (miezul aplicației): toate meciurile LIVE, de azi, de mâine și din zilele următoare. Pentru fiecare meci, aplicația găsește canalele din playlisturile active care îl transmit și le arată ca surse, cu filtre după limba audio, calitate (rezoluție verificată), sport, competiție și echipă, plus favorite.

Ce **nu** intră: filme și seriale (VOD) din playlist, conturi de utilizatori, server propriu obligatoriu.

## 2. Arhitectura

### Tehnologii

| Zonă | Alegere | De ce |
|---|---|---|
| Limbaj | Kotlin | standardul Android, coroutines pentru tot ce e în fundal |
| UI | Jetpack Compose + `androidx.tv:tv-material` | componente gândite pentru focus și D-pad; liste rapide (`LazyColumn`) |
| Player | Media3 ExoPlayer (+ extensia FFmpeg pentru audio AC3/E-AC3/DTS) | HLS, MPEG-TS, schimbare de piste audio, cel mai bun player pe Android TV |
| Bază de date | Room (SQLite) cu FTS4 pentru căutare | playlisturi de 50.000 – 200.000 de intrări, căutare instantă |
| Rețea | OkHttp (+ Jsoup pentru pagini HTML) | streaming de fișiere mari, cache, user-agent configurabil |
| Lucru în fundal | WorkManager | actualizări programate de playlist, EPG, meciuri, verificări de stream |
| Injecție dependențe | Hilt | standard, simplu de testat |
| Serializare | kotlinx.serialization | JSON pentru API-uri și backup-ul setărilor |
| Setări | DataStore | |
| Min SDK | 23 (Android 6), target cel mai nou | acoperă practic toate TV-urile și boxurile |

Alternativa analizată, Flutter sau React Native, are suport slab pentru focus pe TV și pentru ExoPlayer, deci rămânem pe Kotlin nativ.

### Module

```
app/                  pornire, navigare, temă
core/model            entități comune (Channel, Programme, Match, StreamVariant…)
core/database         Room, DAO-uri, FTS
core/network          OkHttp, cache, limitare de conexiuni
core/common           normalizare text, alias-uri, timp și fusuri orare
feature/playlists     import M3U / Xtream, parsare, actualizare programată
feature/epg           XMLTV (inclusiv .gz), potrivire canal ↔ EPG
feature/channels      grupare duplicate, variante de calitate, favorite
feature/player        ExoPlayer, zapping, overlay, piste audio, failover
feature/sports        surse de meciuri, potrivire meci ↔ canale, filtre
feature/settings      setări, backup / restore
```

Fluxul de date: surse externe → parsere în flux (nu se încarcă fișierul întreg în memorie) → Room → `Flow` → ViewModel → UI Compose. Totul rulează pe televizor; nu e nevoie de server.

### Performanță (playlisturile mari sunt problema reală)

- M3U și XMLTV se parsează **în flux** (citire linie cu linie, `XmlPullParser`), cu inserări în loturi și tranzacții.
- La import se păstrează doar intrările live: se aruncă intrările care arată a VOD (`/movie/`, `/series/`, extensii `.mp4/.mkv`, grupuri de tip „VOD”, „Movies”, „Series”) și, la Xtream, se cere doar `get_live_streams`.
- EPG: se păstrează doar fereastra utilă (de exemplu de ieri până la +7 zile) și doar pentru canalele care există în playlisturi.
- Actualizarea e incrementală: dacă fișierul are același `ETag`/`Last-Modified`, nu se mai descarcă.

## 3. Live TV

- Ecranul principal: coloană de grupuri în stânga, canale în dreapta, cu programul curent și următor sub numele canalului. Totul ca text, fără logo-uri mari (opțional un logo mic).
- Mai multe playlisturi, fiecare cu nume, activ/inactiv, interval de actualizare, user-agent și EPG propriu.
- EPG-uri externe multiple, cu prioritate între ele și interval de actualizare propriu.
- Căutare: un singur câmp, cu comutator „Grupuri / Canale / Ambele”. Merge și căutarea vocală a telecomenzii.
- Telecomandă: săgeți pentru navigare, OK pentru pornire, CH+/CH− pentru zapping în grupul curent, cifre pentru numărul canalului, butonul Back pentru ultimul canal, apăsare lungă pe OK pentru meniul canalului (favorit, variantă preferată, ascundere).

## 4. Gruparea canalelor și calitatea

Exemplul tău: „Sky Sport Main Event” apare de ~20 de ori (UHD, FHD, HD, SD). Soluția:

### Gruparea

1. **Cheia de grupare** (numele normalizat): din numele canalului se scot etichetele de calitate și zgomotul (`UHD`, `4K`, `FHD`, `1080p`, `HD`, `720p`, `SD`, `HEVC`, `H265`, `50FPS`, `ᴴᴰ`, `ᵁᴴᴰ`, `RAW`, `VIP`, `BACKUP`, `(1)`, paranteze, prefixe de țară ca `UK:`, `RO|`, `|DE|`), se uniformizează diacriticele și majusculele. Cheia asta servește **doar** ca să știm că „Sky Sport Main Event UHD” și „SKY SPORT MAIN EVENT FHD” sunt același canal. Numele original și eticheta de calitate rămân afișate la fiecare variantă, ca să vezi mereu ce calitate alegi.
2. **Cheia canalului** = numele normalizat + țara (din prefix sau din grup). Dacă există `tvg-id`, acesta are prioritate.
3. **Potrivire aproximativă** (Jaro-Winkler) doar ca sugestie, pentru nume care diferă puțin („Sky Sports Main Event” / „SkySp Main Event”).
4. **Corecții manuale**: poți uni sau separa grupuri; corecțiile se salvează și se aplică la fiecare actualizare.

### Variantele

Fiecare canal grupat are o listă de variante, fiecare cu numele ei original, playlistul, calitatea, limba audio și starea. Alegi **varianta preferată** pentru acel canal; dacă ea pică, playerul trece automat la următoarea, în ordinea calității, oprind-o mai întâi pe cea veche.

### Calitatea și limba: doar din informațiile primite

Decizie (Radu, etapa 0): **nu verificăm stream-urile**. Calitatea și limba se iau din ce dă playlistul:

- **Calitatea**: din numele canalului (`UHD`/`4K`, `FHD`/`1080`, `HD`/`720`, `SD`), din numele grupului sau din atributele M3U, când există. Fără etichetă → „necunoscută”.
- **Limba**: din prefixul canalului sau grupului (`UK`, `EN`, `RO`, `DE`…), din `tvg-language` / `tvg-country`, sau din categoria Xtream.
- Dacă eticheta e greșită, o poți corecta manual pe varianta respectivă; corecția se păstrează la actualizări.
- Pe același stream pot exista mai multe piste audio; în player, ExoPlayer o alege automat pe cea preferată (fără conexiuni suplimentare).

Afișare: etichete scurte `4K`, `FHD`, `HD`, `SD` și codul limbii, lângă numele original al variantei.

### O singură conexiune pe playlist

Playlisturile tale permit **o singură conexiune**. Reguli stricte în tot codul:

- Nicio verificare, previzualizare sau preîncărcare de stream în fundal.
- La schimbarea canalului, stream-ul vechi se **oprește și se eliberează complet** înainte să pornească cel nou (cu o mică pauză, ca serverul să închidă sesiunea).
- Descărcarea playlistului și a EPG-ului (fișiere, nu stream-uri) se face separat de player; la Xtream, cererile API sunt rare și secvențiale.
- Failover-ul încearcă variantele **una câte una**, niciodată în paralel.

## 5. Sports Hub

### 5.1 De unde luăm meciurile

Arhitectura e pe **furnizori interschimbabili**: fiecare sursă e un modul separat care poate fi pornit, oprit sau poate cădea fără să afecteze restul. Rezultatele lor se combină și se deduplică (aceeași oră ± 15 minute, aceleași echipe după normalizare).

| Sursă | Ce dă | Sporturi | Riscuri |
|---|---|---|---|
| **EPG-ul tău** (playlist + extern) | meciuri *și* canalul exact pe care rulează | toate | depinde de calitatea EPG-ului; titluri neuniforme |
| **TheSportsDB** (API, cheie gratuită `123`) | program meciuri, competiții, echipe, logo-uri; endpoint-uri TV (`lookuptv`, `eventstv`) | multe sporturi | limitele gratuite sunt foarte mici la endpoint-urile TV; varianta premium e ieftină |
| **LiveSoccerTV** (pagini HTML) | meciuri de fotbal + posturi TV pe țări | fotbal | nu are API; termenii site-ului nu permit extragerea automată; Cloudflare; structura paginii se poate schimba |
| **Sofascore / ESPN** (API-uri neoficiale JSON) | meciuri live, scoruri, multe sporturi; Sofascore are și posturi TV pe țări | multe sporturi | neoficiale, se pot schimba sau bloca oricând |
| **football-data.org** (API gratuit) | program fiabil pentru ~12 competiții mari | fotbal | fără informații TV |

Recomandarea: în etapa 6 pornim cu **EPG + TheSportsDB** (stabile, legale). În etapa 8 adăugăm **LiveSoccerTV** și eventual **Sofascore** ca furnizori opționali, dezactivabili, cu cereri rare (de exemplu o dată la 3 ore, o pagină pe zi), cache local și user-agent de browser, rulate doar pe televizorul tău, pentru uz personal. Dacă un site se schimbă sau blochează, se strică doar acel furnizor; repararea lui e o sarcină mică, separată.

Despre legalitate: datele de program (cine joacă, când, pe ce post) sunt fapte publice, dar termenii unor site-uri interzic extragerea automată. Pentru uz personal, cu volum mic, riscul practic e blocarea accesului, nu altceva. De aceea furnizorii neoficiali sunt opționali, iar aplicația funcționează și fără ei.

### 5.2 Cum potrivim un meci cu canalele tale

Fiecare potrivire are un **scor de încredere**, afișat lângă sursă:

1. **Sigur (EPG)**: un program din EPG, în intervalul meciului, al cărui titlu sau descriere conține ambele echipe (după normalizare și alias-uri). Exemple de titluri tratate: `Live: Arsenal v Chelsea`, `Premier League: Arsenal - Chelsea`, `Fotbal: FCSB – CFR Cluj (live)`.
2. **Probabil (post anunțat)**: un furnizor spune „Sky Sports Main Event (UK)” → căutăm grupul de canale cu acel nume normalizat, din țara respectivă, folosind un dicționar de alias-uri (`Sky Sp ME`, `SkySports Main Event`…).
3. **Presupus (drepturi TV)**: reguli „competiție → posturi” pe țară (Premier League → Sky Sports / TNT în UK; Liga 1 → Digi Sport / Prima Sport în RO), editabile de tine.
4. **Învățare**: când confirmi sau respingi o sursă („da, e meciul”, „nu e”), aplicația ține minte alias-ul sau regula și îmbunătățește potrivirile viitoare.

Normalizarea echipelor: fără diacritice, fără `FC`, `CF`, `AFC`, `SC`, `CS`, abrevieri cunoscute (`Man Utd` = `Manchester United`, `PSG` = `Paris Saint-Germain`), plus alias-urile din TheSportsDB.

### 5.3 Ecranele

- **Taburi**: LIVE · Azi · Mâine · Săptămâna asta · Favorite.
- **Lista meciurilor**: oră, sport, competiție, echipe, scor (dacă e live), numărul de surse găsite.
- **Fereastra de previzualizare a meciului** (se deschide când selectezi un meci), cu taburi:
  - **Surse**: toate canalele găsite, grupate pe canal și apoi pe variante (calitate, limbă, încredere), ordonate după preferințele tale. OK pornește sursa aleasă; un buton pornește direct sursa preferată.
  - **Pre-meci**: forma recentă a echipelor, meciurile directe, clasamentul, echipele probabile sau oficiale (când apar), arbitru, stadion.
  - **Live**: scor, minut, evenimente (goluri, cartonașe, schimbări), statistici (posesie, șuturi etc.). Respectă opțiunea „fără spoilere”.
  - Datele pentru Pre-meci și Live vin de la TheSportsDB și, opțional, Sofascore; dacă o sursă nu are date pentru un meci, tabul arată doar ce există.
- **Filtre**: sport, competiție, echipă, limbă audio, calitate minimă, „doar meciuri cu surse găsite”. Filtrele se pot salva ca presetări.
- **Preferințe de limbă (setări în aplicație)**: implicit engleză sau română. Pentru echipele și competițiile favorite se arată întâi sursele EN/RO. Dacă pentru un meci nu există surse EN/RO, se acceptă și alte limbi, dar doar de la o calitate minimă (implicit FHD). Toate pragurile se pot schimba.
- **Favorite**: echipe, competiții, sporturi, canale.

## 6. Etapele (fiecare într-un fir nou, fiecare cu PR propriu)

| Etapa | Ce conține | Rezultat verificabil |
|---|---|---|
| **0** | Analiză, plan, igiena repo-ului | acest document |
| **1** | Scheletul proiectului: Gradle, module, temă TV, navigare, ecrane goale; CI care construiește APK-ul; versiunea „ultima-versiune” actualizată automat; curățarea automată | instalezi APK-ul pe TV prin Downloader și se deschide |
| **2** | Playlisturi: import M3U (URL / fișier) și Xtream, filtrare doar live, Room + FTS, liste grupuri/canale, căutare, actualizare programată | îți vezi canalele și cauți în ele |
| **3** | Player: ExoPlayer, zapping, overlay info, piste audio și subtitrări, ultimul canal, reconectare | te uiți la TV |
| **4** | EPG: din playlist (`url-tvg`) și extern, XMLTV/.gz, potrivire canal ↔ EPG, acum/urmează, programul canalului | vezi programul |
| **5** | Gruparea duplicatelor, variante (calitate și limbă din playlist), varianta preferată, failover secvențial, corecții manuale | Sky Sport Main Event apare o dată, cu 20 de variante etichetate |
| **6** | Sports Hub I: meciuri din EPG + TheSportsDB, taburi, filtre, preferințe de limbă, potrivire EPG și pe nume de post | apeși pe un meci și vezi sursele |
| **7** | Previzualizarea meciului: taburile Surse, Pre-meci (statistici) și Live (scor, evenimente, statistici), modul „fără spoilere” | vezi statistici înainte să pornești meciul |
| **8** | Sports Hub II: LiveSoccerTV (și opțional Sofascore), dicționar de posturi și alias-uri, reguli de drepturi TV, învățare din confirmări | mai multe meciuri cu surse găsite |
| **9** | Favorite, notificări înainte de meci, rândul de pe ecranul principal Android TV, catch-up, backup/restore setări, finisare | aplicația de zi cu zi |

Etapele 2–5 pot fi reordonate dacă vrei Sports Hub mai devreme: minimul pentru etapa 6 este 2 + 3 + 4.

Verificarea reală a calității și a limbii (citirea stream-urilor) a fost scoasă din plan: playlisturile au o singură conexiune, iar informațiile din playlist sunt suficiente.

## 7. Igiena repo-ului (să nu depășim limitele GitHub)

Repo-ul e privat, deci contul gratuit are aproximativ 2.000 de minute de Actions pe lună și 500 MB pentru artefacte și pachete. Măsurile:

1. **Nimic generat în git**: `.gitignore` exclude `build/`, `.gradle/`, `*.apk`, `*.aab`, fișiere IDE, `local.properties`, chei de semnare (`*.jks`, `*.keystore`). Playlisturile și EPG-urile tale nu intră niciodată în repo (conțin date de acces).
2. **O singură versiune publicată**: CI-ul publică APK-ul într-un singur release, `ultima-versiune`, înlocuind fișierul de fiecare dată. Linkul de descărcare rămâne același (bun pentru Downloader pe TV), iar versiunile vechi nu se adună.
3. **Artefacte de scurtă durată**: APK-urile din PR-uri se păstrează 3 zile (`retention-days: 3`), apoi dispar automat.
4. **Curățare automată săptămânală** (`.github/workflows/curatenie.yml`, inclus deja): șterge rulările de workflow mai vechi de 14 zile (păstrând ultimele 20), artefactele expirate sau vechi, cache-urile vechi și release-urile în afară de `ultima-versiune`.
5. **Workflow-uri economice**: `concurrency` cu anularea rulărilor vechi, nu se construiește nimic la modificări doar în documentație, cache Gradle gestionat de `setup-gradle`.
6. **Ramuri**: după merge, ramura se șterge automat. Setarea trebuie activată o singură dată de tine: Settings → General → Pull Requests → „Automatically delete head branches”.
7. **Cheia de semnare** stă doar în GitHub Secrets (din etapa 1), niciodată în repo.

## 8. Idei în plus (acceptate)

1. **Failover automat**: dacă varianta preferată se blochează, trece singur la următoarea variantă a aceluiași canal, fără să ieși din player (una câte una, cu o singură conexiune).
2. **Notificări pentru meciurile favorite**: cu 10 minute înainte apare o notificare pe TV; un buton te duce direct pe sursa preferată.
3. **Rând pe ecranul principal Android TV**: „Meciurile tale de azi”, direct din launcher.
4. **Scor live în overlay** când te uiți la un meci, cu opțiune „fără spoilere” (ascunde scorurile).
5. **Catch-up / înapoi în timp** pe canalele Xtream care îl suportă (meciul început de 20 de minute îl vezi de la început).
6. **Starea surselor**: variantele care nu au pornit când le-ai folosit ultima oară apar marcate și coboară în listă (doar din redare, fără verificări în fundal).
7. **Backup / restore** al setărilor, favoritelor, alias-urilor și corecțiilor într-un fișier, ca să muți totul pe alt televizor în câteva secunde.
8. **Profil de limbă pe sport**: de exemplu fotbal în română sau engleză, F1 în engleză, tenis indiferent.
9. **Calendarul echipelor favorite**: următoarele 5 meciuri ale fiecărei echipe, cu sursele deja găsite.

Respinsă: multi-view (playlisturile au o singură conexiune).

## 9. Decizii luate (14.11.2026)

- Playlisturi: și M3U, și Xtream Codes.
- O singură conexiune pe playlist; fără verificarea stream-urilor.
- Limbi preferate: engleză și română; alte limbi doar de la FHD în sus, când nu există EN/RO (setări în aplicație).
- Ideile în plus sunt acceptate, fără multi-view.
- Meciul se deschide într-o fereastră de previzualizare cu Surse, Pre-meci și Live.
- Etapa 1 e aprobată și pornește într-un fir nou.
