package dev.nucleusframework.nna.plugin

import dev.nucleusframework.nna.plugin.tasks.GenerateNativeBridgesTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.logging.LogLevel
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.*
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
import java.io.File

/**
 * Main entry point for the kotlin-native-export Gradle plugin.
 *
 * Pipeline (mirrors swift-java's build plugin approach):
 *  1. Scan nativeMain sources → extract public Kotlin API
 *  2. Generate @CName bridge code → added to nativeMain compilation
 *  3. Generate FFM proxy classes → added to jvmMain compilation
 *  4. Configure sharedLib binary on native targets (both debug + release)
 *  5. Set java.library.path on JVM tasks (both dirs, like swift-java)
 *
 * Convention: both debug and release shared libs are always available as
 * binaries. [KotlinNativeExportExtension.buildType] selects which one the
 * test task links against. java.library.path includes both directories so
 * either variant can be picked up at runtime (same pattern as swift-java).
 */
class KotlinNativeExportPlugin : Plugin<Project> {

    override fun apply(project: Project) {

        val extension = project.extensions.create<KotlinNativeExportExtension>("kotlinNativeExport")

        extension.nativeLibName.convention("nativelib")
        extension.nativePackage.convention("")
        extension.buildType.convention("release")

        project.plugins.withId("org.jetbrains.kotlin.multiplatform") {
            project.afterEvaluate { configureKmp(project, extension) }
        }
    }

