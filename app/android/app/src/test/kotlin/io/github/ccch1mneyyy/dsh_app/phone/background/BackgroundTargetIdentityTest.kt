package io.github.ccch1mneyyy.dsh_app.phone.background

import io.github.ccch1mneyyy.dsh_app.phone.PhoneAction
import io.github.ccch1mneyyy.dsh_app.phone.PhoneAppIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The background write's identity contract, on both ends of the wire. The Bundle objects
 * themselves cannot be constructed in a JVM unit test (the framework jar is stubbed), so
 * the wire form is asserted as the exact key/value list both sides agree on, and the
 * privileged check runs on that same list through its map-shaped seam. Real Bundle
 * marshalling and the actual injection path stay device-only.
 */
class BackgroundTargetIdentityTest {
    private val button = PrivilegedDisplayCore.Identity("按钮", "确定", "com.a:id/ok")
    private val blankInput = PrivilegedDisplayCore.Identity("输入框", "", null)

    // ---- privileged side: identity completeness and matching ------------------------

    @Test fun anIdentityIsOnlyCompleteWithAllThreeFieldsPresent() {
        val full = mapOf(
            Proto.EXPECT_ROLE to "按钮",
            Proto.EXPECT_LABEL to "确定",
            Proto.EXPECT_VIEW_ID to "com.a:id/ok",
        )
        assertEquals(button, PrivilegedDisplayCore.identityOf(full))
        for (key in PrivilegedDisplayCore.identityKeys) {
            assertNull("missing $key must refuse the action", PrivilegedDisplayCore.identityOf(full - key))
        }
        assertNull("a null role is not an identity", PrivilegedDisplayCore.identityOf(full + (Proto.EXPECT_ROLE to null)))
        assertNull("a null label is not an identity", PrivilegedDisplayCore.identityOf(full + (Proto.EXPECT_LABEL to null)))
    }

    @Test fun matchingRequiresAllThreeValuesToBeEqual() {
        assertTrue(PrivilegedDisplayCore.identityMatches(button, button))
        assertTrue(PrivilegedDisplayCore.identityMatches(blankInput, blankInput))
    }

    @Test fun aSameNumberWithADifferentNodeIsRefused() {
        // label grew: same prefix, different node
        assertFalse(PrivilegedDisplayCore.identityMatches(button, PrivilegedDisplayCore.Identity("按钮", "确定更新", "com.a:id/ok")))
        // same label, different view id
        assertFalse(PrivilegedDisplayCore.identityMatches(button, PrivilegedDisplayCore.Identity("按钮", "确定", "com.a:id/send")))
        // view id lost
        assertFalse(PrivilegedDisplayCore.identityMatches(button, PrivilegedDisplayCore.Identity("按钮", "确定", null)))
        // role changed under the same label
        assertFalse(PrivilegedDisplayCore.identityMatches(button, PrivilegedDisplayCore.Identity("文本", "确定", "com.a:id/ok")))
        // the blank input got text
        assertFalse(PrivilegedDisplayCore.identityMatches(blankInput, PrivilegedDisplayCore.Identity("输入框", "138****0000", null)))
    }

    @Test fun anIdentityThatMatchesSeveralNodesIsAmbiguous() {
        val second = PrivilegedDisplayCore.Identity("按钮", "确定", "com.a:id/ok")
        assertFalse(PrivilegedDisplayCore.identityAmbiguous(button, listOf(button)))
        assertTrue("an unlabelled role row repeats on every list: refuse", PrivilegedDisplayCore.identityAmbiguous(blankInput, listOf(blankInput, blankInput)))
        assertTrue(PrivilegedDisplayCore.identityAmbiguous(button, listOf(button, second)))
    }

