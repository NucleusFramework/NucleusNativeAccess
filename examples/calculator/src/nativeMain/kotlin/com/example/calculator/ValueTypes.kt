package com.example.calculator

import kotlin.math.abs

/** Binary operator carrying its own evaluation logic. */
enum class BinaryOp(val symbol: String) {
    PLUS("+") {
        override fun apply(a: Int, b: Int): Int = a + b
    },
    MINUS("-") {
        override fun apply(a: Int, b: Int): Int = a - b
    },
    TIMES("*") {
        override fun apply(a: Int, b: Int): Int = a * b
    };

    abstract fun apply(a: Int, b: Int): Int

    companion object {
        fun fromSymbol(symbol: String): BinaryOp? = entries.firstOrNull { it.symbol == symbol }
    }
}

/** 2D vector with derived helpers. */
data class Vector(val x: Int, val y: Int = 0) {
    val lengthSquared: Int get() = x * x + y * y

    val manhattan: Int get() = abs(x) + abs(y)

    operator fun plus(other: Vector): Vector = Vector(x + other.x, y + other.y)

    fun scaled(factor: Int): Vector = Vector(x * factor, y * factor)
}
