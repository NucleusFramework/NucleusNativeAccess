package com.example.systeminfo

import kotlin.test.Test
import kotlin.test.assertTrue

class SystemDesktopTest {

    @Test
    fun `hostname is not empty`() {
        SystemDesktop().use { desktop ->
            assertTrue(desktop.getHostname().isNotBlank())
        }
    }

    @Test
    fun `cpu model is detected`() {
        SystemDesktop().use { desktop ->
            assertTrue(desktop.getCpuModel() != "Unknown")
        }
    }

    @Test
    fun `cpu cores greater than zero`() {
        SystemDesktop().use { desktop ->
            assertTrue(desktop.getCpuCoreCount() > 0)
        }
    }

    @Test
    fun `total memory is positive`() {
        SystemDesktop().use { desktop ->
            assertTrue(desktop.getTotalMemoryMB() > 0)
        }
    }

    @Test
    fun `available memory is positive`() {
        SystemDesktop().use { desktop ->
            assertTrue(desktop.getAvailableMemoryMB() > 0)
        }
    }

    @Test
    fun `uptime is positive`() {
        SystemDesktop().use { desktop ->
            assertTrue(desktop.getUptime() > 0.0)
        }
    }

    @Test
    fun `kernel version is not empty`() {
        SystemDesktop().use { desktop ->
            assertTrue(desktop.getKernelVersion().isNotBlank())
        }
    }

    @Test
    fun `capture screen is a non-empty BMP or PNG image`() {
        SystemDesktop().use { desktop ->
            kotlinx.coroutines.runBlocking {
                val screen = desktop.captureScreen()
                assertTrue(screen.isNotEmpty())
                // Linux/Windows encode BMP ("BM"), macOS encodes PNG (0x89 "PNG")
                val isBmp = screen[0] == 'B'.code.toByte() && screen[1] == 'M'.code.toByte()
                val isPng = screen.size >= 4 && screen[0] == 0x89.toByte() &&
                    screen[1] == 'P'.code.toByte() && screen[2] == 'N'.code.toByte() && screen[3] == 'G'.code.toByte()
                assertTrue(isBmp || isPng, "unexpected image header: ${screen.take(4)}")
            }
        }
    }
}
