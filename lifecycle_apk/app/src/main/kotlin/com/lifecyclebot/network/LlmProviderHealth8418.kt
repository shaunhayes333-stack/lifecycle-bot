package com.lifecyclebot.network

import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.8418 §READ_WHAT_EACH_DEAD_PROVIDER_SAID — owner, 5.0.8416: most LLM providers dead. The bodies, verbatim from
 * that log's API health table:
 *
 *   llm_cerebras         http=402 "Payment required to access this resource. Visit your billing tab."
 *   llm_emergent         http=401 "Invalid API key"                       (the embedded proxy key is revoked)
 *   llm_openrouter       http=429 "Rate limit exceeded: free-models-per-day. Add 10 credits ..."
 *   llm_pollinations     http=402 {"code":"FREE_LIMIT","message":"Free limit reached: 1 reply every 30 seco..."}
 *   llm_pollinations_get http=404                                          (an undocumented ?model= parameter)
 *   ovh_llm_keyless      sr=55% 4xx=11 net=26                              (no per-model cooling; ring cancels as net)
 *   llm_groq             last_err "interrupted"                            (our own ring cancelling a loser)
 *
 * 7150 classified by status code first, so Pollinations' 402 — which is a 30-second PACING notice, documented as
 * "402 Payment Required, with a Retry-After header saying when the next free request is ready" — was read as a spent
 * account and benched for six hours (llmPenalties7150: llm_pollinations:TERMINAL(19769s)). OpenRouter's free daily
 * allowance was benched for 30 min and then re-asked ~48 times a day until UTC midnight. A key that is truly dead
 * (Emergent 401, Cerebras 402 billing) was re-asked every six hours forever.
 *
 * This object owns the corrections, as pure functions plus a small per-host strike book:
 *  - [classify8418]: pacing notices -> RATE for the provider's own interval (Retry-After, or "every N seconds");
 *    a per-day quota -> QUOTA until the provider's reset (X-RateLimit-Reset) or the next UTC midnight;
 *  - [terminalPenaltyMs8418]: a repeated TERMINAL doubles from 6 h to a 48 h cap; one served answer clears it;
 *  - [isOwnCancellation8418]: the ring's own cancel of a losing member is not that provider's network failure;
 *  - [ownerAction8418]: the one-line thing only the owner can do (billing, a new key), surfaced in the snapshot.
 * Never a key in here: the app's configured keys are used as they are.
 */
object LlmProviderHealth8418 {
    private const val TERMINAL_BASE_MS_8418 = 6L * 60 * 60 * 1000
    private const val TERMINAL_MAX_MS_8418 = 48L * 60 * 60 * 1000
    const val PACING_DEFAULT_MS_8418 = 30_000L
    private const val PACING_MAX_MS_8418 = 10L * 60_000L
    private const val DAY_MS_8418 = 24L * 60 * 60 * 1000

    enum class Kind8418 { RATE, QUOTA }

    data class Verdict8418(val kind: Kind8418, val penaltyMs: Long, val why: String)

    private val strikes = ConcurrentHashMap<String, Int>()
    private val ownerActions = ConcurrentHashMap<String, String>()

    /** Pure: the provider's stated wait in ms from a Retry-After header (seconds), or null. */
    internal fun retryAfterMs8418(header: String?): Long? {
        val s = header?.trim()?.toDoubleOrNull() ?: return null
        if (!s.isFinite() || s <= 0.0) return null
        return (s * 1000.0).toLong().coerceAtMost(PACING_MAX_MS_8418)
    }

    /** Pure: "1 reply every 30 seconds" / "every 30 sec" -> 30 000 ms, or null. */
    internal fun statedIntervalMs8418(body: String): Long? {
        val m = Regex("""every\s+(\d{1,4})\s*(s\b|sec|second)""", RegexOption.IGNORE_CASE).find(body) ?: return null
        val n = m.groupValues[1].toLongOrNull() ?: return null
        return if (n <= 0L) null else (n * 1000L).coerceAtMost(PACING_MAX_MS_8418)
    }

    /** Pure: the first millisecond of the next UTC day after [nowMs]. */
    internal fun nextUtcMidnightMs8418(nowMs: Long): Long {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        c.timeInMillis = nowMs
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        c.add(Calendar.DAY_OF_MONTH, 1)
        return c.timeInMillis
    }

