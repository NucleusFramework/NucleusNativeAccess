package com.example.calculator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnumTest {

    // ═══════════════════════════════════════════════════════════════════════════
    // Mutable properties (var): String, Double, Boolean
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `var String property set and get`() {
        Calculator(0).use { calc ->
            assertEquals("", calc.label)
            calc.label = "test"
            assertEquals("test", calc.label)
        }
    }

    @Test
    fun `var String property unicode`() {
        Calculator(0).use { calc ->
            calc.label = "日本語テスト"
            assertEquals("日本語テスト", calc.label)
        }
    }

    @Test
    fun `var Double property set and get`() {
        Calculator(0).use { calc ->
            assertEquals(1.0, calc.scale, 0.001)
            calc.scale = 2.5
            assertEquals(2.5, calc.scale, 0.001)
        }
    }

    @Test
    fun `var Boolean property set and get`() {
        Calculator(0).use { calc ->
            assertTrue(calc.enabled)
            calc.enabled = false
            assertFalse(calc.enabled)
            calc.enabled = true
            assertTrue(calc.enabled)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Phase 2: Enum type
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `enum has correct entries`() {
        assertEquals(3, Operation.entries.size)
        assertEquals("ADD", Operation.ADD.name)
        assertEquals("SUBTRACT", Operation.SUBTRACT.name)
        assertEquals("MULTIPLY", Operation.MULTIPLY.name)
    }

    @Test
    fun `enum ordinals match`() {
        assertEquals(0, Operation.ADD.ordinal)
        assertEquals(1, Operation.SUBTRACT.ordinal)
        assertEquals(2, Operation.MULTIPLY.ordinal)
    }

    @Test
    fun `enum as parameter - ADD`() {
        Calculator(0).use { calc ->
            assertEquals(5, calc.applyOp(Operation.ADD, 5))
        }
    }

    @Test
    fun `enum as parameter - SUBTRACT`() {
        Calculator(10).use { calc ->
            assertEquals(7, calc.applyOp(Operation.SUBTRACT, 3))
        }
    }

    @Test
    fun `enum as parameter - MULTIPLY`() {
        Calculator(4).use { calc ->
            assertEquals(12, calc.applyOp(Operation.MULTIPLY, 3))
        }
    }

    @Test
    fun `enum as return value`() {
        Calculator(0).use { calc ->
            assertEquals(Operation.ADD, calc.getLastOp())
        }
    }

    @Test
    fun `enum as mutable property`() {
        Calculator(0).use { calc ->
            assertEquals(Operation.ADD, calc.lastOperation)
            calc.applyOp(Operation.MULTIPLY, 2)
            assertEquals(Operation.MULTIPLY, calc.lastOperation)
        }
    }

    @Test
    fun `enum roundtrip through all values`() {
        Calculator(1).use { calc ->
            for (op in Operation.entries) {
                calc.applyOp(op, 1)
                assertEquals(op, calc.getLastOp())
            }
        }
    }

    @Test
    fun `one-line enum has correct entries`() {
        assertEquals(listOf("RED", "GREEN", "BLUE"), Color.entries.map { it.name })
        assertEquals(0, Color.RED.ordinal)
        assertEquals(1, Color.GREEN.ordinal)
        assertEquals(2, Color.BLUE.ordinal)
    }

    @Test
    fun `enum with constructor - properties match`() {
        assertEquals(listOf("SUCCESS", "ERROR", "PENDING"), Status.entries.map { it.name })
        assertEquals(0, Status.SUCCESS.code)
        assertEquals(1, Status.ERROR.code)
        assertEquals(2, Status.PENDING.code)
    }

    @Test
    fun `enum with constructor - as return value`() {
        Calculator(10).use { calc ->
            assertEquals(Status.SUCCESS, calc.getStatus())
        }
        Calculator(-5).use { calc ->
            assertEquals(Status.ERROR, calc.getStatus())
        }
        Calculator(0).use { calc ->
            assertEquals(Status.PENDING, calc.getStatus())
        }
    }

    @Test
    fun `enum with constructor - as mutable property`() {
        Calculator(0).use { calc ->
            assertEquals(Status.SUCCESS, calc.lastStatus)
            calc.lastStatus = Status.PENDING
            assertEquals(Status.PENDING, calc.lastStatus)
        }
    }

    @Test
    fun `enum with multi-param constructor - properties match`() {
        assertEquals(2, HttpStatus.entries.size)
        assertEquals(200, HttpStatus.OK.code)
        assertEquals("OK", HttpStatus.OK.label)
        assertEquals(404, HttpStatus.NOT_FOUND.code)
        assertEquals("Not Found", HttpStatus.NOT_FOUND.label)
    }

    @Test
    fun `enum with multi-param constructor - as return value`() {
        Calculator(1).use { calc ->
            assertEquals(HttpStatus.OK, calc.getHttpStatus())
        }
        Calculator(-1).use { calc ->
            assertEquals(HttpStatus.NOT_FOUND, calc.getHttpStatus())
        }
    }

}
