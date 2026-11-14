package ro.ak.sportshub.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ro.ak.sportshub.core.common.player.ChannelHistory
import ro.ak.sportshub.core.database.ChannelDao
import ro.ak.sportshub.core.database.PlayableChannel

/**
 * Un canal ținut minte peste actualizări: id-urile din baza de date se schimbă la fiecare import,
 * deci păstrăm playlistul, numele și grupul (fără adresa stream-ului, care poate conține parola).
 */
data class ChannelRef(val playlistId: Long, val name: String, val groupName: String) {
    companion object {
        fun of(c: PlayableChannel) = ChannelRef(c.playlistId, c.name, c.groupName)
    }
}

/** Ultimul canal și cel dinainte, păstrate și după repornirea aplicației. */
class PlayerHistory(context: Context, private val dao: ChannelDao) {

    private val prefs = context.getSharedPreferences("player", Context.MODE_PRIVATE)
    private val history = ChannelHistory(read("current"), read("previous"))
    private val _last = MutableStateFlow(history.current)

    /** Ultimul canal pornit, pentru rândul „Ultimul canal” din Live TV. */
    val last: StateFlow<ChannelRef?> = _last.asStateFlow()

    val previous: ChannelRef? get() = history.previous

    fun visit(channel: PlayableChannel) {
        history.visit(ChannelRef.of(channel))
        save()
    }

    fun forget(ref: ChannelRef) {
        history.forget(ref)
        save()
    }

    /** Id-ul de acum al canalului, sau null dacă a dispărut din playlist. */
    suspend fun resolve(ref: ChannelRef): Long? = dao.findByName(ref.playlistId, ref.name, ref.groupName)

    private fun save() {
        prefs.edit().apply {
            write("current", history.current)
            write("previous", history.previous)
        }.apply()
        _last.value = history.current
    }

    private fun read(key: String): ChannelRef? {
        val playlistId = prefs.getLong("$key.playlist", 0L).takeIf { it > 0 } ?: return null
        val name = prefs.getString("$key.name", null) ?: return null
        return ChannelRef(playlistId, name, prefs.getString("$key.group", null).orEmpty())
    }

    private fun android.content.SharedPreferences.Editor.write(key: String, ref: ChannelRef?) {
        if (ref == null) {
            remove("$key.playlist").remove("$key.name").remove("$key.group")
        } else {
            putLong("$key.playlist", ref.playlistId).putString("$key.name", ref.name).putString("$key.group", ref.groupName)
        }
    }
}
