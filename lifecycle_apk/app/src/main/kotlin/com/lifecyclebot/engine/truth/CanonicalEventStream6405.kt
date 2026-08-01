package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6405 §7 — JOURNAL + UI UNIFICATION (canonical event stream).
 *
 * Prior state: journals, live-trade log rows, portfolio UI, and
 * forensic ledger each derived numbers from a different snapshot,
 * producing the inconsistent PnL and quantity rows the operator kept
 * flagging.
 *
 * This authority owns a single append-only canonical event stream
 * that BOTH the journal and the UI subscribe to. Every event carries:
 *   • wallet+mint+positionGeneration
 *   • event type
 *   • raw quantity (BigInteger)          — never a Double
 *   • lamports movement (BigInteger)     — never a Double
 *   • timestamp (ms since epoch)
 *   • proof source label
 *
 * Consumers derive their view state ONLY from this stream. UI
 * refreshes re-fold from the stream; journals persist the stream
 * verbatim (§1+§2 Room migration will point the DAO here).
 */
object CanonicalEventStream6405 {

    enum class Type {
        BUY_INTENT, BUY_LANDED, BUY_VERIFIED,
        SELL_INTENT, SELL_LANDED, SELL_VERIFIED,
        POSITION_TERMINAL,
        DECIMAL_INTEGRITY_BLOCK, PRICE_INTEGRITY_BLOCK,
        DUPLICATE_EXIT_BLOCKED,
    }

    data class Event(
        val seq: Long,
        val timestampMs: Long,
        val wallet: String,
        val mint: String,
        val positionGeneration: Long,
        val type: Type,
        val rawQty: BigInteger,
        val lamports: BigInteger,
        val source: String,
        val note: String,
    )

    private val seqGen = AtomicLong(0L)
    private val events = CopyOnWriteArrayList<Event>()
    /** Bounded — keeps the last N events in memory; older events are the DAO's job (§1+§2). */
    private const val MAX_IN_MEMORY = 4_096
    private val subscribers = CopyOnWriteArrayList<(Event) -> Unit>()

    fun append(
        wallet: String,
        mint: String,
        positionGeneration: Long,
        type: Type,
        rawQty: BigInteger = BigInteger.ZERO,
        lamports: BigInteger = BigInteger.ZERO,
        source: String = "",
        note: String = "",
    ): Event {
        val e = Event(
            seq = seqGen.incrementAndGet(),
            timestampMs = System.currentTimeMillis(),
            wallet = wallet,
            mint = mint,
            positionGeneration = positionGeneration,
            type = type,
            rawQty = rawQty,
            lamports = lamports,
            source = source,
            note = note,
        )
        events.add(e)
        // Trim oldest half when overflowing (cheap; keeps replay boundedness).
        if (events.size > MAX_IN_MEMORY) {
            val drop = events.size - (MAX_IN_MEMORY / 2)
            repeat(drop) { events.removeAt(0) }
        }
        try {
            ForensicLogger.lifecycle(
                "CANONICAL_EVENT_6405",
                "seq=${e.seq} type=${type.name} mint=${mint.take(10)} gen=$positionGeneration " +
                    "rawQty=$rawQty lamports=$lamports source=$source",
            )
            PipelineHealthCollector.labelInc("CANONICAL_EVENT_${type.name}_6405")
        } catch (_: Throwable) {}
        // Notify subscribers on the caller's thread; subscribers are expected to be non-blocking.
        subscribers.forEach { s ->
            try { s(e) } catch (_: Throwable) {}
        }
        return e
    }

    fun subscribe(handler: (Event) -> Unit): AutoCloseable {
        subscribers.add(handler)
        return AutoCloseable { subscribers.remove(handler) }
    }

    /** Fold events for a position lifetime — the canonical PnL & qty view. */
    data class PositionFold(
        val rawBought: BigInteger,
        val rawSold: BigInteger,
        val lamportsSpent: BigInteger,
        val lamportsRecovered: BigInteger,
        val terminal: TerminalFinalityAuthority6405.Terminal?,
    )

    /**
     * Fold events for (mint, positionGeneration). Optionally filter by
     * [wallet] — required for paper/live parity where two lanes hold
     * the same mint on separate wallets and must NOT sum together.
     */
    fun fold(mint: String, positionGeneration: Long, wallet: String? = null): PositionFold {
        var bought = BigInteger.ZERO
        var sold = BigInteger.ZERO
        var spent = BigInteger.ZERO
        var recovered = BigInteger.ZERO
        for (e in events) {
            if (e.mint != mint || e.positionGeneration != positionGeneration) continue
            if (wallet != null && e.wallet != wallet) continue
            when (e.type) {
                Type.BUY_VERIFIED -> {
                    bought = bought.add(e.rawQty)
                    spent = spent.add(e.lamports)
                }
                Type.SELL_VERIFIED -> {
                    sold = sold.add(e.rawQty)
                    recovered = recovered.add(e.lamports)
                }
                else -> Unit
            }
        }
        val term = TerminalFinalityAuthority6405.terminalOf(mint, positionGeneration)
        return PositionFold(bought, sold, spent, recovered, term)
    }

    fun snapshot(): List<Event> = events.toList()
    fun size(): Int = events.size

    internal fun clearForTest() {
        events.clear()
        subscribers.clear()
        seqGen.set(0L)
    }
}
