package org.endless.services

import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import org.endless.model.DecompileResult
import java.io.File
import java.nio.file.Files

class JadxAdapter : DecompilerAdapter {

    override val name: String = "jadx"
    override val supportsJar: Boolean = true

    override fun decompileClass(classBytes: ByteArray, fileName: String): DecompileResult {
        val tmp = Files.createTempFile("jadx-in-", ".class").toFile()
        try {
            tmp.writeBytes(classBytes)
            return runJadx(inputs = listOf(tmp))
        } finally {
            tmp.delete()
        }
    }

    override fun decompileJar(jarFile: File, outputDir: File): DecompileResult =
        runJadx(inputs = listOf(jarFile), outDir = outputDir)

    // ── Core logic ────────────────────────────────────────────────────────────

    private fun runJadx(inputs: List<File>, outDir: File? = null): DecompileResult {
        val warnings = mutableListOf<String>()
        val errors   = mutableListOf<String>()
        val source   = StringBuilder()

        val args = JadxArgs().apply {
            inputFiles.addAll(inputs)
            if (outDir != null) this.outDir = outDir
            isSkipResources   = true
            threadsCount      = 1
            isDeobfuscationOn = false
        }

        JadxDecompiler(args).use { jadx ->
            jadx.load()

            // FIX: processingError is an internal ClassNode field not exposed by
            // the public JavaClass API in jadx 1.5.x. Instead we detect failures
            // by checking for JADX's inline error comment that it writes directly
            // into the decompiled source when a class or method cannot be decompiled.
            for (cls in jadx.classes) {
                val code = cls.code
                source.append(code).append("\n")

                if (JADX_ERROR_MARKER in code) {
                    // Extract every comment line that contains the error marker
                    code.lines()
                        .filter { JADX_ERROR_MARKER in it }
                        .forEach { line ->
                            warnings.add("[JADX][${cls.fullName}] ${line.trim()}")
                        }
                }
            }

            if (outDir != null) jadx.save()
        }

        return DecompileResult(
            source   = source.toString(),
            warnings = warnings,
            errors   = errors
        )
    }

    companion object {
        // JADX writes this literal string into the source when decompilation fails
        private const val JADX_ERROR_MARKER = "// jadx-error:"
    }
}
