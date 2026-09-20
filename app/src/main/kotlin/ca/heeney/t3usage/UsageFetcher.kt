package ca.heeney.t3usage

import android.content.Context
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Pulls usage.json from the NAS over Tailscale. Must be called off the main thread. */
object UsageFetcher {
    private const val MAX_BYTES = 256 * 1024

    fun refresh(context: Context): Boolean {
        val store = UsageStore(context)
        val ok = try {
            val text = get(store.url)
            UsageSnapshot.parse(text) // reject malformed payloads before replacing the last good one
            store.saveSuccess(text, System.currentTimeMillis())
            true
        } catch (error: Exception) {
            store.saveFailure(describe(error), System.currentTimeMillis())
            false
        }
        UsageWidget.updateAll(context)
        return ok
    }

    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Cache-Control", "no-cache")
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
            val bytes = connection.inputStream.use { it.readNBytes(MAX_BYTES + 1) }
            if (bytes.size > MAX_BYTES) throw IOException("Response too large")
            return String(bytes, Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    private fun describe(error: Exception): String {
        val message = error.message?.takeIf { it.isNotBlank() }
        return if (message == null) error.javaClass.simpleName else "${error.javaClass.simpleName}: $message"
    }
}