    @Test fun aWeakIdentityIsRefusedForWritesButStillCarried() {
        // 空文本且无 viewId：写操作拒绝；有 viewId 或有文本即可安全写。
        assertFalse("blank label with no view id cannot be re-verified", PrivilegedDisplayCore.writeIdentitySufficient(blankInput))
        assertTrue(PrivilegedDisplayCore.writeIdentitySufficient(PrivilegedDisplayCore.Identity("输入框", "", "com.a:id/q")))
        assertTrue(PrivilegedDisplayCore.writeIdentitySufficient(PrivilegedDisplayCore.Identity("输入框", "备注", null)))
        assertTrue(PrivilegedDisplayCore.writeIdentitySufficient(button))
        // The wire still carries the weak identity untouched — the refusal happens on the
        // privileged side, right before any text is sent.
        val fields = BackgroundDisplay.expectFields(BackgroundDisplay.ElementExpect(7, 33, blankInput.role, blankInput.label, blankInput.viewId))
            .filter { it.second is String? }
            .associate { it.first to it.second as String? }
        assertEquals(blankInput, PrivilegedDisplayCore.identityOf(fields))
    }

    // ---- privileged side: observation ticket and revision ----------------------------

    @Test fun anObservationTicketOnlyMatchesItselfExactly() {
        assertTrue(PrivilegedDisplayCore.ticketOk("a1b2", "a1b2"))
        assertFalse("no stored ticket: nothing was completely read", PrivilegedDisplayCore.ticketOk(null, "a1b2"))
        assertFalse("no echoed ticket: an old client", PrivilegedDisplayCore.ticketOk("a1b2", null))
        assertFalse("a rotated or revoked ticket is stale", PrivilegedDisplayCore.ticketOk("a1b2", "zzzz"))
    }

    @Test fun onlyMergedProtocolClientsHandshake() {
        assertEquals("dsh-su-vdisplay-3", Proto.REVISION)
        assertTrue(PrivilegedDisplayCore.revisionOk(Proto.REVISION))
        assertFalse("the prior SU and upstream protocols are not interchangeable", PrivilegedDisplayCore.revisionOk("dsh-vdisplay-2"))
        assertFalse("a rev-1 client predates the mandatory identity fields", PrivilegedDisplayCore.revisionOk("dsh-vdisplay-1"))
        assertFalse(PrivilegedDisplayCore.revisionOk(null))
    }

    // ---- app side: the wire form -----------------------------------------------------

    @Test fun theIdentityTravelsWithEveryKeyAlwaysPresent() {
        val expect = BackgroundDisplay.ElementExpect(7, 33, "输入框", "", null)
        val fields = BackgroundDisplay.expectFields(expect)
        assertEquals(
            listOf(
                Proto.EXPECT_DISPLAY, Proto.EXPECT_WINDOW,
                Proto.EXPECT_ROLE, Proto.EXPECT_LABEL, Proto.EXPECT_VIEW_ID,
            ),
            fields.map { it.first },
        )
        // The privileged side consumes exactly these keys: the display/window pair plus
        // the identity triple, all present, nulls kept.
        val keys = fields.map { it.first }
        assertTrue(PrivilegedDisplayCore.identityKeys.all { it in keys })
        assertEquals(listOf(Proto.EXPECT_DISPLAY, Proto.EXPECT_WINDOW), keys - PrivilegedDisplayCore.identityKeys.toSet())
    }

    @Test fun theWireFormRoundTripsIntoThePrivilegedCheck() {
        for (identity in listOf(button, blankInput)) {
            // A Bundle maps a present key to its value, null included; Int fields stay Ints
            // and are simply not part of the identity triple the privileged side matches on.
            val expect = BackgroundDisplay.ElementExpect(7, 33, identity.role, identity.label, identity.viewId)
            val fields = BackgroundDisplay.expectFields(expect)
                .filter { it.second is String? }
                .associate { it.first to it.second as String? }
            val roundTripped = PrivilegedDisplayCore.identityOf(fields)
            assertNotNull(roundTripped)
            assertTrue(PrivilegedDisplayCore.identityMatches(roundTripped!!, identity))
        }
    }

    // ---- app side: expectations come from the current snapshot only ------------------

