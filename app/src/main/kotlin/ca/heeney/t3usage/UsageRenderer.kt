package ca.heeney.t3usage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextUtils
import android.text.format.DateFormat
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws the widget as two alpha masks: [Layers.mono] for everything monochrome (the launcher tints
 * it black or white to match the system theme) and [Layers.red] for the Nothing-red accents.
 * Layout is specified in dp and scaled by a fit factor so 2x3 cells on any launcher grid fill nicely.
 */
class UsageRenderer(context: Context) {

    class Layers(val mono: Bitmap, val red: Bitmap)

    data class Status(val fetchedAt: Long, val error: String?, val errorAt: Long) {
        val failing: Boolean get() = error != null && errorAt >= fetchedAt
    }

    private val density = context.resources.displayMetrics.density
    private val is24h = DateFormat.is24HourFormat(context)
    private val zone: ZoneId = ZoneId.systemDefault()

    // Nothing OS ships its own typefaces in /system/fonts; fall back to the platform monospace elsewhere.
    val ndot: Typeface = systemFont("Ndot-57.otf") ?: Typeface.MONOSPACE
    val ndotCaps: Typeface = systemFont("NDot57Caps.otf") ?: ndot
    val mono: Typeface = systemFont("LetteraMonoLL-Regular.otf") ?: Typeface.MONOSPACE
    val monoMedium: Typeface = systemFont("LetteraMonoLL-Medium.otf") ?: mono
    val hasNothingFonts: Boolean = ndot !== Typeface.MONOSPACE && mono !== Typeface.MONOSPACE

    private class Plan(val k: Float, val providers: List<UsageProvider>, val dropped: Int)

