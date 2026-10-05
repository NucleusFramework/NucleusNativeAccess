package dev.nucleusframework.nna.plugin.codegen

import dev.nucleusframework.nna.plugin.analysis.sha256
import java.io.File

/**
 * Keeps output directories in sync with generated content: only files whose content changed are
 * rewritten and stale files are deleted, so downstream incremental compilation only sees real changes.
 *
 * Outputs are grouped by root name (`root name → relative path → content`); hashes are keyed `root/relative/path`.
 */
object GeneratedOutputs {

    class SyncResult(val written: Int, val deleted: Int, val hashes: Map<String, String>)

    /** SHA-256 of every file currently present under [roots]. */
    fun hashes(roots: Map<String, File>): Map<String, String> =
        roots.flatMap { (rootName, dir) ->
            dir.regularFiles().map { (path, file) -> "$rootName/$path" to sha256(file.readBytes()) }
        }.toMap()

    fun sync(roots: Map<String, File>, outputs: Map<String, Map<String, String>>): SyncResult {
        var written = 0
        var deleted = 0
        val hashes = LinkedHashMap<String, String>()

        for ((rootName, dir) in roots) {
            dir.mkdirs()
            val expected = outputs[rootName].orEmpty()
            val existing = dir.regularFiles()

            for ((path, file) in existing) {
                if (path !in expected) {
                    file.delete()
                    deleted++
                }
            }
            for ((path, content) in expected.toSortedMap()) {
                val bytes = content.toByteArray()
                val file = dir.resolve(path)
                if (existing[path]?.readBytes()?.contentEquals(bytes) != true) {
                    file.parentFile.mkdirs()
                    file.writeBytes(bytes)
                    written++
                }
                hashes["$rootName/$path"] = sha256(bytes)
            }
            if (deleted > 0) dir.deleteEmptyDirectories()
        }
        return SyncResult(written, deleted, hashes)
    }

    private fun File.regularFiles(): Map<String, File> =
        if (!isDirectory) emptyMap()
        else walkTopDown().filter { it.isFile }.associateBy { it.relativeTo(this).invariantSeparatorsPath }

    private fun File.deleteEmptyDirectories() {
        walkBottomUp().filter { it != this && it.isDirectory && it.list().isNullOrEmpty() }.forEach { it.delete() }
    }
}
