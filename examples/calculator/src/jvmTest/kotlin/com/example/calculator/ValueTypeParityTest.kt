package com.example.calculator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** JVM enums and data classes are verbatim copies of the native declarations. */
class ValueTypeParityTest {

    @Test
    fun `enum entry bodies are available on JVM`() {
        assertEquals(5, BinaryOp.PLUS.apply(2, 3))
        assertEquals(-1, BinaryOp.MINUS.apply(2, 3))
        assertEquals(6, BinaryOp.TIMES.apply(2, 3))
    }

    @Test
    fun `enum companion is available on JVM`() {
        assertEquals(BinaryOp.TIMES, BinaryOp.fromSymbol("*"))
        assertNull(BinaryOp.fromSymbol("/"))
    }

    @Test
    fun `enum with bodies crosses FFM as param`() {
        Calculator(10).use { calc ->
            for (op in BinaryOp.entries) {
                val expected = op.apply(calc.current, 3)
                assertEquals(expected, calc.evaluate(op, 3))
            }
        }
    }

    @Test
    fun `enum with bodies crosses FFM as return`() {
        Calculator(1).use { assertEquals(BinaryOp.PLUS, it.lastBinaryOp()) }
        Calculator(-1).use { assertEquals(BinaryOp.MINUS, it.lastBinaryOp()) }
    }

    @Test
    fun `data class default value is available on JVM`() {
        assertEquals(Vector(4, 0), Vector(4))
    }

    @Test
    fun `data class body members are available on JVM`() {
        val v = Vector(3, -4)
        assertEquals(25, v.lengthSquared)
        assertEquals(7, v.manhattan)
        assertEquals(Vector(4, -2), v + Vector(1, 2))
        assertEquals(Vector(6, -8), v.scaled(2))
    }

    @Test
    fun `data class with body crosses FFM`() {
        Calculator(3).use { calc ->
            val v = calc.toVector()
            assertEquals(Vector(3, 6), v)
            assertEquals(45, v.lengthSquared)
            assertEquals(Vector(4, 5), calc.addVector(Vector(1, 2)))
        }
    }

    @Test
    fun `JVM and native compute the same derived value`() {
        Calculator(0).use { calc ->
            val v = Vector(7, -2)
            assertEquals(v.lengthSquared, calc.vectorLengthSquared(v))
        }
    }

    @Test
    fun `data class equals override is preserved on JVM`() {
        val a = BinaryPayload("p", byteArrayOf(1, 2, 3))
        val b = BinaryPayload("p", byteArrayOf(1, 2, 3))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
