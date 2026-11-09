package io.github.ccch1mneyyy.dsh_app.notify

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class NotificationPostsTest {
    @Test fun inFlightApprovalRedeliveryKeepsResponseOwnerUntilItFinishes() {
        val posts = NotificationPosts<String>()
        val action = posts.begin("approval")
        val redelivery = posts.begin("approval", action)
        assertSame(action, redelivery)
        assertTrue(posts.current(action))
        // Completion retires the action as well as a redelivery still waiting
        // for the Root permission gate; it cannot replace the result card.
        posts.cancel("approval") {}
        assertEquals(ShowOutcome.CANCELLED, posts.finish(redelivery, true) {
            throw AssertionError("completed approval was posted again")
        })
        val next = posts.begin("approval", action)
        assertNotSame(action, next)
        assertTrue(posts.current(next))
        assertFalse(posts.current(action))
    }

    @Test fun cancelThenSameKeyReplacementCannotResurrectOldWaiter() {
        val posts = NotificationPosts<String>()
        val old = posts.begin("same")
        val waiting = CountDownLatch(1)
        val release = CountDownLatch(1)
        val posted = mutableListOf<String>()
        val outcome = AtomicReference<ShowOutcome>()
        val worker = Thread {
            outcome.set(posts.awaitAndPost(old, { current ->
                waiting.countDown()
                assertTrue(release.await(2, TimeUnit.SECONDS))
                current()
            }) { posted += "old"; true })
        }
        worker.start()
        assertTrue(waiting.await(2, TimeUnit.SECONDS))
        posts.cancel("same") {}
        val fresh = posts.begin("same")
        release.countDown(); worker.join(2000)
        assertEquals(ShowOutcome.CANCELLED, outcome.get())
        assertEquals(ShowOutcome.DELIVERED, posts.awaitAndPost(fresh, { true }) { posted += "fresh"; true })
        assertEquals(listOf("fresh"), posted)
    }
    @Test fun cancelAllAndGroupRetireEveryMatchingPendingTicket() {
        val posts = NotificationPosts<Pair<String, String>>()
        val a = posts.begin("a" to "x")
        val b = posts.begin("b" to "y")
        posts.cancelWhere({ it.first == "a" }) {}
        assertEquals(ShowOutcome.CANCELLED, posts.finish(a, true) { throw AssertionError("cancelled group posted") })
        assertTrue(posts.current(b))
        posts.cancelAll {}
        assertEquals(ShowOutcome.CANCELLED, posts.finish(b, true) { throw AssertionError("cancelAll posted") })
    }
    @Test fun cancelWinsBeforeCommitAndDenialRemainsDistinct() {
        val posts = NotificationPosts<String>()
        val cancelled = posts.begin("x")
        assertEquals(ShowOutcome.CANCELLED, posts.awaitAndPost(cancelled, {
            posts.cancel("x") {}
            true
        }) { throw AssertionError("late post") })
        val denied = posts.begin("x")
        assertEquals(ShowOutcome.DENIED, posts.awaitAndPost(denied, { false }) { throw AssertionError("denied post") })
    }
    @Test fun atomicPostThenCancelLeavesNoNotification() {
        val posts = NotificationPosts<String>()
        val ticket = posts.begin("x")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelling = CountDownLatch(1)
        var active = false
        val post = Thread { posts.finish(ticket, true) {
            entered.countDown()
            assertTrue(release.await(2, TimeUnit.SECONDS))
            active = true
            true
        } }
        post.start(); assertTrue(entered.await(2, TimeUnit.SECONDS))
        val cancel = Thread { cancelling.countDown(); posts.cancel("x") { active = false } }
        cancel.start(); assertTrue(cancelling.await(2, TimeUnit.SECONDS))
        release.countDown(); post.join(2000); cancel.join(2000)
        assertFalse(active)
        assertFalse(posts.current(ticket))
    }
    @Test fun postGateRefusalAtCommitIsDeniedRatherThanDelivered() {
        val posts = NotificationPosts<String>()
        val ticket = posts.begin("x")
        assertEquals(ShowOutcome.DENIED, posts.awaitAndPost(ticket, { true }) { false })
        assertFalse(posts.current(ticket))
    }
    @Test fun unlockRepostIsReadOnlyAndHonorsCancellationAndActualActiveState() {
        val posts = NotificationPosts<String>()
        val ticket = posts.begin("x")
        var shown = 0
        posts.repost(ticket, { false }, { true }) { shown++ }
        posts.repost(ticket, { true }, { false }) { shown++ }
        assertEquals(0, shown)
        posts.repost(ticket, { true }, { true }) { shown++ }
        posts.cancel("x") {}
        posts.repost(ticket, { true }, { true }) { shown++ }
        assertEquals(1, shown)
    }
}
