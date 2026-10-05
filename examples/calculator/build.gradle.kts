import io.github.kdroidfilter.nucleus.desktop.application.dsl.TargetFormat

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose") version "1.12.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
    id("org.jetbrains.compose.hot-reload") version "1.3.0-beta01"
    id("io.github.kdroidfilter.nucleus") version "1.7.2"
    id("dev.nucleusframework.nna")
}

kotlin {
    jvmToolchain(25)

    // Native target: use the real platform name (KMP convention)
    val hostOs = System.getProperty("os.name")
    val nativeTarget = when {
        hostOs == "Mac OS X" -> macosArm64()
        hostOs == "Linux" -> linuxX64()
        hostOs.startsWith("Windows") -> mingwX64()
        else -> error("Unsupported host OS: $hostOs")
    }

    jvm()

    sourceSets {
        jvmMain {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("org.jetbrains.compose.runtime:runtime:1.12.1")
                implementation("io.github.kdroidfilter:nucleus.graalvm-runtime:1.7.2")
            }
        }
        jvmTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(kotlin("test-junit5"))
            }
        }
    }
}

composeCompiler {
    targetKotlinPlatforms.set(setOf(org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.jvm))
}

kotlinNativeExport {
    nativeLibName = "calculator"
    nativePackage = "com.example.calculator"
}

nucleus.application {
    mainClass = "com.example.calculator.MainKt"
    jvmArgs += listOf("--enable-native-access=ALL-UNNAMED")

    graalvm {
        isEnabled = true
        javaLanguageVersion = 25
        jvmVendor = JvmVendorSpec.BELLSOFT
        imageName = "native-calculator"
        march = "compatibility"
        buildArgs.addAll(
            "-H:+AddAllCharsets",
            "-Djava.awt.headless=false",
            "-Os",
            "-H:-IncludeMethodData",
            "--enable-native-access=ALL-UNNAMED",
        )
    }

    nativeDistributions {
        targetFormats(TargetFormat.Deb, TargetFormat.Nsis, TargetFormat.Dmg)
        appName = "Native Calculator"
        packageName = "com.example.nativecalculator"
        packageVersion = "1.0.0"
        description = "Compose Desktop calculator powered by Kotlin/Native via FFM"
        homepage = "https://github.com/kdroidFilter/KotlinNativeExport"

        linux {
            debMaintainer = "dev@example.com"
        }

        windows {
            upgradeUuid = "a1b2c3d4-5678-9012-abcd-ef0123456789"
            nsis {
                oneClick = false
                allowElevation = true
                perMachine = true
                allowToChangeInstallationDirectory = true
                createDesktopShortcut = true
                createStartMenuShortcut = true
                runAfterFinish = true
            }
        }

        macOS {
            bundleID = "com.example.nativecalculator"
            appCategory = "public.app-category.utilities"
            dockName = "NativeCalc"
            dmg {
                title = "Native Calculator"
                iconSize = 128
            }
        }
    }
}

