@file:Suppress("MagicNumber", "TooGenericExceptionCaught", "MaxLineLength")

package app.recovery.placeholder.spike.fgs

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/** dataSync foreground service running the fake job. EXTRA_TIMEOUT_MODE decides what onTimeout does: "stop" or "ignore". */
class SimJobService : Service() {
    private var chunker: Chunker? = null
    private var worker: Thread? = null
    private var timeoutMode = "stop"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        EventLog.init(this)
        timeoutMode = intent?.getStringExtra(EXTRA_TIMEOUT_MODE) ?: "stop"
        EventLog.add("SERVICE_START", "startId" to startId, "timeoutMode" to timeoutMode)
        try {
            startForeground(NOTIF_ID, notification("Spike 8 job running"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (t: Throwable) {
            EventLog.add("START_FOREGROUND_FAILED", "error" to "${t::class.java.simpleName}: ${t.message?.take(200)}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (worker?.isAlive != true) {
            val c = Chunker(applicationContext, "service")
            chunker = c
            worker = Thread { c.run("service") }.also { it.start() }
        }
        return START_NOT_STICKY
    }

    /** Android 15+: called when the dataSync time limit is reached. The service must stop within a few seconds. */
    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        EventLog.add("ON_TIMEOUT", "startId" to startId, "fgsType" to fgsType, "mode" to timeoutMode)
        if (timeoutMode == "ignore") {
            EventLog.add("TIMEOUT_IGNORED_ON_PURPOSE")
            return
        }
        chunker?.stopRequested = true
        postResumeNotification()
        stopSelf()
        EventLog.add("STOP_SELF_CALLED")
    }

    override fun onDestroy() {
        chunker?.stopRequested = true
        EventLog.add("SERVICE_DESTROYED")
        super.onDestroy()
    }

    private fun channel(): String {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Spike 8", NotificationManager.IMPORTANCE_LOW))
        return CHANNEL
    }

    private fun notification(text: String): Notification =
        Notification
            .Builder(
                this,
                channel(),
            ).setContentTitle("Spike 8")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .build()

    /** What a real app would do: tell the user the work paused and let a tap bring the app to the foreground. */
    private fun postResumeNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        val open =
            PendingIntent.getActivity(
                this,
                1,
                Intent(this, SpikeActivity::class.java).putExtra("mode", "service").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val n =
            Notification
                .Builder(
                    this,
                    channel(),
                ).setContentTitle(
                    "Backup paused",
                ).setContentText("Tap to continue")
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentIntent(open)
                .build()
        nm.notify(RESUME_NOTIF_ID, n)
        EventLog.add("RESUME_NOTIFICATION_POSTED", "notificationsEnabled" to nm.areNotificationsEnabled())
    }

    companion object {
        const val EXTRA_TIMEOUT_MODE = "timeoutMode"
        private const val CHANNEL = "spike8"
        private const val NOTIF_ID = 8
        private const val RESUME_NOTIF_ID = 9
    }
}

/** Starts the service from wherever the broadcast lands, normally while the app is in the background. */
class StartReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        EventLog.init(context)
        EventLog.add("RECEIVER_START_ATTEMPT")
        try {
            context.startForegroundService(
                Intent(context, SimJobService::class.java).putExtra(
                    SimJobService.EXTRA_TIMEOUT_MODE,
                    intent.getStringExtra("timeoutMode") ?: "stop",
                ),
            )
            EventLog.add("RECEIVER_START_ACCEPTED")
        } catch (t: Throwable) {
            EventLog.add("RECEIVER_START_REFUSED", "error" to "${t::class.java.simpleName}: ${t.message?.take(240)}")
        }
    }
}
