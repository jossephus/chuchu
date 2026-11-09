package com.jossephus.chuchu.plugins.rex

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RexBlockOwnershipTest {
    private val block = "block:ours"
    private val us = "client:phone"
    private val desktop = "client:desktop"

    @Test
    fun needsAClaimUntilTheServerNamesUsOwner() {
        val ownership = RexBlockOwnership(block)
        assertTrue(ownership.needsClaim)

        ownership.onClaimed(claimReply(block, us))
        assertFalse(ownership.needsClaim)
    }

    @Test
    fun anotherClientTakingTheBlockMeansWeMustClaimAgain() {
        val ownership = claimed()

        ownership.onEvent(ownerChanged(block, owner = desktop))
        assertTrue(ownership.needsClaim)

        ownership.onEvent(ownerChanged(block, owner = us))
        assertFalse(ownership.needsClaim)
    }

    @Test
    fun ignoresOwnerChangesOfOtherBlocks() {
        val ownership = claimed()

        ownership.onEvent(ownerChanged("block:other", owner = desktop))
        assertFalse(ownership.needsClaim)
    }

    private fun claimed() = RexBlockOwnership(block).apply { onClaimed(claimReply(block, us)) }

    private fun claimReply(blockId: String, owner: String) =
        JSONObject().put("blocks", JSONArray().put(JSONObject().put("block_id", blockId).put("owner", owner)))

    // Shaped like the server's events: a session_event wrapping block_owner_changed.
    private fun ownerChanged(blockId: String, owner: String) =
        JSONObject()
            .put("type", "session_event")
            .put(
                "payload",
                JSONObject()
                    .put("session_id", "session:s")
                    .put(
                        "event",
                        JSONObject()
                            .put("type", "block_owner_changed")
                            .put("payload", JSONObject().put("block_id", blockId).put("owner", owner).put("reason", "take")),
                    ),
            )
}
