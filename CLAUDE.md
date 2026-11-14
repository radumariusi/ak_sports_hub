# AK Sports Hub

Aplicație IPTV pentru Android TV (uz personal), cu Sports Hub. Planul și etapele: [docs/PLAN.md](docs/PLAN.md).

## Reguli de lucru

- Utilizatorul scrie în română; răspunsurile, documentația și mesajele din UI sunt în română.
- Munca e împărțită pe etapele din `docs/PLAN.md`; fiecare etapă are firul ei și un PR propriu. Nu începe etapa următoare fără aprobare.
- Repo-ul trebuie să rămână mic: nimic generat în git (APK, build, cache), un singur release (`ultima-versiune`) înlocuit la fiecare build, artefacte cu `retention-days` mic, rulările vechi șterse de workflow-ul `Curățenie`.
- Playlisturile, EPG-urile și orice date de acces ale utilizatorului nu intră niciodată în repo, în teste sau în loguri.
- Workflow-urile noi: `concurrency` cu `cancel-in-progress`, `paths-ignore` pentru `docs/**` și `*.md`, `timeout-minutes`.
