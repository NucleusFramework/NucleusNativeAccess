package com.example.calculator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNotEquals
import java.lang.Math.PI

class InheritanceTest {

    // -- Shape (base class) -- 10 tests

    @Test fun `Shape - default area is zero`() {
        Shape("test").use { shape ->
            assertEquals(0.0, shape.area())
        }
    }

    @Test fun `Shape - describe format`() {
        Shape("triangle").use { shape ->
            assertEquals("Shape: triangle", shape.describe())
        }
    }

    @Test fun `Shape - color default is red`() {
        Shape("test").use { shape ->
            assertEquals("red", shape.color)
        }
    }

    @Test fun `Shape - color can be mutated`() {
        Shape("test").use { shape ->
            shape.color = "blue"
            assertEquals("blue", shape.color)
        }
    }

    @Test fun `Shape - summary includes name and area`() {
        Shape("test").use { shape ->
            assertTrue(shape.summary().contains("test"))
            assertTrue(shape.summary().contains("area"))
        }
    }

    @Test fun `Shape - name property accessible`() {
        Shape("myshape").use { shape ->
            assertEquals("myshape", shape.name)
        }
    }

    @Test fun `Shape - multiple instances are independent`() {
        Shape("a").use { s1 ->
            Shape("b").use { s2 ->
                assertEquals("a", s1.name)
                assertEquals("b", s2.name)
                assertNotEquals(s1.name, s2.name)
            }
        }
    }

    @Test fun `Shape - empty name is allowed`() {
        Shape("").use { shape ->
            assertEquals("", shape.name)
            assertEquals("Shape: ", shape.describe())
        }
    }

    @Test fun `Shape - area is always zero regardless of name`() {
        Shape("anything").use { shape ->
            assertEquals(0.0, shape.area())
        }
    }

    @Test fun `Shape - color mutation is idempotent`() {
        Shape("test").use { shape ->
            shape.color = "green"
            shape.color = "green"
            assertEquals("green", shape.color)
        }
    }

    // -- Circle (extends Shape) -- 15 tests

    @Test fun `Circle - area with radius 1`() {
        Circle(1.0).use { circle ->
            assertEquals(PI, circle.area(), 0.0001)
        }
    }

    @Test fun `Circle - area with radius 5`() {
        Circle(5.0).use { circle ->
            assertEquals(PI * 25.0, circle.area(), 0.0001)
        }
    }

    @Test fun `Circle - area with radius half`() {
        Circle(0.5).use { circle ->
            assertEquals(PI * 0.25, circle.area(), 0.0001)
        }
    }

    @Test fun `Circle - area with radius 100`() {
        Circle(100.0).use { circle ->
            assertEquals(PI * 10000.0, circle.area(), 0.0001)
        }
    }

    @Test fun `Circle - circumference formula 2*pi*r`() {
        Circle(5.0).use { circle ->
            assertEquals(2 * PI * 5.0, circle.circumference(), 0.0001)
        }
    }

    @Test fun `Circle - inherits describe from Shape`() {
        Circle(3.0).use { circle ->
            assertEquals("Shape: circle", circle.describe())
        }
    }

    @Test fun `Circle - inherits color default`() {
        Circle(3.0).use { circle ->
            assertEquals("red", circle.color)
        }
    }

    @Test fun `Circle - color is mutable`() {
        Circle(3.0).use { circle ->
            circle.color = "green"
            assertEquals("green", circle.color)
        }
    }

    @Test fun `Circle - summary override contains Circle`() {
        Circle(2.0).use { circle ->
            val s = circle.summary()
            assertTrue(s.contains("Circle"))
        }
    }

    @Test fun `Circle - summary format includes radius`() {
        Circle(2.0).use { circle ->
            val s = circle.summary()
            assertTrue(s.contains("r=2.0"))
        }
    }

    @Test fun `Circle - small radius precision (1e-10)`() {
        Circle(1e-10).use { circle ->
            val expected = PI * 1e-20
            assertEquals(expected, circle.area(), 1e-25)
        }
    }

