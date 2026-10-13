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
    ) {
        val unresolved: Boolean get() = state == State.SIGNED || state == State.SUBMIT_ATTEMPTED ||
            state == State.ACCEPTED || state == State.OUTCOME_UNKNOWN
    }

    /** The caller's intent for the NEXT signature on this thread (consumed once, so it never leaks to a later send). */
    class Intent(val mint: String, val side: String, val rawQty: Long = 0L, val positionId: String = "", val intentId: String = "", val route: String = "")

    private val intent = ThreadLocal<Intent?>()
    private val rows = ConcurrentHashMap<String, Entry>()
    /** Blockhash validity is ~150 slots (60-90 s); a row is never held past this without a resolution read. */
    private const val MAX_UNRESOLVED_MS = 10L * 60_000L
    private const val MAX_ROWS = 400

    fun intend(i: Intent) { intent.set(i) }
    fun consumeIntent(): Intent? = intent.get().also { intent.set(null) }

    @Volatile var persister: ((List<Entry>) -> Unit)? = null

    fun record(signature: String, signedB64: String, blockhash: String, i: Intent?, nowMs: Long = System.currentTimeMillis()): Entry {
        val e = rows[signature] ?: Entry(signature, signedB64, blockhash, i?.mint.orEmpty(), i?.side.orEmpty(), i?.rawQty ?: 0L,
            i?.positionId.orEmpty(), i?.intentId.orEmpty(), i?.route.orEmpty(), nowMs, State.SIGNED, nowMs)
        rows[signature] = e
        trim(nowMs)
        persist()
        count("TX_SIGNED_SIGNATURE_PERSISTED_8085")
        return e
    }

    fun get(signature: String): Entry? = rows[signature]

    fun transition(signature: String, to: State, detail: String = "", acceptedBy: String = "", nowMs: Long = System.currentTimeMillis()) {
        val e = rows[signature] ?: return
        // terminal chain truth never moves back to a provider-side state
        if (e.state == State.CONFIRMED || e.state == State.FAILED_ON_CHAIN || e.state == State.EXPIRED_PROVEN || e.state == State.SUBMIT_REJECTED) return
        if (to == State.SUBMIT_ATTEMPTED && e.state != State.SIGNED) return
        if (to == State.OUTCOME_UNKNOWN && e.state == State.ACCEPTED && acceptedBy.isBlank()) { e.detail = detail; return }
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
        return rows.values.filter { it.mint == mint && it.side == side && it.signature != exceptSignature && it.unresolved &&
            nowMs - it.createdAtMs <= MAX_UNRESOLVED_MS }.maxByOrNull { it.createdAtMs }
    }

    fun unresolved(nowMs: Long = System.currentTimeMillis()): List<Entry> =
        rows.values.filter { it.unresolved && nowMs - it.createdAtMs <= MAX_UNRESOLVED_MS }

    /** Rows older than the window that never resolved: their economic truth is the wallet reconciler's, not ours. */
    private fun ageOut(nowMs: Long): Int {
        var n = 0
        for (e in rows.values) if (e.unresolved && nowMs - e.createdAtMs > MAX_UNRESOLVED_MS) {
            e.state = State.OUTCOME_UNKNOWN; e.detail = "aged_out_to_wallet_reconciler"; e.updatedAtMs = nowMs; n++
        }
        return n
    }

    private fun trim(nowMs: Long) {
        ageOut(nowMs)
        if (rows.size <= MAX_ROWS) return
        rows.values.filter { !it.unresolved }.sortedBy { it.updatedAtMs }.take(rows.size - MAX_ROWS)
            .forEach { rows.remove(it.signature) }
    }

    private fun persist() { try { persister?.invoke(rows.values.toList()) } catch (_: Throwable) {} }

    // ── durable row codec (no org.json: pure JVM, field order fixed; base64 bytes contain no '|') ──
    fun encode(e: Entry): String = listOf(e.signature, e.signedB64, e.blockhash, e.mint, e.side, e.rawQty.toString(), e.positionId,
        e.intentId, e.route, e.createdAtMs.toString(), e.state.name, e.updatedAtMs.toString(), e.acceptedBy, e.detail)
        .joinToString("|") { it.replace("|", "/") }

    fun decode(line: String): Entry? = try {
        val f = line.split('|')
        if (f.size < 14) null else Entry(f[0], f[1], f[2], f[3], f[4], f[5].toLong(), f[6], f[7], f[8], f[9].toLong(),
            State.valueOf(f[10]), f[11].toLong(), f[12], f[13])
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

    /** The transaction itself fails (simulation program error / funds): no copy of these bytes can fill. */
    private fun programRejection(msg: String): Boolean {
        val m = msg.lowercase()
        return m.contains("custom program error") || m.contains("instructionerror") || m.contains("insufficient") ||
            m.contains("0x1788") || m.contains("0x1789") || m.contains("slippage") || m.contains("toolittle") ||
            m.contains("accountnotfound") || m.contains("invalid account data")
    }

    /**
     * A definite rejection is proof only when no other copy of the bytes may be in flight: Sender or an RPC endpoint
     * that may have received them (timeout / 5xx / blank reply) make a later rejection inconclusive, unless the
     * rejection is a program error every copy would also hit. An async Jito bundle is atomic: it lands only if the
     * transaction succeeds, so a program rejection still proves it, but a node-state rejection does not.
     */
    fun rejectionIsDefinite(msg: String, otherCopyMaybeDelivered: Boolean, jitoCopySent: Boolean): Boolean = when {
        programRejection(msg) -> true
        otherCopyMaybeDelivered || jitoCopySent -> false
        else -> true
    }

    /** Signature unseen + blockhash no longer valid = the transaction can never land. Unknown validity proves nothing. */
    fun expiredProven(statusSeen: Boolean, blockhashValid: Boolean?): Boolean = !statusSeen && blockhashValid == false

    private fun count(label: String) { try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc(label) } catch (_: Throwable) {} }

    fun statusLine(nowMs: Long = System.currentTimeMillis()): String {
        val by = rows.values.groupingBy { it.state }.eachCount()
        val open = unresolved(nowMs)
        return "rows=${rows.size} ${by.entries.joinToString(",") { "${it.key.name}=${it.value}" }} unresolved=${open.size}" +
            open.take(4).joinToString("") { " [${it.side} ${it.mint.take(6)} ${it.signature.take(10)} ${it.state.name} via=${it.acceptedBy.ifBlank { "-" }}]" }
    }

    internal fun resetForTest() { rows.clear(); intent.set(null) }
}
