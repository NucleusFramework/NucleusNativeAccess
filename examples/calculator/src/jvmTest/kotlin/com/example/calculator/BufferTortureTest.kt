package com.example.calculator

import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Every fixed-size transfer buffer of the generated bridges, pushed past its limit. */
class BufferTortureTest {

    private val sizes = listOf(0, 1, 8_190, 8_191, 8_192, 8_193, 100_000)

    @Test
    fun `data class string field beyond buffer`() {
        Calculator(0).use { calc ->
            for (n in sizes) {
                val nv = calc.bigNamedValue(n)
                assertEquals(n, nv.name.length, "n=$n")
                assertEquals(n, nv.value)
            }
        }
    }

    @Test
    fun `list of data classes with large strings`() {
        Calculator(0).use { calc ->
            val list = calc.bigNamedValues(50, 20_000)
            assertEquals(List(50) { NamedValue("v$it-" + "x".repeat(20_000), it) }, list)
        }
    }

    @Test
    fun `data class bytearray field beyond buffer`() {
        Calculator(0).use { calc ->
            for (n in sizes) {
                val p = calc.bigPayload(n)
                assertEquals(n, p.name.length, "name n=$n")
                assertContentEquals(ByteArray(n) { (it % 251).toByte() }, p.data, "data n=$n")
            }
        }
    }

    @Test
    fun `nullable string beyond buffer`() {
        Calculator(0).use { calc ->
            assertNull(calc.bigNullableString(-1))
            for (n in sizes) assertEquals("ü".repeat(n), calc.bigNullableString(n), "n=$n")
        }
    }

    @Test
    fun `string property beyond buffer`() {
        Calculator(0).use { calc ->
            for (n in sizes) {
                val s = "λ".repeat(n)
                calc.label = s
                assertEquals(s, calc.label, "n=$n")
            }
        }
    }

    @Test
    fun `extension function string return beyond buffer`() {
        Calculator(0).use { calc ->
            val prefix = "p".repeat(20_000)
            assertEquals("$prefix: Calculator(current=0)", calc.describeWithPrefix(prefix))
        }
    }

    @Test
    fun `bytearray roundtrip beyond buffer`() {
        Calculator(0).use { calc ->
            for (n in sizes) {
                val data = Random(n).nextBytes(n)
                assertContentEquals(data, calc.echoBytes(data), "n=$n")
            }
        }
    }

    @Test
    fun `maps with non-string keys`() {
        Calculator(0).use { calc ->
            for (n in listOf(0, 1, 5_000)) {
                assertEquals((0 until n).associateWith { "v$it" }, calc.bigIndexed(n), "int keys n=$n")
                assertEquals((0 until n).associate { it.toLong() * 3_000_000_000L to it }, calc.longKeyMap(n), "long keys n=$n")
            }
            assertEquals(Operation.entries.associateWith { it.ordinal * 10 }, calc.enumKeyMap())
        }
    }

    @Test
    fun `short and byte lists`() {
        Calculator(0).use { calc ->
            for (n in listOf(0, 1, 300, 5_000)) {
                assertEquals(List(n) { (it - 30_000).toShort() }, calc.shortList(n), "shorts n=$n")
                assertEquals(List(n) { (it - 128).toByte() }, calc.byteList(n), "bytes n=$n")
                assertEquals(List(n) { (it - 30_000).toShort() }, runBlocking { calc.delayedShorts(n) }, "suspend shorts n=$n")
            }
        }
    }

    @Test
    fun `closed object passed as parameter fails cleanly`() {
        val manager = CalculatorManager()
        val calc = Calculator(5)
        assertEquals(6, manager.addWith(calc, 1))
        calc.close()
        assertFailsWith<IllegalStateException> { manager.addWith(calc, 1) }
        assertFailsWith<IllegalStateException> { manager.describe(calc) }
    }
}
