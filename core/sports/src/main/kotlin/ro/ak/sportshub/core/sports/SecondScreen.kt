package ro.ak.sportshub.core.sports

import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * F4, telefonul ca al doilea ecran: cât e aplicația deschisă pe televizor (și setarea e pornită), televizorul servește
 * în rețeaua locală o pagină pentru telefon, la o adresă cu un cod aleator. Pe telefon vezi ce e acum pe televizor,
 * meciurile live și de azi–mâine, iar pe un meci scorul, fazele, statisticile și formațiile; „Pornește pe televizor”
 * pornește sursa recomandată pe aceeași cale ca schimbarea canalului (un singur stream).
 *
 * Telefonul nu primește nicio adresă de stream și nicio dată de acces: doar numele canalelor și datele de sport.
 * Scorul respectă întârzierea stream-ului și „Fără spoilere” (dezvăluirea de pe telefon e ca cea de pe televizor).
 */
object SecondScreen {

    /** Ce cere telefonul. */
    sealed interface Route {
        data object Page : Route
        data object Now : Route
        data object Matches : Route
        data class Details(val matchId: String) : Route
        data class Play(val matchId: String) : Route
        data class Reveal(val matchId: String) : Route
        data object NotFound : Route
        data object WrongMethod : Route
    }

    /** Cererea [method] [path] (fără `?…`), cu codul [token] la început; altfel [Route.NotFound]. */
    fun route(method: String, path: String, token: String): Route {
        val clean = path.substringBefore('?').trimEnd('/')
        val prefix = "/$token"
        if (token.isEmpty() || (clean != prefix && !clean.startsWith("$prefix/"))) return Route.NotFound
        val rest = clean.removePrefix(prefix).trimStart('/')
        val parts = rest.split('/').filter { it.isNotEmpty() }.map(::decode)
        val get = method == "GET"
        val post = method == "POST"
        return when {
            parts.isEmpty() -> if (get) Route.Page else Route.WrongMethod
            parts[0] != "api" -> Route.NotFound
            parts.size == 2 && parts[1] == "now" -> if (get) Route.Now else Route.WrongMethod
            parts.size == 2 && parts[1] == "matches" -> if (get) Route.Matches else Route.WrongMethod
            parts.size == 3 && parts[1] == "match" -> if (get) Route.Details(parts[2]) else Route.WrongMethod
            parts.size == 3 && parts[1] == "play" -> if (post) Route.Play(parts[2]) else Route.WrongMethod
            parts.size == 3 && parts[1] == "reveal" -> if (post) Route.Reveal(parts[2]) else Route.WrongMethod
            else -> Route.NotFound
        }
    }

    private fun decode(s: String): String = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

    /** Un meci cum îl vede telefonul (scorul e deja întârziat cât stream-ul). */
    data class Row(
        val match: Match,
        val favorite: Boolean,
        /** „Fără spoilere” și meciul nu e dezvăluit. */
        val hideScore: Boolean,
        val sources: Int,
        val sureSources: Int,
        /** Numele sursei recomandate și calitatea ei („Sky Sports Main Event · FHD”); null = nicio sursă de pornit. */
        val best: String?,
    )

    private val BEFORE = TimeUnit.HOURS.toMillis(3)
    private val AHEAD = TimeUnit.HOURS.toMillis(36)
    const val MAX_ROWS = 150

    /** Meciurile arătate pe telefon: live, cele care încep în 36 h și cele terminate în ultimele 3 h. */
    fun shown(m: Match, now: Long): Boolean = when (m.status) {
        MatchStatus.LIVE -> true
        MatchStatus.SCHEDULED -> m.start <= now + AHEAD && m.end >= now
        MatchStatus.FINISHED -> m.end >= now - BEFORE
    }

