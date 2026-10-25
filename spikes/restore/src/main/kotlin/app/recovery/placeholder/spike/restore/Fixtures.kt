@file:Suppress("MagicNumber", "MaxLineLength")

package app.recovery.placeholder.spike.restore

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Synthetic JPEGs with a hand-built EXIF DateTimeOriginal, so restores can be checked without personal files. */
object Fixtures {
    /** EXIF time 2019-07-<day> 10:30:00, one distinct day per index. EXIF carries no zone; MediaStore's reading is part of the test. */
    fun exifDate(index: Int): String = "2019:07:%02d 10:30:00".format(1 + index % 28)

    fun exifEpochMillisUtc(exif: String): Long =
        SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(exif)!!.time

    fun exifEpochMillisLocal(exif: String): Long = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(exif)!!.time

    /** EXIF content of a fixture: only DateTimeOriginal (the first run), both DateTime and DateTimeOriginal, or no EXIF. */
    enum class ExifMode { ORIGINAL_ONLY, BOTH, NONE }

    fun makeJpeg(
        index: Int,
        exif: String,
        mode: ExifMode = ExifMode.ORIGINAL_ONLY,
    ): ByteArray {
        val bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.HSVToColor(floatArrayOf((index * 37 % 360).toFloat(), 0.6f, 0.9f)))
        canvas.drawText(
            "spike6 #$index",
            6f,
            50f,
            Paint().apply {
                color = Color.BLACK
                textSize = 14f
            },
        )
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        val jpeg = out.toByteArray()
        if (mode == ExifMode.NONE) return jpeg
        val segment = if (mode == ExifMode.BOTH) exifSegmentBoth(exif) else exifSegment(exif)
        return jpeg.copyOfRange(0, 2) + segment + jpeg.copyOfRange(2, jpeg.size)
    }

    /** APP1 with a TIFF header, IFD0 -> ExifIFD pointer, ExifIFD -> DateTimeOriginal (0x9003). Big endian. */
    private fun exifSegment(date: String): ByteArray {
        val tiff = ByteBuffer.allocate(64).order(ByteOrder.BIG_ENDIAN)
        tiff.put(byteArrayOf(0x4D, 0x4D)).putShort(0x002A).putInt(8)
        tiff
            .putShort(1)
            .putShort(0x8769.toShort())
            .putShort(4)
            .putInt(1)
            .putInt(26)
            .putInt(0)
        tiff
            .putShort(1)
            .putShort(0x9003.toShort())
            .putShort(2)
            .putInt(20)
            .putInt(44)
            .putInt(0)
        tiff.put((date + "\u0000").toByteArray(Charsets.US_ASCII))
        val body = "Exif\u0000\u0000".toByteArray(Charsets.US_ASCII) + tiff.array()
        return byteArrayOf(0xFF.toByte(), 0xE1.toByte()) + ByteBuffer.allocate(2).putShort((body.size + 2).toShort()).array() + body
    }

    /** IFD0 holds DateTime (0x0132) and the ExifIFD pointer; ExifIFD holds DateTimeOriginal (0x9003). 96-byte TIFF block. */
    private fun exifSegmentBoth(date: String): ByteArray {
        val tiff = ByteBuffer.allocate(96).order(ByteOrder.BIG_ENDIAN)
        tiff.put(byteArrayOf(0x4D, 0x4D)).putShort(0x002A).putInt(8)
        tiff.putShort(2)
        tiff
            .putShort(0x0132)
            .putShort(2)
            .putInt(20)
            .putInt(56)
        tiff
            .putShort(0x8769.toShort())
            .putShort(4)
            .putInt(1)
            .putInt(38)
        tiff.putInt(0)
        tiff
            .putShort(1)
            .putShort(0x9003.toShort())
            .putShort(2)
            .putInt(20)
            .putInt(76)
            .putInt(0)
        tiff.put((date + "\u0000").toByteArray(Charsets.US_ASCII))
        tiff.put((date + "\u0000").toByteArray(Charsets.US_ASCII))
        val body = "Exif\u0000\u0000".toByteArray(Charsets.US_ASCII) + tiff.array()
        return byteArrayOf(0xFF.toByte(), 0xE1.toByte()) + ByteBuffer.allocate(2).putShort((body.size + 2).toShort()).array() + body
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun fmt(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms)) + "Z"
}
