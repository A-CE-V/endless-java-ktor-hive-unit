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
 * Request body for POST /decompile/chunk — sent by the CF Worker orchestrator.
 *
 * Two mutually exclusive source modes:
 *
 *   Direct / Hybrid path (file ≤ 5 MB):
 *     [jarBase64] contains the full JAR base64-encoded.
 *     [r2Url] is null.
 *
 *   R2 path (file > 5 MB):
 *     [r2Url] is a presigned Cloudflare R2 GET URL.
 *     [jarBase64] is null.
 *     This unit downloads the JAR directly from R2.
 *
 * Exactly one must be non-null — enforced in [init].
 */
data class ChunkRequest(
    val jarBase64:  String?,
    val r2Url:      String?,
    val classes:    List<String>,
    val chunkIndex: Int,
    val jobId:      String,
    val mode:       String = "cfr"
) {
    init {
        require(!jarBase64.isNullOrBlank() || !r2Url.isNullOrBlank()) {
            "ChunkRequest requires either jarBase64 or r2Url — both are null/blank"
        }
    }
}

data class ChunkRunResult(
    val sources:   Map<String, String>,
    val warnings:  List<String>,
    val errors:    List<String>,
    val elapsedMs: Long
)