    fun render(widthDp: Float, heightDp: Float, snapshot: UsageSnapshot?, status: Status, now: Long): Layers {
        val width = max(1, (widthDp * density).roundToInt())
        val height = max(1, (heightDp * density).roundToInt())
        val monoBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        val redBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        val monoCanvas = Canvas(monoBitmap)
        val redCanvas = Canvas(redBitmap)
        val pad = PAD * density
        val x0 = pad
        val x1 = width - pad

        if (snapshot == null || snapshot.providers.isEmpty()) {
            drawEmpty(monoCanvas, redCanvas, x0, x1, pad, status)
            return Layers(monoBitmap, redBitmap)
        }

        val plan = plan(widthDp, heightDp, snapshot)
        val k = plan.k
        fun d(value: Float) = value * k * density

        val headPaint = textPaint(ndotCaps, d(HEAD_TEXT), 1f)
        val pctPaint = textPaint(ndot, d(PCT_TEXT), 1f)
        val labelPaint = textPaint(monoMedium, d(LABEL_TEXT), SECONDARY)
        val smallPaint = textPaint(mono, d(SMALL_TEXT), SECONDARY)
        val dimDot = fill(DIM)
        val litDot = fill(1f)
        val sepDot = fill(SEPARATOR)

        var y = pad
        plan.providers.forEachIndexed { index, provider ->
            if (index > 0) {
                y += d(SEP_TOP)
                drawDotRow(monoCanvas, x0, x1, y, d(SEP_DOT), d(SEP_PITCH), sepDot)
                y += d(SEP_DOT) + d(SEP_BOTTOM)
            }
            // Provider header: red dot + name.
            val radius = d(HEAD_DOT) / 2f
            val headBaseline = y - headPaint.ascent()
            val headCenter = y + d(HEAD_TEXT) / 2f
            redCanvas.drawCircle(x0 + radius, headCenter, radius, litDot)
            val nameX = x0 + d(HEAD_DOT) + d(HEAD_DOT_GAP)
            monoCanvas.drawText(clip(provider.name.uppercase(), x1 - nameX, headPaint), nameX, headBaseline, headPaint)
            y += d(HEAD_TEXT) + d(HEAD_GAP)

            provider.windows.forEachIndexed { windowIndex, window ->
                val resetPassed = window.resetsAt != null && window.resetsAt <= now
                val used = if (resetPassed) 0 else window.usedPercent
                val hot = used >= HOT_PERCENT

                // Line 1: window label (left) and the big dot-matrix percentage (right), baseline aligned.
                val baseline = y - pctPaint.ascent()
                val pct = "$used%"
                val pctWidth = pctPaint.measureText(pct)
                (if (hot) redCanvas else monoCanvas).drawText(pct, x1 - pctWidth, baseline, pctPaint)
                val labelRoom = x1 - pctWidth - d(LABEL_GAP) - x0
                monoCanvas.drawText(clip(window.label.uppercase(), labelRoom, labelPaint), x0, baseline, labelPaint)
                y += d(LINE1_H) + d(LINE1_GAP)

                // Dot-matrix bar: used portion lit red, remainder dim.
                drawBar(monoCanvas, redCanvas, x0, x1, y, d(DOT), d(PITCH), d(ROW_GAP), used / 100f, dimDot, litDot)
                y += BAR_ROWS * d(DOT) + (BAR_ROWS - 1) * d(ROW_GAP) + d(BAR_GAP)

                // Line 3: reset countdown.
                val reset = "RESETS " + resetLabel(window.resetsAt, now)
                monoCanvas.drawText(clip(reset, x1 - x0, smallPaint), x0, y - smallPaint.ascent(), smallPaint)
                y += d(RESET_H)
                if (windowIndex < provider.windows.size - 1) y += d(WIN_GAP)
            }
        }

        // Footer: age of the data T3 last checked, plus hidden-row count and a red "!" when refreshes fail.
        val footTop = height - pad - d(FOOT_H)
        val checked = plan.providers.mapNotNull { it.checkedAt }.minOrNull()
        var footer = if (checked != null) "AS OF " + clock(checked) else "NO CHECK TIME"
        if (plan.dropped > 0) footer += " +${plan.dropped}"
        var footRoom = x1 - x0
        if (status.failing) {
            val bang = textPaint(ndot, d(FOOT_BANG), 1f)
            val bangWidth = bang.measureText("!")
            redCanvas.drawText("!", x1 - bangWidth, footTop - bang.ascent() - (d(FOOT_BANG) - d(FOOT_H)) / 2f, bang)
            footRoom -= bangWidth + d(LABEL_GAP)
        }
        monoCanvas.drawText(clip(footer, footRoom, smallPaint), x0, footTop - smallPaint.ascent(), smallPaint)

        return Layers(monoBitmap, redBitmap)
    }

