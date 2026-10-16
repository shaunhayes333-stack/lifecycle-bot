package com.lifecyclebot.engine

import com.lifecyclebot.engine.market.MarketSweep7297
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Bounded diagnostic snapshots only. No network, new decisions, or trading authority on export. */
object BotImprovementReport8168 {
    data class Hunt(val lane: String, val total: Int, val inBand: Int, val eligible: Int,
        val selected: Int, val unknownAge: Int, val unknownCap: Int, val examples: List<String>)
    @Volatile private var huntAt = 0L
    @Volatile private var hunts: List<Hunt> = emptyList()
    private val cortex = ConcurrentHashMap<String, AtomicLong>()

    fun hunt(lane: String, rows: List<MarketSweep7297.Row>, inBand: Int,
        eligible: Int, selected: List<MarketSweep7297.Row>): Hunt = Hunt(lane, rows.size, inBand,
        eligible, selected.size, rows.count { !it.ageHours.isFinite() || it.ageHours <= 0.0 },
        rows.count { !it.mcapUsd.isFinite() || it.mcapUsd <= 0.0 }, selected.take(3).map {
            "${it.mint} symbol=${it.symbol.take(24).replace('\n', ' ')} cap=${it.mcapUsd} liq=${it.liquidityUsd}" +
                " ageMin=${it.ageHours * 60.0} chg1h=${it.priceChangeH1Pct} tx1h=${it.txCountH1}" +
                " providers=${it.providers.sorted().joinToString(",")}" })

    fun publishHunts(rows: List<Hunt>, atMs: Long) { hunts = rows.toList(); huntAt = atMs }

    internal fun reasonClass(reason: String?): String {
        val r = reason.orEmpty().uppercase()
        return when {
            r.isBlank() -> "UNSPECIFIED"
            r.contains("MCAP_TOO_LOW") || r.contains("MCAP_TOO_HIGH") -> "OUTSIDE_NATIVE_CAP"
            r.contains("TOO_OLD") || r.contains("TOO_YOUNG") -> "OUTSIDE_NATIVE_AGE"
            r.contains("HYDRAT") || r.contains("DATA_INCOMPLETE") || r.contains("PRICE_NOT_FRESH") -> "DATA_MISSING_OR_STALE"
            r.contains("NATIVE_") -> "OTHER_NATIVE_REFUSAL"
            else -> "OTHER_DECISION_REASON"
        }
    }

    fun noteCortex(lane: String, admitted: Boolean, executableByReason: Boolean, reason: String?) {
        // Finite category vocabulary; unknown lane labels share one bin.
        val l = lane.uppercase().takeIf { it in setOf("QUALITY", "BLUECHIP", "SHITCOIN", "CYCLIC", "EXPRESS",
            "CORE", "MOONSHOT", "PROJECT_SNIPER", "DIP_HUNTER", "MANIPULATED", "TREASURY", "CASHGEN") } ?: "OTHER"
        val k = "$l|${if (admitted) "ADMITTED" else reasonClass(reason)}|reasonExecutable=$executableByReason"
        cortex.computeIfAbsent(k) { AtomicLong() }.incrementAndGet()
    }

    internal fun counterSection(labels: Map<String, Long>, prefixes: List<String>): String {
        val rows = labels.entries.filter { (k, v) -> v > 0 && prefixes.any { k.removePrefix("LIFECYCLE/").startsWith(it) } }
            .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
        return if (rows.isEmpty()) "none recorded (not proof of healthy coverage)" else
            rows.take(24).joinToString(" · ") { "${it.key}=${it.value}" } +
                if (rows.size > 24) " · omittedKeys=${rows.size - 24}" else ""
    }

    fun append(sb: StringBuilder, labels: Map<String, Long>, nowMs: Long) {
        sb.append("\n===== BOT IMPROVEMENT EVIDENCE (8168) =====\n")
        sb.append("Scopes: hunter=latest completed sweep; Cortex=context events this process (repeats possible); counters=current collector window. No cross-scope conversion rates.\n")
        val rows = hunts
        sb.append("Discovery lane fit: sweepAgeMs=").append(if (huntAt > 0) (nowMs - huntAt).coerceAtLeast(0) else -1).append('\n')
        if (rows.isEmpty()) sb.append("  NO_HUNT_SNAPSHOT: no completed sweep captured\n")
        for (h in rows) {
            sb.append("  ${h.lane} scanned=${h.total} inCapBand=${h.inBand} mandatePassed=${h.eligible} selected=${h.selected}")
                .append(" outOfBand=${h.total - h.inBand} mandateRejected=${h.inBand - h.eligible} rankedNotSelected=${h.eligible - h.selected}")
                .append(" universeUnknownAge=${h.unknownAge} universeUnknownCap=${h.unknownCap}\n")
            h.examples.forEach { sb.append("    selected ").append(it).append('\n') }
        }
        sb.append("Cortex decision-context audit: reasonExecutable is a reason-based proxy, NOT native lane acceptance or a landed trade.\n")
        if (cortex.isEmpty()) sb.append("  NO_CONTEXT_EVENTS_CAPTURED\n")
        cortex.entries.sortedBy { it.key }.forEach { sb.append("  ${it.key}=${it.value.get()}\n") }
        for ((title, prefixes) in listOf(
            "Exit queue causes" to listOf("EXIT_QUEUE_CAUSE_", "STOP_LATENCY_BALANCE_", "BALANCE_PROOF_"),
            "Admission delivery" to listOf("HANDOFF_TASK_", "HANDOFF_RECEIVER_", "CANDIDATE_ADMISSION_8136_"),
            "Profit execution checks" to listOf("PROFIT_EXIT_TRUTH_", "PARTIAL_BLOCKED_BELOW_BREAKEVEN", "PARTIAL_EXIT_NET_UNECONOMIC"),
            "Learning delivery exceptions" to listOf("FINALIZED_BUS_CONSUMER_", "LEARNING_ELIGIBILITY_", "REWARD_PURITY_REJECT_"))) {
            sb.append(title).append(": ").append(counterSection(labels, prefixes)).append('\n')
        }
        sb.append("Interpretation: selected != native-qualified != FDG-allowed != filled. Forward returns != banked SOL. Compare actual whole-position outcomes and paid fees separately from observed peaks.\n")
    }
}
