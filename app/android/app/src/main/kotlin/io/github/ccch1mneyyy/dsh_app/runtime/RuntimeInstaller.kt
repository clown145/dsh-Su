package io.github.ccch1mneyyy.dsh_app.runtime

import android.content.Context
import android.content.res.AssetManager
import com.topjohnwu.superuser.io.SuFile
import com.topjohnwu.superuser.io.SuFileInputStream
import com.topjohnwu.superuser.io.SuFileOutputStream
import io.github.ccch1mneyyy.dsh_app.R
import io.github.ccch1mneyyy.dsh_app.i18n.AppStrings
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.DigestInputStream
import java.security.MessageDigest
import org.json.JSONObject

/**
 * Unpacks the bundled rootfs into app-private storage.
 *
 * Layout under filesDir/runtime:
 *   rootfs/            the active Linux root (proot -r)
 *   rootfs.version     runtimeVersion of the active rootfs
 *   staging/           in-progress extraction, swapped in atomically when done
 *   home.carry/        the user's /root while an upgrade swaps rootfs (see [activateRootfs])
 *   keep.trash/        fresh copies a restore of kept files replaced (see [restoreKept])
 * and next to it, for a reset (see [retireRuntime]):
 *   runtime.reset/     the runtime being deleted
 *   runtime-keep/      what the reset keeps, mirrored under rootfs paths, until the next install
 *
 * First install creates the system; upgrades replace only app-owned engine paths.
 * The user's Linux system, package databases and home are never rebuilt implicitly.
 */
