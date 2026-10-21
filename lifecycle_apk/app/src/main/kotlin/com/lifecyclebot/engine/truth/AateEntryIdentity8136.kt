package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8136 §SEALED_ENTRY_IDENTITY — the exact identity of an entry decision, sealed at the canonical open and
 * carried by the AATE envelope (AateDecisionEnvelope6512.entryIdentity8136) through partial exits to the one
 * whole-position close.
 *
 * 8127: specialistLearningMissing=1. AateDecisionFabric6512 kept the position -> envelope link in memory only, so
 * a position opened before a restart and closed after it reached the reward path with no envelope: the close was
 * reported as specialist learning MISSING although the owner policy's own bind (UnifiedPolicyHead, persisted)
 * still held, and the secondary desks lost their credit. The identity (mode, mint, positionId, candidateVersion,
 * owner lane, exact setup, strategy variant, tactic, entry style, exit policy, evidence version, decision time)
 * and the decision's own features are now persisted per positionId and restored at the close.
 *
 * Attribution at the close:
 *   EXACT_ENVELOPE     the live envelope is bound (normal case)
 *   SEALED_IDENTITY    restored from the sealed identity (restart) — exact, trains like EXACT_ENVELOPE
 *   OWNER_LEDGER_ONLY  only LaneAttributionLedger6427's owner stamp: owner-lane economics, no strategy book
 *   UNVERIFIABLE       nothing: quarantined from every strategy-specific book; the economics stay on the bus for
 *                      risk, KillSwitch and the dashboard. Missing attribution is never a negative strategy sample.
 * Shadow lanes (mode SHADOW) get observational labels only.
 */
data class AateEntryIdentity8136(
    val mode: String,
    val mint: String,
    val positionId: String,
    val candidateVersion: Long,
    val ownerLane: String,
    val exactSetup: String,
    val strategyVariant: String,
    val tactic: String,
    val entryStyle: String,
    val exitPolicy: String,
    val evidenceVersion: String,
    val decisionTimestampMs: Long,
    val scoreFinal: Double,
    val pWin: Double?,
    val expectedPnlPct: Double?,
    val rugP: Double?,
    val contributors: List<AateBrainContribution6512>,
    val shadow: Boolean,
)

/** Pure, in-memory store: first seal per positionId wins; a conflicting reseal is refused; one terminal per position. */
class EntryIdentityStore8136(private val maxEntries: Int = 800) {
    private val byPosition = ConcurrentHashMap<String, AateEntryIdentity8136>()
    private val closed = ConcurrentHashMap<String, Long>()
    val sealedCount = AtomicLong(0)
    val conflicts = AtomicLong(0)
    val terminals = AtomicLong(0)

    /** Seal [id]. True when stored or the same identity is already sealed; false for a conflict or a closed position. */
    fun seal(id: AateEntryIdentity8136): Boolean {
        if (id.positionId.isBlank() || id.mint.isBlank() || id.ownerLane.isBlank()) return false
        if (closed.containsKey(id.positionId)) { conflicts.incrementAndGet(); return false }
        val prior = byPosition.putIfAbsent(id.positionId, id)
        if (prior == null) {
            sealedCount.incrementAndGet()
            if (byPosition.size > maxEntries) prune()
            return true
        }
        val same = AateEntryIdentityBook8136.sameDecision8136(prior, id)
        if (!same) conflicts.incrementAndGet()
        return same
    }

    /** Entry-time snapshot facts may fill fields the seal left blank; a sealed value is never overwritten. */
    fun fillBlanks(positionId: String, setup: String, variant: String, entryStyle: String, exitPolicy: String) {
        val p = byPosition[positionId] ?: return
        val filled = p.copy(
            // V5.0.8295 — a desk toolkit setup name is not the playbook setup the seal names (LaneSetupAttribution8295).
            exactSetup = p.exactSetup.ifBlank { if (com.lifecyclebot.engine.cortex.LaneSetupAttribution8295.deskSetup8295(setup)) "" else setup },
            strategyVariant = p.strategyVariant.ifBlank { variant },
            entryStyle = p.entryStyle.ifBlank { entryStyle },
            exitPolicy = p.exitPolicy.ifBlank { exitPolicy },
        )
        if (filled != p) byPosition.replace(positionId, p, filled)
    }

    fun get(positionId: String): AateEntryIdentity8136? = byPosition[positionId]

    /** The whole-position close: returns the identity once (null on a repeat) and retires it. */
    fun terminal(positionId: String, nowMs: Long): AateEntryIdentity8136? {
        if (positionId.isBlank()) return null
        if (closed.putIfAbsent(positionId, nowMs) != null) return null
        terminals.incrementAndGet()
        if (closed.size > maxEntries * 2) closed.entries.sortedBy { it.value }.take(maxEntries).forEach { closed.remove(it.key, it.value) }
        return byPosition.remove(positionId)
    }

