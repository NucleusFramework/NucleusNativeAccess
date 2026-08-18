package dev.nucleusframework.nna.plugin.analysis

import dev.nucleusframework.nna.plugin.ir.KneType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PsiSourceParserEnumTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `parses simple, one-line, and constructor enum entries`() {
        val source = tmp.newFile("Enums.kt")
        source.writeText(
            """
            package demo

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
            """.trimIndent(),
        )

        val module = PsiSourceParser().parse(listOf(source), "demo", emptyList())
        val enums = module.enums.associateBy { it.simpleName }

        assertEquals(setOf("Operation", "Color", "Status", "HttpStatus"), enums.keys)

        val operation = enums.getValue("Operation")
        assertEquals(listOf("ADD", "SUBTRACT", "MULTIPLY"), operation.entries.map { it.name })
        assertTrue(operation.constructorParams.isEmpty())

        val color = enums.getValue("Color")
        assertEquals(listOf("RED", "GREEN", "BLUE"), color.entries.map { it.name })

        val status = enums.getValue("Status")
        assertEquals(listOf("SUCCESS", "ERROR", "PENDING"), status.entries.map { it.name })
        assertEquals(listOf("0", "1", "2"), status.entries.map { it.constructorArgs.single() })
        assertEquals(1, status.constructorParams.size)
        assertEquals("code", status.constructorParams.single().name)
        assertEquals(KneType.INT, status.constructorParams.single().type)

        val http = enums.getValue("HttpStatus")
        assertEquals(listOf("OK", "NOT_FOUND"), http.entries.map { it.name })
        assertEquals(listOf("200", "\"OK\""), http.entries[0].constructorArgs)
        assertEquals(listOf("404", "\"Not Found\""), http.entries[1].constructorArgs)
        assertEquals(listOf("code", "label"), http.constructorParams.map { it.name })
        assertEquals(KneType.INT, http.constructorParams[0].type)
        assertEquals(KneType.STRING, http.constructorParams[1].type)
    }
}