    /** Composited ARGB preview (black background) for the settings screen and debugging. */
    fun composite(layers: Layers, foreground: Int = Color.WHITE, background: Int = Color.BLACK): Bitmap {
        val out = Bitmap.createBitmap(layers.mono.width, layers.mono.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(background)
        canvas.drawBitmap(layers.mono, 0f, 0f, Paint().apply { color = foreground })
        canvas.drawBitmap(layers.red, 0f, 0f, Paint().apply { color = NOTHING_RED })
        return out
    }

    fun describe(snapshot: UsageSnapshot?, now: Long): String {
        if (snapshot == null || snapshot.providers.isEmpty()) return "T3 usage: no data yet"
        return snapshot.providers.joinToString("; ") { provider ->
            provider.name + ": " + provider.windows.joinToString(", ") { window ->
                "${window.label} ${window.usedPercent}% used, resets ${resetLabel(window.resetsAt, now).lowercase()}"
            }
        }
    }

    // ---- layout -------------------------------------------------------------------------------

    private fun plan(widthDp: Float, heightDp: Float, snapshot: UsageSnapshot): Plan {
        val total = snapshot.providers.sumOf { it.windows.size }
        val availableHeight = heightDp - 2 * PAD - FOOT_H - FOOT_GAP
        val widthScale = ((widthDp - 2 * PAD) / DESIGN_CONTENT_W).coerceIn(MIN_K_WIDTH, MAX_K)
        var providers = snapshot.providers
        while (true) {
            val shown = providers.sumOf { it.windows.size }
            val k = min(widthScale, availableHeight / neededHeight(providers))
            if (k >= MIN_K || shown <= 1) {
                return Plan(k.coerceIn(MIN_K_WIDTH, MAX_K), providers, total - shown)
            }
            providers = dropLastWindow(providers)
        }
    }

    private fun neededHeight(providers: List<UsageProvider>): Float {
        var height = 0f
        providers.forEachIndexed { index, provider ->
            if (index > 0) height += SEP_TOP + SEP_DOT + SEP_BOTTOM
            height += HEAD_TEXT + HEAD_GAP
            height += provider.windows.size * WINDOW_H - WIN_GAP
        }
        return max(height, 1f)
    }

    private fun dropLastWindow(providers: List<UsageProvider>): List<UsageProvider> {
        val last = providers.last()
        val trimmed = last.copy(windows = last.windows.dropLast(1))
        return if (trimmed.windows.isEmpty()) providers.dropLast(1) else providers.dropLast(1) + trimmed
    }

    // ---- drawing helpers -----------------------------------------------------------------------

    private fun drawBar(
        monoCanvas: Canvas, redCanvas: Canvas, x0: Float, x1: Float, top: Float,
        dot: Float, pitch: Float, rowGap: Float, fraction: Float, dim: Paint, lit: Paint,
    ) {
        val count = max(2, ((x1 - x0 - dot) / pitch).toInt() + 1)
        val step = (x1 - x0 - dot) / (count - 1) // stretch so the last dot meets the right edge
        val filled = fraction.coerceIn(0f, 1f) * count
        val radius = dot / 2f
        val partial = Paint(lit)
        for (row in 0 until BAR_ROWS) {
            val cy = top + row * (dot + rowGap) + radius
            for (i in 0 until count) {
                val cx = x0 + i * step + radius
                when {
                    i + 1 <= filled -> redCanvas.drawCircle(cx, cy, radius, lit)
                    i < filled -> {
                        monoCanvas.drawCircle(cx, cy, radius, dim)
                        partial.alpha = ((filled - i) * 255).roundToInt().coerceIn(0, 255)
                        redCanvas.drawCircle(cx, cy, radius, partial)
                    }
                    else -> monoCanvas.drawCircle(cx, cy, radius, dim)
                }
            }
        }
    }

    private fun drawDotRow(canvas: Canvas, x0: Float, x1: Float, top: Float, dot: Float, pitch: Float, paint: Paint) {
        val count = max(2, ((x1 - x0 - dot) / pitch).toInt() + 1)
        val step = (x1 - x0 - dot) / (count - 1)
        val radius = dot / 2f
        for (i in 0 until count) canvas.drawCircle(x0 + i * step + radius, top + radius, radius, paint)
    }

    private fun drawEmpty(monoCanvas: Canvas, redCanvas: Canvas, x0: Float, x1: Float, pad: Float, status: Status) {
        val title = textPaint(ndotCaps, 14f * density, 1f)
        val body = textPaint(mono, 8f * density, SECONDARY)
        var y = pad
        redCanvas.drawCircle(x0 + 2.5f * density, y + 7f * density, 2.5f * density, fill(1f))
        monoCanvas.drawText("NO DATA", x0 + 11f * density, y - title.ascent(), title)
        y += 14f * density + 10f * density
        val message = status.error ?: "WAITING FOR FIRST REFRESH"
        for (line in wrap(message.uppercase(), x1 - x0, body).take(4)) {
            monoCanvas.drawText(line, x0, y - body.ascent(), body)
            y += 11f * density
        }
        y += 6f * density
        monoCanvas.drawText("TAP TO RETRY", x0, y - body.ascent(), body)
    }

    private fun wrap(text: String, room: Float, paint: Paint): List<String> {
        val lines = ArrayList<String>()
        var current = StringBuilder()
        for (word in text.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(candidate) <= room || current.isEmpty()) {
                current = StringBuilder(candidate)
            } else {
                lines.add(current.toString())
                current = StringBuilder(word)
            }
        }
        if (current.isNotEmpty()) lines.add(current.toString())
        return lines.map { clip(it, room, paint) }
    }

