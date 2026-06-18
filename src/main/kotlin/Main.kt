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
import org.endless.model.toResponseMap
import org.endless.services.*
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
import java.util.zip.ZipOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// ── Globals ───────────────────────────────────────────────────────────────────

private val activeJobs    = AtomicInteger(0)
private val startTimeMs   = System.currentTimeMillis()
private val MAX_JOBS: Int = System.getenv("MAX_JOBS")?.toIntOrNull() ?: 2
private val TIMEOUT_MS    = System.getenv("DECOMPILE_TIMEOUT_MS")?.toLongOrNull() ?: 60_000L

// ── Shared HTTP client for R2 downloads ──────────────────────────────────────
// Used ONLY by the R2 path in /decompile/chunk when req.r2Url is set.
// One instance shared across all requests — thread-safe, connection-pooled.
// Java 17 stdlib — no extra dependency needed.

private val r2HttpClient: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(30))
    .followRedirects(HttpClient.Redirect.NORMAL)
    .build()

// ── Minimal Hello.class (120 bytes, Java 8 bytecode) ─────────────────────────
private val WARMUP_CLASS_BYTES = byteArrayOf(
    -54, -2, -70, -66,
    0, 0, 0, 52,
    0, 10,
    7, 0, 2,
    1, 0, 5, 72, 101, 108, 108, 111,
    7, 0, 4,
    1, 0, 16, 106, 97, 118, 97, 47, 108, 97, 110, 103,
    47, 79, 98, 106, 101, 99, 116,
    1, 0, 6, 60, 105, 110, 105, 116, 62,
    1, 0, 3, 40, 41, 86,
    1, 0, 4, 67, 111, 100, 101,
    12, 0, 5, 0, 6,
    10, 0, 3, 0, 8,
    0, 33,
    0, 1,
    0, 3,
    0, 0,
    0, 0,
    0, 1,
    0, 1, 0, 5, 0, 6, 0, 1,
    0, 7, 0, 0, 0, 17, 0, 1, 0, 1, 0, 0, 0, 5,
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
    respond(HttpStatusCode.Unauthorized,
        mapOf("status" to "error", "error" to "UNAUTHORIZED", "message" to msg))

// ── Capacity guard ────────────────────────────────────────────────────────────

private suspend fun ApplicationCall.respondBusy(): Boolean {
    if (activeJobs.get() >= MAX_JOBS) {
        respond(HttpStatusCode.ServiceUnavailable, mapOf(
            "status"     to "busy",
            "message"    to "Instance at capacity — retry shortly.",
            "activeJobs" to activeJobs.get(),
            "maxJobs"    to MAX_JOBS
        ))
        return true
    }
    return false
}

// ── R2 download ───────────────────────────────────────────────────────────────
// Downloads a JAR from a Cloudflare R2 presigned GET URL.
// Called only when ChunkRequest.r2Url is set (files > 5 MB).
// Runs in Dispatchers.IO — never blocks Netty's event loop.

private suspend fun downloadFromR2(url: String): ByteArray = withContext(Dispatchers.IO) {
    val req = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .timeout(Duration.ofSeconds(90))
        .GET()
        .build()

    val res = r2HttpClient.send(req, HttpResponse.BodyHandlers.ofByteArray())

    if (res.statusCode() != 200) {
        throw RuntimeException("R2 download failed — HTTP ${res.statusCode()}")
    }

    res.body()
}

// ── Entry point ───────────────────────────────────────────────────────────────

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, module = Application::module).start(wait = true)
}

