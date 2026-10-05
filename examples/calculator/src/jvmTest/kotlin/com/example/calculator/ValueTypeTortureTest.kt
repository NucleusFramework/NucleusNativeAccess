package com.example.calculator

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLClassLoader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Extreme values, races and deadlock probes on verbatim JVM enums / data classes crossing FFM. */
class ValueTypeTortureTest {

    private val extremes = listOf(Int.MIN_VALUE, Int.MIN_VALUE + 1, -1, 0, 1, Int.MAX_VALUE - 1, Int.MAX_VALUE)

    // ══════════════════════════════════════════════════════════════════════════
    // Extreme values — JVM copy and native must agree bit for bit
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `overflow parity - every op on every extreme pair`() {
        for (a in extremes) for (b in extremes) for (op in BinaryOp.entries) {
            Calculator(a).use { calc ->
                assertEquals(op.apply(a, b), calc.evaluate(op, b), "$a ${op.symbol} $b")
            }
        }
    }

    @Test
    fun `overflow parity - vector members on extreme components`() {
        for (x in extremes) for (y in extremes) {
            val v = Vector(x, y)
            Calculator(x).use { calc ->
                assertEquals(v.lengthSquared, calc.vectorLengthSquared(v), "lengthSquared($x, $y)")
                assertEquals(Vector(x, x) + Vector(y, y), calc.addVector(Vector(y, y)))
                assertEquals(Vector(x, x * 2), calc.toVector())
            }
        }
    }

    @Test
    fun `fromSymbol parity on odd strings`() {
        val inputs = listOf("", "+", "-", "*", "/", "++", " +", "+ ", "日本語", "😀", "\t", "+".repeat(1_000_000))
        Calculator(0).use { calc ->
            for (s in inputs) assertEquals(BinaryOp.fromSymbol(s), calc.findBinaryOp(s), "symbol '${s.take(10)}'")
        }
    }

    @Test
    fun `nullable data class with body`() {
        Calculator(Int.MAX_VALUE).use { calc ->
            assertEquals(Vector(Int.MAX_VALUE, -2), calc.vectorOrNull(true))
            assertNull(calc.vectorOrNull(false))
        }
    }

    @Test
    fun `list of data classes - empty, single, large`() {
        Calculator(0).use { calc ->
            assertEquals(emptyList(), calc.vectorsUpTo(0))
            assertEquals(listOf(Vector(0, 0)), calc.vectorsUpTo(1))
            val big = calc.vectorsUpTo(10_000)
            assertEquals(10_000, big.size)
            big.forEachIndexed { i, v ->
                assertEquals(Vector(i, -i), v)
                assertEquals(2 * i, v.manhattan)
            }
        }
    }

    @Test
    fun `list of enums with bodies - empty and large`() {
        Calculator(0).use { calc ->
            assertEquals(emptyList(), calc.binaryOpsCycle(0))
            val ops = calc.binaryOpsCycle(10_000)
            assertEquals(10_000, ops.size)
            ops.forEachIndexed { i, op -> assertEquals(BinaryOp.entries[i % 3], op) }
            assertEquals(3, ops.toSet().size)
        }
    }

    @Test
    fun `callbacks receive and return extreme value types`() {
        for (x in extremes) Calculator(x).use { calc ->
            assertEquals(Vector(x, Int.MIN_VALUE), calc.buildVector { Vector(it, Int.MIN_VALUE) })
            assertEquals(Vector(x, x * 2).manhattan, calc.measureVector { it.manhattan })
            for (op in BinaryOp.entries) assertEquals(op, calc.pickBinaryOp { op })
        }
    }