    @Test fun `Circle - large radius precision (1e6)`() {
        Circle(1e6).use { circle ->
            val expected = PI * 1e12
            assertEquals(expected, circle.area(), 1e7)
        }
    }

    @Test fun `Circle - two circles independent areas`() {
        Circle(2.0).use { c1 ->
            Circle(3.0).use { c2 ->
                assertEquals(PI * 4.0, c1.area(), 0.0001)
                assertEquals(PI * 9.0, c2.area(), 0.0001)
            }
        }
    }

    @Test fun `Circle - name property is circle`() {
        Circle(5.0).use { circle ->
            assertEquals("circle", circle.name)
        }
    }

    @Test fun `Circle - zero radius area is zero`() {
        Circle(0.0).use { circle ->
            assertEquals(0.0, circle.area())
        }
    }

    // -- Rectangle (extends Shape) -- 12 tests

    @Test fun `Rectangle - area is width times height`() {
        Rectangle(4.0, 5.0).use { rect ->
            assertEquals(20.0, rect.area(), 0.0001)
        }
    }

    @Test fun `Rectangle - perimeter formula 2*(w+h)`() {
        Rectangle(4.0, 5.0).use { rect ->
            assertEquals(18.0, rect.perimeter(), 0.0001)
        }
    }

    @Test fun `Rectangle - inherits describe from Shape`() {
        Rectangle(3.0, 4.0).use { rect ->
            assertEquals("Shape: rectangle", rect.describe())
        }
    }

    @Test fun `Rectangle - summary override contains Rect`() {
        Rectangle(2.0, 3.0).use { rect ->
            val s = rect.summary()
            assertTrue(s.contains("Rect"))
        }
    }

    @Test fun `Rectangle - square case (width equals height)`() {
        Rectangle(5.0, 5.0).use { rect ->
            assertEquals(25.0, rect.area())
            assertEquals(20.0, rect.perimeter())
        }
    }

    @Test fun `Rectangle - wide rectangle wider than tall`() {
        Rectangle(10.0, 2.0).use { rect ->
            assertEquals(20.0, rect.area())
            assertEquals(24.0, rect.perimeter())
        }
    }

    @Test fun `Rectangle - tall rectangle taller than wide`() {
        Rectangle(2.0, 10.0).use { rect ->
            assertEquals(20.0, rect.area())
            assertEquals(24.0, rect.perimeter())
        }
    }

    @Test fun `Rectangle - color mutation`() {
        Rectangle(3.0, 4.0).use { rect ->
            assertEquals("red", rect.color)
            rect.color = "yellow"
            assertEquals("yellow", rect.color)
        }
    }

    @Test fun `Rectangle - summary format includes dimensions`() {
        Rectangle(2.0, 3.0).use { rect ->
            val s = rect.summary()
            assertTrue(s.contains("2.0x3.0"))
        }
    }

    @Test fun `Rectangle - zero width area is zero`() {
        Rectangle(0.0, 5.0).use { rect ->
            assertEquals(0.0, rect.area())
        }
    }

    @Test fun `Rectangle - zero height area is zero`() {
        Rectangle(5.0, 0.0).use { rect ->
            assertEquals(0.0, rect.area())
        }
    }

    @Test fun `Rectangle - name property is rectangle`() {
        Rectangle(4.0, 5.0).use { rect ->
            assertEquals("rectangle", rect.name)
        }
    }

    // -- Shape3D hierarchy -- 15 tests

    @Test fun `Cube - area is side squared`() {
        Cube(3.0).use { cube ->
            assertEquals(9.0, cube.area(), 0.0001)
        }
    }

    @Test fun `Cube - volume is side cubed`() {
        Cube(3.0).use { cube ->
            assertEquals(27.0, cube.volume(), 0.0001)
        }
    }

    @Test fun `Cube - describe from Shape (3 levels)`() {
        Cube(2.0).use { cube ->
            assertEquals("Shape: cube", cube.describe())
        }
    }

    @Test fun `Cube - color inherited from Shape`() {
        Cube(2.0).use { cube ->
            assertEquals("red", cube.color)
        }
    }

