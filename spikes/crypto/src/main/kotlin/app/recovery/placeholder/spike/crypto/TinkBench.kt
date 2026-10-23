@file:Suppress("MagicNumber", "TooGenericExceptionCaught", "MaxLineLength")

package app.recovery.placeholder.spike.crypto

import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Random

object TinkBench {
    private const val PLAIN_MIB = 64
    private val ad = "spike3|scheme-v1|content-id".toByteArray()

    private fun primitive(template: String): StreamingAead {
        StreamingAeadConfig.register()
        val handle = KeysetHandle.generateNew(KeyTemplates.get(template))
        return handle.getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
    }

    private fun writePlain(
        file: File,
        mib: Int,
    ): ByteArray {
        val rnd = Random(42)
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 20)
        file.outputStream().use { out ->
            repeat(mib) {
                rnd.nextBytes(buf)
                md.update(buf)
                out.write(buf)
            }
        }
        return md.digest()
    }

    private fun decryptDigest(
        sa: StreamingAead,
        file: File,
        ad: ByteArray,
    ): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 16)
        sa.newDecryptingStream(file.inputStream(), ad).use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest()
    }

    /** True when decryption of [file] is rejected (any exception) rather than yielding data. */
    private fun rejected(
        sa: StreamingAead,
        file: File,
        ad: ByteArray,
    ): Boolean =
        try {
            decryptDigest(sa, file, ad)
            false
        } catch (_: Exception) {
            true
        }

    fun run(
        dir: File,
        template: String,
    ): JSONObject {
        val sa = primitive(template)
        val plain = File(dir, "plain.bin")
        val cipher = File(dir, "cipher.bin")
        val expected = writePlain(plain, PLAIN_MIB)

        val t0 = System.nanoTime()
        sa.newEncryptingStream(cipher.outputStream(), ad).use { out ->
            plain.inputStream().use { it.copyTo(out, 1 shl 16) }
        }
        val encMs = (System.nanoTime() - t0) / 1_000_000

        val t1 = System.nanoTime()
        val actual = decryptDigest(sa, cipher, ad)
        val decMs = (System.nanoTime() - t1) / 1_000_000

        // Tamper tests on a small file so they stay quick.
        val small = File(dir, "small-plain.bin")
        val smallCipher = File(dir, "small-cipher.bin")
        writePlain(small, 1)
        sa.newEncryptingStream(smallCipher.outputStream(), ad).use { out ->
            small.inputStream().use { it.copyTo(out, 1 shl 16) }
        }
        val original = smallCipher.readBytes()
        val intact = !rejected(sa, smallCipher, ad)

        smallCipher.writeBytes(original.copyOf().also { it[original.size / 2] = (it[original.size / 2].toInt() xor 1).toByte() })
        val bitFlip = rejected(sa, smallCipher, ad)

        smallCipher.writeBytes(original.copyOf(original.size - 4096))
        val truncated = rejected(sa, smallCipher, ad)

        smallCipher.writeBytes(original)
        val wrongAd = rejected(sa, smallCipher, "other".toByteArray())

        val result =
            JSONObject()
                .put("template", template)
                .put("plainMiB", PLAIN_MIB)
                .put("encryptMs", encMs)
                .put("decryptMs", decMs)
                .put("encryptMiBps", PLAIN_MIB * 1000.0 / encMs)
                .put("decryptMiBps", PLAIN_MIB * 1000.0 / decMs)
                .put("overheadBytes", cipher.length() - plain.length())
                .put("roundTripHashMatches", expected.contentEquals(actual))
                .put("untamperedAccepted", intact)
                .put("bitFlipRejected", bitFlip)
                .put("truncationRejected", truncated)
                .put("wrongAssociatedDataRejected", wrongAd)

        listOf(plain, cipher, small, smallCipher).forEach { it.delete() }
        return result
    }
}
