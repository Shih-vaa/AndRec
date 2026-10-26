@file:Suppress("MagicNumber", "TooGenericExceptionCaught", "MaxLineLength")

package app.recovery.placeholder.spike.fgs

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.TextView

/**
 * Spike 8 control surface, driven by adb: am start -n .../.SpikeActivity --es mode <reset|service|bare|stop|note> [--es timeoutMode stop|ignore].
 * Every resume and pause is logged so background time can be lined up with service events.
 */
class SpikeActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EventLog.init(this)
        status = TextView(this).apply { setPadding(48, 96, 48, 48) }
        setContentView(status)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        EventLog.add("ACTIVITY_RESUMED")
    }

    override fun onPause() {
        EventLog.add("ACTIVITY_PAUSED")
        super.onPause()
    }

    private fun handle(intent: Intent) {
        val mode = intent.getStringExtra("mode") ?: return
        EventLog.add("COMMAND", "mode" to mode)
        when (mode) {
            "reset" -> {
                stopService(Intent(this, SimJobService::class.java))
                BareJob.stop()
                Progress.clear(this)
                EventLog.reset()
            }
            "service" ->
                try {
                    startForegroundService(
                        Intent(this, SimJobService::class.java).putExtra(
                            SimJobService.EXTRA_TIMEOUT_MODE,
                            intent.getStringExtra("timeoutMode") ?: "stop",
                        ),
                    )
                } catch (t: Throwable) {
                    EventLog.add("ACTIVITY_START_REFUSED", "error" to "${t::class.java.simpleName}: ${t.message?.take(240)}")
                }
            "bare" -> BareJob.start(this)
            "stop" -> {
                stopService(Intent(this, SimJobService::class.java))
                BareJob.stop()
            }
            "note" -> EventLog.add("NOTE", "service" to Progress.next(this, "service"), "bare" to Progress.next(this, "bare"))
        }
        status.text = "Last command: $mode"
    }
}
