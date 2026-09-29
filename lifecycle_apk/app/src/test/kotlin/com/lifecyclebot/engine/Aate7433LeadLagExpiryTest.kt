package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7433LeadLagExpiryTest {
    @Test fun leadLagSignalsCarryBirthTimeAndExpireAgainstIt() {
        val models = File("src/main/kotlin/com/lifecyclebot/v4/meta/MetaModels.kt").readText()
        val leadLag = File("src/main/kotlin/com/lifecyclebot/v4/meta/CrossAssetLeadLagAI.kt").readText()
        assertTrue(models.contains("val createdAtMs: Long = System.currentTimeMillis()"))
        assertTrue(leadLag.contains("now - entry.value.createdAtMs > ttlMs"))
        assertTrue(leadLag.contains("CROSS_ASSET_STALE_LINK_EXPIRED_7433"))
        assertTrue(leadLag.contains("activeLinks.remove(entry.key, entry.value)"))
        assertTrue(leadLag.contains("labelInc(\"CROSS_ASSET_STALE_LINK_EXPIRED_7433\")"))
    }
}
