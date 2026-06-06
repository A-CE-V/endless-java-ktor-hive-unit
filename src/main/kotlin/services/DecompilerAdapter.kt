package org.endless.services

import org.endless.model.DecompileResult
import java.io.File

/**
 * Contract every decompiler adapter must satisfy.
 *
 * Adapters are expected to:
 *  - Manage their own temp-file lifecycle (always delete on exit).
 *  - Return [DecompileResult] with whatever warnings/errors they collected;
 *    never throw for partial failures — put them in [DecompileResult.errors].
 *  - Throw only for truly unrecoverable situations (e.g. I/O setup failure).
 */
interface DecompilerAdapter {

    /** Human-readable name shown in API responses. */
    val name: String

    /** Decompile a single .class file from raw bytes. */
    fun decompileClass(classBytes: ByteArray, fileName: String): DecompileResult

    /** Returns true when this adapter can handle whole JARs. */
    val supportsJar: Boolean get() = false

    /**
     * Decompile all classes inside [jarFile], writing .java files under [outputDir].
     * Only called when [supportsJar] is true.
     */
    fun decompileJar(jarFile: File, outputDir: File): DecompileResult =
        throw UnsupportedOperationException("$name does not support JAR decompilation")
}
