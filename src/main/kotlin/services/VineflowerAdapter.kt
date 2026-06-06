package org.endless.services

import org.endless.model.DecompileResult
import org.jetbrains.java.decompiler.main.decompiler.BaseDecompiler
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger.Severity
import org.jetbrains.java.decompiler.main.extern.IResultSaver
import java.io.File
import java.nio.file.Files
import java.util.jar.Manifest

class VineflowerAdapter : DecompilerAdapter {

    override val name: String = "vineflower"
    override val supportsJar: Boolean = true

    private val options: Map<String, Any> = mapOf(
        "ind"  to "    ",
        "bsm"  to "1",
        "vac"  to "1",
        "iib"  to "1",
        "ner"  to "1",
        "udv"  to "1",
        "rbr"  to "1",
        "rsy"  to "1",
        "lac"  to "1"
    )

    override fun decompileClass(classBytes: ByteArray, fileName: String): DecompileResult {
        val tempIn  = Files.createTempFile("vf-in-", ".class").toFile()
        val tempOut = Files.createTempDirectory("vf-out-").toFile()
        try {
            tempIn.writeBytes(classBytes)
            return run(listOf(tempIn), tempOut)
        } finally {
            tempIn.delete()
            tempOut.deleteRecursively()
        }
    }

    override fun decompileJar(jarFile: File, outputDir: File): DecompileResult =
        run(listOf(jarFile), outputDir)

    // ── Core logic ────────────────────────────────────────────────────────────

    private fun run(inputs: List<File>, outputDir: File): DecompileResult {
        val source   = StringBuilder()
        val warnings = mutableListOf<String>()
        val errors   = mutableListOf<String>()

        val logger = object : IFernflowerLogger() {
            override fun writeMessage(message: String, severity: Severity) {
                when (severity) {
                    Severity.WARN  -> warnings.add("[Vineflower WARN] $message")
                    Severity.ERROR -> errors.add("[Vineflower ERROR] $message")
                    else           -> { }
                }
            }
            override fun writeMessage(message: String, severity: Severity, t: Throwable?) {
                val detail = if (t != null) "$message — ${t.javaClass.simpleName}: ${t.message}" else message
                when (severity) {
                    Severity.WARN  -> warnings.add("[Vineflower WARN] $detail")
                    Severity.ERROR -> errors.add("[Vineflower ERROR] $detail")
                    else           -> { }
                }
            }
        }

        val saver = object : IResultSaver {

            // ── Called for loose .class files (single-class decompilation) ────
            // FIX: Vineflower 1.11.x added this method for non-archive results.
            // Without it the object is not concrete and compilation fails.
            override fun saveClassFile(
                path: String,
                qualifiedName: String,
                entryName: String,
                content: String?,
                mapping: IntArray?
            ) {
                content?.let { source.append(it).append("\n") }
            }

            // ── Called for entries inside a JAR/archive ────────────────────────
            override fun saveClassEntry(
                path: String,
                archiveName: String,
                qualifiedName: String,
                entryName: String,
                content: String?
            ) {
                content?.let { source.append(it).append("\n") }
            }

            // ── No-ops for everything we don't need ───────────────────────────
            override fun saveFolder(path: String)                                                    {}
            override fun copyFile(source: String, path: String, entryName: String)                  {}
            override fun createArchive(path: String, archiveName: String, manifest: Manifest?)      {}
            override fun saveDirEntry(path: String, archiveName: String, entryName: String)         {}
            override fun copyEntry(source: String, path: String, archiveName: String, entry: String){}
            override fun closeArchive(path: String, archiveName: String)                            {}
        }

        val decompiler = BaseDecompiler(saver, options, logger)
        inputs.forEach { decompiler.addSource(it) }
        decompiler.decompileContext()

        return DecompileResult(source.toString(), warnings, errors)
    }
}
