package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8330 §LIVE_BUY_NOT_OPENED_HAS_A_CAUSE.
 *
 * 5.0.8326 live: MOONSHOT 3 LIVE_BUY_NOT_OPENED, EXPRESS 1 — one wrapper name for three different things:
 *  - ALREADY_HELD: the mint is already an open / verifying position or a signed buy is still unresolved. The refusal is
 *    the one-purchase rule working (correct suppression);
 *  - TICKET_VANISHED: the sealed, exclusive ticket for the attempt is gone (removed or past its TTL) while the lane
 *    wrapper still believed it owned it — a fault in the ticket's life, not a market refusal;
 *  - REFUSED:<stop>: liveBuy refused and named its stop (route, door, quote, chart, gate) — the 8252 inner name.
 *  - UNNAMED: no inner stop was recorded and neither of the above holds (the remaining fault class).
 * The wrapper prefix "LIVE_BUY_NOT_OPENED_7948:" is kept so every 8252 / 8327 reader still parses the inner cause.
 * Counted per lane on the ticketExec8304 line: `notOpened8330[held= vanished= refused= unnamed=]`.
 */
object TicketCause8330 {
    const val WRAPPER_8330 = "LIVE_BUY_NOT_OPENED_7948"
    const val HELD_8330 = "ALREADY_HELD_8330"
    const val VANISHED_8330 = "TICKET_VANISHED_8330"
    const val UNNAMED_8330 = "UNNAMED_8330"

    private val DUPLICATE_WORDS_8330 = listOf("DUPLICATE", "ALREADY_OPEN", "ALREADY_IN_POSITION", "ALREADY_HELD", "IN_FLIGHT", "SIGNED_TRANSACTION_PENDING")

    /** Pure: the inner stop names the one-purchase suppression. */
    fun innerIsDuplicate8330(inner: String?): Boolean {
        val u = inner?.uppercase() ?: return false
        return DUPLICATE_WORDS_8330.any { u.contains(it) }
    }

    /**
     * Pure: the refined reason. [inner] = the 8252 inner stop (null when none was recorded), [mintHeld] = the mint is
     * held / verifying / a buy is in flight, [ticketLive] = the attempt's sealed ticket still exists.
     */
    fun refine8330(wrapper: String, inner: String?, mintHeld: Boolean, ticketLive: Boolean): String = when {
        !wrapper.startsWith(WRAPPER_8330) -> if (inner != null) "$wrapper:${inner.take(60)}" else wrapper
        mintHeld || innerIsDuplicate8330(inner) -> "$wrapper:$HELD_8330" + (if (inner != null) ":${inner.take(48)}" else "")
        inner != null -> "$wrapper:${inner.take(60)}"
        !ticketLive -> "$wrapper:$VANISHED_8330"
        else -> "$wrapper:$UNNAMED_8330"
    }

    /** Pure: the class of a (refined) drop reason for the per-lane counter: held / vanished / refused / unnamed, or null (not this wrapper). */
    fun classOf8330(reason: String): String? {
        if (!reason.startsWith(WRAPPER_8330)) return null
        val inner = reason.substringAfter(':', "")
        return when {
            inner.startsWith(HELD_8330) -> "held"
            inner == VANISHED_8330 -> "vanished"
            inner == UNNAMED_8330 || inner.isBlank() -> "unnamed"
            else -> "refused"
        }
    }

    /** Executor.unexecutedTicket7948: the runtime read of [refine8330] for [attemptId]. Other wrappers pass through. */
    fun refineLive8330(attemptId: String, reason: String): String {
        if (!reason.startsWith(WRAPPER_8330)) return reason
        val wrapper = reason.substringBefore(':')
        val inner = reason.substringAfter(':', "").takeIf { it.isNotBlank() }
        val ticket = try { ExecutableOpenGate.ticketForAttempt(attemptId) } catch (_: Throwable) { null }
        val mint = ticket?.mint.orEmpty()
        val held = mint.isNotBlank() && (
            (try { com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.hasOpenMint(mint) } catch (_: Throwable) { false }) ||
                (try { HandoffRoute8180.tokenFor8180(mint)?.position?.let { it.isOpen || it.pendingVerify || it.qtyToken > 0.0 } == true } catch (_: Throwable) { false }) ||
                (try { com.lifecyclebot.network.SignedTxLedger8085.unresolvedFor(mint, "BUY") } catch (_: Throwable) { null }) != null)
        val refined = refine8330(wrapper, inner, held, ticket != null)
        try { PipelineHealthCollector.labelInc("LIVE_BUY_NOT_OPENED_CAUSE_8330_${classOf8330(refined)?.uppercase() ?: "OTHER"}") } catch (_: Throwable) {}
        return refined
    }

    // ── per-lane counters (TicketExec8304.onDrop8304) ──

    private val counts8330 = ConcurrentHashMap<String, AtomicLong>()

    fun onDrop8330(lane: String, reason: String) {
        val c = classOf8330(reason) ?: return
        counts8330.computeIfAbsent("$lane|$c") { AtomicLong(0L) }.incrementAndGet()
    }

    /** Pure: " notOpened8330[held= vanished= refused= unnamed=]" ("" when the lane never saw the wrapper). */
    fun formatSuffix8330(held: Long, vanished: Long, refused: Long, unnamed: Long): String =
        if (held + vanished + refused + unnamed <= 0L) "" else " notOpened8330[held=$held vanished=$vanished refused=$refused unnamed=$unnamed]"

    fun suffix8330(lane: String): String {
        fun c(k: String) = counts8330["$lane|$k"]?.get() ?: 0L
        return formatSuffix8330(c("held"), c("vanished"), c("refused"), c("unnamed"))
    }

    internal fun resetForTests8330() { counts8330.clear() }
}
