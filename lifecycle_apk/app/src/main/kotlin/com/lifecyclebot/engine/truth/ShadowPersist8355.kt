package com.lifecyclebot.engine.truth

/**
 * V5.0.8355 §SHADOW_EVIDENCE_SURVIVES_RESTARTS — both shadow books (EvidenceShadow8344, ExploreShadow8347) persisted their
 * CLOSES but kept their OPEN positions / tickets in memory only. The owner installs a build about once an hour (every
 * install restarts the service) and a shadow position needs 30–120 min to close, so no shadow position ever lived long
 * enough to close and the evidence hold could never lift.
 *
 * This is the pure part: one compact string per open record (versioned prefix `v1|`), the restore-age rule and the
 * write-coalescing rule. The books keep their own SharedPreferences for the open records (names in the books, separate
 * from the close books, which are untouched) and call these to encode on write and decode on init.
 *
 * Nothing here invents data: a restored record carries its real entry mark, entry time, banked slices and last mark; it
 * is never re-priced. A record older than its own timeout + lost-mark window is dropped on restore (counted), because the
 * book would have closed it long before and no honest mark for that close exists any more.
 */
object ShadowPersist8355 {
    const val VERSION_8355 = "v1"
    const val COALESCE_MS_8355 = 10_000L
    const val LOST_MARK_MS_8355 = 10 * 60_000L
    private const val F = '|'      // field separator
    private const val LEG_SEP = ';' // legs within a ticket
    private const val LEG_F = ','   // fields within a leg

    /** An open EvidenceShadow8344 position, every field the book keeps. */
    data class PosRec8355(
        val mint: String, val lane: String, val entryPx: Double, val atMs: Long, val costPct: Double, val stopPct: Double,
        val armPct: Double, val timeoutMs: Long, val held: Double, val bankedGrossPct: Double, val tiersHit: Int,
        val peakPct: Double, val lastPx: Double, val lastPxAtMs: Long,
        // V5.0.8366 — the lifecycle hold a +100 % position is promoted into (ShadowAlign8366): appended, so a pre-8366 record decodes
        val hold8366: Boolean = false, val taken8366: Int = 0, val peakAt8366: Long = 0L,
        val advisoryEntry8393: Boolean = false, // absent on legacy records; never retroactively reclassified
        val exitAt8394: Long = 0L, val exitWhy8394: String = "",
        val sellFraction8394: Double = 1.0, val sellTier8394: Int = 0, val sellTaken8394: Int = 0,
        val contractVersion8394: Int = 0,
    )

    /** One ExploreShadow8347 leg (entry / exit variant) with its full state, dead legs included. */
    data class LegRec8355(
        val entry: String, val exit: String, val active: Boolean, val dead: Boolean, val entryPx: Double, val entryAtMs: Long,
        val held: Double, val banked: Double, val tiers: Int, val peak: Double, val trough: Double,
        // V5.0.8366 — the lifecycle hold (ShadowAlign8366): appended leg fields, so a pre-8366 leg (11 fields) decodes
        val hold8366: Boolean = false, val taken8366: Int = 0, val peakAt8366: Long = 0L,
        // V5.0.8372 — a triggered exit retains its original cause and latency clock across restart.
        val exitAt8366: Long = 0L, val exitWhy8366: String = "",
        val sellFraction8394: Double = 1.0, val sellTier8394: Int = 0, val sellTaken8394: Int = 0,
    )