    /** Ordinea pe telefon: live, apoi după oră (favoritele întâi la aceeași oră), terminatele la final. */
    fun order(rows: List<Row>): List<Row> = rows.sortedWith(
        compareBy<Row> { r -> when (r.match.status) { MatchStatus.LIVE -> 0; MatchStatus.SCHEDULED -> 1; MatchStatus.FINISHED -> 2 } }
            .thenBy { if (it.match.status == MatchStatus.FINISHED) -it.match.start else it.match.start }
            .thenBy { if (it.favorite) 0 else 1 },
    ).take(MAX_ROWS)

    private fun str(s: String?): JsonElement = if (s == null) JsonNull else JsonPrimitive(s)
    private fun num(n: Number?): JsonElement = if (n == null) JsonNull else JsonPrimitive(n)
    private fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))

    private fun rowJson(r: Row): JsonObject {
        val m = r.match
        val showScore = !r.hideScore && m.status != MatchStatus.SCHEDULED && m.homeScore != null && m.awayScore != null
        return obj(
            "id" to str(m.id),
            "home" to str(m.home),
            "away" to str(m.away),
            "sport" to str(m.sport?.label),
            "competition" to str(m.competition),
            "start" to num(m.start),
            "status" to str(m.status.name),
            "homeScore" to num(if (showScore) m.homeScore else null),
            "awayScore" to num(if (showScore) m.awayScore else null),
            "hidden" to JsonPrimitive(r.hideScore),
            "favorite" to JsonPrimitive(r.favorite),
            "sources" to JsonPrimitive(r.sources),
            "sure" to JsonPrimitive(r.sureSources),
            "best" to str(r.best),
        )
    }

    /** Lista de meciuri (deja aleasă cu [shown] și ordonată cu [order]). */
    fun matchesJson(rows: List<Row>): String = obj("matches" to JsonArray(rows.map(::rowJson))).toString()

    /** Ce e acum pe televizor: canalul (null = playerul nu rulează) și meciul legat de el. */
    fun nowJson(channel: String?, row: Row?): String = obj(
        "playing" to JsonPrimitive(channel != null),
        "channel" to str(channel),
        "match" to (row?.let(::rowJson) ?: JsonNull),
    ).toString()

    /**
     * Detaliile unui meci pentru telefon: antetul (minutul, stadionul), fazele, statisticile și formațiile.
     * Cu [Row.hideScore], scorul, fazele și statisticile lipsesc (formațiile și stadionul rămân).
     */
    fun detailsJson(row: Row, details: MatchDetails, loading: Boolean): String {
        val hide = row.hideScore
        val e = details.event
        val m = row.match
        val status = listOfNotNull(e?.status, m.status).maxBy { it.ordinal }
        val started = status != MatchStatus.SCHEDULED
        val hs = e?.homeScore ?: m.homeScore
        val aw = e?.awayScore ?: m.awayScore
        val base = rowJson(row.copy(match = m.copy(status = status, homeScore = hs, awayScore = aw)))
        val venue = details.venue
        val place = listOfNotNull(venue?.stadium ?: e?.venue ?: m.venue, venue?.city ?: e?.city).distinct().joinToString(", ").ifEmpty { null }
        val timeline = if (hide || !started) emptyList() else details.timeline.filter { it.kind != TimelineKind.OTHER }
        val stats = if (hide || !started) emptyList() else details.stats
        return JsonObject(
            base + mapOf(
                "progress" to str(if (hide || status != MatchStatus.LIVE) null else e?.progress),
                "venue" to str(place),
                "referee" to str(venue?.referee ?: e?.referee),
                "loading" to JsonPrimitive(loading),
                "events" to JsonArray(
                    timeline.map { t ->
                        obj(
                            "minute" to num(t.minute),
                            "kind" to str(t.kind.label),
                            "home" to JsonPrimitive(t.home),
                            "player" to str(t.player),
                            "other" to str(t.other),
                            "goal" to JsonPrimitive(t.kind == TimelineKind.GOAL || t.kind == TimelineKind.OWN_GOAL || t.kind == TimelineKind.PENALTY),
                        )
                    },
                ),
                "stats" to JsonArray(stats.map { s -> obj("name" to str(s.name), "home" to str(s.home), "away" to str(s.away)) }),
                "lineup" to JsonArray(
                    details.lineup.map { p ->
                        obj(
                            "name" to str(p.name),
                            "number" to str(p.number),
                            "position" to str(p.position),
                            "home" to JsonPrimitive(p.home),
                            "sub" to JsonPrimitive(p.substitute),
                        )
                    },
                ),
            ),
        ).toString()
    }

    fun ok(message: String): String = obj("ok" to JsonPrimitive(true), "message" to JsonPrimitive(message)).toString()
    fun error(message: String): String = obj("ok" to JsonPrimitive(false), "message" to JsonPrimitive(message)).toString()

    /**
     * Pagina pentru telefon: o singură pagină, fără nimic de pe internet. Citește `api/now` la 10 s, `api/matches` la
     * 30 s și meciul deschis la 30 s (televizorul hotărăște singur cât de des cere date noi la furnizori).
     * Textul vine doar prin `textContent`, deci numele din playlist nu pot strica pagina.
     */
    fun page(): String = """<!DOCTYPE html><html lang="ro"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><meta name="theme-color" content="#0B0F14">
<title>AK Sports Hub · al doilea ecran</title><style>
body{font-family:system-ui,sans-serif;background:#0B0F14;color:#eef;margin:0;padding:0 14px 40px;max-width:640px}
h1{font-size:18px;color:#FFB547;margin:14px 0 6px}h2{font-size:15px;color:#FFB547;margin:18px 0 6px;text-transform:uppercase;letter-spacing:.04em}
.card{background:#151b23;border-radius:10px;padding:10px 12px;margin:6px 0}.row{display:flex;align-items:center;gap:10px}
.t{flex:1;min-width:0}.n{white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.dim{color:#8a93a3;font-size:13px}
.sc{font-weight:700;font-size:17px;min-width:28px;text-align:right}.live{color:#ff5a5a;font-weight:700;font-size:12px}
.fav{color:#FFB547}button{background:#FFB547;color:#000;border:0;border-radius:8px;padding:11px 14px;font-size:15px;font-weight:700;margin:6px 6px 0 0}
button.sec{background:#26303c;color:#eef}.tabs{display:flex;gap:6px;margin:10px 0}.tabs button{flex:1;margin:0}.tabs .on{background:#FFB547;color:#000}
.tabs button:not(.on){background:#1b222c;color:#cfd6e0}.big{font-size:30px;font-weight:800;text-align:center;margin:6px 0}
.st{display:grid;grid-template-columns:1fr auto 1fr;gap:6px;font-size:14px;padding:4px 0;border-bottom:1px solid #1f2731}
.st span:first-child{text-align:left}.st span:nth-child(2){color:#8a93a3;text-align:center}.st span:last-child{text-align:right}
.ev{display:flex;gap:8px;font-size:14px;padding:4px 0}.ev .m{color:#8a93a3;min-width:34px}.ev.away{flex-direction:row-reverse;text-align:right}
.cols{display:grid;grid-template-columns:1fr 1fr;gap:10px;font-size:14px}.msg{position:fixed;left:14px;right:14px;bottom:14px;background:#26303c;padding:12px;border-radius:10px;display:none}
a{color:#FFB547}</style></head><body>
<h1>AK Sports Hub</h1>
<div id="now" class="card dim">Se încarcă…</div>
<div id="list"></div>
<div id="match" style="display:none"></div>
<div id="msg" class="msg"></div>
<script>
var base=location.pathname.replace(/\/+$/,'');var open=null,openTab='live',lastList=null,lastNow=null;
function h(t,c,x){var e=document.createElement(t);if(c)e.className=c;if(x!=null)e.textContent=x;return e}
function api(p,post){return fetch(base+'/api/'+p,{method:post?'POST':'GET',cache:'no-store'}).then(function(r){return r.json()})}
function say(t){var m=document.getElementById('msg');m.textContent=t;m.style.display='block';clearTimeout(say.t);say.t=setTimeout(function(){m.style.display='none'},3500)}
function hhmm(ms){var d=new Date(ms);return ('0'+d.getHours()).slice(-2)+':'+('0'+d.getMinutes()).slice(-2)}
function day(ms){var d=new Date(ms),n=new Date();if(d.toDateString()==n.toDateString())return '';n.setDate(n.getDate()+1);if(d.toDateString()==n.toDateString())return 'mâine ';return ('0'+d.getDate()).slice(-2)+'.'+('0'+(d.getMonth()+1)).slice(-2)+' '}
function title(m){return m.away?m.home+' – '+m.away:m.home}
function score(m){return m.homeScore!=null&&m.awayScore!=null?m.homeScore+'–'+m.awayScore:''}
function when(m){if(m.status=='LIVE')return 'LIVE';if(m.status=='FINISHED')return 'Final';return day(m.start)+hhmm(m.start)}
function play(id){api('play/'+encodeURIComponent(id),true).then(function(r){say(r.message)}).catch(function(){say('Televizorul nu răspunde.')})}
function rowEl(m){var c=h('div','card');var r=h('div','row');c.appendChild(r);
 var w=h('div',m.status=='LIVE'?'live':'dim',when(m));w.style.minWidth='46px';r.appendChild(w);
 var t=h('div','t');var n=h('div','n',(m.favorite?'★ ':'')+title(m));if(m.favorite)n.className+=' fav';t.appendChild(n);
 t.appendChild(h('div','dim n',[m.sport,m.competition].filter(Boolean).join(' · ')+(m.sources?' · '+m.sources+' surse':' · fără surse')));r.appendChild(t);
 r.appendChild(h('div','sc',m.hidden&&m.status!='SCHEDULED'?'•–•':score(m)));
 c.onclick=function(){show(m.id)};return c}
function drawNow(d){var e=document.getElementById('now');e.textContent='';e.className='card';
 if(!d.playing){e.appendChild(h('div','dim','Pe televizor nu rulează niciun canal.'));return}
 e.appendChild(h('div','dim','Acum pe televizor'));e.appendChild(h('div','n',d.channel));
 if(d.match){var m=d.match;var r=h('div','row');r.appendChild(h('div','t n',title(m)));r.appendChild(h('div','sc',m.hidden?'•–•':score(m)));e.appendChild(r);
  var b=h('button','sec','Deschide meciul');b.onclick=function(){show(m.id)};e.appendChild(b)}}
function drawList(d){var l=document.getElementById('list');l.textContent='';var ms=d.matches||[];
 var live=ms.filter(function(m){return m.status=='LIVE'}),next=ms.filter(function(m){return m.status=='SCHEDULED'}),done=ms.filter(function(m){return m.status=='FINISHED'});
 [['Live',live],['Urmează',next],['Terminate',done]].forEach(function(g){if(!g[1].length)return;l.appendChild(h('h2',null,g[0]));g[1].forEach(function(m){l.appendChild(rowEl(m))})});
 if(!ms.length)l.appendChild(h('div','card dim','Niciun meci acum. Lista vine din Sports Hub de pe televizor.'))}
function refresh(){api('now').then(function(d){lastNow=d;drawNow(d)}).catch(function(){var e=document.getElementById('now');e.className='card dim';e.textContent='Televizorul nu răspunde: e aplicația deschisă și telefonul în aceeași rețea?'})}
function refreshList(){api('matches').then(function(d){lastList=d;if(!open)drawList(d)}).catch(function(){})}
function show(id){open=id;openTab='live';document.getElementById('list').style.display='none';var e=document.getElementById('match');e.style.display='block';e.textContent='Se încarcă…';window.scrollTo(0,0);history.pushState({m:id},'');loadMatch()}
function back(){open=null;document.getElementById('match').style.display='none';document.getElementById('list').style.display='block';if(lastList)drawList(lastList)}
window.onpopstate=function(){if(open)back()};
function loadMatch(){if(!open)return;var id=open;api('match/'+encodeURIComponent(id)).then(function(d){if(open==id)drawMatch(d)}).catch(function(){})}
function drawMatch(d){var e=document.getElementById('match');e.textContent='';
 var b=h('button','sec','‹ Înapoi');b.onclick=function(){history.back()};e.appendChild(b);
 if(!d.id){e.appendChild(h('div','card dim',d.message||'Meciul nu mai e în listă.'));return}
 var c=h('div','card');e.appendChild(c);c.appendChild(h('div','dim',[d.sport,d.competition].filter(Boolean).join(' · ')));
 c.appendChild(h('div',null,title(d)));
 var top=d.hidden&&d.status!='SCHEDULED'?'•–•':(score(d)||when(d));c.appendChild(h('div','big',top));
 var sub=[d.status=='LIVE'?(d.progress?d.progress+(/^\d+$/.test(d.progress)?'′':''):'LIVE'):(d.status=='FINISHED'?'Final':day(d.start)+hhmm(d.start)),d.venue,d.referee?'arbitru '+d.referee:null].filter(Boolean).join(' · ');
 c.appendChild(h('div','dim',sub));
 c.appendChild(h('div','dim',d.best?'Sursa recomandată: '+d.best+(d.sources>1?' (din '+d.sources+')':''):'Nicio sursă în playlisturile tale.'));
 if(d.best){var p=h('button',null,'Pornește pe televizor');p.onclick=function(){play(d.id)};c.appendChild(p)}
 if(d.hidden&&d.status!='SCHEDULED'){var r=h('button','sec','Arată scorul');r.onclick=function(){api('reveal/'+encodeURIComponent(d.id),true).then(loadMatch)};c.appendChild(r)}
 var tabs=h('div','tabs');[['live','Faze'],['stats','Statistici'],['lineup','Formații']].forEach(function(t){var x=h('button',openTab==t[0]?'on':'',t[1]);x.onclick=function(){openTab=t[0];drawMatch(d)};tabs.appendChild(x)});e.appendChild(tabs);
 var box=h('div','card');e.appendChild(box);
 if(openTab=='live'){if(d.hidden)box.appendChild(h('div','dim','Fără spoilere: fazele sunt ascunse.'));else if(!d.events.length)box.appendChild(h('div','dim',d.status=='SCHEDULED'?'Meciul n-a început.':(d.loading?'Se încarcă…':'Nicio fază încă.')));
  d.events.slice().reverse().forEach(function(v){var r=h('div','ev'+(v.home?'':' away'));r.appendChild(h('span','m',v.minute!=null?v.minute+'′':''));r.appendChild(h('span',null,(v.goal?'⚽ ':'')+v.kind+(v.player?': '+v.player:'')+(v.other?' ('+v.other+')':'')));box.appendChild(r)})}
 if(openTab=='stats'){if(d.hidden)box.appendChild(h('div','dim','Fără spoilere: statisticile sunt ascunse.'));else if(!d.stats.length)box.appendChild(h('div','dim',d.loading?'Se încarcă…':'Încă nu sunt statistici.'));
  d.stats.forEach(function(s){var r=h('div','st');r.appendChild(h('span',null,s.home));r.appendChild(h('span',null,s.name));r.appendChild(h('span',null,s.away));box.appendChild(r)})}
 if(openTab=='lineup'){if(!d.lineup.length){box.appendChild(h('div','dim',d.loading?'Se încarcă…':'Formațiile nu sunt încă anunțate.'))}else{var g=h('div','cols');[true,false].forEach(function(home){var col=h('div');col.appendChild(h('div','fav',home?d.home:d.away));
  [false,true].forEach(function(sub){var ps=d.lineup.filter(function(p){return p.home==home&&p.sub==sub});if(!ps.length)return;if(sub)col.appendChild(h('div','dim','Rezerve'));ps.forEach(function(p){col.appendChild(h('div',sub?'dim':null,(p.number?p.number+' ':'')+p.name))})});g.appendChild(col)});box.appendChild(g)}}}
refresh();refreshList();setInterval(refresh,10000);setInterval(refreshList,30000);setInterval(loadMatch,30000);
</script></body></html>"""
}