class RuntimeInstaller(
    private val context: Context,
    private val manifest: RuntimeManifest,
    private val onLog: (String) -> Unit,
    private val sync: () -> Unit = ::syncFilesystems,
    /** Root builds refuse deletes that cannot be proven mount-free (notes/root-backend-interface.md). */
    private val rootRuntime: Boolean = false,
) {
    val baseDir = File(context.filesDir, "runtime")
    val rootfs = File(baseDir, "rootfs")
    private val versionFile = File(baseDir, "rootfs.version")
    private val staging = File(baseDir, "staging")
    private val retiredDir = File(context.filesDir, "runtime.reset")
    private val keepDir = File(context.filesDir, "runtime-keep")

    private val engineUpgrade = EngineUpgrade(baseDir, sync)

    fun installedVersion(): String? =
        if (!engineUpgrade.isPending() && rootfs.isDirectory && versionFile.isFile) versionFile.readText().trim().ifEmpty { null } else null

    fun isCurrent(): Boolean = installedVersion() == manifest.runtimeVersion

    /**
     * Extracts the bundled rootfs; [onProgress] gets (compressedBytesRead, totalCompressedBytes).
     * Fails with [KeepFailed] when what a reset kept cannot go back; [dropKept] then gives up
     * whatever cannot and installs anyway.
     */
    fun install(dropKept: Boolean = false, onProgress: (Long, Long) -> Unit) {
        val files = if (rootRuntime) openRootFileAccess(context) else null
        try {
            // A reset killed halfway finishes first, so what it keeps is in runtime-keep for the restore.
            val mounts = MountGuard.read(strict = rootRuntime, files = files)
            settleReset(retiredDir, keepDir, dropKept, mounts, files)
            baseDir.mkdirs()
            // Resume the durable engine transaction before clearing a previous extraction.
            EngineUpgrade(baseDir, sync, files = files, mounts = mounts).recover()
            // staging is a fresh tree that was never a chroot target: it deletes without host-view proof.
            staging.deleteRecursivelySafely(mounts, allowWithoutHostProof = true, files = files)
            checkFreeSpace()
            staging.mkdirs()

            val assetPath = "${RuntimeManifest.ASSET_DIR}/${manifest.rootfsAsset}"
            val assetBytes = assetLength(assetPath)
            val total = assetBytes.takeIf { it > 0 } ?: manifest.rootfsBytes
            var lastReported = 0L
            val progress: (Long) -> Unit = { read ->
                if (read - lastReported >= 512 * 1024 || read == total) {
                    lastReported = read
                    onProgress(read, total)
                }
            }
            timed("verify") {
                // Parallel readers revisit compressed bytes; hash the asset once, in order.
                val digest = MessageDigest.getInstance("SHA-256")
                DigestInputStream(context.assets.open(assetPath, AssetManager.ACCESS_STREAMING), digest).use { input ->
                    val buffer = ByteArray(1 shl 16)
                    while (input.read(buffer) >= 0) {}
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                val expected = manifest.rootfsSha256
                if (expected != null && !expected.equals(actual, ignoreCase = true)) {
                    staging.deleteRecursivelySafely(mounts, allowWithoutHostProof = true, files = files)
                    throw IllegalStateException(AppStrings.get(context, R.string.service_package_checksum_failed))
                }
            }
            timed("extract") {
                val extractor = TarExtractor(
                    staging,
                    applyOwner = { file, uid, gid ->
                        // Root installs must land with the archive's numeric ownership; a failed
                        // chown aborts the install so a bad candidate is never activated. Plain
                        // installs have no [files] handle and keep the legacy no-op behavior.
                        if (files != null && !files.chown(file, uid, gid)) {
                            throw IllegalStateException("could not set numeric ownership on ${file.path}")
                        }
                    },
                    onProgress = progress,
                )
                if (manifest.rootfsAsset.endsWith(".xz") && assetBytes > 0) {
                    val decoded = ParallelXZInputStream.open(
                        { AssetSeekableInputStream(context.assets.openFd(assetPath)) }, progress,
                    )
                    extractor.extractDecoded(decoded)
                } else {
                    context.assets.open(assetPath, AssetManager.ACCESS_STREAMING).use { raw ->
                        extractor.extract(raw, manifest.rootfsAsset)
                    }
                }
            }
            onProgress(total, total)

            val groupNames = appendAndroidGroupNames(staging,
                runCatching { File("/proc/self/task/${android.os.Process.myTid()}/status").readText() }.getOrNull())
            onLog("[dsh-app] install group names: " +
                (groupNames.skipped?.let { "skipped ($it)" } ?: "added ${groupNames.added}"))
            timed("activate") {
                activateRuntimeUpdate(baseDir, manifest.runtimeVersion, manifest.abi, manifest.raw,
                    keepDir, dropKept, sync = { timed("sync") { sync() } }, onLog = onLog,
                    mounts = mounts, rootRuntime = rootRuntime, files = files)
            }
        } finally {
            files?.close()
        }
    }

    private inline fun <T> timed(phase: String, block: () -> T): T {
        val started = System.nanoTime()
        try {
            return block()
        } finally {
            onLog("[dsh-app] install $phase: ${(System.nanoTime() - started) / 1_000_000} ms")
        }
    }

    private fun checkFreeSpace() {
        val need = (manifest.uncompressedBytes.takeIf { it > 0 } ?: 600L * 1024 * 1024) * 115 / 100
        val free = context.filesDir.usableSpace
        if (free < need) {
            val mb = { b: Long -> b / (1024 * 1024) }
            throw IllegalStateException(AppStrings.get(context, R.string.service_storage_insufficient, mb(need), mb(free)))
        }
    }

    /** Deletes the runtime; [keep] (paths under the rootfs) waits in runtime-keep for the next install. */
    fun reset(keep: List<String>) {
        val files = if (rootRuntime) openRootFileAccess(context) else null
        try {
            retireRuntime(baseDir, retiredDir, keepDir, keep, MountGuard.read(strict = rootRuntime, files = files), files)
        } finally {
            files?.close()
        }
    }

    private fun assetLength(path: String): Long = try {
        context.assets.openFd(path).use { it.length }
    } catch (_: Exception) {
        -1L // compressed asset; fall back to the manifest
    }
}

/** The production activation route, also exercised without an Android Context in JVM tests. */
internal fun activateRuntimeUpdate(
    base: File,
    version: String,
    abi: String,
    raw: JSONObject,
    keep: File? = null,
    dropKept: Boolean = false,
    sync: () -> Unit,
    onLog: (String) -> Unit = {},
    mounts: MountGuard? = null,
    rootRuntime: Boolean = false,
    files: RuntimeFileAccess? = null,
) {
    val rootfs = File(base, "rootfs")
    if (File(base, "home.carry").present(files) || File(base, "rootfs.old").present(files)) {
        throw RuntimeUpgradeBlocked("legacy_interrupted")
    }
    if (rootfs.present(files)) {
        val spec = checkEngineCompatibility(base, raw, version, abi, files)
        onLog("[dsh-app] upgrading engine; preserving installed Linux system and user tools")
        EngineUpgrade(base, sync, files = files, mounts = mounts).activate(version, spec)
        File(base, "staging").deleteRecursivelySafely(mounts, allowWithoutHostProof = true, files = files)
    } else {
        // Only first install (or an explicit user-confirmed reset) may activate
        // an entire rootfs. Never use this as an incompatible-upgrade fallback.
        activateRootfs(base, version, keep, dropKept, sync = sync, mounts = mounts, rootRuntime = rootRuntime, files = files)
    }
}

internal interface RuntimeFileAccess : AutoCloseable {
    fun file(file: File): File
    fun isSymlink(file: File): Boolean
    fun delete(file: File, recursive: Boolean): Boolean
    fun readText(file: File): String
    fun openInput(file: File): java.io.InputStream = file.inputStream()

    /** Writes UTF-8 content, creating or truncating [file]. */
    fun writeText(file: File, content: String) {
        file.writeText(content)
    }

    /**
     * Creates [file] with [content] (UTF-8, mode 0600 where supported) in one exclusive
     * step, returning false when it already exists — the app-side counterpart of DSH's
     * `fs.writeFileSync(lock, …, {flag: "wx", mode: 0o600})`.
     */
    fun createExclusive(file: File, content: String): Boolean {
        val options = java.util.LinkedHashSet<java.nio.file.StandardOpenOption>()
        options.add(java.nio.file.StandardOpenOption.CREATE_NEW)
        options.add(java.nio.file.StandardOpenOption.WRITE)
        val bytes = java.nio.ByteBuffer.wrap(content.toByteArray(Charsets.UTF_8))
        return try {
            try {
                java.nio.file.Files.newByteChannel(
                    file.toPath(), options,
                    java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                        java.util.HashSet(
                            listOf(
                                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                            ),
                        ),
                    ),
                ).use { it.write(bytes.duplicate()) }
            } catch (_: UnsupportedOperationException) {
                // Non-POSIX file store: create exclusively without the mode hint.
                java.nio.file.Files.newByteChannel(file.toPath(), options).use { it.write(bytes.duplicate()) }
            }
            true
        } catch (_: java.nio.file.FileAlreadyExistsException) {
            false
        }
    }

    /**
     * Sets numeric ownership through this backend. The default is false: plain
     * (proot) installs cannot chown and keep the extracted state, so legacy
     * behavior is an explicit no-op rather than a silent failure.
     */
    fun chown(file: File, uid: Long, gid: Long): Boolean = false

    /**
     * Prepares [file] as a directory the app itself can write into (mode 0755,
     * owned by [uid]/[gid]) through this backend. The plain backend already owns
     * everything it creates, so the default is a plain mkdir that also accepts a
     * pre-existing directory; a false return means the app's overlay writers will
     * visibly degrade (title-only search, no sign-in, no voice, no phone tools).
     */
    fun ensureAppOwnedDir(file: File, uid: Long, gid: Long): Boolean {
        return file.isDirectory || file.mkdirs()
    }
}

