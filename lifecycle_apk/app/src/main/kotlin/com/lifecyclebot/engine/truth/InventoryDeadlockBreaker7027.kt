package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector

/**
 * V5.0.7027 — §THE_CAP_COUNTED_ROWS_IT_COULD_NOT_MANAGE.
 *
 * THE DEADLOCK, from the operator's 5.0.7025 capture. Every stage of the
 * pipeline is alive:
 *
 *   V3 allow 72/72 · FDG allow 211 · BUY verdicts 967 · SAFETY allow 245
 *   cash 26.9127 SOL · capitalStarved=false · execution state ACTIVE
 *
 * and every authorised entry dies in one place:
 *
 *   Canonical PAPER active mints: 100
 *   Order size resolver: resolves=1646 exec=0 skip=1646
 *   final=0.00000 exec=false reason=POSITION_HARD_CAP_EXIT_THROUGHPUT_6727
 *   EXEC_GATE allow=0 block=336
 *   every specialist: status=SIZING_CHOKED
 *
 * Session completed trades: 0. Not scanner-choked, not FDG-choked, not cash
 * starved, not executor-dead — INVENTORY SATURATED, on a book of 100 opens
 * that this run did not create. It booted into them: positions=305 open=100
 * closed=188, MEME_REGISTRY_RESTORE=160, at 198 seconds of uptime.
 *
 * WHY IT DOES NOT CLEAR ITSELF. ExitThroughputAuthority6727 is admission-side
 * by design and says so — "deliberately does not block exits ... exits drain
 * the inventory without new opens re-saturating". That reasoning holds only
 * while exits ARE draining. In this capture they are not, and the state is
 * closed:
 *
 *   Exit scheduler eval=40000  SL=0 CATA=0 TP=0 TRAIL=0
 *   SELL ok=0 · PARTIAL ok=0 · close ledger: 0 mints stamped CLOSED
 *   STALE_PRICE_QUARANTINED 5328 · OPEN_PNL_BASIS_REJECTED 2625
 *
 * no exits -> no free slots -> no entries -> no exits, and nothing anywhere
 * escalates. The bot will sit like that until the operator restarts it, and a
 * restart reloads the same 100 rows.
 *
 * WHAT IS ACTUALLY WRONG WITH THE CAP. POSITION_HARD_CAP exists to bound how
 * much inventory the bot is MANAGING. It is implemented as a count of
 * canonical open rows, and those are not the same thing. A row whose token
 * cannot be priced by any of the seven providers for ten minutes is not being
 * managed: the exit scheduler pings it with markPx=0 and returns, the
 * universal sweep skips it on `last <= 0.0`, no stop can arm, no trail can
 * move. It contributes nothing but a slot. Counting those rows against the
 * ceiling means the bot stops trading in proportion to how much of its book
 * has gone dark — which is exactly backwards, because a darkened book is when
 * fresh, priceable candidates matter most.
 *
 * WHAT THIS DOES. It reports how many open rows are unmanageable, so the hard
 * cap can count managed inventory instead of rows. Nothing is sold, no price
 * is fabricated, no learner is fed. The relief is bounded (see
 * CAP_RELIEF_MAX_FRACTION) so a total feed blackout cannot uncap the bot, and
 * the cash-starvation gate in 6727 is untouched and still governs capital —
 * it is inventory COUNT that stops lying, not exposure.
 *
 * WHAT THIS IS NOT. Not a throttle, not a cap-to-dust, not a lane disable
 * (V5.9.1358). It only ever RAISES effective capacity, and only for rows the
 * exit engine has demonstrably been unable to touch. It also does not excuse
 * the darkness: every row it discounts is named in the diagnostic and stays
 * in the mark-refresh queue, because the operator's rule is that no position
 * should ever go unpriceable — this makes the bot survive the condition, it
 * does not accept it.
 */
object InventoryDeadlockBreaker7027 {

    /**
     * A position with no usable mark for this long is not being managed.
     *
     * Ten minutes is deliberately far past every refresh cadence in the app:
     * the open-position tick loop runs at 1Hz, the keyless batch rescue and
     * the per-mint chain both run inside it, and the exit feed queues its own
     * refresh with a TTL measured in seconds. If all of that has produced
     * nothing for ten minutes the token is dark, not slow.
     */
    private const val UNMANAGEABLE_AFTER_MS = 600_000L