    @Test fun anExpectationNeedsTheSnapshotItsDisplayAndItsWindow() {
        val display = BackgroundDisplay(
            journal = BackgroundResourceJournal({ null }, { true }).apply { load() },
            mayUse = { true },
            admitUse = { action -> action(); true },
            channelProvider = { error("expectation building must not start a connection") },
        )
        assertNull("no snapshot yet", display.expectFor(1))

        setField(display, "lastElements", listOf(Element(1, "按钮", "确定", Region.MIDDLE, viewId = "com.a:id/ok")))
        assertNull("elements without a display binding are not addressable", display.expectFor(1))
        setField(display, "lastDisplayId", 7)
        assertNull("elements without a window binding are not addressable", display.expectFor(1))
        setField(display, "lastWindowId", 33)
        assertEquals(
            BackgroundDisplay.ElementExpect(7, 33, "按钮", "确定", "com.a:id/ok"),
            display.expectFor(1),
        )
        assertNull("numbers past the snapshot are not addressable", display.expectFor(2))
    }

    @Test fun anActionWithoutALiveTicketIsRefusedWithoutTouchingTheChannel() {
        val display = BackgroundDisplay(
            journal = BackgroundResourceJournal({ null }, { true }).apply { load() },
            mayUse = { true },
            admitUse = { action -> action(); true },
            channelProvider = { error("a refused action must not reach the privileged channel") },
        )
        setField(display, "targetReady", true)
        // The screen is "open" but nothing was completely observed: no ticket exists, so
        // every action is refused before any request is built.
        setField(display, "currentTarget", PhoneAppIdentity("example.opened", 10123))
        for (action in listOf(PhoneAction.Tap("b1", 1), PhoneAction.Tap("b1", 1, long = true), PhoneAction.Type("b1", 1, "hi"))) {
            val result = display.perform(action)
            assertFalse(result.ok)
            assertTrue(result.message.contains("重新观察"))
            assertNull(result.screen)
        }
        // With a live ticket but a snapshot that lacks the number, the identity gate
        // refuses next — still without building a request.
        setField(display, "observationTicket", "c0ffee")
        val result = display.perform(PhoneAction.Tap("b1", 1))
        assertFalse(result.ok)
        assertTrue(result.message.contains("重新观察"))
    }

    @Test fun invalidatingTheObservationDropsNumbersTheWindowAndTheTicket() {
        val display = BackgroundDisplay(
            journal = BackgroundResourceJournal({ null }, { true }).apply { load() },
            mayUse = { true },
            admitUse = { action -> action(); true },
            channelProvider = { error("invalidation must not start a connection") },
        )
        setField(display, "targetReady", true)
        setField(display, "lastScreenId", "b7")
        setField(display, "lastElements", listOf(Element(1, "按钮", "确定", Region.MIDDLE)))
        setField(display, "lastDisplayId", 7)
        setField(display, "lastWindowId", 33)
        setField(display, "observationTicket", "c0ffee")
        assertNotNull(display.target("b7", 1))
        assertNotNull(display.expectFor(1))

        display.invalidateObservation()

        assertNull(display.target("b7", 1))
        assertNull("the next write must need a fresh observation", display.expectFor(1))
        assertNull("no ticket survives an invalidation", fieldOf(display, "observationTicket"))

        // A fresh complete observation restores operability: new numbers, new ticket.
        display.applyObservation(
            id = "b8", partial = false, truncated = false, total = null, ticket = "fresh",
            elements = listOf(Element(1, "按钮", "确定", Region.MIDDLE)), displayId = 7, windowId = 33,
            appLabel = "示例", pkg = "example.opened", poor = false, portrait = true,
            keyboardUp = false, imageOmitted = false, image = null, imageMime = null,
        )
        assertNotNull("a fresh observation acts again", display.target("b8", 1))
        assertEquals("fresh", fieldOf(display, "observationTicket"))
    }

    // ---- the four observation outcomes (complete / truncated / cut / failed) ----------

