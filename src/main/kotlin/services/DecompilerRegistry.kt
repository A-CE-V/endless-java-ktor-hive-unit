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

        // ── FIX: raised from 4 to 16 ────────────────────────────────────────────
        // BEFORE: only 4 classes decompiled simultaneously per chunk, regardless
        //   of how many classes were assigned to that chunk (could be 60+).
        //   A 60-class chunk took 15 sequential batches of 4 — the bottleneck
        //   was never the decompiler itself, it was this artificial cap.
        //
        // AFTER: 16 concurrent decompiles per chunk. Kotlin's Dispatchers.IO pool
        //   defaults to 64 threads (or 2× CPU cores, whichever is larger) — 16 is
        //   a safe fraction of that even when 2-3 chunks run on the same Render
        //   instance simultaneously (MAX_JOBS=2 means up to 2 chunks in parallel,
        //   2 × 16 = 32 threads max, well under the pool's 64-thread ceiling).
        //
        // A 60-class chunk now needs only 4 sequential batches of 16 instead of
        // 15 batches of 4 — roughly 4× faster per-chunk decompilation.
        private const val MAX_PARALLEL = 16
    }

    private val byName: Map<String, DecompilerAdapter> =
        adapters.associateBy { it.name.lowercase() }

    val availableDecompilers:  List<String> get() = adapters.map { it.name }
    val jarCapableDecompilers: List<String> get() = adapters.filter { it.supportsJar }.map { it.name }

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

    suspend fun runChunk(mode: String, jarBytes: ByteArray, classNames: List<String>): ChunkRunResult {
        val adapter  = resolve(mode)!!
        val t0       = System.currentTimeMillis()
        val warnings = mutableListOf<String>()
        val errors   = mutableListOf<String>()
        val sources  = mutableMapOf<String, String>()

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
                        outDir.walkTopDown().filter { it.isFile }.forEach { file ->
                            sources[file.relativeTo(outDir).path.replace('\\', '/')] = file.readText()
                        }
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

        if (sources.isEmpty()) {
            data class ClassWork(val className: String, val bytes: ByteArray)
            data class ClassOutcome(val javaPath: String, val source: String, val warns: List<String>, val errs: List<String>)

            val extracted = extractAllClassBytes(jarBytes, classNames.toSet())
            val work = classNames.mapNotNull { className ->
                val bytes = extracted[className]
                if (bytes == null) { errors.add("[$className] Not found in JAR"); null }
                else ClassWork(className, bytes)
            }

            // Semaphore now allows up to 16 concurrent decompiles (was 4).
            val semaphore = Semaphore(minOf(work.size, MAX_PARALLEL))
            val outcomes: List<ClassOutcome?> = coroutineScope {
                work.map { (className, classBytes) ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val shortName = className.substringAfterLast('/')
                            runCatching { adapter.decompileClass(classBytes, shortName) }
                                .fold(
                                    onSuccess = { result ->
                                        if (result.source.isNotBlank())
                                            ClassOutcome(className.removeSuffix(".class") + ".java", result.source, result.warnings, result.errors)
                                        else null
                                    },
                                    onFailure = { ex ->
                                        ClassOutcome("", "", emptyList(),
                                            listOf("[${mode.uppercase()}][$className] ${ex.javaClass.simpleName}: ${ex.message}"))
                                    }
                                )
                        }
                    }
                }.awaitAll()
            }

            outcomes.filterNotNull().forEach { outcome ->
                if (outcome.source.isNotBlank()) sources[outcome.javaPath] = outcome.source
                warnings.addAll(outcome.warns)
                errors.addAll(outcome.errs)
            }
        }

        return ChunkRunResult(sources, warnings, errors, System.currentTimeMillis() - t0)
    }

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

    private fun extractAllClassBytes(jarBytes: ByteArray, classNames: Set<String>): Map<String, ByteArray> {
        val result = HashMap<String, ByteArray>(classNames.size)
        ZipInputStream(ByteArrayInputStream(jarBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null && result.size < classNames.size) {
                if (entry.name in classNames) result[entry.name] = zis.readBytes()
                entry = zis.nextEntry
            }
        }
        return result
    }
}
