package com.lifecyclebot.engine

/** V5.0.8931 — a crypto backing slot spent by an intent that never dispatched comes back; a fill keeps it. */
class Aate8931CryptoSlotTest {
    @org.junit.Test fun anUnfilledAdmitGivesItsSlotBack() {
        val b = com.lifecyclebot.engine.truth.CryptoSpotPaperBacking8788
        b.clearForTest8930()
        val now = System.currentTimeMillis()
        b.committedForTest8930("a1", now); b.committedForTest8930("a2", now + 1); b.committedForTest8930("a3", now + 2)
        org.junit.Assert.assertEquals(3, b.admitsForTest8930())
        b.intentEnded8930("a1", filled = false)
        org.junit.Assert.assertEquals("an unfilled admit: its slot back", 2, b.admitsForTest8930())
        b.intentEnded8930("a2", filled = true)
        org.junit.Assert.assertEquals("a fill keeps its slot", 2, b.admitsForTest8930())
        b.intentEnded8930("a2", filled = false)
        org.junit.Assert.assertEquals("a second end of the same intent changes nothing", 2, b.admitsForTest8930())
        b.intentEnded8930("never", filled = false)
        org.junit.Assert.assertEquals(1L, b.refundedForTest8930())
        b.clearForTest8930()
        val c = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalAssetEntryContract6551.kt").readText()
        org.junit.Assert.assertTrue(c.contains("if (!wasDispatched6647) try { CryptoSpotPaperBacking8788.intentEnded8930(intent.attemptId, filled = false) }"))
        org.junit.Assert.assertTrue(c.contains("CryptoSpotPaperBacking8788.intentEnded8930(intent.attemptId, filled = true)"))
        org.junit.Assert.assertTrue(java.io.File("../../docs/DATA_MAP.md").readText().contains("## 119. The crypto slot an unfilled intent spent (8931)"))
    }
}
