package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LaneAuthority8577
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8711 — 8708 live: 17 of 17 admits at an UNKNOWN stage or with no setup bought the top; none won. */
class Aate8711StageUnreadTest {
    @Test fun anUnreadStageIsNotBoughtLive() {
        assertTrue(LaneAuthority8577.stageUnread8711(null))
        assertTrue(LaneAuthority8577.stageUnread8711(" "))
        assertTrue(LaneAuthority8577.stageUnread8711("unknown"))
        assertFalse(LaneAuthority8577.stageUnread8711("BASE"))
        assertFalse(LaneAuthority8577.stageUnread8711("FRESH_LAUNCH"))
        assertTrue(LaneAuthority8577.binding8577(LaneAuthority8577.LIVE_STAGE_UNREAD_8711))
        val s = File("src/main/kotlin/com/lifecyclebot/engine/truth/LaneAuthority8577.kt").readText()
        val fn = s.substringAfter("private fun memeEntryState8696(").substringBefore("\n    }\n")
        assertTrue("only the meme lanes, after the at-the-high and shape doors",
            fn.indexOf("if (l !in MEME_LANES_8582) return null") in 0 until fn.indexOf("if (stageUnread8711(stage)) return LIVE_STAGE_UNREAD_8711"))
        assertTrue(fn.indexOf("if (stageUnread8711(stage)) return LIVE_STAGE_UNREAD_8711") < fn.indexOf("stageLoses8626(stage"))
    }
}
