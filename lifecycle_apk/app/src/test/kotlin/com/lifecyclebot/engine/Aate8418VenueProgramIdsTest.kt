package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.OnChainDepth8038
import com.lifecyclebot.engine.truth.OnChainHolderConcentration7379
import com.lifecyclebot.network.ChainLaunchFeed8179
import com.lifecyclebot.network.HeliusCurveTape8354
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.8418 — Orca Whirlpool, PumpSwap and pump.fun program ids as published by their owners:
 * orca-so/whirlpools programs/whirlpool/src/lib.rs declare_id; pump-fun/pump-public-docs idl/pump.json and idl/pump_amm.json "address".
 */
class Aate8418VenueProgramIdsTest {
    private val orca = "whirLbMiicVdio4qvUfM5KAg6Ct8VwpYzGff3uctyCc"
    private val pumpFun = "6EF8rrecthR5Dkzon8Nwu78hRvfCKubJ14M5uBEwF6P"
    private val pumpSwap = "pAMMBay6oceH9fJKBRHGP5D4bD4sWpmSwMn52FMfXEA"

    @Test fun programIdsMatchTheOfficialSources() {
        assertEquals(pumpSwap, OnChainDepth8038.PUMP_AMM)
        assertEquals(pumpSwap, ChainLaunchFeed8179.PUMPSWAP_AMM_8179)
        assertEquals(pumpFun, HeliusCurveTape8354.PUMP_PROGRAM_8354)
        assertEquals("OrcaWhirlpool", OnChainDepth8038.venueName8418(orca))
        assertEquals("PumpCurve", OnChainDepth8038.venueName8418(pumpFun))
        assertEquals("PumpSwap", OnChainDepth8038.venueName8418(pumpSwap))
        assertTrue(OnChainHolderConcentration7379.VENUE_PROGRAMS_8418.containsAll(listOf(orca, pumpFun, pumpSwap)))
    }

    @Test fun vaultsOwnedByAProgramOwnedPoolAccountAreVenueHolders() {
        // Whirlpool vault authority = the whirlpool account; PumpSwap = the pool PDA; pump.fun = the bonding_curve PDA.
        // Each is an account owned by its program, so the holder is classed by its owner's program.
        val pool = "Czfq3xZZDmsdGdUyrNLtRhGc47cXcZtLG4crryfu44zE"   // Orca SOL/USDC whirlpool: a PDA (off curve)
        for (program in listOf(orca, pumpFun, pumpSwap)) {
            assertEquals(OnChainHolderConcentration7379.Holder8418.VENUE,
                OnChainHolderConcentration7379.classify8418(pool, program, true))
        }
    }
}