    @Test fun anUnreadableObservationIsNotReportedAsAnEmptyScreen() {
        val display = BackgroundDisplay(
            journal = BackgroundResourceJournal({ null }, { true }).apply { load() },
            mayUse = { true },
            admitUse = { action -> action(); true },
            channelProvider = { error("snapshot handling must not start a connection") },
        )
        setField(display, "targetReady", true)
        setField(display, "lastScreenId", "b7")
        setField(display, "lastElements", listOf(Element(1, "按钮", "确定", Region.MIDDLE)))
        setField(display, "lastDisplayId", 7)
        setField(display, "lastWindowId", 33)
        setField(display, "observationTicket", "c0ffee")

        val snap = display.applyObservation(
            id = "b9", partial = true, truncated = false, total = null, ticket = null,
            elements = emptyList(), displayId = null, windowId = null,
            appLabel = "示例", pkg = "example.opened", poor = false, portrait = true,
            keyboardUp = false, imageOmitted = false, image = null, imageMime = null,
        )

        assertNull("old numbers are gone", display.target("b7", 1))
        assertNull("and the unreadable screen owns none either", display.target("b9", 1))
        assertTrue(snap.text.contains("重新观察"))
        assertFalse(snap.secure)
        assertNull("a failed read never leaves a writable ticket", fieldOf(display, "observationTicket"))
    }

    @Test fun anIncompleteCollectionIsViewOnlyAndNeverOperable() {
        val display = BackgroundDisplay(
            journal = BackgroundResourceJournal({ null }, { true }).apply { load() },
            mayUse = { true },
            admitUse = { action -> action(); true },
            channelProvider = { error("snapshot handling must not start a connection") },
        )
        setField(display, "targetReady", true)
        val elements = (1..5).map { Element(it, "按钮", "项 $it", Region.MIDDLE) }

        val snap = display.applyObservation(
            id = "b4", partial = true, truncated = false, total = null, ticket = null,
            elements = elements, displayId = 7, windowId = null,
            appLabel = "示例", pkg = "example.opened", poor = false, portrait = true,
            keyboardUp = false, imageOmitted = false, image = null, imageMime = null,
        )

        // The cut list stays readable, but without a window binding and a ticket none of
        // its numbers may be acted on.
        assertNotNull(display.target("b4", 5))
        assertNull("a budget-cut page hands out no operable numbers", display.expectFor(5))
        assertNull("no writable ticket from an incomplete read", fieldOf(display, "observationTicket"))
        assertTrue(snap.text.contains("暂不可操作"))
    }

    @Test fun aDisplayOnlyTruncationKeepsItsNumbersOperableAndSaysSo() {
        val display = BackgroundDisplay(
            journal = BackgroundResourceJournal({ null }, { true }).apply { load() },
            mayUse = { true },
            admitUse = { action -> action(); true },
            channelProvider = { error("snapshot handling must not start a connection") },
        )
        setField(display, "targetReady", true)
        val elements = (1..120).map { Element(it, "按钮", "项 $it", Region.MIDDLE) }

        val snap = display.applyObservation(
            id = "b3", partial = false, truncated = true, total = 250, ticket = "fresh",
            elements = elements, displayId = 7, windowId = 33,
            appLabel = "示例", pkg = "example.opened", poor = false, portrait = true,
            keyboardUp = false, imageOmitted = false, image = null, imageMime = null,
        )

        assertNotNull(display.target("b3", 120))
        assertEquals(
            "the display cap keeps the page fully operable",
            BackgroundDisplay.ElementExpect(7, 33, "按钮", "项 120", null),
            display.expectFor(120),
        )
        assertEquals("fresh", fieldOf(display, "observationTicket"))
        assertTrue(snap.text.contains("本页共 250 项"))
        assertFalse("truncation is not an incomplete collection", snap.text.contains("暂不可操作"))
    }

    @Test fun aCompleteObservationNeedsNoCaveat() {
        val display = BackgroundDisplay(
            journal = BackgroundResourceJournal({ null }, { true }).apply { load() },
            mayUse = { true },
            admitUse = { action -> action(); true },
            channelProvider = { error("snapshot handling must not start a connection") },
        )
        setField(display, "targetReady", true)
        val elements = listOf(Element(1, "按钮", "确定", Region.MIDDLE))

        val snap = display.applyObservation(
            id = "b2", partial = false, truncated = false, total = null, ticket = "fresh",
            elements = elements, displayId = 7, windowId = 33,
            appLabel = "示例", pkg = "example.opened", poor = false, portrait = true,
            keyboardUp = false, imageOmitted = false, image = null, imageMime = null,
        )

        assertNotNull(display.target("b2", 1))
        assertEquals(BackgroundDisplay.ElementExpect(7, 33, "按钮", "确定", null), display.expectFor(1))
        assertFalse(snap.text.contains("不完整"))
        assertFalse(snap.text.contains("重新观察"))
    }