    /**
     * V5.0.8372 — one decision, one learning outcome. The exit is chosen BEFORE the trade; the other modelled exit is
     * still a useful counterfactual, but never a second vote-training example. A terminal value is retained until the
     * Cortex acknowledges its exact (mint, lane, assessment time) receipt. Missing/failed fills are explicit unavailable
     * outcomes, not zero returns or fee-only token losses. Empty exit denotes a pre-8372 ticket: no historical prediction
     * is reconstructed for it.
     */
    data class CortexShadowOutcome8372(
        val exit: String = "",
        val atMs: Long = 0L,
        val netPct: Double = Double.NaN,
        val grossPct: Double = Double.NaN,
        val unavailable: String = "",
        val delivered: Boolean = false,
    ) {
        fun owns8372(entry: String, legExit: String): Boolean =
            exit in setOf("RUNNER", "SCALP") && entry == "NOW" && legExit == exit

        fun pending8372(): Boolean = exit.isNotBlank() && atMs > 0L && !delivered

        fun awaiting8372(): Boolean = exit.isNotBlank() && !delivered

        fun closed8372(entry: String, legExit: String, net: Double, gross: Double, why: String, nowMs: Long,
                       executable: Boolean): CortexShadowOutcome8372 {
            if (!owns8372(entry, legExit) || atMs > 0L || delivered) return this
            if (!executable || !net.isFinite() || !gross.isFinite())
                return unavailable8372(why.ifBlank { "INVALID_TERMINAL_MARK_8372" }, nowMs)
            return copy(atMs = nowMs, netPct = net, grossPct = gross)
        }

        fun unavailable8372(why: String, nowMs: Long): CortexShadowOutcome8372 {
            if (!awaiting8372() || atMs > 0L) return this
            return copy(atMs = nowMs, unavailable = why.ifBlank { "SHADOW_UNAVAILABLE_8372" })
        }

        fun acknowledged8372(): CortexShadowOutcome8372 = if (pending8372()) copy(delivered = true) else this
    }

    /** An open ExploreShadow8347 ticket with its legs. */
    data class TicketRec8355(
        val mint: String, val symbol: String, val lane: String, val setup: String, val tactic: String, val scoreBand: String,
        val band: String, val signalPx: Double, val atMs: Long, val costPct: Double, val stopPct: Double, val armPct: Double,
        val runner: Boolean, val nativeEligible: Boolean, val lastPx: Double, val lastPxAtMs: Long, val legs: List<LegRec8355>,
        // V5.0.8360 — the ticket's kind (NATIVE / REJECT / SETUP) and the source that priced its entry; appended fields, so a
        // pre-8360 record (19 fields) still decodes ("" = derive the kind from nativeEligible).
        val kind8360: String = "", val markSrc8360: String = "",
        // V5.0.8372 — appended: old books stay intact; old tickets carry no invented prediction receipt.
        val cortex8372: CortexShadowOutcome8372 = CortexShadowOutcome8372(),
        val entryPullbackPct8394: Double = Double.NaN, val contractVersion8394: Int = 0,
    )

    /** One liveEntryWait8347 wait: the first admit's time and mark for mint|lane. */
    data class WaitRec8355(val key: String, val firstAtMs: Long, val signalPx: Double, val entryPullbackPct8394: Double = Double.NaN, val contractVersion8394: Int = 0)

    // ── pure rules ──

    /** The restore-age rule: a record whose own window ([timeoutMs] + [lostMarkMs]) has passed since [atMs] is dropped. */
    fun expiredOnRestore8355(atMs: Long, timeoutMs: Long, nowMs: Long, lostMarkMs: Long = LOST_MARK_MS_8355): Boolean =
        atMs <= 0L || nowMs - atMs > timeoutMs + lostMarkMs

    /** The coalescing rule: a forced write (open, arm, close) always goes; a changed record waits [coalesceMs] since its last write. */
    fun writeDue8355(dirty: Boolean, lastWriteMs: Long, nowMs: Long, force: Boolean = false, coalesceMs: Long = COALESCE_MS_8355): Boolean =
        force || (dirty && (lastWriteMs <= 0L || nowMs - lastWriteMs >= coalesceMs || nowMs < lastWriteMs))