    @Test
    fun `callback throwing leaves calculator usable`() {
        Calculator(3).use { calc ->
            repeat(1_000) {
                val e1 = assertFailsWith<IllegalStateException> { calc.measureVector { error("boom $it") } }
                assertEquals("boom Vector(x=3, y=6)", e1.message)
                assertFailsWith<UnsupportedOperationException> { calc.buildVector { throw UnsupportedOperationException() } }
            }
            assertEquals(Vector(3, 6), calc.toVector())
            assertEquals(5, calc.evaluate(BinaryOp.PLUS, 2))
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Races
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `concurrent first-time class init of verbatim enum and data class`() = deadlockGuard(60) {
        val classesDir = BinaryOp::class.java.protectionDomain.codeSource.location
        repeat(30) {
            val loader = ChildFirstLoader(arrayOf(classesDir), javaClass.classLoader)
            val threads = 16
            val barrier = CyclicBarrier(threads)
            val errors = ConcurrentHashMap.newKeySet<Throwable>()
            val workers = List(threads) { t ->
                thread {
                    try {
                        barrier.await()
                        val op = Class.forName("com.example.calculator.BinaryOp", true, loader)
                        when (t % 4) {
                            0 -> op.getField("PLUS").get(null)
                            1 -> op.getMethod("values").invoke(null)
                            2 -> op.getMethod("valueOf", String::class.java).invoke(null, "TIMES")
                            else -> {
                                val companion = op.getField("Companion").get(null)
                                companion.javaClass.getMethod("fromSymbol", String::class.java).invoke(companion, "-")
                            }
                        }
                        val vec = Class.forName("com.example.calculator.Vector", true, loader)
                        vec.getConstructor(Int::class.java, Int::class.java).newInstance(t, t)
                    } catch (e: Throwable) {
                        errors += e
                    }
                }
            }
            workers.forEach { it.join() }
            assertTrue(errors.isEmpty(), "init errors: $errors")
        }
    }

    @Test
    fun `shared calculator - concurrent identity ops keep invariants`() = deadlockGuard(60) {
        Calculator(21).use { calc ->
            runParallel(threads = 16, iterations = 5_000) { _, i ->
                when (i % 5) {
                    0 -> assertEquals(21, calc.evaluate(BinaryOp.PLUS, 0))
                    1 -> assertEquals(21, calc.evaluate(BinaryOp.TIMES, 1))
                    2 -> assertEquals(Vector(21, 42), calc.toVector())
                    3 -> assertEquals(Vector(22, 23), calc.addVector(Vector(1, 2)))
                    else -> assertEquals(BinaryOp.PLUS, calc.lastBinaryOp())
                }
            }
            assertEquals(21, calc.current)
        }
    }

    @Test
    fun `shared calculator - concurrent mutations never crash`() = deadlockGuard(60) {
        Calculator(0).use { calc ->
            runParallel(threads = 16, iterations = 5_000) { _, _ -> calc.evaluate(BinaryOp.PLUS, 1) }
            // Native accumulator is not atomic: lost updates are allowed, corruption is not
            assertTrue(calc.current in 1..80_000, "current=${calc.current}")
        }
    }

    @Test
    fun `per-thread calculators match JVM simulation exactly`() = deadlockGuard(60) {
        runParallel(threads = 16, iterations = 1) { t, _ ->
            Calculator(t).use { calc ->
                var expected = t
                repeat(5_000) { i ->
                    val op = BinaryOp.entries[(i + t) % 3]
                    val operand = (i % 7) - 3
                    expected = op.apply(expected, operand)
                    assertEquals(expected, calc.evaluate(op, operand))
                }
                assertEquals(Vector(expected, expected * 2), calc.toVector())
            }
        }
    }

    @Test
    fun `native vectors hash consistently across threads`() = deadlockGuard(60) {
        val seen = ConcurrentHashMap<Vector, AtomicInteger>()
        Calculator(7).use { calc ->
            runParallel(threads = 16, iterations = 2_000) { _, _ ->
                seen.computeIfAbsent(calc.toVector()) { AtomicInteger() }.incrementAndGet()
            }
        }
        assertEquals(setOf(Vector(7, 14)), seen.keys)
        assertEquals(32_000, seen.getValue(Vector(7, 14)).get())
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Deadlock probes
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `re-entrant callbacks into the same native object`() = deadlockGuard(60) {
        Calculator(5).use { calc ->
            runParallel(threads = 8, iterations = 500) { _, _ ->
                val nested = calc.buildVector { outer ->
                    val inner = calc.buildVector { Vector(it, outer) }
                    Vector(calc.vectorLengthSquared(inner), calc.pickBinaryOp { BinaryOp.MINUS }.apply(outer, 1))
                }
                assertEquals(Vector(50, 4), nested)
            }
        }
    }

    @Test
    fun `callback blocks on another thread calling native`() = deadlockGuard(30) {
        Calculator(9).use { calc ->
            repeat(200) {
                val result = calc.buildVector { x ->
                    var fromOtherThread: Vector? = null
                    thread { fromOtherThread = calc.toVector() }.join()
                    Vector(x, fromOtherThread!!.y)
                }
                assertEquals(Vector(9, 18), result)
            }
        }
    }

    @Test
    fun `thousands of suspend calls with cancellation on a shared object`() = deadlockGuard(120) {
        Calculator(11).use { calc ->
            val results = runBlocking(Dispatchers.Default) {
                List(2_000) { i ->
                    async {
                        if (i % 2 == 0) {
                            withTimeoutOrNull(1) { calc.delayedVector(50) }
                        } else {
                            val op = if (i % 4 == 1) BinaryOp.PLUS else BinaryOp.TIMES
                            calc.delayedEvaluate(op, if (op == BinaryOp.PLUS) 0 else 1, 5)
                        }
                    }
                }.awaitAll()
            }
            results.forEachIndexed { i, r ->
                if (i % 2 == 0) assertTrue(r == null || r == Vector(11, 22), "#$i -> $r")
                else assertEquals(11, r)
            }
            // Object must stay usable after mass cancellation
            assertEquals(Vector(11, 22), runBlocking { calc.delayedVector(1) })
        }
    }

    @Test
    fun `gc pressure with unclosed calculators producing value types`() = deadlockGuard(120) {
        runParallel(threads = 8, iterations = 5_000) { t, i ->
            val calc = Calculator(i)
            assertEquals(Vector(i, i * 2), calc.toVector())
            assertEquals(BinaryOp.PLUS, calc.lastBinaryOp())
            if (i % 1_000 == 0 && t == 0) System.gc()
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════════

    /** Loads the value types afresh so that class initialization races actually happen. */
    private class ChildFirstLoader(urls: Array<java.net.URL>, parent: ClassLoader) : URLClassLoader(urls, parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
            val isValueType = name.startsWith("com.example.calculator.BinaryOp") || name.startsWith("com.example.calculator.Vector")
            if (!isValueType) return super.loadClass(name, resolve)
            findLoadedClass(name) ?: findClass(name)
        }
    }
}