    fun all(): List<AateEntryIdentity8136> = byPosition.values.toList()
    fun size(): Int = byPosition.size

    fun clear() { byPosition.clear(); closed.clear() }

    /** Restore persisted identities; never resurrects a position already closed in this process. */
    fun restore(ids: List<AateEntryIdentity8136>) {
        for (id in ids) if (!closed.containsKey(id.positionId)) byPosition.putIfAbsent(id.positionId, id)
    }

    private fun prune() {
        val excess = byPosition.size - maxEntries
        if (excess <= 0) return
        byPosition.values.sortedBy { it.decisionTimestampMs }.take(excess).forEach { byPosition.remove(it.positionId, it) }
    }
}

object AateEntryIdentityBook8136 {

    enum class Attribution8136 { EXACT_ENVELOPE, SEALED_IDENTITY, OWNER_LEDGER_ONLY, UNVERIFIABLE }

    /** Pure: the attribution of a close from what is available at the close. */
    fun attribution8136(hasEnvelope: Boolean, hasSealedIdentity: Boolean, ledgerOwner: String?): Attribution8136 = when {
        hasEnvelope -> Attribution8136.EXACT_ENVELOPE
        hasSealedIdentity -> Attribution8136.SEALED_IDENTITY
        !ledgerOwner.isNullOrBlank() -> Attribution8136.OWNER_LEDGER_ONLY
        else -> Attribution8136.UNVERIFIABLE
    }

    /** Pure: may this close update strategy-specific books (variant, setup, contributor credit)? */
    fun trainsStrategy8136(a: Attribution8136): Boolean =
        a == Attribution8136.EXACT_ENVELOPE || a == Attribution8136.SEALED_IDENTITY

    /** Pure: is this close a NEGATIVE strategy sample? Only an attributed loss; missing attribution never is. */
    fun negativeStrategySample8136(a: Attribution8136, netPnlSol: Double): Boolean =
        trainsStrategy8136(a) && netPnlSol.isFinite() && netPnlSol < 0.0

    /** Pure: shadow decisions are observed, never trained. */
    fun isShadowMode8136(mode: String): Boolean = mode.trim().equals("SHADOW", ignoreCase = true)

    /** Pure: two seals describe the same entry decision (mode, mint, candidate version, owner lane). */
    fun sameDecision8136(a: AateEntryIdentity8136, b: AateEntryIdentity8136): Boolean =
        a.mode.equals(b.mode, true) && a.mint == b.mint && a.candidateVersion == b.candidateVersion &&
            a.ownerLane.equals(b.ownerLane, true)

    /** Pure: the contributors' mean effect mapped to [0, 1], weight-averaged (AateDecisionFabric6512.attachPosition). */
    fun contributorEffect8136(contributors: List<AateBrainContribution6512>): Double {
        val w = contributors.sumOf { it.weight }.coerceAtLeast(0.0001)
        return contributors.sumOf { ((it.effect + 1.0) * 0.5).coerceIn(0.0, 1.0) * it.weight } / w
    }

    // ── encoding (pure; control characters never occur in identity text after [clean]) ──
    private const val F = '\u001F'
    private const val R = '\u001E'
    private const val C = '\u001D'
    private const val CF = '\u001C'

    private fun clean(s: String): String = s.filterNot { it == F || it == R || it == C || it == CF }
    private fun num(v: Double?): String = if (v != null && v.isFinite()) v.toString() else ""

    fun encode8136(id: AateEntryIdentity8136): String = listOf(
        clean(id.mode), clean(id.mint), clean(id.positionId), id.candidateVersion.toString(), clean(id.ownerLane),
        clean(id.exactSetup), clean(id.strategyVariant), clean(id.tactic), clean(id.entryStyle), clean(id.exitPolicy),
        clean(id.evidenceVersion), id.decisionTimestampMs.toString(), num(id.scoreFinal), num(id.pWin),
        num(id.expectedPnlPct), num(id.rugP),
        id.contributors.joinToString(C.toString()) { c ->
            listOf(clean(c.brain), clean(c.role), num(c.weight), num(c.effect)).joinToString(CF.toString())
        },
        if (id.shadow) "1" else "0",
    ).joinToString(F.toString())

