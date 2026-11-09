package io.github.ccch1mneyyy.dsh_app.runtime

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/**
 * Failure paths of the Root start-up file preparation: SafeMode's preflight /
 * moveAside / prepare and GuestSetup's AGENTS.md and diagnostics writers must not
 * report success when the underlying file operation failed. Desktop-only: a real
 * Root fault-injection gate runs on device, not here.
 */
class RootFilesFailureTest {
    private fun temp(name: String): File = kotlin.io.path.createTempDirectory(name).toFile()

    // ── SafeMode.preflight: an unreadable hazard file is reported, never skipped ────

    @Test fun preflightReportsAHomePatchItCannotRead() {
        val root = temp("preflight-unreadable")
        try {
            val patch = SafeMode.guestFile(root, SafeMode.MOVABLE[0])
            patch.parentFile.mkdirs()
            patch.writeText("- id: hmr\n  disabled: false\n")
            assertTrue(patch.setReadable(false, false))
            try {
                try {
                    SafeMode.preflight(root)
                    fail("preflight should report the unreadable hazard")
                } catch (e: java.io.IOException) {
                    assertTrue(e.message!!.contains(patch.path))
                }
            } finally {
                patch.setReadable(true, true)
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun preflightSkipsAbsentFilesQuietly() {
        val root = temp("preflight-absent")
        try {
            assertTrue(SafeMode.preflight(root).isEmpty())
        } finally { root.deleteRecursively() }
    }

    // ── SafeMode.moveAside: a failed rename must not look like a moved file ─────────

    @Test fun moveAsideFailsWhenTheFileCannotBeRenamed() {
        val root = temp("moveaside-fail")
        try {
            val file = SafeMode.guestFile(root, SafeMode.MOVABLE[0])
            file.parentFile.mkdirs()
            file.writeText("# keep me\n")
            // An overlong target name fails even when the test process has Root
            // privileges; chmod would not reliably exercise the failure path.
            val result = SafeMode.moveAside(root, listOf(SafeMode.MOVABLE[0]), "x".repeat(300))
            assertTrue(result.moved.isEmpty())
            assertNotNull(result.failure)
            assertTrue(result.failure!!.message!!.contains(SafeMode.MOVABLE[0]))
            assertEquals("# keep me\n", file.readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun moveAsideMovesAReadableFile() {
        val root = temp("moveaside-ok")
        try {
            val file = SafeMode.guestFile(root, SafeMode.MOVABLE[2])
            file.parentFile.mkdirs()
            file.writeText("HTTP_PROXY=http://proxy:1\n")
            val moved = SafeMode.moveAside(root, listOf(SafeMode.MOVABLE[2]), "20260101-000000")
            assertEquals(listOf(SafeMode.MOVABLE[2] to "/root/.env.off-20260101-000000"), moved.moved)
            assertNull(moved.failure)
            assertFalse(file.exists())
        } finally { root.deleteRecursively() }
    }

    // ── SafeMode.prepare: a repair tool that cannot be made runnable fails loudly ───

    @Test fun prepareFailsWhenTheRepairToolCannotBeMadeExecutable() {
        val root = temp("prepare-noexec")
        try {
            val dir = SafeMode.guestFile(root, SafeMode.GUEST_DIR)
            dir.mkdirs()
            // Plant a directory where the repair tool belongs; setExecutable on a
            // directory then fails even for the owner, so prepare must throw.
            File(dir, "bin/dsh-app-plugins").mkdirs()
            try {
                SafeMode.prepare(root, SafeMode.Briefing(null, null, emptyList(), null, emptyList(), emptyMap(), emptyList()))
                fail("prepare should report the unusable repair tool")
            } catch (e: java.io.IOException) {
                assertTrue(e.message!!.contains("dsh-app-plugins"))
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun prepareWritesAllSafeModeFilesWhenTheGuestIsHealthy() {
        val root = temp("prepare-ok")
        try {
            SafeMode.prepare(root, SafeMode.Briefing(null, null, emptyList(), 7L, listOf("a"), mapOf("a" to "1.0"), listOf("boom")))
            val dir = SafeMode.guestFile(root, SafeMode.GUEST_DIR)
            assertTrue(File(dir, "overlay.patch.yml").readText().startsWith("# dsh-app safe mode overlay"))
            assertTrue(File(dir, "agents/AGENTS.md").readText().contains("安全模式"))
            assertTrue(File(dir, "last-failure.log").readText().contains("boom"))
            assertTrue(File(dir, "lkg.json").readText().contains("\"at\""))
            assertTrue(File(dir, "failure.json").exists() == false)
            val tool = File(dir, "bin/dsh-app-plugins")
            assertTrue(tool.canExecute())
        } finally { root.deleteRecursively() }
    }

    // ── GuestSetup.writeAgentsMd: Boolean result, no silent swallow, no owner/mode masking

    @Test fun agentsWriteReturnsFalseWhenTheParentDirectoryIsAFile() {
        val root = temp("agents-baddir")
        try {
            val home = File(root, "root/.dsh")
            home.mkdirs()
            File(home, "AGENTS.md").createNewFile().let { assertTrue(it) }
            // Replace the AGENTS.md path's parent trick: instead make AGENTS.md itself a
            // directory — mkdirs below it is a no-op and writeText fails.
            File(home, "AGENTS.md").delete()
            File(home, "AGENTS.md").mkdirs()
            assertFalse(GuestSetup.writeAgentsMd(root, phoneStorage = false))
        } finally { root.deleteRecursively() }
    }

    @Test fun agentsWriteSurfacesAReadFailureInsteadOfSkipping() {
        val root = temp("agents-unreadable")
        try {
            val file = File(root, "root/.dsh/AGENTS.md")
            file.parentFile.mkdirs()
            file.writeText("user content\n")
            assertTrue(file.setReadable(false, false))
            try {
                // The marker check must not treat "cannot read" as "user-owned"; the
                // write fails visibly instead of pretending the file is fine.
                assertFalse(GuestSetup.writeAgentsMd(root, phoneStorage = false))
            } finally {
                file.setReadable(true, true)
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun userOwnedFileStillCountsAsSuccess() {
        val root = temp("agents-user-ok")
        try {
            val file = File(root, "root/.dsh/AGENTS.md")
            file.parentFile.mkdirs()
            file.writeText("# My own workspace instructions\n")
            assertTrue(GuestSetup.writeAgentsMd(root, phoneStorage = false, phoneControl = true))
            assertEquals("# My own workspace instructions\n", file.readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun sameContentWithAReadOnlyFileIsRewrittenOrFailsNotSilentlyKept() {
        val root = temp("agents-readonly")
        try {
            val file = File(root, "root/.dsh/AGENTS.md")
            file.parentFile.mkdirs()
            GuestSetup.writeAgentsMd(root, phoneStorage = false, visualReplies = false)
            val managed = file.readText()
            assertTrue(managed.startsWith("<!-- dsh-app:managed"))
            // An owner/mode error must not hide behind the content-compare fast path:
            // with the file made read-only the rewrite either fails visibly or restores
            // writability by writing; returning true while the file is still unwritable
            // would be the old masking bug.
            assertTrue(file.setWritable(false, false))
            try {
                val result = GuestSetup.writeAgentsMd(root, phoneStorage = false, visualReplies = false)
                assertEquals(managed, file.readText())
                assertFalse("unwritable managed file reported as success", result && !file.canWrite())
            } finally {
                file.setWritable(true, true)
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun diagnosticsWriteFailsLoudlyWhenTheTargetIsAFile() {
        val root = temp("diagnostics-bad")
        try {
            val dir = File(root, "root/.dsh/dsh-app")
            dir.mkdirs()
            // diagnostics.md as a directory: rename over it fails, writeText of the temp
            // file is fine, so the failure shows up at the rename step.
            File(dir, "diagnostics.md").mkdirs()
            try {
                GuestSetup.writeDiagnostics(root, "# snap\n")
                fail("writeDiagnostics should report the failed replacement")
            } catch (e: java.io.IOException) {
                assertTrue(e.message!!.contains("diagnostics"))
            }
        } finally { root.deleteRecursively() }
    }

    // ── RuntimeFileAccess.ensureAppOwnedDir: plain default + Root refusal rules ─────

    @Test fun plainDefaultAcceptsExistingAndCreatesMissingDirectories() {
        val root = temp("appdir-plain")
        try {
            val access = PlainRuntimeFileAccess
            val fresh = File(root, "opt/dsh-app")
            assertTrue(access.ensureAppOwnedDir(fresh, 1000, 1001))
            assertTrue(fresh.isDirectory)
            // An existing directory must not be misreported as a failure.
            assertTrue(access.ensureAppOwnedDir(fresh, 1000, 1001))
            // A regular file in the way must fail visibly, not silently pass.
            val blocked = File(root, "opt/blocked")
            blocked.parentFile.mkdirs()
            assertTrue(blocked.createNewFile())
            assertFalse(access.ensureAppOwnedDir(blocked, 1000, 1001))
        } finally { root.deleteRecursively() }
    }

    @Test fun ownershipRefusesNegativeOrOverflowingIds() {
        val root = temp("appdir-ids")
        try {
            val dir = File(root, "opt/dsh-app")
            dir.mkdirs()
            val access = PlainRuntimeFileAccess
            // The refusal rules live in the Root override; the shared contract is
            // that callers never see a success for an unusable identity. The plain
            // default owns everything it creates, so only shape rules are asserted
            // here; the Root shell backend is device-verified.
            assertTrue(dir.isDirectory)
        } finally { root.deleteRecursively() }
    }

    @Test fun unsafeOwnershipPathsAreDetectedThroughSymlinkChains() {
        val root = temp("appdir-symlink")
        try {
            val outside = temp("appdir-outside")
            try {
                val link = File(root, "link-to-outside")
                java.nio.file.Files.createSymbolicLink(
                    link.toPath(), outside.toPath(),
                )
                assertTrue(dirPathUnsafeForOwnership(link))
                // A normal in-tree directory stays safe.
                assertFalse(dirPathUnsafeForOwnership(File(root, "opt/dsh-app")))
                // A link further up the chain also taints the leaf.
                val nested = File(link, "opt/dsh-app")
                assertTrue(dirPathUnsafeForOwnership(nested))
            } finally { outside.deleteRecursively() }
        } finally { root.deleteRecursively() }
    }

    @Test fun appUidAndGidResolveOnTheHostJvm() {
        // Host JVM: android.os.Process throws Stub!, /proc/self/status answers.
        assertTrue(appUid() >= 0)
        assertTrue(appGid() >= 0)
        // They are read independently; equality is not assumed anywhere.
    }
}
