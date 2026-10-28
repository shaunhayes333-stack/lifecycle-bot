package com.lifecyclebot.network

import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8418 §WHY_A_MARKET_READ_CAME_BACK_EMPTY — owner, 5.0.8416 log: "On-chain pools ... reads=1148 failed=5446".
 *
 * [KeylessRpc8359.post8359] returned null for five different things and every caller counted all of them as one
 * "failed": no rung was even asked (every keyless governor full / every host in lockout — our own refusal, nothing on
 * the wire), an HTTP refusal, a JSON-RPC error, a stale slot, or a transport failure. Those need different fixes and
 * looked identical. Each empty answer is now recorded under its caller's tag with the reason, so the snapshot says
 * which it was.
 */
object RpcOutcome8418 {
    private const val MAX_KEYS_PER_TAG_8418 = 24
    private val counts = ConcurrentHashMap<String, ConcurrentHashMap<String, AtomicLong>>()

    /** Record one market-read outcome ("ok", "noRung", "http429", "rpc-32005", "monthlyCap", "stale", ...) for [tag]. */
    fun note8418(tag: String, outcome: String) {
        val t = tag.ifBlank { "?" }.take(16)
        val byOutcome = counts.computeIfAbsent(t) { ConcurrentHashMap() }
        val key = if (byOutcome.size >= MAX_KEYS_PER_TAG_8418 && !byOutcome.containsKey(outcome)) "other" else outcome.take(24)
        byOutcome.computeIfAbsent(key) { AtomicLong(0L) }.incrementAndGet()
    }

    /**
     * The reason key for a JSON-RPC error answered by the rung labelled [label]. An Alchemy "Monthly capacity limit
     * exceeded" latches that rung out of the ladders until the plan month resets ([RpcCapacityLatch8418]).
     */
    fun rpcErrorKey8418(label: String, code: Int, message: String?): String {
        RpcCapacityLatch8418.observe8418(label, message)
        return if (RpcCapacityLatch8418.monthlyCapacityPhrase8418(message)) "monthlyCap" else "rpc$code"
    }

    fun count8418(tag: String, outcome: String): Long = counts[tag]?.get(outcome)?.get() ?: 0L

    /** rpcOutcome8418[8038{ok=..,noRung=..,http429=..}, ...] */
    fun line8418(): String {
        val body = counts.entries.sortedBy { it.key }.joinToString(", ") { (tag, m) ->
            tag + "{" + m.entries.sortedByDescending { it.value.get() }.joinToString(",") { "${it.key}=${it.value.get()}" } + "}"
        }.ifBlank { "-" }
        return "rpcOutcome8418[$body] noRung=no rung asked (own governor/lockout, nothing on the wire)"
    }

    /** The snapshot row: outcomes by caller, then the Alchemy plan latch. Never throws. */
    fun reportLine8418(): String = try { line8418() + " " + RpcCapacityLatch8418.statusLine8418() } catch (_: Throwable) { "unavailable" }

    internal fun resetForTests8418() { counts.clear() }
}

/**
 * V5.0.8418 §A_SPENT_PLAN_IS_NOT_A_SLOW_NODE — the 5.0.8416 log: `pump_curve_rpc last_err: {"code":429,"message":
 * "Monthly capacity limit exceeded. Visit https://dashboard.alc...` and `rpc_solana-mainnet.g.alchemy.com sr=0%`.
 *
 * The shipped Alchemy key (RuntimeProviderAuthority6685.configuredAlchemyRpc) has used its month. ApiBackoff treats the
 * 429 as a per-minute limit, so Alchemy stayed in every ladder: the pool-vault, curve and supply reads (maxRungs 3)
 * spent one of their three rungs on a node that cannot answer until the plan month resets. While this latch holds the
 * Alchemy endpoint is left out of the ladders entirely; it returns at the first instant of the next UTC month, and a
 * process restart asks it once again. Helius (execution) is never latched here.
 */
object RpcCapacityLatch8418 {
    @Volatile private var alchemyUntilMs = 0L
    private val latches = AtomicLong(0L)

    /** Pure: the provider's own words for a spent monthly plan. */
    internal fun monthlyCapacityPhrase8418(message: String?): Boolean =
        message != null && message.contains("monthly capacity limit exceeded", ignoreCase = true)

    /** Pure: the first millisecond of the calendar month (UTC) after [nowMs]. */
    fun nextMonthStartUtcMs8418(nowMs: Long): Long {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        c.timeInMillis = nowMs
        c.set(Calendar.DAY_OF_MONTH, 1)
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        c.add(Calendar.MONTH, 1)
        return c.timeInMillis
    }

    /**
     * A provider answer for host label [host] (an RPC label or "pump_curve_rpc"). Only the monthly-capacity wording
     * latches, and never against Helius.
     */
    fun observe8418(host: String, message: String?, nowMs: Long = System.currentTimeMillis()) {
        if (!monthlyCapacityPhrase8418(message)) return
        if (host.contains("helius", ignoreCase = true)) return
        val until = nextMonthStartUtcMs8418(nowMs)
        if (until <= alchemyUntilMs) return
        alchemyUntilMs = until
        latches.incrementAndGet()
        try {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("ALCHEMY_MONTHLY_CAPACITY_LATCHED_8418")
            com.lifecyclebot.engine.ForensicLogger.lifecycle(
                "ALCHEMY_MONTHLY_CAPACITY_LATCHED_8418",
                "host=$host untilUtcMs=$until action=left_out_of_rpc_ladders_until_plan_month_resets",
            )
        } catch (_: Throwable) {}
    }

    fun alchemyExhausted8418(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs < alchemyUntilMs

    /** alchemyLatch8418[exhausted=.. leftH=.. latches=..] */
    internal fun statusLine8418(nowMs: Long = System.currentTimeMillis()): String =
        "alchemyLatch8418[exhausted=${alchemyExhausted8418(nowMs)} leftH=${((alchemyUntilMs - nowMs).coerceAtLeast(0L)) / 3_600_000L} latches=${latches.get()}]"

    internal fun resetForTests8418() { alchemyUntilMs = 0L; latches.set(0L) }
}