    @Test fun `Cube - color is mutable`() {
        Cube(2.0).use { cube ->
            cube.color = "gold"
            assertEquals("gold", cube.color)
        }
    }

    @Test fun `Cube - summary override contains Cube`() {
        Cube(2.0).use { cube ->
            val s = cube.summary()
            assertTrue(s.contains("Cube"))
        }
    }

    @Test fun `Cube - summary format includes side`() {
        Cube(2.0).use { cube ->
            val s = cube.summary()
            assertTrue(s.contains("side=2.0"))
        }
    }

    @Test fun `Cube - small cube side tenth`() {
        Cube(0.1).use { cube ->
            assertEquals(0.01, cube.area(), 0.0001)
            assertEquals(0.001, cube.volume(), 0.0001)
        }
    }

    @Test fun `Cube - large cube side 100`() {
        Cube(100.0).use { cube ->
            assertEquals(10000.0, cube.area(), 0.0001)
            assertEquals(1000000.0, cube.volume(), 0.0001)
        }
    }

    @Test fun `Cube - name property is cube`() {
        Cube(3.0).use { cube ->
            assertEquals("cube", cube.name)
        }
    }

    @Test fun `Cylinder - area is pi*r^2`() {
        Cylinder(2.0, 10.0).use { cyl ->
            assertEquals(PI * 4.0, cyl.area(), 0.0001)
        }
    }

    @Test fun `Cylinder - volume is pi*r^2*h`() {
        Cylinder(2.0, 10.0).use { cyl ->
            val expected = PI * 4.0 * 10.0
            assertEquals(expected, cyl.volume(), 0.0001)
        }
    }

    @Test fun `Cylinder - inherits describe from Shape`() {
        Cylinder(1.0, 5.0).use { cyl ->
            assertEquals("Shape: cylinder", cyl.describe())
        }
    }

    @Test fun `Cylinder - summary override contains Cylinder`() {
        Cylinder(1.0, 5.0).use { cyl ->
            val s = cyl.summary()
            assertTrue(s.contains("Cylinder"))
        }
    }

    @Test fun `Cylinder - tall thin cylinder`() {
        Cylinder(0.5, 100.0).use { cyl ->
            val baseArea = PI * 0.25
            val vol = baseArea * 100.0
            assertEquals(baseArea, cyl.area(), 0.0001)
            assertEquals(vol, cyl.volume(), 0.01)
        }
    }

    @Test fun `Cylinder - short wide cylinder`() {
        Cylinder(10.0, 1.0).use { cyl ->
            val baseArea = PI * 100.0
            val vol = baseArea * 1.0
            assertEquals(baseArea, cyl.area(), 0.0001)
            assertEquals(vol, cyl.volume(), 0.01)
        }
    }

    // -- Cross-hierarchy tests -- 15 tests

    @Test fun `multiple subclasses - all alive simultaneously`() {
        Circle(2.0).use { circle ->
            Rectangle(3.0, 4.0).use { rect ->
                Cube(2.0).use { cube ->
                    Cylinder(1.0, 5.0).use { cyl ->
                        assertEquals(PI * 4.0, circle.area(), 0.0001)
                        assertEquals(12.0, rect.area(), 0.0001)
                        assertEquals(8.0, cube.volume(), 0.0001)
                        assertEquals(PI * 5.0, cyl.volume(), 0.0001)
                    }
                }
            }
        }
    }

    @Test fun `child does not affect parent area`() {
        Shape("base").use { shape ->
            Circle(10.0).use { circle ->
                assertEquals(0.0, shape.area())
                assertEquals(PI * 100, circle.area(), 0.0001)
            }
        }
    }

    @Test fun `parent does not affect child area`() {
        Shape("base").use { shape ->
            Rectangle(5.0, 6.0).use { rect ->
                assertEquals(0.0, shape.area())
                assertEquals(30.0, rect.area())
            }
        }
    }

    @Test fun `two circles independent`() {
        Circle(2.0).use { c1 ->
            Circle(5.0).use { c2 ->
                assertEquals(PI * 4.0, c1.area(), 0.0001)
                assertEquals(PI * 25.0, c2.area(), 0.0001)
                assertNotEquals(c1.area(), c2.area())
            }
        }
    }

