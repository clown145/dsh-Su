package io.github.ccch1mneyyy.dsh_app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * [LastKnownGood.readDetailed]/[LastKnownGood.restore] through the root-aware
 * [RuntimeFileAccess] seam and DSH's shared writer lock: the bundle list and
 * settings layer are replaced atomically under `package.json.lock`, the replaced
 * settings layer is kept as `cordis.patch.yml.bak-<stamp>`, and a missing,
 * corrupt or unreadable main profile is told apart instead of collapsing into one
 * null.
 *
 * The tests that execute the JSON rewrite itself are guarded by
 * [ORG_JSON_OPERABLE]: the JVM unit-test classpath stubs parts of org.json, while
 * the on-device engine ships the real implementation, so those tests run wherever
 * org.json works and skip (rather than fail) on the stubbed host classpath.
 */
class LastKnownGoodRestoreSeamTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** True when this classpath executes org.json object navigation for real. */
    private val orgJsonOperable: Boolean = try {
        org.json.JSONObject("""{"a":{"b":1}}""").optJSONObject("a") != null
    } catch (_: RuntimeException) {
        false
    }

    /** Plain-file seam over a real directory tree; can force a read failure per path fragment. */
    private inner class FakeAccess(private val rootfs: File, private val failReadOf: String? = null) : RuntimeFileAccess {
        override fun file(file: File): File {
            if (file.path.startsWith(rootfs.path)) return file
            return File(rootfs, file.path.removePrefix("/"))
        }
        override fun isSymlink(file: File): Boolean = file(file).let { java.nio.file.Files.isSymbolicLink(it.toPath()) }
        override fun delete(file: File, recursive: Boolean): Boolean = file(file).let { if (recursive) it.deleteRecursively() else it.delete() }
        override fun readText(file: File): String {
            val resolved = file(file)
            if (failReadOf != null && resolved.path.contains(failReadOf)) throw java.io.IOException("permission denied")
            return resolved.readText()
        }
        override fun close() {}
    }

    private fun access(rootfs: File) = FakeAccess(rootfs)

    private fun rootfs(): File = tmp.newFolder("rootfs")

    private fun webDir(rootfs: File): File = File(rootfs, "root/.dsh/profiles/web").apply { mkdirs() }

    private fun pkgPath(rootfs: File): File = File(webDir(rootfs), "package.json")

    private fun patchPath(rootfs: File): File = File(webDir(rootfs), "cordis.patch.yml")

    private fun lockPath(rootfs: File): File = File(webDir(rootfs), "package.json.lock")

    private fun writeProfile(rootfs: File, bundles: List<String>, patch: String?) {
        pkgPath(rootfs).writeText("""{"dsh":{"profile":{"bundles":${bundles.joinToString(",", "[", "]") { "\"$it\"" }}}},"dependencies":{}}""")
        if (patch != null) patchPath(rootfs).writeText(patch) else patchPath(rootfs).delete()
    }

    private fun snapshot(bundles: List<String>, patch: String?) = ProfileSnapshot(
        bundles = bundles,
        deps = emptySet(),
        versions = emptyMap(),
        patch = patch,
    )

    private fun deadPid(): Long {
        val self = ProfileLock.currentPid()
        var candidate = self + 1
        while (java.io.File("/proc/" + candidate).exists()) candidate += 1
        return candidate
    }

    @Test
    fun `restore rewrites bundles and settings layer through the seam and removes the lock`() {
        Assume.assumeTrue(orgJsonOperable)
        val rootfs = rootfs()
        writeProfile(rootfs, bundles = listOf("current"), patch = "current patch")
        val plan = LastKnownGood.restore(access(rootfs), rootfs, snapshot(listOf("a", "b"), "last good patch"), "S1", setPrivateMode = { true })
        assertEquals(listOf("a", "b"), plan.target)
        assertTrue(plan.bundlesChanged && plan.patchChanged)
        val restored = org.json.JSONObject(pkgPath(rootfs).readText())
        val bundles = restored.getJSONObject("dsh").getJSONObject("profile").getJSONArray("bundles")
        assertEquals(listOf("a", "b"), List(bundles.length()) { bundles.getString(it) })
        assertEquals(0, restored.getJSONObject("dependencies").length())
        assertEquals("last good patch", patchPath(rootfs).readText())
        assertEquals("current patch", File(webDir(rootfs), "cordis.patch.yml.bak-S1").readText())
        assertFalse(lockPath(rootfs).exists())
        assertEquals(listOf("cordis.patch.yml", "cordis.patch.yml.bak-S1", "package.json"), webDir(rootfs).list()!!.sorted())
    }

    @Test
    fun `restore on a missing profile throws the missing message`() {
        val rootfs = rootfs()
        try {
            LastKnownGood.restore(access(rootfs), rootfs, snapshot(listOf("a"), null), "S1")
            throw AssertionError("restore must throw on a missing profile")
        } catch (expected: IllegalStateException) {
            assertEquals("找不到主配置 profiles/web/package.json", expected.message)
        }
        assertFalse(lockPath(rootfs).exists())
    }

    @Test
    fun `restore waits out a live holder and fails busy without touching the profile`() {
        val rootfs = rootfs()
        writeProfile(rootfs, listOf("current"), "current patch")
        Files.write(lockPath(rootfs).toPath(), "${ProfileLock.currentPid()}\n".toByteArray())
        try {
            LastKnownGood.restore(access(rootfs), rootfs, snapshot(listOf("a"), "p"), "S1", busyConfig = "busy!", lockWaitMs = 400, setPrivateMode = { true })
            throw AssertionError("restore must throw on a live lock")
        } catch (expected: IllegalStateException) {
            assertEquals("busy!", expected.message)
        }
        assertTrue(pkgPath(rootfs).readText().contains("current"))
        assertEquals("current patch", patchPath(rootfs).readText())
        assertTrue(lockPath(rootfs).exists())
    }

    @Test
    fun `restore takes over a lock whose holder pid is gone`() {
        Assume.assumeTrue(orgJsonOperable)
        val rootfs = rootfs()
        writeProfile(rootfs, listOf("current"), "current patch")
        Files.write(lockPath(rootfs).toPath(), "${deadPid()}\n".toByteArray())
        // The injected adapter plays the kernel: the stale holder reports ESRCH-dead.
        val plan = LastKnownGood.restore(access(rootfs), rootfs, snapshot(listOf("a"), "last good"), "S1", setPrivateMode = { true }, alive = { false })
        assertTrue(plan.bundlesChanged)
        assertEquals("last good", patchPath(rootfs).readText())
        assertFalse(lockPath(rootfs).exists())
    }

    @Test
    fun `restore keeps the replaced settings layer as a bak file`() {
        Assume.assumeTrue(orgJsonOperable)
        val rootfs = rootfs()
        writeProfile(rootfs, listOf("current"), "replaced layer")
        LastKnownGood.restore(access(rootfs), rootfs, snapshot(listOf("current"), "last good patch"), "S9", setPrivateMode = { true })
        assertEquals("replaced layer", File(webDir(rootfs), "cordis.patch.yml.bak-S9").readText())
        assertEquals("last good patch", patchPath(rootfs).readText())
    }

    @Test
    fun `restore with a null patch removes the settings layer but keeps the bak`() {
        Assume.assumeTrue(orgJsonOperable)
        val rootfs = rootfs()
        writeProfile(rootfs, listOf("current"), "replaced layer")
        LastKnownGood.restore(access(rootfs), rootfs, snapshot(listOf("current"), null), "S3", setPrivateMode = { true })
        assertFalse(patchPath(rootfs).exists())
        assertEquals("replaced layer", File(webDir(rootfs), "cordis.patch.yml.bak-S3").readText())
    }

    @Test
    fun `restore writes the bundle list without org-json slash escapes`() {
        Assume.assumeTrue(orgJsonOperable)
        val rootfs = rootfs()
        writeProfile(rootfs, listOf("cur/rent"), null)
        val plan = LastKnownGood.restore(access(rootfs), rootfs, snapshot(listOf("a/b"), null), "S1", setPrivateMode = { true })
        assertEquals(listOf("a/b"), plan.target)
        assertTrue("Android org.json escapes '/', the app must undo it", !pkgPath(rootfs).readText().contains("\\/"))
    }

    @Test
    fun `readDetailed distinguishes missing corrupt unreadable and ok`() {
        Assume.assumeTrue(orgJsonOperable)
        val missing = tmp.newFolder("rootfs-missing")
        assertEquals(LkgDisk.Missing, LastKnownGood.readDetailed(access(missing), missing))
        val corrupt = tmp.newFolder("rootfs-corrupt")
        pkgPath(corrupt).writeText("not json at all")
        val corruptDisk = LastKnownGood.readDetailed(access(corrupt), corrupt)
        assertTrue("expected Corrupt, got $corruptDisk", corruptDisk is LkgDisk.Corrupt)
        val unreadable = tmp.newFolder("rootfs-unreadable")
        pkgPath(unreadable).writeText("{}")
        val unreadableDisk = LastKnownGood.readDetailed(FakeAccess(unreadable, failReadOf = "package.json"), unreadable)
        assertTrue("expected Unreadable, got $unreadableDisk", unreadableDisk is LkgDisk.Unreadable)
        val ok = tmp.newFolder("rootfs-ok")
        writeProfile(ok, listOf("a"), "patch")
        val okDisk = LastKnownGood.readDetailed(access(ok), ok)
        assertTrue("expected Ok, got $okDisk", okDisk is LkgDisk.Ok)
        assertEquals(listOf("a"), (okDisk as LkgDisk.Ok).snapshot.bundles)
        assertEquals("patch", okDisk.snapshot.patch)
    }

    @Test
    fun `restore failing mid-way leaves the profile intact and the lock released`() {
        Assume.assumeTrue(orgJsonOperable)
        val rootfs = rootfs()
        writeProfile(rootfs, listOf("current"), "current patch")
        try {
            LastKnownGood.restore(access(rootfs), rootfs, snapshot(listOf("a"), "p"), "S1", setPrivateMode = { false })
            throw AssertionError("restore must propagate the failure")
        } catch (expected: IllegalStateException) {
            assertTrue(
                "the failure names the private-mode step, got: ${expected.message}",
                expected.message?.contains("private mode") == true,
            )
        }
        assertTrue(pkgPath(rootfs).readText().contains("current"))
        assertEquals("current patch", patchPath(rootfs).readText())
        assertFalse(lockPath(rootfs).exists())
        assertEquals(listOf("cordis.patch.yml", "package.json"), webDir(rootfs).list()!!.sorted())
    }
}
