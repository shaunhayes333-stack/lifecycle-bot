package com.lifecyclebot.engine.truth

import java.math.BigInteger

/**
 * V5.0.8144 — P1-E: EVERY TRADE RECONCILED AGAINST THE BLOCKCHAIN.
 *
 * Device 5.0.8142: nine orphan sells, one host/canonical projection mismatch and
 * other reconciliation exceptions, each reported as a bare count with no cause.
 *
 * This is the one pure reconciliation function. Inputs are the canonical lots, the
 * chain token balances, the signed transaction ledger and the LIVE journal rows,
 * plus the durable bot-buy lineage (ReconcileLineageLedger8144, economic Buy events,
 * fill lots, confirmed BUY signatures). Output is a list of exceptions, each with a
 * NAMED cause, and the explicitly represented non-exception states (frozen, dust,
 * pending transaction, protective quarantine, owner/unowned holding).
 *
 * The equation it checks, per mint:
 *   canonical LIVE inventory == confirmed chain-held inventory
 *                               + explicitly described pending / quarantine / frozen / dust states.
 *
 * It is read-only by construction: it returns a report and never writes a journal
 * row, a close, a balance or a reward. A mismatch is reported, never repaired by
 * deleting or resetting rows. No Android dependencies, so the JVM tests run it.
 */
object ReconcileClassifier8144 {

    /** Raw amounts at or below this are dust (WalletReconciler.DUST_RAW). */
    internal const val DUST_RAW_8144: Long = 1L

    /** Canonical vs chain quantity tolerance: 1% (receipt rounding, transfer fees) plus one raw unit. */
    internal const val QTY_TOLERANCE_PCT_8144: Long = 1L

    enum class Cause8144(val inventory: Boolean) {
        /** A sell whose mint/position has no parent in the journal, the canonical book or any durable lineage. */
        ORPHAN_SELL_NO_LINEAGE(false),
        /** A sell of wallet-adopted inventory (recovered / observed-mark) that never had a bot buy. */
        ORPHAN_SELL_ADOPTED_NO_BUY(false),
        /** A sell of a mint the owner bought by hand. */
        ORPHAN_SELL_MANUAL_HOLDING(false),
        /** A LIVE BUY row without a transaction signature. */
        LIVE_BUY_NO_SIGNATURE(false),
        /**
         * V5.0.8169 — a chain-proven LIVE BUY (proofState LIVE_FINALIZED / LIVE_SIG_CONFIRMED) whose signature
         * was lost by the pre-v10 journal (no `sig` column) and is not in the signed ledger any more.
         */
        LIVE_BUY_SIG_NOT_PERSISTED(false),
        /** A LIVE BUY row whose signature the signed ledger proves failed / expired / rejected. */
        LIVE_BUY_TX_NOT_CONFIRMED(false),
        /** A LIVE BUY row with no confirmed fill quantity. */
        LIVE_BUY_NO_CONFIRMED_FILL(false),
        /** A LIVE SELL/PARTIAL row with no confirmed tokens sold. */
        LIVE_SELL_NO_CONFIRMED_QTY(false),
        /** A LIVE SELL/PARTIAL row with neither cost basis nor proceeds (net SOL proceeds unknowable). */
        LIVE_SELL_NO_PROCEEDS(false),
        /** A LIVE SELL/PARTIAL row whose signature the signed ledger proves failed / expired / rejected. */
        LIVE_SELL_TX_NOT_CONFIRMED(false),
        /** More than one accounting movement for the same signature and side. */
        DUPLICATE_SIGNATURE_MOVEMENT(false),
        /** An open LIVE lot whose immutable entry basis is missing or whose remaining exceeds its original. */
        ENTRY_BASIS_INVALID(false),
        /** Canonical holds a LIVE lot the (complete) chain read proves is not in the wallet. */
        CANONICAL_NOT_ON_CHAIN(true),
        /** The chain holds a bot-bought mint the canonical book does not own. */
        CHAIN_BOT_HOLDING_NOT_CANONICAL(true),
        /** Canonical remaining raw and chain raw disagree beyond tolerance. */
        QUANTITY_MISMATCH(true),
        /** The host wallet projection counts a holding the bot has no evidence it bought. */
        HOST_TRACKS_UNOWNED_HOLDING(true),
        /** Canonical owns a chain-held LIVE lot the host wallet projection does not count. */
        HOST_MISSING_CANONICAL_HELD(true),
    }

