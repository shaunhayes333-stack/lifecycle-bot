package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.market.MarketSweep7297
import com.lifecyclebot.engine.truth.AdmissionBirth8144
import com.lifecyclebot.engine.truth.LaunchBirth8169
import com.lifecyclebot.engine.truth.PoolCreationTime7385
import com.lifecyclebot.engine.truth.TokenBirthHydrator7441
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Aate8417BirthDiscoveryTest {
    @Test fun jupiterPoolAnswerReachesLaunchScopeAsAnAgeFloorWithoutBecomingMintBirth() {
        val now = System.currentTimeMillis()
        val mint = "8417JupFloor".padEnd(44, 'F')
        val born = now - 8 * 3_600_000L
        val parse = MarketSweep7297.javaClass.getDeclaredMethod("parseJupiter", JSONArray::class.java, String::class.java).apply { isAccessible = true }
        val payload = JSONArray().put(JSONObject().put("id", mint).put("firstPool", JSONObject()
            .put("createdAt", java.time.Instant.ofEpochMilli(born).toString())))
        parse.invoke(MarketSweep7297, payload, "JUP_RECENT")
        val ts = TokenState(mint = mint, symbol = "OLD").apply { addedToWatchlistAt = now }
        val read = AdmissionBirth8144.read8144(AdmissionBirth8144.witnessFor8144(ts), now)
        assertEquals(AdmissionBirth8144.Kind8144.PROVEN_OLDER_THAN, read.kind)
        assertEquals(0L, read.birthMs)
        assertEquals(8 * 3_600_000L, read.minAgeMs)
        val window = LaunchBirth8169.sniperWindow8169(null, read, 180)
        assertEquals(AdmissionBirth8144.Window8144.PAST_WINDOW, window)
        assertEquals(setOf("QUALITY", "BLUECHIP"), LaunchBirth8169.scopeFor8169(setOf("PROJECT_SNIPER", "QUALITY", "BLUECHIP"), window))
        assertNull("ordinary pool is never a direct mint birth", LaunchBirth8169.launchBirth8169(mint, now))
        assertTrue("no queued RPC walk when old age is already proven", TokenBirthHydrator7441.establishedWitness8417(mint, now))
    }

    @Test fun recentPoolKeepsBirthHydrationAndCannotAdmitAnUnknownMintAsFresh() {
        val now = System.currentTimeMillis()
        val mint = "8417RecentPool".padEnd(44, 'G')
        PoolCreationTime7385.record(mint, now - 30_000L, "JUP_FIRST_POOL_8094")
        val ts = TokenState(mint = mint, symbol = "NEW").apply { addedToWatchlistAt = now }
        val read = AdmissionBirth8144.read8144(AdmissionBirth8144.witnessFor8144(ts), now)
        assertEquals(AdmissionBirth8144.Kind8144.PROVEN_OLDER_THAN, read.kind)
        assertEquals(AdmissionBirth8144.Window8144.UNKNOWN_DEFER, LaunchBirth8169.sniperWindow8169(null, read, 180))
        assertFalse(TokenBirthHydrator7441.establishedWitness8417(mint, now))
        assertNull(LaunchBirth8169.launchBirth8169(mint, now))
    }
}
