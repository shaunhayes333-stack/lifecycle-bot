package com.lifecyclebot.engine.market

import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * A short-lived view of actual swaps delivered to the unified trade path.
 *
 * LaunchTape7921 requires a witnessed pump.fun birth; 5-minute provider bars arrive too
 * late for first buyers, and a price-only curve update cannot identify a buyer at all.
 * This feed instead counts the wallets, sides and SOL sizes already present on the
 * live PumpPortal / Helius / validator trade callbacks, for any mint and any lane.
 * A SIGNED, executed swap without a decoded wallet still proves size, side and
 * volume, but never a unique or new buyer. Inferred curve marks are not swaps.
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
                              val firstSeenBuyer: Boolean = true,
                              /** Retained only on walletless prints so a richer duplicate can fill its identity. */
                              val missingWalletSignature: String = "")
    private class MintWindow {
        val prints = ArrayDeque<Print>()
        val seenBuyerAtMs = LinkedHashMap<String, Long>()
        val signatures = LinkedHashMap<String, Long>()
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
        val sustainedDemand: Boolean = false,
    )

    private val prints = ConcurrentHashMap<String, MintWindow>()
    private val accepted8790 = AtomicLong(0L)
    private val signedWalletless8790 = AtomicLong(0L)
    private val walletRepaired8790 = AtomicLong(0L)
    private val duplicate8790 = AtomicLong(0L)
    private val stale8790 = AtomicLong(0L)
    private val unsignedWalletless8790 = AtomicLong(0L)
    private val freshReads8790 = AtomicLong(0L)
    private val emptyReads8790 = AtomicLong(0L)

    /** The next capture distinguishes an absent trade feed from an undecoded buyer address. */
    fun statusLine8790(): String = "buyerFlow8790[accepted=${accepted8790.get()} walletlessSigned=${signedWalletless8790.get()} " +
        "walletRecovered=${walletRepaired8790.get()} duplicates=${duplicate8790.get()} stale=${stale8790.get()} " +
        "unsignedWalletless=${unsignedWalletless8790.get()} freshReads=${freshReads8790.get()} " +
        "emptyReads=${emptyReads8790.get()} trackedMints=${prints.size}]"

    fun note(mint: String, wallet: String, sol: Double, buy: Boolean, nowMs: Long = System.currentTimeMillis(),
             signature: String = "", receivedAtMs: Long = nowMs) {
        // A walletless signed swap is still measured trade volume; do not throw that
        // evidence away because the provider omitted the trader identity. Unsigned
        // AND walletless records are ambiguous and cannot prove an actual print.
        if (mint.isBlank() || !sol.isFinite() || sol <= 0.0 || nowMs <= 0L) return
        if (wallet.isBlank() && signature.isBlank()) { unsignedWalletless8790.incrementAndGet(); return }
        if (receivedAtMs - nowMs !in 0L..WINDOW_MS) { stale8790.incrementAndGet(); return }
        val q = prints.computeIfAbsent(mint) { MintWindow() }
        synchronized(q) {
            // The same signed swap may arrive from several providers. A later
            // provider may supply the wallet omitted by the first; count it once.
            q.signatures.entries.removeAll { receivedAtMs - it.value > WINDOW_MS }
            if (signature.isNotBlank() && q.signatures.putIfAbsent("$signature|$buy", receivedAtMs) != null) {
                duplicate8790.incrementAndGet()
                // The same signed swap may be decoded by a walletless backfill first,
                // then by a program stream with a buyer address. Repair identity on
                // the original row; do not double its volume or its transaction.
                if (wallet.isNotBlank()) {
                    val older = q.prints.firstOrNull { it.missingWalletSignature == signature && it.buy == buy && it.wallet.isBlank() }
                    if (older != null) {
                        val priorFirst = q.seenBuyerAtMs[wallet]
                        val firstSeen = buy && (priorFirst == null || older.atMs < priorFirst)
                        if (firstSeen) q.seenBuyerAtMs[wallet] = older.atMs
                        val amended = q.prints.map {
                            when {
                                it === older -> it.copy(wallet = wallet, firstSeenBuyer = firstSeen, missingWalletSignature = "")
                                firstSeen && it.wallet == wallet && it.buy -> it.copy(firstSeenBuyer = false)
                                else -> it
                            }
                        }
                        q.prints.clear(); q.prints.addAll(amended)
                        walletRepaired8790.incrementAndGet()
                    }
                }
                return
            }
            while (q.signatures.size > MAX_PRINTS_PER_MINT * 2) q.signatures.remove(q.signatures.keys.first())
            q.seenBuyerAtMs.entries.removeAll { receivedAtMs - it.value > BUYER_MEMORY_MS }
            val previousFirst = if (wallet.isNotBlank()) q.seenBuyerAtMs[wallet] else null
            val firstSeen = buy && wallet.isNotBlank() && (previousFirst == null || nowMs < previousFirst)
            if (firstSeen) {
                q.seenBuyerAtMs[wallet] = nowMs
                // An older event can arrive after a newer buy from the same wallet.
                // Move the first-buy flag to its event time, never count both as new.
                if (previousFirst != null) {
                    val corrected = q.prints.map { if (it.wallet == wallet && it.buy) it.copy(firstSeenBuyer = false) else it }
                    q.prints.clear(); q.prints.addAll(corrected)
                }
            }
            while (q.seenBuyerAtMs.size > MAX_KNOWN_WALLETS) q.seenBuyerAtMs.remove(q.seenBuyerAtMs.keys.first())
            val row = Print(nowMs, wallet, sol, buy, firstSeen,
                missingWalletSignature = if (wallet.isBlank()) signature else "")
            accepted8790.incrementAndGet()
            if (wallet.isBlank()) signedWalletless8790.incrementAndGet()
            if (q.prints.isEmpty() || q.prints.peekLast().atMs <= nowMs) q.prints.addLast(row)
            else {
                val ordered = (q.prints.toList() + row).sortedBy { it.atMs }
                q.prints.clear(); q.prints.addAll(ordered)
            }
            while (q.prints.isNotEmpty() && (q.prints.size > MAX_PRINTS_PER_MINT ||
                        receivedAtMs - q.prints.peekFirst().atMs > WINDOW_MS)) q.prints.removeFirst()
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
        val q = prints[mint]
        if (q == null) { emptyReads8790.incrementAndGet(); return null }
        val observed = synchronized(q) { q.prints.toList() }
        val view = calculate(observed, nowMs)
        if (view == null) emptyReads8790.incrementAndGet() else freshReads8790.incrementAndGet()
        return view
    }

    /**
     * V5.0.8845 — fresh signed-swap mints are eligible for the EARLY 60-second
     * discovery pass even when TradeTape8100 has not accumulated six comparable
     * 2/5-minute price-bearing prints. This is only a scanner priority list; it
     * does not promote a 60-second observation into a five-minute flow fact.
     * A timestamp from a stale provider is never eligible.
     */
    fun activeMints8845(nowMs: Long = System.currentTimeMillis(), limit: Int = 48): List<String> {
        if (limit <= 0) return emptyList()
        return prints.entries.mapNotNull { (mint, q) ->
            val newest = synchronized(q) { q.prints.peekLast()?.atMs } ?: return@mapNotNull null
            if (nowMs - newest in 0L..FRESH_MS) mint to newest else null
        }.sortedByDescending { it.second }.take(limit).map { it.first }
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
        val newBuyers = buys.filter { it.firstSeenBuyer && it.wallet.isNotBlank() && nowMs - it.atMs in 0L..14_999L }
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
            repeatBuyers60s = buys.filter { it.wallet.isNotBlank() }.groupingBy { it.wallet }.eachCount().count { it.value > 1 },
            txPerMin = recent.size.toDouble(), // actual swaps in the trailing minute
            volumeAccelScore = accel,
            // V5.0.8681 — "sustained" means demand present in BOTH windows, not "a seller appeared first". The old
            // prior15.any { !it.buy } term made a pure two-window buy run ineligible: the 8680 session recorded zero
            // sustained-demand reads. A real baseline (two or more prior prints, net bought) is the requirement now.
            sustainedDemand = newBuyers >= 2 && prior15.size >= 2 &&
                prior15.sumOf { if (it.buy) it.sol else -it.sol } > 0.0 &&
                recent15.sumOf { if (it.buy) it.sol else -it.sol } > 0.0 &&
                recent15.filter { it.buy }.groupBy { it.wallet }.values.maxOfOrNull { group -> group.sumOf { it.sol } }
                    ?.let { it <= recent15.filter { p -> p.buy }.sumOf { p -> p.sol } * 0.5 } == true,
        )
    }

    internal fun clearForTest() {
        prints.clear()
        listOf(accepted8790, signedWalletless8790, walletRepaired8790, duplicate8790, stale8790,
            unsignedWalletless8790, freshReads8790, emptyReads8790).forEach { it.set(0L) }
    }
}
