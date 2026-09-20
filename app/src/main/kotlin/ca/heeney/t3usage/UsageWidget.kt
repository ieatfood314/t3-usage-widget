package ca.heeney.t3usage

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews

class UsageWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(context, manager, it) }
        RefreshJob.ensurePeriodic(context)
        RefreshJob.fetchSoon(context, 0L)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        render(context, manager, id)
    }

    override fun onEnabled(context: Context) {
        RefreshJob.ensurePeriodic(context)
    }

    override fun onDisabled(context: Context) {
        RefreshJob.cancelAll(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) RefreshJob.fetchNow(context)
    }

    companion object {
        const val ACTION_REFRESH = "ca.heeney.t3usage.REFRESH"
        private const val T3_PACKAGE = "com.t3tools.t3code"
        private const val T3_USAGE_DEEP_LINK = "t3code://settings/usage?tab=limits"

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            manager.getAppWidgetIds(ComponentName(context, UsageWidget::class.java))
                .forEach { render(context, manager, it) }
        }

        fun render(context: Context, manager: AppWidgetManager, id: Int) {
            val store = UsageStore(context)
            val snapshot = store.snapshot()
            val status = UsageRenderer.Status(store.fetchedAt, store.lastError, store.lastErrorAt)
            val renderer = UsageRenderer(context)
            val options = manager.getAppWidgetOptions(id)
            val sizes = sizesFrom(options)
            val views = if (sizes.isNotEmpty()) {
                RemoteViews(sizes.associateWith { build(context, renderer, id, it.width, it.height, snapshot, status) })
            } else {
                val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 160)
                val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 280)
                build(context, renderer, id, width.toFloat(), height.toFloat(), snapshot, status)
            }
            store.saveSizes(sizes.joinToString(" | ") { "${it.width.toInt()}x${it.height.toInt()}dp" }.ifEmpty { "legacy" })
            manager.updateAppWidget(id, views)
        }

        private fun build(
            context: Context, renderer: UsageRenderer, id: Int,
            widthDp: Float, heightDp: Float, snapshot: UsageSnapshot?, status: UsageRenderer.Status,
        ): RemoteViews {
            val now = System.currentTimeMillis()
            val layers = renderer.render(widthDp, heightDp, snapshot, status, now)
            return RemoteViews(context.packageName, R.layout.usage_widget).apply {
                setImageViewBitmap(R.id.layer_mono, layers.mono)
                setImageViewBitmap(R.id.layer_red, layers.red)
                setContentDescription(R.id.root, renderer.describe(snapshot, now))
                setOnClickPendingIntent(R.id.root, openIntent(context, id))
                setOnClickPendingIntent(R.id.footer_tap, refreshIntent(context))
            }
        }

        private fun sizesFrom(options: Bundle): List<SizeF> {
            @Suppress("DEPRECATION")
            val sizes: List<SizeF>? = if (Build.VERSION.SDK_INT >= 33) {
                options.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java)
            } else {
                options.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES)
            }
            return sizes.orEmpty().filter { it.width > 0f && it.height > 0f }.distinct().take(16)
        }

        private fun openIntent(context: Context, id: Int): PendingIntent {
            val intent = Intent(context, OpenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return PendingIntent.getActivity(
                context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun refreshIntent(context: Context): PendingIntent {
            val intent = Intent(context, UsageWidget::class.java).setAction(ACTION_REFRESH)
            return PendingIntent.getBroadcast(
                context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /** T3 Code's usage screen, the same deep link its own widget opens. */
        fun t3UsageIntent(context: Context): Intent? =
            context.packageManager.getLaunchIntentForPackage(T3_PACKAGE)?.apply {
                action = Intent.ACTION_VIEW
                data = android.net.Uri.parse(T3_USAGE_DEEP_LINK)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
    }
}
