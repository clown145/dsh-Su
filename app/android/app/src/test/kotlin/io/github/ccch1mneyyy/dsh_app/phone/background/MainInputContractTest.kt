package io.github.ccch1mneyyy.dsh_app.phone.background

import io.github.ccch1mneyyy.dsh_app.phone.PhoneAction
import io.github.ccch1mneyyy.dsh_app.phone.PhoneScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainInputContractTest {
    @Test fun explicitMainTargetsKeepTheirScreenAndRejectBackgroundTargets() {
        assertEquals(
            MainInputCommand.TapXY(120, 240),
            mainInputCommand(PhoneAction.TapXY("s7", 120, 240)),
        )
        assertEquals(
            MainInputCommand.Key("back"),
            mainInputCommand(PhoneAction.Key("s8", PhoneAction.GlobalKey.BACK)),
        )
        assertNull(mainInputCommand(PhoneAction.TapXY("b1", 120, 240)))
        assertNull(mainInputCommand(PhoneAction.Key("b1", PhoneAction.GlobalKey.BACK)))
        assertNull(mainInputCommand(PhoneAction.Scroll("s7", null, PhoneAction.Direction.DOWN)))
        assertNull(mainInputCommand(PhoneAction.Scroll("b1", null, PhoneAction.Direction.DOWN)))
        assertNull(mainInputCommand(PhoneAction.Tap("s1", 1)))
    }

    @Test fun mainTargetsUseTheExistingScreenPrefixContract() {
        assertEquals(PhoneScreen.MAIN, PhoneScreen.ofScreenId("s7"))
        assertEquals(PhoneScreen.BACKGROUND, PhoneScreen.ofScreenId("b1"))
    }

    @Test fun onlyFreshSuInstallsDefaultToRoot() {
        assertEquals("root", initialBackgroundMode(null, rootRuntime = true))
        assertEquals("off", initialBackgroundMode(null, rootRuntime = false))
        for (rootRuntime in listOf(true, false)) {
            assertEquals("off", initialBackgroundMode("off", rootRuntime))
            assertEquals("adb", initialBackgroundMode("adb", rootRuntime))
        }
    }

    @Test fun longPressIsPreservedAndSwipeCannotBecomeATap() {
        assertEquals(MainInputCommand.TapXY(120, 240, long = true),
            mainInputCommand(PhoneAction.TapXY("s7", 120, 240, gesture = PhoneAction.CoordinateGesture.LONG)))
        assertNull(mainInputCommand(PhoneAction.TapXY("s7", 120, 240,
            gesture = PhoneAction.CoordinateGesture.SWIPE, x2 = 200, y2 = 400)))
    }
}
