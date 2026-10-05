package com.example.calculator

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Torture tests for the generated FFM runtime: buffers, errors, lifecycle, coroutines. */
class RuntimeTortureTest {

    // ══════════════════════════════════════════════════════════════════════════
    // Collections beyond the default transfer buffers
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `large primitive lists and sets`() {
        Calculator(0).use { calc ->
            for (n in listOf(0, 1, 4095, 4096, 4097, 100_000)) {
                assertEquals(List(n) { it }, calc.bigInts(n), "ints n=$n")
                assertEquals(List(n) { it.toLong() * 1_000_000_007L }, calc.bigLongs(n), "longs n=$n")
                assertEquals((0 until n).toSet(), calc.bigIntSet(n), "set n=$n")
            }
        }
    }

    @Test
    fun `large string lists beyond packed buffer`() {
        Calculator(0).use { calc ->
            for ((count, length) in listOf(1 to 20_000, 2_000 to 10, 10_000 to 100)) {
                val expected = List(count) { i -> "s$i-" + "x".repeat(length) }
                assertEquals(expected, calc.bigStrings(count, length), "count=$count length=$length")
            }
        }
    }

    @Test
    fun `large map`() {
        Calculator(0).use { calc ->
            val map = calc.bigMap(20_000)
            assertEquals(20_000, map.size)
            assertEquals(19_999, map["key_19999"])
        }
    }

    @Test
    fun `large string return`() {
        Calculator(0).use { calc ->
            assertEquals("é".repeat(1_000_000), calc.bigString(1_000_000))
        }
    }

    @Test
    fun `large collection params roundtrip`() {
        Calculator(0).use { calc ->
            val ints = List(100_000) { it * 3 }
            assertEquals(ints, calc.echoInts(ints))
            assertEquals(ints.sumOf { it.toLong() }, calc.sumInts(ints))
            val strings = List(20_000) { "élément_$it" }
            assertEquals(strings, calc.echoStrings(strings))
        }
    }

    @Test
    fun `large collection inside data class`() {
        Calculator(0).use { calc ->
            assertEquals(TaggedList("big", List(10_000) { it }), calc.bigTaggedList(10_000))
        }
    }

