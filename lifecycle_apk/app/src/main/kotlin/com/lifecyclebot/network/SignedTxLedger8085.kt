package com.lifecyclebot.network

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.8085 §SIGNED_TX_IDENTITY — the one canonical tracker of every locally signed live Solana transaction.
 *
 * WHY. A signed transaction's id is its first signature, which this wallet computes itself (SolanaSigningEnvelope
 * proves the wallet is the sole signer, so slot 0 is ours). Before 8085 that id existed only if a provider echoed it:
 * a send timeout, a blank JSON-RPC result, an HTTP 200 carrying a JSON-RPC error after another transport had already
 * delivered the bytes, or a confirmation-wait throw all surfaced as "NO_SIGNATURE"/"sendTransaction failed" — and the
 * sell ladder built a SECOND sell (or the buy fell through to a second venue) while the first could still land.
 *
 * CONTRACT. The signature is derived from the signed bytes and persisted (with the bytes, recent blockhash, mint,
 * side, raw quantity, position, intent and route) BEFORE any submission. States: BUILT, SIGNED, SUBMIT_ATTEMPTED,
 * ACCEPTED, CONFIRMED, FAILED_ON_CHAIN, EXPIRED_PROVEN, OUTCOME_UNKNOWN. Provider acknowledgement only moves a row to
 * ACCEPTED; CONFIRMED / FAILED_ON_CHAIN come from signature status, EXPIRED_PROVEN from the blockhash being invalid
 * while the signature is unseen. While a row for (mint, side) is unresolved no other transaction may be signed for it.
 */
object SignedTxLedger8085 {
    enum class State { BUILT, SIGNED, SUBMIT_ATTEMPTED, ACCEPTED, CONFIRMED, FAILED_ON_CHAIN, EXPIRED_PROVEN, OUTCOME_UNKNOWN, SUBMIT_REJECTED }

    /** How one submission attempt ended, provider-side only (never economic truth). */
    enum class Submit { ACCEPTED, REJECTED, UNKNOWN }

    class Entry(
        val signature: String, val signedB64: String, val blockhash: String, val mint: String, val side: String,
        val rawQty: Long, val positionId: String, val intentId: String, val route: String, val createdAtMs: Long,
        @Volatile var state: State, @Volatile var updatedAtMs: Long, @Volatile var acceptedBy: String = "",
        @Volatile var detail: String = "",
        // V5.0.8087 — first real submission time (expiry attribution: sign -> submit -> expiry).
        @Volatile var submittedAtMs8087: Long = 0L,
    ) {
        val unresolved: Boolean get() = state == State.SIGNED || state == State.SUBMIT_ATTEMPTED ||
            state == State.ACCEPTED || state == State.OUTCOME_UNKNOWN
    }

    /** The caller's intent for the NEXT signature on this thread (consumed once, so it never leaks to a later send). */
    class Intent(val mint: String, val side: String, val rawQty: Long = 0L, val positionId: String = "", val intentId: String = "", val route: String = "")

    private val intent = ThreadLocal<Intent?>()
    private val rows = ConcurrentHashMap<String, Entry>()
    private const val MAX_ROWS = 400

    fun intend(i: Intent) { intent.set(i) }
    fun consumeIntent(): Intent? = intent.get().also { intent.set(null) }

    @Volatile var persister: ((List<Entry>) -> Unit)? = null

    @Synchronized fun record(signature: String, signedB64: String, blockhash: String, i: Intent?, nowMs: Long = System.currentTimeMillis(), requireDurable: Boolean = false): Entry {
        if (i != null) unresolvedFor(i.mint, i.side, exceptSignature = signature)?.let {
            throw SolanaWallet.DuplicateOrderPrevented8085(it.signature, i.side)
        }
        val e = rows[signature] ?: Entry(signature, signedB64, blockhash, i?.mint.orEmpty(), i?.side.orEmpty(), i?.rawQty ?: 0L,
            i?.positionId.orEmpty(), i?.intentId.orEmpty(), i?.route.orEmpty(), nowMs, State.SIGNED, nowMs)
        rows[signature] = e
        trim(nowMs)
        if (requireDurable) {
            val writer = persister ?: throw IllegalStateException("SIGNED_ORDER_STORAGE_UNAVAILABLE")
            writer(durableRows8094(rows.values.toList())) // throws before broadcast; retain the signed identity for retry/proof
        } else persist()
        count("TX_SIGNED_SIGNATURE_PERSISTED_8085")
        return e
    }

    fun get(signature: String): Entry? = rows[signature]

    fun historyFor8090(mint: String): List<Entry> = rows.values.filter { it.mint == mint }

    /** V5.0.8144 — every row (in-memory ledger, resolved + unresolved) for the reconciliation classifier. */
    fun allRows8144(): List<Entry> = rows.values.toList()

