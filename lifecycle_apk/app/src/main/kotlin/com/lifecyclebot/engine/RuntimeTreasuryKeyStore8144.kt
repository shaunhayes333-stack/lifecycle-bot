package com.lifecyclebot.engine

import android.content.Context
import com.lifecyclebot.data.BotConfig
import com.lifecyclebot.data.ConfigStore

/**
 * V5.0.8144 — the treasury key write, out of TreasuryWalletManager's synchronized methods.
 *
 * RUNTIME SMOKE STALL (5.0.8117 → 5.0.8143). Every debug smoke since 8117 reached
 * SERVICE_BOOTSTRAP_PHASE_6516 phase=CORE_STORES_READY and stopped. The witness
 * read `serviceFailure=Undefined` (run 6047: `serviceFailure=VerifyError`): the
 * "Undefined" is ART's own verifier text (`copy1 vA<-vB type=Undefined cat=1`, a hard
 * class rejection) caught by the witness regex inside START_BLOCKED_SERVICE_BOOTSTRAP_FAILED_6516.
 * 8116 passed the same smoke; 8117 changed only one thing every class shares: BotConfig
 * gained a 188th field, so `BotConfig.copy$default` now takes 253 argument registers
 * (8116: 252) — three short of the 255 hard ceiling for a dex/JVM call.
 *
 * TreasuryWalletManager is the first class the bootstrap touches after
 * CORE_STORES_READY (BotService: `TreasuryWalletManager.init`, guarded only by
 * `catch (e: Exception)`, which a VerifyError — an Error — passes straight through),
 * and the only class in the app whose synchronized methods hold that 253-register
 * call with values live across it and a monitor to release on the exceptional edge.
 * The debug build (no R8) keeps the call as written; the release build passed its own
 * ART startup check, which is why only the smoke saw it.
 *
 * The copy now happens here: a plain, unsynchronized two-statement method with only
 * `ctx` live across the call. TreasuryWalletManager keeps its locking and its exact
 * behaviour — the same config row is written with the same key.
 */
internal object RuntimeTreasuryKeyStore8144 {

    /** Persist [keyB58] as the treasury private key on top of [cfg]. Throws exactly what ConfigStore.save throws. */
    fun persist8144(ctx: Context, cfg: BotConfig, keyB58: String) {
        ConfigStore.save(ctx, cfg.copy(treasuryPrivateKeyB58 = keyB58))
    }
}
