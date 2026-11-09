package io.github.ccch1mneyyy.dsh_app.phone.a11y

import io.github.ccch1mneyyy.dsh_app.phone.PhoneAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors

class RootMainActuatorTest {
    private class Recording(private val accepted: Boolean) : RootMainInput {
        var request: RootMainInput.Request? = null
        var calls = 0

        override fun send(request: RootMainInput.Request): Boolean {
            if (request.beforeSend?.invoke() == false) return false
            calls++
            this.request = request
            return accepted
        }
    }

    @Test fun nullInputKeepsA11yOnlyFallback() {
        assertEquals(RootMainActuator.Result.Unavailable, RootMainActuator(null).tapXY(3, 4, long = false))
    }

    @Test fun tapAlwaysTargetsDisplayZero() {
        val input = Recording(accepted = true)
        val result = RootMainActuator(input).tapXY(7, 9, long = true)

        assertEquals(RootMainActuator.Result.Accepted, result)
        assertEquals(1, input.calls)
        assertEquals(RootMainInput.Request(displayId = 0, x = 7, y = 9, long = true), input.request)
    }

    @Test fun keyAlwaysTargetsDisplayZero() {
        val input = Recording(accepted = true)
        val result = RootMainActuator(input).key(PhoneAction.GlobalKey.BACK)

        assertEquals(RootMainActuator.Result.Accepted, result)
        assertEquals(1, input.calls)
        assertEquals(RootMainInput.Request(displayId = 0, key = PhoneAction.GlobalKey.BACK), input.request)
    }

    @Test fun swipeAlwaysTargetsDisplayZero() {
        val input = Recording(accepted = true)
        val result = RootMainActuator(input).swipe(10, 20, 30, 40)

        assertEquals(RootMainActuator.Result.Accepted, result)
        assertEquals(1, input.calls)
        assertEquals(RootMainInput.Request(displayId = 0, sx = 10, sy = 20, ex = 30, ey = 40), input.request)
    }

    @Test fun swipeFailureIsRejectedWithoutSilentSuccess() {
        val result = RootMainActuator(Recording(accepted = false)).swipe(1, 2, 3, 4)
        assertTrue(result is RootMainActuator.Result.Rejected)
        assertEquals("Root 系统没有接受这次滚动。", (result as RootMainActuator.Result.Rejected).reason)
    }

    @Test fun failedInputIsRejectedWithoutSilentSuccess() {
        val result = RootMainActuator(Recording(accepted = false)).tapXY(1, 2, long = false)
        assertTrue(result is RootMainActuator.Result.Rejected)
        assertEquals("Root 系统没有接受这次输入。", (result as RootMainActuator.Result.Rejected).reason)
    }

    @Test fun keyFailureUsesExplicitFailureWording() {
        val result = RootMainActuator(Recording(accepted = false)).key(PhoneAction.GlobalKey.HOME)
        assertTrue(result is RootMainActuator.Result.Rejected)
        assertEquals("Root 系统没有执行这个按键。", (result as RootMainActuator.Result.Rejected).reason)
    }

    @Test fun requestHasNoDisplayFallbackField() {
        val request = RootMainInput.Request(displayId = 0, key = PhoneAction.GlobalKey.RECENTS)
        assertEquals(0, request.displayId)
        assertEquals(null, request.x)
        assertEquals(null, request.y)
        assertFalse(request.long)
    }

    @Test fun guardedTapSendsOnceThroughTheGuard() {
        val input = Recording(accepted = true)
        var guardCalls = 0
        val result = RootMainActuator(input) { _, body -> guardCalls++; body() }.tapXY(5, 6, long = false)

        assertEquals(RootMainActuator.Result.Accepted, result)
        assertEquals(1, input.calls)
        assertEquals(1, guardCalls)
    }

    @Test fun guardedSwipeSendsOnceThroughTheGuard() {
        val input = Recording(accepted = true)
        var guardCalls = 0
        val result = RootMainActuator(input) { _, body -> guardCalls++; body() }.swipe(1, 2, 3, 4)

        assertEquals(RootMainActuator.Result.Accepted, result)
        assertEquals(1, input.calls)
        assertEquals(1, guardCalls)
    }

