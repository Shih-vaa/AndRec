@file:Suppress("MagicNumber", "TooGenericExceptionCaught", "MaxLineLength", "DEPRECATION")

package app.recovery.placeholder.spike.saf

import android.app.Activity
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Spike 2. "Pick folder" stores a persisted tree grant. "Run traversal" lists the tree, compares the bulk and
 * per-property methods and times opening files. "Check grant" reports whether the grant survived restart,
 * rename or reboot. adb can start a run with: am start -n .../.SpikeActivity --es mode traverse|check.
 * Logs under tag SPIKE2, no file names or contents.
 */
class SpikeActivity : Activity() {
    private lateinit var status: TextView
    private val prefs by lazy { getSharedPreferences("spike2", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status = TextView(this).apply { text = "Spike 2: ${savedTree()?.let { "folder saved" } ?: "no folder picked yet"}" }
        val pick =
            Button(this).apply {
                text = "Pick folder"
                setOnClickListener { pickFolder() }
            }
        val run =
            Button(this).apply {
                text = "Run traversal"
                setOnClickListener { launch("traverse") }
            }
        val check =
            Button(this).apply {
                text = "Check grant"
                setOnClickListener { launch("check") }
            }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 96, 48, 48)
                addView(status)
                addView(pick)
                addView(run)
                addView(check)
            },
        )
        intent.getStringExtra("mode")?.let { launch(it) }
    }

    private fun savedTree(): Uri? = prefs.getString("tree", null)?.let { Uri.parse(it) }

    private fun pickFolder() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PICK)
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != PICK || resultCode != RESULT_OK || uri == null) {
            Log.i(TAG, "PICK_CANCELLED")
            return
        }
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        prefs.edit().putString("tree", uri.toString()).apply()
        status.text = "Folder saved."
        Log.i(TAG, "PICKED " + JSONObject().put("grantsAfterPick", contentResolver.persistedUriPermissions.size))
    }

    private fun launch(mode: String) {
        status.text = "Running $mode..."
        Thread {
            val result =
                try {
                    when (mode) {
                        "traverse" -> traverse()
                        "bulk1" -> bulkOnce()
                        else -> JSONObject().put("check", SafBench.check(contentResolver, savedTree())).put("env", env())
                    }
                } catch (t: Throwable) {
                    JSONObject().put("fatal", "${t::class.java.simpleName}: ${t.message}")
                }
            result.put("mode", mode)
            File(filesDir, "spike2-$mode.json").writeText(result.toString(2))
            result.toString(2).lines().forEach { Log.i(TAG, it) }
            Log.i(TAG, "DONE $mode")
            runOnUiThread { status.text = "$mode done. See logcat tag $TAG." }
        }.start()
    }

    private fun env(): JSONObject =
        JSONObject()
            .put("bootCount", Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1))
            .put("uptimeMinutes", SystemClock.elapsedRealtime() / 60000)
            .put("model", Build.MODEL)
            .put("sdk", Build.VERSION.SDK_INT)

    private fun battery(): JSONObject {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return JSONObject()
            .put("levelPercent", intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1)
            .put("tempTenthsC", intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1)
    }

    /** One bulk pass with progress logged every 20 directories, to see where time goes. */
    private fun bulkOnce(): JSONObject {
        val tree = savedTree() ?: return JSONObject().put("fatal", "no folder picked")
        val t = System.nanoTime()
        val l = SafBench.bulk(contentResolver, tree) { d, f, ms -> Log.i(TAG, "PROGRESS dirs=$d files=$f ms=$ms") }
        val ms = (System.nanoTime() - t) / 1_000_000
        return JSONObject().put("env", env()).put("files", l.files.size).put("dirs", l.dirs).put("ms", ms).put(
            "filesPerSecond",
            l.files.size * 1000.0 / ms,
        )
    }

    private fun traverse(): JSONObject {
        val tree = savedTree() ?: return JSONObject().put("fatal", "no folder picked")
        val cr = contentResolver
        val before = battery()
        val rt = Runtime.getRuntime()
        var peakHeap = 0L
        val sampler =
            Thread {
                while (!Thread.currentThread().isInterrupted) {
                    peakHeap = maxOf(peakHeap, rt.totalMemory() - rt.freeMemory())
                    try {
                        Thread.sleep(100)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                }
            }.also { it.start() }

        val bulkRuns = JSONArray()
        var last: Listing? = null
        repeat(1) {
            val t = System.nanoTime()
            val l = SafBench.bulk(cr, tree)
            val ms = (System.nanoTime() - t) / 1_000_000
            bulkRuns.put(
                JSONObject().put("ms", ms).put("files", l.files.size).put("dirs", l.dirs).put(
                    "filesPerSecond",
                    l.files.size * 1000.0 / ms,
                ),
            )
            last = l
        }
        val listing = last!!
        val perProp = SafBench.perProperty(cr, tree, 2000)

        val shuffled = listing.files.shuffled(java.util.Random(5))
        val open1 = SafBench.openRead(cr, tree, shuffled.take(1000), 1, "open+read 1 thread")
        val open4 = SafBench.openRead(cr, tree, shuffled.drop(1000).take(1000), 4, "open+read 4 threads")

        sampler.interrupt()
        sampler.join()
        return JSONObject()
            .put("env", env())
            .put("batteryBefore", before)
            .put("batteryAfter", battery())
            .put("peakJavaHeapMiB", peakHeap shr 20)
            .put("javaHeapMaxMiB", rt.maxMemory() shr 20)
            .put("totalPssMiB", Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss / 1024)
            .put("bulkRuns", bulkRuns)
            .put("perProperty", perProp)
            .put("openRead", JSONArray().put(open1).put(open4))
            .put("check", SafBench.check(cr, tree))
    }

    private companion object {
        const val TAG = "SPIKE2"
        const val PICK = 2
    }
}
