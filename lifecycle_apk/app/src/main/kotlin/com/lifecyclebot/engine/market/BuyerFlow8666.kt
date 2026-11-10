package com.lifecyclebot.engine.market

import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * A short-lived view of actual swaps delivered to the unified trade path.
 *
 * LaunchTape7921 requires a witnessed pump.fun birth; 5-minute provider bars arrive too
 * late for first buyers, and a price-only curve update cannot identify a buyer at all.
 * This feed instead counts the wallets, sides and SOL sizes already present on the
 * live PumpPortal / Helius / validator trade callbacks, for any mint and any lane.
 * Unknown wallets and inferred curve trades are never manufactured as new buyers.
 *
 * This is observation only: it neither admits nor refuses an order, and never
 * changes quote, safety, wallet or exit authority.
 */
object BuyerFlow8666 {
    private const val WINDOW_MS = 60_000L
    private const val FRESH_MS = 20_000L
    private const val MAX_MINTS = 512
    private const val MAX_PRINTS_PER_MINT = 256
    private const val MAX_KNOWN_WALLETS = 400
    private const val BUYER_MEMORY_MS = 5L * 60_000L

    internal data class Print(val atMs: Long, val wallet: String, val sol: Double, val buy: Boolean,
                              val firstSeenBuyer: Boolean = true)
    private class MintWindow {
        val prints = ArrayDeque<Print>()
        val seenBuyerAtMs = LinkedHashMap<String, Long>()
    }

    data class View(
        val atMs: Long,
        val distinctBuyers60s: Int,
        val newBuyers15s: Int,
        val buyersPerMin: Double,
        val buySharePct: Double,
        val netSol60s: Double,
        val buyTx15s: Int,
        val buyTxPrev15s: Int,
        val repeatBuyers60s: Int,
        val txPerMin: Double,
        /** Same 0..100 momentum score scale as MomentumPredictorAI, but actual adjacent 15s trade windows. */
        val volumeAccelScore: Double,
    )

    private val prints = ConcurrentHashMap<String, MintWindow>()

    fun note(mint: String, wallet: String, sol: Double, buy: Boolean, nowMs: Long = System.currentTimeMillis()) {
        // The unified path also carries walletless curve-snapshot inferences. They are NOT executed buyer prints.
        if (mint.isBlank() || wallet.isBlank() || !sol.isFinite() || sol <= 0.0 || nowMs <= 0L) return
        val q = prints.computeIfAbsent(mint) { MintWindow() }
        synchronized(q) {
            val wallets = q.seenBuyerAtMs.entries.iterator()
            while (wallets.hasNext()) {
                val first = wallets.next()
                if (nowMs - first.value > BUYER_MEMORY_MS || q.seenBuyerAtMs.size >= MAX_KNOWN_WALLETS) wallets.remove()
                else break // insertion order is first-seen order
            }
            val firstSeen = buy && wallet !in q.seenBuyerAtMs
            if (firstSeen) q.seenBuyerAtMs[wallet] = nowMs
            q.prints.addLast(Print(nowMs, wallet, sol, buy, firstSeen))
            while (q.prints.isNotEmpty() && (q.prints.size > MAX_PRINTS_PER_MINT || nowMs - q.prints.peekFirst().atMs > WINDOW_MS)) q.prints.removeFirst()
        }
        if (prints.size > MAX_MINTS) {
            // Bounded memory; evict quiet mints, never broaden the Helius subscription cap.
            val victims = prints.entries.map { it.key to synchronized(it.value) { it.value.prints.peekLast()?.atMs ?: 0L } }
                .sortedBy { it.second }.take((prints.size - MAX_MINTS).coerceAtLeast(1))
            victims.forEach { (key, _) -> if (key != mint) prints.remove(key) }
        }
    }

    /** A non-fetching, freshness-stamped read; null is genuinely unobserved. */
    fun read(mint: String, nowMs: Long = System.currentTimeMillis()): View? {
        val q = prints[mint] ?: return null
        val observed = synchronized(q) { q.prints.toList() }
        return calculate(observed, nowMs)
    }

    /** Pure window arithmetic, also used by executable regression tests. */
    internal fun calculate(rows: List<Print>, nowMs: Long): View? {
        val recent = rows.filter { nowMs - it.atMs in 0L..WINDOW_MS && it.sol.isFinite() && it.sol > 0.0 }
        if (recent.isEmpty()) return null
        val last = recent.maxOf { it.atMs }
        if (nowMs - last > FRESH_MS) return null
        val buys = recent.filter { it.buy }
        val wallets = buys.map { it.wallet }.filter { it.isNotBlank() }.distinct()
        val span = (nowMs - recent.minOf { it.atMs }).coerceAtLeast(15_000L).coerceAtMost(WINDOW_MS)
        val recent15 = recent.filter { nowMs - it.atMs < 15_000L }
        val prior15 = recent.filter { nowMs - it.atMs in 15_000L..29_999L }
        val newBuyers = buys.filter { it.firstSeenBuyer && nowMs - it.atMs in 0L..14_999L }
            .map { it.wallet }.distinct().size
        val total = recent.sumOf { it.sol }
        val bought = buys.sumOf { it.sol }
        val sold = total - bought
        val recentVolume = recent15.sumOf { it.sol }
        val priorVolume = prior15.sumOf { it.sol }
        // One prior dust trade cannot establish an acceleration baseline; NaN remains unknown.
        val accel = if (prior15.size >= 2 && priorVolume > 0.0) {
            ((recentVolume / priorVolume - 1.0) * 200.0).coerceIn(0.0, 100.0)
        } else Double.NaN
        return View(
            atMs = last,
            distinctBuyers60s = wallets.size,
            newBuyers15s = newBuyers,
            buyersPerMin = wallets.size * 60_000.0 / span,
            buySharePct = if (recent.size >= 3 && total > 0.0) bought / total * 100.0 else Double.NaN,
            netSol60s = bought - sold,
            buyTx15s = recent15.count { it.buy },
            buyTxPrev15s = prior15.count { it.buy },
            repeatBuyers60s = buys.groupingBy { it.wallet }.eachCount().count { it.value > 1 },
            txPerMin = recent.size * 60_000.0 / span,
            volumeAccelScore = accel,
        )
    }

    internal fun clearForTest() { prints.clear() }
}
