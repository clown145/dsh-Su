package io.github.ccch1mneyyy.dsh_app.notify

internal enum class ShowOutcome { DELIVERED, DENIED, CANCELLED }

/** Root waits run outside this lock; posting and cancellation use the same commit boundary. */
internal class NotificationPosts<K> {
    class Ticket<K> internal constructor(val key: K)
    private val guard = Any()
    private val current = HashMap<K, Ticket<K>>()

    fun begin(key: K, reuse: Ticket<K>? = null): Ticket<K> = synchronized(guard) {
        if (reuse != null && current[key] === reuse) reuse else Ticket(key).also { current[key] = it }
    }
    fun current(ticket: Ticket<K>): Boolean = synchronized(guard) { current[ticket.key] === ticket }
    fun finish(ticket: Ticket<K>, granted: Boolean, post: () -> Boolean): ShowOutcome = synchronized(guard) {
        if (current[ticket.key] !== ticket) return ShowOutcome.CANCELLED
        if (!granted) {
            current.remove(ticket.key)
            return ShowOutcome.DENIED
        }
        if (post()) ShowOutcome.DELIVERED else {
            current.remove(ticket.key)
            ShowOutcome.DENIED
        }
    }
    fun awaitAndPost(ticket: Ticket<K>, prepare: (() -> Boolean) -> Boolean, post: () -> Boolean): ShowOutcome {
        val granted = current(ticket) && runCatching { prepare { current(ticket) } }.getOrDefault(false)
        return finish(ticket, granted, post)
    }
    fun repost(ticket: Ticket<K>, allowed: () -> Boolean, active: () -> Boolean, post: () -> Unit) = synchronized(guard) {
        if (current[ticket.key] === ticket && allowed() && active()) post()
    }
    fun cancel(key: K, remove: () -> Unit) = synchronized(guard) {
        current.remove(key)
        remove()
    }
    fun cancelWhere(matches: (K) -> Boolean, remove: () -> Unit) = synchronized(guard) {
        current.keys.removeAll(matches)
        remove()
    }
    fun cancelAll(remove: () -> Unit) = synchronized(guard) {
        current.clear()
        remove()
    }
    fun <T> locked(action: () -> T): T = synchronized(guard) { action() }
}
