package ro.ak.sportshub.home

import android.app.SearchManager
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.BaseColumns
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import ro.ak.sportshub.AkApp
import ro.ak.sportshub.DeepLinks
import ro.ak.sportshub.core.common.SearchText
import ro.ak.sportshub.core.sports.MatchSearch
import ro.ak.sportshub.core.sports.MatchStatus
import ro.ak.sportshub.core.sports.ScreensaverBoard

/**
 * F1: rezultatele aplicației în căutarea televizorului (căutarea globală Android TV, `res/xml/searchable.xml`):
 * meciurile din lista Sports Hub deja calculată și canalele din baza locală. Fără rețea. Doar sistemul îl poate
 * citi (`GLOBAL_SEARCH`). OK pe un rezultat deschide meciul sau pornește canalul (`DeepLinks`).
 */
class SearchSuggestionsProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val cursor = MatrixCursor(COLUMNS)
        val raw = selectionArgs?.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.takeIf { it != SearchManager.SUGGEST_URI_PATH_QUERY }
            ?: return cursor
        val query = raw.trim()
        if (query.length < 2) return cursor
        val limit = uri.getQueryParameter(SearchManager.SUGGEST_PARAMETER_LIMIT)?.toIntOrNull()?.coerceIn(1, 50) ?: 20
        val container = (context?.applicationContext as? AkApp)?.container ?: return cursor
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        var row = 0L

        val snapshot = container.sports.snapshot.value
        val counts = snapshot.matches.associate { it.match.id to it.summary.count }
        for (m in MatchSearch.find(snapshot.matches.map { it.match }, query, limit = limit / 2 + 1)) {
            val sources = counts[m.id] ?: 0
            val line = listOfNotNull(
                when (m.status) {
                    MatchStatus.LIVE -> "LIVE"
                    MatchStatus.FINISHED -> "terminat"
                    MatchStatus.SCHEDULED -> ScreensaverBoard.when_(m.start, now, zone)
                },
                m.competition,
                if (sources > 0) "$sources surse" else null,
            ).joinToString(" · ")
            cursor.addRow(arrayOf(row++, m.title, line, DeepLinks.match(m.id).toString(), if (m.isLive) 1 else 0, "video/*"))
        }

        val fts = SearchText.ftsQuery(MatchSearch.clean(query))
        if (fts != null) {
            val channels = runCatching {
                runBlocking { withTimeoutOrNull(2_000) { container.database.channels().search(fts, limit).first() } }
            }.getOrNull().orEmpty()
            for (c in channels.take((limit - row.toInt()).coerceAtLeast(0))) {
                cursor.addRow(arrayOf(row++, c.name, "Canal · " + c.playlistName, DeepLinks.channel(c.id).toString(), 1, "video/*"))
            }
        }
        return cursor
    }

    override fun getType(uri: Uri): String = SearchManager.SUGGEST_MIME_TYPE

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private companion object {
        val COLUMNS = arrayOf(
            BaseColumns._ID,
            SearchManager.SUGGEST_COLUMN_TEXT_1,
            SearchManager.SUGGEST_COLUMN_TEXT_2,
            SearchManager.SUGGEST_COLUMN_INTENT_DATA,
            SearchManager.SUGGEST_COLUMN_IS_LIVE,
            SearchManager.SUGGEST_COLUMN_CONTENT_TYPE,
        )
    }
}