    /** A delayed sell needs an actual observation after its trigger + latency, not a reread of the trigger mark. */
    fun exitFillObserved8372(observedAtMs: Long, triggerAtMs: Long, latencyMs: Long, nowMs: Long): Boolean =
        observedAtMs > 0L && triggerAtMs > 0L && latencyMs >= 0L && observedAtMs <= nowMs &&
            observedAtMs >= triggerAtMs && observedAtMs - triggerAtMs >= latencyMs

    /** A routed SPL address does not turn a stock/FX/crypto/short trader into the Solana long-only fill model. */
    fun crossAssetShadowLane8372(laneRaw: String): Boolean {
        val lane = laneRaw.trim().substringBefore('@').uppercase()
        return lane in setOf("CRYPTO", "FOREX", "EVM", "PERP", "PERPS", "SHORT") ||
            listOf("MKT_", "CRYPTO_", "EVM_", "FOREX_", "PERP_", "PERPS_", "SHORT_").any { lane.startsWith(it) } ||
            lane.endsWith("_SHORT")
    }

    /** The filled numerator and unfilled denominator always use the same isolated Cortex populations. */
    fun cortexPopulationKeys8372(lane: String, entry: String, exit: String, kind: String,
                                setup: String, tactic: String): List<String> {
        if (kind !in setOf("CORTEX_YES", "CORTEX_NO", "CORTEX_HOLD")) return emptyList()
        val v = "$lane|$entry|$exit"
        val verdict = kind.removePrefix("CORTEX_")
        return listOf("C|$v|CX:$verdict", "C|$v|CXS:$verdict:${setup.take(48)}", "C|$v|CXT:$verdict:$tactic")
    }

    const val TRADE_CONTRACT_8394 = 8394
    fun proofEligible8394(version: Double?, openedAtMs: Double?, sinceMs: Long): Boolean =
        version == TRADE_CONTRACT_8394.toDouble() && openedAtMs != null && openedAtMs.isFinite() &&
            openedAtMs >= maxOf(sinceMs, LearningEpoch8164.epochMs8164())

    // ── encoding ──

    private fun clean(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(if (c == F || c == LEG_SEP || c == LEG_F || c == '\n' || c == '\r') '_' else c)
        return sb.toString()
    }

    private fun d(x: Double): String = if (x.isFinite()) x.toString() else "NaN"
    private fun b(x: Boolean): String = if (x) "1" else "0"
    private fun pd(s: String?): Double? = s?.toDoubleOrNull()
    private fun pl(s: String?): Long? = s?.toLongOrNull()
    private fun pi(s: String?): Int? = s?.toIntOrNull()
    private fun pb(s: String?): Boolean? = when (s) { "1" -> true; "0" -> false; else -> null }

    fun encodePos8355(r: PosRec8355): String = listOf(
        VERSION_8355, "P", clean(r.mint), clean(r.lane), d(r.entryPx), r.atMs.toString(), d(r.costPct), d(r.stopPct), d(r.armPct),
        r.timeoutMs.toString(), d(r.held), d(r.bankedGrossPct), r.tiersHit.toString(), d(r.peakPct), d(r.lastPx), r.lastPxAtMs.toString(),
        b(r.hold8366), r.taken8366.toString(), r.peakAt8366.toString(),   // V5.0.8366
        b(r.advisoryEntry8393),
        r.exitAt8394.toString(), clean(r.exitWhy8394), d(r.sellFraction8394), r.sellTier8394.toString(), r.sellTaken8394.toString(), r.contractVersion8394.toString(),
    ).joinToString(F.toString())

