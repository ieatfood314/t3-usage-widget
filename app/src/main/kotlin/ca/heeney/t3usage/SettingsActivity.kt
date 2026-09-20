package ca.heeney.t3usage

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.InputType
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Minimal settings/diagnostics screen: server URL, manual refresh, last fetch status. */
class SettingsActivity : Activity() {
    private lateinit var store: UsageStore
    private lateinit var status: TextView
    private lateinit var renderer: UsageRenderer
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> showStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = UsageStore(this)
        renderer = UsageRenderer(this)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            val pad = dp(20)
            setPadding(pad, dp(28), pad, pad)
        }
        column.addView(label("T3 USAGE", renderer.ndotCaps, 26f, Color.WHITE))
        column.addView(label("NAS USAGE.JSON URL", renderer.monoMedium, 11f, GREY).apply { setPadding(0, dp(24), 0, dp(6)) })

        val urlField = EditText(this).apply {
            setText(store.url)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            typeface = renderer.mono
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.WHITE)
            setSingleLine()
        }
        column.addView(urlField)

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(button("SAVE") {
            store.url = urlField.text.toString()
            RefreshJob.fetchNow(this)
        })
        buttons.addView(button("REFRESH NOW") { RefreshJob.fetchNow(this) })
        buttons.addView(button("PREVIEW") { exportPreview(160f, 280f) })
        column.addView(buttons)

        status = label("", renderer.mono, 12f, GREY).apply { setPadding(0, dp(20), 0, 0) }
        column.addView(status)
        column.addView(
            label(
                "Add the 2x3 “T3 Usage” widget from your launcher. Tap the widget to open T3 Code's usage " +
                    "screen; tap its bottom edge to refresh in place. A red “!” in the footer means the last " +
                    "refresh failed (check Tailscale).",
                renderer.mono, 11f, GREY,
            ).apply { setPadding(0, dp(24), 0, 0) },
        )

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })

        if (savedInstanceState == null) handleDebugExtras(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDebugExtras(intent)
    }

    /** adb hooks: am start -n ca.heeney.t3usage/.SettingsActivity --activity-clear-task --ez refresh true --ei preview_w 160 --ei preview_h 280 */
    private fun handleDebugExtras(intent: Intent) {
        if (intent.getBooleanExtra("refresh", false)) RefreshJob.fetchNow(this)
        val previewWidth = intent.getIntExtra("preview_w", 0)
        val previewHeight = intent.getIntExtra("preview_h", 0)
        if (previewWidth > 0 && previewHeight > 0) exportPreview(previewWidth.toFloat(), previewHeight.toFloat())
    }

    override fun onResume() {
        super.onResume()
        store.prefs.registerOnSharedPreferenceChangeListener(listener)
        showStatus()
    }

    override fun onPause() {
        store.prefs.unregisterOnSharedPreferenceChangeListener(listener)
        super.onPause()
    }

    private fun showStatus() {
        val lines = ArrayList<String>()
        lines += "LAST OK: " + (if (store.fetchedAt > 0) "${clock(store.fetchedAt)} (${ago(store.fetchedAt)})" else "NEVER")
        store.lastError?.let { lines += "LAST ERROR (${clock(store.lastErrorAt)}): $it" }
        lines += "WIDGET SIZES: " + (store.lastSizes ?: "none placed yet")
        lines += "NOTHING FONTS: " + if (renderer.hasNothingFonts) "YES" else "NO (fallback monospace)"
        val snapshot = store.snapshot()
        if (snapshot != null) {
            lines += ""
            snapshot.providers.forEach { provider ->
                lines += provider.name.uppercase() + " (checked " + (provider.checkedAt?.let { clock(it) } ?: "?") + ")"
                provider.windows.forEach { window ->
                    lines += "  ${window.label}: ${window.usedPercent}% used, resets " +
                        renderer.resetLabel(window.resetsAt, System.currentTimeMillis())
                }
            }
        }
        status.text = lines.joinToString("\n")
    }

    private fun exportPreview(widthDp: Float, heightDp: Float) {
        val snapshot = store.snapshot()
        val fetchStatus = UsageRenderer.Status(store.fetchedAt, store.lastError, store.lastErrorAt)
        val bitmap = renderer.composite(renderer.render(widthDp, heightDp, snapshot, fetchStatus, System.currentTimeMillis()))
        val name = "t3usage-preview-${widthDp.toInt()}x${heightDp.toInt()}-${System.currentTimeMillis() / 1000}.png"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "image/png")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            status.text = "PREVIEW: could not create file"
            return
        }
        contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        status.text = "PREVIEW: Download/$name\n\n" + status.text
    }

    private fun label(text: String, font: android.graphics.Typeface, sizeSp: Float, color: Int): TextView =
        TextView(this).apply {
            this.text = text
            typeface = font
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
        }

    private fun button(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        typeface = renderer.monoMedium
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.TRANSPARENT)
        setOnClickListener { onClick() }
    }

    private fun clock(epochMs: Long): String =
        DateTimeFormatter.ofPattern("HH:mm:ss").format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

    private fun ago(epochMs: Long): String {
        val minutes = (System.currentTimeMillis() - epochMs) / 60_000L
        return if (minutes < 1) "just now" else if (minutes < 60) "${minutes}m ago" else "${minutes / 60}h ${minutes % 60}m ago"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val GREY = 0xFF8C8C8C.toInt()
    }
}