    fun decode8136(s: String): AateEntryIdentity8136? = try {
        val f = s.split(F)
        if (f.size != 18 || f[2].isBlank() || f[1].isBlank() || f[4].isBlank()) null else AateEntryIdentity8136(
            mode = f[0], mint = f[1], positionId = f[2], candidateVersion = f[3].toLongOrNull() ?: 0L, ownerLane = f[4],
            exactSetup = f[5], strategyVariant = f[6], tactic = f[7], entryStyle = f[8], exitPolicy = f[9],
            evidenceVersion = f[10], decisionTimestampMs = f[11].toLongOrNull() ?: 0L,
            scoreFinal = f[12].toDoubleOrNull() ?: 0.0, pWin = f[13].toDoubleOrNull(),
            expectedPnlPct = f[14].toDoubleOrNull(), rugP = f[15].toDoubleOrNull(),
            contributors = if (f[16].isEmpty()) emptyList() else f[16].split(C).mapNotNull { cs ->
                val c = cs.split(CF)
                if (c.size != 4 || c[0].isBlank()) null
                else AateBrainContribution6512(brain = c[0], role = c[1], weight = c[2].toDoubleOrNull() ?: 0.0, effect = c[3].toDoubleOrNull() ?: 0.0)
            },
            shadow = f[17] == "1",
        )
    } catch (_: Throwable) { null }

    fun encodeAll8136(ids: List<AateEntryIdentity8136>): String = ids.joinToString(R.toString()) { encode8136(it) }

    fun decodeAll8136(s: String?): List<AateEntryIdentity8136> =
        if (s.isNullOrEmpty()) emptyList() else s.split(R).mapNotNull { decode8136(it) }

    // ── runtime store + persistence (LearningPersistence; written off the canonical position lock) ──
    private const val PERSIST_KEY_8136 = "AATE_ENTRY_IDENTITY_8136"
    private val store = EntryIdentityStore8136()
    private val attributions = ConcurrentHashMap<String, AtomicLong>()
    private val loaded = AtomicBoolean(false)
    private val persistQueued = AtomicBoolean(false)
    private val writer by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "aate-entry-identity-8136").apply { isDaemon = true } }
    }

    private fun ensureLoaded() {
        if (loaded.get()) return
        val ready = try { com.lifecyclebot.engine.LearningPersistence.ready() } catch (_: Throwable) { false }
        if (!ready || !loaded.compareAndSet(false, true)) return
        try { store.restore(decodeAll8136(com.lifecyclebot.engine.LearningPersistence.load(PERSIST_KEY_8136))) } catch (_: Throwable) {}
    }

    private fun persistSoon() {
        if (!persistQueued.compareAndSet(false, true)) return
        try {
            writer.execute {
                persistQueued.set(false)
                try {
                    // Load (merge) before the first write, on this thread: never overwrite the stored book with a partial one.
                    ensureLoaded()
                    if (loaded.get() && com.lifecyclebot.engine.LearningPersistence.ready()) {
                        com.lifecyclebot.engine.LearningPersistence.save(PERSIST_KEY_8136, encodeAll8136(store.all()))
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) { persistQueued.set(false) }
    }

    /**
     * Canonical open (AateDecisionFabric6512.attachPosition, inside the canonical position lock): seal the entry
     * identity in memory only — the persisted book is loaded and written on the writer thread, never under the lock.
     */
    fun seal8136(id: AateEntryIdentity8136): Boolean {
        val ok = store.seal(id)
        try {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc(if (ok) "AATE_ENTRY_IDENTITY_SEALED_8136" else "AATE_ENTRY_IDENTITY_SEAL_CONFLICT_8136")
        } catch (_: Throwable) {}
        if (ok) persistSoon()
        return ok
    }

    fun identity8136(positionId: String): AateEntryIdentity8136? { ensureLoaded(); return store.get(positionId) }

    fun fillBlanks8136(positionId: String, setup: String, variant: String, entryStyle: String, exitPolicy: String) {
        ensureLoaded()
        store.fillBlanks(positionId, setup, variant, entryStyle, exitPolicy)
    }

    /** The whole-position close was delivered: retire the identity (once). */
    fun terminal8136(positionId: String, nowMs: Long = System.currentTimeMillis()): AateEntryIdentity8136? {
        ensureLoaded()
        val id = store.terminal(positionId, nowMs)
        if (id != null) persistSoon()
        return id
    }

    fun noteAttribution8136(a: Attribution8136) {
        attributions.computeIfAbsent(a.name) { AtomicLong(0) }.incrementAndGet()
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("AATE_CLOSE_ATTRIBUTION_8136_${a.name}") } catch (_: Throwable) {}
    }

    internal fun resetForTest8136() { store.clear(); attributions.clear() }

    fun statusLine8136(): String =
        "entryIdentity8136[sealed=${store.sealedCount.get()} open=${store.size()} conflicts=${store.conflicts.get()} terminals=${store.terminals.get()} " +
            "closes=${Attribution8136.values().joinToString(",") { "${it.name}=${attributions[it.name]?.get() ?: 0L}" }}]"
}