fun Application.module() {

    install(ContentNegotiation) {
        jackson { disable(SerializationFeature.INDENT_OUTPUT) }
    }
    install(DoubleReceive)
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "status" to "error",
                "error"  to "INTERNAL_ERROR",
                "detail" to (cause.message ?: "Unknown error")
            ))
        }
    }

    val secret   = System.getenv("INTERNAL_API_KEY") ?: error("INTERNAL_API_KEY env var is required")
    val registry = DecompilerRegistry(listOf(
        CfrAdapter(), JadxAdapter(), VineflowerAdapter(), ProcyonAdapter(), JdCoreAdapter()
    ))

    routing {

        // ── GET /health ───────────────────────────────────────────────────────
        get("/health") {
            val rt         = Runtime.getRuntime()
            val heapUsedMb = (rt.totalMemory() - rt.freeMemory()) / 1_048_576L
            val heapMaxMb  = rt.maxMemory() / 1_048_576L

            val rssKb: Long = runCatching {
                java.io.File("/proc/self/status")
                    .readLines()
                    .firstOrNull { it.startsWith("VmRSS:") }
                    ?.let { Regex("(\\d+)").find(it)?.groupValues?.get(1)?.toLong() }
            }.getOrNull() ?: 0L

            val rssMb            = if (rssKb > 0L) rssKb / 1024L else heapUsedMb
            val containerLimitMb = System.getenv("CONTAINER_MEMORY_MB")?.toLongOrNull() ?: 512L
            val memoryPct        = if (containerLimitMb > 0) rssMb * 100 / containerLimitMb else 0L

            val jobs = activeJobs.get()
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
            if (!call.verifyHmacNoBody(secret)) {
                call.respondUnauthorized("Invalid or expired HMAC signature"); return@get
            }

            val t0 = System.currentTimeMillis()
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    withTimeout(10_000L) {
                        registry.runClass("cfr", WARMUP_CLASS_BYTES, "Hello.class")
                    }
                }
            }.getOrElse { ex -> DecompileOutcome.Failure("WARMUP_FAILED", ex.message ?: "") }

            val ok = outcome is DecompileOutcome.Success
            call.respond(
                if (ok) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                mapOf(
                    "status"        to if (ok) "warm" else "error",
                    "decompileOk"   to ok,
                    "elapsedMs"     to (System.currentTimeMillis() - t0),
                    "uptimeSeconds" to (System.currentTimeMillis() - startTimeMs) / 1000L,
                    "activeJobs"    to activeJobs.get(),
                    "error"         to if (!ok) (outcome as DecompileOutcome.Failure).error else null
                ).filterValues { it != null }
            )
        }

        // ── POST /decompile/class ─────────────────────────────────────────────
        post("/decompile/class") {
            val rawBody = call.receive<ByteArray>()
            if (!call.verifyHmac(secret, rawBody)) {
                call.respondUnauthorized("Invalid or expired HMAC signature"); return@post
            }
            if (call.respondBusy()) return@post

            var fileBytes: ByteArray? = null
            var fileName  = "unknown.class"
            val parts     = mutableMapOf<String, String>()

            call.receiveMultipart().forEachPart { part ->
                when (part) {
                    is PartData.FormItem -> parts[part.name ?: ""] = part.value
                    is PartData.FileItem -> {
                        fileName  = part.originalFileName ?: fileName
                        fileBytes = part.streamProvider().readBytes()
                    }
                    else -> Unit
                }
                part.dispose()
            }

            val bytes = fileBytes ?: run {
                call.respond(HttpStatusCode.BadRequest,
                    mapOf("error" to "NO_FILE", "message" to "No file part in request"))
                return@post
            }
            val mode = (parts["mode"] ?: "cfr").lowercase()

            registry.validateClassBytes(bytes, mode)?.let {
                call.respond(HttpStatusCode.BadRequest, it); return@post
            }

            activeJobs.incrementAndGet()
            try {
                val outcome = withContext(Dispatchers.IO) {
                    withTimeout(TIMEOUT_MS) { registry.runClass(mode, bytes, fileName) }
                }
                call.respond(HttpStatusCode.OK, outcome.toResponseMap())
            } catch (e: TimeoutCancellationException) {
                call.respond(HttpStatusCode.GatewayTimeout, mapOf(
                    "status" to "error", "error" to "TIMEOUT",
                    "message" to "Decompilation timed out after ${TIMEOUT_MS}ms"
                ))
            } finally {
                activeJobs.decrementAndGet()
            }
        }

        // ── POST /decompile/jar ───────────────────────────────────────────────
        post("/decompile/jar") {
            val rawBody = call.receive<ByteArray>()
            if (!call.verifyHmac(secret, rawBody)) {
                call.respondUnauthorized("Invalid or expired HMAC signature"); return@post
            }
            if (call.respondBusy()) return@post

            var fileBytes: ByteArray? = null
            val parts = mutableMapOf<String, String>()

            call.receiveMultipart().forEachPart { part ->
                when (part) {
                    is PartData.FormItem -> parts[part.name ?: ""] = part.value
                    is PartData.FileItem -> fileBytes = part.streamProvider().readBytes()
                    else -> Unit
                }
                part.dispose()
            }

            val bytes = fileBytes ?: run {
                call.respond(HttpStatusCode.BadRequest,
                    mapOf("error" to "NO_FILE", "message" to "No file part in request"))
                return@post
            }
            val mode = (parts["mode"] ?: "cfr").lowercase()

            registry.validateJarBytes(bytes, mode)?.let {
                call.respond(HttpStatusCode.BadRequest, it); return@post
            }

            activeJobs.incrementAndGet()
            val tempJar = File.createTempFile("jar-in-", ".jar")
            val outDir  = Files.createTempDirectory("jar-out-").toFile()

            try {
                val zipBytes = withContext(Dispatchers.IO) {
                    withTimeout(TIMEOUT_MS) {
                        tempJar.writeBytes(bytes)
                        when (val outcome = registry.runJar(mode, tempJar, outDir)) {
                            is DecompileOutcome.Failure ->
                                throw RuntimeException("${outcome.error}: ${outcome.detail}")
                            is DecompileOutcome.Success ->
                                buildZipBytes(outDir, outcome)
                        }
                    }
                }
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment
                        .withParameter(ContentDisposition.Parameters.FileName, "decompiled.zip")
                        .toString()
                )
                call.respondBytes(zipBytes, ContentType.Application.Zip, HttpStatusCode.OK)
            } catch (e: TimeoutCancellationException) {
                call.respond(HttpStatusCode.GatewayTimeout, mapOf(
                    "status" to "error", "error" to "TIMEOUT",
                    "message" to "Decompilation timed out after ${TIMEOUT_MS}ms"
                ))
            } catch (e: RuntimeException) {
                call.respond(HttpStatusCode.UnprocessableEntity, mapOf(
                    "status" to "error", "error" to "DECOMPILE_FAILED", "detail" to e.message
                ))
            } finally {
                tempJar.delete()
                outDir.deleteRecursively()
                activeJobs.decrementAndGet()
            }
        }

        // ── POST /decompile/chunk ─────────────────────────────────────────────
        // Hive endpoint called by the CF Worker orchestrator.
        //
        // Two source modes (see ChunkRequest for full docs):
        //
        //   Direct / Hybrid path (file ≤ 5 MB):
        //     req.jarBase64 is set. Decode and process in memory.
        //     This is the same as the original behaviour.
        //
        //   R2 path (file > 5 MB):
        //     req.r2Url is set — a Cloudflare R2 presigned GET URL.
        //     This unit downloads the full JAR directly from R2.
        //     The CF Worker never held the file bytes.
        //
        // Both paths converge at registry.runChunk() — identical from there.
        post("/decompile/chunk") {
            val rawBody = call.receive<ByteArray>()
            if (!call.verifyHmac(secret, rawBody)) {
                call.respondUnauthorized("Invalid or expired HMAC signature"); return@post
            }
            if (call.respondBusy()) return@post

            val req = runCatching { call.receive<ChunkRequest>() }.getOrElse {
                call.respond(HttpStatusCode.BadRequest, mapOf(
                    "error"   to "BAD_REQUEST",
                    "message" to "Invalid JSON — required: classes, chunkIndex, jobId, and either jarBase64 or r2Url"
                ))
                return@post
            }

            if (req.classes.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest,
                    mapOf("error" to "NO_CLASSES", "message" to "classes list must not be empty"))
                return@post
            }

            // ── Resolve JAR bytes from whichever source is provided ───────────
            val jarBytes: ByteArray = when {

                // R2 path: download the full JAR from Cloudflare R2
                !req.r2Url.isNullOrBlank() -> {
                    runCatching {
                        downloadFromR2(req.r2Url)
                    }.getOrElse { ex ->
                        call.respond(HttpStatusCode.BadGateway, mapOf(
                            "error"   to "R2_DOWNLOAD_FAILED",
                            "message" to "Could not download JAR from R2: ${ex.message}"
                        ))
                        return@post
                    }
                }

                // Direct / Hybrid path: decode base64 JAR from request body
                !req.jarBase64.isNullOrBlank() -> {
                    runCatching { Base64.getDecoder().decode(req.jarBase64) }.getOrElse {
                        call.respond(HttpStatusCode.BadRequest,
                            mapOf("error" to "INVALID_BASE64", "message" to "jarBase64 could not be decoded"))
                        return@post
                    }
                }

                // Should never happen — ChunkRequest.init validates this
                else -> {
                    call.respond(HttpStatusCode.BadRequest, mapOf(
                        "error"   to "MISSING_SOURCE",
                        "message" to "Either r2Url or jarBase64 must be provided"
                    ))
                    return@post
                }
            }

            registry.validateJarBytes(jarBytes, req.mode)?.let {
                call.respond(HttpStatusCode.BadRequest, it); return@post
            }

            activeJobs.incrementAndGet()
            try {
                val result = withContext(Dispatchers.IO) {
                    withTimeout(TIMEOUT_MS) { registry.runChunk(req.mode, jarBytes, req.classes) }
                }
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
            } catch (e: TimeoutCancellationException) {
                call.respond(HttpStatusCode.GatewayTimeout, mapOf(
                    "status"     to "error",
                    "jobId"      to req.jobId,
                    "chunkIndex" to req.chunkIndex,
                    "error"      to "TIMEOUT",
                    "message"    to "Chunk decompilation timed out after ${TIMEOUT_MS}ms"
                ))
            } finally {
                activeJobs.decrementAndGet()
            }
        }
    }
}

// ── ZIP builder ───────────────────────────────────────────────────────────────

private fun buildZipBytes(outDir: File, outcome: DecompileOutcome.Success): ByteArray {
    val baos = java.io.ByteArrayOutputStream()
    ZipOutputStream(baos).use { zos ->
        outDir.walkTopDown().filter { it.isFile }.forEach { file ->
            zos.putNextEntry(ZipEntry(file.relativeTo(outDir).path.replace('\\', '/')))
            file.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()
        }
        if (outcome.warnings.isNotEmpty() || outcome.errors.isNotEmpty()) {
            zos.putNextEntry(ZipEntry("DECOMPILE_NOTES.txt"))
            val notes = buildString {
                if (outcome.warnings.isNotEmpty()) {
                    appendLine("=== WARNINGS ==="); outcome.warnings.forEach { appendLine(it) }
                }
                if (outcome.errors.isNotEmpty()) {
                    appendLine("=== ERRORS ==="); outcome.errors.forEach { appendLine(it) }
                }
            }
            zos.write(notes.toByteArray())
            zos.closeEntry()
        }
    }
    return baos.toByteArray()
}
