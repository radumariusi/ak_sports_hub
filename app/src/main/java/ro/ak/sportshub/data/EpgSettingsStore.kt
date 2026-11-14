package ro.ak.sportshub.data

import android.content.Context
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import ro.ak.sportshub.core.epg.EpgOverride
import ro.ak.sportshub.core.epg.EpgSettings
import ro.ak.sportshub.core.epg.EpgUpdatePolicy
import ro.ak.sportshub.core.epg.ExternalEpg

/**
 * Setările EPG (interval, zile păstrate, surse externe, decalaje), în preferințele aplicației.
 * Stau separat de baza EPG, care e doar un cache și poate fi reconstruită oricând.
 */
class EpgSettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("epg", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<EpgSettings> = _settings.asStateFlow()

    val current: EpgSettings get() = _settings.value

    @Synchronized
    fun update(transform: (EpgSettings) -> EpgSettings) {
        val next = transform(_settings.value)
        prefs.edit().putString(KEY, write(next)).apply()
        _settings.value = next
    }

    fun setPolicy(policy: EpgUpdatePolicy) = update { it.copy(policy = policy) }

    fun addExternal(url: String, name: String): ExternalEpg {
        val e = ExternalEpg(id = UUID.randomUUID().toString().take(8), url = url.trim(), name = name.trim())
        update { it.copy(external = it.external + e) }
        return e
    }

    fun updateExternal(e: ExternalEpg) = update { s -> s.copy(external = s.external.map { if (it.id == e.id) e else it }) }

    fun removeExternal(id: String) = update { s -> s.copy(external = s.external.filterNot { it.id == id }) }

    fun setOverride(key: String, o: EpgOverride) = update { s ->
        s.copy(overrides = if (o == EpgOverride()) s.overrides - key else s.overrides + (key to o))
    }

    private fun read(): EpgSettings {
        val json = prefs.getString(KEY, null) ?: return EpgSettings()
        return runCatching {
            val o = JSONObject(json)
            val p = o.optJSONObject("policy")
            val defaults = EpgUpdatePolicy()
            EpgSettings(
                policy = EpgUpdatePolicy(
                    intervalHours = p?.optInt("intervalHours", defaults.intervalHours) ?: defaults.intervalHours,
                    daysAhead = p?.optInt("daysAhead", defaults.daysAhead) ?: defaults.daysAhead,
                    hoursBack = p?.optInt("hoursBack", defaults.hoursBack) ?: defaults.hoursBack,
                ),
                external = o.optJSONArray("external")?.let { arr ->
                    (0 until arr.length()).map { i ->
                        val e = arr.getJSONObject(i)
                        ExternalEpg(
                            id = e.getString("id"),
                            url = e.getString("url"),
                            name = e.optString("name"),
                            enabled = e.optBoolean("enabled", true),
                            shiftMinutes = e.optInt("shiftMinutes", 0),
                        )
                    }
                }.orEmpty(),
                overrides = o.optJSONObject("overrides")?.let { obj ->
                    obj.keys().asSequence().associateWith { k ->
                        val v = obj.getJSONObject(k)
                        EpgOverride(enabled = v.optBoolean("enabled", true), shiftMinutes = v.optInt("shiftMinutes", 0))
                    }
                }.orEmpty(),
            )
        }.getOrDefault(EpgSettings())
    }

    private fun write(s: EpgSettings): String = JSONObject().apply {
        put(
            "policy",
            JSONObject()
                .put("intervalHours", s.policy.intervalHours)
                .put("daysAhead", s.policy.daysAhead)
                .put("hoursBack", s.policy.hoursBack),
        )
        put(
            "external",
            JSONArray().apply {
                s.external.forEach { e ->
                    put(
                        JSONObject()
                            .put("id", e.id)
                            .put("url", e.url)
                            .put("name", e.name)
                            .put("enabled", e.enabled)
                            .put("shiftMinutes", e.shiftMinutes),
                    )
                }
            },
        )
        put(
            "overrides",
            JSONObject().apply {
                s.overrides.forEach { (k, v) ->
                    put(k, JSONObject().put("enabled", v.enabled).put("shiftMinutes", v.shiftMinutes))
                }
            },
        )
    }.toString()

    private companion object {
        const val KEY = "settings"
    }
}
