package dev.nucleusframework.nna.plugin.codegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GeneratedOutputsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `only changed files are rewritten and stale files are deleted`() {
        val root = tmp.newFolder("out")
        val roots = mapOf("proxies" to root)

        val first = GeneratedOutputs.sync(roots, mapOf("proxies" to mapOf("a/A.kt" to "A", "a/B.kt" to "B", "c/C.kt" to "C")))
        assertEquals(3, first.written)
        val untouched = File(root, "a/A.kt").apply { setLastModified(1_000) }

        val second = GeneratedOutputs.sync(roots, mapOf("proxies" to mapOf("a/A.kt" to "A", "a/B.kt" to "B2")))
        assertEquals(1, second.written)
        assertEquals(1, second.deleted)
        assertEquals(1_000, untouched.lastModified())
        assertEquals("B2", File(root, "a/B.kt").readText())
        assertFalse("Empty directories are pruned", File(root, "c").exists())

        assertEquals(second.hashes, GeneratedOutputs.hashes(roots))
    }
}
