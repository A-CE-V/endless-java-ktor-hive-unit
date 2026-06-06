package org.endless.services

import org.benf.cfr.reader.api.CfrDriver
import org.benf.cfr.reader.api.OutputSinkFactory
import org.benf.cfr.reader.api.SinkReturns
import org.endless.model.DecompileResult
import java.io.File
import java.io.FileOutputStream

class CfrAdapter : DecompilerAdapter {

    override val name: String = "cfr"
    override val supportsJar: Boolean = true

    override fun decompileClass(classBytes: ByteArray, fileName: String): DecompileResult {
        val tmp = File.createTempFile("cfr-class-", ".class")
        try {
            FileOutputStream(tmp).use { it.write(classBytes) }
            return decompilePath(tmp.absolutePath)
        } finally {
            tmp.delete()
        }
    }

    override fun decompileJar(jarFile: File, outputDir: File): DecompileResult =
        decompilePath(jarFile.absolutePath)

    // ── Core decompile logic ──────────────────────────────────────────────────

    private fun decompilePath(path: String): DecompileResult {
        val source   = StringBuilder()
        val warnings = mutableListOf<String>()
        val errors   = mutableListOf<String>()

        val sink = object : OutputSinkFactory {

            override fun getSupportedSinks(
                sinkType: OutputSinkFactory.SinkType,
                available: Collection<OutputSinkFactory.SinkClass>
            ): List<OutputSinkFactory.SinkClass> = when (sinkType) {
                OutputSinkFactory.SinkType.JAVA ->
                    listOf(OutputSinkFactory.SinkClass.DECOMPILED, OutputSinkFactory.SinkClass.STRING)
                OutputSinkFactory.SinkType.EXCEPTION ->
                    listOf(OutputSinkFactory.SinkClass.EXCEPTION_MESSAGE, OutputSinkFactory.SinkClass.STRING)
                else -> listOf(OutputSinkFactory.SinkClass.STRING)
            }

            // FIX: Kotlin cannot infer T from the SAM lambda alone when the interface
            // uses a raw generic. Explicitly type each Sink's lambda parameter so the
            // compiler knows which erased type to expect, then cast to Sink<T>.
            @Suppress("UNCHECKED_CAST")
            override fun <T> getSink(
                sinkType: OutputSinkFactory.SinkType,
                sinkClass: OutputSinkFactory.SinkClass
            ): OutputSinkFactory.Sink<T> = when {

                // ── Decompiled Java source ────────────────────────────────────
                sinkType == OutputSinkFactory.SinkType.JAVA
                        && sinkClass == OutputSinkFactory.SinkClass.DECOMPILED ->
                    OutputSinkFactory.Sink<SinkReturns.Decompiled> { x ->
                        if (x.packageName.isNotBlank())
                            source.append("/* Package: ${x.packageName} | Class: ${x.className} */\n")
                        source.append(x.java).append("\n")
                    } as OutputSinkFactory.Sink<T>

                // ── Informational / warning strings ──────────────────────────
                sinkType == OutputSinkFactory.SinkType.JAVA
                        && sinkClass == OutputSinkFactory.SinkClass.STRING ->
                    OutputSinkFactory.Sink<String> { msg ->
                        if (msg.isNotBlank()) warnings.add("[CFR] $msg")
                    } as OutputSinkFactory.Sink<T>

                // ── Exceptions reported by CFR ────────────────────────────────
                sinkType == OutputSinkFactory.SinkType.EXCEPTION
                        && sinkClass == OutputSinkFactory.SinkClass.EXCEPTION_MESSAGE ->
                    OutputSinkFactory.Sink<SinkReturns.ExceptionMessage> { x ->
                        errors.add("[CFR-EXCEPTION] ${x.path}: ${x.message}")
                    } as OutputSinkFactory.Sink<T>

                sinkType == OutputSinkFactory.SinkType.EXCEPTION ->
                    OutputSinkFactory.Sink<String> { msg ->
                        if (msg.isNotBlank()) errors.add("[CFR-ERROR] $msg")
                    } as OutputSinkFactory.Sink<T>

                else -> OutputSinkFactory.Sink<Any> { } as OutputSinkFactory.Sink<T>
            }
        }

        val options = mapOf(
            "silent"      to "true",
            "showversion" to "false"
        )

        CfrDriver.Builder()
            .withOutputSink(sink)
            .withOptions(options)
            .build()
            .analyse(listOf(path))

        return DecompileResult(source.toString(), warnings, errors)
    }
}