/** Plain-file access for proot runtimes and tests: the identity mapping. */
internal object PlainRuntimeFileAccess : RuntimeFileAccess {
    override fun file(file: File): File = file
    override fun isSymlink(file: File): Boolean = java.nio.file.Files.isSymbolicLink(file.toPath())
    override fun delete(file: File, recursive: Boolean): Boolean = if (recursive) file.deleteRecursively() else file.delete()
    override fun readText(file: File): String = file.readText()
    override fun close() {}
}

internal fun openRootFileAccess(context: Context): RuntimeFileAccess {
    val selection = RootShellSelector.select(context) ?: throw IllegalStateException("no root shell")
    return object : RuntimeFileAccess {
        override fun file(file: File): File = SuFile(file.path).apply { setShell(selection.shell) }

        override fun isSymlink(file: File): Boolean = (this.file(file) as SuFile).isSymlink()

        override fun delete(file: File, recursive: Boolean): Boolean {
            val rootFile = this.file(file) as SuFile
            return if (recursive) rootFile.deleteRecursive() else rootFile.delete()
        }

        override fun readText(file: File): String =
            SuFileInputStream.open(this.file(file)).bufferedReader().use { it.readText() }

        override fun openInput(file: File): java.io.InputStream = SuFileInputStream.open(this.file(file))

        override fun writeText(file: File, content: String) {
            SuFileOutputStream.open(this.file(file)).use { it.write(content.toByteArray(Charsets.UTF_8)) }
        }

        override fun createExclusive(file: File, content: String): Boolean {
            val rootFile = this.file(file) as SuFile
            // Real O_EXCL is not reachable through SuFile (each call is its own shell job);
            // noclobber redirection refuses an existing target in one job and umask 077
            // lands mode 0600. The record content is pid digits supplied by ProfileLock.
            require(content.isNotBlank() && content.all { it.isDigit() || it == '\n' }) { "lock record must be pid digits" }
            val path = "'" + rootFile.path.replace("'", "'\\''") + "'"
            val result = selection.shell.newJob()
                .add("( set -o noclobber; umask 077; printf '%s\\n' ${content.trim()} > $path )")
                .exec()
            return result.code == 0
        }

        override fun chown(file: File, uid: Long, gid: Long): Boolean {
            if (uid < 0 || uid > Int.MAX_VALUE || gid < 0 || gid > Int.MAX_VALUE) return false
            val quoted = "'" + file.path.replace("'", "'\\''") + "'"
            return selection.shell.newJob().add("chown -h '$uid:$gid' $quoted").exec().code == 0
        }

        override fun ensureAppOwnedDir(file: File, uid: Long, gid: Long): Boolean {
            if (uid < 0 || uid > Int.MAX_VALUE || gid < 0 || gid > Int.MAX_VALUE) return false
            // A symlink here or anywhere up the chain would make the shell follow
            // it: refuse instead of chowning through a link to a tree-external path.
            if (dirPathUnsafeForOwnership(file)) return false
            val quoted = "'" + file.path.replace("'", "'\\''") + "'"
            return selection.shell.newJob()
                .add("mkdir -p $quoted && chown '$uid:$gid' $quoted && chmod 0755 $quoted")
                .exec()
                .code == 0
        }

        override fun close() = selection.close()
    }
}