    @Test fun guardFalseIsRejectedWithoutResend() {
        val input = Recording(accepted = true)
        var guardCalls = 0
        val result = RootMainActuator(input) { _, _ -> guardCalls++; false }.tapXY(1, 2, long = false)

        assertTrue(result is RootMainActuator.Result.Rejected)
        assertEquals(1, guardCalls)
        assertEquals(0, input.calls)
    }

    @Test fun guardThrowIsRejectedWithoutResend() {
        val input = Recording(accepted = true)
        val result = RootMainActuator(input) { _, _ -> throw IllegalStateException("guard") }.swipe(1, 2, 3, 4)

        assertTrue(result is RootMainActuator.Result.Rejected)
        assertEquals(0, input.calls)
    }

    @Test fun nullInputIsUnavailableBeforeTheGuardRuns() {
        var guardCalls = 0
        val result = RootMainActuator(null) { _, _ -> guardCalls++; true }.tapXY(1, 2, long = false)

        assertEquals(RootMainActuator.Result.Unavailable, result)
        assertEquals(0, guardCalls)
    }

    @Test fun keyDoesNotEnterTheTouchGuard() {
        val input = Recording(accepted = true)
        var guardCalls = 0
        val result = RootMainActuator(input) { _, _ -> guardCalls++; true }.key(PhoneAction.GlobalKey.HOME)

        assertEquals(RootMainActuator.Result.Accepted, result)
        assertEquals(0, guardCalls)
        assertEquals(1, input.calls)
    }

    @Test fun expiredCoordinateAdmissionDoesNotSendRootInput() {
        val input = Recording(accepted = true)
        var admissions = 0
        val result = RootMainActuator(input).tapXY(5, 6, long = true, start = {
            admissions++
            false
        })

        assertTrue(result is RootMainActuator.Result.Rejected)
        assertEquals(1, admissions)
        assertEquals(0, input.calls)
    }

    @Test fun rootFailureAfterAdmissionIsNotRetried() {
        val input = Recording(accepted = false)
        var admissions = 0
        val result = RootMainActuator(input).swipe(1, 2, 3, 4, start = { send ->
            admissions++
            send()
        })

        assertTrue(result is RootMainActuator.Result.Rejected)
        assertEquals(1, admissions)
        assertEquals(1, input.calls)
    }

    @Test fun coordinateSwipePreservesDurationAndInjectionGuard() {
        val input = Recording(accepted = true)
        var guardedDuration = 0L
        var inGuard = false
        val result = RootMainActuator(input) { duration, body ->
            guardedDuration = duration
            inGuard = true
            try { body() } finally { inGuard = false }
        }.swipe(1, 2, 3, 4, durationMs = 1_400, start = { send ->
            assertTrue(inGuard)
            send()
        })

        assertEquals(RootMainActuator.Result.Accepted, result)
        assertEquals(1_400L, input.request?.durationMs)
        assertEquals(1_550L, guardedDuration)
        assertEquals(1, input.calls)
    }

    @Test fun interruptedAdmissionPreservesCancellationWithoutSending() {
        val input = Recording(accepted = true)
        try {
            val result = RootMainActuator(input).tapXY(1, 2, long = false, start = {
                throw InterruptedException()
            })
            assertTrue(result is RootMainActuator.Result.Rejected)
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(0, input.calls)
        } finally {
            Thread.interrupted()
        }
    }

    @Test fun coordinateAdmissionVisitsMainWithoutMovingBlockingRootIoToMain() {
        val worker = Thread.currentThread()
        val main = Executors.newSingleThreadExecutor()
        var admissionThread: Thread? = null
        val input = RootMainInput { request ->
            assertSame("Root transport stays on the calling worker", worker, Thread.currentThread())
            checkNotNull(request.beforeSend).invoke()
        }
        try {
            val result = RootMainActuator(input).tapXY(1, 2, long = false, start = { claim ->
                val ticket = CoordinateDispatch(worker, { true }, { admit ->
                    admissionThread = Thread.currentThread()
                    admit()
                }, claim)
                main.execute(ticket)
                ticket.awaitStart(2_000)
            })
            assertEquals(RootMainActuator.Result.Accepted, result)
            assertNotSame(worker, admissionThread)
        } finally {
            main.shutdownNow()
        }
    }
}
