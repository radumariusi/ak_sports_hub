package ro.ak.sportshub.core.epg

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.StringReader
import java.util.zip.GZIPInputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler

/** Eroare afișabilă: mesajul e în română și nu conține adrese sau date de acces. */
class EpgException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Un canal declarat de EPG (`<channel id="…"><display-name>…`). */
data class XmltvChannel(val id: String, val displayNames: List<String>)

/** O emisiune, cu orele deja corectate (decalaj) în milisecunde epoch. */
data class XmltvProgramme(
    val channelId: String,
    val start: Long,
    /** Null dacă EPG-ul nu dă ora de sfârșit; se deduce din emisiunea următoare. */
    val stop: Long?,
    val title: String,
    val subtitle: String?,
    val description: String?,
    val category: String?,
)

/** Unde ajung datele citite. Parserul e sincron: implementarea scrie în loturi, pe firul apelantului. */
interface XmltvSink {
    fun onChannel(channel: XmltvChannel)

    /** Doar emisiunile canalelor care ne interesează sunt construite și trimise mai departe. */
    fun wantsProgrammes(channelId: String): Boolean

    fun onProgramme(programme: XmltvProgramme)
}

data class XmltvStats(
    val channels: Int,
    val programmes: Int,
    /** Emisiuni sărite: canal nefolosit, în afara ferestrei sau fără oră validă. */
    val skipped: Int,
    /** Dacă fișierul s-a terminat cu o eroare după ce s-au citit emisiuni, ce s-a citit rămâne. */
    val partialError: String?,
)

/**
 * Parser XMLTV în flux (SAX): fișierul nu se încarcă în memorie, fiecare emisiune ajunge direct în [XmltvSink].
 * Merge la fel pe Android (Expat) și pe JVM (teste).
 *
 * @param window doar emisiunile care se suprapun cu intervalul (ms epoch) sunt păstrate
 * @param shiftMs decalajul sursei, adunat la toate orele
 * @param preferredLanguages la titluri și descrieri în mai multe limbi, ordinea preferată (`ro`, `en`)
 */