    @Test fun `circle and rectangle independent`() {
        Circle(3.0).use { circle ->
            Rectangle(3.0, 3.0).use { rect ->
                assertEquals(PI * 9.0, circle.area(), 0.0001)
                assertEquals(9.0, rect.area())
                assertNotEquals(circle.area(), rect.area())
            }
        }
    }

    @Test fun `all 5 types alive at once`() {
        Circle(1.0).use { c ->
            Rectangle(2.0, 3.0).use { r ->
                Shape3D("test3d", 2.0).use { s3d ->
                    Cube(1.0).use { cube ->
                        Cylinder(1.0, 1.0).use { cyl ->
                            assertEquals(PI, c.area(), 0.0001)
                            assertEquals(6.0, r.area())
                            assertEquals(0.0, s3d.area())
                            assertEquals(1.0, cube.volume())
                            assertEquals(PI, cyl.volume(), 0.0001)
                        }
                    }
                }
            }
        }
    }

    @Test fun `creating 50 shapes in loop`() {
        val shapes = mutableListOf<Shape>()
        for (i in 0..49) {
            val s = when (i % 5) {
                0 -> Circle((i + 1).toDouble())
                1 -> Rectangle((i + 1).toDouble(), (i + 2).toDouble())
                2 -> Cube((i + 1).toDouble())
                3 -> Cylinder((i + 1).toDouble() / 2.0, (i + 1).toDouble())
                else -> Shape("shape$i")
            }
            shapes.add(s)
        }
        shapes.forEach { it.close() }
        assertEquals(50, shapes.size)
    }

    @Test fun `two rectangles independent colors`() {
        Rectangle(1.0, 2.0).use { r1 ->
            Rectangle(2.0, 3.0).use { r2 ->
                r1.color = "blue"
                r2.color = "green"
                assertEquals("blue", r1.color)
                assertEquals("green", r2.color)
            }
        }
    }

    @Test fun `two cubes independent colors`() {
        Cube(2.0).use { c1 ->
            Cube(3.0).use { c2 ->
                c1.color = "silver"
                c2.color = "gold"
                assertEquals("silver", c1.color)
                assertEquals("gold", c2.color)
            }
        }
    }

    @Test fun `shape color does not affect child colors`() {
        Shape("base").use { s ->
            Circle(2.0).use { c ->
                s.color = "purple"
                assertEquals("red", c.color)
                assertEquals("purple", s.color)
            }
        }
    }

    @Test fun `circle and rectangle have different names`() {
        Circle(2.0).use { c ->
            Rectangle(3.0, 4.0).use { r ->
                assertEquals("circle", c.name)
                assertEquals("rectangle", r.name)
            }
        }
    }

    @Test fun `cube and cylinder have different names`() {
        Cube(2.0).use { cube ->
            Cylinder(1.0, 5.0).use { cyl ->
                assertEquals("cube", cube.name)
                assertEquals("cylinder", cyl.name)
            }
        }
    }

    @Test fun `multiple rectangles independent areas`() {
        Rectangle(2.0, 3.0).use { r1 ->
            Rectangle(4.0, 5.0).use { r2 ->
                Rectangle(1.0, 1.0).use { r3 ->
                    assertEquals(6.0, r1.area())
                    assertEquals(20.0, r2.area())
                    assertEquals(1.0, r3.area())
                }
            }
        }
    }

    // -- Polymorphism via inheritance -- 10 tests

    @Test fun `Circle is-a Shape`() {
        Circle(5.0).use { circle ->
            val shape: Shape = circle
            assertTrue(shape is Circle)
        }
    }

    @Test fun `Rectangle is-a Shape`() {
        Rectangle(3.0, 4.0).use { rect ->
            val shape: Shape = rect
            assertTrue(shape is Rectangle)
        }
    }

    @Test fun `Cube is-a Shape3D`() {
        Cube(2.0).use { cube ->
            val shape3d: Shape3D = cube
            assertTrue(shape3d is Cube)
        }
    }

