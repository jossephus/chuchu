package com.jossephus.chuchu.service.terminal

import com.jossephus.chuchu.service.multiplexer.HerdrAgentStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HerdrAgentNotifierTest {
    @Test
    fun notifiesWhenPaneBecomesBlockedInBackground() {
        assertTrue(
            HerdrAgentNotifier.shouldNotify(
                previousStatus = HerdrAgentStatus.Working,
                newStatus = HerdrAgentStatus.Blocked,
                foreground = false,
                enabled = true,
                lastPostedAt = null,
                now = 1_000L,
            ),
        )
    }

    @Test
    fun doesNotNotifyWithoutAttentionTransitionOrWhenForeground() {
        assertFalse(
            HerdrAgentNotifier.shouldNotify(
                previousStatus = HerdrAgentStatus.Done,
                newStatus = HerdrAgentStatus.Done,
                foreground = false,
                enabled = true,
                lastPostedAt = null,
                now = 1_000L,
            ),
        )
        assertFalse(
            HerdrAgentNotifier.shouldNotify(
                previousStatus = HerdrAgentStatus.Working,
                newStatus = HerdrAgentStatus.Done,
                foreground = true,
                enabled = true,
                lastPostedAt = null,
                now = 1_000L,
            ),
        )
    }

    @Test
    fun throttlesNotificationsForTheSamePane() {
        assertFalse(
            HerdrAgentNotifier.shouldNotify(
                previousStatus = HerdrAgentStatus.Working,
                newStatus = HerdrAgentStatus.Done,
                foreground = false,
                enabled = true,
                lastPostedAt = 1_000L,
                now = 30_999L,
            ),
        )
        assertTrue(
            HerdrAgentNotifier.shouldNotify(
                previousStatus = HerdrAgentStatus.Working,
                newStatus = HerdrAgentStatus.Done,
                foreground = false,
                enabled = true,
                lastPostedAt = 1_000L,
                now = 31_000L,
            ),
        )
    }

    @Test
    fun doesNotNotifyWhenDisabled() {
        assertFalse(
            HerdrAgentNotifier.shouldNotify(
                previousStatus = HerdrAgentStatus.Working,
                newStatus = HerdrAgentStatus.Blocked,
                foreground = false,
                enabled = false,
                lastPostedAt = null,
                now = 1_000L,
            ),
        )
    }
}