    /**
     * Pure: an X-RateLimit-Reset value as an absolute epoch ms (OpenRouter sends epoch ms; some send epoch seconds),
     * or null when absent, unparseable or not in the future.
     */
    internal fun resetAtMs8418(header: String?, nowMs: Long): Long? {
        val v = header?.trim()?.toLongOrNull() ?: return null
        val at = when {
            v > 100_000_000_000L -> v            // epoch ms
            v > 1_000_000_000L -> v * 1000L      // epoch s
            else -> return null
        }
        return if (at > nowMs) at else null
    }

    /**
     * Pure: a correction to 7150's status-code classification, or null to keep it.
     *  - PACING (402/429 whose body says FREE_LIMIT, "reply every N", "queue full", or a 402/429 carrying Retry-After):
     *    RATE for the stated wait — the provider will answer again in seconds, not hours.
     *  - DAILY QUOTA (429 naming per-day / daily): QUOTA until the stated reset, else the next UTC midnight (<= 24 h).
     */
    fun classify8418(code: Int, body: String, retryAfter: String?, rateLimitReset: String?, nowMs: Long): Verdict8418? {
        val b = body.lowercase()
        val paced = (code == 402 || code == 429) && (
            b.contains("free_limit") || b.contains("free limit reached") || b.contains("queue full") ||
                Regex("""reply every\s+\d""").containsMatchIn(b) ||
                (code == 402 && retryAfterMs8418(retryAfter) != null)
            )
        if (paced) {
            val ms = retryAfterMs8418(retryAfter) ?: statedIntervalMs8418(body) ?: PACING_DEFAULT_MS_8418
            return Verdict8418(Kind8418.RATE, ms, "PACING")
        }
        val daily = code == 429 && (b.contains("per-day") || b.contains("per day") || b.contains("daily") || b.contains("requests per day"))
        if (daily) {
            val until = resetAtMs8418(rateLimitReset, nowMs) ?: nextUtcMidnightMs8418(nowMs)
            val ms = (until - nowMs).coerceIn(60_000L, DAY_MS_8418)
            return Verdict8418(Kind8418.QUOTA, ms, "DAILY_QUOTA")
        }
        return null
    }

    /** Pure: the bench for the [strike]-th consecutive TERMINAL answer (1 = first): 6 h doubling, capped at 48 h. */
    internal fun terminalPenaltyMs8418(strike: Int): Long {
        val n = strike.coerceIn(1, 8) - 1
        return (TERMINAL_BASE_MS_8418 shl n).coerceAtMost(TERMINAL_MAX_MS_8418)
    }

    /** A TERMINAL answer from [host]: returns the escalated bench to apply. */
    fun noteTerminal8418(host: String): Long {
        val s = strikes.merge(host, 1) { a, b -> (a + b).coerceAtMost(8) } ?: 1
        return terminalPenaltyMs8418(s)
    }

    /** A served answer from [host]: its terminal strikes and owner action are cleared. */
    fun noteSuccess8418(host: String) {
        if (host.isBlank()) return
        strikes.remove(host)
        ownerActions.remove(host)
    }

    fun strikes8418(host: String): Int = strikes[host] ?: 0

    /**
     * Pure: what only the owner can do about this refusal, or null. Billing (402 that is not pacing) and a rejected
     * key (401 / "invalid api key") are the owner's; rate limits and quotas recover on their own.
     */
    fun ownerAction8418(host: String, code: Int, body: String): String? {
        val b = body.lowercase()
        val provider = host.removePrefix("llm_").removeSuffix("_7136")
        if (classify8418(code, body, null, null, 0L) != null) return null
        return when {
            host == "llm_emergent" && (code == 401 || b.contains("invalid api key") || b.contains("budget")) ->
                "emergent: embedded proxy key revoked/spent (not owner-settable) - benched; other members carry the council"
            code == 402 || b.contains("payment required") || b.contains("billing") ->
                "$provider: account needs billing/credits (HTTP $code) - add credits or replace the $provider key in Settings"
            code == 401 || b.contains("invalid api key") || b.contains("incorrect api key") || b.contains("api key not valid") ->
                "$provider: key rejected (HTTP $code) - replace the $provider key in Settings"
            else -> null
        }
    }