    @Test fun `Cube is-a Shape`() {
        Cube(2.0).use { cube ->
            val shape: Shape = cube
            assertTrue(shape is Cube)
            assertTrue(shape is Shape3D)
        }
    }

    @Test fun `Cylinder is-a Shape3D`() {
        Cylinder(1.0, 5.0).use { cyl ->
            val shape3d: Shape3D = cyl
            assertTrue(shape3d is Cylinder)
        }
    }

    @Test fun `Cylinder is-a Shape`() {
        Cylinder(1.0, 5.0).use { cyl ->
            val shape: Shape = cyl
            assertTrue(shape is Cylinder)
            assertTrue(shape is Shape3D)
        }
    }

    @Test fun `Shape3D is-a Shape`() {
        Shape3D("test3d", 2.0).use { s3d ->
            val shape: Shape = s3d
            assertTrue(shape is Shape3D)
        }
    }

    @Test fun `Shape is not Circle`() {
        Shape("test").use { shape ->
            assertTrue(shape !is Circle)
        }
    }

    @Test fun `Circle is not Rectangle`() {
        Circle(2.0).use { circle ->
            assertFalse(Rectangle::class.isInstance(circle))
        }
    }

    @Test fun `Cube is not Cylinder`() {
        Cube(2.0).use { cube ->
            assertFalse(Cylinder::class.isInstance(cube))
        }
    }

    // -- Property inheritance -- 10 tests

    @Test fun `Circle name is circle`() {
        Circle(5.0).use { circle ->
            assertEquals("circle", circle.name)
        }
    }

    @Test fun `Rectangle name is rectangle`() {
        Rectangle(3.0, 4.0).use { rect ->
            assertEquals("rectangle", rect.name)
        }
    }

    @Test fun `Cube name is cube`() {
        Cube(2.0).use { cube ->
            assertEquals("cube", cube.name)
        }
    }

    @Test fun `Cylinder name is cylinder`() {
        Cylinder(1.0, 5.0).use { cyl ->
            assertEquals("cylinder", cyl.name)
        }
    }

    @Test fun `Shape3D has depth property`() {
        Shape3D("test3d", 5.0).use { s3d ->
            assertEquals(5.0, s3d.depth)
        }
    }

    @Test fun `Cube depth equals side`() {
        Cube(3.0).use { cube ->
            assertEquals(3.0, cube.depth)
        }
    }

    @Test fun `Cylinder depth equals height`() {
        Cylinder(2.0, 10.0).use { cyl ->
            assertEquals(10.0, cyl.depth)
        }
    }

    @Test fun `Circle color change does not affect Rectangle`() {
        Circle(2.0).use { c ->
            Rectangle(3.0, 4.0).use { r ->
                c.color = "blue"
                assertEquals("blue", c.color)
                assertEquals("red", r.color)
            }
        }
    }

    @Test fun `Rectangle color change does not affect Circle`() {
        Rectangle(3.0, 4.0).use { r ->
            Circle(2.0).use { c ->
                r.color = "green"
                assertEquals("green", r.color)
                assertEquals("red", c.color)
            }
        }
    }

    @Test fun `Shape color change does not affect Circle`() {
        Shape("base").use { s ->
            Circle(2.0).use { c ->
                s.color = "yellow"
                assertEquals("yellow", s.color)
                assertEquals("red", c.color)
            }
        }
    }

    // -- Override behavior -- 10 tests

    @Test fun `Shape area returns zero`() {
        Shape("any").use { shape ->
            assertEquals(0.0, shape.area())
        }
    }

    @Test fun `Circle area overrides to pi*r^2`() {
        Circle(2.0).use { circle ->
            assertEquals(PI * 4.0, circle.area(), 0.0001)
        }
    }

    @Test fun `same radius different polymorphic dispatch`() {
        val radius = 3.0
        Shape("test").use { s ->
            Circle(radius).use { c ->
                assertNotEquals(s.area(), c.area())
                assertEquals(0.0, s.area())
                assertEquals(PI * 9.0, c.area(), 0.0001)
            }
        }
    }

