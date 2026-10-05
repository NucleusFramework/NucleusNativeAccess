package dev.nucleusframework.nna.plugin.analysis

import dev.nucleusframework.nna.plugin.codegen.FfmProxyGenerator
import dev.nucleusframework.nna.plugin.codegen.GeneratedOutputs
import dev.nucleusframework.nna.plugin.codegen.NativeBridgeGenerator
import dev.nucleusframework.nna.plugin.ir.KneModule
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.Logging
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters

/**
 * Gradle Worker Action that runs PSI parsing + code generation inside an isolated classloader.
 * Both parsing AND generation run in the worker to avoid serialization issues with KneType singletons.
 *
 * The work is incremental across executions thanks to a [ParseCache] stored in [Params.cacheFile]:
 * unchanged source files reuse their cached IR fragments, code generation is skipped when the
 * merged module is unchanged, and only output files whose content changed are rewritten.
 */
abstract class PsiParseWorkAction : WorkAction<PsiParseWorkAction.Params> {

    interface Params : WorkParameters {
        /** Common sources first, then native sources, in deterministic merge order. */
        val sourceFiles: ConfigurableFileCollection
        /** Subset of [sourceFiles] that comes from commonMain. */
        val commonSourceFiles: ConfigurableFileCollection
        val libName: Property<String>
        val jvmPackage: Property<String>
        val nativeBridgesDir: DirectoryProperty
        val jvmProxiesDir: DirectoryProperty
        val jvmResourcesDir: DirectoryProperty
        val cacheFile: RegularFileProperty
        /** Identifies the plugin/parser version; a different key invalidates the cache. */
        val cacheKey: Property<String>
    }

    private val logger = Logging.getLogger(PsiParseWorkAction::class.java)

    override fun execute() {
        val commonFiles = parameters.commonSourceFiles.files
        val sources = parameters.sourceFiles.files.map { PsiSourceParser.Source(it, isCommon = it in commonFiles) }
        val libName = parameters.libName.get()
        val cacheFile = parameters.cacheFile.get().asFile
        val cacheKey = parameters.cacheKey.get()
        val previous = ParseCache.read(cacheFile, cacheKey)

        val result = PsiSourceParser().parseIncremental(sources, libName, previous?.files.orEmpty())
        val module = result.module
        logger.lifecycle("kne: Parsed ${result.parsedFiles} of ${result.files.size} source file(s) [PSI]")

        // Auto-detect package from sources if not explicitly configured
        val jvmPackage = parameters.jvmPackage.get().ifEmpty {
            module.packages.firstOrNull() ?: ""
        }

        val roots = mapOf(
            NATIVE_BRIDGES to parameters.nativeBridgesDir.get().asFile,
            JVM_PROXIES to parameters.jvmProxiesDir.get().asFile,
            JVM_RESOURCES to parameters.jvmResourcesDir.get().asFile,
        )
        val snapshot = previous?.codegen
        val codegen = if (snapshot != null && snapshot.matches(module, jvmPackage) && GeneratedOutputs.hashes(roots) == snapshot.outputHashes) {
            logger.lifecycle("kne: API unchanged, generated sources are up to date")
            snapshot
        } else {
            val sync = GeneratedOutputs.sync(roots, generate(module, jvmPackage))
            logger.lifecycle("kne: Updated ${sync.written} and deleted ${sync.deleted} generated file(s)")
            CodegenSnapshot(module, module.packages.toList(), jvmPackage, sync.hashes)
        }

        try {
            ParseCache(cacheKey, result.files, codegen).write(cacheFile)
        } catch (e: Exception) {
            // The cache is an optimisation only; the next run falls back to a full parse
            logger.warn("kne: Could not write incremental cache: ${e.message}")
            cacheFile.delete()
        }
    }