/**
 * True when [file] itself or any of its ancestors is a symlink: ownership work
 * through such a chain would follow the link and touch a resource outside the
 * runtime tree, so callers must refuse instead.
 */
internal fun dirPathUnsafeForOwnership(file: File): Boolean {
    var current: File = file
    while (true) {
        if (Files.isSymbolicLink(current.toPath())) return true
        current = current.parentFile ?: return false
    }
}

/** The app's numeric uid for ownership transfers; -1 when nothing resolves. */
internal fun appUid(): Int = try {
    android.os.Process.myUid()
} catch (_: Throwable) {
    procIdField("Uid")
}

/**
 * The app's numeric gid, read independently of the uid (they are not assumed
 * equal); -1 when nothing resolves.
 */
internal fun appGid(): Int = try {
    android.system.Os.getgid()
} catch (_: Throwable) {
    procIdField("Gid")
}

private fun procIdField(field: String): Int = try {
    java.io.File("/proc/self/status").readLines()
        .firstOrNull { it.startsWith(field) }
        ?.trim()?.split(Regex("\\s+"))?.getOrNull(1)?.toInt() ?: -1
} catch (_: Throwable) {
    -1
}

private fun File.asRuntimeFile(files: RuntimeFileAccess?): File = files?.file(this) ?: this

private fun File.present(files: RuntimeFileAccess? = null): Boolean =
    asRuntimeFile(files).exists() || isSymlink(files)

private fun File.isSymlink(files: RuntimeFileAccess?): Boolean =
    if (files == null) isSymlink() else files.isSymlink(this)

private fun File.renameTo(target: File, files: RuntimeFileAccess?): Boolean {
    if (files != null && target.present(files)) return false
    return asRuntimeFile(files).renameTo(target.asRuntimeFile(files))
}

private fun File.mkdirs(files: RuntimeFileAccess?): Boolean = asRuntimeFile(files).mkdirs()

/**
 * Swaps `staging` in as `rootfs` and moves the user's /root over. The home waits in `home.carry`,
 * never in `staging` (which every install wipes first), so an upgrade killed at any step loses
 * nothing: the next install picks up from what is on disk.
 */
