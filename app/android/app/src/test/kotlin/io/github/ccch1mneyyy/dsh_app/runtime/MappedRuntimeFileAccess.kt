package io.github.ccch1mneyyy.dsh_app.runtime

import java.io.File
import java.io.InputStream
import java.nio.file.Files

/** Separate app and privileged views: accidental plain access cannot reach the fixture. */
internal class MappedRuntimeFileAccess(private val logical: File, private val physical: File) : RuntimeFileAccess {
    var inputOpens = 0
        private set

    override fun file(file: File): File = if (file == logical) physical
        else if (file.path.startsWith(logical.path + File.separator)) File(physical, file.relativeTo(logical).path)
        else file
    override fun isSymlink(file: File): Boolean = Files.isSymbolicLink(file(file).toPath())
    override fun delete(file: File, recursive: Boolean): Boolean =
        if (recursive) file(file).deleteRecursively() else file(file).delete()
    override fun readText(file: File): String = file(file).readText()
    override fun writeText(file: File, content: String) = file(file).writeText(content)
    override fun openInput(file: File): InputStream {
        inputOpens++
        return file(file).inputStream()
    }
    override fun close() {}
}
