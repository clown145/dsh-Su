package io.github.ccch1mneyyy.dsh_app.runtime

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption

/** An upgrade must never fall back to rebuilding an existing user system. */
internal class RuntimeUpgradeBlocked(reason: String) : IllegalStateException("runtime_upgrade_blocked:$reason")

internal val ENGINE_PATHS = listOf(
    "opt/dsh", "opt/node", "opt/dsh-go", "etc/dsh-app",
    "etc/profile.d/dsh-app.sh", "usr/local/lib/node_modules/pnpm",
    "usr/local/bin/dsh", "usr/local/bin/node", "usr/local/bin/npm", "usr/local/bin/npx",
    "usr/local/bin/corepack", "usr/local/bin/pnpm", "usr/local/bin/pnpx", "usr/local/bin/pn", "usr/local/bin/pnx",
)

internal data class EngineUpgradeSpec(val baseId: String, val ubuntuVersion: String, val paths: List<String>) {
    fun validate() {
        if (!baseId.matches(Regex("[a-f0-9]{64}")) || ubuntuVersion.isBlank() || paths != ENGINE_PATHS) {
            throw RuntimeUpgradeBlocked("manifest")
        }
    }
}

/**
 * A forward-only, durable transaction. Its new payload and old engine are outside
 * staging, which install may discard. User files never enter the transaction.
 * A killed install resumes before staging is touched, even across APK versions.
 */
