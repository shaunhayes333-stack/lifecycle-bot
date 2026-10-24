package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8364 — ONE CANDIDATE, ONE CHAIN: QUALIFIED -> (evaluated) -> FDG verdict -> OWNER_SELECTED -> BUY_INTENT -> TICKET.
 *
 * 5.0.8341 / 8344 live: every lane ownerSelectedN=0 buyIntentN=0, and the causal funnel did not show the same candidate
 * moving from qualified to its FDG refusal. Reading the code:
 *  - OWNER_SELECTED is stamped in TradeAuthorizer.authorizeLocked7835 after the LaneExecutionCoordinator election, and
 *    authorize() runs only on an FDG allow (SpecialistPreauthSeal7834.refusal refuses anything else). BUY_INTENT is stamped
 *    on entry to authorize(). So with FDG allow=0, owner=0 and intent=0 are not lost hand-offs: they are stages that sit
 *    AFTER the FDG. (The one exception is the 7951 capital-only path, which stamps OWNER+INTENT+FDG_BLOCK on a refused
 *    size.)
 *  - QUALIFIED is keyed "mint:version" (ToolkitSignalSheet causalId6647) while OWNER/INTENT/TICKET are keyed by the
 *    canonical attempt id "gen:MODE:mint:BUY:LANE:version:ticket", so the causal funnel holds two records per candidate,
 *    and an ordinary FDG refusal stamped nothing on either (only the capital-only path did). A qualified candidate
 *    therefore vanished from the per-candidate view with no terminal.
 *
 * This ledger joins every stage by the candidate identity (lane, mint, candidate version — parsed from either id shape,
 * carried to the newest open version of the same lane+mint when the version rolled), records the one terminal each
 * qualified candidate ended in (FDG_<reason>, NOT_EVALUATED_<gate>, NATIVE_REFUSED_AFTER_QUALIFY, AUTH_<...>, TICKET),
 * and counts every candidate that expired with no terminal as OWNER_LOST_NO_TERMINAL_8364_<furthest stage>.
 * Bookkeeping only: it never admits, sizes, elects or executes.
 */
object OwnershipChain8364 {
    enum class Step8364 { QUALIFIED, FDG_ALLOW, FDG_BLOCK, OWNER, INTENT, TICKET, EXEC }

    data class CandidateId8364(val mint: String, val version: Long)

    private const val TTL_MS_8364 = 600_000L
    private const val MAX_RECORDS_8364 = 4_096
    private const val SWEEP_MS_8364 = 15_000L

    private class Cand8364(val lane: String, val mint: String, val version: Long, var touchedMs: Long) {
        val steps = HashSet<Step8364>()
        var terminal = ""
        var softTerminal = ""
    }

    private val lock8364 = Any()
    private val records8364 = LinkedHashMap<String, Cand8364>(256, 0.75f, true)
    private val latestByLaneMint8364 = HashMap<String, String>()
    private val stepCounts8364 = ConcurrentHashMap<String, AtomicLong>()
    private val terminalCounts8364 = ConcurrentHashMap<String, AtomicLong>()
    private val lostCounts8364 = ConcurrentHashMap<String, AtomicLong>()
    private val versionCarried8364 = AtomicLong(0L)
    private var lastSweepMs8364 = 0L

    // ── pure ──

    /**
     * Pure: the candidate identity of a desk-stage event id. "mint:version" (the qualified / pre-intent shape) and the
     * canonical attempt "gen:MODE:mint:SIDE:LANE:version:ticket" both resolve to the same (mint, version). Null otherwise.
     */
    fun parseCandidate8364(eventId: String): CandidateId8364? {
        val p = eventId.split(':')
        if (p.size >= 7 && (p[1].equals("PAPER", true) || p[1].equals("LIVE", true))) {
            val v = p[5].toLongOrNull() ?: return null
            return if (p[2].isBlank() || v <= 0L) null else CandidateId8364(p[2], v)
        }
        if (p.size >= 2 && !p[0].equals("PAPER", true) && !p[0].equals("LIVE", true)) {
            val v = p[1].toLongOrNull() ?: return null
            return if (p[0].isBlank() || v <= 0L) null else CandidateId8364(p[0], v)
        }
        return null
    }

    /** The furthest step a candidate reached (null = none). */
    private fun furthest8364(steps: Collection<Step8364>): Step8364? = steps.maxByOrNull { it.ordinal }

    /** Pure: a short, countable reason key (head before ':' / '=' , A-Z0-9_ only, 48 chars). */
    fun reasonKey8364(reason: String?): String =
        reason.orEmpty().substringBefore(':').substringBefore('=').trim().uppercase()
            .replace(Regex("[^A-Z0-9_]"), "_").take(48).ifBlank { "UNNAMED" }