    @Test fun `Shape summary vs Circle summary different format`() {
        Shape("circle").use { s ->
            Circle(2.0).use { c ->
                val sSummary = s.summary()
                val cSummary = c.summary()
                assertNotEquals(sSummary, cSummary)
                assertTrue(cSummary.contains("Circle"))
            }
        }
    }

    @Test fun `Rectangle summary overrides Shape summary`() {
        Shape("rect").use { s ->
            Rectangle(2.0, 3.0).use { r ->
                val sSummary = s.summary()
                val rSummary = r.summary()
                assertNotEquals(sSummary, rSummary)
                assertTrue(rSummary.contains("Rect"))
            }
        }
    }

    @Test fun `Cube volume overrides Shape3D volume`() {
        Shape3D("custom", 2.0).use { s3d ->
            Cube(2.0).use { cube ->
                assertNotEquals(s3d.volume(), cube.volume())
                assertEquals(8.0, cube.volume())  // side^3 = 2^3 = 8
            }
        }
    }

    @Test fun `Cylinder volume uses overridden area`() {
        Cylinder(2.0, 5.0).use { cyl ->
            val expectedArea = PI * 4.0
            val expectedVol = expectedArea * 5.0
            assertEquals(expectedArea, cyl.area(), 0.0001)
            assertEquals(expectedVol, cyl.volume(), 0.0001)
        }
    }

    @Test fun `Shape3D area delegates to subclass`() {
        Shape3D("base3d", 2.0).use { s3d ->
            Cube(3.0).use { cube ->
                assertEquals(0.0, s3d.area())
                assertEquals(9.0, cube.area())
            }
        }
    }

    @Test fun `Cube summary overrides Shape3D summary`() {
        Shape3D("cube", 2.0).use { s3d ->
            Cube(2.0).use { cube ->
                val s3dSummary = s3d.summary()
                val cubeSummary = cube.summary()
                assertNotEquals(s3dSummary, cubeSummary)
                assertTrue(cubeSummary.contains("Cube"))
            }
        }
    }

    @Test fun `Cylinder summary overrides Shape3D summary`() {
        Shape3D("cylinder", 5.0).use { s3d ->
            Cylinder(2.0, 5.0).use { cyl ->
                val s3dSummary = s3d.summary()
                val cylSummary = cyl.summary()
                assertNotEquals(s3dSummary, cylSummary)
                assertTrue(cylSummary.contains("Cylinder"))
            }
        }
    }

    // -- Edge cases -- 8 tests

    @Test fun `zero radius circle area is zero`() {
        Circle(0.0).use { circle ->
            assertEquals(0.0, circle.area())
        }
    }

    @Test fun `very large radius 1e6`() {
        Circle(1e6).use { circle ->
            val expected = PI * 1e12
            assertEquals(expected, circle.area(), 1e7)
        }
    }

    @Test fun `very small radius 1e-10`() {
        Circle(1e-10).use { circle ->
            val expected = PI * 1e-20
            assertEquals(expected, circle.area(), 1e-25)
        }
    }

    @Test fun `negative width still computes area`() {
        Rectangle(-3.0, 4.0).use { rect ->
            assertEquals(-12.0, rect.area())
        }
    }

    @Test fun `negative height still computes area`() {
        Rectangle(3.0, -4.0).use { rect ->
            assertEquals(-12.0, rect.area())
        }
    }

    @Test fun `cube with side zero has zero area`() {
        Cube(0.0).use { cube ->
            assertEquals(0.0, cube.area())
            assertEquals(0.0, cube.volume())
        }
    }

    @Test fun `cube with side 1 has unit volume`() {
        Cube(1.0).use { cube ->
            assertEquals(1.0, cube.area())
            assertEquals(1.0, cube.volume())
        }
    }

    @Test fun `cylinder with zero height`() {
        Cylinder(5.0, 0.0).use { cyl ->
            assertEquals(PI * 25.0, cyl.area(), 0.0001)
            assertEquals(0.0, cyl.volume())
        }
    }
}