internal fun activateRootfs(
    base: File,
    version: String,
    keep: File? = null,
    dropKept: Boolean = false,
    sync: () -> Unit,
    syncDirectory: (File) -> Unit = ::syncDirectory,
    mounts: MountGuard? = null,
    rootRuntime: Boolean = false,
    files: RuntimeFileAccess? = null,
) {
    val rootfs = File(base, "rootfs")
    val staging = File(base, "staging")
    val retired = File(base, "rootfs.old")
    val carry = File(base, "home.carry")
    val home = File(rootfs, "root")
    val versionFile = File(base, "rootfs.version")
    if (rootfs.present(files)) mounts?.assertDeletable(rootfs)
    if (retired.present(files)) mounts?.assertDeletable(retired)
    // A retry may install the same version. Retire its ready marker before replacing any tree.
    if (Files.deleteIfExists(versionFile.toPath())) syncDirectory(base)
    // /opt is replaced below. Remember old direct use with the carried home so
    // saved physical cwd paths remain readable after upgrading in PRoot mode.
    if (!rootRuntime) {
        DirectLaunch.preservePhysicalAlias(rootfs, if (carry.exists()) carry else home, retired)
    }
    if (!carry.present(files) && home.asRuntimeFile(files).isDirectory && !home.renameTo(carry, files)) {
        throw IllegalStateException("could not keep the user's home")
    }
    retired.deleteRecursivelySafely(mounts, files = files)
    if (rootfs.present(files)) mounts?.assertDeletable(rootfs)
    if (rootfs.present(files) && !rootfs.renameTo(retired, files)) rootfs.deleteRecursivelySafely(mounts, files = files)
    if (!staging.renameTo(rootfs, files)) throw IllegalStateException("could not activate rootfs")
    if (carry.present(files)) {
        // The fresh home out of staging was never a chroot target: it deletes without host-view proof.
        home.deleteRecursivelySafely(mounts, allowWithoutHostProof = true, files = files)
        if (!carry.renameTo(home, files)) throw IllegalStateException("could not restore the user's home")
    }
    listOf("root", "root/workspace", "tmp", "dev", "proc", "sys").forEach { File(rootfs, it).mkdirs(files) }
    File(rootfs, "tmp").asRuntimeFile(files).setWritable(true, false)
    // Before the version: until everything kept is back, the runtime reads as not installed.
    if (keep != null) restoreKept(keep, rootfs, dropKept, mounts, files)
    // Everything the marker vouches for is on disk before it says ready. A marker torn by a
    // power cut doesn't match the version, so the next start just installs again.
    sync()
    versionFile.writeText(version)
    retired.deleteRecursivelySafely(mounts, files = files)
}

/** One line of /proc/<pid>/mountinfo: the mount point (field 5) plus its peer-group markers. */
internal data class MountEntry(val mountPoint: String, val shared: Boolean = false, val master: Boolean = false)

/** Parses /proc/<pid>/mountinfo by field; the regex-free field split the mount-leak review called for. */
internal object MountInfo {
    private val ESCAPES = mapOf("040" to " ", "011" to "\t", "012" to "\n", "134" to "\\")

    /** [text] is the raw mountinfo; malformed lines are skipped. */
    fun parse(text: String): List<MountEntry> = text.lines().mapNotNull { line ->
        val fields = splitFields(line)
        if (fields.size < 5) return@mapNotNull null
        val optional = fields.drop(6).takeWhile { it != "-" }
        MountEntry(
            mountPoint = decode(fields[4]),
            shared = optional.any { it.startsWith("shared:") },
            master = optional.any { it.startsWith("master:") },
        )
    }

    /** Mounts at [tree] itself or below it, compared on whole path components. */
    fun under(mounts: List<MountEntry>, tree: String): List<MountEntry> {
        val base = tree.trimEnd('/').ifEmpty { "/" }
        if (base == "/") return mounts.toList()
        val prefix = "$base/"
        return mounts.filter { it.mountPoint == base || it.mountPoint.startsWith(prefix) }
    }

