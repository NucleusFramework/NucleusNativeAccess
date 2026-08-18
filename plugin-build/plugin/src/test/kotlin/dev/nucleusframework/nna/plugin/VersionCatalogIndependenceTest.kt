package dev.nucleusframework.nna.plugin

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Regression for https://github.com/NucleusFramework/NucleusNativeAccess/issues/26
 *
 * The plugin must configure a consumer KMP project regardless of how — or whether —
 * that project names dependencies in libs.versions.toml.
 */
class VersionCatalogIndependenceTest {

    @get:Rule
    val testProjectDir = TemporaryFolder()

    @Test
    fun `sync succeeds without a version catalog`() {
        writeConsumerProject(catalogToml = null)
        assertConsumerConfigures()
    }

    @Test
    fun `sync succeeds when catalog has no coroutines aliases`() {
        writeConsumerProject(
            catalogToml = """
                [versions]
                kotlin = "2.3.20"

                [libraries]
                kotlin-test = { module = "org.jetbrains.kotlin:kotlin-test", version.ref = "kotlin" }

                [plugins]
                kotlinMultiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
            """.trimIndent(),
        )
        assertConsumerConfigures()
    }

    @Test
    fun `sync succeeds when coroutines uses a different catalog alias`() {
        writeConsumerProject(
            catalogToml = """
                [versions]
                kotlin = "2.3.20"

                [libraries]
                kotlinx-coroutines = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version = "1.10.2" }

                [plugins]
                kotlinMultiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
            """.trimIndent(),
        )
        assertConsumerConfigures()
    }

    private fun writeConsumerProject(catalogToml: String?) {
        val root = testProjectDir.root
        val pluginBuildDir = File(System.getProperty("nna.pluginBuildDir")).invariantSeparatorsPath
        File(root, "settings.gradle.kts").writeText(
            """
            pluginManagement {
                includeBuild("$pluginBuildDir")
                repositories {
                    gradlePluginPortal()
                    mavenCentral()
                    google()
                }
            }
            dependencyResolutionManagement {
                repositories {
                    mavenCentral()
                    google()
                }
            }
            rootProject.name = "catalog-consumer"
            """.trimIndent(),
        )
        if (catalogToml != null) {
            File(root, "gradle").mkdirs()
            File(root, "gradle/libs.versions.toml").writeText(catalogToml)
        }
        File(root, "build.gradle.kts").writeText(
            """
            plugins {
                kotlin("multiplatform") version "2.3.20"
                id("dev.nucleusframework.nna")
            }

            kotlin {
                jvm()
                $hostNativeTarget
            }

            kotlinNativeExport {
                nativeLibName = "repro"
            }
            """.trimIndent(),
        )
        val nativeSrc = File(root, "src/nativeMain/kotlin/com/example")
        nativeSrc.mkdirs()
        File(nativeSrc, "Repro.kt").writeText(
            """
            package com.example

            class Repro {
                fun ping(): String = "ok"
            }
            """.trimIndent(),
        )
    }

    private fun assertConsumerConfigures() {
        val result = GradleRunner.create()
            .withProjectDir(testProjectDir.root)
            .withArguments("help", "generateKneNativeBridges", "--stacktrace")
            .forwardOutput()
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":help")?.outcome)
        val generate = result.task(":generateKneNativeBridges")
        assertTrue(
            "generateKneNativeBridges should run or be up-to-date, was ${generate?.outcome}\n${result.output}",
            generate?.outcome == TaskOutcome.SUCCESS || generate?.outcome == TaskOutcome.UP_TO_DATE,
        )
        assertTrue(
            "configuration must not fail with the catalog Optional.get() crash",
            "No value present" !in result.output,
        )
    }

    private val hostNativeTarget: String
        get() {
            val hostOs = System.getProperty("os.name")
            return when {
                hostOs == "Mac OS X" -> "macosArm64()"
                hostOs == "Linux" -> "linuxX64()"
                hostOs.startsWith("Windows") -> "mingwX64()"
                else -> error("Unsupported host OS: $hostOs")
            }
        }
}
