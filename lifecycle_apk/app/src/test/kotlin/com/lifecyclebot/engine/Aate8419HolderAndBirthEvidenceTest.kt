package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.market.LaneHunter7297
import com.lifecyclebot.engine.market.MarketSweep7297
import com.lifecyclebot.engine.truth.AdmissionBirth8144
import com.lifecyclebot.engine.truth.CanonicalTokenBirthTime7440
import com.lifecyclebot.engine.truth.HolderShare8419
import com.lifecyclebot.engine.truth.LaunchBirth8169
import com.lifecyclebot.engine.truth.OnChainHolderConcentration7379
import com.lifecyclebot.engine.truth.OnChainHolderConcentration7379.Holder8418
import com.lifecyclebot.engine.truth.OnChainHolderConcentration7379.Row8418
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * V5.0.8419 — reviewer items 1 (holder concentration from a truncated list) and 2 (mint birth / pool creation / first
 * observation keep separate meanings on the entry path).
 */
class Aate8419HolderAndBirthEvidenceTest {

    private fun wallet(i: Int) = "W8419".padEnd(40, 'a' + (i % 26)) + "%04d".format(i)

    // ── item 1: the on-chain verdict divides by the verified supply, never by a row count or the rows' total ──

    @Test fun twenty_rows_whose_top_ten_hold_ninety_percent_read_ninety_percent() {
        val supply = BigInteger.valueOf(10_000L)
        val rows = (0 until 10).map { Row8418(wallet(it), BigInteger.valueOf(900L), Holder8418.WALLET) } +
            (10 until 20).map { Row8418(wallet(it), BigInteger.valueOf(50L), Holder8418.WALLET) }
        assertEquals(20, rows.size)
        val v = OnChainHolderConcentration7379.verdict8418(supply, rows, listTruncated = true)!!
        assertEquals(90.0, v.pct, 1e-9)
        assertNotEquals("never 1/11 of anything", 9.09, v.pct, 0.01)
        assertTrue("twenty visible holders complete the top ten even on a capped list", v.complete)
        assertEquals(20, v.holders)
        val decisive = OnChainHolderConcentration7379.decisive8418(v)
        assertNotNull(decisive)
        assertEquals(90.0, decisive!!, 1e-9)
        assertTrue("a real >= 80 % concentration crosses the hard-block line", decisive > OnChainHolderConcentration7379.LOWER_BOUND_DECISIVE_PCT_8418)
    }

    @Test fun truncated_list_short_of_ten_holders_is_unknown_never_a_small_number() {
        val supply = BigInteger.valueOf(10_000L)
        val rows = (0 until 9).map { Row8418(wallet(it), BigInteger.valueOf(300L), Holder8418.WALLET) } +
            (9 until 20).map { Row8418("venue$it", BigInteger.valueOf(600L), Holder8418.VENUE) }
        assertEquals(20, rows.size)
        val v = OnChainHolderConcentration7379.verdict8418(supply, rows, listTruncated = true)!!
        assertEquals(27.0, v.pct, 1e-9)
        assertFalse("nine holders on a capped list cannot complete the top ten", v.complete)
        assertNull("UNKNOWN, not 27 %", OnChainHolderConcentration7379.decisive8418(v))
        // The same nine holders on an UNtruncated list are the whole population: complete and decisive.
        val full = OnChainHolderConcentration7379.verdict8418(supply, rows, listTruncated = false)!!
        assertTrue(full.complete)
        assertEquals(27.0, OnChainHolderConcentration7379.decisive8418(full)!!, 1e-9)
    }

    @Test fun truncated_list_already_past_the_hard_block_line_still_blocks() {
        val supply = BigInteger.valueOf(10_000L)
        val rows = (0 until 9).map { Row8418(wallet(it), BigInteger.valueOf(950L), Holder8418.WALLET) } +
            (9 until 20).map { Row8418("venue$it", BigInteger.valueOf(100L), Holder8418.VENUE) }
        val v = OnChainHolderConcentration7379.verdict8418(supply, rows, listTruncated = true)!!
        assertFalse(v.complete)
        assertEquals(85.5, v.pct, 1e-9)
        val decisive = OnChainHolderConcentration7379.decisive8418(v)
        assertNotNull("a lower bound above 80 % is decisive on its own", decisive)
        assertTrue(decisive!! > 80.0)
    }

    @Test fun sentiment_concentration_reads_the_verified_figure_and_never_fabricates_fifty() {
        assertEquals(0.0, HolderShare8419.sentimentConcentration8419(null), 0.0)
        assertEquals(0.0, HolderShare8419.sentimentConcentration8419(Double.NaN), 0.0)
        assertEquals(0.0, HolderShare8419.sentimentConcentration8419(-1.0), 0.0)
        assertEquals(85.5, HolderShare8419.sentimentConcentration8419(85.5), 1e-9)
        assertEquals(100.0, HolderShare8419.sentimentConcentration8419(140.0), 1e-9)
        assertNotEquals(50.0, HolderShare8419.sentimentConcentration8419(null), 0.0)
    }

    @Test fun amount_only_holder_rows_are_unknown_not_a_clean_zero() {
        val amountOnly = JSONArray()
        repeat(20) { i -> amountOnly.put(JSONObject().put("owner", wallet(i)).put("amount", "900000000").put("decimals", 6).put("rank", i + 1)) }
        assertEquals(HolderShare8419.UNKNOWN_8419, HolderShare8419.top10PctFromRows8419(amountOnly), 0.0)
        assertEquals(HolderShare8419.UNKNOWN_8419, HolderShare8419.top10PctFromRows8419(null), 0.0)
        assertEquals(HolderShare8419.UNKNOWN_8419, HolderShare8419.top10PctFromRows8419(JSONArray()), 0.0)
        val stated = JSONArray()
        repeat(20) { i -> stated.put(JSONObject().put("owner", wallet(i)).put("percentage", if (i < 10) 9.0 else 0.5)) }
        assertEquals(90.0, HolderShare8419.top10PctFromRows8419(stated), 1e-9)
        val pct = JSONArray().put(JSONObject().put("pct", 60.0)).put(JSONObject().put("pct", 25.0))
        assertEquals(85.0, HolderShare8419.top10PctFromRows8419(pct), 1e-9)
    }