    private fun configureKmp(project: Project, extension: KotlinNativeExportExtension) {
        val kotlin = project.extensions.getByType<KotlinMultiplatformExtension>()

        val libName = extension.nativeLibName.get()
        val pkg = extension.nativePackage.get()

        // read the jvm target name
        // JVM target should exist otherwise the plugin is of no use
        val jvmTarget = kotlin.targets.filterIsInstance<KotlinJvmTarget>()
            .firstOrNull() ?: throw GradleException("Sorry this plugin required jvm target to work with")

        val jvmMainSourceSetName = jvmTarget.name + "Main"
        val jvmTestSourceSetName = jvmTarget.name + "Test"
        val jvmMainTaskName = jvmMainSourceSetName.replaceFirstChar { it.titlecase() }
        val jvmTaskName = jvmTarget.name.replaceFirstChar { it.titlecase() }

        val nativeBridgesDir = project.layout.buildDirectory.dir("generated/kne/nativeBridges")
        val jvmProxiesDir = project.layout.buildDirectory.dir("generated/kne/jvmProxies")
        val jvmResourcesDir = project.layout.buildDirectory.dir("generated/kne/jvmResources")

        // Detect the first native target and its source sets.
        // Convention: use src/nativeMain if it exists (shared native source set),
        // otherwise fall back to the first native target's main source set (e.g. linuxX64Main).
        val nativeTarget = kotlin.targets
            .filterIsInstance<KotlinNativeTarget>()
            .firstOrNull()


        val nativeTargetTaskName = nativeTarget?.name?.replaceFirstChar { it.titlecase() } ?: "Native"

        val userNativeSrcDirs = mutableListOf<File>()

        // check if native main dir present
        val nativeMainDir = project.projectDir.resolve("src/nativeMain/kotlin")
        if (nativeMainDir.exists()) userNativeSrcDirs.add(nativeMainDir)

        // if native target present use that too
        if (nativeTarget != null) {
            project.logger.log(
                LogLevel.INFO,
                "NATIVE TARGET FOUND :${nativeTarget.konanTarget.name} ALIAS:${nativeTarget.name}"
            )
            val targetMainDir = project.projectDir.resolve("src/${nativeTarget.name}Main/kotlin")
            if (targetMainDir.exists()) userNativeSrcDirs.add(targetMainDir)
        }

        val userNativeSources = project.files(userNativeSrcDirs)

        // Collect commonMain sources for data class discovery
        val commonMainDir = project.projectDir.resolve("src/commonMain/kotlin")
        val commonSources = project.files(if (commonMainDir.exists()) commonMainDir else null)

        // ── PSI parser classpath (kotlin-compiler-embeddable for isolated Worker classloader) ──
        val knePsiScope = project.configurations.dependencyScope("knePsiScope").get()
        val psiClasspath = project.configurations.resolvable("knePsiClasspath") {
            extendsFrom(knePsiScope)
            description = "Classpath for KNE PSI resolution"
        }
        // using the project kotlin version for embedded kotlin
        val kotlinVersion = project.extensions.findByType<KotlinProjectExtension>()?.coreLibrariesVersion
        project.dependencies.add(knePsiScope.name, "org.jetbrains.kotlin:kotlin-compiler-embeddable:$kotlinVersion")

        // ── Code-generation tasks ────────────────────────────────────────────

        // Single task generates both native bridges and JVM proxies (PSI parsing + codegen in isolated worker)
        val generateBridges = project.tasks.register<GenerateNativeBridgesTask>("generateKneNativeBridges") {
            group = "kne"
            description = "Generate Kotlin/Native bridges and JVM FFM proxies"
            taskNativeSources.from(userNativeSources)
            taskCommonSources.from(commonSources)
            taskLibName.set(libName)
            taskJvmPackage.set(pkg)
            taskOutputDir.set(nativeBridgesDir)
            taskJvmOutputDir.set(jvmProxiesDir)
            taskJvmResourcesDir.set(jvmResourcesDir)
            taskPsiClasspath.from(psiClasspath)
        }
        // Keep old task name as alias
        project.tasks.register("generateKneJvmProxies") { dependsOn(generateBridges) }

        // Required for generated suspend/Flow bridges. Always added by Maven
        // coordinates — never look up the consumer's version catalog. Alias
        // names in libs.versions.toml are project-local and must not be required.
        val coroutinesVersion = DEFAULT_COROUTINES_VERSION

        nativeTarget?.let { target ->
            kotlin.sourceSets.findByName("${target.name}Main")?.dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
            }
        }
        kotlin.sourceSets.findByName("nativeMain")?.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
        }

        kotlin.sourceSets.findByName(jvmMainSourceSetName)?.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:$coroutinesVersion")
        }
        kotlin.sourceSets.findByName(jvmTestSourceSetName)?.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
        }

        // Wire generated bridges into the native source set (try nativeMain, fall back to <target>Main)
        val nativeSourceSet = kotlin.sourceSets.findByName("nativeMain")
            ?: nativeTarget?.let { kotlin.sourceSets.findByName("${it.name}Main") }
        nativeSourceSet?.kotlin?.srcDir(nativeBridgesDir)

        // Wire generated JVM proxies into jvmMain
        kotlin.sourceSets.findByName(jvmMainSourceSetName)?.kotlin?.srcDir(jvmProxiesDir)

        // Wire generated GraalVM metadata into JVM resources
        kotlin.sourceSets.findByName(jvmMainSourceSetName)?.resources?.srcDir(jvmResourcesDir)

        // Ensure compilation waits for generation
        project.tasks.filter { task ->
            val name = task.name
            val isCompileKotlinOrNative = task.name.startsWith("compileKotlin") &&
                (task.name.contains("Native", ignoreCase = true) || task.name.contains(
                    nativeTargetTaskName,
                    ignoreCase = true
                ))

            val isJvmTask = name == "compileKotlin$jvmTaskName" || name == "compileKotlin$jvmMainTaskName"
            isJvmTask || isCompileKotlinOrNative
        }.forEach { task ->
            task.dependsOn(generateBridges)
        }

        // ── Native binaries (both debug + release, like swift-java) ──────────

        kotlin.targets.filterIsInstance<KotlinNativeTarget>().forEach { target ->
            target.binaries.sharedLib(
                namePrefix = libName,
                buildTypes = listOf(NativeBuildType.DEBUG, NativeBuildType.RELEASE),
            )
        }

        // ── Bundle native lib into JVM resources (zero-config deployment) ────

        if (nativeTarget != null) {
            // konan target names are the actual target name but we can have an associated alias
            val targetAliasName = nativeTarget.name
            val targetName = nativeTarget.konanTarget.name

            project.logger.log(LogLevel.INFO, "TARGET ALIAS :$targetAliasName, TARGET NAME:$targetName")

            val targetCap = targetAliasName.replaceFirstChar { it.uppercaseChar() }
            val libCap = libName.replaceFirstChar { it.uppercaseChar() }

            val platform = mapTargetToPlatform(targetName)

            val linkTaskName = "link${libCap}ReleaseShared$targetCap"
            val nativeLibResourceDir = project.layout.buildDirectory.dir("generated/kne/nativeLib")

            val buildDir = project.layout.buildDirectory
            val copyNativeLib = project.tasks.register("copyKneNativeLib") {
                group = "kne"
                description = "Copy native shared library into JVM resources for JAR bundling"
                dependsOn(linkTaskName)
                doLast {
                    val releaseDir = buildDir
                        .dir("bin/$targetAliasName/${libName}ReleaseShared").get().asFile
                    val nativeFile = releaseDir.listFiles()?.firstOrNull { f ->
                        f.extension in listOf("so", "dylib", "dll")
                    }
                    if (nativeFile != null) {
                        val destDir = nativeLibResourceDir.get().asFile.resolve("kne/native/$platform")
                        destDir.mkdirs()
                        nativeFile.copyTo(destDir.resolve(nativeFile.name), overwrite = true)
                        logger.lifecycle("kne: Bundled ${nativeFile.name} → kne/native/$platform/")
                    }
                }
            }

            // Wire native lib resource dir into JVM resources
            kotlin.sourceSets.findByName(jvmMainSourceSetName)?.resources?.srcDir(nativeLibResourceDir)

            // Ensure processResources waits for the native lib copy
            project.tasks.configureEach {
                if (name == "${jvmTarget.name}ProcessResources" || name == "process${jvmMainTaskName}Resources") {
                    dependsOn(copyNativeLib)
                }
            }
        }

        // ── JVM test configuration ───────────────────────────────────────────

        configureJvmTestPaths(project, kotlin, extension)
    }

    /** Map Kotlin/Native target name to platform directory name. */
    private fun mapTargetToPlatform(targetName: String): String {

        val isArm = targetName.contains("Arm")
        val isArch = targetName.contains("aarch")
        val isArm64 = targetName.contains("arm64") || targetName.contains("Arm64")

        return when {
            targetName.startsWith("linux") -> if (isArm || isArch) "linux-aarch64" else "linux-x64"
            targetName.startsWith("macos") -> if (isArm || isArm64) "darwin-aarch64" else "darwin-x64"
            targetName.startsWith("mingw") -> if (isArm) "win32-arm64" else "win32-x64"
            else -> "unknown"
        }
    }

    /**
     * Mirrors swift-java's convention: include both debug + release library
     * directories in java.library.path so either variant is found at runtime.
     * The [KotlinNativeExportExtension.buildType] determines which link task
     * the test task depends on (only that variant is guaranteed to be built).
     */
    private fun configureJvmTestPaths(
        project: Project,
        kotlin: KotlinMultiplatformExtension,
        extension: KotlinNativeExportExtension,
    ) {
        val nativeTarget = kotlin.targets
            .filterIsInstance<KotlinNativeTarget>()
            .firstOrNull() ?: return

        val libName = extension.nativeLibName.get()
        val targetName = nativeTarget.name
        val targetCap = targetName.replaceFirstChar { it.uppercaseChar() }
        val libCap = libName.replaceFirstChar { it.uppercaseChar() }

        // Both variant output dirs (convention: build/bin/<target>/<lib><Variant>Shared/)
        val debugDir = project.layout.buildDirectory
            .dir("bin/$targetName/${libName}DebugShared").get().asFile
        val releaseDir = project.layout.buildDirectory
            .dir("bin/$targetName/${libName}ReleaseShared").get().asFile

        // java.library.path includes both, separated by the platform path separator
        val libraryPath = listOf(releaseDir, debugDir)
            .joinToString(File.pathSeparator) { it.absolutePath.replace("\\", "/") }

        // Link task for the configured build type
        val buildType = extension.buildType.get().replaceFirstChar { it.uppercaseChar() }
        val linkTaskName = "link${libCap}${buildType}Shared$targetCap"

        project.tasks.withType<Test>().configureEach {
            dependsOn(project.tasks.matching { it.name == linkTaskName })
            useJUnitPlatform()
            jvmArgs(
                "-Djava.library.path=$libraryPath",
                "--enable-native-access=ALL-UNNAMED",
            )
        }
    }

    companion object {
        private const val DEFAULT_COROUTINES_VERSION = "1.11.0"
    }
}
