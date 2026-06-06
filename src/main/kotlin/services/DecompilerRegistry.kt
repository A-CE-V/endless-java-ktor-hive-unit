package org.endless.services

import org.endless.model.ChunkRunResult
import org.endless.model.DecompileOutcome
import org.endless.model.DecompileResult
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
        // BUG FIX (original): both were 1L * 1024 * 1024 (1 MB) despite comments saying 10 / 200 MB
        private const val MAX_CLASS_BYTES = 10L * 1024 * 1024   // 10 MB per .class
        private const val MAX_JAR_BYTES   = 50L * 1024 * 1024   // 50 MB per JAR / chunk
    }

    // FIX: use `it.name` — DecompilerAdapter has no `mode` field, only `name`
    private val byName: Map<String, DecompilerAdapter> =
        adapters.associateBy { it.name.lowercase() }

    val availableDecompilers: List<String>    get() = adapters.map { it.name }
    val jarCapableDecompilers: List<String>   get() = adapters.filter { it.supportsJar }.map { it.name }

    // ── Validation ────────────────────────────────────────────────────────────

    fun validateClassBytes(bytes: ByteArray, mode: String): Map<String, Any>? {
        if (bytes.isEmpty()) return mapOf("error" to "EMPTY_FILE", "message" to "File is empty")
        if (bytes.size > MAX_CLASS_BYTES) return mapOf(
            "error"   to "FILE_TOO_LARGE",
            "message" to "Class file exceeds ${MAX_CLASS_BYTES / 1_048_576} MB limit"
        )
        val adapter = resolve(mode) ?: return mapOf(
            "error"   to "UNSUPPORTED_MODE",
            "message" to "Unknown decompiler '$mode'. Available: ${availableDecompilers.joinToString()}"
        )
        if (!adapter.supportsJar && bytes.size > MAX_CLASS_BYTES) return mapOf(
            "error" to "FILE_TOO_LARGE", "message" to "Class too large"
        )
        if (bytes[0] != 0xCA.toByte() || bytes[1] != 0xFE.toByte()) return mapOf(
            "error" to "INVALID_CLASS_MAGIC", "message" to "Not a valid .class file (bad magic bytes)"
        )
        return null
    }

    fun validateJarBytes(bytes: ByteArray, mode: String): Map<String, Any>? {
        if (bytes.isEmpty()) return mapOf("error" to "EMPTY_FILE", "message" to "File is empty")
        if (bytes.size > MAX_JAR_BYTES) return mapOf(
            "error"   to "FILE_TOO_LARGE",
            "message" to "JAR exceeds ${MAX_JAR_BYTES / 1_048_576} MB limit"
        )
        resolve(mode) ?: return mapOf(
            "error"   to "UNSUPPORTED_MODE",
            "message" to "Unknown decompiler '$mode'. Available: ${availableDecompilers.joinToString()}"
        )
        if (bytes[0] != 0x50.toByte() || bytes[1] != 0x4B.toByte()) return mapOf(
            "error" to "INVALID_JAR_MAGIC", "message" to "Not a valid JAR/ZIP file (bad magic bytes)"
        )
        return null
    }

    // ── Core operations ───────────────────────────────────────────────────────

    fun resolve(mode: String): DecompilerAdapter? = byName[mode.lowercase()]

    /**
     * Decompile a single .class file.
     * Wraps the adapter's DecompileResult into DecompileOutcome so Main.kt
     * never needs try-catch around the registry calls.
     */
    fun runClass(mode: String, bytes: ByteArray, fileName: String): DecompileOutcome =
        runCatching { resolve(mode)!!.decompileClass(bytes, fileName) }
            .fold(
                onSuccess = { r -> DecompileOutcome.Success(r.source, r.warnings, r.errors) },
                onFailure = { e -> DecompileOutcome.Failure(e.javaClass.simpleName, e.message ?: "") }
            )

    /**
     * Decompile a whole JAR file.
     * Main.kt is responsible for creating/deleting tempJar and outDir.
     */
    fun runJar(mode: String, jar: File, outDir: File): DecompileOutcome =
        runCatching { resolve(mode)!!.decompileJar(jar, outDir) }
            .fold(
                onSuccess = { r -> DecompileOutcome.Success(r.source, r.warnings, r.errors) },
                onFailure = { e -> DecompileOutcome.Failure(e.javaClass.simpleName, e.message ?: "") }
            )

    // ── Chunk decompilation (hive) ────────────────────────────────────────────

    fun runChunk(mode: String, jarBytes: ByteArray, classNames: List<String>): ChunkRunResult {
        val adapter  = resolve(mode)!!
        val t0       = System.currentTimeMillis()
        val warnings = mutableListOf<String>()
        val errors   = mutableListOf<String>()
        val sources  = mutableMapOf<String, String>()

        if (adapter.supportsJar) {
            val miniJarBytes = buildMiniJar(jarBytes, classNames.toSet())
            val tempJar = File.createTempFile("chunk-in-", ".jar")
            val outDir  = Files.createTempDirectory("chunk-out-").toFile()
            try {
                tempJar.writeBytes(miniJarBytes)
                val result: DecompileResult = runCatching { adapter.decompileJar(tempJar, outDir) }
                    .getOrElse { ex ->
                        return ChunkRunResult(
                            emptyMap(), emptyList(),
                            listOf("[${mode.uppercase()}] ${ex.message ?: "Unknown error"}"),
                            System.currentTimeMillis() - t0
                        )
                    }
                warnings.addAll(result.warnings)
                errors.addAll(result.errors)
                outDir.walkTopDown().filter { it.isFile }.forEach { file ->
                    sources[file.relativeTo(outDir).path.replace('\\', '/')] = file.readText()
                }
            } finally {
                // Always clean up — even if an exception is thrown
                tempJar.delete()
                outDir.deleteRecursively()
            }
        } else {
            // Per-class fallback for adapters that don't support JAR (Procyon, JD-Core)
            classNames.forEach { className ->
                val classBytes = extractClassBytes(jarBytes, className)
                if (classBytes == null) {
                    errors.add("[$className] Not found in JAR"); return@forEach
                }
                val shortName = className.substringAfterLast('/')
                val result: DecompileResult = runCatching { adapter.decompileClass(classBytes, shortName) }
                    .getOrElse { ex ->
                        errors.add("[${mode.uppercase()}][$className] ${ex.message}"); return@forEach
                    }
                sources[className.removeSuffix(".class") + ".java"] = result.source
                warnings.addAll(result.warnings)
                errors.addAll(result.errors)
            }
        }

        return ChunkRunResult(sources, warnings, errors, System.currentTimeMillis() - t0)
    }

    // ── ZIP helpers ───────────────────────────────────────────────────────────

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
