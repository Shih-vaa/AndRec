@file:Suppress("MagicNumber", "TooGenericExceptionCaught", "MaxLineLength")

package app.recovery.placeholder.spike.fgs

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Timestamped events, written to files/events.jsonl and mirrored to logcat tag SPIKE8. */
object EventLog {
    private const val TAG = "SPIKE8"
    private var file: File? = null

    @Synchronized
    fun init(context: Context) {
        if (file == null) file = File(context.filesDir, "events.jsonl")
    }

    @Synchronized
    fun reset() {
        file?.delete()
    }

    @Synchronized
    fun add(
        ev: String,
        vararg kv: Pair<String, Any?>,
        quiet: Boolean = false,
    ) {
        val j = JSONObject().put("t", System.currentTimeMillis()).put("up", SystemClock.elapsedRealtime()).put("ev", ev)
        kv.forEach { j.put(it.first, it.second ?: JSONObject.NULL) }
        file?.appendText(j.toString() + "\n")
        if (!quiet) Log.i(TAG, j.toString())
    }
}

/** Progress is persisted after every chunk, so a stop, timeout or kill loses at most the chunk in flight. */
object Progress {
    private fun prefs(context: Context) = context.getSharedPreferences("spike8", Context.MODE_PRIVATE)

    fun next(
        context: Context,
        key: String,
    ): Int = prefs(context).getInt(key, 0)

    fun save(
        context: Context,
        key: String,
        value: Int,
    ) {
        prefs(context).edit().putInt(key, value).commit()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().commit()
    }
}

/** The fake long job: hash 1 MiB, record progress, pause. Real CPU work, so a frozen process shows as a gap. */
class Chunker(
    private val context: Context,
    private val key: String,
    private val tickMs: Long = 200,
    private val total: Int = 1_000_000,
) {
    @Volatile var stopRequested = false
    private val buf = ByteArray(1 shl 20)
    private val md = MessageDigest.getInstance("SHA-256")

    fun run(label: String) {
        val start = Progress.next(context, key)
        EventLog.add("RESUME_FROM", "job" to label, "index" to start)
        var i = start
        while (!stopRequested && i < total) {
            md.update(buf)
            md.digest()
            i++
            Progress.save(context, key, i)
            EventLog.add("TICK", "job" to label, "i" to i, quiet = i % 25 != 0)
            try {
                Thread.sleep(tickMs)
            } catch (_: InterruptedException) {
                break
            }
        }
        EventLog.add("WORKER_STOPPED", "job" to label, "next" to i, "reason" to if (stopRequested) "stop requested" else "ended")
    }
}

/** The same job as a plain thread inside the app process: the baseline with no foreground service. */
object BareJob {
    private var thread: Thread? = null
    private var chunker: Chunker? = null

    @Synchronized
    fun start(context: Context) {
        if (thread?.isAlive == true) return
        val c = Chunker(context.applicationContext, "bare")
        chunker = c
        thread = Thread { c.run("bare") }.also { it.start() }
    }

    @Synchronized
    fun stop() {
        chunker?.stopRequested = true
        thread?.interrupt()
    }
}