    private fun clip(text: String, room: Float, paint: Paint): String =
        TextUtils.ellipsize(text, android.text.TextPaint(paint), max(room, 0f), TextUtils.TruncateAt.END).toString()

    private fun textPaint(font: Typeface, size: Float, alpha: Float): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = font
            textSize = size
            color = Color.WHITE
            this.alpha = (alpha * 255).roundToInt()
        }

    private fun fill(alpha: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        this.alpha = (alpha * 255).roundToInt()
    }

    // ---- time formatting -----------------------------------------------------------------------

    fun clock(epochMs: Long): String =
        DateTimeFormatter.ofPattern(if (is24h) "H:mm" else "h:mm a", Locale.US)
            .format(Instant.ofEpochMilli(epochMs).atZone(zone)).uppercase()

    fun resetLabel(resetsAt: Long?, now: Long): String {
        if (resetsAt == null) return "--"
        val delta = resetsAt - now
        if (delta <= 0) return "RESET"
        if (delta < DAY_MS) {
            val minutes = delta / 60_000L
            val hours = minutes / 60
            val rest = minutes % 60
            return if (hours > 0) "${hours}H ${rest.toString().padStart(2, '0')}M" else "${max(rest, 1)}M"
        }
        val at = Instant.ofEpochMilli(resetsAt).atZone(zone)
        val pattern = if (delta < 6 * DAY_MS) (if (is24h) "EEE H:mm" else "EEE h:mm a") else "MMM d"
        return DateTimeFormatter.ofPattern(pattern, Locale.US).format(at).uppercase()
    }

    private fun systemFont(name: String): Typeface? = runCatching {
        val file = File("/system/fonts/$name")
        if (file.canRead()) Typeface.createFromFile(file) else null
    }.getOrNull()

    companion object {
        const val NOTHING_RED = 0xFFD71921.toInt()

        // Alpha of monochrome elements (relative to the launcher's foreground tint).
        private const val SECONDARY = 0.55f
        private const val DIM = 0.18f
        private const val SEPARATOR = 0.30f
        private const val HOT_PERCENT = 80

        // Layout in dp at scale 1; the design width is a 2-cell widget on a 5-column grid.
        private const val PAD = 14f
        private const val DESIGN_CONTENT_W = 132f
        private const val MIN_K = 0.85f
        private const val MIN_K_WIDTH = 0.6f
        private const val MAX_K = 1.25f
        private const val HEAD_TEXT = 11f
        private const val HEAD_DOT = 5f
        private const val HEAD_DOT_GAP = 6f
        private const val HEAD_GAP = 8f
        private const val PCT_TEXT = 18f
        private const val LABEL_TEXT = 8.5f
        private const val LABEL_GAP = 6f
        private const val SMALL_TEXT = 8f
        private const val LINE1_H = 18f
        private const val LINE1_GAP = 5f
        private const val DOT = 3f
        private const val PITCH = 5f
        private const val ROW_GAP = 2f
        private const val BAR_ROWS = 3
        private const val BAR_GAP = 5f
        private const val RESET_H = 10f
        private const val WIN_GAP = 9f
        private const val WINDOW_H = LINE1_H + LINE1_GAP + BAR_ROWS * DOT + (BAR_ROWS - 1) * ROW_GAP + BAR_GAP + RESET_H + WIN_GAP
        private const val SEP_TOP = 6f
        private const val SEP_DOT = 1.5f
        private const val SEP_PITCH = 4f
        private const val SEP_BOTTOM = 10f
        private const val FOOT_H = 10f
        private const val FOOT_GAP = 8f
        private const val FOOT_BANG = 12f
        private const val DAY_MS = 24 * 60 * 60_000L
    }
}
