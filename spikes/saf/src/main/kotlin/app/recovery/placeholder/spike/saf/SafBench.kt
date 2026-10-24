@file:Suppress("MagicNumber", "TooGenericExceptionCaught", "MaxLineLength", "LongMethod", "NestedBlockDepth", "CyclomaticComplexMethod")

package app.recovery.placeholder.spike.saf

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** What the cheap fingerprint needs from a SAF file. Names are read but not kept or logged. */
class DocRow(
    val docId: String,
    val size: Long,
    val mtime: Long,
)

class Listing(
    val files: List<DocRow>,
    val dirs: Int,
    val nameChars: Long,
)

object SafBench {
    private val bulkProjection =
        arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
        )

    private fun isDir(mime: String?) = mime == DocumentsContract.Document.MIME_TYPE_DIR

    fun rootId(tree: Uri): String = DocumentsContract.getTreeDocumentId(tree)

    /** One query per directory, all columns read in the same pass. This is the method SPEC 8.1 proposes. */
    fun bulk(
        cr: ContentResolver,
        tree: Uri,
        onDir: ((dirs: Int, files: Int, ms: Long) -> Unit)? = null,
    ): Listing {
        val t0 = System.nanoTime()
        val files = ArrayList<DocRow>()
        var dirs = 0
        var chars = 0L
        val stack = ArrayDeque<String>().apply { add(rootId(tree)) }
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
            cr.query(children, bulkProjection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    chars += c.getString(1)?.length ?: 0
                    if (isDir(c.getString(2))) {
                        dirs++
                        stack.add(c.getString(0))
                        if (dirs % 20 == 0) onDir?.invoke(dirs, files.size, (System.nanoTime() - t0) / 1_000_000)
                    } else {
                        files += DocRow(c.getString(0), c.getLong(3), c.getLong(4))
                    }
                }
            }
        }
        return Listing(files, dirs, chars)
    }

    private fun one(
        cr: ContentResolver,
        doc: Uri,
        column: String,
    ): Cursor? = cr.query(doc, arrayOf(column), null, null, null)

    /**
     * My reconstruction of how androidx DocumentFile behaves (the library source was not read): list children by id
     * only, then one separate query per property per child (type, name, size, modified). Capped at [limit] files
     * because it would take far too long on the full set.
     */
    fun perProperty(
        cr: ContentResolver,
        tree: Uri,
        limit: Int,
    ): JSONObject {
        val start = System.nanoTime()
        var files = 0
        var queries = 0
        val stack = ArrayDeque<String>().apply { add(rootId(tree)) }
        while (stack.isNotEmpty() && files < limit) {
            val id = stack.removeLast()
            val ids = ArrayList<String>()
            cr
                .query(
                    DocumentsContract.buildChildDocumentsUriUsingTree(tree, id),
                    arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                    null,
                    null,
                    null,
                )?.use { c -> while (c.moveToNext()) ids += c.getString(0) }
            queries++
            for (child in ids) {
                if (files >= limit) break
                val doc = DocumentsContract.buildDocumentUriUsingTree(tree, child)
                val mime =
                    one(
                        cr,
                        doc,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                    )?.use { if (it.moveToFirst()) it.getString(0) else null }
                one(cr, doc, DocumentsContract.Document.COLUMN_DISPLAY_NAME)?.use { it.moveToFirst() }
                queries += 2
                if (isDir(mime)) {
                    stack.add(child)
                } else {
                    one(cr, doc, DocumentsContract.Document.COLUMN_SIZE)?.use { it.moveToFirst() }
                    one(cr, doc, DocumentsContract.Document.COLUMN_LAST_MODIFIED)?.use { it.moveToFirst() }
                    queries += 2
                    files++
                }
            }
        }
        val ms = (System.nanoTime() - start) / 1_000_000
        return JSONObject()
            .put("method", "per-property queries (DocumentFile-style, hand simulation)")
            .put("files", files)
            .put("queries", queries)
            .put("ms", ms)
            .put("filesPerSecond", files * 1000.0 / ms)
    }

    /** Open and read [rows] through the tree grant with [threads] workers. */
    fun openRead(
        cr: ContentResolver,
        tree: Uri,
        rows: List<DocRow>,
        threads: Int,
        label: String,
    ): JSONObject {
        val next = AtomicInteger(0)
        val bytes = AtomicLong(0)
        val errors = ConcurrentHashMap<String, AtomicInteger>()
        val start = System.nanoTime()
        List(threads) {
            Thread {
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val i = next.getAndIncrement()
                    if (i >= rows.size) break
                    try {
                        cr.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, rows[i].docId))!!.use { input ->
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                bytes.addAndGet(n.toLong())
                            }
                        }
                    } catch (t: Throwable) {
                        errors.getOrPut(t::class.java.simpleName) { AtomicInteger() }.incrementAndGet()
                    }
                }
            }.also { it.start() }
        }.forEach { it.join() }
        val ms = (System.nanoTime() - start) / 1_000_000
        return JSONObject()
            .put("label", label)
            .put("threads", threads)
            .put("files", rows.size)
            .put("KiB", bytes.get() / 1024)
            .put("ms", ms)
            .put("filesPerSecond", rows.size * 1000.0 / ms)
            .put("errors", JSONObject(errors.mapValues { it.value.get() }))
    }

    /** Light check used after restart, rename and reboot: is the grant listed, and does a listing still work? */
    fun check(
        cr: ContentResolver,
        tree: Uri?,
    ): JSONObject {
        val grants = cr.persistedUriPermissions
        val json =
            JSONObject()
                .put("persistedGrantCount", grants.size)
                .put("savedUriStillListed", tree != null && grants.any { it.uri == tree })
                .put("savedUriReadFlag", grants.firstOrNull { it.uri == tree }?.isReadPermission ?: false)
                .put("savedUriWriteFlag", grants.firstOrNull { it.uri == tree }?.isWritePermission ?: false)
        if (tree == null) return json.put("rootListing", "no saved uri")
        try {
            var n = 0
            cr
                .query(
                    DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId(tree)),
                    arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                    null,
                    null,
                    null,
                )?.use { c -> while (c.moveToNext()) n++ }
            json.put("rootListing", "ok").put("rootChildren", n)
        } catch (t: Throwable) {
            json.put("rootListing", "failed: ${t::class.java.simpleName}")
        }
        return json
    }
}
