package ro.ak.sportshub.data

import android.content.Context

/**
 * Cheile personale ale furnizorilor de date (GOAL API), în preferințele `chei-api`: nu intră în backup
 * (`SettingsBackup.PREFS`), nici în jurnal (adresele nu le conțin, cheia merge în antet).
 */
class ApiKeys(context: Context) {

    private val prefs = context.getSharedPreferences("chei-api", Context.MODE_PRIVATE)

    var goalApi: String?
        get() = prefs.getString("goalApi", null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().putString("goalApi", value?.trim()?.ifEmpty { null }).apply()
        }
}