    fun transition(signature: String, to: State, detail: String = "", acceptedBy: String = "", nowMs: Long = System.currentTimeMillis()) {
        val e = rows[signature] ?: return
        // terminal chain truth never moves back to a provider-side state
        if (e.state == State.CONFIRMED || e.state == State.FAILED_ON_CHAIN || e.state == State.EXPIRED_PROVEN || e.state == State.SUBMIT_REJECTED) return
        if (to == State.SUBMIT_ATTEMPTED && e.state != State.SIGNED) return
        if (to == State.OUTCOME_UNKNOWN && e.state == State.ACCEPTED && acceptedBy.isBlank()) { e.detail = detail; return }
        if (e.side == "SELL") stage8087(e.mint, to)
        if (to == State.SUBMIT_ATTEMPTED && e.submittedAtMs8087 == 0L) e.submittedAtMs8087 = nowMs
        if (to == State.EXPIRED_PROVEN) attributeExpiry8087(e, detail, nowMs)
        e.state = to; e.updatedAtMs = nowMs
        if (detail.isNotBlank()) e.detail = detail.take(160)
        if (acceptedBy.isNotBlank()) e.acceptedBy = (if (e.acceptedBy.isBlank()) acceptedBy else "${e.acceptedBy},$acceptedBy").take(80)
        when (to) {
            State.ACCEPTED -> count("TX_RPC_ACCEPTED_8085")
            State.OUTCOME_UNKNOWN -> count("TX_SUBMIT_OUTCOME_UNKNOWN_8085")
            State.EXPIRED_PROVEN -> count("TX_PROVEN_EXPIRED_8085")
            else -> {}
        }
        persist()
    }

    /** The unresolved row signed for (mint, side), other than [exceptSignature], or null. */
    fun unresolvedFor(mint: String, side: String, exceptSignature: String = "", nowMs: Long = System.currentTimeMillis()): Entry? {
        if (mint.isBlank()) return null
        return rows.values.filter { it.mint == mint && it.side == side && it.signature != exceptSignature && it.unresolved }.maxByOrNull { it.createdAtMs }
    }

    fun unresolved(nowMs: Long = System.currentTimeMillis()): List<Entry> =
        rows.values.filter { it.unresolved }

    private fun trim(nowMs: Long) {
        if (rows.size <= MAX_ROWS) return
        rows.values.filter { !it.unresolved }.sortedBy { it.updatedAtMs }.take(rows.size - MAX_ROWS)
            .forEach { rows.remove(it.signature) }
    }

    private fun persist() { try { persister?.invoke(durableRows8094(rows.values.toList())) } catch (_: Throwable) {} }

    /**
     * V5.0.8094 — what is written to disk: every unresolved row and the newest [KEEP_RESOLVED_ON_DISK_8094] resolved ones.
     * Every write used to re-encrypt up to 400 rows, each with the full signed bytes, in one string on each sign and
     * state change (8094: SIGNED_ORDER_STORAGE_COMMIT_FAILED refused a live buy, plus heap churn). The in-memory ledger
     * is unchanged.
     */
    fun durableRows8094(all: List<Entry>): List<Entry> =
        all.filter { it.unresolved } + all.filter { !it.unresolved }.sortedByDescending { it.updatedAtMs }.take(KEEP_RESOLVED_ON_DISK_8094)

    const val KEEP_RESOLVED_ON_DISK_8094 = 60

    // ── durable row codec (no org.json: pure JVM, field order fixed; base64 bytes contain no '|') ──
    // V5.0.8094 — a resolved row's bytes are never re-broadcast, so they are not written.
    fun encode(e: Entry): String = listOf(e.signature, if (e.unresolved) e.signedB64 else "", e.blockhash, e.mint, e.side, e.rawQty.toString(), e.positionId,
        e.intentId, e.route, e.createdAtMs.toString(), e.state.name, e.updatedAtMs.toString(), e.acceptedBy, e.detail, e.submittedAtMs8087.toString())
        .joinToString("|") { it.replace("|", "/") }

    fun decode(line: String): Entry? = try {
        val f = line.split('|')
        if (f.size < 14) null else Entry(f[0], f[1], f[2], f[3], f[4], f[5].toLong(), f[6], f[7], f[8], f[9].toLong(),
            State.valueOf(f[10]), f[11].toLong(), f[12], f[13], f.getOrNull(14)?.toLongOrNull() ?: 0L)
    } catch (_: Throwable) { null }

    /** Restore rows saved before a restart; unresolved ones are handed to [onRecovered] for a finality read. */
    fun restore(lines: List<String>, onRecovered: (Entry) -> Unit = {}): Int {
        var n = 0
        for (l in lines) decode(l)?.let { e ->
            if (rows.putIfAbsent(e.signature, e) == null) {
                n++
                if (e.unresolved) { count("TX_SIGNATURE_RECOVERED_8085"); try { onRecovered(e) } catch (_: Throwable) {} }
            }
        }
        return n
    }

    // ── pure classification (unit-tested) ──