    // ── item 2: a pool age is a lower bound, never the birth the sniper hunts on ──

    private fun row(mint: String, ageHours: Double, observedAtMs: Long) = MarketSweep7297.Row(
        mint = mint, symbol = "S", name = "n", priceUsd = 0.001, mcapUsd = 20_000.0, liquidityUsd = 5_000.0,
        volumeH1Usd = 1_000.0, volumeH24Usd = 2_000.0, priceChangeH1Pct = 10.0, txCountH1 = 40, holders = 0,
        organicScore = 0.0, verified = false, ageHours = ageHours, providers = setOf("DEXSCREENER"), observedAtMs8169 = observedAtMs,
    )

    @Test fun sweep_row_pool_age_is_not_a_birth_for_the_sniper_hunter() {
        val mint = "8419PoolAge".padEnd(44, 'P')
        val now = System.currentTimeMillis()
        val poolOnly = MarketSweep7297.merge(listOf(row(mint, 0.02, now)))   // a 72 s old POOL, birth unknown
        assertEquals(1, poolOnly.size)
        assertFalse(poolOnly[0].birthProven8419)
        assertEquals(0.02, poolOnly[0].ageHours, 1e-12)
        assertFalse("a young pool of a mint with no proven birth is not a sniper launch", LaneHunter7297.sniperAgeFits8419(poolOnly[0].ageHours, poolOnly[0].birthProven8419))
        val born = MarketSweep7297.merge(listOf(row(mint, 0.02, now))) { 0.02 }   // the chain-proven birth, 72 s ago
        assertTrue(born[0].birthProven8419)
        assertTrue(LaneHunter7297.sniperAgeFits8419(born[0].ageHours, born[0].birthProven8419))
        val old = MarketSweep7297.merge(listOf(row(mint, 0.02, now))) { 0.2 }   // proven birth 12 min ago: the merge takes the birth
        assertEquals(0.2, old[0].ageHours, 1e-12)
        assertTrue(old[0].birthProven8419)
        assertFalse("past the 3-minute window", LaneHunter7297.sniperAgeFits8419(old[0].ageHours, old[0].birthProven8419))
        assertFalse("unknown age never fits", LaneHunter7297.sniperAgeFits8419(0.0, true))
    }

    @Test fun create_frame_is_the_sniper_birth_and_a_later_pool_never_rejuvenates_it() {
        val now = System.currentTimeMillis()
        val mint = "8419CreateFrame".padEnd(40, 'C') + "pump"
        val createdAt = now - 40_000L
        AdmissionBirth8144.noteCreateFrame8144(mint, createdAt)
        val ts = TokenState(mint = mint, symbol = "NEW").apply { addedToWatchlistAt = now - 1_000L }
        val read = AdmissionBirth8144.read8144(AdmissionBirth8144.witnessFor8144(ts), now)
        assertEquals(AdmissionBirth8144.Kind8144.KNOWN, read.kind)
        assertEquals("CREATE_FRAME", read.source)
        assertEquals(createdAt, read.birthMs)
        assertEquals(40_000L, read.minAgeMs)
        assertEquals(AdmissionBirth8144.Window8144.IN_WINDOW, LaunchBirth8169.sniperWindow8169(null, read, 180))
        // The canonical resolver the sniper reads (launchAgeMs7767 -> resolve) holds the same create-event birth.
        assertEquals(createdAt, CanonicalTokenBirthTime7440.peekBirthMs8418(mint, now))
        val resolved = CanonicalTokenBirthTime7440.resolve(mint, now)
        assertNotNull(resolved)
        assertEquals(createdAt, resolved!!.birthMs)
        assertEquals(CanonicalTokenBirthTime7440.Source.PUMP_CREATE_EVENT, resolved.source)
        assertEquals(createdAt, FreshLaunchIntake8303.createBirthMs8303(mint))
        // A Raydium pool created 10 s ago is a FLOOR only: the birth stays the create frame and the age is not reset.
        AdmissionBirth8144.notePairCreated8169(mint, "raydium", now - 10_000L)
        val after = AdmissionBirth8144.read8144(AdmissionBirth8144.witnessFor8144(ts), now)
        assertEquals(AdmissionBirth8144.Kind8144.KNOWN, after.kind)
        assertEquals("CREATE_FRAME", after.source)
        assertEquals(createdAt, CanonicalTokenBirthTime7440.peekBirthMs8418(mint, now))
        // And a pool alone (another mint) is PROVEN_OLDER_THAN, never KNOWN.
        val poolMint = "8419PoolOnly".padEnd(44, 'Q')
        AdmissionBirth8144.notePairCreated8169(poolMint, "raydium", now - 10_000L)
        val poolRead = AdmissionBirth8144.read8144(AdmissionBirth8144.witnessFor8144(TokenState(mint = poolMint, symbol = "P")), now)
        assertEquals(AdmissionBirth8144.Kind8144.PROVEN_OLDER_THAN, poolRead.kind)
        assertEquals(0L, poolRead.birthMs)
        assertNull(CanonicalTokenBirthTime7440.peekBirthMs8418(poolMint, now))
    }
}