    /** Splits on unescaped spaces; a `\040` keeps a literal space inside one field. */
    private fun splitFields(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var escaped = false
        for (c in line) {
            when {
                c == '\\' -> {
                    escaped = true
                    current.append(c)
                }
                c == ' ' && !escaped -> {
                    fields += current.toString()
                    current.clear()
                }
                else -> {
                    if (c != ' ') escaped = false
                    current.append(c)
                }
            }
        }
        if (current.isNotEmpty()) fields += current.toString()
        return fields
    }

    /** Decodes the kernel's octal escapes (040 space, 011 tab, 012 newline, 134 backslash). */
    private fun decode(field: String): String {
        if (!field.contains('\\')) return field
        val out = StringBuilder(field.length)
        var i = 0
        while (i < field.length) {
            val c = field[i]
            if (c == '\\' && i + 4 <= field.length) {
                val code = field.substring(i + 1, i + 4)
                ESCAPES[code]?.let {
                    out.append(it)
                    i += 4
                    continue
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }
}

/**
 * The mount tables a delete must respect: this process's view plus the host's own
 * (`/proc/1/mountinfo`). A null list is a view that could not be read; root builds refuse
 * to delete environment trees until the root backend provides an authoritative check
 * (notes/root-backend-interface.md), so a leaked mount can never be rm -rf'd through.
 */
internal class MountGuard(
    private val self: List<MountEntry>?,
    private val host: List<MountEntry>?,
    internal val strict: Boolean,
    private val refresh: (() -> MountGuard)? = null,
) {
    constructor(self: List<MountEntry>?, host: List<MountEntry>?) : this(self, host, false)

    val hostVisible: Boolean get() = host != null

    /** Throws when [tree] holds live mounts in any readable view, or — in strict builds — when no view can rule them out. */
    fun assertDeletable(tree: File, allowWithoutHostProof: Boolean = false) {
        val current = refresh?.invoke() ?: this
        current.assertSnapshotDeletable(tree, allowWithoutHostProof)
    }

    private fun assertSnapshotDeletable(tree: File, allowWithoutHostProof: Boolean) {
        val blockers = MountInfo.under(self.orEmpty() + host.orEmpty(), tree.absolutePath)
        if (blockers.isNotEmpty()) {
            throw IllegalStateException(
                "refusing to delete ${tree.path}: " +
                    blockers.joinToString { "${it.mountPoint} is mounted there${if (it.shared || it.master) " (shared)" else ""}" } +
                    "; a recursive delete would cross live mounts (notes/probes/incident-root-cause.md)",
            )
        }
        if (strict && !hostVisible && !allowWithoutHostProof) {
            throw IllegalStateException(
                "refusing to delete ${tree.path}: the host mount table (/proc/1/mountinfo) is not readable, " +
                    "so mounts under it cannot be ruled out; the root backend must provide this check (notes/root-backend-interface.md)",
            )
        }
    }

    companion object {
        fun read(
            strict: Boolean,
            selfPath: String = "/proc/self/mountinfo",
            hostPath: String = "/proc/1/mountinfo",
            files: RuntimeFileAccess? = null,
        ): MountGuard = MountGuard(
            readMounts(selfPath, files),
            readMounts(hostPath, files),
            strict,
            if (files == null) null else {
                { readSnapshot(strict, selfPath, hostPath, files) }
            },
        )

        private fun readSnapshot(
            strict: Boolean,
            selfPath: String,
            hostPath: String,
            files: RuntimeFileAccess,
        ): MountGuard = MountGuard(readMounts(selfPath, files), readMounts(hostPath, files), strict)

        private fun readMounts(path: String, files: RuntimeFileAccess?): List<MountEntry>? = try {
            val file = File(path)
            MountInfo.parse(files?.readText(file) ?: file.readText())
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Recursive delete that removes symlinks without following them and copes with read-only dirs.
 * With [mounts], a tree that could hold mounts: refuses up front when any view shows a mount
 * under it, and in strict (root) builds verifies nothing was left behind afterwards.
 */
internal fun File.deleteRecursivelySafely(
    mounts: MountGuard? = null,
    allowWithoutHostProof: Boolean = false,
    files: RuntimeFileAccess? = null,
) {
    if (mounts == null) {
        deleteRecursivelyLenient(files)
        return
    }
    // A mount keeps its directory alive, so a missing tree has nothing mounted under it.
    if (!present(files)) return
    mounts.assertDeletable(this, allowWithoutHostProof)
    if (files == null) deleteRecursivelyLenient() else files.delete(this, recursive = true)
    if (mounts.strict && present(files)) {
        throw IllegalStateException(
            "could not delete $path completely; what remains is likely root-owned and needs the root-side " +
                "file interface (notes/root-backend-interface.md)",
        )
    }
}

private fun File.deleteRecursivelyLenient(files: RuntimeFileAccess? = null) {
    val current = asRuntimeFile(files)
    if (isSymlink(files)) {
        current.delete()
        return
    }
    if (current.isDirectory) {
        if (files == null) {
            if (!current.canWrite()) runCatching { android.system.Os.chmod(path, 0x1C0) }
            if (!current.canWrite()) current.setWritable(true)
        }
        current.listFiles()?.forEach { File(it.path).deleteRecursivelyLenient(files) }
    }
    if (files == null) current.delete() else files.delete(this, recursive = current.isDirectory)
}

private fun File.isSymlink(): Boolean = try {
    android.system.OsConstants.S_ISLNK(android.system.Os.lstat(path).st_mode)
} catch (_: Exception) {
    // No android.system off the device (JVM tests).
    runCatching { Files.isSymbolicLink(toPath()) }.getOrDefault(false)
}

/** Exists, counting a symlink whose target is gone. */
internal fun syncDirectory(directory: File) {
    FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
}

/** What 「重置运行环境」 can keep of the user's /root (paths under the rootfs). */
enum class ResetKeep(val wire: String, val paths: List<String>) {
    /** `$DSH_HOME/dsh-go` (ui.json) and both user skill roots: small, written by the user, harmless. */
    UI_AND_SKILLS("uiAndSkills", listOf("root/.dsh/dsh-go", "root/.dsh/skills", "root/.agents/skills")),

    /** Installed plugins with their settings, the global settings layer and the home `.env`. */
    PLUGINS_AND_SETTINGS("pluginsAndSettings", listOf("root/.dsh/profiles", "root/.dsh/cordis.patch.yml", "root/.dsh/.env"));

    companion object {
        /** Every path a reset can keep; none lies inside another. */
        val ALL: List<String> = entries.flatMap { it.paths }

        fun fromWire(names: Collection<String>): Set<ResetKeep> = entries.filterTo(LinkedHashSet()) { it.wire in names }
    }
}

/** The reset's choice of paths, written into the runtime before it is retired. */
private const val KEEP_LIST = "reset.keep"

/**
 * What a reset kept could not be moved; nothing is lost, and the install can be retried or told
 * to drop what cannot move. The message starts with `keep:` (the app's failure page looks for it).
 */
class KeepFailed(rel: String) : IllegalStateException("keep: could not move $rel")

/**
 * Deletes [base] but keeps [paths] of its rootfs in [keep]. The runtime is first renamed to [retired]
 * in one step, so from then on it reads as not installed, and the list of paths goes with it: a reset
 * killed at any point is finished by the next one or by the next install ([settleReset]). Nothing is
 * deleted before what is kept has moved.
 */
internal fun retireRuntime(
    base: File,
    retired: File,
    keep: File,
    paths: List<String>,
    mounts: MountGuard? = null,
    files: RuntimeFileAccess? = null,
) {
    settleReset(retired, keep, mounts = mounts, files = files)
    if (!base.present(files)) {
        // Nothing to take from; the choice still applies to what an earlier reset kept.
        pruneKept(keep, paths, mounts, files)
        return
    }
    mounts?.assertDeletable(base)
    val list = File(base, KEEP_LIST)
    list.writeText(paths.joinToString("\n"))
    if (!base.renameTo(retired, files)) {
        list.delete()
        throw IllegalStateException("could not retire the runtime")
    }
    settleReset(retired, keep, mounts = mounts, files = files)
}

/**
 * Finishes a retired runtime: moves its listed paths into [keep], then deletes it. A path that
 * [keep] already holds is an earlier reset's, not restored yet (the runtime never got installed),
 * so that copy is the user's and the retired one is a fresh one. If a move fails, the retired
 * runtime stays as it is and the next attempt picks up from there; with [dropFailed] it is deleted
 * with whatever could not move.
 */
internal fun settleReset(
    retired: File,
    keep: File,
    dropFailed: Boolean = false,
    mounts: MountGuard? = null,
    files: RuntimeFileAccess? = null,
) {
    if (!retired.present(files)) return
    mounts?.assertDeletable(retired)
    val list = File(retired, KEEP_LIST)
    val listed = readKeepList(list)
    // activateRootfs keeps the authoritative home here until its move back succeeds.
    // A reset during that interrupted upgrade must not keep the fresh rootfs skeleton.
    val carry = File(retired, "home.carry")
    val home = if (carry.present(files)) carry else File(retired, "rootfs/root")
    for (rel in listed.orEmpty()) {
        val from = File(home, rel.removePrefix("root/"))
        val to = File(keep, rel)
        if (!from.present(files) || to.present(files)) continue
        to.parentFile?.mkdirs(files)
        if (!from.renameTo(to, files) && !dropFailed) throw KeepFailed(rel)
    }
    // An unreadable list keeps whatever is already kept.
    if (listed != null) pruneKept(keep, listed, mounts, files)
    // The list goes first: a delete cut short leaves nothing that a later settle would keep.
    list.delete()
    retired.deleteRecursivelySafely(mounts, files = files)
}

/** The kept paths, or null when the list is missing; anything that could leave the rootfs is dropped. */
private fun readKeepList(file: File): List<String>? {
    if (!file.isFile) return null
    return file.readLines().map { it.trim() }.filter { it in ResetKeep.ALL }
}

/** Drops what [keep] holds outside [paths]: the latest reset's choice wins. */
private fun pruneKept(
    keep: File,
    paths: List<String>,
    mounts: MountGuard? = null,
    files: RuntimeFileAccess? = null,
) {
    if (!keep.present(files)) return
    for (rel in ResetKeep.ALL) if (rel !in paths) File(keep, rel).deleteRecursivelySafely(mounts, files = files)
    keep.deleteEmptyDirs(files)
}

/**
 * Moves what a reset kept back into the new [rootfs], over the fresh copies (which wait in
 * `keep.trash` until all is back). A failed move puts that path's fresh copy back and throws
 * [KeepFailed]; what is not back yet stays in [keep] for the next install. With [dropFailed] the
 * paths that cannot move are given up instead and [keep] is cleared.
 */
internal fun restoreKept(
    keep: File,
    rootfs: File,
    dropFailed: Boolean = false,
    mounts: MountGuard? = null,
    files: RuntimeFileAccess? = null,
) {
    val dropped = File(keep.parentFile, "${keep.name}.dropped")
    dropped.deleteRecursivelySafely(mounts, files = files)
    if (!keep.present(files)) return
    val trash = File(rootfs.parentFile, "keep.trash")
    trash.deleteRecursivelySafely(mounts, allowWithoutHostProof = true, files = files)
    for (rel in ResetKeep.ALL) {
        val kept = File(keep, rel)
        if (!kept.present(files)) continue
        val target = File(rootfs, rel)
        val aside = File(trash, rel)
        if (target.present(files)) {
            aside.parentFile?.mkdirs(files)
            if (!target.renameTo(aside, files)) {
                if (dropFailed) continue
                throw KeepFailed(rel)
            }
        }
        target.parentFile?.mkdirs(files)
        if (!kept.renameTo(target, files)) {
            if (aside.present(files)) aside.renameTo(target, files)
            if (dropFailed) continue
            throw KeepFailed(rel)
        }
    }
    trash.deleteRecursivelySafely(mounts, allowWithoutHostProof = true, files = files)
    if (!dropFailed) {
        keep.deleteEmptyDirs(files)
        return
    }
    // Out of the restore's way in one step, then deleted; a delete cut short is retried next time.
    if (keep.renameTo(dropped, files)) dropped.deleteRecursivelySafely(mounts, files = files)
    else keep.deleteRecursivelySafely(mounts, files = files)
}

/** Removes the empty directories of this tree, itself included; files (and symlinks) stay. */
private fun File.deleteEmptyDirs(files: RuntimeFileAccess? = null) {
    val current = asRuntimeFile(files)
    if (isSymlink(files) || !current.isDirectory) return
    current.listFiles()?.forEach { File(it.path).deleteEmptyDirs(files) }
    if (current.list()?.isEmpty() == true) current.delete()
}
