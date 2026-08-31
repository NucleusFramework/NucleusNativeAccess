package dev.nucleusframework.nna.plugin.codegen

import dev.nucleusframework.nna.plugin.ir.KneModule
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmProxyGeneratorRuntimeTest {

    @Test
    fun `cache staleness check compares content, not only size`() {
        val runtime = generateRuntime()

        // A same-size library with different symbols must refresh the cache (issue #29).
        assertTrue(
            "Generated runtime must compare cached bytes with resource bytes",
            runtime.contains("!Files.readAllBytes(target).contentEquals(bytes)"),
        )
    }

    private fun generateRuntime(): String {
        val module = KneModule(
            libName = "demo",
            packages = setOf("demo"),
            classes = emptyList(),
            dataClasses = emptyList(),
            enums = emptyList(),
            functions = emptyList(),
        )
        return FfmProxyGenerator().generate(module, "demo").getValue("KneRuntime.kt")
    }
}