    /** Explicitly represented states — described, never exceptions. */
    enum class State8144 { MATCHED, FROZEN_HELD, DUST, PENDING_TX, QUARANTINED, OWNER_UNOWNED }

    data class Lot8144(
        val positionId: String,
        val mint: String,
        /** CanonicalPositionAuthority6441.Lifecycle name. */
        val lifecycle: String,
        val originalRaw: BigInteger,
        val remainingRaw: BigInteger,
        val entryCostSol: Double,
        val entrySource: String = "",
        val quarantineReason: String = "",
    )

    data class Tx8144(val signature: String, val mint: String, val side: String, val state: String)

    data class Row8144(
        val side: String,
        val mint: String,
        val positionId: String = "",
        val sig: String = "",
        val soldQty: Double = 0.0,
        val consumedRaw: BigInteger = BigInteger.ZERO,
        /** For a LIVE sell row: the cost basis of the slice (proceeds = cost + pnl). */
        val costSol: Double = 0.0,
        val grossProceedsSol: Double = 0.0,
        val reason: String = "",
        val ts: Long = 0L,
        /** For a LIVE BUY row: the confirmed fill quantity (ui / raw). */
        val fillQty: Double = 0.0,
        val fillRaw: BigInteger = BigInteger.ZERO,
        /** V5.0.8169 — the journal row's proofState (chain proof at write time). */
        val proofState: String = "",
    )

    data class Inputs8144(
        /** LIVE protective inventory (OPEN / PARTIALLY_CLOSED with qty + funded protective quarantines). */
        val lots: List<Lot8144>,
        /** mint -> raw held; null = no wallet snapshot available. */
        val chain: Map<String, BigInteger>?,
        /** Both token programs answered: absence may be inferred. */
        val chainComplete: Boolean,
        val txs: List<Tx8144>,
        /** LIVE journal rows. */
        val journal: List<Row8144>,
        /** Mints / position ids parented by durable bot-buy lineage or the canonical book (incl. closed lots). */
        val parentMints: Set<String> = emptySet(),
        val parentPositionIds: Set<String> = emptySet(),
        /** Mints the bot has evidence it bought (signed buy, fill lot, economic Buy event, lineage, journal BUY). */
        val botEvidenceMints: Set<String> = emptySet(),
        /** HostWalletTokenTracker open-for-accounting mints; null = projection not supplied. */
        val hostOpenMints: Set<String>? = null,
        val frozenMints: Set<String> = emptySet(),
        val dustMints: Set<String> = emptySet(),
        val pendingMints: Set<String> = emptySet(),
        val ownerManualMints: Set<String> = emptySet(),
    )

    data class Exception8144(val cause: Cause8144, val mint: String, val positionId: String, val detail: String)

    data class Report8144(
        val exceptions: List<Exception8144>,
        val states: Map<State8144, Int>,
        /** Sells parented only by durable lineage (the restart/adoption lineage fix), not by journal BUY or live canonical. */
        val lineageResolvedSells: Int,
        /** MATCH | MISMATCH | UNVERIFIED_NO_CHAIN | UNVERIFIED_CHAIN_PARTIAL */
        val inventory: String,
        val liveBuys: Int,
        val liveSells: Int,
        val lots: Int,
        val chainMints: Int,
    ) {
        fun count(c: Cause8144): Int = exceptions.count { it.cause == c }
        val ok: Boolean get() = exceptions.isEmpty() && inventory == "MATCH"
        val byCause: Map<Cause8144, Int> get() = exceptions.groupingBy { it.cause }.eachCount()
    }

    private val FAILED_TX_STATES_8144 = setOf("FAILED_ON_CHAIN", "EXPIRED_PROVEN", "SUBMIT_REJECTED")

