package io.github.ccch1mneyyy.dsh_app.runtime

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class GuestAgentsModeTest {
    @Test fun compatibleLaunchRestoresTheExactOriginalDefaultInstructionsAfterDirect() {
        val root = kotlin.io.path.createTempDirectory("agents-mode").toFile()
        try {
            val file = File(root, "root/.dsh/AGENTS.md")
            GuestSetup.writeAgentsMd(root, phoneStorage = false)
            val original = file.readBytes()
            assertTrue(file.readText().contains("`/root/workspace`"))
            GuestSetup.writeAgentsMd(root, phoneStorage = false, directEngine = true)
            assertTrue(file.readText().contains("`${root.canonicalPath}/root/workspace`"))
            assertFalse(original.contentEquals(file.readBytes()))
            GuestSetup.writeAgentsMd(root, phoneStorage = false, directEngine = false)
            assertArrayEquals(original, file.readBytes())
        } finally { root.deleteRecursively() }
    }

    @Test fun aUserOwnedFileIsUnchangedAcrossBothLaunchModes() {
        val root = kotlin.io.path.createTempDirectory("agents-user").toFile()
        try {
            val file = File(root, "root/.dsh/AGENTS.md")
            file.parentFile.mkdirs()
            val original = "# My own workspace instructions\nKeep my paths and examples.\n".toByteArray()
            file.writeBytes(original)
            for (direct in listOf(true, false)) {
                GuestSetup.writeAgentsMd(root, phoneStorage = true, phoneControl = true, directEngine = direct)
                assertArrayEquals(original, file.readBytes())
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun phoneStorageSendsOutputsToTheDshGoFolder() {
        val root = kotlin.io.path.createTempDirectory("agents-storage").toFile()
        try {
            GuestSetup.writeAgentsMd(root, phoneStorage = true)
            val text = File(root, "root/.dsh/AGENTS.md").readText()
            // The folder FilesPlugin saves into and lists, with no space to quote in shell commands.
            assertTrue(text.contains("给用户的产出文件放到 `/sdcard/Download/DSH-Go/`。"))
            assertFalse(text.contains("/sdcard/Download/DSH/"))
        } finally { root.deleteRecursively() }
    }

    @Test fun diagnosticsSectionNamesThePathForEachLaunchMode() {
        val root = kotlin.io.path.createTempDirectory("agents-diagnostics").toFile()
        try {
            val file = File(root, "root/.dsh/AGENTS.md")
            GuestSetup.writeAgentsMd(root, phoneStorage = false)
            val proot = file.readText()
            assertTrue(proot.contains("## 本机诊断"))
            assertTrue(proot.contains("`\$DSH_HOME/dsh-app/diagnostics.md`"))
            assertTrue(proot.contains("dsh-go-support"))
            GuestSetup.writeAgentsMd(root, phoneStorage = false, directEngine = true)
            val direct = file.readText()
            assertTrue(direct.contains("`${root.canonicalPath}/root/.dsh/dsh-app/diagnostics.md`"))
            assertFalse(direct.contains("\$DSH_HOME/dsh-app/diagnostics.md"))
        } finally { root.deleteRecursively() }
    }

    @Test fun diagnosticsSnapshotIsWrittenWhereTheEngineCanReadIt() {
        val root = kotlin.io.path.createTempDirectory("agents-write").toFile()
        try {
            val file = GuestSetup.writeDiagnostics(root, "# first\n")
            assertEquals(File(root, "root/.dsh/dsh-app/diagnostics.md").absoluteFile, file.absoluteFile)
            assertEquals("# first\n", file.readText())
            GuestSetup.writeDiagnostics(root, "# second\n")
            assertEquals("# second\n", file.readText())
            assertEquals(0, file.parentFile!!.listFiles()!!.count { it.name.startsWith(".diagnostics") })
        } finally { root.deleteRecursively() }
    }

    @Test fun safeDiagnosticsLeaveOriginalHomeUntouchedAndNormalDiagnosticsReturnToIt() {
        val root = kotlin.io.path.createTempDirectory("diagnostics-safe").toFile()
        try {
            val original = GuestSetup.writeDiagnostics(root, "# original\n")
            val originalBytes = original.readBytes()
            val safeFile = GuestSetup.writeDiagnostics(root, "# rescue\n", safe = true)
            assertEquals(
                File(root, "var/lib/dsh-app/safe-mode/home/dsh-app/diagnostics.md").absoluteFile,
                safeFile.absoluteFile,
            )
            assertEquals("# rescue\n", safeFile.readText())
            assertArrayEquals(originalBytes, original.readBytes())
            val normalFile = GuestSetup.writeDiagnostics(root, "# normal again\n")
            assertEquals(original.absoluteFile, normalFile.absoluteFile)
            assertEquals("# normal again\n", original.readText())
            assertEquals("# rescue\n", safeFile.readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun safeDiagnosticsDoNotNeedTheOriginalHomeToBeADirectory() {
        val root = kotlin.io.path.createTempDirectory("diagnostics-broken-home").toFile()
        try {
            val originalHome = File(root, "root/.dsh")
            originalHome.parentFile.mkdirs()
            val originalBytes = "original path is not a directory\n".toByteArray()
            originalHome.writeBytes(originalBytes)
            val safeFile = GuestSetup.writeDiagnostics(root, "# rescue\n", safe = true)
            assertEquals("# rescue\n", safeFile.readText())
            assertArrayEquals(originalBytes, originalHome.readBytes())
            assertEquals(0, safeFile.parentFile!!.listFiles()!!.count { it.name.startsWith(".diagnostics") })
        } finally { root.deleteRecursively() }
    }

    @Test fun unchangedDefaultStartupDoesNotRewriteAnExistingManagedFile() {
        val root = kotlin.io.path.createTempDirectory("agents-unchanged").toFile()
        try {
            val file = File(root, "root/.dsh/AGENTS.md")
            GuestSetup.writeAgentsMd(root, phoneStorage = true, visualReplies = false)
            val original = file.readBytes()
            assertTrue(file.setLastModified(1_234_567_000L))
            val modified = file.lastModified()
            GuestSetup.writeAgentsMd(root, phoneStorage = true, visualReplies = false, directEngine = false)
            assertArrayEquals(original, file.readBytes())
            assertEquals(modified, file.lastModified())
        } finally { root.deleteRecursively() }
    }

    @Test fun rootBuildDescribesARealRootChroot() {
        val root = kotlin.io.path.createTempDirectory("agents-root").toFile()
        try {
            val file = File(root, "root/.dsh/AGENTS.md")
            GuestSetup.writeAgentsMd(root, phoneStorage = false, rootRuntime = true)
            val text = file.readText()
            assertTrue(text.contains("chroot"))
            assertTrue(text.contains("真实 root（UID 0）"))
            assertFalse(text.contains("proot，免 Root"))
            // Chroot paths stay guest-side, unlike Direct's absolute host paths.
            assertTrue(text.contains("`/root/workspace`"))
            assertFalse(text.contains(root.canonicalPath))
            assertTrue(text.contains("`\$DSH_HOME/dsh-app/diagnostics.md`"))
        } finally { root.deleteRecursively() }
    }

    @Test fun rootBuildStillLeavesAUserOwnedAgentsFileAlone() {
        val root = kotlin.io.path.createTempDirectory("agents-root-user").toFile()
        try {
            val file = File(root, "root/.dsh/AGENTS.md")
            file.parentFile.mkdirs()
            file.writeText("# My own workspace instructions\n")
            // Root mode keeps the same write contract as upstream: a user-owned AGENTS.md is
            // left alone even when the app runs the engine as real root.
            GuestSetup.writeAgentsMd(root, phoneStorage = true, rootRuntime = true)
            assertEquals("# My own workspace instructions\n", file.readText())
            // The managed root template (see rootBuildDescribesARealRootChroot) describes a
            // chroot with real UID 0, never the upstream proot wording.
            val probe = kotlin.io.path.createTempDirectory("agents-root-managed").toFile()
            try {
                val managed = File(probe, "root/.dsh/AGENTS.md")
                GuestSetup.writeAgentsMd(probe, phoneStorage = true, rootRuntime = true)
                val text = managed.readText()
                assertTrue(text.startsWith("<!-- dsh-app:managed"))
                assertTrue(text.contains("chroot"))
                assertTrue(text.contains("真实 root（UID 0）"))
                assertFalse(text.contains("proot，免 Root"))
            } finally { probe.deleteRecursively() }
        } finally { root.deleteRecursively() }
    }

    @Test fun rootBuildKeepsPhoneActionsOnTheAppBridge() {
        val root = kotlin.io.path.createTempDirectory("agents-root-commands").toFile()
        try {
            GuestSetup.writeAgentsMd(root, phoneStorage = false, rootRuntime = true)
            val text = File(root, "root/.dsh/AGENTS.md").readText()
            assertTrue(text.contains("手机操作请用 App 的 `mcp__phone__*` 工具"))
            assertFalse(text.contains("`cmd`"))
            assertFalse(text.contains("`/system/bin/...`"))
        } finally { root.deleteRecursively() }
    }
}
