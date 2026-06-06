package org.endless.model

// ── Original data class ───────────────────────────────────────────────────────
// Returned by every DecompilerAdapter implementation.
// The DecompilerAdapter interface contract depends on this type — do not remove.
data class DecompileResult(
    val source:   String,
    val warnings: List<String> = emptyList(),
    val errors:   List<String> = emptyList()
)

// ── Routing outcome (used by DecompilerRegistry + Main.kt) ────────────────────
// The registry wraps adapter results into this sealed class so Main.kt can
// distinguish clean success from a thrown exception without try-catch everywhere.
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

// ── Hive models ───────────────────────────────────────────────────────────────

data class ChunkRequest(
    val jarBase64:  String,
    val classes:    List<String>,
    val chunkIndex: Int,
    val jobId:      String,
    val mode:       String = "cfr"
)

data class ChunkRunResult(
    val sources:   Map<String, String>,
    val warnings:  List<String>,
    val errors:    List<String>,
    val elapsedMs: Long
)