internal class EngineUpgrade(
    private val base: File,
    private val sync: () -> Unit,
    private val syncDirectory: (File) -> Unit = { dir ->
        FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
    },
    private val afterStep: (String) -> Unit = {},
    private val files: RuntimeFileAccess? = null,
    private val mounts: MountGuard? = null,
) {
    private val root = File(base, "rootfs")
    private val pending = File(base, "engine.upgrade")
    private val preparing = File(base, "engine.prepare")
    private val done = File(base, "engine.done")
    private val marker = File(base, "rootfs.version")

    fun isPending(): Boolean = present(pending)

    fun activate(version: String, spec: EngineUpgradeSpec) {
        spec.validate()
        validateVersion(version)
        recover()
        val staging = File(base, "staging")
        mounts?.assertDeletable(root)
        // Validate every path before the first mutation of the active tree.
        for (path in spec.paths) {
            safePath(root, path)
            val source = safePath(staging, path)
            if (!present(source)) throw RuntimeUpgradeBlocked("payload")
        }
        remove(preparing)
        check(access(preparing).mkdirs()) { "could not prepare engine upgrade" }
        for (path in spec.paths) {
            val target = safePath(File(preparing, "new"), path)
            ensureParent(target)
            move(safePath(staging, path), target)
        }
        writeSynced(File(preparing, "version"), version)
        writeSynced(File(preparing, "original"), spec.paths.filter { present(File(root, it)) }.joinToString("\n"))
        sync()
        afterStep("prepared")
        move(preparing, pending)
        afterStep("journal")
        recover()
    }

    fun recover() {
        // Committed transactions are retired atomically before their cleanup.
        remove(done)
        if (!present(pending)) {
            remove(preparing)
            return
        }
        if (!directory(pending)) throw RuntimeUpgradeBlocked("transaction")
        val version = try { readText(File(pending, "version")) }
            catch (_: Exception) { throw RuntimeUpgradeBlocked("transaction") }
        validateVersion(version)
        val original = try { readText(File(pending, "original")).reader().readLines() }
            catch (_: Exception) { throw RuntimeUpgradeBlocked("transaction") }
        if (original.distinct() != original || original.any { it !in ENGINE_PATHS }) {
            throw RuntimeUpgradeBlocked("transaction")
        }
        mounts?.assertDeletable(root)
        for (path in ENGINE_PATHS) {
            safePath(root, path)
            safePath(File(pending, "new"), path)
            safePath(File(pending, "old"), path)
        }
        if (deleteIfPresent(marker)) syncDir(base)
        afterStep("retired")
        for (path in ENGINE_PATHS) {
            val target = safePath(root, path)
            val next = safePath(File(pending, "new"), path)
            val old = safePath(File(pending, "old"), path)
            if (present(next)) {
                if (present(target) && !present(old)) {
                    ensureParent(old)
                    move(target, old)
                    afterStep("backup:$path")
                }
                // With an old copy already present, target can only be the new
                // entry from an interrupted rename. Do not delete unknown data.
                if (present(target)) throw RuntimeUpgradeBlocked("transaction")
                ensureParent(target)
                move(next, target)
                afterStep("activate:$path")
            } else if (!present(target) || (path in original && !present(old))) {
                throw RuntimeUpgradeBlocked("transaction")
            }
        }
        sync()
        afterStep("data")
        val temporary = File(base, "rootfs.version.next")
        deleteIfPresent(temporary)
        writeSynced(temporary, version)
        if (files == null) Files.move(temporary.toPath(), marker.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        else check(access(temporary).renameTo(access(marker))) { "could not publish runtime version" }
        syncDir(base)
        afterStep("marker")
        move(pending, done)
        afterStep("committed")
        remove(done)
    }

    private fun ensureParent(file: File) {
        val missing = mutableListOf<File>()
        var directory = file.parentFile!!
        while (!present(directory)) {
            missing += directory
            directory = directory.parentFile ?: throw RuntimeUpgradeBlocked("layout")
        }
        for (next in missing.asReversed()) {
            check(access(next).mkdir()) { "could not create engine transaction directory" }
            syncDir(next)
            syncDir(next.parentFile!!)
        }
    }

    private fun move(from: File, to: File) {
        check(access(from).renameTo(access(to))) { "could not move engine upgrade entry" }
        // Persist the destination before the source removal: a power cut must
        // not make an entry unreachable from both sides of a cross-dir rename.
        syncDir(to.parentFile!!)
        if (from.parentFile != to.parentFile) syncDir(from.parentFile!!)
    }

    private fun validateVersion(version: String) {
        if (!version.matches(Regex("[A-Za-z0-9._-]+"))) throw RuntimeUpgradeBlocked("transaction")
    }

    private fun writeSynced(file: File, text: String) {
        if (files == null) {
            FileOutputStream(file).use { out -> out.write(text.toByteArray(Charsets.UTF_8)); out.fd.sync() }
        } else {
            files.writeText(file, text)
            sync()
        }
    }

    private fun access(file: File): File = files?.file(file) ?: file
    private fun symlink(file: File): Boolean = files?.isSymlink(file) ?: Files.isSymbolicLink(file.toPath())
    private fun present(file: File): Boolean = access(file).exists() || symlink(file)
    private fun directory(file: File): Boolean = !symlink(file) && access(file).isDirectory
    private fun readText(file: File): String = files?.readText(file) ?: file.readText()

    // Root-owned directories cannot be opened by the app for fsync. The existing
    // privileged filesystem barrier persists their renames with the same ordering.
    private fun syncDir(directory: File) {
        if (files == null) syncDirectory(directory) else sync()
    }

    private fun deleteIfPresent(file: File): Boolean {
        if (!present(file)) return false
        check(files?.delete(file, recursive = false) ?: file.delete()) { "could not delete engine transaction entry" }
        return true
    }

    private fun safePath(tree: File, relative: String): File {
        // Managed leaf symlinks are moved as links, never followed. A user's
        // symlinked parent must not redirect an upgrade outside the system.
        var parent: File? = File(tree, relative).parentFile
        while (parent != null) {
            if (symlink(parent)) throw RuntimeUpgradeBlocked("symlink")
            if (present(parent) && !directory(parent)) {
                throw RuntimeUpgradeBlocked("layout")
            }
            if (parent == tree) break
            parent = parent.parentFile
        }
        return File(tree, relative)
    }

    private fun remove(file: File) {
        if (!present(file)) return
        mounts?.assertDeletable(file, allowWithoutHostProof = true)
        if (files != null) {
            // One root-side deletion avoids a shell round trip for every bundled
            // package file. The mount guard covers the complete transaction tree.
            check(files.delete(file, recursive = !symlink(file)) && !present(file)) {
                "could not remove engine transaction"
            }
        } else removeEntries(file)
    }

    private fun removeEntries(file: File) {
        if (directory(file)) {
            for (child in access(file).list() ?: throw IllegalStateException("could not list engine transaction")) {
                removeEntries(File(file, child))
            }
        }
        deleteIfPresent(file)
    }
}