    /**
     * Pure: the name a qualified candidate that expired is counted under. A hard terminal wins; a soft one (it was not
     * let evaluate on its last cycle) is next; with neither it is lost with no terminal at its furthest stage.
     */
    fun closeKey8364(qualified: Boolean, steps: Collection<Step8364>, terminal: String, softTerminal: String): String = when {
        terminal.isNotBlank() -> "T:$terminal"
        Step8364.FDG_BLOCK in steps -> "T:FDG_BLOCK"
        softTerminal.isNotBlank() -> "T:$softTerminal"
        !qualified && steps.isEmpty() -> "T:EMPTY"
        else -> "LOST:" + (furthest8364(steps)?.name ?: "NONE")
    }

    /**
     * Pure: lanes8169 skip key for a non-owner lane with no named gate. NON_OWNER_NOT_RESCUE mixed the lanes that never
     * qualified the token (no READY book, no eligible native opinion — not a loss) with qualified specialists that found
     * no slot (a real pre-FDG loss). [gateKey] is LoadTelemetry8169.laneSkipReason8169's key.
     */
    fun nonOwnerSkipKey8364(gateKey: String, qualified: Boolean): String =
        if (gateKey != "NON_OWNER_NOT_RESCUE") gateKey else if (qualified) "NON_OWNER_QUALIFIED_NO_SLOT_8364" else "NON_OWNER_UNQUALIFIED_8364"

    // ── runtime reads ──

    private fun lane8364(raw: String?): String = try {
        com.lifecyclebot.engine.truth.CanonicalLaneIdentity6506.canonical(
            raw.orEmpty().uppercase().replace("BLUE_CHIP", "BLUECHIP").replace("SHITCOIN_EXPRESS", "EXPRESS"))
    } catch (_: Throwable) { raw.orEmpty().trim().uppercase() }

    /** The lane's own native opinion on [mint]: true eligible, false refused (authoritative), null unknown. */
    fun nativeEligible8364(mint: String, lane: String): Boolean? = try {
        val op = SpecialistBrainBridge7542.cachedSnapshot7650(mint)?.opinions?.get(lane8364(lane))
        if (op == null || !op.authoritative) null else op.eligible
    } catch (_: Throwable) { null }

    /** [lane] holds independent evidence for [mint]: a READY resident book entry or an eligible native opinion. */
    private fun laneQualified8364(mint: String, lane: String): Boolean = try {
        val l = lane8364(lane)
        com.lifecyclebot.engine.market.SpecialistCandidateBooks7803.entry(l, mint)?.state ==
            com.lifecyclebot.engine.market.SpecialistCandidateBooks7803.State.READY || nativeEligible8364(mint, l) == true
    } catch (_: Throwable) { false }

    /** LoadTelemetry8169: the skip key, with NON_OWNER_NOT_RESCUE split by whether the lane qualified the token. */
    fun skipKey8364(gateKey: String, mint: String, lane: String): String =
        if (gateKey != "NON_OWNER_NOT_RESCUE") gateKey else nonOwnerSkipKey8364(gateKey, laneQualified8364(mint, lane))

    // ── events ──

    private fun bump8364(m: ConcurrentHashMap<String, AtomicLong>, k: String) {
        if (m.size > 512 && !m.containsKey(k)) return
        m.computeIfAbsent(k) { AtomicLong(0L) }.incrementAndGet()
    }

    private fun addStep8364(c: Cand8364, s: Step8364) {
        if (c.steps.add(s)) bump8364(stepCounts8364, "${c.lane}|${s.name}")
    }

    private fun close8364(c: Cand8364) {
        val key = closeKey8364(Step8364.QUALIFIED in c.steps, c.steps, c.terminal, c.softTerminal)
        if (key == "T:EMPTY") return
        if (key.startsWith("LOST:")) {
            val stage = key.removePrefix("LOST:")
            bump8364(lostCounts8364, "${c.lane}|$stage")
            try { PipelineHealthCollector.labelInc("OWNER_LOST_NO_TERMINAL_8364_$stage") } catch (_: Throwable) {}
        } else {
            bump8364(terminalCounts8364, "${c.lane}|${key.removePrefix("T:")}")
        }
    }

