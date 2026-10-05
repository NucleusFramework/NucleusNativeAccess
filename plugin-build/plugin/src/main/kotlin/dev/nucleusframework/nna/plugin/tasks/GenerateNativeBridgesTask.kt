package dev.nucleusframework.nna.plugin.tasks

import dev.nucleusframework.nna.plugin.analysis.PsiParseWorkAction
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.workers.WorkerExecutor
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject

/**
 * Generates native bridges, JVM proxies and GraalVM metadata from the native sources.
 *
 * Cacheable: outputs depend only on the (relative) source contents, the PSI classpath and the
 * configuration inputs. Between non-cached executions, the worker keeps a per-source-file IR cache in
 * [taskCacheDir] so that only changed source files are re-parsed and only changed outputs are rewritten.
 */
@CacheableTask
abstract class GenerateNativeBridgesTask : DefaultTask() {

    @get:Inject
    abstract val taskWorkerExecutor: WorkerExecutor

    @get:Inject
    abstract val taskObjectFactory: ObjectFactory

    @get:InputFiles
    @get:SkipWhenEmpty
    @get:IgnoreEmptyDirectories
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val taskNativeSources: ConfigurableFileCollection

    @get:InputFiles
    @get:IgnoreEmptyDirectories
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val taskCommonSources: ConfigurableFileCollection

    @get:Classpath
    abstract val taskPsiClasspath: ConfigurableFileCollection

    @get:Input
    abstract val taskLibName: Property<String>

    @get:Input
    abstract val taskJvmPackage: Property<String>

    @get:OutputDirectory
    abstract val taskOutputDir: DirectoryProperty

    @get:OutputDirectory
    abstract val taskJvmOutputDir: DirectoryProperty

    @get:OutputDirectory
    abstract val taskJvmResourcesDir: DirectoryProperty

    /** Incremental parse state; never stored in the build cache and wiped when outputs come from it. */
    @get:LocalState
    abstract val taskCacheDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val ktFiles = taskNativeSources.orderedKotlinFiles()
        if (ktFiles.isEmpty()) {
            listOf(taskOutputDir, taskJvmOutputDir, taskJvmResourcesDir).forEach {
                it.get().asFile.apply { deleteRecursively(); mkdirs() }
            }
            logger.lifecycle("kne: No Kotlin sources found, skipping."); return
        }
        val commonKtFiles = taskCommonSources.orderedKotlinFiles()

        val pluginLocation = PsiParseWorkAction::class.java.protectionDomain?.codeSource?.location
            ?.let { File(it.toURI()) }
        val pluginJar = taskObjectFactory.fileCollection().apply { pluginLocation?.let { from(it) } }

        val workQueue = taskWorkerExecutor.classLoaderIsolation {
            classpath.from(taskPsiClasspath)
            classpath.from(pluginJar)
        }

        workQueue.submit(PsiParseWorkAction::class.java) {
            sourceFiles.from(commonKtFiles + ktFiles)
            commonSourceFiles.from(commonKtFiles)
            libName.set(taskLibName)
            jvmPackage.set(taskJvmPackage)
            nativeBridgesDir.set(taskOutputDir)
            jvmProxiesDir.set(taskJvmOutputDir)
            jvmResourcesDir.set(taskJvmResourcesDir)
            cacheFile.set(taskCacheDir.file("parse-cache.bin"))
            cacheKey.set(cacheKey(pluginLocation))
        }
    }

    /**
     * Kotlin files in a stable order: roots in declaration order, files sorted by relative path within
     * each root. The merge order decides which duplicate declaration wins and the package order, so
     * it must not depend on file system traversal order.
     */
    private fun FileCollection.orderedKotlinFiles(): List<File> = files.flatMap { root ->
        when {
            root.isFile -> listOf(root).filter { it.extension == "kt" }
            root.isDirectory -> root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
                .toList()
            else -> emptyList()
        }
    }.distinct()

    /** Identifies the plugin build and PSI classpath that produce the incremental cache. */
    private fun cacheKey(pluginLocation: File?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun update(value: Any?) = digest.update("$value\n".toByteArray())
        update(CACHE_FORMAT_VERSION)
        val pluginFiles = when {
            pluginLocation == null -> emptySequence()
            pluginLocation.isDirectory -> pluginLocation.walkTopDown().filter { it.isFile }.sortedBy { it.path }
            else -> sequenceOf(pluginLocation)
        }
        (pluginFiles + taskPsiClasspath.files.asSequence()).forEach { update("${it.path}:${it.length()}:${it.lastModified()}") }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        /** Bump when the cache layout or the parsing semantics change. */
        const val CACHE_FORMAT_VERSION = 1
    }
}