class XmltvParser(
    private val window: LongRange? = null,
    private val shiftMs: Long = 0,
    private val preferredLanguages: List<String> = emptyList(),
    private val maxDescription: Int = 600,
    /** Apelată des; poate arunca (de exemplu la anularea coroutinei). */
    private val checkCancelled: () -> Unit = {},
) {

    fun parse(input: InputStream, sink: XmltvSink): XmltvStats {
        val handler = Handler(sink)
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = false
            isValidating = false
        }
        val parser = factory.newSAXParser()
        val reader = parser.xmlReader
        // Fără DTD-uri externe (`<!DOCTYPE tv SYSTEM "xmltv.dtd">`): nu ieșim în rețea și nu extindem entități străine.
        runCatching { reader.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        runCatching { reader.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        reader.setEntityResolver { _, _ -> InputSource(StringReader("")) }
        reader.contentHandler = handler
        reader.errorHandler = handler

        var partial: String? = null
        try {
            reader.parse(InputSource(open(input)))
        } catch (e: SAXParseException) {
            if (handler.programmes == 0) {
                throw EpgException("Fișierul nu e un EPG XMLTV valid (linia ${e.lineNumber}).", e)
            }
            partial = "EPG citit parțial: fișierul are o eroare la linia ${e.lineNumber}."
        } catch (e: SAXException) {
            (e.exception as? RuntimeException)?.let { throw it }
            throw EpgException("Fișierul nu e un EPG XMLTV valid.", e)
        }
        if (handler.channels == 0 && handler.programmes == 0 && !handler.sawRoot) {
            throw EpgException("Fișierul nu e un EPG XMLTV.")
        }
        return XmltvStats(handler.channels, handler.programmes, handler.skipped, partial)
    }

    private inner class Handler(private val sink: XmltvSink) : DefaultHandler() {
        var channels = 0
        var programmes = 0
        var skipped = 0
        var sawRoot = false
        private var seen = 0

        private val text = StringBuilder()
        private var collecting = false
        private var textLang: String? = null

        // <channel>
        private var channelId: String? = null
        private val displayNames = ArrayList<String>(4)

        // <programme>
        private var inProgramme = false
        private var progChannel = ""
        private var progStart = 0L
        private var progStop: Long? = null
        private val titles = ArrayList<Pair<String?, String>>(2)
        private val subtitles = ArrayList<Pair<String?, String>>(2)
        private val descriptions = ArrayList<Pair<String?, String>>(2)
        private var category: String? = null

        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
            when (qName) {
                "tv" -> sawRoot = true
                "channel" -> {
                    channelId = attributes.getValue("id")?.trim()
                    displayNames.clear()
                }
                "display-name" -> if (channelId != null) startText(attributes)
                "programme" -> startProgramme(attributes)
                "title", "sub-title", "desc", "category" -> if (inProgramme) startText(attributes)
            }
        }

        private fun startProgramme(attributes: Attributes) {
            if (++seen % 1000 == 0) checkCancelled()
            val channel = attributes.getValue("channel")?.trim()
            val start = XmltvTime.parse(attributes.getValue("start"))
            if (channel.isNullOrEmpty() || start == null || !sink.wantsProgrammes(channel)) {
                skipped++
                return
            }
            val begin = start + shiftMs
            val end = XmltvTime.parse(attributes.getValue("stop"))?.plus(shiftMs)
            if (window != null && !overlaps(begin, end, window)) {
                skipped++
                return
            }
            inProgramme = true
            progChannel = channel
            progStart = begin
            progStop = end?.takeIf { it > begin }
            titles.clear()
            subtitles.clear()
            descriptions.clear()
            category = null
        }

        private fun startText(attributes: Attributes) {
            collecting = true
            text.setLength(0)
            textLang = attributes.getValue("lang")?.lowercase()
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (collecting) text.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            if (collecting) {
                collecting = false
                val value = text.toString().trim().replace(whitespace, " ")
                if (value.isNotEmpty()) {
                    when (qName) {
                        "display-name" -> displayNames += value
                        "title" -> titles += textLang to value
                        "sub-title" -> subtitles += textLang to value
                        "desc" -> descriptions += textLang to value
                        "category" -> if (category == null) category = value
                    }
                }
            }
            when (qName) {
                "channel" -> {
                    val id = channelId
                    if (!id.isNullOrEmpty()) {
                        channels++
                        sink.onChannel(XmltvChannel(id, displayNames.toList()))
                    }
                    channelId = null
                }
                "programme" -> if (inProgramme) {
                    inProgramme = false
                    val title = pick(titles)
                    if (title == null) {
                        skipped++
                    } else {
                        programmes++
                        sink.onProgramme(
                            XmltvProgramme(
                                channelId = progChannel,
                                start = progStart,
                                stop = progStop,
                                title = title,
                                subtitle = pick(subtitles),
                                description = pick(descriptions)?.let {
                                    if (it.length > maxDescription) it.take(maxDescription - 1).trimEnd() + "…" else it
                                },
                                category = category,
                            ),
                        )
                    }
                }
            }
        }

        private fun pick(values: List<Pair<String?, String>>): String? {
            if (values.isEmpty()) return null
            for (lang in preferredLanguages) {
                values.firstOrNull { it.first == lang }?.let { return it.second }
            }
            return values.first().second
        }

        // Erorile mărunte nu opresc citirea; cele fatale ajung în parse().
        override fun error(e: SAXParseException) = Unit
        override fun warning(e: SAXParseException) = Unit
    }

    companion object {
        private val whitespace = Regex("""\s+""")

        private fun overlaps(start: Long, stop: Long?, window: LongRange): Boolean =
            start <= window.last && (stop ?: start) >= window.first

        /** Dezarhivează automat fișierele .gz (după primii octeți, nu după nume). */
        fun open(raw: InputStream): InputStream {
            val input = if (raw is BufferedInputStream) raw else BufferedInputStream(raw, 64 * 1024)
            input.mark(2)
            val b1 = input.read()
            val b2 = input.read()
            input.reset()
            return if (b1 == 0x1f && b2 == 0x8b) BufferedInputStream(GZIPInputStream(input, 64 * 1024), 64 * 1024) else input
        }
    }
}