    fun decodePos8355(s: String?): PosRec8355? {
        if (s == null) return null
        val p = s.split(F)
        if (p.size < 16 || p[0] != VERSION_8355 || p[1] != "P") return null
        val entryPx = pd(p[4]) ?: return null
        val atMs = pl(p[5]) ?: return null
        if (p[2].isBlank() || !(entryPx > 0.0) || atMs <= 0L) return null
        return PosRec8355(
            mint = p[2], lane = p[3], entryPx = entryPx, atMs = atMs,
            costPct = pd(p[6]) ?: return null, stopPct = pd(p[7]) ?: return null, armPct = pd(p[8]) ?: return null,
            timeoutMs = pl(p[9]) ?: return null, held = pd(p[10]) ?: return null, bankedGrossPct = pd(p[11]) ?: return null,
            tiersHit = pi(p[12]) ?: return null, peakPct = pd(p[13]) ?: return null, lastPx = pd(p[14]) ?: return null,
            lastPxAtMs = pl(p[15]) ?: return null,
            hold8366 = pb(p.getOrNull(16)) ?: false, taken8366 = pi(p.getOrNull(17)) ?: 0, peakAt8366 = pl(p.getOrNull(18)) ?: 0L,   // V5.0.8366
            advisoryEntry8393 = pb(p.getOrNull(19)) ?: false,
            exitAt8394 = pl(p.getOrNull(20)) ?: 0L, exitWhy8394 = p.getOrNull(21) ?: "",
            sellFraction8394 = pd(p.getOrNull(22)) ?: 1.0, sellTier8394 = pi(p.getOrNull(23)) ?: 0,
            sellTaken8394 = pi(p.getOrNull(24)) ?: 0, contractVersion8394 = pi(p.getOrNull(25)) ?: 0,
        )
    }

    private fun encodeLeg(l: LegRec8355): String = listOf(
        clean(l.entry), clean(l.exit), b(l.active), b(l.dead), d(l.entryPx), l.entryAtMs.toString(), d(l.held), d(l.banked),
        l.tiers.toString(), d(l.peak), d(l.trough),
        b(l.hold8366), l.taken8366.toString(), l.peakAt8366.toString(),   // V5.0.8366
        l.exitAt8366.toString(), clean(l.exitWhy8366),   // V5.0.8372
        d(l.sellFraction8394), l.sellTier8394.toString(), l.sellTaken8394.toString(),
    ).joinToString(LEG_F.toString())

    private fun decodeLeg(s: String): LegRec8355? {
        val p = s.split(LEG_F)
        if (p.size < 11 || p[0].isBlank() || p[1].isBlank()) return null
        return LegRec8355(
            entry = p[0], exit = p[1], active = pb(p[2]) ?: return null, dead = pb(p[3]) ?: return null,
            entryPx = pd(p[4]) ?: return null, entryAtMs = pl(p[5]) ?: return null, held = pd(p[6]) ?: return null,
            banked = pd(p[7]) ?: return null, tiers = pi(p[8]) ?: return null, peak = pd(p[9]) ?: return null,
            trough = pd(p[10]) ?: return null,
            hold8366 = pb(p.getOrNull(11)) ?: false, taken8366 = pi(p.getOrNull(12)) ?: 0, peakAt8366 = pl(p.getOrNull(13)) ?: 0L,   // V5.0.8366
            exitAt8366 = pl(p.getOrNull(14)) ?: 0L, exitWhy8366 = p.getOrNull(15) ?: "",   // V5.0.8372
            sellFraction8394 = pd(p.getOrNull(16)) ?: 1.0, sellTier8394 = pi(p.getOrNull(17)) ?: 0, sellTaken8394 = pi(p.getOrNull(18)) ?: 0,
        )
    }

    fun encodeTicket8355(r: TicketRec8355): String = listOf(
        VERSION_8355, "T", clean(r.mint), clean(r.symbol), clean(r.lane), clean(r.setup), clean(r.tactic), clean(r.scoreBand), clean(r.band),
        d(r.signalPx), r.atMs.toString(), d(r.costPct), d(r.stopPct), d(r.armPct), b(r.runner), b(r.nativeEligible),
        d(r.lastPx), r.lastPxAtMs.toString(), r.legs.joinToString(LEG_SEP.toString()) { encodeLeg(it) },
        clean(r.kind8360), clean(r.markSrc8360),   // V5.0.8360
        clean(r.cortex8372.exit), r.cortex8372.atMs.toString(), d(r.cortex8372.netPct), d(r.cortex8372.grossPct),
        clean(r.cortex8372.unavailable), b(r.cortex8372.delivered),   // V5.0.8372
        d(r.entryPullbackPct8394), r.contractVersion8394.toString(),
    ).joinToString(F.toString())

