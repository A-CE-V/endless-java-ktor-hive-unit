package org.endless.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
        private const val MAX_CLASS_BYTES = 10L * 1024 * 1024
        private const val MAX_JAR_BYTES   = 50L * 1024 * 1024
        // Max parallel per-class decompilations in Pass 2.
        // Balances speed vs. memory pressure on Render free tier (512 MB).
        // Each coroutine holds one class in memory + decompiler overhead.
        private const val MAX_PARALLEL = 4  // 4×2 jobs = 8 peak calls; Vineflower ~360MB RSS (70%) — safe on Render 512MB
    }

    private val byName: Map<String, DecompilerAdapter> =
        adapters.associateBy { it.name.lowercase() }

    val availableDecompilers:  List<String> get() = adapters.map { it.name }
    val jarCapableDecompilers: List<String> get() = adapters.filter { it.supportsJar }.map { it.name }

    // ── Validation ────────────────────────────────────────────────────────────

    fun validateClassBytes(bytes: ByteArray, mode: String): Map<String, Any>? {
        if (bytes.isEmpty()) return mapOf("error" to "EMPTY_FILE", "message" to "File is empty")
        if (bytes.size > MAX_CLASS_BYTES) return mapOf(
            "error" to "FILE_TOO_LARGE", "message" to "Class file exceeds ${MAX_CLASS_BYTES / 1_048_576} MB limit"
        )
        resolve(mode) ?: return mapOf(
            "error" to "UNSUPPORTED_MODE",
            "message" to "Unknown decompiler '$mode'. Available: ${availableDecompilers.joinToString()}"
        )
        if (bytes[0] != 0xCA.toByte() || bytes[1] != 0xFE.toByte()) return mapOf(
            "error" to "INVALID_CLASS_MAGIC", "message" to "Not a valid .class file (bad magic bytes)"
        )
        return null
    }

    fun validateJarBytes(bytes: ByteArray, mode: String): Map<String, Any>? {
        if (bytes.isEmpty()) return mapOf("error" to "EMPTY_FILE", "message" to "File is empty")
        if (bytes.size > MAX_JAR_BYTES) return mapOf(
            "error" to "FILE_TOO_LARGE", "message" to "JAR exceeds ${MAX_JAR_BYTES / 1_048_576} MB limit"
        )
        resolve(mode) ?: return mapOf(
            "error" to "UNSUPPORTED_MODE",
            "message" to "Unknown decompiler '$mode'. Available: ${availableDecompilers.joinToString()}"
        )
        if (bytes[0] != 0x50.toByte() || bytes[1] != 0x4B.toByte()) return mapOf(
            "error" to "INVALID_JAR_MAGIC", "message" to "Not a valid JAR/ZIP file (bad magic bytes)"
        )
        return null
    }

    // ── Core operations (v1 endpoints) ────────────────────────────────────────

    fun resolve(mode: String): DecompilerAdapter? = byName[mode.lowercase()]

    fun runClass(mode: String, bytes: ByteArray, fileName: String): DecompileOutcome =
        runCatching { resolve(mode)!!.decompileClass(bytes, fileName) }
            .fold(
                onSuccess = { r -> DecompileOutcome.Success(r.source, r.warnings, r.errors) },
                onFailure = { e -> DecompileOutcome.Failure(e.javaClass.simpleName, e.message ?: "") }
            )

    fun runJar(mode: String, jar: File, outDir: File): DecompileOutcome =
        runCatching { resolve(mode)!!.decompileJar(jar, outDir) }
            .fold(
                onSuccess = { r -> DecompileOutcome.Success(r.source, r.warnings, r.errors) },
                onFailure = { e -> DecompileOutcome.Failure(e.javaClass.simpleName, e.message ?: "") }
            )

    // ── Chunk decompilation (hive, v2) ────────────────────────────────────────
    //
    // Two-pass strategy:
    //
    // PASS 1 — Single JAR call (fast path, supportsJar adapters only)
    //   Builds a mini-JAR from the class subset, runs decompileJar() once,
    //   reads resulting .java files from outDir.
    //   JADX writes via jadx.save()         → always produces files ✓
    //   CFR/Vineflower write via custom sink → may silently produce nothing
    //   If outDir is empty after Pass 1 → trigger Pass 2.
    //
    // PASS 2 — Parallel per-class (reliable fallback)
    //   Decompiles each class independently using decompileClass().
    //   decompileClass() returns source directly in DecompileResult.source,
    //   no file I/O involved — verified working for all adapters.
    //   Runs MAX_PARALLEL classes concurrently (semaphore-limited) so it's
    //   nearly as fast as JAR mode while being 100% reliable.

    suspend fun runChunk(mode: String, jarBytes: ByteArray, classNames: List<String>): ChunkRunResult {
        val adapter  = resolve(mode)!!
        val t0       = System.currentTimeMillis()
        val warnings = mutableListOf<String>()
        val errors   = mutableListOf<String>()
        val sources  = mutableMapOf<String, String>()

        // ── Pass 1: JAR mode ──────────────────────────────────────────────────
        if (adapter.supportsJar) {
            val miniJarBytes = buildMiniJar(jarBytes, classNames.toSet())
            val tempJar      = File.createTempFile("chunk-in-", ".jar")
            val outDir       = Files.createTempDirectory("chunk-out-").toFile()
            try {
                tempJar.writeBytes(miniJarBytes)
                runCatching { adapter.decompileJar(tempJar, outDir) }
                    .onSuccess { result ->
                        warnings.addAll(result.warnings)
                        errors.addAll(result.errors)
                        // Read files written to outDir (JADX always does this)
                        outDir.walkTopDown().filter { it.isFile }.forEach { file ->
                            sources[file.relativeTo(outDir).path.replace('\\', '/')] = file.readText()
                        }
                        // CFR/Vineflower may return source in result.source
                        // instead of writing files — use it if outDir was empty
                        if (sources.isEmpty() && result.source.isNotBlank()) {
                            sources["decompiled_${mode}.java"] = result.source
                        }
                    }
                    .onFailure { ex ->
                        errors.add("[${mode.uppercase()}-JAR] ${ex.javaClass.simpleName}: ${ex.message}")
                    }
            } finally {
                tempJar.delete()
                outDir.deleteRecursively()
            }
        }

        // ── Pass 2: Parallel per-class ────────────────────────────────────────
        // Triggers when Pass 1 produced nothing, or adapter doesn't support JAR.
        // Each class is decompiled independently on Dispatchers.IO, limited to
        // MAX_PARALLEL concurrent coroutines to avoid memory spikes.
        if (sources.isEmpty()) {
            // Single-pass extraction: scan jarBytes ONCE and collect all needed
            // class bytes. Previously called extractClassBytes() per class = N scans
            // of the full JAR (60 × 7MB = 420MB of in-memory reads). Now: 1 scan.
            data class ClassWork(val className: String, val bytes: ByteArray)

            val extracted = extractAllClassBytes(jarBytes, classNames.toSet())
            val work = classNames.mapNotNull { className ->
                val bytes = extracted[className]
                if (bytes == null) {
                    errors.add("[$className] Not found in JAR")
                    null
                } else {
                    ClassWork(className, bytes)
                }
            }

            // Parallel decompilation — each result is immutable, merged after awaitAll()
            data class ClassOutcome(
                val javaPath: String,
                val source:   String,
                val warns:    List<String>,
                val errs:     List<String>
            )

            val semaphore = Semaphore(minOf(work.size, MAX_PARALLEL))

            val outcomes: List<ClassOutcome?> = coroutineScope {
                work.map { (className, classBytes) ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val shortName = className.substringAfterLast('/')
                            runCatching { adapter.decompileClass(classBytes, shortName) }
                                .fold(
                                    onSuccess = { result ->
                                        if (result.source.isNotBlank()) {
                                            ClassOutcome(
                                                javaPath = className.removeSuffix(".class") + ".java",
                                                source   = result.source,
                                                warns    = result.warnings,
                                                errs     = result.errors
                                            )
                                        } else null  // empty source = skip
                                    },
                                    onFailure = { ex ->
                                        // Return null and record the error
                                        ClassOutcome(
                                            javaPath = "",
                                            source   = "",
                                            warns    = emptyList(),
                                            errs     = listOf("[${mode.uppercase()}][$className] ${ex.javaClass.simpleName}: ${ex.message}")
                                        )
                                    }
                                )
                        }
                    }
                }.awaitAll()
            }

            // Merge all outcomes sequentially (thread-safe, no concurrent writes)
            outcomes.filterNotNull().forEach { outcome ->
                if (outcome.source.isNotBlank()) sources[outcome.javaPath] = outcome.source
                warnings.addAll(outcome.warns)
                errors.addAll(outcome.errs)
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

    /** Single-class extraction — used by buildMiniJar internals. */
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

    /**
     * Single-pass bulk extraction.
     * Scans jarBytes ONCE and returns a map of className → bytes for all
     * requested classes. Used by Pass 2 to avoid N sequential ZIP scans.
     * Stops early once all requested classes have been found.
     */
    private fun extractAllClassBytes(jarBytes: ByteArray, classNames: Set<String>): Map<String, ByteArray> {
        val result = HashMap<String, ByteArray>(classNames.size)
        ZipInputStream(ByteArrayInputStream(jarBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null && result.size < classNames.size) {
                if (entry.name in classNames) {
                    result[entry.name] = zis.readBytes()
                }
                entry = zis.nextEntry
            }
        }
        return result
    }
}
