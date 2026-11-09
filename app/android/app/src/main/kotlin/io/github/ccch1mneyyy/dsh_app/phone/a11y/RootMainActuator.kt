package io.github.ccch1mneyyy.dsh_app.phone.a11y

import io.github.ccch1mneyyy.dsh_app.phone.PhoneAction

/** The narrow Root input seam for the main screen; the owner supplies [io.github.ccch1mneyyy.dsh_app.phone.PhoneRegistry.rootMainInput]. */
fun interface RootMainInput {
    data class Request(
        val displayId: Int,
        val x: Int? = null,
        val y: Int? = null,
        val sx: Int? = null,
        val sy: Int? = null,
        val ex: Int? = null,
        val ey: Int? = null,
        val long: Boolean = false,
        val key: PhoneAction.GlobalKey? = null,
        val durationMs: Long = 350,
        /** Invoked at transport admission, immediately before writing input bytes. */
        val beforeSend: (() -> Boolean)? = null,
    )

    /** Honor [Request.beforeSend] after acquiring capacity; true only for a completed display0 event. */
    fun send(request: Request): Boolean
}

internal class RootMainActuator(
    private val input: RootMainInput?,
    private val guard: ((durationMs: Long, body: () -> Boolean) -> Boolean)? = null,
) {
    sealed interface Result {
        data object Unavailable : Result
        data class Rejected(val reason: String) : Result
        data object Accepted : Result
    }

    fun tapXY(x: Int, y: Int, long: Boolean, start: ((() -> Boolean) -> Boolean)? = null): Result {
        if (input == null) return Result.Unavailable
        if (Thread.currentThread().isInterrupted) return Result.Rejected("已停止，没有发送 Root 输入。")
        val accepted = sendGesture(RootMainInput.Request(displayId = 0, x = x, y = y, long = long),
            if (long) 950L else 200L, start)
        return if (accepted) Result.Accepted else Result.Rejected("Root 系统没有接受这次输入。")
    }

    fun key(key: PhoneAction.GlobalKey): Result {
        if (input == null) return Result.Unavailable
        if (Thread.currentThread().isInterrupted) return Result.Rejected("已停止，没有发送 Root 输入。")
        val accepted = try {
            input.send(RootMainInput.Request(displayId = 0, key = key))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (_: Exception) {
            false
        }
        return if (accepted) Result.Accepted else Result.Rejected("Root 系统没有执行这个按键。")
    }

    fun swipe(sx: Int, sy: Int, ex: Int, ey: Int, durationMs: Long = 350, start: ((() -> Boolean) -> Boolean)? = null): Result {
        if (input == null) return Result.Unavailable
        if (Thread.currentThread().isInterrupted) return Result.Rejected("已停止，没有发送 Root 输入。")
        val accepted = sendGesture(RootMainInput.Request(displayId = 0, sx = sx, sy = sy, ex = ex, ey = ey, durationMs = durationMs),
            durationMs + 150L, start)
        return if (accepted) Result.Accepted else Result.Rejected("Root 系统没有接受这次滚动。")
    }

    private fun sendGesture(request: RootMainInput.Request, durationMs: Long, start: ((() -> Boolean) -> Boolean)?): Boolean {
        // Only the admission claim visits main. The blocking privileged call remains on
        // this worker, and a queued transport cannot outlive the captured screen epoch.
        val admittedRequest = if (start == null) request else request.copy(beforeSend = {
            !Thread.currentThread().isInterrupted && start { true } && !Thread.currentThread().isInterrupted
        })
        val send = { !Thread.currentThread().isInterrupted && input?.send(admittedRequest) == true }
        return try {
            guard?.invoke(durationMs, send) ?: send()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (_: Exception) {
            false
        }
    }
}
