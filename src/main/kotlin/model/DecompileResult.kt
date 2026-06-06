package org.endless.model

// ── Outcome from a single decompile operation ─────────────────────────────────
sealed class DecompileOutcome {
    data class Success(
        val source: String,
        val warnings: List<String> = emptyList(),
        val errors:   List<String> = emptyList()
    ) : DecompileOutcome()

    data class Failure(
        val error: String,
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

// ── Request body for /decompile/chunk ─────────────────────────────────────────
data class ChunkRequest(
    val jarBase64:  String,         // full original JAR, base64-encoded
    val classes:    List<String>,   // .class entry names to decompile (e.g. "com/Foo.class")
    val chunkIndex: Int,            // position in the overall job (for ordering on orchestrator)
    val jobId:      String,         // ties this chunk to its parent job
    val mode:       String = "cfr"  // decompiler to use
)

// ── Result returned by DecompilerRegistry.runChunk ───────────────────────────
data class ChunkRunResult(
    val sources:   Map<String, String>, // relative-path → java source (e.g. "com/Foo.java" → "...")
    val warnings:  List<String>,
    val errors:    List<String>,
    val elapsedMs: Long
)