    @Test
    fun `large suspend collection returns`() = runBlocking {
        Calculator(0).use { calc ->
            assertEquals(List(50_000) { it }, calc.delayedBigInts(50_000))
            assertEquals(List(5_000) { "item_$it" }, calc.delayedBigStrings(5_000))
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Strings
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `unicode strings roundtrip`() {
        Calculator(0).use { calc ->
            for (s in listOf("", "a", "日本語", "😀👨‍👩‍👧‍👦", "\t\n\r", "é".repeat(10_000), "𝄞".repeat(5_000))) {
                assertEquals(s, calc.echo(s))
            }
        }
    }

    @Test
    fun `strings with embedded NUL are not silently truncated`() {
        Calculator(0).use { calc ->
            val s = "before\u0000after"
            val result = runCatching { calc.echo(s) }
            // Either a faithful roundtrip or an explicit failure — never silent truncation
            result.onSuccess { assertEquals(s, it) }
            result.onFailure { assertTrue(it is IllegalArgumentException, "unexpected $it") }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Error propagation under concurrency
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `native errors never leak to another thread`() = deadlockGuard(60) {
        val spurious = AtomicInteger()
        val lost = AtomicInteger()
        runParallel(threads = 16, iterations = 20_000) { t, _ ->
            Calculator(1).use { calc ->
                if (t % 2 == 0) {
                    if (runCatching { calc.divide(0) }.isSuccess) lost.incrementAndGet()
                } else {
                    if (runCatching { calc.add(1) }.isFailure) spurious.incrementAndGet()
                }
            }
        }
        assertEquals(0, lost.get(), "errors lost")
        assertEquals(0, spurious.get(), "errors raised on the wrong thread")
    }

    @Test
    fun `callback exceptions keep their type and message`() {
        Calculator(0).use { calc ->
            val e = assertFailsWith<ArithmeticException> { calc.callTwice { if (it == 2) throw ArithmeticException("second") else it } }
            assertEquals("second", e.message)
            assertEquals(5, calc.callTwice { it + 1 })
        }
    }

    @Test
    fun `callback exceptions under concurrency stay on their thread`() = deadlockGuard(60) {
        Calculator(0).use { calc ->
            runParallel(threads = 16, iterations = 2_000) { t, i ->
                if ((t + i) % 2 == 0) {
                    val e = assertFailsWith<IllegalStateException> { calc.callTwice { error("t$t-i$i") } }
                    assertEquals("t$t-i$i", e.message)
                } else {
                    assertEquals(3, calc.callTwice { it })
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Object lifecycle
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `use after close fails cleanly`() {
        val calc = Calculator(1)
        calc.close()
        assertFailsWith<IllegalStateException> { calc.add(1) }
        assertFailsWith<IllegalStateException> { calc.current }
        calc.close() // idempotent
    }

    @Test
    fun `concurrent close is safe`() = deadlockGuard(60) {
        repeat(2_000) {
            val calc = Calculator(1)
            runParallel(threads = 8, iterations = 1) { _, _ -> calc.close() }
        }
    }

    @Test
    fun `close racing with calls never crashes`() = deadlockGuard(60) {
        repeat(500) {
            val calc = Calculator(1)
            val closer = thread { Thread.sleep(0, 50_000); calc.close() }
            runParallel(threads = 4, iterations = 200) { _, _ ->
                runCatching { calc.toVector() }.onFailure { assertTrue(it is IllegalStateException, "unexpected $it") }
            }
            closer.join()
        }
    }

    @Test
    fun `close from inside a callback does not deadlock`() = deadlockGuard(30) {
        val calc = Calculator(4)
        assertEquals(8, calc.measureVector { v -> calc.close(); v.x * 2 })
        assertFailsWith<IllegalStateException> { calc.add(1) }
    }

    @Test
    fun `unclosed objects are reclaimed by the cleaner`() {
        val refs = List(1_000) { WeakReference(Calculator(it)) }
        repeat(50) {
            System.gc()
            if (refs.all { it.get() == null }) return
            Thread.sleep(20)
        }
        val alive = refs.count { it.get() != null }
        assertEquals(0, alive, "$alive proxies still strongly reachable")
    }

    @Test
    fun `objects used with callbacks and coroutines are still reclaimed`() {
        val refs = List(200) { i ->
            val calc = Calculator(i)
            calc.callTwice { it + calc.current }
            calc.measureVector { it.x }
            runBlocking { calc.delayedVector(0); calc.fastFlow(3).toList() }
            WeakReference(calc)
        }
        repeat(50) {
            System.gc()
            if (refs.all { it.get() == null }) return
            Thread.sleep(20)
        }
        assertEquals(0, refs.count { it.get() != null }, "proxies kept alive by upcall stubs")
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Coroutines & flows
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `fast flow with slow collector loses nothing`() = runBlocking {
        Calculator(0).use { calc ->
            var expected = 0
            calc.fastFlow(20_000).collect { v ->
                assertEquals(expected++, v)
                if (v % 2_000 == 0) delay(5)
            }
            assertEquals(20_000, expected)
        }
    }

    @Test
    fun `completed flows keep close waiting for pending suspend calls`() = deadlockGuard(60) {
        runBlocking(Dispatchers.Default) {
            val calc = Calculator(5)
            repeat(5) { assertEquals(listOf(0, 1, 2), calc.fastFlow(3).toList()) }
            val pending = async { calc.delayedVector(200) }
            delay(20)
            calc.close()
            assertEquals(Vector(5, 10), pending.await())
        }
    }

    @Test
    fun `flow collector exception propagates`() = runBlocking {
        Calculator(0).use { calc ->
            val e = assertFailsWith<IllegalStateException> { calc.fastFlow(1_000).collect { if (it == 500) error("stop") } }
            assertEquals("stop", e.message)
            assertEquals(listOf(0, 1), calc.fastFlow(2).toList())
        }
    }

    @Test
    fun `thousands of early flow cancellations`() = deadlockGuard(60) {
        runBlocking(Dispatchers.Default) {
            Calculator(0).use { calc ->
                List(2_000) { async { calc.infiniteFlow().take(1).toList() } }.awaitAll().forEach { assertEquals(listOf(0), it) }
                List(2_000) { async { calc.fastFlow(1_000_000).first() } }.awaitAll().forEach { assertEquals(0, it) }
            }
        }
    }

    @Test
    fun `suspend cancelled before native start does not leak`() = deadlockGuard(60) {
        val calc = Calculator(11)
        runBlocking(Dispatchers.Default) {
            List(5_000) { async { kotlinx.coroutines.withTimeoutOrNull(0) { calc.delayedVector(10) } } }.awaitAll()
                .forEach { assertNull(it) }
        }
        calc.close()
    }
}
