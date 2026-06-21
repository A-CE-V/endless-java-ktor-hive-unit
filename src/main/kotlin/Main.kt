package org.endless

import com.fasterxml.jackson.databind.SerializationFeature
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.serialization.jackson.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.doublereceive.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.endless.model.ChunkRequest
import org.endless.model.DecompileOutcome
import org.endless.model.MergeRequest
import org.endless.model.toResponseMap
import org.endless.services.*
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// ── Globals ───────────────────────────────────────────────────────────────────

private val activeJobs  = AtomicInteger(0)
private val startTimeMs = System.currentTimeMillis()
private val MAX_JOBS    = System.getenv("MAX_JOBS")?.toIntOrNull() ?: 2
private val TIMEOUT_MS  = System.getenv("DECOMPILE_TIMEOUT_MS")?.toLongOrNull() ?: 120_000L

// Shared HTTP client for all R2 operations (download input JAR, upload mini-ZIP,
// download chunk ZIPs for merge). Thread-safe, connection-pooled, Java 17 stdlib.
private val r2HttpClient: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(30))
    .followRedirects(HttpClient.Redirect.NORMAL)
    .build()

// Minimal Hello.class (Java 8 bytecode) used for JVM warmup
private val WARMUP_CLASS_BYTES = byteArrayOf(
    -54, -2, -70, -66, 0, 0, 0, 52, 0, 10, 7, 0, 2,
    1, 0, 5, 72, 101, 108, 108, 111, 7, 0, 4,
    1, 0, 16, 106, 97, 118, 97, 47, 108, 97, 110, 103, 47, 79, 98, 106, 101, 99, 116,
    1, 0, 6, 60, 105, 110, 105, 116, 62, 1, 0, 3, 40, 41, 86,
    1, 0, 4, 67, 111, 100, 101, 12, 0, 5, 0, 6, 10, 0, 3, 0, 8,
    0, 33, 0, 1, 0, 3, 0, 0, 0, 0, 0, 1,
    0, 1, 0, 5, 0, 6, 0, 1, 0, 7, 0, 0, 0, 17, 0, 1, 0, 1, 0, 0, 0, 5,
    42, -73, 0, 9, -79, 0, 0, 0, 0
)

// ── HMAC auth ─────────────────────────────────────────────────────────────────

private fun ApplicationCall.verifyHmac(secret: String, rawBody: ByteArray): Boolean {
    val timestamp = request.header("x-auth-timestamp") ?: return false
    val signature = request.header("x-auth-signature") ?: return false
    val ts        = timestamp.toLongOrNull() ?: return false
    if (Math.abs(System.currentTimeMillis() - ts) > 60_000) return false
    val mac = Mac.getInstance("HmacSHA256").apply {
        init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        update(timestamp.toByteArray(Charsets.UTF_8))
        if (rawBody.isNotEmpty()) update(rawBody)
    }
    return mac.doFinal().joinToString("") { "%02x".format(it) } == signature
}

private fun ApplicationCall.verifyHmacNoBody(secret: String): Boolean {
    val timestamp = request.header("x-auth-timestamp") ?: return false
    val signature = request.header("x-auth-signature") ?: return false
    val ts        = timestamp.toLongOrNull() ?: return false
    if (Math.abs(System.currentTimeMillis() - ts) > 60_000) return false
    val mac = Mac.getInstance("HmacSHA256").apply {
        init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        update(timestamp.toByteArray(Charsets.UTF_8))
    }
    return mac.doFinal().joinToString("") { "%02x".format(it) } == signature
}

private suspend fun ApplicationCall.respondUnauthorized(msg: String) =
    respond(HttpStatusCode.Unauthorized, mapOf("status" to "error", "error" to "UNAUTHORIZED", "message" to msg))

private suspend fun ApplicationCall.respondBusy(): Boolean {
    if (activeJobs.get() >= MAX_JOBS) {
        respond(HttpStatusCode.ServiceUnavailable, mapOf(
            "status" to "busy", "message" to "Instance at capacity — retry shortly.",
            "activeJobs" to activeJobs.get(), "maxJobs" to MAX_JOBS
        ))
        return true
    }
    return false
}

