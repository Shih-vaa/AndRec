@file:Suppress("MagicNumber", "TooGenericExceptionCaught", "MaxLineLength", "LongMethod", "NestedBlockDepth")

package app.recovery.placeholder.spike.mediastore

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.os.Bundle
import android.os.Debug
import android.os.Process
import android.provider.MediaStore
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** What the cheap fingerprint of SPEC 8.1 needs. Names are read but never kept or logged. */
class MediaRow(
    val uri: Uri,
    val size: Long,
)

class Enumeration(
    val total: Int,
    val synthetic: List<MediaRow>,
    val nameChars: Long,
)

object MediaBench {
    private const val SYNTHETIC_DIR = "SpikeSynthetic/"
    private const val BATCH = 1_000

    private val collections: List<Uri> by lazy {
        listOf(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
        )
    }

    private val projection =
        arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.RELATIVE_PATH,
        )

    /** One cursor over the whole collection. */
    fun enumerateSingleCursor(cr: ContentResolver): Enumeration {
        var total = 0
        var chars = 0L
        val synthetic = ArrayList<MediaRow>()
        for (collection in collections) {
            cr.query(collection, projection, null, null, "${MediaStore.MediaColumns._ID} ASC")?.use { c ->
                while (c.moveToNext()) {
                    total++
                    chars += c.getString(1)?.length ?: 0
                    c.getLong(3)
                    c.getString(4)
                    if (c.getString(5)?.contains(SYNTHETIC_DIR) == true) {
                        synthetic += MediaRow(ContentUris.withAppendedId(collection, c.getLong(0)), c.getLong(2))
                    }
                }
            }
        }
        return Enumeration(total, synthetic, chars)
    }

    /** Keyset pages of [BATCH] rows ordered by _id (SPEC 8.1 "batch by _ID"). */
    fun enumerateBatched(cr: ContentResolver): Enumeration {
        var total = 0
        var chars = 0L
        val synthetic = ArrayList<MediaRow>()
        for (collection in collections) {
            var lastId = -1L
            while (true) {
                val args =
                    Bundle().apply {
                        putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns._ID} > ?")
                        putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(lastId.toString()))
                        putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.MediaColumns._ID} ASC")
                        putInt(ContentResolver.QUERY_ARG_LIMIT, BATCH)
                    }
                var rows = 0
                cr.query(collection, projection, args, null)?.use { c ->
                    while (c.moveToNext()) {
                        rows++
                        total++
                        lastId = c.getLong(0)
                        chars += c.getString(1)?.length ?: 0
                        c.getLong(3)
                        c.getString(4)
                        if (c.getString(5)?.contains(SYNTHETIC_DIR) == true) {
                            synthetic += MediaRow(ContentUris.withAppendedId(collection, lastId), c.getLong(2))
                        }
                    }
                }
                if (rows < BATCH) break
            }
        }
        return Enumeration(total, synthetic, chars)
    }

    fun timeEnumeration(
        name: String,
        runs: Int,
        block: () -> Enumeration,
    ): Pair<JSONObject, Enumeration> {
        val ms = mutableListOf<Long>()
        var last: Enumeration? = null
        repeat(runs) {
            val t = System.nanoTime()
            last = block()
            ms += (System.nanoTime() - t) / 1_000_000
        }
        val e = last!!
        val json =
            JSONObject()
                .put("method", name)
                .put("rowsTotal", e.total)
                .put("rowsSynthetic", e.synthetic.size)
                .put("runsMs", ms.toString())
                .put("medianMs", ms.sorted()[ms.size / 2])
                .put("rowsPerSecondMedian", e.total * 1000.0 / ms.sorted()[ms.size / 2])
        return json to e
    }

    /** Hash [rows] with [threads] workers; returns timing, memory and error counts. */
    fun hash(
        cr: ContentResolver,
        label: String,
        rows: List<MediaRow>,
        threads: Int,
    ): JSONObject {
        val next = AtomicInteger(0)
        val bytes = AtomicLong(0)
        val errors = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
        val sampler = MemorySampler().also { it.start() }
        val start = System.nanoTime()
        val workers =
            List(threads) {
                Thread {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                    val buf = ByteArray(1 shl 20)
                    val md = MessageDigest.getInstance("SHA-256")
                    while (true) {
                        val i = next.getAndIncrement()
                        if (i >= rows.size) break
                        try {
                            cr.openInputStream(rows[i].uri)!!.use { input ->
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    md.update(buf, 0, n)
                                    bytes.addAndGet(n.toLong())
                                }
                            }
                            md.digest()
                        } catch (t: Throwable) {
                            errors.getOrPut(t::class.java.simpleName) { AtomicInteger() }.incrementAndGet()
                        }
                    }
                }.also { it.start() }
            }
        workers.forEach { it.join() }
        val ms = (System.nanoTime() - start) / 1_000_000
        sampler.stopAndJoin()
        val mib = bytes.get() / 1048576.0
        return JSONObject()
            .put("label", label)
            .put("threads", threads)
            .put("files", rows.size)
            .put("MiB", mib)
            .put("ms", ms)
            .put("MiBps", mib * 1000 / ms)
            .put("filesPerSecond", rows.size * 1000.0 / ms)
            .put("peakJavaHeapMiB", sampler.peakJava shr 20)
            .put("peakNativeHeapMiB", sampler.peakNative shr 20)
            .put("errors", JSONObject(errors.mapValues { it.value.get() }))
    }

    /** SHA-256 over an in-memory buffer: the CPU ceiling with no file I/O at all. */
    fun cpuOnly(mib: Int): JSONObject {
        val buf = ByteArray(1 shl 20)
        val md = MessageDigest.getInstance("SHA-256")
        val start = System.nanoTime()
        repeat(mib) { md.update(buf) }
        md.digest()
        val ms = (System.nanoTime() - start) / 1_000_000
        return JSONObject()
            .put("label", "cpu-only sha256, no I/O")
            .put("MiB", mib)
            .put("ms", ms)
            .put("MiBps", mib * 1000.0 / ms)
    }

    /** Read every byte through openInputStream with no hashing: the I/O ceiling of that path. */
    fun readOnly(
        cr: ContentResolver,
        rows: List<MediaRow>,
    ): JSONObject =
        timed("read only via openInputStream, 1 thread", rows) { r, buf, _ ->
            var n = 0L
            cr.openInputStream(r.uri)!!.use { input ->
                while (true) {
                    val k = input.read(buf)
                    if (k < 0) break
                    n += k
                }
            }
            n
        }

    /** Hash through a ParcelFileDescriptor and FileInputStream instead of openInputStream. */
    fun hashViaFd(
        cr: ContentResolver,
        rows: List<MediaRow>,
    ): JSONObject =
        timed("hash via openFileDescriptor, 1 thread", rows) { r, buf, md ->
            var n = 0L
            cr.openFileDescriptor(r.uri, "r")!!.use { pfd ->
                java.io.FileInputStream(pfd.fileDescriptor).use { input ->
                    while (true) {
                        val k = input.read(buf)
                        if (k < 0) break
                        md.update(buf, 0, k)
                        n += k
                    }
                }
            }
            n
        }

    private fun timed(
        label: String,
        rows: List<MediaRow>,
        one: (MediaRow, ByteArray, MessageDigest) -> Long,
    ): JSONObject {
        val buf = ByteArray(1 shl 20)
        val md = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        val start = System.nanoTime()
        rows.forEach {
            bytes += one(it, buf, md)
            md.digest()
        }
        val ms = (System.nanoTime() - start) / 1_000_000
        val mib = bytes / 1048576.0
        return JSONObject()
            .put("label", label)
            .put("files", rows.size)
            .put("MiB", mib)
            .put("ms", ms)
            .put("MiBps", mib * 1000 / ms)
    }

    private class MemorySampler : Thread() {
        @Volatile var peakJava = 0L

        @Volatile var peakNative = 0L

        @Volatile private var running = true
        private val rt = Runtime.getRuntime()

        override fun run() {
            while (running) {
                peakJava = maxOf(peakJava, rt.totalMemory() - rt.freeMemory())
                peakNative = maxOf(peakNative, Debug.getNativeHeapAllocatedSize())
                try {
                    sleep(100)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }

        fun stopAndJoin() {
            running = false
            join()
        }
    }
}
