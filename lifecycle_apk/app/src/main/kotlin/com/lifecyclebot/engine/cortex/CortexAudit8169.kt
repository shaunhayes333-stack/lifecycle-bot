package com.lifecyclebot.engine.cortex

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8169 — Cortex / scanner / hunter wiring audit (owner: "ensure the Cortex is included in the audit including all
 * functions including the scanners and hunters").
 *
 * One report block that names every Cortex function and every scanner / hunter with three runtime facts: is it ALIVE
 * (its own work counter moved this process), is it CONSUMED (the decision path called it: gate, sizing, exits, intake)
 * and what it LAST produced. Counters only; nothing here decides anything. Pure helpers are unit-tested
 * (Aate8169CortexTest).
 */
object CortexAudit8169 {

    // ── consumer call counters: every public Cortex entry point the decision path reaches ──
    private val calls = ConcurrentHashMap<String, AtomicLong>()

    fun call(name: String) { calls.computeIfAbsent(name) { AtomicLong(0) }.incrementAndGet() }
    fun calls(name: String): Long = calls[name]?.get() ?: 0L

    // ── per-lane gate reach (LiveEdgeGate7877 -> Cortex7885.entryRefusal): do the Rp / Rl powers ever meet a candidate? ──
    // [reached, readMissing, refuseBucket, refuseWithAuthority, refused]
    private val gate = ConcurrentHashMap<String, LongArray>()

    fun noteGate(lane: String, read: Boolean, refuseBucket: Boolean, authority: Boolean, refused: Boolean) {
        val a = gate.computeIfAbsent(lane.uppercase().take(16)) { LongArray(5) }
        synchronized(a) {
            a[0]++
            if (!read) a[1]++
            if (refuseBucket) a[2]++
            if (refuseBucket && authority) a[3]++
            if (refused) a[4]++
        }
    }

    /** "LANE reach/noRead/refuseBucket/withAuthority/refused" per lane (8168: Rp/Rl on most lanes, did refused=0). */
    fun gateLine(): String = gate.entries.sortedBy { it.key }.joinToString(" · ") { (l, a) ->
        synchronized(a) { "$l ${a[0]}/${a[1]}/${a[2]}/${a[3]}/${a[4]}" }
    }.ifBlank { "no candidate reached the Cortex gate" }

    // ── voter-read timing vs the forward label's start (leakage) ──
    private val captureLag = AtomicLong(0)
    private val captureLagN = AtomicLong(0)
    private val captureLagMax = AtomicLong(0)
    private val lateReads = AtomicLong(0)

    /**
     * Pure: were a decision's voters read after its forward label's start? The labeler re-bases a label to the first mark
     * [labelStartDelayMs] after the decision (ForwardReturnLabeler7731.rebaseDelayMs8094, the lane's fill latency); a voter
     * read later than that has seen part of the move it is graded on (future data in the features).
     */
    fun voterReadLeaks8169(readAtMs: Long, decisionAtMs: Long, labelStartDelayMs: Long): Boolean =
        readAtMs > 0L && decisionAtMs > 0L && labelStartDelayMs >= 0L && readAtMs - decisionAtMs > labelStartDelayMs

    fun noteCapture(lagMs: Long, late: Boolean) {
        val l = lagMs.coerceAtLeast(0L)
        captureLag.addAndGet(l); captureLagN.incrementAndGet()
        captureLagMax.accumulateAndGet(l) { a, b -> maxOf(a, b) }
        if (late) lateReads.incrementAndGet()
    }

    fun captureLine(): String {
        val n = captureLagN.get()
        return "captures=$n meanLagMs=${if (n > 0) captureLag.get() / n else 0} maxLagMs=${captureLagMax.get()} lateVoterReads=${lateReads.get()}"
    }

    // ── pure report helpers ──

    /** Pure: the integer value of `key=<n>` in a status line (word-bounded), or null. */
    fun numOf(line: String, key: String): Long? =
        Regex("(?<![A-Za-z0-9_])" + Regex.escape(key) + "=(-?\\d+)").find(line)?.groupValues?.getOrNull(1)?.toLongOrNull()

    /** Pure: ALIVE / IDLE from a work counter (null = the counter could not be read). */
    fun aliveTag(n: Long?): String = when { n == null -> "UNKNOWN"; n > 0L -> "ALIVE"; else -> "IDLE" }

    /** Pure: the summed count of [keys] in [labels], with or without the "LIFECYCLE/" prefix. */
    fun labelSum(labels: Map<String, Long>, vararg keys: String): Long =
        labels.entries.sumOf { (k, v) -> if (keys.any { k == it || k == "LIFECYCLE/$it" }) v else 0L }

    /** Pure: the summed count of every label starting with [prefix] (with or without "LIFECYCLE/"). */
    fun labelPrefixSum(labels: Map<String, Long>, prefix: String): Long =
        labels.entries.sumOf { (k, v) -> if (k.removePrefix("LIFECYCLE/").startsWith(prefix)) v else 0L }

    /** Pure: the summed `resident=<n>` counts of a SpecialistCandidateBooks7803 status line. */
    fun residentSum(line: String): Long =
        Regex("resident=(\\d+)").findAll(line).sumOf { it.groupValues[1].toLongOrNull() ?: 0L }

    private fun excerpt(s: String?, max: Int = 170): String =
        (s ?: "unavailable").replace('\n', ' ').replace(Regex("\\s+"), " ").trim().take(max)

    private fun safe(f: () -> String): String = try { f() } catch (t: Throwable) { "unavailable:${t.javaClass.simpleName}" }

    private fun row(sb: StringBuilder, name: String, alive: Long?, consumer: String, last: String) {
        sb.append("  ").append(name.padEnd(30)).append(' ').append(aliveTag(alive).padEnd(7))
            .append(" consumedBy=").append(consumer).append(" | ").append(excerpt(last)).append('\n')
    }

    /**
     * The audit block (PipelineHealthCollector, after the 8168 improvement evidence). [labels] are the collector's label
     * counters, [intake] the intake-by-source counters.
     */
    fun append(sb: StringBuilder, labels: Map<String, Long>, intake: Map<String, Long>, nowMs: Long) {
        sb.append("===== CORTEX / SCANNER / HUNTER AUDIT (8169) =====\n")
        sb.append("  row: component ALIVE|IDLE|UNKNOWN (own work counter this process) consumedBy=<decision path that calls it> | last output\n")
        val c = Cortex7885
        row(sb, "Cortex core assess/fuse §7885", c.assessedCount8169(), "FDG gate+sizing calls[" +
            "entryRefusal=${calls("ENTRY_REFUSAL")} overrule=${calls("OVERRULE")} fullAuth=${calls("FULL_AUTHORITY")} paperChoice=${calls("PAPER_CHOICE")} " +
            "conviction=${calls("CONVICTION")} sizeShape=${calls("SIZE_SHAPE")} vetoAudit=${calls("VETO_AUDIT")}]",
            "assessed=${c.assessedCount8169()} avgMs=${"%.1f".format(c.avgAssessMs8169())} ${safe { CortexVoters7885.memoLine8169() }} ${captureLine()}")
        row(sb, "Cortex capture/labels §7885", c.gradedCount8169(), "ForwardReturnLabeler7731.observe/tick",
            "graded=${c.gradedCount8169()} pending=${c.pendingCount()} ${safe { c.hygieneLine8169() }}")
        row(sb, "Scoreboard §7919 (Rp/Rl/O)", calls("ENTRY_REFUSAL"), "LiveEdgeGate7877 entryRefusal C3",
            "gate[lane reach/noRead/refuseBucket/withAuthority/refused]=${gateLine()}")
        row(sb, "Calibration v7 §7901", c.gradedCount8169(), "bucketOf(calibrated edge)", safe { c.calibrationLine8169() })
        val timing = safe { CortexTiming7900.statusLine() }
        row(sb, "Timing cortex §7900", numOf(timing, "graded"), "entryRefusal C4_WAIT_FOR_DIP", timing)
        val exit = safe { CortexExit7897.statusLine() }
        row(sb, "Exit cortex §7897", numOf(exit, "pending")?.plus(numOf(exit, "graded") ?: 0L), "exit hold/sell authority", exit)
        val xa = safe { CrossAssetCortex7931.statusLine() }
        row(sb, "Cross-asset Cortex §7931", numOf(xa, "tracked"), "crypto/markets LiveEdgeGate", xa)
        val p0 = safe { com.lifecyclebot.engine.truth.CortexPhase0Status7884.statusLine() }
        row(sb, "Cortex Phase 0 §7884", if (p0.startsWith("unavailable")) null else 1L, "marks/exits/safety guards", p0)
        val rg = safe { c.progressiveLine8006() }
        row(sb, "Re-grading §8006", numOf(rg, "retained"), "ForwardReturnLabeler7731.revise7997", rg)
        row(sb, "Grade book/walk-forward §8144", c.gradeBookSize8169(), "authority caps (walk-forward, realised)", safe { c.gradeBookLine8169() })
        row(sb, "Curve witness §8165", labelSum(labels, "FORWARD_LABEL_MARK_CURVE_CONTRADICTED_8165"), "forward label marks",
            "markContradicted=${labelSum(labels, "FORWARD_LABEL_MARK_CURVE_CONTRADICTED_8165")} (entry refusals in labeler curveWitness8165)")
        row(sb, "Median panel §8166", c.gradedCount8169(), "Learning screen Cortex panel (ui8069)", "verdict rows carry strong/neutral/refuse medians")
        val pb = safe { LanePlaybook7907.statusLine() }
        row(sb, "Lane playbooks §7907", numOf(pb, "MATCHED") ?: c.gradedCount8169(), "LiveEdgeGate playbook refusal / selected setup", pb)
        row(sb, "Stop authority §7887", 1L, "exit stops", safe { StopAuthority7887.statusLine() })
        row(sb, "Voters (static+lane+V3)", c.assessedCount8169(), "fusion", "voters=${safe { CortexVoters7885.votersLine8003() }} slow=${safe { CortexVoters7885.slowLine() }}")

        // ── discovery ──
        val sweep = safe { com.lifecyclebot.engine.market.MarketSweep7297.statusLine() }
        row(sb, "Market sweep §7297", numOf(sweep, "rows"), "LaneHunter7297.hunt", sweep)
        row(sb, "Lane hunters §7297", labelPrefixSum(labels, "LANE_HUNT_7297_PICKED_"), "SolanaMarketScanner.emitResidentHunt7807 + election claims",
            safe { com.lifecyclebot.engine.market.LaneHunter7297.auditLine8169() })
        val rh = safe { com.lifecyclebot.engine.market.ResidentHunterWorker7807.statusLine() }
        row(sb, "Resident hunters §7807", numOf(rh, "runs"), "sweep->hunt->intake (candidates only)", rh)
        row(sb, "Hunter delivery §7301", labelPrefixSum(labels, "MARKET_HUNT_7301_EMITTED_") + labelPrefixSum(labels, "MARKET_HUNT_7301_REQUEUED_"), "intake (TokenMergeQueue / emitWithRugcheck)",
            "emitted=${labelPrefixSum(labels, "MARKET_HUNT_7301_EMITTED_")} requeued=${labelPrefixSum(labels, "MARKET_HUNT_7301_REQUEUED_")} " +
                "cooldown=${labelPrefixSum(labels, "MARKET_HUNT_7301_REQUEUE_COOLDOWN_")} alreadyWatched=${labelPrefixSum(labels, "MARKET_HUNT_7301_ALREADY_WATCHED_")} " +
                "filterRejected=${labelPrefixSum(labels, "MARKET_HUNT_7301_FILTER_REJECTED_")} multiLane=${labelPrefixSum(labels, "MARKET_HUNT_7807_MULTI_LANE_CLAIM_")}")
        val th = safe { com.lifecyclebot.engine.truth.TailHunter7996.statusLine7996() }
        row(sb, "Tail hunter §7996", numOf(th, "observed"), "LiveEdgeGate decisions -> ladder replay", th)
        for (src in listOf("PUMP_FUN_NEW", "RAYDIUM_NEW_POOL", "DEX_TRENDING", "DEX_BOOSTED", "SOLANA_BLUECHIP_WATCHLIST")) {
            val n = intake.entries.sumOf { (k, v) -> if (k.contains(src)) v else 0L }
            row(sb, "Scanner $src", n, "watchlist intake", "intake=$n (all intake keys containing $src)")
        }
        val groq = safe { com.lifecyclebot.engine.market.GroqTokenScout7830.statusLine() }
        row(sb, "Groq token scout §7830", numOf(groq, "runs"), "intake (verified rows only)", groq)
        val tg = safe { com.lifecyclebot.engine.market.TelegramCallSweeper7830.statusLine() }
        row(sb, "Telegram scouts §7830", numOf(tg, "sweeps"), "intake (verified rows only)", tg)
        val ssb = safe { com.lifecyclebot.engine.ScannerSourceBrain.summary() }
        row(sb, "Scanner source brain", if (ssb.isBlank()) 0L else 1L, "intake order/weight; fed by Cortex labels", ssb)
        val acc = safe { com.lifecyclebot.engine.truth.CandidateAccumulator6430.statusLine() }
        row(sb, "Scanner dedup §6430", numOf(acc, "raw"), "none (not wired)", acc)
        val books = safe { com.lifecyclebot.engine.market.SpecialistCandidateBooks7803.statusLine() }
        row(sb, "Specialist candidate books §7803", residentSum(books), "native specialist readers", books)
        sb.append("  read: IDLE on a component whose input exists is a wiring defect; consumedBy counts are calls this process.\n")
    }
}
