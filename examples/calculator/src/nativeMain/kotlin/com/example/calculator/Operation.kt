package com.example.calculator

enum class Operation {
    ADD,
    SUBTRACT,
    MULTIPLY,
}

enum class Color { RED, GREEN, BLUE }

enum class Status(val code: Int) {
    SUCCESS(0),
    ERROR(1),
    PENDING(2),
}

enum class HttpStatus(val code: Int, val label: String) {
    OK(200, "OK"),
    NOT_FOUND(404, "Not Found"),
}