    private fun sweep8364(nowMs: Long, force: Boolean = false) {
        if (!force && nowMs - lastSweepMs8364 < SWEEP_MS_8364 && records8364.size <= MAX_RECORDS_8364) return
        lastSweepMs8364 = nowMs
        val it = records8364.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            val c = e.value
            val over = records8364.size > MAX_RECORDS_8364
            if (!over && nowMs - c.touchedMs <= TTL_MS_8364) continue
            close8364(c)
            val lm = "${c.lane}|${c.mint}"
            if (latestByLaneMint8364[lm] == e.key) latestByLaneMint8364.remove(lm)
            it.remove()
        }
    }

    /**
     * The candidate record for (lane, mint, version). A stage on a rolled version joins the newest record of the same
     * lane+mint (the candidate version is a clock bucket, not a new candidate) unless it was ticketed; a QUALIFIED stamp
     * opens a new record only when the previous one already ended (FDG verdict / terminal).
     */
    private fun recordFor8364(lane: String, id: CandidateId8364, qualify: Boolean, create: Boolean, nowMs: Long): Cand8364? {
        val key = "$lane|${id.mint}|${id.version}"
        records8364[key]?.let { it.touchedMs = nowMs; return it }
        val lm = "$lane|${id.mint}"
        val latest = latestByLaneMint8364[lm]?.let { records8364[it] }
        if (latest != null && latest.terminal != "TICKET") {
            val episodeOpen = latest.terminal.isBlank() && Step8364.FDG_ALLOW !in latest.steps && Step8364.FDG_BLOCK !in latest.steps
            if (!qualify || episodeOpen) {
                versionCarried8364.incrementAndGet()
                latest.touchedMs = nowMs
                return latest
            }
        }
        if (!create) return null
        val c = Cand8364(lane, id.mint, id.version, nowMs)
        records8364[key] = c
        latestByLaneMint8364[lm] = key
        return c
    }

    /** ToolkitSignalSheet.recordDeskStage (after its dedupe): every desk stage of a candidate joins its chain. */
    fun onDeskStage8364(laneRaw: String, stageRaw: String, eventId: String, nowMs: Long = System.currentTimeMillis()) {
        try {
            val st = stageRaw.uppercase()
            val step = when (st) {
                "QUALIFIED" -> Step8364.QUALIFIED
                "FDG_ALLOW" -> Step8364.FDG_ALLOW
                "FDG_BLOCK" -> Step8364.FDG_BLOCK
                "OWNER_SELECTED" -> Step8364.OWNER
                "BUY_INTENT" -> Step8364.INTENT
                "TICKET" -> Step8364.TICKET
                "EXEC" -> Step8364.EXEC
                "AUTH_REJECT", "SUPERSEDED", "STALE" -> null
                else -> return
            }
            val id = parseCandidate8364(eventId) ?: return
            val lane = lane8364(laneRaw)
            if (lane.isBlank()) return
            synchronized(lock8364) {
                sweep8364(nowMs)
                val c = recordFor8364(lane, id, qualify = step == Step8364.QUALIFIED,
                    create = step == Step8364.QUALIFIED || step == Step8364.OWNER || step == Step8364.INTENT, nowMs = nowMs) ?: return
                if (step != null) addStep8364(c, step)
                when {
                    step == Step8364.TICKET -> c.terminal = "TICKET"
                    step == null && c.terminal != "TICKET" -> c.terminal = "AUTH_$st"
                }
            }
        } catch (_: Throwable) {}
    }

    /** SpecialistOwnership7951.onFdgVerdict7951: the lane's FDG verdict on this candidate, with its reason. */
    fun onFdgVerdict8364(laneRaw: String, mint: String, version: Long, allow: Boolean, reason: String?, nowMs: Long = System.currentTimeMillis()) {
        try {
            val lane = lane8364(laneRaw)
            if (lane.isBlank() || mint.isBlank()) return
            synchronized(lock8364) {
                sweep8364(nowMs)
                val c = (if (version > 0L) recordFor8364(lane, CandidateId8364(mint, version), qualify = false, create = false, nowMs = nowMs) else
                    latestByLaneMint8364["$lane|$mint"]?.let { records8364[it] }) ?: return
                if (allow) {
                    addStep8364(c, Step8364.FDG_ALLOW)
                    c.softTerminal = ""
                } else {
                    addStep8364(c, Step8364.FDG_BLOCK)
                    if (c.terminal.isBlank()) c.terminal = "FDG_" + reasonKey8364(reason)
                }
            }
        } catch (_: Throwable) {}
    }

    /**
     * SpecialistOwnership7951.noteReadyNotEvaluated7951: the cycle did not let [lane] evaluate [mint] ([why] = the named
     * gate, or NOT_PRIMARY_<p>). A soft terminal: a later cycle that evaluates it replaces it with the FDG verdict.
     */
    fun onNotEvaluated8364(laneRaw: String, mint: String, why: String, nowMs: Long = System.currentTimeMillis()) {
        try {
            val lane = lane8364(laneRaw)
            if (lane.isBlank() || mint.isBlank()) return
            synchronized(lock8364) {
                val c = latestByLaneMint8364["$lane|$mint"]?.let { records8364[it] } ?: return
                if (c.terminal.isNotBlank() || Step8364.FDG_ALLOW in c.steps || Step8364.FDG_BLOCK in c.steps) return
                c.touchedMs = nowMs
                c.softTerminal = "NOT_EVALUATED_" + reasonKey8364(why)
            }
        } catch (_: Throwable) {}
    }

    /** ToolkitSignalSheet: the lane's own native brain refused a candidate it had qualified (READY -> LOST). */
    fun onNativeLost8364(laneRaw: String, mint: String, nowMs: Long = System.currentTimeMillis()) {
        try {
            val lane = lane8364(laneRaw)
            if (lane.isBlank() || mint.isBlank()) return
            synchronized(lock8364) {
                val c = latestByLaneMint8364["$lane|$mint"]?.let { records8364[it] } ?: return
                if (Step8364.QUALIFIED !in c.steps || c.terminal.isNotBlank() || Step8364.FDG_ALLOW in c.steps || Step8364.FDG_BLOCK in c.steps) return
                c.touchedMs = nowMs
                c.terminal = "NATIVE_REFUSED_AFTER_QUALIFY"
            }
        } catch (_: Throwable) {}
    }

    /** The version of [lane]'s newest record for [mint] that carries QUALIFIED (the causal funnel's qualified key), else null. */
    fun qualifiedVersion8364(laneRaw: String, mint: String): Long? = try {
        val lane = lane8364(laneRaw)
        synchronized(lock8364) {
            latestByLaneMint8364["$lane|$mint"]?.let { records8364[it] }?.takeIf { Step8364.QUALIFIED in it.steps }?.version
        }
    } catch (_: Throwable) { null }

    // ── report ──

    private fun laneMap8364(m: ConcurrentHashMap<String, AtomicLong>, lane: String): Map<String, Long> {
        val prefix = "$lane|"
        return m.entries.filter { it.key.startsWith(prefix) }.associate { it.key.removePrefix(prefix) to it.value.get() }
    }

    /** "LANE chain8364[...]" — the same candidates followed stage by stage, with how each one ended. Null when silent. */
    fun reportLine8364(laneRaw: String, nowMs: Long = System.currentTimeMillis()): String? {
        val lane = lane8364(laneRaw)
        val steps = laneMap8364(stepCounts8364, lane)
        if (steps.isEmpty()) return null
        val open = synchronized(lock8364) { sweep8364(nowMs); records8364.values.count { it.lane == lane } }
        fun s(x: Step8364) = steps[x.name] ?: 0L
        val terms = laneMap8364(terminalCounts8364, lane).entries.sortedByDescending { it.value }.take(6)
            .joinToString(",") { "${it.key}=${it.value}" }.ifBlank { "-" }
        val lost = laneMap8364(lostCounts8364, lane).entries.sortedByDescending { it.value }
            .joinToString(",") { "${it.key}=${it.value}" }.ifBlank { "0" }
        return "$lane chain8364[qualified=${s(Step8364.QUALIFIED)} fdg=${s(Step8364.FDG_ALLOW)}/${s(Step8364.FDG_BLOCK)} " +
            "owner=${s(Step8364.OWNER)} intent=${s(Step8364.INTENT)} ticket=${s(Step8364.TICKET)} exec=${s(Step8364.EXEC)} open=$open " +
            "ended{$terms} lostNoTerminal{$lost}] (owner/intent are stamped after an FDG allow)"
    }

    /** Totals across lanes of candidates closed with no terminal (by furthest stage). */
    fun lostNoTerminal8364(): Map<String, Long> =
        lostCounts8364.entries.groupBy({ it.key.substringAfter('|') }, { it.value.get() }).mapValues { it.value.sum() }

    internal fun flushForTests8364(nowMs: Long = Long.MAX_VALUE / 2) {
        synchronized(lock8364) {
            for (c in records8364.values) close8364(c)
            records8364.clear(); latestByLaneMint8364.clear()
        }
    }

    internal fun resetForTests8364() {
        synchronized(lock8364) {
            records8364.clear(); latestByLaneMint8364.clear(); lastSweepMs8364 = 0L
        }
        stepCounts8364.clear(); terminalCounts8364.clear(); lostCounts8364.clear(); versionCarried8364.set(0L)
    }
}
