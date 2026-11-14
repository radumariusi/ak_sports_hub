package ro.ak.sportshub

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Legăturile interne ale aplicației (din notificări și din rândul de pe ecranul principal Android TV):
 * `aksportshub://match/{id}` deschide meciul, `aksportshub://play/{id}` pornește sursa recomandată.
 */
object DeepLinks {
    const val SCHEME = "aksportshub"

    sealed interface Target {
        data class Match(val id: String) : Target
        data class Play(val matchId: String) : Target
    }

    fun match(id: String): Uri = Uri.parse("$SCHEME://match/" + Uri.encode(id))
    fun play(id: String): Uri = Uri.parse("$SCHEME://play/" + Uri.encode(id))

    fun parse(uri: Uri?): Target? {
        if (uri == null || uri.scheme != SCHEME) return null
        val id = uri.pathSegments.firstOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        return when (uri.host) {
            "match" -> Target.Match(id)
            "play" -> Target.Play(id)
            else -> null
        }
    }

    /** Intentul care deschide aplicația pe [uri]. */
    fun intent(context: Context, uri: Uri): Intent =
        Intent(Intent.ACTION_VIEW, uri, context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
}