    /**
     * A position must also be at least this old before it can be discounted,
     * so a fill still waiting for its first quote is never counted as dark.
     */
    private const val MIN_HOLD_MS = 600_000L

    /**
     * The most of the hard cap that dark rows may free, as a fraction.
     *
     * Deliberately not 1.0. If every provider dies at once the whole book goes
     * dark, and an unbounded discount would answer a total blackout by
     * removing the inventory ceiling entirely — buying into the exact
     * conditions where nothing can be exited. At 0.40 a 100-slot cap can fall
     * to 60 effective rows and no further, which is enough to break the
     * deadlock and keep trading while a subset of the book is stuck, and not
     * enough to matter if the feed is gone.
     */
    private const val CAP_RELIEF_MAX_FRACTION = 0.40

    /** How often the diagnostic may be emitted, per mode. */
    private const val DIAGNOSTIC_INTERVAL_MS = 60_000L

    private val lastDiagnosticMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

    data class Relief(
        /** Canonical open rows for this mode. */
        val openRows: Int,
        /** Rows with no usable mark past the threshold. */
        val darkRows: Int,
        /** Rows discounted after the relief bound is applied. */
        val discounted: Int,
        /** openRows - discounted. What the hard cap should compare. */
        val managedRows: Int,
    )

    /**
     * Count the open rows this mode can actually manage.
     *
     * @param mode "paper" or "live", already normalised by the caller.
     * @param hardCap the ceiling being enforced, used to bound the relief.
     */
    fun relief(mode: String, hardCap: Int): Relief {
        val open = try {
            CanonicalPositionAuthority6441.openPositions().filter { it.mode.equals(mode, true) }
        } catch (_: Throwable) { emptyList() }
        if (open.isEmpty()) return Relief(0, 0, 0, 0)

        val now = System.currentTimeMillis()
        var dark = 0
        val darkSample = ArrayList<String>(4)
        for (cp in open) {
            val heldMs = now - cp.openedAtMs
            if (heldMs < MIN_HOLD_MS) continue
            // A quote the guard has never been told about, or one stamped
            // longer ago than the threshold, is the same thing to the exit
            // engine: nothing it can compare a stop against. The guard is the
            // right authority to ask because it is what the exit feed itself
            // consults (the 6651 provenance test) — asking a different source
            // than the decider is how 6999 happened.
            val stampedAtMs = try {
                QuoteFreshnessGuard6452.lastPrice(cp.mint)?.stampedAtMs ?: 0L
            } catch (_: Throwable) { 0L }
            val markAgeMs = if (stampedAtMs <= 0L) Long.MAX_VALUE else now - stampedAtMs
            if (markAgeMs >= UNMANAGEABLE_AFTER_MS) {
                dark++
                if (darkSample.size < 4) darkSample.add(cp.symbol.ifBlank { cp.mint.take(8) })
            }
        }

        val maxRelief = (hardCap * CAP_RELIEF_MAX_FRACTION).toInt().coerceAtLeast(0)
        val discounted = dark.coerceAtMost(maxRelief)
        val managed = (open.size - discounted).coerceAtLeast(0)

        if (discounted > 0) {
            val last = lastDiagnosticMs[mode] ?: 0L
            if (now - last >= DIAGNOSTIC_INTERVAL_MS) {
                lastDiagnosticMs[mode] = now
                try {
                    PipelineHealthCollector.labelInc("INVENTORY_DARK_ROWS_DISCOUNTED_7027")
                    ForensicLogger.lifecycle(
                        "INVENTORY_DARK_ROWS_DISCOUNTED_7027",
                        "mode=$mode openRows=${open.size} darkRows=$dark discounted=$discounted " +
                            "managedRows=$managed hardCap=$hardCap reliefBound=$maxRelief " +
                            "darkAfterMs=$UNMANAGEABLE_AFTER_MS sample=${darkSample.joinToString(",")} " +
                            "armedExits=${ProtectiveExitScheduler6450.armedCount7027()} " +
                            "noMarkPings=${ProtectiveExitScheduler6450.noMarkCount7027()} " +
                            "note=rows_the_exit_engine_cannot_touch_do_not_count_as_managed_inventory",
                    )
                } catch (_: Throwable) {}
            }
        }
        return Relief(open.size, dark, discounted, managed)
    }
}
