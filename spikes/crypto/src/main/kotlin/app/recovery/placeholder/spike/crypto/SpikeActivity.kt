@file:Suppress("MagicNumber", "TooGenericExceptionCaught", "MaxLineLength")

package app.recovery.placeholder.spike.crypto

import android.app.Activity
import android.app.ActivityManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Runs spike 3 once on launch, logs under tag SPIKE3 and writes files/spike3-result.json. */
class SpikeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status =
            TextView(this).apply {
                text = "Spike 3 running..."
                setPadding(48, 96, 48, 48)
            }
        setContentView(status)
        Thread {
            val result =
                try {
                    collect()
                } catch (t: Throwable) {
                    JSONObject().put("fatal", "${t::class.java.simpleName}: ${t.message}")
                }
            File(filesDir, "spike3-result.json").writeText(result.toString(2))
            result.toString(2).lines().forEach { Log.i(TAG, it) }
            Log.i(TAG, "DONE")
            runOnUiThread { status.text = "Spike 3 done. See logcat tag $TAG." }
        }.start()
    }

    private fun collect(): JSONObject {
        val bc = BouncyCastleArgon2()
        val native = Argon2KtImpl()
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val device =
            JSONObject()
                .put("model", Build.MODEL)
                .put("sdk", Build.VERSION.SDK_INT)
                .put("abis", Build.SUPPORTED_ABIS.joinToString())
                .put("cores", Runtime.getRuntime().availableProcessors())
                .put("totalRamMiB", mem.totalMem shr 20)
                .put("memoryClassMiB", am.memoryClass)
                .put("largeMemoryClassMiB", am.largeMemoryClass)
                .put("javaHeapMaxMiB", Runtime.getRuntime().maxMemory() shr 20)
                .put("isEmulator", Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu"))

        val checks =
            JSONObject()
                .put("rfc9106VectorBouncyCastle", Argon2Bench.rfcVectorPasses())
                .put("bouncyCastleAgreesWithArgon2kt", Argon2Bench.agree(bc, native))

        val argon = JSONArray()
        (Argon2Bench.run(native) + Argon2Bench.run(bc)).forEach { argon.put(it) }

        val tink = JSONArray()
        // First pass warms the JIT, so only the second pass of each template is comparable.
        listOf("AES256_GCM_HKDF_4KB", "AES256_GCM_HKDF_1MB", "AES256_GCM_HKDF_4KB", "AES256_GCM_HKDF_1MB")
            .forEachIndexed { i, t -> tink.put(TinkBench.run(cacheDir, t).put("pass", i / 2 + 1)) }

        return JSONObject()
            .put("device", device)
            .put("checks", checks)
            .put("argon2id", argon)
            .put("tinkStreamingAead", tink)
    }

    private companion object {
        const val TAG = "SPIKE3"
    }
}