    /**
     * One JSON-RPC sendTransaction reply. [httpOk] is transport success only; acceptance needs a non-blank result
     * equal to the local signature. An "already processed/known" error is acceptance of these exact bytes.
     */
    fun classifyRpcReply(httpOk: Boolean, result: String?, error: String?, localSignature: String): Submit = when {
        !httpOk -> Submit.UNKNOWN
        !result.isNullOrBlank() -> if (result == localSignature) Submit.ACCEPTED else Submit.UNKNOWN
        !error.isNullOrBlank() -> if (alreadyProcessed(error)) Submit.ACCEPTED else Submit.REJECTED
        else -> Submit.UNKNOWN   // HTTP 200 with neither result nor error: not acceptance, not rejection
    }

    fun alreadyProcessed(msg: String): Boolean {
        val m = msg.lowercase()
        return m.contains("already processed") || m.contains("alreadyprocessed") || m.contains("already known")
    }

    /** A later simulation observes a different bank: it cannot disprove an earlier broadcast. */
    fun rejectionIsDefinite(msg: String, otherCopyMaybeDelivered: Boolean, jitoCopySent: Boolean): Boolean =
        !otherCopyMaybeDelivered && !jitoCopySent

    /** Signature unseen + blockhash no longer valid = the transaction can never land. Unknown validity proves nothing. */
    fun expiredProven(statusSeen: Boolean, blockhashValid: Boolean?): Boolean = !statusSeen && blockhashValid == false

    /** V5.0.8094 — a blockhash lives ~150 slots (~60-90 s); 3 minutes is past any landing. */


    /**
     * V5.0.8087 — every proven expiry is attributed: route, signed age at expiry, sign -> submit delay (a stale
     * build signed long after its quote), and whether any provider ever accepted the bytes. 8086 live: 24 proven
     * expiries beside 21 confirmations, with nothing saying which route or which delay produced them.
     */
    private val expiryByRoute8087 = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()

    private fun attributeExpiry8087(e: Entry, detail: String, nowMs: Long) {
        val route = e.route.ifBlank { "UNKNOWN" }
        val signToSubmitMs = if (e.submittedAtMs8087 > 0L) e.submittedAtMs8087 - e.createdAtMs else -1L
        val ageMs = nowMs - e.createdAtMs
        val accepted = if (e.acceptedBy.isBlank()) "NONE" else e.acceptedBy
        val cause = when {
            signToSubmitMs < 0L -> "NEVER_SUBMITTED"
            signToSubmitMs > 15_000L -> "STALE_AT_SUBMIT"
            e.acceptedBy.isBlank() -> "NO_PROVIDER_ACCEPTED"
            else -> "ACCEPTED_NOT_LANDED"
        }
        expiryByRoute8087.computeIfAbsent("$route|$cause") { java.util.concurrent.atomic.AtomicLong(0) }.incrementAndGet()
        count("TX_EXPIRED_${route}_8087"); count("TX_EXPIRED_CAUSE_${cause}_8087")
        try {
            com.lifecyclebot.engine.ForensicLogger.lifecycle("TX_EXPIRED_ATTRIBUTED_8087",
                "sig=${e.signature.take(16)} mint=${e.mint.take(10)} side=${e.side} route=$route cause=$cause ageMs=$ageMs " +
                    "signToSubmitMs=$signToSubmitMs acceptedBy=$accepted blockhash=${e.blockhash.take(10)} detail=${detail.take(60)}")
        } catch (_: Throwable) {}
    }

    fun expiryLine8087(): String = expiryByRoute8087.entries.sortedByDescending { it.value.get() }
        .joinToString(" ") { "${it.key}=${it.value.get()}" }.ifBlank { "none" }

    /** V5.0.8087 — one place a live sell's submission / acceptance / chain stage reaches the exit clocks. */
    private fun stage8087(mint: String, to: State) {
        if (mint.isBlank()) return
        try {
            when (to) {
                State.SUBMIT_ATTEMPTED -> {
                    com.lifecyclebot.engine.truth.ExitTelemetryStamper6732.noteBroadcast7807(mint)
                    com.lifecyclebot.engine.truth.ExitStageTiming7876.onPhase(mint, "TX_SUBMIT_ATTEMPTED")
                }
                State.ACCEPTED -> com.lifecyclebot.engine.truth.ExitStageTiming7876.onPhase(mint, "TX_RPC_ACCEPTED")
                State.CONFIRMED -> com.lifecyclebot.engine.truth.ExitStageTiming7876.onPhase(mint, "SELL_TX_CONFIRMED")
                else -> {}
            }
        } catch (_: Throwable) {}
    }

    private fun count(label: String) { try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc(label) } catch (_: Throwable) {} }

    fun statusLine(nowMs: Long = System.currentTimeMillis()): String {
        val by = rows.values.groupingBy { it.state }.eachCount()
        val open = unresolved(nowMs)
        return "rows=${rows.size} ${by.entries.joinToString(",") { "${it.key.name}=${it.value}" }} unresolved=${open.size}" +
            open.take(4).joinToString("") { " [${it.side} ${it.mint.take(6)} ${it.signature.take(10)} ${it.state.name} via=${it.acceptedBy.ifBlank { "-" }}]" } +
            " expiries=${expiryLine8087()}"
    }

    internal fun resetForTest() { rows.clear(); intent.set(null); expiryByRoute8087.clear() }
}
