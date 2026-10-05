package com.example.calculator

import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread
import kotlin.test.fail

/** Runs [block] on a worker and fails with a full thread dump if it does not finish in time. */
internal fun deadlockGuard(seconds: Long, block: () -> Unit) {
    val executor = Executors.newSingleThreadExecutor()
    try {
        executor.submit(block).get(seconds, TimeUnit.SECONDS)
    } catch (_: TimeoutException) {
        val dump = ManagementFactory.getThreadMXBean().dumpAllThreads(true, true).joinToString("")
        fail("Probable deadlock (no completion in ${seconds}s)\n$dump")
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    } finally {
        executor.shutdownNow()
    }
}

/** Starts [threads] threads behind a barrier, each running [body] [iterations] times; rethrows the first failure. */
internal fun runParallel(threads: Int, iterations: Int, body: (thread: Int, iteration: Int) -> Unit) {
    val barrier = CyclicBarrier(threads)
    val errors = ConcurrentHashMap.newKeySet<Throwable>()
    List(threads) { t ->
        thread {
            try {
                barrier.await()
                repeat(iterations) { i -> body(t, i) }
            } catch (e: Throwable) {
                errors += e
            }
        }
    }.forEach { it.join() }
    errors.firstOrNull()?.let { throw AssertionError("${errors.size} thread(s) failed", it) }
}
