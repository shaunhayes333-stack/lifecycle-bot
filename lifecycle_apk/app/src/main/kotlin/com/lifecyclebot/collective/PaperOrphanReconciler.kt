package com.lifecyclebot.collective

import com.lifecyclebot.engine.BotService
import com.lifecyclebot.engine.ErrorLogger

/**
 * PaperOrphanReconciler — V5.9.134
 *
 * Root cause of the "paper balance gets wiped on app update" bug:
 *   - Every perps trader (CryptoAlt, Stock, Metals, Forex, Commodities) debits
 *     `BotService.status.paperWalletSol` via `creditUnifiedPaperSol(-sizeSol)`
 *     when it opens a position.
 *   - The open position is persisted to Turso (`markets_positions` table with
 *     status='OPEN') but the in-memory `spotPositions` / `leveragePositions`
 *     maps are NOT rehydrated on startup.
 *   - When the app is updated / restarted mid-trade those positions vanish
 *     from memory. The capital debit stays on the books; the wallet looks
 *     drained while the Turso row rots as an orphan.
 *
 * The reconciler is invoked from each trader's startup sequence. It:
 *   1. Queries Turso for every OPEN paper-mode row belonging to that
 *      trader's asset class + this instance.
 *   2. Refunds the deployed capital (sizeSol) back to the unified paper
 *      wallet via `BotService.creditUnifiedPaperSol`.
 *   3. Deletes the Turso row so the next startup doesn't double-refund.
 *
 * We deliberately DO NOT try to rehydrate the positions back into memory:
 *   - The bot's entry-score snapshots are also gone, so per-layer real
 *     accuracy can't be correctly updated at close.
 *   - Prices drift during downtime — the paper P&L that would come out
 *     is meaningless.
 *   - Treating an update as an effective "break-even unwind" keeps paper
 *     accounting honest and the balance stable.
 */
object PaperOrphanReconciler {

    private const val TAG = "PaperOrphanRecon"

    /**
     * Reconcile orphaned paper positions for one asset class.
     * Returns the number of SOL refunded.
     *
     * @param assetClass  Turso marker: "CRYPTO_ALT" | "STOCK" | "METAL" |
     *                    "FOREX" | "COMMODITY".
     * @param sourceLabel Label used in the credit log line.
     */
    suspend fun reconcile(assetClass: String, sourceLabel: String): Double {
        val client = CollectiveLearning.getClient() ?: return 0.0
        val instanceId = CollectiveLearning.getInstanceId() ?: return 0.0
        return try {
            val orphans = client.loadOpenMarketsPositions(
                instanceId = instanceId,
                assetClass = assetClass,
                paperOnly  = true,
            )
            if (orphans.isEmpty()) return 0.0
            var refunded = 0.0
            orphans.forEach { orphan ->
                refunded += orphan.sizeSol
                try {
                    BotService.creditUnifiedPaperSol(
                        delta  = orphan.sizeSol,
                        source = "$sourceLabel.reconcile[${orphan.market}]",
                    )
                } catch (_: Exception) {}
                try { client.deleteMarketsPosition(orphan.id) } catch (_: Exception) {}
            }
            ErrorLogger.info(
                TAG,
                "♻️  $sourceLabel reconciled ${orphans.size} orphan paper positions → " +
                    "refunded ${"%.3f".format(refunded)} SOL (wipe-on-update fix)"
            )
            refunded
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "$sourceLabel reconcile error: ${e.message}")
            0.0
        }
    }
}
