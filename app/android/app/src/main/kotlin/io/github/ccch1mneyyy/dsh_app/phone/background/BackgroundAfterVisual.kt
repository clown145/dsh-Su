package io.github.ccch1mneyyy.dsh_app.phone.background

/** Versioned attachment only: never changes the dispatched action's acknowledgement. */
internal object BackgroundAfterVisual {
    fun supported(value: Any?): Boolean = value is Int && value == 1

    fun validRequest(op: String?, present: Boolean, value: Any?): Boolean = !present ||
        supported(value) && op in setOf(Proto.OP_TAP, Proto.OP_SCROLL, Proto.OP_KEY, Proto.OP_TEXT, Proto.OP_COORDINATE)

    // No ACK, target identity, coordinate nonce/targets, or pixels replace the original reply.
    // The legacy tree projection includes the fresh element ticket and its window binding.
    val legacyKeys = setOf(Proto.DISPLAY_ID, Proto.FG_PKG, Proto.PORTRAIT, Proto.APP_LABEL,
        Proto.SECURE, Proto.POOR, Proto.COUNT, Proto.FRAME_NOT_READY, Proto.IMAGE_OMITTED,
        Proto.KEYBOARD_UP, Proto.A_ROLE, Proto.A_LABEL, Proto.A_VIEW_ID, Proto.A_STATES,
        Proto.A_FLAGS, Proto.A_REGION, Proto.A_INDENT, Proto.A_BOUNDS, Proto.ELEMENT_OVERFLOW,
        Proto.TREE_COMPLETE, Proto.COORDINATE_SCOPE_VERSION, Proto.COORDINATE_SCOPE_KNOWN,
        Proto.VISUAL_CHANGE, Proto.WINDOW, Proto.OBS_TICKET, Proto.PARTIAL, Proto.TRUNCATED,
        Proto.TOTAL, Proto.KEYBOARD_UNKNOWN)
}
