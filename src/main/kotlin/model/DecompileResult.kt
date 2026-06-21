package org.endless.model

data class DecompileResult(
    val source:   String,
    val warnings: List<String> = emptyList(),
    val errors:   List<String> = emptyList()
)

sealed class DecompileOutcome {
    data class Success(
        val source:   String,
        val warnings: List<String> = emptyList(),
        val errors:   List<String> = emptyList()
    ) : DecompileOutcome()

    data class Failure(
        val error:  String,
        val detail: String = ""
    ) : DecompileOutcome()
}

fun DecompileOutcome.toResponseMap(): Map<String, Any> = when (this) {
    is DecompileOutcome.Success -> mapOf(
        "status"   to "success",
        "source"   to source,
        "warnings" to warnings,
        "errors"   to errors
    )
    is DecompileOutcome.Failure -> mapOf(
        "status" to "error",
        "error"  to error,
        "detail" to detail
    )
}

/**
 * Request body for POST /decompile/chunk.
 *
 * JAR source:
 *   r2Url      — presigned R2 GET URL (large files > 300 KB)
 *   jarBase64  — base64-encoded JAR (small files ≤ 300 KB)
 *
 * Result destination:
 *   resultR2PutUrl — when set, KTOR builds a mini-ZIP from decompiled sources
 *                    and uploads it directly to R2 via this presigned PUT URL.
 *                    The JSON response then contains only metadata (no sources).
 *                    This is the key fix for large files: source code never passes
 *                    through the CF Worker, eliminating the JSON.parse + TextEncoder
 *                    + zipSync CPU that was killing the Worker on the free plan.
 *
 *                    When null (small files), sources are returned in the JSON
 *                    response as before.
 */
data class ChunkRequest(
    val jarBase64:      String?,
    val r2Url:          String?,
    val classes:        List<String>,
    val chunkIndex:     Int,
    val jobId:          String,
    val mode:           String = "cfr",
    val resultR2PutUrl: String? = null   // null → return sources in response
                                          // set  → upload mini-ZIP to R2, return tiny JSON
) {
    init {
        require(!jarBase64.isNullOrBlank() || !r2Url.isNullOrBlank()) {
            "ChunkRequest requires either jarBase64 or r2Url"
        }
    }
}

/**
 * Request body for POST /merge.
 *
 * After all chunk units finish decompiling and uploading their mini-ZIPs to R2,
 * the CF Worker sends this request to one KTOR unit. That unit downloads all the
 * mini-ZIPs, merges their entries into a single ZIP, and returns the binary ZIP
 * in the HTTP response.
 *
 * The Worker then writes that binary to R2 — zero string/source processing in Worker.
 */
data class MergeRequest(
    val chunkZipUrls: List<String>,   // presigned R2 GET URLs for each chunk mini-ZIP
    val jobId:        String,
    val warnings:     List<String> = emptyList(),
    val errors:       List<String> = emptyList()
)

data class ChunkRunResult(
    val sources:   Map<String, String>,
    val warnings:  List<String>,
    val errors:    List<String>,
    val elapsedMs: Long
)
