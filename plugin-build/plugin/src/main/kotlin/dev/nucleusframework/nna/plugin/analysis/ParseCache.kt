package dev.nucleusframework.nna.plugin.analysis

import dev.nucleusframework.nna.plugin.ir.KneClass
import dev.nucleusframework.nna.plugin.ir.KneDataClass
import dev.nucleusframework.nna.plugin.ir.KneEnum
import dev.nucleusframework.nna.plugin.ir.KneFunction
import dev.nucleusframework.nna.plugin.ir.KneInterface
import dev.nucleusframework.nna.plugin.ir.KneModule
import java.io.File
import java.io.InputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.ObjectStreamClass
import java.io.Serializable
import java.security.MessageDigest

/** IR declarations contributed by a single source file, merged in source order into the [KneModule]. */
data class FileFragment(
    val packageName: String,
    val classes: List<KneClass>,
    val dataClasses: List<KneDataClass>,
    val enums: List<KneEnum>,
    /** Keyed by function name, or `Receiver.name` for extension functions. */
    val functions: List<Pair<String, KneFunction>>,
    val interfaces: List<KneInterface>,
) : Serializable

/**
 * Source file → IR mapping. [prescan] depends only on the file content; [fragment] additionally
 * depends on the global symbols listed in [lookups], so it is reused only while they are unchanged.
 */
data class CachedSourceFile(
    val contentHash: String,
    val isCommon: Boolean,
    val prescan: List<PrescanDecl>,
    val fragment: FileFragment,
    val lookups: Map<String, SymbolInfo?>,
) : Serializable

/** IR → generated files mapping: the module that produced [outputHashes] (`root/relative/path` → SHA-256). */
data class CodegenSnapshot(
    val module: KneModule,
    val packageOrder: List<String>,
    val jvmPackage: String,
    val outputHashes: Map<String, String>,
) : Serializable {

    fun matches(module: KneModule, jvmPackage: String): Boolean =
        this.jvmPackage == jvmPackage && this.module == module && packageOrder == module.packages.toList()
}

/**
 * Incremental state persisted between task executions. [key] identifies the plugin and parser
 * versions that produced it; a mismatch discards the whole cache.
 */
data class ParseCache(
    val key: String,
    val files: Map<String, CachedSourceFile>,
    val codegen: CodegenSnapshot?,
) : Serializable {

    fun write(file: File) {
        file.parentFile.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        ObjectOutputStream(tmp.outputStream().buffered()).use { it.writeObject(this) }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    companion object {
        /** Reads the cache, or returns null when it is missing, corrupt, or produced with a different [key]. */
        fun read(file: File, key: String): ParseCache? {
            if (!file.isFile) return null
            return try {
                PluginObjectInputStream(file.inputStream().buffered()).use { it.readObject() as? ParseCache }
                    ?.takeIf { it.key == key }
            } catch (_: Exception) {
                null
            }
        }
    }

    /** Resolves classes through the plugin's classloader (the worker's isolated loader), not the caller's. */
    private class PluginObjectInputStream(input: InputStream) : ObjectInputStream(input) {
        override fun resolveClass(desc: ObjectStreamClass): Class<*> =
            try {
                Class.forName(desc.name, false, ParseCache::class.java.classLoader)
            } catch (_: ClassNotFoundException) {
                super.resolveClass(desc)
            }
    }
}

internal fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
