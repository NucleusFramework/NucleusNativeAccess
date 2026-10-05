package dev.nucleusframework.nna.plugin.analysis

import dev.nucleusframework.nna.plugin.ir.KneType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PsiSourceParserIncrementalTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val parser = PsiSourceParser()

    private fun source(name: String, content: String, isCommon: Boolean = false): PsiSourceParser.Source {
        val file = File(tmp.root, name).apply { parentFile.mkdirs(); writeText(content.trimIndent()) }
        return PsiSourceParser.Source(file, isCommon)
    }

    private fun parse(sources: List<PsiSourceParser.Source>, previous: PsiSourceParser.Result? = null) =
        parser.parseIncremental(sources, "demo", previous?.files.orEmpty())

    /** An incremental result must always be identical to a from-scratch parse. */
    private fun assertMatchesFullParse(sources: List<PsiSourceParser.Source>, result: PsiSourceParser.Result) {
        val full = parse(sources).module
        assertEquals(full, result.module)
        assertEquals(full.packages.toList(), result.module.packages.toList())
    }

    @Test
    fun `unchanged sources are not re-parsed`() {
        val sources = listOf(
            source("A.kt", "package demo\nclass A { fun b(): B = B() }"),
            source("B.kt", "package demo\nclass B { fun x(): Int = 1 }"),
        )
        val first = parse(sources)
        assertEquals(2, first.parsedFiles)

        val second = parse(sources, first)
        assertEquals(0, second.parsedFiles)
        assertEquals(first.module, second.module)
    }

    @Test
    fun `body-only edit re-parses only the edited file`() {
        val a = source("A.kt", "package demo\nclass A { fun b(): B = B() }")
        val b = source("B.kt", "package demo\nclass B { fun x(): Int = 1 }")
        val first = parse(listOf(a, b))

        b.file.writeText("package demo\nclass B { fun x(): Int = 42 }")
        val second = parse(listOf(a, b), first)

        assertEquals(1, second.parsedFiles)
        assertMatchesFullParse(listOf(a, b), second)
    }

    @Test
    fun `adding a referenced type re-parses dependent files`() {
        val a = source("A.kt", "package demo\nclass A { fun widget(): Widget? = null }")
        val other = source("Other.kt", "package demo\nclass Other { fun x(): Int = 1 }")
        val first = parse(listOf(a, other))
        val methodsBefore = first.module.classes.single { it.simpleName == "A" }.methods
        assertTrue("Unknown types are dropped", methodsBefore.none { it.name == "widget" && it.returnType != KneType.UNIT })

        val widget = source("Widget.kt", "package demo\nclass Widget")
        val sources = listOf(a, other, widget)
        val second = parse(sources, first)

        // Widget.kt is new and A.kt looked up `Widget`; Other.kt is untouched
        assertEquals(2, second.parsedFiles)
        val widgetFn = second.module.classes.single { it.simpleName == "A" }.methods.single { it.name == "widget" }
        assertEquals(KneType.NULLABLE(KneType.OBJECT("demo.Widget", "Widget")), widgetFn.returnType)
        assertMatchesFullParse(sources, second)
    }

    @Test
    fun `data class field change propagates to users of the data class`() {
        val point = source("Point.kt", "package demo\ndata class Point(val x: Int, val y: Int)")
        val user = source("User.kt", "package demo\nclass Canvas { fun draw(p: Point) {} }")
        val first = parse(listOf(point, user))

        point.file.writeText("package demo\ndata class Point(val x: Int, val y: Int, val z: Int)")
        val second = parse(listOf(point, user), first)

        assertEquals(2, second.parsedFiles)
        val param = second.module.classes.single().methods.single().params.single().type as KneType.DATA_CLASS
        assertEquals(listOf("x", "y", "z"), param.fields.map { it.name })
        assertMatchesFullParse(listOf(point, user), second)
    }

    @Test
    fun `removing a file drops its declarations`() {
        val a = source("A.kt", "package demo\nclass A")
        val b = source("B.kt", "package demo\nclass B { fun a(): A = A() }")
        val first = parse(listOf(a, b))

        val second = parse(listOf(b), first)
        assertEquals(listOf("B"), second.module.classes.map { it.simpleName })
        assertTrue("B looked up A, so it must be re-parsed", second.parsedFiles == 1)
        assertMatchesFullParse(listOf(b), second)
    }

    @Test
    fun `moving a file between common and native invalidates it`() {
        val native = source("Shared.kt", "package demo\ndata class Shared(val v: Int)")
        val first = parse(listOf(native))
        assertTrue(first.module.dataClasses.single().isCommon.not())

        val common = native.copy(isCommon = true)
        val second = parse(listOf(common), first)
        assertEquals(1, second.parsedFiles)
        assertTrue(second.module.dataClasses.single().isCommon)
    }

    @Test
    fun `cache survives serialization round trip`() {
        val sources = listOf(
            source("Api.kt", """
                package demo
                enum class Mode { A, B }
                data class Point(val x: Int, val y: Double)
                class Api {
                    fun flags(): List<Boolean> = emptyList()
                    fun mode(m: Mode): String = ""
                    fun point(cb: (Point) -> Unit) {}
                }
            """),
        )
        val first = parse(sources)
        val cacheFile = File(tmp.root, "cache/parse-cache.bin")
        ParseCache("key", first.files, null).write(cacheFile)

        assertNull(ParseCache.read(cacheFile, "other-key"))
        val restored = ParseCache.read(cacheFile, "key")!!
        assertEquals(first.files, restored.files)

        val method = restored.files.values.single().fragment.classes.single().methods.first { it.name == "flags" }
        assertSame(KneType.BOOLEAN, (method.returnType as KneType.LIST).elementType)

        val second = parser.parseIncremental(sources, "demo", restored.files)
        assertEquals(0, second.parsedFiles)
        assertEquals(first.module, second.module)
    }

    @Test
    fun `corrupt cache is ignored`() {
        val cacheFile = File(tmp.root, "parse-cache.bin").apply { writeText("garbage") }
        assertNull(ParseCache.read(cacheFile, "key"))
    }
}
