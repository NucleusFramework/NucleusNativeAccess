package com.example.calculator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class InterfaceTest {

    // ========== Ruler - Measurable + Printable (15 tests) ==========

    @Test fun `Ruler - measure returns length`() {
        Ruler(42.5).use { ruler ->
            assertEquals(42.5, ruler.measure(), 0.0001)
        }
    }

    @Test fun `Ruler - measure with zero length`() {
        Ruler(0.0).use { ruler ->
            assertEquals(0.0, ruler.measure(), 0.0001)
        }
    }

    @Test fun `Ruler - measure with small length`() {
        Ruler(0.001).use { ruler ->
            assertEquals(0.001, ruler.measure(), 0.000001)
        }
    }

    @Test fun `Ruler - measure with large length`() {
        Ruler(1000.0).use { ruler ->
            assertEquals(1000.0, ruler.measure(), 0.0001)
        }
    }

    @Test fun `Ruler - measure with negative length`() {
        Ruler(-5.5).use { ruler ->
            assertEquals(-5.5, ruler.measure(), 0.0001)
        }
    }

    @Test fun `Ruler - measure with decimal precision`() {
        Ruler(3.14159).use { ruler ->
            assertEquals(3.14159, ruler.measure(), 0.00001)
        }
    }

    @Test fun `Ruler - unit is cm`() {
        Ruler(10.0).use { ruler ->
            assertEquals("cm", ruler.unit)
        }
    }

    @Test fun `Ruler - unit consistency across lengths`() {
        Ruler(5.0).use { ruler1 ->
            Ruler(100.0).use { ruler2 ->
                assertEquals(ruler1.unit, ruler2.unit)
                assertEquals("cm", ruler1.unit)
            }
        }
    }

    @Test fun `Ruler - prettyPrint format`() {
        Ruler(15.0).use { ruler ->
            assertEquals("15.0 cm", ruler.prettyPrint())
        }
    }

    @Test fun `Ruler - prettyPrint with decimal`() {
        Ruler(7.5).use { ruler ->
            assertEquals("7.5 cm", ruler.prettyPrint())
        }
    }

    @Test fun `Ruler - prettyPrint with zero`() {
        Ruler(0.0).use { ruler ->
            assertEquals("0.0 cm", ruler.prettyPrint())
        }
    }

    @Test fun `Ruler - implements Measurable`() {
        Ruler(10.0).use { ruler ->
            assertTrue(ruler is Measurable)
        }
    }

    @Test fun `Ruler - implements Printable`() {
        Ruler(10.0).use { ruler ->
            assertTrue(ruler is Printable)
        }
    }

    @Test fun `Ruler - multiple rulers independent`() {
        Ruler(20.0).use { ruler1 ->
            Ruler(30.0).use { ruler2 ->
                assertEquals(20.0, ruler1.measure(), 0.0001)
                assertEquals(30.0, ruler2.measure(), 0.0001)
            }
        }
    }

    @Test fun `Ruler - prettyPrint precision with large values`() {
        Ruler(999.999).use { ruler ->
            assertTrue(ruler.prettyPrint().contains("999.999"))
            assertTrue(ruler.prettyPrint().contains("cm"))
        }
    }

    // ========== Scale - Measurable only (10 tests) ==========

    @Test fun `Scale - measure returns weight`() {
        Scale(75.5).use { scale ->
            assertEquals(75.5, scale.measure(), 0.0001)
        }
    }

    @Test fun `Scale - measure with zero weight`() {
        Scale(0.0).use { scale ->
            assertEquals(0.0, scale.measure(), 0.0001)
        }
    }

    @Test fun `Scale - measure with small weight`() {
        Scale(0.001).use { scale ->
            assertEquals(0.001, scale.measure(), 0.000001)
        }
    }

    @Test fun `Scale - measure with very large weight`() {
        Scale(1e15).use { scale ->
            assertEquals(1e15, scale.measure(), 1e10)
        }
    }

    @Test fun `Scale - measure with decimal precision`() {
        Scale(62.48).use { scale ->
            assertEquals(62.48, scale.measure(), 0.0001)
        }
    }

    @Test fun `Scale - unit is kg`() {
        Scale(50.0).use { scale ->
            assertEquals("kg", scale.unit)
        }
    }

    @Test fun `Scale - implements Measurable`() {
        Scale(10.0).use { scale ->
            assertTrue(scale is Measurable)
        }
    }

    @Test fun `Scale - does not implement Printable`() {
        Scale(10.0).use { scale ->
            assertFalse(Printable::class.isInstance(scale))
        }
    }

    @Test fun `Scale - multiple scales independent`() {
        Scale(60.0).use { scale1 ->
            Scale(80.0).use { scale2 ->
                assertEquals(60.0, scale1.measure(), 0.0001)
                assertEquals(80.0, scale2.measure(), 0.0001)
            }
        }
    }

    @Test fun `Scale - unit consistency across weights`() {
        Scale(1.0).use { scale1 ->
            Scale(1000.0).use { scale2 ->
                assertEquals(scale1.unit, scale2.unit)
                assertEquals("kg", scale1.unit)
            }
        }
    }

    // ========== SmartRuler - Shape + 3 interfaces (25 tests) ==========

    @Test fun `SmartRuler - measure returns current length`() {
        SmartRuler(100.0).use { ruler ->
            assertEquals(100.0, ruler.measure(), 0.0001)
        }
    }

    @Test fun `SmartRuler - measure with zero initial length`() {
        SmartRuler(0.0).use { ruler ->
            assertEquals(0.0, ruler.measure(), 0.0001)
        }
    }

    @Test fun `SmartRuler - measure with decimal precision`() {
        SmartRuler(33.33).use { ruler ->
            assertEquals(33.33, ruler.measure(), 0.0001)
        }
    }

    @Test fun `SmartRuler - unit is mm`() {
        SmartRuler(50.0).use { ruler ->
            assertEquals("mm", ruler.unit)
        }
    }

    @Test fun `SmartRuler - prettyPrint includes measurement`() {
        SmartRuler(25.0).use { ruler ->
            val pp = ruler.prettyPrint()
            assertTrue(pp.contains("25.0"))
            assertTrue(pp.contains("mm"))
        }
    }

    @Test fun `SmartRuler - prettyPrint includes description`() {
        SmartRuler(50.0).use { ruler ->
            val pp = ruler.prettyPrint()
            assertTrue(pp.contains("Shape: smart-ruler"))
        }
    }

    @Test fun `SmartRuler - prettyPrint complete format`() {
        SmartRuler(10.0).use { ruler ->
            assertTrue(ruler.prettyPrint().contains("10.0 mm"))
        }
    }

    @Test fun `SmartRuler - describe inherited from Shape`() {
        SmartRuler(50.0).use { ruler ->
            assertEquals("Shape: smart-ruler", ruler.describe())
        }
    }

    @Test fun `SmartRuler - color inherited from Shape default`() {
        SmartRuler(50.0).use { ruler ->
            assertEquals("red", ruler.color)
        }
    }

    @Test fun `SmartRuler - color can be changed`() {
        SmartRuler(50.0).use { ruler ->
            ruler.color = "blue"
            assertEquals("blue", ruler.color)
        }
    }

    @Test fun `SmartRuler - color multiple changes`() {
        SmartRuler(30.0).use { ruler ->
            ruler.color = "green"
            assertEquals("green", ruler.color)
            ruler.color = "yellow"
            assertEquals("yellow", ruler.color)
        }
    }

    @Test fun `SmartRuler - area override calculation`() {
        SmartRuler(100.0).use { ruler ->
            assertEquals(1.0, ruler.area(), 0.0001)
        }
    }

    @Test fun `SmartRuler - area with different values`() {
        SmartRuler(50.0).use { ruler ->
            assertEquals(0.5, ruler.area(), 0.0001)
        }
    }

    @Test fun `SmartRuler - area zero when no length`() {
        SmartRuler(0.0).use { ruler ->
            assertEquals(0.0, ruler.area(), 0.0001)
        }
    }

    @Test fun `SmartRuler - reset sets to zero`() {
        SmartRuler(100.0).use { ruler ->
            assertEquals(100.0, ruler.currentValue(), 0.0001)
            ruler.reset()
            assertEquals(0.0, ruler.currentValue(), 0.0001)
        }
    }

    @Test fun `SmartRuler - reset affects measure`() {
        SmartRuler(75.0).use { ruler ->
            ruler.reset()
            assertEquals(0.0, ruler.measure(), 0.0001)
        }
    }

    @Test fun `SmartRuler - reset affects area`() {
        SmartRuler(50.0).use { ruler ->
            ruler.reset()
            assertEquals(0.0, ruler.area(), 0.0001)
        }
    }

    @Test fun `SmartRuler - prettyPrint after reset`() {
        SmartRuler(25.0).use { ruler ->
            ruler.reset()
            val pp = ruler.prettyPrint()
            assertTrue(pp.contains("0.0 mm"))
        }
    }

    @Test fun `SmartRuler - double reset`() {
        SmartRuler(50.0).use { ruler ->
            ruler.reset()
            ruler.reset()
            assertEquals(0.0, ruler.currentValue(), 0.0001)
        }
    }

    @Test fun `SmartRuler - measure after area call`() {
        SmartRuler(200.0).use { ruler ->
            ruler.area()
            assertEquals(200.0, ruler.measure(), 0.0001)
        }
    }

    @Test fun `SmartRuler - currentValue returns current length`() {
        SmartRuler(123.45).use { ruler ->
            assertEquals(123.45, ruler.currentValue(), 0.0001)
        }
    }

    @Test fun `SmartRuler - implements Measurable interface`() {
        SmartRuler(50.0).use { ruler ->
            assertTrue(ruler is Measurable)
        }
    }

    @Test fun `SmartRuler - implements Printable interface`() {
        SmartRuler(50.0).use { ruler ->
            assertTrue(ruler is Printable)
        }
    }

    @Test fun `SmartRuler - implements Resettable interface`() {
        SmartRuler(50.0).use { ruler ->
            assertTrue(ruler is Resettable)
        }
    }

    @Test fun `SmartRuler - is instance of Shape`() {
        SmartRuler(50.0).use { ruler ->
            assertTrue(ruler is Shape)
        }
    }

    @Test fun `SmartRuler - summary accessible from Shape`() {
        SmartRuler(40.0).use { ruler ->
            val summary = ruler.summary()
            assertTrue(summary.isNotEmpty())
        }
    }

    // ========== MeasurementFactory (10 tests) ==========

    @Test fun `MeasurementFactory - createRuler returns Ruler with correct length`() {
        MeasurementFactory().use { factory ->
            factory.createRuler(30.0).use { ruler ->
                assertEquals(30.0, ruler.measure(), 0.0001)
                assertEquals("cm", ruler.unit)
            }
        }
    }

    @Test fun `MeasurementFactory - createRuler with zero length`() {
        MeasurementFactory().use { factory ->
            factory.createRuler(0.0).use { ruler ->
                assertEquals(0.0, ruler.measure(), 0.0001)
            }
        }
    }

    @Test fun `MeasurementFactory - createRuler with decimal`() {
        MeasurementFactory().use { factory ->
            factory.createRuler(12.75).use { ruler ->
                assertEquals(12.75, ruler.measure(), 0.0001)
            }
        }
    }

    @Test fun `MeasurementFactory - createScale returns Scale with correct weight`() {
        MeasurementFactory().use { factory ->
            factory.createScale(80.0).use { scale ->
                assertEquals(80.0, scale.measure(), 0.0001)
                assertEquals("kg", scale.unit)
            }
        }
    }

    @Test fun `MeasurementFactory - createScale with zero weight`() {
        MeasurementFactory().use { factory ->
            factory.createScale(0.0).use { scale ->
                assertEquals(0.0, scale.measure(), 0.0001)
            }
        }
    }

    @Test fun `MeasurementFactory - factory instances independent`() {
        MeasurementFactory().use { factory1 ->
            MeasurementFactory().use { factory2 ->
                factory1.createRuler(20.0).use { ruler1 ->
                    factory2.createRuler(30.0).use { ruler2 ->
                        assertEquals(20.0, ruler1.measure(), 0.0001)
                        assertEquals(30.0, ruler2.measure(), 0.0001)
                    }
                }
            }
        }
    }

    @Test fun `MeasurementFactory - create multiple rulers from same factory`() {
        MeasurementFactory().use { factory ->
            factory.createRuler(10.0).use { ruler1 ->
                factory.createRuler(20.0).use { ruler2 ->
                    factory.createRuler(30.0).use { ruler3 ->
                        assertEquals(10.0, ruler1.measure(), 0.0001)
                        assertEquals(20.0, ruler2.measure(), 0.0001)
                        assertEquals(30.0, ruler3.measure(), 0.0001)
                    }
                }
            }
        }
    }

    @Test fun `MeasurementFactory - create ruler and scale from same factory`() {
        MeasurementFactory().use { factory ->
            factory.createRuler(25.0).use { ruler ->
                factory.createScale(60.0).use { scale ->
                    assertEquals(25.0, ruler.measure(), 0.0001)
                    assertEquals(60.0, scale.measure(), 0.0001)
                    assertEquals("cm", ruler.unit)
                    assertEquals("kg", scale.unit)
                }
            }
        }
    }

    @Test fun `MeasurementFactory - created objects implement correct interfaces`() {
        MeasurementFactory().use { factory ->
            factory.createRuler(50.0).use { ruler ->
                factory.createScale(50.0).use { scale ->
                    assertTrue(ruler is Measurable)
                    assertTrue(ruler is Printable)
                    assertTrue(scale is Measurable)
                    assertFalse(Printable::class.isInstance(scale))
                }
            }
        }
    }

    // ========== Interface polymorphism (10 tests) ==========

    @Test fun `polymorphism - Ruler as Measurable type`() {
        Ruler(10.0).use { ruler ->
            val measurable: Measurable = ruler
            assertEquals(10.0, measurable.measure(), 0.0001)
            assertEquals("cm", measurable.unit)
        }
    }

    @Test fun `polymorphism - Scale as Measurable type`() {
        Scale(50.0).use { scale ->
            val measurable: Measurable = scale
            assertEquals(50.0, measurable.measure(), 0.0001)
            assertEquals("kg", measurable.unit)
        }
    }

    @Test fun `polymorphism - SmartRuler as Measurable type`() {
        SmartRuler(30.0).use { smartRuler ->
            val measurable: Measurable = smartRuler
            assertEquals(30.0, measurable.measure(), 0.0001)
            assertEquals("mm", measurable.unit)
        }
    }

    @Test fun `polymorphism - different implementors same interface`() {
        Ruler(10.0).use { ruler ->
            Scale(20.0).use { scale ->
                val m1: Measurable = ruler
                val m2: Measurable = scale
                assertEquals(10.0, m1.measure(), 0.0001)
                assertEquals(20.0, m2.measure(), 0.0001)
            }
        }
    }

    @Test fun `polymorphism - Ruler as Printable type`() {
        Ruler(15.0).use { ruler ->
            val printable: Printable = ruler
            assertEquals("15.0 cm", printable.prettyPrint())
        }
    }

    @Test fun `polymorphism - SmartRuler as Printable type`() {
        SmartRuler(20.0).use { smartRuler ->
            val printable: Printable = smartRuler
            assertTrue(printable.prettyPrint().contains("20.0"))
        }
    }

    @Test fun `polymorphism - SmartRuler as Resettable type`() {
        SmartRuler(50.0).use { smartRuler ->
            val resettable: Resettable = smartRuler
            resettable.reset()
            assertEquals(0.0, smartRuler.currentValue(), 0.0001)
        }
    }

    @Test fun `polymorphism - SmartRuler as Shape type`() {
        SmartRuler(40.0).use { smartRuler ->
            val shape: Shape = smartRuler
            assertEquals("Shape: smart-ruler", shape.describe())
        }
    }

    @Test fun `polymorphism - multiple implementations alive simultaneously`() {
        Ruler(5.0).use { ruler ->
            Scale(10.0).use { scale ->
                SmartRuler(15.0).use { smartRuler ->
                    val m1: Measurable = ruler
                    val m2: Measurable = scale
                    val m3: Measurable = smartRuler
                    assertEquals(5.0, m1.measure(), 0.0001)
                    assertEquals(10.0, m2.measure(), 0.0001)
                    assertEquals(15.0, m3.measure(), 0.0001)
                }
            }
        }
    }

    @Test fun `polymorphism - measure through interface on all implementors`() {
        Ruler(8.0).use { ruler ->
            Scale(16.0).use { scale ->
                SmartRuler(32.0).use { smartRuler ->
                    val items: List<Measurable> = listOf(ruler, scale, smartRuler)
                    assertEquals(8.0, items[0].measure(), 0.0001)
                    assertEquals(16.0, items[1].measure(), 0.0001)
                    assertEquals(32.0, items[2].measure(), 0.0001)
                }
            }
        }
    }

    // ========== Edge cases (10 tests) ==========

    @Test fun `edge case - Ruler with zero length`() {
        Ruler(0.0).use { ruler ->
            assertEquals(0.0, ruler.measure(), 0.0001)
            assertEquals("0.0 cm", ruler.prettyPrint())
        }
    }

    @Test fun `edge case - Scale with zero weight`() {
        Scale(0.0).use { scale ->
            assertEquals(0.0, scale.measure(), 0.0001)
        }
    }

    @Test fun `edge case - SmartRuler with zero initial length`() {
        SmartRuler(0.0).use { ruler ->
            assertEquals(0.0, ruler.measure(), 0.0001)
            assertEquals(0.0, ruler.area(), 0.0001)
        }
    }

    @Test fun `edge case - SmartRuler double reset`() {
        SmartRuler(100.0).use { ruler ->
            ruler.reset()
            assertEquals(0.0, ruler.currentValue(), 0.0001)
            ruler.reset()
            assertEquals(0.0, ruler.currentValue(), 0.0001)
        }
    }

    @Test fun `edge case - SmartRuler measure after area call`() {
        SmartRuler(200.0).use { ruler ->
            val area = ruler.area()
            assertEquals(2.0, area, 0.0001)
            assertEquals(200.0, ruler.measure(), 0.0001)
        }
    }

    @Test fun `edge case - very large value Ruler`() {
        Ruler(1e15).use { ruler ->
            assertEquals(1e15, ruler.measure(), 1e10)
            assertTrue(ruler.prettyPrint().contains("E"))
        }
    }

    @Test fun `edge case - very small value Ruler`() {
        Ruler(1e-15).use { ruler ->
            assertEquals(1e-15, ruler.measure(), 1e-16)
        }
    }

    @Test fun `edge case - negative length Ruler`() {
        Ruler(-10.5).use { ruler ->
            assertEquals(-10.5, ruler.measure(), 0.0001)
            assertEquals("-10.5 cm", ruler.prettyPrint())
        }
    }

    @Test fun `edge case - prettyPrint with special values`() {
        Ruler(Double.MAX_VALUE / 1e10).use { ruler ->
            val pp = ruler.prettyPrint()
            assertTrue(pp.contains("cm"))
        }
    }

    @Test fun `edge case - SmartRuler reset to zero affects all methods`() {
        SmartRuler(100.0).use { ruler ->
            ruler.reset()
            assertEquals(0.0, ruler.currentValue(), 0.0001)
            assertEquals(0.0, ruler.measure(), 0.0001)
            assertEquals(0.0, ruler.area(), 0.0001)
            assertTrue(ruler.prettyPrint().contains("0.0 mm"))
        }
    }
}
