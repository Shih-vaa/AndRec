@file:Suppress(
    "MagicNumber",
    "TooGenericExceptionCaught",
    "MaxLineLength",
    "DEPRECATION",
    "LongMethod",
    "CyclomaticComplexMethod",
    "NestedBlockDepth",
)

package app.recovery.placeholder.spike.restore

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.File

/**
 * Spike 6. adb starts a mode: images | pending | video | docs | cleanup (am start ... --es mode images).
 * "Pick folder" stores a tree grant for the docs mode. Logs under tag SPIKE6. File names and contents are
 * never logged: names are synthetic and shown only as lengths; hashes are compared, not printed.
 */
class SpikeActivity : Activity() {
    private lateinit var status: TextView
    private val prefs by lazy { getSharedPreferences("spike6", MODE_PRIVATE) }
    private val day = 24L * 3600 * 1000

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status = TextView(this).apply { text = "Spike 6 ready" }
        val pick =
            Button(this).apply {
                text = "Pick folder (for docs mode)"
                setOnClickListener {
                    startActivityForResult(
                        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                            // Hint only: start the picker inside the test folder so fewer taps are needed.
                            putExtra(
                                DocumentsContract.EXTRA_INITIAL_URI,
                                DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Documents/Spike6Docs"),
                            )
                        },
                        PICK,
                    )
                }
            }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 96, 48, 48)
                addView(status)
                addView(pick)
            },
        )
        if (intent.getBooleanExtra("pick", false)) {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    putExtra(
                        DocumentsContract.EXTRA_INITIAL_URI,
                        DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Documents/Spike6Docs"),
                    )
                },
                PICK,
            )
        }
        intent.getStringExtra("mode")?.let { run(it) }
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (requestCode == PICK && resultCode == RESULT_OK) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            prefs.edit().putString("tree", uri.toString()).apply()
            status.text = "Folder saved."
            Log.i(TAG, "PICKED")
        }
    }

    private fun run(mode: String) {
        status.text = "Running $mode..."
        Thread {
            val result =
                try {
                    when (mode) {
                        "images" -> images(intent.getBooleanExtra("camera", false))
                        "pending" -> pending()
                        "dates" -> dates()
                        "video" -> video()
                        "docs" -> docs()
                        "cleanup" ->
                            JSONObject().put(
                                "deletedRows",
                                RestoreBench.deleteAll(contentResolver, RestoreBench.ownRestoredRows(contentResolver)),
                            )
                        else -> JSONObject().put("fatal", "unknown mode")
                    }
                } catch (t: Throwable) {
                    JSONObject().put("fatal", "${t::class.java.simpleName}: ${t.message}")
                }
            result.put("mode", mode).put("device", JSONObject().put("model", Build.MODEL).put("sdk", Build.VERSION.SDK_INT))
            File(filesDir, "spike6-$mode.json").writeText(result.toString(2))
            result.toString(2).lines().forEach { Log.i(TAG, it) }
            Log.i(TAG, "DONE $mode")
            runOnUiThread { status.text = "$mode done." }
        }.start()
    }

    private fun record(
        variant: String,
        wanted: JSONObject,
        restored: Pair<JSONObject, Uri?>,
        sha: String,
        isImage: Boolean = true,
    ): JSONObject {
        val (j, uri) = restored
        j.put("variant", variant).put("wanted", wanted)
        if (uri != null && !j.has("error")) j.put("stored", RestoreBench.readBack(contentResolver, uri, sha, isImage))
        return j
    }

    private fun images(camera: Boolean): JSONObject {
        val cr = contentResolver
        val out = ArrayList<JSONObject>()
        val coll = RestoreBench.imagesUri()
        var n = 0

        fun one(
            variant: String,
            name: String,
            rel: String,
            want: (Long) -> Wanted,
            bytesOf: (Int) -> ByteArray = {
                Fixtures.makeJpeg(it, Fixtures.exifDate(it))
            },
        ): Uri? {
            val i = n++
            val exif = Fixtures.exifDate(i)
            val utc = Fixtures.exifEpochMillisUtc(exif)
            val bytes = bytesOf(i)
            val w = want(utc)
            val wantedJson =
                JSONObject()
                    .put("exifOriginal", exif)
                    .put("exifAsUtc", w.taken?.let { Fixtures.fmt(utc) } ?: JSONObject.NULL)
                    .put("requestedTaken", w.taken?.let { Fixtures.fmt(it) } ?: JSONObject.NULL)
                    .put("requestedModified", w.modifiedSec?.let { Fixtures.fmt(it * 1000) } ?: JSONObject.NULL)
                    .put("requestedAdded", w.addedSec?.let { Fixtures.fmt(it * 1000) } ?: JSONObject.NULL)
                    .put("exifAsLocalMs", Fixtures.exifEpochMillisLocal(exif) - utc)
            val r = RestoreBench.restore(cr, coll, "image/jpeg", name, rel, bytes, w)
            out += record(variant, wantedJson, r, Fixtures.sha256(bytes))
            return r.second
        }

        // Date handling: three files per variant.
        for (v in listOf("consistent", "unset", "conflicting", "after-pending", "with-added")) {
            repeat(3) { k ->
                one(v, "spike6_${v}_$k.jpg", "DCIM/Restored/", { utc ->
                    when (v) {
                        "consistent" -> Wanted(utc, utc / 1000)
                        "unset" -> Wanted()
                        "conflicting" -> Wanted(utc + 10 * day, (utc + 10 * day) / 1000)
                        "after-pending" -> Wanted(utc, utc / 1000, setDatesAfterPending = true)
                        else -> Wanted(utc, utc / 1000, addedSec = utc / 1000)
                    }
                })
            }
        }

        // Name collision: same name twice, different content. The first file must survive unchanged.
        val firstBytes = Fixtures.makeJpeg(100, Fixtures.exifDate(100))
        val secondBytes = Fixtures.makeJpeg(101, Fixtures.exifDate(101))
        val first = RestoreBench.restore(cr, coll, "image/jpeg", "spike6_collide.jpg", "DCIM/Restored/", firstBytes, Wanted())
        val second = RestoreBench.restore(cr, coll, "image/jpeg", "spike6_collide.jpg", "DCIM/Restored/", secondBytes, Wanted())
        out += record("collision-first", JSONObject(), first, Fixtures.sha256(firstBytes))
        out += record("collision-second", JSONObject(), second, Fixtures.sha256(secondBytes))
        out +=
            JSONObject()
                .put("variant", "collision-first-rechecked")
                .put(
                    "stored",
                    first.second?.let { RestoreBench.readBack(cr, it, Fixtures.sha256(firstBytes), true) } ?: JSONObject.NULL,
                ).put(
                    "sameUriAsSecond",
                    first.second == second.second,
                )

        // Folders: one valid nested path, several that Android may refuse for images.
        val paths =
            mutableListOf(
                "Pictures/Restored/",
                "Pictures/Restored/Sub/Deep/",
                "Documents/Restored/",
                "Download/Restored/",
                "Movies/Restored/",
                "Restored/",
            )
        if (camera) paths += "DCIM/Camera/"
        paths.forEachIndexed { k, p -> one("path", "spike6_path_$k.jpg", p, { utc -> Wanted(utc, utc / 1000) }) }

        // Names: non-ASCII, very long, slash inside, no extension, upper-case extension.
        val names =
            listOf(
                "फोटो_é_$n.jpg" to "unicode",
                "x".repeat(251) + ".jpg" to "255-chars",
                "a/b_$n.jpg" to "slash-in-name",
                "noextension_$n" to "no-extension",
                "UPPER_$n.JPG" to "upper-extension",
            )
        names.forEach { (nm, label) -> one("name-$label", nm, "DCIM/Restored/", { utc -> Wanted(utc, utc / 1000) }) }

        return JSONObject().put("results", RestoreBench.jsonArray(out)).put("cameraFolderTested", camera)
    }

    private fun rowDates(uri: Uri): JSONObject {
        val j = JSONObject()
        contentResolver.query(uri, arrayOf("datetaken", "date_modified", "date_added", "is_pending", "_data"), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                j.put("taken", if (c.isNull(0)) JSONObject.NULL else Fixtures.fmt(c.getLong(0)))
                j.put("modified", Fixtures.fmt(c.getLong(1) * 1000))
                j.put("added", Fixtures.fmt(c.getLong(2) * 1000))
                j.put("pending", c.getInt(3))
                c.getString(4)?.let { j.put("fileMtime", Fixtures.fmt(File(it).lastModified())) }
            }
        }
        return j
    }

    /**
     * Which route, if any, makes the old capture and modified dates stick? Each strategy inserts one JPEG, then samples
     * the row at several moments. Wanted date: 2019-07-14 10:30:00 UTC.
     */
    private fun dates(): JSONObject {
        val cr = contentResolver
        val exif = "2019:07:14 10:30:00"
        val want = Fixtures.exifEpochMillisUtc(exif)
        val out = ArrayList<JSONObject>()

        fun strategy(
            label: String,
            mode: Fixtures.ExifMode,
            takenAtInsert: Boolean,
            steps: (Uri, JSONObject) -> Unit,
        ) {
            val j = JSONObject().put("strategy", label)
            try {
                val bytes = Fixtures.makeJpeg(300 + out.size, exif, mode)
                val values =
                    ContentValues().apply {
                        put("_display_name", "spike6_dates_${out.size}.jpg")
                        put("mime_type", "image/jpeg")
                        put("relative_path", "DCIM/Restored/")
                        put("is_pending", 1)
                        if (takenAtInsert) put("datetaken", want)
                    }
                val uri = cr.insert(RestoreBench.imagesUri(), values)!!
                j.put("whilePendingAfterInsert", rowDates(uri))
                cr.openOutputStream(uri)!!.use { it.write(bytes) }
                steps(uri, j)
            } catch (t: Throwable) {
                j.put("error", "${t::class.java.simpleName}: ${t.message?.take(160)}")
            }
            out += j
        }

        fun clear(uri: Uri) = cr.update(uri, ContentValues().apply { put("is_pending", 0) }, null, null)

        fun settle(
            uri: Uri,
            j: JSONObject,
            key: String,
        ) {
            j.put("${key}Immediately", rowDates(uri))
            Thread.sleep(4000)
            j.put("${key}After4s", rowDates(uri))
        }

        strategy("A exif-original-only, no dates set", Fixtures.ExifMode.ORIGINAL_ONLY, false) { u, j ->
            clear(u)
            settle(u, j, "cleared")
        }
        strategy("B exif DateTime+Original, no dates set", Fixtures.ExifMode.BOTH, false) { u, j ->
            clear(u)
            settle(u, j, "cleared")
        }
        strategy("C exif DateTime+Original, taken set at insert", Fixtures.ExifMode.BOTH, true) { u, j ->
            clear(u)
            settle(u, j, "cleared")
        }
        strategy("D no exif, taken set at insert", Fixtures.ExifMode.NONE, true) { u, j ->
            clear(u)
            settle(u, j, "cleared")
        }
        strategy("E no exif, taken set by update 4 s after clearing pending", Fixtures.ExifMode.NONE, false) { u, j ->
            clear(u)
            Thread.sleep(4000)
            j.put("updateRows", cr.update(u, ContentValues().apply { put("datetaken", want) }, null, null))
            settle(u, j, "afterUpdate")
        }
        strategy("F no exif, file mtime set while pending (path from _data)", Fixtures.ExifMode.NONE, false) { u, j ->
            val path = cr.query(u, arrayOf("_data"), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
            j.put("setLastModified", path?.let { File(it).setLastModified(want) } ?: false)
            clear(u)
            settle(u, j, "cleared")
        }
        strategy("G no exif, file mtime set after clearing pending, then rescan", Fixtures.ExifMode.NONE, false) { u, j ->
            clear(u)
            Thread.sleep(2000)
            val path = cr.query(u, arrayOf("_data"), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
            j.put("setLastModified", path?.let { File(it).setLastModified(want) } ?: false)
            j.put("beforeRescan", rowDates(u))
            path?.let { android.media.MediaScannerConnection.scanFile(this, arrayOf(it), null, null) }
            settle(u, j, "afterRescan")
        }
        strategy("H exif DateTime+Original, cleared, then explicit rescan", Fixtures.ExifMode.BOTH, false) { u, j ->
            clear(u)
            Thread.sleep(2000)
            val path = cr.query(u, arrayOf("_data"), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
            path?.let { android.media.MediaScannerConnection.scanFile(this, arrayOf(it), null, null) }
            settle(u, j, "afterRescan")
        }
        strategy("K exif BOTH, one update sets taken and clears pending together", Fixtures.ExifMode.BOTH, false) { u, j ->
            j.put(
                "updateRows",
                cr.update(
                    u,
                    ContentValues().apply {
                        put("is_pending", 0)
                        put("datetaken", want)
                    },
                    null,
                    null,
                ),
            )
            settle(u, j, "afterUpdate")
        }
        strategy("L exif BOTH, mtime set while pending, taken set at insert", Fixtures.ExifMode.BOTH, true) { u, j ->
            val path = cr.query(u, arrayOf("_data"), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
            j.put("setLastModified", path?.let { File(it).setLastModified(want) } ?: false)
            clear(u)
            settle(u, j, "cleared")
        }

        fun withMtime(
            label: String,
            mode: Fixtures.ExifMode,
            taken: Long?,
            mtime: Long,
        ) {
            strategy(label, mode, false) { u, j ->
                // taken is set on the pending row before the bytes are written, like the insert-time route above.
                taken?.let { cr.update(u, ContentValues().apply { put("datetaken", it) }, null, null) }
                val path = cr.query(u, arrayOf("_data"), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
                j.put("setLastModified", path?.let { File(it).setLastModified(mtime) } ?: false)
                clear(u)
                settle(u, j, "cleared")
            }
        }
        withMtime("M no exif, taken set while pending, mtime = taken", Fixtures.ExifMode.NONE, want, want)
        withMtime("N no exif, taken = mtime + 10 days (conflict)", Fixtures.ExifMode.NONE, want + 10 * day, want)
        withMtime("P no exif, taken = mtime + 1 day", Fixtures.ExifMode.NONE, want + day, want)
        withMtime("Q exif BOTH, taken unset, mtime set (EXIF alone, old file time)", Fixtures.ExifMode.BOTH, null, want)
        return JSONObject().put("wanted", Fixtures.fmt(want)).put("results", RestoreBench.jsonArray(out))
    }

    /** Insert, hold the row pending while adb checks what other apps see, then clear the flag. */
    private fun pending(): JSONObject {
        val cr = contentResolver
        val bytes = Fixtures.makeJpeg(200, Fixtures.exifDate(200))
        val values =
            ContentValues().apply {
                put("_display_name", "spike6_pending.jpg")
                put("mime_type", "image/jpeg")
                put("relative_path", "DCIM/Restored/")
                put("is_pending", 1)
            }
        val uri = cr.insert(RestoreBench.imagesUri(), values)!!
        cr.openOutputStream(uri)!!.use { it.write(bytes) }
        Log.i(TAG, "PENDING_HELD id=${uri.lastPathSegment}")
        Thread.sleep(40_000)
        cr.update(uri, ContentValues().apply { put("is_pending", 0) }, null, null)
        Log.i(TAG, "PENDING_CLEARED id=${uri.lastPathSegment}")
        Thread.sleep(40_000)
        return JSONObject().put("id", uri.lastPathSegment).put("note", "adb sampled other-app visibility during the two 40 s windows")
    }

    /** Optional: restore a sample video the owner placed in the app's external files folder. */
    private fun video(): JSONObject {
        val fixture = File(getExternalFilesDir(null), "fixture.mp4")
        if (!fixture.isFile) return JSONObject().put("skipped", "no fixture.mp4 in the app's external files folder")
        val bytes = fixture.readBytes()
        val sha = Fixtures.sha256(bytes)
        val out = ArrayList<JSONObject>()
        val coll = RestoreBench.videosUri()
        val utc = Fixtures.exifEpochMillisUtc("2019:07:14 10:30:00")
        for ((label, want) in listOf(
            "consistent" to Wanted(utc, utc / 1000),
            "unset" to Wanted(),
            "after-pending" to Wanted(utc, utc / 1000, setDatesAfterPending = true),
        )) {
            val r = RestoreBench.restore(contentResolver, coll, "video/mp4", "spike6_video_$label.mp4", "Movies/Restored/", bytes, want)
            val j =
                record(
                    "video-$label",
                    JSONObject().put("requestedTaken", want.taken?.let { Fixtures.fmt(it) } ?: JSONObject.NULL),
                    r,
                    sha,
                    isImage = false,
                )
            r.second?.let { j.put("videoMetadata", RestoreBench.videoMetadata(contentResolver, it)) }
            out += j
        }
        return JSONObject().put("fixtureBytes", bytes.size).put("results", RestoreBench.jsonArray(out))
    }

    /** Part 6b: write documents into the picked SAF tree. Reports names, collisions and what cannot be set. */
    private fun docs(): JSONObject {
        val tree = prefs.getString("tree", null)?.let { Uri.parse(it) } ?: return JSONObject().put("fatal", "no folder picked")
        val cr = contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val created = ArrayList<Uri>()
        val out = ArrayList<JSONObject>()
        val old = Fixtures.exifEpochMillisUtc("2019:07:14 10:30:00")

        fun make(
            label: String,
            mime: String,
            name: String,
            bytes: ByteArray,
            parentUri: Uri = parent,
        ): Uri? {
            val j = JSONObject().put("variant", label).put("requestedNameLength", name.length)
            var uri: Uri? = null
            try {
                uri = DocumentsContract.createDocument(cr, parentUri, mime, name)
                if (uri == null) {
                    j.put("error", "createDocument returned null")
                } else {
                    created += uri
                    cr.openOutputStream(uri, "w")!!.use { it.write(bytes) }
                    cr
                        .query(
                            uri,
                            arrayOf(
                                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                                DocumentsContract.Document.COLUMN_MIME_TYPE,
                                DocumentsContract.Document.COLUMN_SIZE,
                            ),
                            null,
                            null,
                            null,
                        )?.use { c ->
                            if (c.moveToFirst()) {
                                val stored = c.getString(0)
                                j.put("storedNameLength", stored?.length ?: -1)
                                j.put("nameKept", stored == name)
                                j.put("storedLastModified", Fixtures.fmt(c.getLong(1)))
                                j.put("storedMime", c.getString(2))
                                j.put("size", c.getLong(3))
                            }
                        }
                    j.put("sha256Matches", Fixtures.sha256(cr.openInputStream(uri)!!.use { it.readBytes() }) == Fixtures.sha256(bytes))
                }
            } catch (t: Throwable) {
                j.put("error", "${t::class.java.simpleName}: ${t.message?.take(160)}")
            }
            out += j
            return uri
        }
        val text = "spike6 document".toByteArray()
        val firstDoc = make("plain", "text/plain", "spike6_doc.txt", text)
        val secondDoc = make("collision-same-name", "text/plain", "spike6_doc.txt", "different content".toByteArray())
        out += JSONObject().put("variant", "collision-distinct-uris").put("distinct", firstDoc != null && firstDoc != secondDoc)
        make("pdf", "application/pdf", "spike6_report.pdf", text)
        make("unknown-extension", "application/octet-stream", "spike6_data.xyz", text)
        make("no-extension", "text/plain", "spike6_noext", text)
        make("mime-mismatch", "image/jpeg", "spike6_mismatch.txt", text)
        // Modified date: there is no API to set it. Try the only plausible route and record what happens.
        val j = JSONObject().put("variant", "set-last-modified")
        try {
            val rows =
                cr.update(
                    firstDoc!!,
                    ContentValues().apply { put(DocumentsContract.Document.COLUMN_LAST_MODIFIED, old) },
                    null,
                    null,
                )
            j.put("updatedRows", rows)
        } catch (t: Throwable) {
            j.put("error", "${t::class.java.simpleName}: ${t.message?.take(160)}")
        }
        out += j
        val dir =
            runCatching {
                DocumentsContract.createDocument(
                    cr,
                    parent,
                    DocumentsContract.Document.MIME_TYPE_DIR,
                    "spike6_dir",
                )
            }.getOrNull()
        if (dir != null) {
            created += dir
            make("nested-file", "text/plain", "spike6_nested.txt", text, dir)
        }
        val deleted = created.asReversed().count { runCatching { DocumentsContract.deleteDocument(cr, it) }.getOrDefault(false) }
        return JSONObject().put("results", RestoreBench.jsonArray(out)).put("createdThenDeleted", "$deleted of ${created.size}")
    }

    private companion object {
        const val TAG = "SPIKE6"
        const val PICK = 6
    }
}
