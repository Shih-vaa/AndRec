@file:Suppress("MagicNumber", "TooGenericExceptionCaught", "MaxLineLength")

package app.recovery.placeholder.spike.mediastore

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.util.Log
import android.view.WindowManager
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Spike 1. On launch (once the media permission is granted) enumerates MediaStore and hashes the
 * synthetic SpikeSynthetic files. Logs under tag SPIKE1 and writes files/spike1-result.json.
 * No file names or hashes are logged.
 */
class SpikeActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status =
            TextView(this).apply {
                text = "Spike 1 starting..."
                setPadding(48, 96, 48, 48)
            }
        setContentView(status)
        if (permissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            start()
        } else {
            requestPermissions(permissions(), REQUEST)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            start()
        } else {
            status.text = "Permission denied. Nothing to do."
            Log.i(TAG, "PERMISSION_DENIED")
        }
    }

    private fun permissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    private fun start() {
        status.text = "Spike 1 running..."
        Thread {
            val result =
                try {
                    if (intent.getStringExtra("mode") == "variants") variants() else collect()
                } catch (t: Throwable) {
                    JSONObject().put("fatal", "${t::class.java.simpleName}: ${t.message}")
                }
            File(filesDir, "spike1-result.json").writeText(result.toString(2))
            result.toString(2).lines().forEach { Log.i(TAG, it) }
            Log.i(TAG, "DONE")
            runOnUiThread { status.text = "Spike 1 done. See logcat tag $TAG." }
        }.start()
    }

    /** Short follow-up run: why is one-thread hashing slow? Separates CPU from the read path. */
    private fun variants(): JSONObject {
        val rows =
            MediaBench
                .enumerateSingleCursor(contentResolver)
                .synthetic
                .shuffled(java.util.Random(11))
                .take(600)
        val out = JSONArray()
        out.put(MediaBench.cpuOnly(256))
        out.put(MediaBench.readOnly(contentResolver, rows))
        out.put(MediaBench.hash(contentResolver, "hash via openInputStream, 1 thread", rows, 1))
        out.put(MediaBench.hashViaFd(contentResolver, rows))
        // Per-file open cost versus byte throughput: many small files against a few large ones.
        val all = MediaBench.enumerateSingleCursor(contentResolver).synthetic.shuffled(java.util.Random(13))
        out.put(MediaBench.readOnly(contentResolver, all.filter { it.size < 1_048_576 }.take(300)).put("subset", "small files"))
        out.put(MediaBench.readOnly(contentResolver, all.filter { it.size >= 1_048_576 }.take(60)).put("subset", "large files"))
        return JSONObject().put("variants", out)
    }

    private fun battery(): JSONObject {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return JSONObject()
            .put("levelPercent", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            .put("tempTenthsC", intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1)
            .put("pluggedFlag", intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1)
    }

    private fun collect(): JSONObject {
        val cr = contentResolver
        val before = battery()
        val startedAt = System.nanoTime()

        val enumeration = JSONArray()
        val (singleJson, single) = MediaBench.timeEnumeration("single-cursor", 3) { MediaBench.enumerateSingleCursor(cr) }
        enumeration.put(singleJson)
        val (batchJson, _) = MediaBench.timeEnumeration("keyset-batches-of-1000", 3) { MediaBench.enumerateBatched(cr) }
        enumeration.put(batchJson)

        // Disjoint thirds so each configuration reads files it has not read before.
        val rows = single.synthetic.shuffled(java.util.Random(7))
        val a = rows.filterIndexed { i, _ -> i % 3 == 0 }
        val b = rows.filterIndexed { i, _ -> i % 3 == 1 }
        val c = rows.filterIndexed { i, _ -> i % 3 == 2 }
        val hashing = JSONArray()
        hashing.put(MediaBench.hash(cr, "set A, first read", a, 1))
        hashing.put(MediaBench.hash(cr, "set B, first read", b, 2))
        hashing.put(MediaBench.hash(cr, "set C, first read", c, 4))
        hashing.put(MediaBench.hash(cr, "set A, second read (page cache likely warm)", a, 1))

        val mem = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        return JSONObject()
            .put(
                "device",
                JSONObject()
                    .put("model", Build.MODEL)
                    .put("sdk", Build.VERSION.SDK_INT)
                    .put("cores", Runtime.getRuntime().availableProcessors())
                    .put("javaHeapMaxMiB", Runtime.getRuntime().maxMemory() shr 20),
            ).put("batteryBefore", before)
            .put("batteryAfter", battery())
            .put("totalRunSeconds", (System.nanoTime() - startedAt) / 1_000_000_000)
            .put("syntheticFiles", rows.size)
            .put("syntheticMiB", rows.sumOf { it.size } / 1048576)
            .put("enumeration", enumeration)
            .put("hashing", hashing)
            .put("totalPssMiBAtEnd", mem.totalPss / 1024)
    }

    private companion object {
        const val TAG = "SPIKE1"
        const val REQUEST = 1
    }
}
