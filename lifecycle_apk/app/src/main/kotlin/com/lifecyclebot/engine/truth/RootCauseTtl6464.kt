package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6464 §P1 — ROOT CAUSE TTL / DECAY.
 *
 * OPERATOR MANDATE:
 *   "Yet header still says MECHANICAL_FAULT/ui/reporting. Do not keep
 *    historical ANR hints as the primary diagnosis indefinitely. Root
 *    cause needs timestamp, active window, correlated trading impact,
 *    expiry/decay."
 *
 * DESIGN
 * ──────
 * `classify(reason, activeWindowMs, correlated)` sets the current root
 * cause with a firstAt/expiryAt timestamp. `current()` returns null
 * once expiry has passed, so downstream headers cannot indefinitely
 * report an old ANR hint as the current cause.
 *
 * Default active window: 300_000 ms (5 min). Callers may pass an
 * explicit window (e.g. 30s for a UI hiccup, 600s for a repeated
 * timeout).
 */
object RootCauseTtl6464 {

    data class Classification(
        val reason: String,
        val severity: String,       // "high" | "med" | "low"
        val firstAtMs: Long,
        val expiryAtMs: Long,
        val correlatedTradesImpact: Int,
        val source: String,
    )

    private val current = AtomicReference<Classification?>(null)
    private val classifications = AtomicLong(0L)
    private val expiries = AtomicLong(0L)

    fun classify(
        reason: String,
        severity: String,
        activeWindowMs: Long = 300_000L,
        correlatedTradesImpact: Int = 0,
        source: String = "unknown",
    ) {
        if (reason.isBlank()) return
        val now = System.currentTimeMillis()
        val c = Classification(
            reason = reason, severity = severity,
            firstAtMs = now, expiryAtMs = now + activeWindowMs.coerceAtLeast(0L),
            correlatedTradesImpact = correlatedTradesImpact, source = source,
        )
        current.set(c)
        classifications.incrementAndGet()
        try {
            ForensicLogger.lifecycle(
                "ROOT_CAUSE_CLASSIFIED_6464",
                "reason=$reason severity=$severity activeWindowMs=$activeWindowMs " +
                    "correlated=$correlatedTradesImpact source=$source",
            )
            PipelineHealthCollector.labelInc("ROOT_CAUSE_CLASSIFIED_6464")
        } catch (_: Throwable) {}
    }

    fun current(): Classification? {
        val c = current.get() ?: return null
        val now = System.currentTimeMillis()
        if (now > c.expiryAtMs) {
            if (current.compareAndSet(c, null)) {
                expiries.incrementAndGet()
                try {
                    ForensicLogger.lifecycle(
                        "ROOT_CAUSE_EXPIRED_6464",
                        "reason=${c.reason} ageMs=${now - c.firstAtMs}",
                    )
                    PipelineHealthCollector.labelInc("ROOT_CAUSE_EXPIRED_6464")
                } catch (_: Throwable) {}
            }
            return null
        }
        return c
    }

    fun clear() { current.set(null) }

    fun statusLine(): String {
        val c = current()
        val header = if (c == null) "current=(none)"
        else "current=${c.reason}(${c.severity}) age=${System.currentTimeMillis() - c.firstAtMs}ms " +
             "ttl=${c.expiryAtMs - System.currentTimeMillis()}ms correlated=${c.correlatedTradesImpact}"
        return "$header classifications=${classifications.get()} expiries=${expiries.get()}"
    }

    internal fun resetForTest() {
        current.set(null); classifications.set(0L); expiries.set(0L)
    }
}
