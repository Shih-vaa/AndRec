@file:Suppress(
    "MagicNumber",
    "TooGenericExceptionCaught",
    "MaxLineLength",
    "LongMethod",
    "LongParameterList",
    "CyclomaticComplexMethod",
    "ReturnCount",
    "NestedBlockDepth",
)

package app.recovery.placeholder.spike.restore

import android.content.ContentResolver
import android.content.ContentValues
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Dates a restore asks MediaStore to keep. Null means "do not set". */
class Wanted(
    val taken: Long? = null,
    val modifiedSec: Long? = null,
    val addedSec: Long? = null,
    val setDatesAfterPending: Boolean = false,
)

object RestoreBench {
    private val images get() = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val videos get() = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private val cols =
        arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.IS_PENDING,
            MediaStore.MediaColumns.DATA,
        )

    /** Restore [bytes] the way the real restore writer would. Never throws: failures are returned as data. */
    fun restore(
        cr: ContentResolver,
        collection: Uri,
        mime: String,
        name: String,
        relPath: String,
        bytes: ByteArray,
        want: Wanted,
    ): Pair<JSONObject, Uri?> {
        val j = JSONObject().put("name", "(redacted)").put("relPath", relPath).put("requestedNameLength", name.length)
        var uri: Uri? = null
        try {
            val values =
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                    if (!want.setDatesAfterPending) {
                        want.taken?.let { put(MediaStore.MediaColumns.DATE_TAKEN, it) }
                        want.modifiedSec?.let { put(MediaStore.MediaColumns.DATE_MODIFIED, it) }
                        want.addedSec?.let { put(MediaStore.MediaColumns.DATE_ADDED, it) }
                    }
                }
            uri = cr.insert(collection, values) ?: return j.put("error", "insert returned null") to null
            cr.openOutputStream(uri)!!.use { it.write(bytes) }
            val done =
                ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                    if (want.setDatesAfterPending) {
                        want.taken?.let { put(MediaStore.MediaColumns.DATE_TAKEN, it) }
                        want.modifiedSec?.let { put(MediaStore.MediaColumns.DATE_MODIFIED, it) }
                    }
                }
            j.put("updatedRows", cr.update(uri, done, null, null))
            j.put("uriId", uri.lastPathSegment)
        } catch (t: Throwable) {
            j.put("error", "${t::class.java.simpleName}: ${t.message?.take(160)}")
        }
        return j to uri
    }

    /** What MediaStore and the filesystem now say about [uri], and whether the bytes survived. */
    fun readBack(
        cr: ContentResolver,
        uri: Uri,
        sourceSha: String,
        isImage: Boolean,
    ): JSONObject {
        val j = JSONObject()
        try {
            cr.query(uri, cols, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    j.put("storedNameLength", c.getString(0)?.length ?: -1)
                    j.put("storedNameKind", nameKind(c.getString(0)))
                    j.put("storedRelPath", c.getString(1))
                    j.put("dateTaken", if (c.isNull(2)) JSONObject.NULL else Fixtures.fmt(c.getLong(2)))
                    j.put("dateModifiedSec", c.getLong(3))
                    j.put("dateModified", Fixtures.fmt(c.getLong(3) * 1000))
                    j.put("dateAdded", Fixtures.fmt(c.getLong(4) * 1000))
                    j.put("size", c.getLong(5))
                    j.put("isPending", c.getInt(7))
                    val path = c.getString(8)
                    if (path != null) j.put("fileMtime", Fixtures.fmt(File(path).lastModified()))
                } else {
                    j.put("rowFound", false)
                }
            }
            val data = cr.openInputStream(uri)!!.use { it.readBytes() }
            j.put("sha256Matches", Fixtures.sha256(data) == sourceSha)
            if (isImage) {
                j.put(
                    "exifDateTimeOriginal",
                    ExifInterface(data.inputStream()).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: JSONObject.NULL,
                )
            }
        } catch (t: Throwable) {
            j.put("readBackError", "${t::class.java.simpleName}: ${t.message?.take(160)}")
        }
        return j
    }

    /** Names are not logged. Say only whether a suffix such as " (1)" or a changed extension appeared. */
    private fun nameKind(stored: String?): String =
        when {
            stored == null -> "null"
            Regex(""".* \(\d+\)\.[A-Za-z0-9]+$""").matches(stored) -> "suffix-added"
            else -> "as-requested-or-unknown"
        }

    fun videoMetadata(
        cr: ContentResolver,
        uri: Uri,
    ): JSONObject {
        val j = JSONObject()
        try {
            cr
                .query(
                    uri,
                    arrayOf(
                        MediaStore.Video.VideoColumns.DURATION,
                        MediaStore.Video.VideoColumns.WIDTH,
                        MediaStore.Video.VideoColumns.HEIGHT,
                    ),
                    null,
                    null,
                    null,
                )?.use { c ->
                    if (c.moveToFirst()) j.put("durationMs", c.getLong(0)).put("width", c.getInt(1)).put("height", c.getInt(2))
                }
            MediaMetadataRetriever().use { r ->
                cr.openFileDescriptor(uri, "r")!!.use { pfd -> r.setDataSource(pfd.fileDescriptor) }
                j.put("containerCreationDate", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE) ?: JSONObject.NULL)
                j.put("retrieverDurationMs", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION) ?: JSONObject.NULL)
            }
        } catch (t: Throwable) {
            j.put("videoMetadataError", "${t::class.java.simpleName}: ${t.message?.take(160)}")
        }
        return j
    }

    /** Own restored rows that this app inserted: removed at the end of a run. */
    fun deleteAll(
        cr: ContentResolver,
        uris: List<Uri>,
    ): Int = uris.sumOf { runCatching { cr.delete(it, null, null) }.getOrDefault(0) }

    /** Rows this app can see under any "Restored" folder, for the cleanup mode. */
    fun ownRestoredRows(cr: ContentResolver): List<Uri> {
        val out = ArrayList<Uri>()
        for (collection in listOf(images, videos)) {
            cr
                .query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                    arrayOf("%Restored%"),
                    null,
                )?.use { c ->
                    while (c.moveToNext()) out += android.content.ContentUris.withAppendedId(collection, c.getLong(0))
                }
        }
        return out
    }

    fun imagesUri(): Uri = images

    fun videosUri(): Uri = videos

    fun jsonArray(items: List<JSONObject>): JSONArray = JSONArray().also { a -> items.forEach { a.put(it) } }
}
