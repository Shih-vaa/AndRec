@file:Suppress("MagicNumber", "TooGenericExceptionCaught", "MaxLineLength")

package app.recovery.placeholder.spike.crypto

import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.json.JSONObject

data class Argon2Params(
    val memoryKiB: Int,
    val iterations: Int,
    val parallelism: Int,
) {
    override fun toString() = "m=${memoryKiB}KiB,t=$iterations,p=$parallelism"
}

interface Argon2Impl {
    val name: String

    fun hash(
        password: ByteArray,
        salt: ByteArray,
        params: Argon2Params,
        outLen: Int,
    ): ByteArray
}

class BouncyCastleArgon2 : Argon2Impl {
    override val name = "bouncycastle-1.86-pure-java"

    override fun hash(
        password: ByteArray,
        salt: ByteArray,
        params: Argon2Params,
        outLen: Int,
    ): ByteArray {
        val built =
            Argon2Parameters
                .Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(params.iterations)
                .withMemoryAsKB(params.memoryKiB)
                .withParallelism(params.parallelism)
                .withSalt(salt)
                .build()
        val out = ByteArray(outLen)
        Argon2BytesGenerator().apply { init(built) }.generateBytes(password, out, 0, outLen)
        return out
    }
}

class Argon2KtImpl : Argon2Impl {
    override val name = "argon2kt-1.6.0-native"
    private val argon2 = Argon2Kt()

    override fun hash(
        password: ByteArray,
        salt: ByteArray,
        params: Argon2Params,
        outLen: Int,
    ): ByteArray =
        argon2
            .hash(
                mode = Argon2Mode.ARGON2_ID,
                password = password,
                salt = salt,
                tCostInIterations = params.iterations,
                mCostInKibibyte = params.memoryKiB,
                parallelism = params.parallelism,
                hashLengthInBytes = outLen,
            ).rawHashAsByteArray()
}

object Argon2Bench {
    /** Candidate grid. RFC 9106 section 4 lists 2 GiB/t1/p4 and 64 MiB/t3/p4 as its recommended options. */
    val grid =
        listOf(
            Argon2Params(19_456, 2, 1),
            Argon2Params(47_104, 1, 1),
            Argon2Params(65_536, 3, 1),
            Argon2Params(65_536, 3, 4),
            Argon2Params(131_072, 3, 4),
        )

    private const val RUNS = 3

    /** RFC 9106 section 5.3 Argon2id vector. Needs secret + associated data, which only BouncyCastle is asked to take. */
    fun rfcVectorPasses(): Boolean {
        val built =
            Argon2Parameters
                .Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(3)
                .withMemoryAsKB(32)
                .withParallelism(4)
                .withSalt(ByteArray(16) { 2 })
                .withSecret(ByteArray(8) { 3 })
                .withAdditional(ByteArray(12) { 4 })
                .build()
        val out = ByteArray(32)
        Argon2BytesGenerator().apply { init(built) }.generateBytes(ByteArray(32) { 1 }, out, 0, 32)
        return out.toHex() == "0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659"
    }

    /** Both libraries must agree on every input; that is the only cross-check available for the native one. */
    fun agree(
        a: Argon2Impl,
        b: Argon2Impl,
    ): Boolean =
        listOf(Argon2Params(32, 3, 4), Argon2Params(1_024, 2, 1), Argon2Params(4_096, 1, 2)).all { p ->
            val pw = "correct horse battery staple".toByteArray()
            val salt = ByteArray(16) { (it * 7).toByte() }
            a.hash(pw, salt, p, 32).contentEquals(b.hash(pw, salt, p, 32))
        }

    fun run(impl: Argon2Impl): List<JSONObject> =
        grid.map { p ->
            val times = mutableListOf<Long>()
            var error: String? = null
            try {
                repeat(RUNS) {
                    System.gc()
                    val start = System.nanoTime()
                    impl.hash("benchmark password".toByteArray(), ByteArray(16) { it.toByte() }, p, 32)
                    times += (System.nanoTime() - start) / 1_000_000
                }
            } catch (t: Throwable) {
                // OutOfMemoryError is a legitimate spike outcome, not a crash.
                error = "${t::class.java.simpleName}: ${t.message}"
            }
            JSONObject()
                .put("impl", impl.name)
                .put("params", p.toString())
                .put("runsMs", times.toString())
                .put("medianMs", times.sorted().getOrNull(times.size / 2) ?: -1)
                .put("error", error ?: "none")
        }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