// ── R2 helpers ────────────────────────────────────────────────────────────────

/**
 * Downloads bytes from a presigned R2 GET URL.
 * Used for:
 *   - Input JAR download in /decompile/chunk (r2Url field)
 *   - Chunk mini-ZIP downloads in /merge
 */
private suspend fun downloadFromR2(url: String): ByteArray = withContext(Dispatchers.IO) {
    val req = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .timeout(Duration.ofSeconds(120))
        .GET()
        .build()
    val res = r2HttpClient.send(req, HttpResponse.BodyHandlers.ofByteArray())
    if (res.statusCode() != 200) throw RuntimeException("R2 download failed: HTTP ${res.statusCode()}")
    res.body()
}

/**
 * Uploads bytes to a presigned R2 PUT URL.
 * Used by /decompile/chunk to upload its mini-ZIP result directly to R2,
 * bypassing the CF Worker entirely. This eliminates the JSON.parse + TextEncoder
 * + zipSync CPU that was killing the Worker on the free plan.
 */
private suspend fun uploadToR2(url: String, data: ByteArray): Unit = withContext(Dispatchers.IO) {
    val req = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .timeout(Duration.ofSeconds(120))
        .PUT(HttpRequest.BodyPublishers.ofByteArray(data))
        .header("Content-Type", "application/zip")
        .build()
    val res = r2HttpClient.send(req, HttpResponse.BodyHandlers.discarding())
    if (res.statusCode() !in 200..299) {
        throw RuntimeException("R2 upload failed: HTTP ${res.statusCode()}")
    }
}

/**
 * Builds a mini-ZIP from a sources map (path → Java source code).
 * Called by /decompile/chunk when resultR2PutUrl is set.
 * Uses Java's ZipOutputStream — fast and reliable, no third-party library needed.
 */
private fun buildMiniZip(sources: Map<String, String>, warnings: List<String>, errors: List<String>): ByteArray {
    val baos = ByteArrayOutputStream()
    ZipOutputStream(baos).use { zos ->
        for ((path, src) in sources) {
            zos.putNextEntry(ZipEntry(path))
            zos.write(src.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        if (warnings.isNotEmpty() || errors.isNotEmpty()) {
            zos.putNextEntry(ZipEntry("DECOMPILE_NOTES.txt"))
            val notes = buildString {
                if (warnings.isNotEmpty()) { appendLine("=== WARNINGS ==="); warnings.forEach { appendLine(it) } }
                if (errors.isNotEmpty())   { appendLine("=== ERRORS ===");   errors.forEach   { appendLine(it) } }
            }
            zos.write(notes.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
    }
    return baos.toByteArray()
}

/**
 * Merges N mini-ZIPs (downloaded from R2) into a single final ZIP.
 * Called by /merge. Uses ZipInputStream to read each mini-ZIP and
 * ZipOutputStream to write the merged result.
 * Duplicate file names are handled gracefully (first occurrence wins).
 */
private suspend fun buildMergedZip(
    chunkZipUrls: List<String>,
    warnings:     List<String>,
    errors:       List<String>
): ByteArray = withContext(Dispatchers.IO) {
    val baos = ByteArrayOutputStream()
    val seen = mutableSetOf<String>()

    ZipOutputStream(baos).use { out ->
        // Download and merge all chunk ZIPs (in parallel for speed)
        val chunkBytes = chunkZipUrls.map { url ->
            try { downloadFromR2(url) }
            catch (e: Exception) {
                System.err.println("[merge] Failed to download chunk ZIP: ${e.message}")
                ByteArray(0)
            }
        }

        for (bytes in chunkBytes) {
            if (bytes.isEmpty()) continue
            ZipInputStream(BufferedInputStream(ByteArrayInputStream(bytes))).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    // Skip DECOMPILE_NOTES from individual chunks — we'll write our own
                    if (!entry.isDirectory && entry.name != "DECOMPILE_NOTES.txt" && seen.add(entry.name)) {
                        out.putNextEntry(ZipEntry(entry.name))
                        zin.copyTo(out)
                        out.closeEntry()
                    }
                    entry = zin.nextEntry
                }
            }
        }

        // Write combined notes at the end if any warnings/errors were collected
        if (warnings.isNotEmpty() || errors.isNotEmpty()) {
            out.putNextEntry(ZipEntry("DECOMPILE_NOTES.txt"))
            val notes = buildString {
                if (warnings.isNotEmpty()) { appendLine("=== WARNINGS ==="); warnings.forEach { appendLine(it) } }
                if (errors.isNotEmpty())   { appendLine("=== ERRORS ===");   errors.forEach   { appendLine(it) } }
            }
            out.write(notes.toByteArray(Charsets.UTF_8))
            out.closeEntry()
        }
    }

    baos.toByteArray()
}

// ── Entry point ───────────────────────────────────────────────────────────────

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, module = Application::module).start(wait = true)
}

