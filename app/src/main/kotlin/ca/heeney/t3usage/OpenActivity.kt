package ca.heeney.t3usage

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Invisible trampoline for widget taps: kick off refreshes, then open T3 Code's usage screen. */
class OpenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RefreshJob.fetchNow(this)
        // T3 Code re-probes its providers once it is in the foreground; pick that up shortly after.
        RefreshJob.fetchSoon(this, 45_000L)
        val target = UsageWidget.t3UsageIntent(this)
            ?: Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(target) }
        finish()
    }
}
