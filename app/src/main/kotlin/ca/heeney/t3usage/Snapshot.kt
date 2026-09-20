package ca.heeney.t3usage

import org.json.JSONObject
import java.time.OffsetDateTime

/** One rate-limit window (session / weekly / monthly) of a provider. Times are epoch millis. */
data class UsageWindow(
    val id: String,
    val kind: String,
    val label: String,
    val usedPercent: Int,
    val resetsAt: Long?,
)

data class UsageProvider(
    val id: String,
    val name: String,
    val checkedAt: Long?,
    val windows: List<UsageWindow>,
)

/** Parsed form of usage.json as written by t3-usage-export on the NAS. */
data class UsageSnapshot(val generatedAt: Long?, val providers: List<UsageProvider>) {
    companion object {
        fun parse(json: String): UsageSnapshot {
            val root = JSONObject(json)
            val providers = ArrayList<UsageProvider>()
            val providerArray = root.optJSONArray("providers")
            for (i in 0 until (providerArray?.length() ?: 0)) {
                val provider = providerArray?.optJSONObject(i) ?: continue
                val windows = ArrayList<UsageWindow>()
                val windowArray = provider.optJSONArray("windows")
                for (j in 0 until (windowArray?.length() ?: 0)) {
                    val window = windowArray?.optJSONObject(j) ?: continue
                    if (window.isNull("usedPercent")) continue
                    windows.add(
                        UsageWindow(
                            id = window.optString("id"),
                            kind = window.optString("kind"),
                            label = window.optString("label").ifBlank { window.optString("kind") }.ifBlank { "Limit" },
                            usedPercent = window.optInt("usedPercent").coerceIn(0, 100),
                            resetsAt = instant(window, "resetsAt"),
                        ),
                    )
                }
                if (windows.isEmpty()) continue
                val id = provider.optString("id")
                providers.add(
                    UsageProvider(
                        id = id,
                        name = provider.optString("name").ifBlank { id },
                        checkedAt = instant(provider, "checkedAt"),
                        windows = windows,
                    ),
                )
            }
            return UsageSnapshot(instant(root, "generatedAt"), providers)
        }

        private fun instant(obj: JSONObject, key: String): Long? {
            if (obj.isNull(key)) return null
            val text = obj.optString(key)
            if (text.isBlank()) return null
            return runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
        }
    }
}