fun Application.module() {
    install(ContentNegotiation) { jackson { disable(SerializationFeature.INDENT_OUTPUT) } }
    install(DoubleReceive)
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "status" to "error", "error" to "INTERNAL_ERROR", "detail" to (cause.message ?: "Unknown")
            ))
        }
    }

    val secret   = System.getenv("INTERNAL_API_KEY") ?: error("INTERNAL_API_KEY is required")
    val registry = DecompilerRegistry(listOf(
        CfrAdapter(), JadxAdapter(), VineflowerAdapter(), ProcyonAdapter(), JdCoreAdapter()
    ))

    routing {

        // ── GET /health ───────────────────────────────────────────────────────
        get("/health") {
            val rt            = Runtime.getRuntime()
            val heapUsedMb    = (rt.totalMemory() - rt.freeMemory()) / 1_048_576L
            val heapMaxMb     = rt.maxMemory() / 1_048_576L
            val rssKb: Long   = runCatching {
                File("/proc/self/status").readLines()
                    .firstOrNull { it.startsWith("VmRSS:") }
                    ?.let { Regex("(\\d+)").find(it)?.groupValues?.get(1)?.toLong() }
            }.getOrNull() ?: 0L

            val rssMb            = if (rssKb > 0L) rssKb / 1024L else heapUsedMb
            val containerLimitMb = System.getenv("CONTAINER_MEMORY_MB")?.toLongOrNull() ?: 512L
            val memoryPct        = if (containerLimitMb > 0) rssMb * 100 / containerLimitMb else 0L
            val jobs             = activeJobs.get()

            call.respond(mapOf(
                "status"         to "ok",
                "activeJobs"     to jobs,
                "maxJobs"        to MAX_JOBS,
                "busy"           to (jobs >= MAX_JOBS),
                "memoryUsedMb"   to rssMb,
                "memoryMaxMb"    to containerLimitMb,
                "memoryPct"      to memoryPct,
                "heapUsedMb"     to heapUsedMb,
                "heapMaxMb"      to heapMaxMb,
                "uptimeSeconds"  to (System.currentTimeMillis() - startTimeMs) / 1000L,
                "timeoutMs"      to TIMEOUT_MS,
                "decompilers"    to registry.availableDecompilers,
                "jarDecompilers" to registry.jarCapableDecompilers
            ))
        }

        // ── GET /warmup ───────────────────────────────────────────────────────
        get("/warmup") {
            if (!call.verifyHmacNoBody(secret)) { call.respondUnauthorized("Invalid HMAC"); return@get }
            val t0 = System.currentTimeMillis()
            val outcome = runCatching {
                withContext(Dispatchers.IO) { withTimeout(10_000L) { registry.runClass("cfr", WARMUP_CLASS_BYTES, "Hello.class") } }
            }.getOrElse { ex -> DecompileOutcome.Failure("WARMUP_FAILED", ex.message ?: "") }
            val ok = outcome is DecompileOutcome.Success
            call.respond(if (ok) HttpStatusCode.OK else HttpStatusCode.InternalServerError, mapOf(
                "status"        to if (ok) "warm" else "error",
                "decompileOk"   to ok,
                "elapsedMs"     to (System.currentTimeMillis() - t0),
                "uptimeSeconds" to (System.currentTimeMillis() - startTimeMs) / 1000L,
                "activeJobs"    to activeJobs.get()
            ))
        }

        // ── POST /decompile/chunk ─────────────────────────────────────────────
        //
        // Two result modes, controlled by ChunkRequest.resultR2PutUrl:
        //
        // Mode A — resultR2PutUrl is NULL (small files ≤ 300 KB):
        //   Original behaviour. Sources returned in JSON response body.
        //   Worker receives source code and builds the ZIP.
        //
        // Mode B — resultR2PutUrl is SET (large files > 300 KB):
        //   KTOR decompiles, builds mini-ZIP, uploads to R2 via the PUT URL.
        //   Returns tiny JSON: { jobId, chunkIndex, classCount, warnings, errors }.
        //   NO source code in the response. Worker CPU cost: ~0.01ms for JSON parse.
        //   This is the fix for "Worker killed on free plan" for large files.
        post("/decompile/chunk") {
            val rawBody = call.receive<ByteArray>()
            if (!call.verifyHmac(secret, rawBody)) { call.respondUnauthorized("Invalid HMAC"); return@post }
            if (call.respondBusy()) return@post

            val req = runCatching { call.receive<ChunkRequest>() }.getOrElse {
                call.respond(HttpStatusCode.BadRequest, mapOf(
                    "error" to "BAD_REQUEST",
                    "message" to "Invalid JSON — required: classes, chunkIndex, jobId, and either jarBase64 or r2Url"
                ))
                return@post
            }

            if (req.classes.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "NO_CLASSES", "message" to "classes must not be empty"))
                return@post
            }

            // ── Resolve JAR bytes ─────────────────────────────────────────────
            val jarBytes: ByteArray = when {
                !req.r2Url.isNullOrBlank() -> {
                    runCatching { downloadFromR2(req.r2Url) }.getOrElse { ex ->
                        call.respond(HttpStatusCode.BadGateway, mapOf(
                            "error" to "R2_DOWNLOAD_FAILED", "message" to "Could not download JAR: ${ex.message}"
                        ))
                        return@post
                    }
                }
                !req.jarBase64.isNullOrBlank() -> {
                    runCatching { Base64.getDecoder().decode(req.jarBase64) }.getOrElse {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "INVALID_BASE64", "message" to "jarBase64 decode failed"))
                        return@post
                    }
                }
                else -> {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "MISSING_SOURCE", "message" to "Either r2Url or jarBase64 required"))
                    return@post
                }
            }

            registry.validateJarBytes(jarBytes, req.mode)?.let { call.respond(HttpStatusCode.BadRequest, it); return@post }

            activeJobs.incrementAndGet()
            try {
                val result = withContext(Dispatchers.IO) {
                    withTimeout(TIMEOUT_MS) { registry.runChunk(req.mode, jarBytes, req.classes) }
                }

                // ── Mode B: upload mini-ZIP to R2, return tiny JSON ───────────
                if (!req.resultR2PutUrl.isNullOrBlank()) {
                    val miniZip = buildMiniZip(result.sources, result.warnings, result.errors)

                    runCatching { uploadToR2(req.resultR2PutUrl, miniZip) }.onFailure { ex ->
                        call.respond(HttpStatusCode.BadGateway, mapOf(
                            "error" to "R2_UPLOAD_FAILED", "message" to "Could not upload mini-ZIP: ${ex.message}"
                        ))
                        return@post
                    }

                    // Return TINY JSON — no source code, no large payload
                    call.respond(HttpStatusCode.OK, mapOf(
                        "status"       to "success",
                        "jobId"        to req.jobId,
                        "chunkIndex"   to req.chunkIndex,
                        "decompiler"   to req.mode,
                        "classCount"   to req.classes.size,
                        "sources"      to emptyMap<String, String>(),  // intentionally empty
                        "warnings"     to result.warnings,
                        "errors"       to result.errors,
                        "processingMs" to result.elapsedMs
                    ))

                } else {
                    // ── Mode A: return sources in response (small files) ───────
                    call.respond(HttpStatusCode.OK, mapOf(
                        "status"       to "success",
                        "jobId"        to req.jobId,
                        "chunkIndex"   to req.chunkIndex,
                        "decompiler"   to req.mode,
                        "classCount"   to req.classes.size,
                        "sources"      to result.sources,
                        "warnings"     to result.warnings,
                        "errors"       to result.errors,
                        "processingMs" to result.elapsedMs
                    ))
                }

            } catch (e: TimeoutCancellationException) {
                call.respond(HttpStatusCode.GatewayTimeout, mapOf(
                    "status" to "error", "jobId" to req.jobId, "chunkIndex" to req.chunkIndex,
                    "error" to "TIMEOUT", "message" to "Timed out after ${TIMEOUT_MS}ms"
                ))
            } finally {
                activeJobs.decrementAndGet()
            }
        }

        // ── POST /merge ───────────────────────────────────────────────────────
        //
        // Called by the CF Worker after all chunk units finish uploading their
        // mini-ZIPs to R2. This unit downloads all mini-ZIPs, merges their entries
        // into a single final ZIP, and returns it as binary in the HTTP response.
        //
        // The Worker writes this binary directly to R2 at results/{jobId}.zip.
        // Zero source-code processing happens in the Worker — all string/ZIP work
        // is done here in the JVM where there is no CPU time limit.
        //
        // The merge counts as 1 active job (download + ZIP merge can be intensive).
        post("/merge") {
            val rawBody = call.receive<ByteArray>()
            if (!call.verifyHmac(secret, rawBody)) { call.respondUnauthorized("Invalid HMAC"); return@post }
            if (call.respondBusy()) return@post

            val req = runCatching { call.receive<MergeRequest>() }.getOrElse {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "BAD_REQUEST", "message" to "Invalid MergeRequest JSON"))
                return@post
            }

            if (req.chunkZipUrls.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "NO_CHUNKS", "message" to "chunkZipUrls must not be empty"))
                return@post
            }

            activeJobs.incrementAndGet()
            try {
                val mergedZip = withContext(Dispatchers.IO) {
                    withTimeout(TIMEOUT_MS) {
                        buildMergedZip(req.chunkZipUrls, req.warnings, req.errors)
                    }
                }

                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "decompiled.zip").toString()
                )
                call.respondBytes(mergedZip, ContentType.Application.Zip, HttpStatusCode.OK)

            } catch (e: TimeoutCancellationException) {
                call.respond(HttpStatusCode.GatewayTimeout, mapOf(
                    "error" to "MERGE_TIMEOUT", "jobId" to req.jobId,
                    "message" to "Merge timed out after ${TIMEOUT_MS}ms"
                ))
            } finally {
                activeJobs.decrementAndGet()
            }
        }

        // ── POST /decompile/class ─────────────────────────────────────────────
        post("/decompile/class") {
            val rawBody = call.receive<ByteArray>()
            if (!call.verifyHmac(secret, rawBody)) { call.respondUnauthorized("Invalid HMAC"); return@post }
            if (call.respondBusy()) return@post

            var fileBytes: ByteArray? = null
            var fileName = "unknown.class"
            val parts = mutableMapOf<String, String>()

            call.receiveMultipart().forEachPart { part ->
                when (part) {
                    is PartData.FormItem -> parts[part.name ?: ""] = part.value
                    is PartData.FileItem -> { fileName = part.originalFileName ?: fileName; fileBytes = part.streamProvider().readBytes() }
                    else -> Unit
                }
                part.dispose()
            }

            val bytes = fileBytes ?: run {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "NO_FILE")); return@post
            }
            val mode = (parts["mode"] ?: "cfr").lowercase()
            registry.validateClassBytes(bytes, mode)?.let { call.respond(HttpStatusCode.BadRequest, it); return@post }

            activeJobs.incrementAndGet()
            try {
                val outcome = withContext(Dispatchers.IO) { withTimeout(TIMEOUT_MS) { registry.runClass(mode, bytes, fileName) } }
                call.respond(HttpStatusCode.OK, outcome.toResponseMap())
            } catch (e: TimeoutCancellationException) {
                call.respond(HttpStatusCode.GatewayTimeout, mapOf("error" to "TIMEOUT", "message" to "Timed out after ${TIMEOUT_MS}ms"))
            } finally {
                activeJobs.decrementAndGet()
            }
        }
    }
}
