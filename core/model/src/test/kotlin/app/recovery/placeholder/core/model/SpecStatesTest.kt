package app.recovery.placeholder.core.model

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards against drift between the honesty model in docs/SPEC.md §4 and the code.
 * If the spec gains, loses or renames a state, this fails until the enum is updated
 * (or the other way round), so the two axes cannot silently change or collapse.
 */
class SpecStatesTest {
    private val section4: List<String> by lazy {
        val spec = findSpec().readLines()
        val start = spec.indexOfFirst { it.startsWith("## 4.") }
        val end = spec.indexOfFirst { it.startsWith("## 5.") }
        assertTrue(start >= 0 && end > start, "SPEC.md section 4 not found")
        spec.subList(start, end)
    }

    @Test
    fun protectionStatesMatchSpec() {
        assertEquals(specStates("**Protection axis"), ProtectionState.entries.map { it.name })
    }

    @Test
    fun recoveryStatesMatchSpec() {
        assertEquals(specStates("**Recovery axis"), RecoveryState.entries.map { it.name })
    }

    @Test
    fun axesShareNoState() {
        val overlap = ProtectionState.entries.map { it.name } intersect RecoveryState.entries.map { it.name }.toSet()
        assertTrue(overlap.isEmpty(), "States must not appear on both axes: $overlap")
    }

    /** Backticked ALL_CAPS names in the first column of the table following [heading], in order. */
    private fun specStates(heading: String): List<String> {
        val from = section4.indexOfFirst { it.startsWith(heading) }
        assertTrue(from >= 0, "Heading '$heading' not found in SPEC.md section 4")
        val row = Regex("""^\|\s*`([A-Z_]+)`\s*\|""")
        return section4
            .drop(from + 1)
            .takeWhile { !it.startsWith("**") }
            .mapNotNull { row.find(it)?.groupValues?.get(1) }
    }

    private fun findSpec(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "docs/SPEC.md")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("docs/SPEC.md not found above ${File("").absolutePath}")
    }
}
