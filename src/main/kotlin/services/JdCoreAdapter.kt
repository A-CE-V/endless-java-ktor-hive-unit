package org.endless.services

import org.endless.model.DecompileResult
import org.jd.core.v1.ClassFileToJavaSourceDecompiler
import org.jd.core.v1.api.loader.Loader
import org.jd.core.v1.api.printer.Printer

/**
 * Adapter for io.github.nbauma109:jd-core:1.3.3
 *
 * NOTE: This fork does NOT have LoaderException — the Loader contract
 * simply declares `load()` as `throws Exception`, so we throw a plain
 * IllegalArgumentException when a requested type is not available.
 */
class JdCoreAdapter : DecompilerAdapter {

    override val name: String = "jd-core"
    override val supportsJar: Boolean = false

    private val decompiler = ClassFileToJavaSourceDecompiler()

    override fun decompileClass(classBytes: ByteArray, fileName: String): DecompileResult {
        val warnings = mutableListOf<String>()
        val errors   = mutableListOf<String>()

        val internalName = fileName
            .removeSuffix(".class")
            .replace('\\', '/')
            .trim('/')
            .ifBlank { "Unknown" }

        // FIX: nbauma109 fork has no LoaderException — Loader.load() is declared
        // as `throws Exception`, so any Exception signals "not found" to JD-Core.
        val loader = object : Loader {
            override fun load(internalTypeName: String): ByteArray {
                if (internalTypeName == internalName ||
                    internalTypeName == internalName.replace('/', '.')) {
                    return classBytes
                }
                throw IllegalArgumentException("Class not available: $internalTypeName")
            }

            override fun canLoad(internalTypeName: String): Boolean =
                internalTypeName == internalName ||
                internalTypeName == internalName.replace('/', '.')
        }

        val printer = StringBuilderPrinter()

        return try {
            decompiler.decompile(loader, printer, internalName)
            DecompileResult(printer.result, warnings, errors)
        } catch (ex: Exception) {
            errors.add("[JD-Core] ${ex.javaClass.simpleName}: ${ex.message}")
            DecompileResult(printer.result, warnings, errors)
        }
    }

    // ── Printer ───────────────────────────────────────────────────────────────

    private class StringBuilderPrinter : Printer {

        private val sb      = StringBuilder()
        private var indent  = 0
        private var newLine = true

        val result: String get() = sb.toString()

        private fun write(text: String) {
            if (newLine && text.isNotEmpty() && text != "\n") {
                sb.append("    ".repeat(indent))
                newLine = false
            }
            sb.append(text)
        }

        override fun start(maxLineNumber: Int, majorVersion: Int, minorVersion: Int) {}
        override fun end() {}

        override fun printText(text: String?)                                            { write(text ?: "") }
        override fun printNumericConstant(constant: String?)                             { write(constant ?: "") }
        override fun printStringConstant(constant: String?, ownerInternalName: String?) { write(constant ?: "") }
        override fun printKeyword(keyword: String?)                                      { write(keyword ?: "") }

        override fun printDeclaration(
            type: Int, internalTypeName: String?, name: String?, descriptor: String?
        ) { write(name ?: "") }

        override fun printReference(
            type: Int, internalTypeName: String?,
            name: String?, descriptor: String?, ownerInternalName: String?
        ) { write(name ?: "") }

        override fun indent()   { indent++ }
        override fun unindent() { if (indent > 0) indent-- }

        override fun startLine(lineNumber: Int) {
            if (sb.isNotEmpty()) sb.append("\n")
            newLine = true
        }

        override fun endLine()              {}
        override fun extraLine(count: Int)  { repeat(count) { sb.append("\n") } }
        override fun startMarker(type: Int) {}
        override fun endMarker(type: Int)   {}
    }
}