    fun isSellLike8144(side: String): Boolean {
        val s = side.trim().uppercase()
        return s == "SELL" || s.startsWith("PARTIAL")
    }

    fun isBuy8144(side: String): Boolean = side.trim().equals("BUY", true)

    /** Pure: a row recording wallet-adopted inventory rather than a bot buy. */
    fun adoptedMarker8144(reason: String, positionId: String): Boolean {
        val s = (reason + "|" + positionId).uppercase()
        return s.contains("RECOVER") || s.contains("ORPHAN") || s.contains("ADOPT") || s.contains("OBSERVED_MARK")
    }

    /**
     * Pure: may a wallet holding be adopted as BOT inventory? Only with durable evidence the bot bought it,
     * and never when it is the owner's manual holding. A holding without evidence stays an explicitly
     * represented OWNER_UNOWNED state — tracked as wallet observation, never managed or sold by the bot.
     */
    fun adoptWalletHolding8144(botEvidence: Boolean, ownerManual: Boolean): Boolean = botEvidence && !ownerManual

    /**
     * Pure: classify a sell that has no parent anywhere. The order matters: the owner's manual holding first
     * (the bot must not have sold it), then adopted inventory, then a plain lineage break.
     */
    fun orphanCause8144(row: Row8144, ownerManualMints: Set<String>): Cause8144 = when {
        row.mint in ownerManualMints -> Cause8144.ORPHAN_SELL_MANUAL_HOLDING
        adoptedMarker8144(row.reason, row.positionId) -> Cause8144.ORPHAN_SELL_ADOPTED_NO_BUY
        else -> Cause8144.ORPHAN_SELL_NO_LINEAGE
    }

    /** Pure: is [rawA] within tolerance of [rawB]? */
    fun qtyMatches8144(canonicalRaw: BigInteger, chainRaw: BigInteger): Boolean {
        val diff = (canonicalRaw - chainRaw).abs()
        if (diff <= BigInteger.ONE) return true
        val base = canonicalRaw.max(chainRaw)
        return diff.multiply(BigInteger.valueOf(100)) <= base.multiply(BigInteger.valueOf(QTY_TOLERANCE_PCT_8144))
    }

    private fun live8144(lifecycle: String): Boolean = lifecycle == "OPEN" || lifecycle == "PARTIALLY_CLOSED"