    // ---- contract verdicts on response shapes ------------------------------------------

    @Test fun aResponseWithElementsButNoTicketBreaksTheContract() {
        assertTrue("elements without a ticket are a rev mismatch, not a screen", BackgroundDisplay.missingTicketViolation(hasCount = true, partial = false, ticket = null))
        assertFalse("a partial read legitimately carries no ticket", BackgroundDisplay.missingTicketViolation(hasCount = true, partial = true, ticket = null))
        assertFalse(BackgroundDisplay.missingTicketViolation(hasCount = true, partial = false, ticket = "fresh"))
        assertFalse("no elements: nothing to bind", BackgroundDisplay.missingTicketViolation(hasCount = false, partial = false, ticket = null))
    }

    @Test fun anyFailureAfterARequestWasSentInvalidatesTheCache() {
        assertFalse("success keeps everything", BackgroundDisplay.invalidatesCache(ok = true, dispatched = false, errorCode = null))
        // Conservative by contract: once the request was sent, every failure response —
        // a cancelled settle, a timeout, an unreachable device, even an unrelated
        // refusal — kills the cached observation and its ticket.
        for (code in listOf("cancelled", "timeout", "deviceUnavailable", "locked", "identityInsufficient", "staleSnapshot", "revisionMismatch")) {
            assertTrue(code, BackgroundDisplay.invalidatesCache(ok = false, dispatched = false, errorCode = code))
        }
        assertTrue("dispatched with unknown outcome: the cache dies", BackgroundDisplay.invalidatesCache(ok = false, dispatched = true, errorCode = "locked"))
    }

    @Test fun aTapRefusesWeakTargetsButARealViewIdStillCounts() {
        // The tap resolver applies the same sufficiency rule as writes: a blank label with
        // no view id cannot be re-verified after the screen changes, so it is not injected.
        assertFalse("blank label, no view id: tap refused", PrivilegedDisplayCore.writeIdentitySufficient(PrivilegedDisplayCore.Identity("按钮", "", null)))
        // An identifiable empty input (a real view id) stays usable for tap and text alike.
        assertTrue("empty box with a real view id stays tappable", PrivilegedDisplayCore.writeIdentitySufficient(PrivilegedDisplayCore.Identity("输入框", "", "com.a:id/q")))
        // The weak identity still travels on the wire untouched; the refusal is the core's.
        val fields = BackgroundDisplay.expectFields(BackgroundDisplay.ElementExpect(7, 33, "按钮", "", null))
            .filter { it.second is String? }
            .associate { it.first to it.second as String? }
        assertEquals(PrivilegedDisplayCore.Identity("按钮", "", null), PrivilegedDisplayCore.identityOf(fields))
    }

    @Test fun theKeyboardStateIsARealTriStateNeverAFakeDown() {
        val up = BackgroundTree.render("b1", null, true, true, emptyList(), background = true, poorTree = false)
        val down = BackgroundTree.render("b1", null, true, false, emptyList(), background = true, poorTree = false)
        val unknown = BackgroundTree.render("b1", null, true, null, emptyList(), background = true, poorTree = false)
        assertTrue("a real observation renders 键盘弹出", up.contains("键盘弹出"))
        assertTrue("a real observation renders 键盘收起", down.contains("键盘收起"))
        assertTrue("an unobservable IME renders 键盘状态未知", unknown.contains("键盘状态未知"))
        assertFalse("unknown must not claim 键盘收起", unknown.contains("键盘收起"))
    }

    private fun setField(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply {
            isAccessible = true
            set(target, value)
        }
    }

    private fun fieldOf(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
}