    fun decodeTicket8355(s: String?): TicketRec8355? {
        if (s == null) return null
        val p = s.split(F)
        if (p.size < 19 || p[0] != VERSION_8355 || p[1] != "T") return null
        val signalPx = pd(p[9]) ?: return null
        val atMs = pl(p[10]) ?: return null
        if (p[2].isBlank() || !(signalPx > 0.0) || atMs <= 0L) return null
        val legs = ArrayList<LegRec8355>()
        for (row in p[18].split(LEG_SEP)) {
            if (row.isBlank()) continue
            legs += decodeLeg(row) ?: return null
        }
        if (legs.isEmpty()) return null
        return TicketRec8355(
            mint = p[2], symbol = p[3], lane = p[4], setup = p[5], tactic = p[6], scoreBand = p[7], band = p[8],
            signalPx = signalPx, atMs = atMs, costPct = pd(p[11]) ?: return null, stopPct = pd(p[12]) ?: return null,
            armPct = pd(p[13]) ?: return null, runner = pb(p[14]) ?: return null, nativeEligible = pb(p[15]) ?: return null,
            lastPx = pd(p[16]) ?: return null, lastPxAtMs = pl(p[17]) ?: return null, legs = legs,
            kind8360 = p.getOrNull(19) ?: "", markSrc8360 = p.getOrNull(20) ?: "",   // V5.0.8360
            cortex8372 = CortexShadowOutcome8372(
                exit = p.getOrNull(21)?.takeIf { it == "RUNNER" || it == "SCALP" } ?: "",
                atMs = pl(p.getOrNull(22)) ?: 0L, netPct = pd(p.getOrNull(23)) ?: Double.NaN,
                grossPct = pd(p.getOrNull(24)) ?: Double.NaN, unavailable = p.getOrNull(25) ?: "",
                delivered = pb(p.getOrNull(26)) ?: false,
            ),
            entryPullbackPct8394 = pd(p.getOrNull(27)) ?: Double.NaN, contractVersion8394 = pi(p.getOrNull(28)) ?: 0,
        )
    }

    /** The whole waits map in one string: `v1|W|key,firstAtMs,signalPx;...` (the key is mint|lane, so its '|' is kept). */
    fun encodeWaits8355(ws: List<WaitRec8355>): String =
        VERSION_8355 + F + "W" + F + ws.joinToString(LEG_SEP.toString()) { "${it.key.replace(LEG_SEP, '_').replace(LEG_F, '_')}$LEG_F${it.firstAtMs}$LEG_F${d(it.signalPx)}$LEG_F${d(it.entryPullbackPct8394)}$LEG_F${it.contractVersion8394}" }

    fun decodeWaits8355(s: String?): List<WaitRec8355> {
        if (s == null) return emptyList()
        val head = VERSION_8355 + F + "W" + F
        if (!s.startsWith(head)) return emptyList()
        val out = ArrayList<WaitRec8355>()
        for (row in s.substring(head.length).split(LEG_SEP)) {
            if (row.isBlank()) continue
            val p = row.split(LEG_F)
            if (p.size < 3) continue
            val at = pl(p[1]) ?: continue
            val px = pd(p[2]) ?: continue
            if (p[0].isBlank() || at <= 0L || !(px > 0.0)) continue
            out += WaitRec8355(p[0], at, px, pd(p.getOrNull(3)) ?: Double.NaN, pi(p.getOrNull(4)) ?: 0)
        }
        return out
    }
}