    fun classify8144(i: Inputs8144): Report8144 {
        val out = ArrayList<Exception8144>()
        val states = LinkedHashMap<State8144, Int>()
        fun state(s: State8144) { states[s] = (states[s] ?: 0) + 1 }
        val failedSigs = i.txs.filter { it.state in FAILED_TX_STATES_8144 && it.signature.isNotBlank() }
            .associateBy { it.signature }

        // ── 1. journal: buys need a signature and a fill; sells need tokens, proceeds and a parent ──
        val buys = i.journal.filter { isBuy8144(it.side) }
        val sells = i.journal.filter { isSellLike8144(it.side) }
        val journalBuyMints = buys.mapTo(HashSet()) { it.mint }
        val journalBuyPids = buys.mapNotNullTo(HashSet()) { it.positionId.takeIf { p -> p.isNotBlank() } }
        val lotMints = i.lots.mapTo(HashSet()) { it.mint }
        val lotPids = i.lots.mapTo(HashSet()) { it.positionId }
        for (b in buys) {
            if (b.sig.isBlank()) out += Exception8144(
                // V5.0.8169 — a chain-proven row lost its signature in storage; only an unproven one is unsigned.
                if (AccountingIntegrity8169.chainProvenProofState8169(b.proofState)) Cause8144.LIVE_BUY_SIG_NOT_PERSISTED else Cause8144.LIVE_BUY_NO_SIGNATURE,
                b.mint, b.positionId, "reason=${b.reason.take(40)} proof=${b.proofState.take(24)}")
            else failedSigs[b.sig]?.let { out += Exception8144(Cause8144.LIVE_BUY_TX_NOT_CONFIRMED, b.mint, b.positionId, "sig=${b.sig.take(12)} state=${it.state}") }
            if (!(b.fillQty > 0.0) && b.fillRaw.signum() <= 0) {
                out += Exception8144(Cause8144.LIVE_BUY_NO_CONFIRMED_FILL, b.mint, b.positionId, "reason=${b.reason.take(40)}")
            }
        }
        var lineageResolved = 0
        for (s in sells) {
            if (s.soldQty <= 0.0 && s.consumedRaw.signum() <= 0) {
                out += Exception8144(Cause8144.LIVE_SELL_NO_CONFIRMED_QTY, s.mint, s.positionId, "reason=${s.reason.take(40)}")
            }
            if (!(s.costSol > 0.0) && !(s.grossProceedsSol > 0.0)) {
                out += Exception8144(Cause8144.LIVE_SELL_NO_PROCEEDS, s.mint, s.positionId, "reason=${s.reason.take(40)}")
            }
            if (s.sig.isNotBlank()) failedSigs[s.sig]?.let {
                out += Exception8144(Cause8144.LIVE_SELL_TX_NOT_CONFIRMED, s.mint, s.positionId, "sig=${s.sig.take(12)} state=${it.state}")
            }
            if (s.mint.isBlank()) {
                out += Exception8144(Cause8144.ORPHAN_SELL_NO_LINEAGE, "", s.positionId, "blank_mint")
                continue
            }
            val pid = s.positionId
            val directParent = s.mint in journalBuyMints || s.mint in lotMints ||
                (pid.isNotBlank() && (pid in journalBuyPids || pid in lotPids))
            if (directParent) continue
            val lineageParent = s.mint in i.parentMints || (pid.isNotBlank() && pid in i.parentPositionIds)
            if (lineageParent) { lineageResolved++; continue }
            out += Exception8144(orphanCause8144(s, i.ownerManualMints), s.mint, pid, "reason=${s.reason.take(40)}")
        }
        // one accounting movement per signature and side class
        i.journal.filter { it.sig.isNotBlank() && (isBuy8144(it.side) || isSellLike8144(it.side)) }
            .groupBy { it.sig + "|" + (if (isBuy8144(it.side)) "BUY" else "SELL") }
            .filter { it.value.size > 1 }
            .forEach { (k, rows) ->
                out += Exception8144(Cause8144.DUPLICATE_SIGNATURE_MOVEMENT, rows.first().mint, rows.first().positionId,
                    "sig=${k.substringBefore('|').take(12)} side=${k.substringAfter('|')} rows=${rows.size}")
            }

        // ── 2. lots: immutable entry basis and actual quantity ──
        for (l in i.lots) {
            if (l.lifecycle == "QUARANTINED") { state(State8144.QUARANTINED); continue }
            if (!live8144(l.lifecycle)) continue
            val bad = when {
                l.originalRaw.signum() <= 0 -> "original_raw<=0"
                l.remainingRaw > l.originalRaw -> "remaining>original"
                !(l.entryCostSol.isFinite() && l.entryCostSol > 0.0) -> "entry_cost_missing"
                else -> null
            }
            if (bad != null) out += Exception8144(Cause8144.ENTRY_BASIS_INVALID, l.mint, l.positionId, "$bad source=${l.entrySource.take(32)}")
        }

        // ── 3. inventory: canonical == chain + explicit states ──
        val dust = BigInteger.valueOf(DUST_RAW_8144)
        val canonicalRaw = HashMap<String, BigInteger>()
        for (l in i.lots) if (l.remainingRaw.signum() > 0) canonicalRaw[l.mint] = (canonicalRaw[l.mint] ?: BigInteger.ZERO) + l.remainingRaw
        val chain = i.chain
        val flaggedNotCanonical = HashSet<String>()
        val inventory: String
        if (chain == null) {
            inventory = "UNVERIFIED_NO_CHAIN"
        } else {
            val mints = LinkedHashSet<String>()
            mints += canonicalRaw.keys
            chain.forEach { (m, raw) -> if (raw > dust) mints += m }
            for (m in mints) {
                val c = canonicalRaw[m] ?: BigInteger.ZERO
                val w = chain[m] ?: BigInteger.ZERO
                when {
                    m in i.frozenMints -> state(State8144.FROZEN_HELD)
                    m in i.pendingMints -> state(State8144.PENDING_TX)
                    c.signum() == 0 && m in i.dustMints -> state(State8144.DUST)
                    c.signum() == 0 -> {
                        if (!adoptWalletHolding8144(m in i.botEvidenceMints, m in i.ownerManualMints)) state(State8144.OWNER_UNOWNED)
                        else {
                            flaggedNotCanonical += m
                            out += Exception8144(Cause8144.CHAIN_BOT_HOLDING_NOT_CANONICAL, m, "", "chainRaw=$w")
                        }
                    }
                    w <= dust -> {
                        // a partial read is a lower bound: absence proves nothing
                        if (i.chainComplete) out += Exception8144(Cause8144.CANONICAL_NOT_ON_CHAIN, m, "", "canonicalRaw=$c chainRaw=$w")
                    }
                    qtyMatches8144(c, w) -> state(State8144.MATCHED)
                    m in i.dustMints -> state(State8144.DUST)
                    else -> out += Exception8144(Cause8144.QUANTITY_MISMATCH, m, "", "canonicalRaw=$c chainRaw=$w")
                }
            }
            val host = i.hostOpenMints
            if (host != null) {
                val explained = i.frozenMints + i.pendingMints + i.dustMints
                for (m in host) {
                    if (m in canonicalRaw || m in explained) continue
                    if (!adoptWalletHolding8144(m in i.botEvidenceMints, m in i.ownerManualMints)) {
                        out += Exception8144(Cause8144.HOST_TRACKS_UNOWNED_HOLDING, m, "", "host counts a holding with no bot buy evidence")
                    } else if (m !in flaggedNotCanonical && (chain[m] ?: BigInteger.ZERO) > dust) {
                        out += Exception8144(Cause8144.CHAIN_BOT_HOLDING_NOT_CANONICAL, m, "", "host-held chainRaw=${chain[m]}")
                    }
                }
                for (m in canonicalRaw.keys) {
                    if (m in host || m in explained) continue
                    if ((chain[m] ?: BigInteger.ZERO) > dust) {
                        out += Exception8144(Cause8144.HOST_MISSING_CANONICAL_HELD, m, "", "canonicalRaw=${canonicalRaw[m]} chainRaw=${chain[m]}")
                    }
                }
            }
            inventory = when {
                out.any { it.cause.inventory } -> "MISMATCH"
                !i.chainComplete -> "UNVERIFIED_CHAIN_PARTIAL"
                else -> "MATCH"
            }
        }
        return Report8144(
            exceptions = out, states = states, lineageResolvedSells = lineageResolved, inventory = inventory,
            liveBuys = buys.size, liveSells = sells.size, lots = i.lots.size, chainMints = chain?.count { it.value > dust } ?: 0,
        )
    }

    /** One line for the pipeline dump. */
    fun statusLine8144(r: Report8144?, ageMs: Long, runs: Long, error: String = ""): String {
        if (r == null) return "reconcile8144 not run yet runs=$runs" + (if (error.isNotBlank()) " lastError=${error.take(60)}" else "")
        val causes = r.byCause.entries.sortedByDescending { it.value }.joinToString(",") { "${it.key.name}=${it.value}" }.ifBlank { "none" }
        val st = State8144.values().joinToString(",") { "${it.name}=${r.states[it] ?: 0}" }
        val first = r.exceptions.take(3).joinToString("") { " [${it.cause.name} ${it.mint.take(6)} ${it.detail.take(40)}]" }
        return "reconcile8144 ok=${r.ok} inventory=${r.inventory} exceptions=${r.exceptions.size} causes[$causes] states[$st] " +
            "lineageResolvedSells=${r.lineageResolvedSells} buys=${r.liveBuys} sells=${r.liveSells} lots=${r.lots} chainMints=${r.chainMints} " +
            "ageS=${ageMs / 1000} runs=$runs$first" + (if (error.isNotBlank()) " lastError=${error.take(60)}" else "")
    }
}
