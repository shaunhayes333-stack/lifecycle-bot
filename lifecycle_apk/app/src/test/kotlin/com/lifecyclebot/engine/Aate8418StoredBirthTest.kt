package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.AdmissionBirth8144
import com.lifecyclebot.engine.truth.AdmissionBirth8144.Kind8144
import com.lifecyclebot.engine.truth.AdmissionBirth8144.Witness8144
import com.lifecyclebot.engine.truth.CanonicalTokenBirthTime7440
import com.lifecyclebot.engine.truth.LaunchBirth8169
import com.lifecyclebot.engine.truth.StoredBirth8418
import com.lifecyclebot.engine.truth.StoredBirth8418.Class8418
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * V5.0.8418 — births stored before InitializeMint proof was required are kept but read back as a HISTORY_FLOOR and
 * re-verified; pump.fun's created_timestamp is an observation and a chain proof wins when present.
 */
class Aate8418StoredBirthTest {

    @Before fun reset() { StoredBirth8418.resetForTest8418() }

    @Test fun aStoredBirthIsTheBirthOnlyWithMintInitProof() {
        val t = 1_760_000_000_000L
        assertEquals(Class8418.PROVEN_BIRTH, StoredBirth8418.classify8418(t, StoredBirth8418.PROOF_MINT_INIT_8418))
        assertEquals(Class8418.HISTORY_FLOOR, StoredBirth8418.classify8418(t, StoredBirth8418.PROOF_NONE_8418))
        assertEquals(Class8418.NONE, StoredBirth8418.classify8418(0L, StoredBirth8418.PROOF_MINT_INIT_8418))
        assertEquals(Class8418.NONE, StoredBirth8418.classify8418(5L, StoredBirth8418.PROOF_NONE_8418))
        assertTrue(StoredBirth8418.usableAsBirth8418(t, 1))
        assertFalse(StoredBirth8418.usableAsBirth8418(t, 0))
    }

    @Test fun anUnprovenStoredBirthBecomesAFloorAndIsQueuedOnceNeverDeleted() {
        val mint = "Aate8418StoredBirthMintAAAAAAAAAAAAAAAAAAA"
        val now = System.currentTimeMillis()
        val stored = now - 2L * 60_000L          // two minutes ago, saved under the old oldest-signature rule
        val asked = ArrayList<String>()
        assertEquals(Class8418.HISTORY_FLOOR, StoredBirth8418.onLoaded8418(mint, stored, 0) { asked.add(it) })
        assertEquals(listOf(mint), asked)
        assertTrue(StoredBirth8418.queued8418(mint))
        // Loading again (lazy disk read, the resolver) does not queue it twice.
        StoredBirth8418.onLoaded8418(mint, stored, 0) { asked.add(it) }
        assertEquals(1, asked.size)
        // It is a lower bound of the age: the token existed then, nothing says it was born then.
        assertEquals(stored, AdmissionBirth8144.existenceFloorMs8417(mint))
        val r = AdmissionBirth8144.read8144(Witness8144(historyFloorMs = stored), now)
        assertEquals(Kind8144.PROVEN_OLDER_THAN, r.kind)
        assertEquals(0L, r.birthMs)
        assertEquals(stored, now - r.minAgeMs)
        // A proven row is not queued.
        val provenMint = "Aate8418StoredBirthMintBBBBBBBBBBBBBBBBBBB"
        assertEquals(Class8418.PROVEN_BIRTH, StoredBirth8418.onLoaded8418(provenMint, stored, 1) { asked.add(it) })
        assertEquals(1, asked.size)
        assertFalse(StoredBirth8418.queued8418(provenMint))
    }

    @Test fun theChainProofReplacesAnObservationAndIsNeverReplacedByOne() {
        val obs = 1_760_000_000_000L
        val proof = obs + 3_000L
        // A proven birth replaces an unproven one, even a (wrongly) earlier observation.
        assertEquals(proof to 1, StoredBirth8418.merge8418(obs, 0, proof, true))
        // An observation does not touch a proven row (the caller's earliest-observation rule applies only while unproven).
        assertNull(StoredBirth8418.merge8418(proof, 1, obs, false))
        // A later proof does not replace an earlier proof; an earlier one does.
        assertNull(StoredBirth8418.merge8418(proof, 1, proof + 10L, true))
        assertEquals((proof - 10L) to 1, StoredBirth8418.merge8418(proof, 1, proof - 10L, true))
        assertNull(StoredBirth8418.merge8418(0L, 0, 5L, true))
    }

    @Test fun pumpFunCreatedTimestampIsAnObservationTheChainProofWins() {
        val now = 1_760_000_000_000L
        val pumpApi = now - 60_000L
        val chainInit = now - 55_000L
        // Alone, the pump.fun record is a direct observation of the launch.
        val alone = AdmissionBirth8144.read8144(Witness8144(pumpRecordMs = pumpApi), now)
        assertEquals("PUMP_COIN_RECORD", alone.source)
        assertEquals(pumpApi, alone.birthMs)
        // With the mint's initialising transaction held, that is the birth.
        val withInit = AdmissionBirth8144.read8144(Witness8144(pumpRecordMs = pumpApi, chainMintInitMs = chainInit), now)
        assertEquals("CHAIN_MINT_INIT", withInit.source)
        assertEquals(chainInit, withInit.birthMs)
        val withSig = AdmissionBirth8144.read8144(Witness8144(pumpRecordMs = pumpApi, oldestSignatureMs = chainInit), now)
        assertEquals("OLDEST_SIGNATURE", withSig.source)
        // The canonical resolver's chain-proof read: earliest proven witness, null with none.
        val c = CanonicalTokenBirthTime7440.chainProofOf8418(0L, chainInit, 0L, now)
        assertNotNull(c)
        assertEquals(chainInit, c!!.birthMs)
        assertNull(CanonicalTokenBirthTime7440.chainProofOf8418(0L, 0L, 0L, now))
        assertEquals(chainInit - 1L, CanonicalTokenBirthTime7440.chainProofOf8418(chainInit - 1L, chainInit, 0L, now)!!.birthMs)
    }

    @Test fun launchBirthDropsThePumpRecordOnceTheMintInitIsKnown() {
        val mint = "Aate8418LaunchBirthMintAAAAAAAAAAAAAAApump"
        val now = System.currentTimeMillis()
        AdmissionBirth8144.notePumpCoinRecord8144(mint, now - 90_000L)
        assertEquals("PUMP_COIN_RECORD", LaunchBirth8169.launchBirth8169(mint, now)?.source)
        AdmissionBirth8144.noteChainMintInit8179(mint, now - 80_000L)
        val b = LaunchBirth8169.launchBirth8169(mint, now)
        assertEquals("CHAIN_MINT_INIT", b?.source)
        assertEquals(now - 80_000L, b?.birthMs)
    }
}