    /** Record the owner action for [host] (from [ownerAction8418]) when there is one. */
    fun noteRefusal8418(host: String, code: Int, body: String) {
        ownerAction8418(host, code, body)?.let { ownerActions[host] = it }
    }

    /**
     * Pure: is [e] the council ring cancelling a member it no longer needs (Future.cancel(true) interrupts the socket)?
     * A read/call timeout ("timeout", SocketTimeoutException) is the provider's own slowness and stays a failure.
     */
    fun isOwnCancellation8418(e: Exception): Boolean {
        if (e is java.net.SocketTimeoutException) return false
        val msg = e.message?.trim()?.lowercase().orEmpty()
        if (msg == "timeout") return false
        if (msg == "canceled" || msg == "cancelled") return true
        return e is java.io.InterruptedIOException && (msg == "interrupted" || msg.isEmpty())
    }

    /**
     * llmHealth8418[benched=host:REASON(strikes=n,leftS) ... | owner=...] — one line for the snapshot. [penalties] is
     * host -> (untilMs, reason) from the council's bench; [configuredMissing] names keyed members with no key set.
     */
    fun healthLine8418(penalties: Map<String, Pair<Long, String>>, configuredMissing: List<String>, nowMs: Long): String {
        val benched = penalties.entries.filter { it.value.first > nowMs }.sortedBy { it.key }.joinToString(",") { (h, v) ->
            "$h:${v.second}(strikes=${strikes8418(h)},left=${(v.first - nowMs) / 1000L}s)"
        }.ifBlank { "none" }
        val owner = (ownerActions.values.sorted() + configuredMissing.map { "$it: no key configured (optional)" })
            .joinToString(" ; ").ifBlank { "none" }
        return "llmHealth8418[benched=$benched | ownerAction=$owner]"
    }

    // ── pacing for a host whose free tier allows one request per interval (Pollinations: one at a time, >= 30 s) ──
    private val lastWireMs = ConcurrentHashMap<String, Long>()

    /**
     * True and claims the slot when [intervalMs] has passed since the last wire call to [host]; false = our own pacing
     * refusal (nothing sent, nothing penalised). Asking inside the interval is what produced the 402 FREE_LIMIT.
     */
    @Synchronized fun tryPace8418(host: String, intervalMs: Long, nowMs: Long): Boolean {
        val last = lastWireMs[host] ?: 0L
        if (last > 0L && nowMs - last in 0L until intervalMs) return false
        lastWireMs[host] = nowMs
        return true
    }

    // ── per-model cooling for a keyless catalogue (OVH anonymous tier: ~2 requests / minute per model per IP) ──
    private val modelCoolUntil = ConcurrentHashMap<String, Long>()

    /** Pure: how long a model is set aside after [code]: 429 one minute, 5xx 30 s, anything else not at all. */
    fun modelCoolMs8418(code: Int): Long = when {
        code == 429 -> 60_000L
        code in 500..599 -> 30_000L
        else -> 0L
    }

    fun coolModel8418(model: String, ms: Long, nowMs: Long) {
        if (ms <= 0L || model.isBlank()) return
        modelCoolUntil[model] = nowMs + ms
        if (modelCoolUntil.size > 200) modelCoolUntil.entries.removeIf { it.value <= nowMs }
    }

    fun modelCooling8418(model: String, nowMs: Long): Boolean = (modelCoolUntil[model] ?: 0L) > nowMs

    /**
     * Pure: the chat-capable ids of an OpenAI-compatible catalogue. OVH's /v1/models also lists embedding, speech,
     * image and reranking models; rotating the council onto one of those is a guaranteed 4xx.
     */
    fun chatModels8418(ids: List<String>): List<String> {
        val nonChat = listOf(
            "whisper", "tts", "embed", "bge-", "rerank", "moderation", "guard", "ocr", "audio", "transcri",
            "stable-diffusion", "sdxl", "flux", "diffusion", "speech", "nvr-", "clip",
        )
        return ids.map { it.trim() }.filter { id -> id.isNotEmpty() && nonChat.none { id.lowercase().contains(it) } }.distinct()
    }

    internal fun resetForTests8418() { strikes.clear(); ownerActions.clear(); lastWireMs.clear(); modelCoolUntil.clear() }
}
