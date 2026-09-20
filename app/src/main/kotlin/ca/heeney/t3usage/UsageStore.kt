package ca.heeney.t3usage

import android.content.Context
import android.content.SharedPreferences

/** Last good payload plus fetch bookkeeping, so the widget can always draw something. */
class UsageStore(context: Context) {
    val prefs: SharedPreferences = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    private val defaultUrl = context.getString(R.string.default_url)

    var url: String
        get() = prefs.getString(KEY_URL, null)?.trim()?.takeIf { it.isNotEmpty() } ?: defaultUrl
        set(value) {
            prefs.edit().putString(KEY_URL, value.trim()).apply()
        }

    val json: String? get() = prefs.getString(KEY_JSON, null)
    val fetchedAt: Long get() = prefs.getLong(KEY_FETCHED_AT, 0L)
    val lastError: String? get() = prefs.getString(KEY_ERROR, null)
    val lastErrorAt: Long get() = prefs.getLong(KEY_ERROR_AT, 0L)
    val lastSizes: String? get() = prefs.getString(KEY_SIZES, null)

    fun snapshot(): UsageSnapshot? = json?.let { runCatching { UsageSnapshot.parse(it) }.getOrNull() }

    fun saveSuccess(json: String, at: Long) {
        prefs.edit()
            .putString(KEY_JSON, json)
            .putLong(KEY_FETCHED_AT, at)
            .remove(KEY_ERROR)
            .remove(KEY_ERROR_AT)
            .apply()
    }

    fun saveFailure(error: String, at: Long) {
        prefs.edit().putString(KEY_ERROR, error).putLong(KEY_ERROR_AT, at).apply()
    }

    /** Debug aid: the sizes the launcher last asked for, shown in the settings screen. */
    fun saveSizes(description: String) {
        prefs.edit().putString(KEY_SIZES, description).apply()
    }

    companion object {
        const val NAME = "t3usage"
        const val KEY_URL = "url"
        const val KEY_JSON = "json"
        const val KEY_FETCHED_AT = "fetchedAt"
        const val KEY_ERROR = "error"
        const val KEY_ERROR_AT = "errorAt"
        const val KEY_SIZES = "sizes"
    }
}
