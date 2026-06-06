package org.endless.services

import com.strobel.assembler.metadata.ArrayTypeLoader
import com.strobel.assembler.metadata.CompositeTypeLoader
import com.strobel.assembler.metadata.ITypeLoader
import com.strobel.decompiler.Decompiler
import com.strobel.decompiler.DecompilerSettings
import com.strobel.decompiler.PlainTextOutput
import org.endless.model.DecompileResult
import java.io.StringWriter

class ProcyonAdapter : DecompilerAdapter {

    override val name: String = "procyon"
    override val supportsJar: Boolean = false   // Procyon's JAR support is partial; use JADX/CFR for JARs

    override fun decompileClass(classBytes: ByteArray, fileName: String): DecompileResult {
        val warnings = mutableListOf<String>()
        val errors   = mutableListOf<String>()

        // Derive internal class name: strip .class suffix, normalise separators
        val internalName = fileName
            .removeSuffix(".class")
            .replace('\\', '/')
            .trim('/')
            .ifBlank { "Unknown" }

        val writer = StringWriter()

        // FIX: Build settings first, capture the existing loader in a local val,
        // then mutate — avoids "unresolved reference: settings" inside apply {}
        val settings = DecompilerSettings.javaDefaults()
        val fallbackLoader: ITypeLoader = settings.typeLoader ?: ITypeLoader { _, _ -> false }
        settings.typeLoader = CompositeTypeLoader(ArrayTypeLoader(classBytes), fallbackLoader)
        settings.isUnicodeOutputEnabled = true

        return try {
            Decompiler.decompile(internalName, PlainTextOutput(writer), settings)
            DecompileResult(writer.toString(), warnings, errors)
        } catch (ex: Exception) {
            errors.add("[Procyon] ${ex.javaClass.simpleName}: ${ex.message}")
            // Return whatever partial output was written before the exception
            DecompileResult(writer.toString(), warnings, errors)
        }
    }
}
