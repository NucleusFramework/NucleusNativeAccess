package com.example.calculator

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Seeded monkey tests. Every failure reports its seed; replay one with `MONKEY_SEED=<seed>`,
 * widen the search with `MONKEY_SEEDS=<count>`.
 */
class MonkeyTest {

    private val seeds: List<Long> = System.getenv("MONKEY_SEED")?.let { listOf(it.toLong()) }
        ?: List(System.getenv("MONKEY_SEEDS")?.toInt() ?: 25) { 1_000L + it }

    private fun forEachSeed(block: (seed: Long, random: Random) -> Unit) {
        for (seed in seeds) {
            try {
                block(seed, Random(seed))
            } catch (t: Throwable) {
                throw AssertionError("Monkey failure with seed=$seed (replay: MONKEY_SEED=$seed)", t)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Model-based: every native result is checked against a pure JVM model
    // ══════════════════════════════════════════════════════════════════════════

    private class Model(var acc: Int, var label: String = "", var closed: Boolean = false)

    @Test
    fun `model-based monkey`() = deadlockGuard(300) {
        forEachSeed { seed, rnd ->
            val pool = mutableListOf<Pair<Calculator, Model>>()
            fun fresh(): Pair<Calculator, Model> {
                val initial = rnd.nextInt()
                return (Calculator(initial) to Model(initial)).also { pool += it }
            }
            fresh()
            repeat(1_500) { step ->
                if (pool.isEmpty() || rnd.nextInt(40) == 0) fresh()
                val (calc, model) = pool.random(rnd)
                val where = "seed=$seed step=$step"
                if (model.closed) {
                    expectClosed(where) { calc.add(1) }
                    return@repeat
                }
                when (rnd.nextInt(26)) {
                    0 -> { val v = rnd.nextInt(); model.acc += v; assertEquals(model.acc, calc.add(v), where) }
                    1 -> { val v = rnd.nextInt(); model.acc -= v; assertEquals(model.acc, calc.subtract(v), where) }
                    2 -> { val v = rnd.nextInt(-5, 5); model.acc *= v; assertEquals(model.acc, calc.multiply(v), where) }
                    3 -> {
                        val d = rnd.nextInt(-3, 4)
                        if (d == 0) {
                            val e = runCatching { calc.divide(0) }.exceptionOrNull()
                            assertTrue(e is KotlinNativeException && e.message == "Division by zero", "$where: $e")
                        } else {
                            model.acc /= d
                            assertEquals(model.acc, calc.divide(d), where)
                        }
                    }
                    4 -> {
                        val op = BinaryOp.entries.random(rnd)
                        val v = rnd.nextInt()
                        model.acc = op.apply(model.acc, v)
                        assertEquals(model.acc, calc.evaluate(op, v), where)
                    }
                    5 -> assertEquals(Vector(model.acc, model.acc * 2), calc.toVector(), where)
                    6 -> { val v = Vector(rnd.nextInt(), rnd.nextInt()); assertEquals(Vector(model.acc, model.acc) + v, calc.addVector(v), where) }
                    7 -> { val s = randomString(rnd); assertEquals(s, calc.echo(s), where) }
                    8 -> { val s = randomString(rnd); model.label = s; calc.label = s; assertEquals(s, calc.label, where) }
                    9 -> {
                        val n = rnd.nextInt(0, 12_000)
                        assertEquals(List(n) { it }, calc.bigInts(n), where)
                    }
                    10 -> {
                        val items = List(rnd.nextInt(0, 6_000)) { randomString(rnd, 12) }
                        assertEquals(items, calc.echoStrings(items), where)
                    }
                    11 -> {
                        // Callback that sometimes throws: the original exception must surface and state stay intact
                        val failAt = rnd.nextInt(0, 4)
                        val result = runCatching { calc.callTwice { if (it == failAt) throw MonkeyException("cb $it") else it * 10 } }
                        if (failAt == 1 || failAt == 2) {
                            assertTrue(result.exceptionOrNull() is MonkeyException, "$where: ${result.exceptionOrNull()}")
                        } else {
                            assertEquals(30, result.getOrThrow(), where)
                        }
                    }
                    12 -> {
                        val e = runCatching { calc.measureVector { v -> if (rnd.nextBoolean()) throw MonkeyException("m") else v.x } }
                        if (e.isSuccess) { model.acc = e.getOrThrow(); assertEquals(model.acc, calc.current, where) }
                        else assertTrue(e.exceptionOrNull() is MonkeyException, "$where: ${e.exceptionOrNull()}")
                    }
                    13 -> {
                        val result = runBlocking { calc.delayedVector(rnd.nextLong(0, 3)) }
                        assertEquals(Vector(model.acc, model.acc * 2), result, where)
                    }
                    14 -> {
                        val result = runBlocking { withTimeoutOrNull(rnd.nextLong(0, 3)) { calc.delayedEvaluate(BinaryOp.PLUS, 0, rnd.nextLong(0, 5)) } }
                        if (result != null) assertEquals(model.acc, result, where)
                    }
                    15 -> {
                        val n = rnd.nextInt(0, 300)
                        val take = rnd.nextInt(1, n + 2)
                        assertEquals(List(n) { it }.take(take), runBlocking { calc.fastFlow(n).take(take).toList() }, where)
                    }
                    16 -> {
                        val n = rnd.nextInt(0, 5_000)
                        assertEquals((0 until n).associateBy { "key_$it" }, calc.bigMap(n), where)
                    }
                    17 -> {
                        val s = "\u0000" + randomString(rnd)
                        val e = runCatching { calc.echo(s) }.exceptionOrNull()
                        assertTrue(e is IllegalArgumentException, "$where: $e")
                    }
                    18 -> assertEquals(model.label, calc.label, where)
                    19 -> {
                        val len = rnd.nextInt(0, 30_000)
                        assertEquals("é".repeat(len), calc.bigString(len), where)
                    }
                    20 -> { model.closed = true; calc.close() }
                    21 -> {
                        val n = rnd.nextInt(0, 20_000)
                        assertEquals(NamedValue("n".repeat(n), n), calc.bigNamedValue(n), where)
                    }
                    22 -> {
                        // Another pooled calculator as argument, possibly closed
                        val (other, otherModel) = pool.random(rnd)
                        val v = rnd.nextInt()
                        val result = runCatching { CalculatorManager().addWith(other, v) }
                        if (otherModel.closed) {
                            expectClosed(where) { result.getOrThrow() }
                        } else {
                            otherModel.acc += v
                            assertEquals(otherModel.acc, result.getOrThrow(), where)
                        }
                    }
                    23 -> {
                        val n = rnd.nextInt(0, 3_000)
                        assertEquals((0 until n).associate { it.toLong() * 3_000_000_000L to it }, calc.longKeyMap(n), where)
                    }
                    24 -> {
                        val n = rnd.nextInt(0, 20_000)
                        val p = calc.bigPayload(n)
                        assertEquals(BinaryPayload("p".repeat(n), ByteArray(n) { (it % 251).toByte() }), p, where)
                    }
                    else -> assertEquals(model.acc, calc.current, where)
                }
            }
            pool.forEach { it.first.close() }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Concurrent chaos: shared objects, random closes, cancellations, throwing callbacks
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `concurrent chaos monkey`() = deadlockGuard(600) {
        forEachSeed { seed, rootRandom ->
            val shared = CopyOnWriteArrayList(List(4) { Calculator(rootRandom.nextInt(-100, 100)) })
            val operations = AtomicInteger()
            runParallel(threads = 8, iterations = 1) { t, _ ->
                val rnd = Random(seed * 31 + t)
                repeat(400) { step ->
                    val where = "seed=$seed thread=$t step=$step"
                    if (rnd.nextInt(50) == 0) shared += Calculator(rnd.nextInt())
                    val calc = shared.random(rnd)
                    val outcome = runCatching { chaosStep(calc, rnd, shared) }
                    operations.incrementAndGet()
                    outcome.exceptionOrNull()?.let { e ->
                        val allowed = e is IllegalStateException && e.message == "Native object has been closed" ||
                            e is MonkeyException ||
                            e is KotlinNativeException && e.message == "Division by zero"
                        if (!allowed) throw AssertionError("$where: unexpected ${e::class.simpleName}: ${e.message}", e)
                    }
                }
            }
            shared.forEach { it.close() }
            assertEquals(8 * 400, operations.get())
        }
    }

    private fun chaosStep(calc: Calculator, rnd: Random, shared: MutableList<Calculator>) {
        when (rnd.nextInt(19)) {
            0 -> calc.add(rnd.nextInt())
            1 -> calc.divide(rnd.nextInt(-1, 2))
            2 -> calc.evaluate(BinaryOp.entries.random(rnd), rnd.nextInt())
            3 -> calc.toVector().also { assertEquals(it.x * 2, it.y) }
            4 -> calc.echo(randomString(rnd)).let { }
            5 -> calc.bigInts(rnd.nextInt(0, 9_000)).let { list -> list.forEachIndexed { i, v -> if (i != v) fail("bigInts[$i]=$v") } }
            6 -> calc.callTwice { if (rnd.nextInt(3) == 0) throw MonkeyException("cb") else it }
            7 -> calc.measureVector { v -> if (rnd.nextInt(4) == 0) calc.close(); v.x }
            8 -> runBlocking(Dispatchers.Default) {
                List(rnd.nextInt(1, 20)) { async { withTimeoutOrNull(rnd.nextLong(0, 4)) { calc.delayedVector(rnd.nextLong(0, 6)) } } }.awaitAll()
            }
            9 -> runBlocking { calc.fastFlow(rnd.nextInt(0, 2_000)).take(rnd.nextInt(1, 300)).toList() }
            10 -> runBlocking { withTimeoutOrNull(rnd.nextLong(0, 10)) { calc.infiniteFlow().take(rnd.nextInt(1, 5)).toList() } }
            11 -> if (rnd.nextInt(10) == 0) calc.close()
            12 -> calc.echoStrings(List(rnd.nextInt(0, 3_000)) { "x$it" })
            13 -> calc.bigMap(rnd.nextInt(0, 6_000)).let { m -> if (m.size > 0 && m["key_0"] != 0) fail("bigMap corrupted") }
            14 -> if (rnd.nextInt(20) == 0) System.gc()
            15 -> CalculatorManager().addWith(shared.random(rnd), 1)
            16 -> calc.bigNamedValue(rnd.nextInt(0, 20_000)).let { if (it.name.length != it.value) fail("bigNamedValue truncated") }
            17 -> calc.transformWith(shared.random(rnd)) { a, b -> if (rnd.nextInt(5) == 0) throw MonkeyException("tw") else a.current + b.current }
            else -> runBlocking { calc.delayedBigInts(rnd.nextInt(0, 6_000)) }.let { list -> list.forEachIndexed { i, v -> if (i != v) fail("delayedBigInts[$i]=$v") } }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════════

    private class MonkeyException(message: String) : RuntimeException(message)

    private fun expectClosed(where: String, block: () -> Unit) {
        val e = runCatching(block).exceptionOrNull()
        assertTrue(e is IllegalStateException && e.message == "Native object has been closed", "$where: expected closed, got $e")
    }

    private val alphabet = listOf("a", "Z", "0", " ", "é", "日", "😀", "\t", "\n", "𝄞", "ß", "\\", "\"", "%s", "{}")

    private fun randomString(rnd: Random, maxPieces: Int = 40): String =
        buildString { repeat(rnd.nextInt(0, maxPieces)) { append(alphabet.random(rnd)) } }
}
