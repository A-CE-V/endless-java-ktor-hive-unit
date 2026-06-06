package org.endless.services

import org.endless.model.ChunkRunResult
import org.endless.model.DecompileOutcome
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class DecompilerRegistry(private val adapters: List<DecompilerAdapter>) {

    companion object {
        // BUG FIX: both were 1L * 1024 * 1024 (1 MB) despite comments saying 10 MB / 200 MB
        private const val MAX_CLASS_BYTES = 10L  * 1024 * 1024   // 10 MB per .class
        private const val MAX_JAR_BYTES   = 50L  * 1024 * 1024   // 50 MB per JAR / chunk
    }

    private val byMode: Map<String, DecompilerAdapter> =
        adapters.associateBy { it.mode.lowercase() }

    val availableDecompilers: List<String> get() = adapters.map { it.mode }
    val jarCapableDecompilers: List<String> get() = adapters.filter { it.supportsJar }.map { it.mode }

    // ── Validation helpers ────────────────────────────────────────────────────

    fun validateClassBytes(bytes: ByteArray, mode: String): Map<String, Any>? {
        if (bytes.isEmpty()) return mapOf("error" to "EMPTY_FILE", "message" to "File is empty")
        if (bytes.size > MAX_CLASS_BYTES) return mapOf(
            "error"   to "FILE_TOO_LARGE",
            "message" to "Class file exceeds limit of ${MAX_CLASS_BYTES / 1_048_576} MB"
        )
        val adapter = resolve(mode) ?: return mapOf("error" to "UNSUPPORTED_MODE",
            "message" to "Unknown decompiler '$mode'. Available: ${availableDecompilers.joinToString()}")
        if (!adapter.supportsClass) return mapOf("error" to "MODE_NO_CLASS_SUPPORT",
            "message" to "'$mode' does not support single .class decompilation")
        if (bytes[0] != 0xCA.toByte() || bytes[1] != 0xFE.toByte()) return mapOf(
            "error" to "INVALID_CLASS_MAGIC", "message" to "Not a valid .class file (bad magic bytes)")
        return null
    }

    fun validateJarBytes(bytes: ByteArray, mode: String): Map<String, Any>? {
        if (bytes.isEmpty()) return mapOf("error" to "EMPTY_FILE", "message" to "File is empty")
        if (bytes.size > MAX_JAR_BYTES) return mapOf(
            "error"   to "FILE_TOO_LARGE",
            "message" to "JAR exceeds limit of ${MAX_JAR_BYTES / 1_048_576} MB"
        )
        val adapter = resolve(mode) ?: return mapOf("error" to "UNSUPPORTED_MODE",
            "message" to "Unknown decompiler '$mode'. Available: ${availableDecompilers.joinToString()}")
        if (!adapter.supportsJar) return mapOf("error" to "MODE_NO_JAR_SUPPORT",
            "message" to "'$mode' does not support JAR decompilation")
        if (bytes[0] != 0x50.toByte() || bytes[1] != 0x4B.toByte()) return mapOf(
            "error" to "INVALID_JAR_MAGIC", "message" to "Not a valid JAR/ZIP file (bad magic bytes)")
        return null
    }

    // ── Core decompile operations ─────────────────────────────────────────────

    fun resolve(mode: String): DecompilerAdapter? = byMode[mode.lowercase()]

    fun runClass(mode: String, bytes: ByteArray, fileName: String): DecompileOutcome =
        resolve(mode)!!.decompileClass(bytes, fileName)

    fun runJar(mode: String, jar: File, outDir: File): DecompileOutcome =
        resolve(mode)!!.decompileJar(jar, outDir)

    // ── Chunk decompilation (hive architecture) ───────────────────────────────
    //
    // Receives the full JAR bytes and a subset of class-entry names.
    // Builds a mini-JAR from just those classes, runs the decompiler on it,
    // and returns a map of ( relative-java-path → source-code ).

    fun runChunk(mode: String, jarBytes: ByteArray, classNames: List<String>): ChunkRunResult {
        val adapter   = resolve(mode)!!
        val t0        = System.currentTimeMillis()
        val warnings  = mutableListOf<String>()
        val errors    = mutableListOf<String>()
        val sources   = mutableMapOf<String, String>()

        if (adapter.supportsJar) {
            // ── JAR-capable path: build mini-JAR → decompile → collect .java files ──
            val miniJarBytes = buildMiniJar(jarBytes, classNames.toSet())
            val tempJar = File.createTempFile("chunk-in-", ".jar")
            val outDir  = Files.createTempDirectory("chunk-out-").toFile()
            try {
                tempJar.writeBytes(miniJarBytes)
                val result = runCatching { adapter.decompileJar(tempJar, outDir) }
                    .getOrElse { ex ->
                        return ChunkRunResult(
                            emptyMap(), emptyList(),
                            listOf("[${mode.uppercase()}] ${ex.message ?: "Unknown error"}"),
                            System.currentTimeMillis() - t0
                        )
                    }
                when (result) {
                    is DecompileOutcome.Success -> {
                        warnings.addAll(result.warnings)
                        errors.addAll(result.errors)
                        outDir.walkTopDown().filter { it.isFile }.forEach { file ->
                            val rel = file.relativeTo(outDir).path.replace('\\', '/')
                            sources[rel] = file.readText()
                        }
                    }
                    is DecompileOutcome.Failure ->
                        errors.add("[${mode.uppercase()}] ${result.error}: ${result.detail}")
                }
            } finally {
                // BUG FIX: always clean up even if an exception is thrown
                tempJar.delete()
                outDir.deleteRecursively()
            }

        } else {
            // ── Per-class fallback for adapters that don't support JAR (Procyon, JD-Core) ──
            classNames.forEach { className ->
                val classBytes = extractClassBytes(jarBytes, className)
                if (classBytes == null) {
                    errors.add("[$className] Entry not found in JAR")
                    return@forEach
                }
                val shortName = className.substringAfterLast('/')
                when (val result = runCatching { adapter.decompileClass(classBytes, shortName) }
                    .getOrElse { ex -> DecompileOutcome.Failure(ex.message ?: "Error", className) }) {
                    is DecompileOutcome.Success -> {
                        val javaPath = className.removeSuffix(".class") + ".java"
                        sources[javaPath] = result.source
                        warnings.addAll(result.warnings)
                        errors.addAll(result.errors)
                    }
                    is DecompileOutcome.Failure ->
                        errors.add("[${mode.uppercase()}][$className] ${result.error}")
                }
            }
        }

        return ChunkRunResult(sources, warnings, errors, System.currentTimeMillis() - t0)
    }

    // ── ZIP helpers ───────────────────────────────────────────────────────────

    /** Builds a new JAR containing only the requested entries from the source JAR. */
    private fun buildMiniJar(jarBytes: ByteArray, classNames: Set<String>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(BufferedOutputStream(baos)).use { zos ->
            ZipInputStream(ByteArrayInputStream(jarBytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name in classNames) {
                        zos.putNextEntry(ZipEntry(entry.name))
                        zis.copyTo(zos)
                        zos.closeEntry()
                    }
                    entry = zis.nextEntry
                }
            }
        }
        return baos.toByteArray()
    }

    /** Extracts the raw bytes of a single .class entry from a JAR. */
    private fun extractClassBytes(jarBytes: ByteArray, className: String): ByteArray? {
        ZipInputStream(ByteArrayInputStream(jarBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == className) return zis.readBytes()
                entry = zis.nextEntry
            }
        }
        return null
    }
}
