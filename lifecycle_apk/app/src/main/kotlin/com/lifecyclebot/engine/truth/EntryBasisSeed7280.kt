package com.lifecyclebot.engine.truth

import com.lifecyclebot.network.PumpCurveKeys7269
import com.lifecyclebot.network.PumpFunDirectApi

/**
 * V5.0.7280 §A CAP OVER A SUPPLY IS A SEED, NOT A QUOTE.
 *
 * Intake prices a mint two ways when no feed has quoted it: a pump.fun
 * launch is `mcap / 1e9` (a protocol constant — exact), and anything else
 * with a known chain supply is `mcap / supply` (two measurements from two
 * sources — an inference). Both carried the label PUMP_FUN_BC_SYNTHETIC, so
 * the executor assumed a 1e9 supply and a bonding curve for mints that had
 * neither, and the synthesized pair let the seed validate itself as the
 * entry basis.
 *
 * 5.0.7279: 98sMhv entered QUALITY at $0.96 (a $683k cap over a 712k
 * supply) while two feeds quoted $90.8; the position printed 20.7 SOL of
 * unrealized on a 0.22 SOL ticket and the equity line read 33 SOL on a
 * 10 SOL book.
 *
 * The second case now has its own label, and [isCapDerived] recognises
 * both the new label and the old one on a mint with no curve evidence, so
 * rows written before this build are judged the same way.
 */
object EntryBasisSeed7280 {

    const val CHAIN_SUPPLY_CAP_SEED = "CHAIN_SUPPLY_CAP_SEED_7280"

    /** True when [source] is a cap-derived seed on a mint that is not a pump.fun curve. */
    fun isCapDerived(source: String?, mint: String): Boolean {
        val s = source?.trim().orEmpty()
        if (s == CHAIN_SUPPLY_CAP_SEED) return true
        if (s != "PUMP_FUN_BC_SYNTHETIC") return false
        val onCurve = try {
            PumpCurveKeys7269.keyFor(mint) != null || PumpFunDirectApi.isPumpFunMint(mint)
        } catch (_: Throwable) { false }
        return !onCurve
    }
}