    /** Generates every output file from the module, grouped by output root. */
    private fun generate(module: KneModule, jvmPackage: String): Map<String, Map<String, String>> {
        val outputs = mapOf(
            NATIVE_BRIDGES to LinkedHashMap<String, String>(),
            JVM_PROXIES to LinkedHashMap(),
            JVM_RESOURCES to LinkedHashMap(),
        )
        val hasApi = module.classes.isNotEmpty() || module.enums.isNotEmpty() || module.functions.isNotEmpty()
        if (!hasApi) return outputs

        // Native bridges
        outputs.getValue(NATIVE_BRIDGES)["kne_bridges.kt"] = NativeBridgeGenerator().generate(module)

        // JVM proxies + GraalVM metadata
        if (jvmPackage.isNotEmpty()) {
            val pkgPath = jvmPackage.replace('.', '/')
            val generator = FfmProxyGenerator()
            generator.generate(module, jvmPackage).forEach { (filename, content) ->
                outputs.getValue(JVM_PROXIES)["$pkgPath/$filename"] = content
            }

            // Generate GraalVM reachability metadata for native-image support
            generateGraalVmMetadata(outputs.getValue(JVM_RESOURCES), module, jvmPackage, generator)
        }
        return outputs
    }

    /**
     * Generates GraalVM reachability metadata under META-INF/native-image/kne/.
     * Includes reflection config, resource config, and FFM foreign downcall/upcall descriptors.
     */
    private fun generateGraalVmMetadata(
        resources: MutableMap<String, String>,
        module: KneModule,
        jvmPackage: String,
        generator: FfmProxyGenerator,
    ) {
        val metaDir = "META-INF/native-image/kne/${module.libName}"

        // Collect all generated class FQNs for reflection
        val classNames = mutableListOf<String>()
        classNames.add("$jvmPackage.KneRuntime")
        classNames.add("$jvmPackage.KotlinNativeException")
        module.classes.forEach { classNames.add("$jvmPackage.${it.simpleName}") }
        module.dataClasses.filter { !it.isCommon }.forEach { classNames.add("$jvmPackage.${it.simpleName}") }
        module.enums.forEach { classNames.add("$jvmPackage.${it.simpleName}") }
        if (module.functions.isNotEmpty()) {
            classNames.add("$jvmPackage.${module.libName.replaceFirstChar { it.uppercaseChar() }}")
        }

        // Collect FFM downcall descriptors
        val downcalls = generator.collectGraalVmDowncalls(module)

        // reflect-config.json
        val reflectEntries = classNames.joinToString(",\n") { name ->
            """  {
    "name": "$name",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true,
    "allDeclaredFields": true
  }"""
        }
        resources["$metaDir/reflect-config.json"] = "[\n$reflectEntries\n]\n"

        // resource-config.json
        resources["$metaDir/resource-config.json"] = """{
  "resources": {
    "includes": [
      { "pattern": "\\Qkne/native/\\E.*" }
    ]
  }
}
"""

        // reachability-metadata.json — FFM foreign downcall + upcall descriptors
        fun formatEntries(descriptors: Set<Pair<List<String>, String?>>): String =
            descriptors.joinToString(",\n") { (params, ret) ->
                val paramStr = params.joinToString(", ") { "\"$it\"" }
                val retStr = ret ?: "void"
                """      { "parameterTypes": [$paramStr], "returnType": "$retStr" }"""
            }

        val downcallEntries = formatEntries(downcalls)

        // Collect FFM upcall descriptors (suspend/flow stubs + lambda callbacks)
        val upcalls = generator.collectGraalVmUpcalls(module)
        val upcallSection = if (upcalls.isNotEmpty()) {
            val upcallEntries = formatEntries(upcalls)
            """,
    "upcalls": [
$upcallEntries
    ]"""
        } else {
            ""
        }

        resources["$metaDir/reachability-metadata.json"] = """{
  "reflection": [
${classNames.joinToString(",\n") { """    { "type": "$it", "allDeclaredConstructors": true, "allDeclaredMethods": true }""" }}
  ],
  "resources": [
    { "glob": "kne/native/**" }
  ],
  "foreign": {
    "downcalls": [
$downcallEntries
    ]$upcallSection
  }
}
"""
    }

    private companion object {
        const val NATIVE_BRIDGES = "nativeBridges"
        const val JVM_PROXIES = "jvmProxies"
        const val JVM_RESOURCES = "jvmResources"
    }
}
