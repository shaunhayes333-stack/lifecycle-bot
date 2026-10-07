package com.lifecyclebot.engine.market

import android.content.Context
import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.network.PairInfo
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7830 — TELEGRAM CALL SWEEPER (operator-approved new discovery source).
 *
 * Root cause of "the bot never catches Telegram pump-group calls":
 * network/TelegramScraper.kt reads the public previews `t.me/s/<channel>` of
 * five hard-coded channels, but parseTelegramHtml keeps only messages that
 * mention a symbol or mint the bot ALREADY has (TelegramScraper ~L120-126),
 * so a contract address posted in a call channel was never turned into a
 * candidate. This resident worker sweeps public previews for NEW posts,
 * extracts Solana contract addresses, verifies each one is a real SPL mint
 * with a DexScreener market, and hands it to the normal scanner intake as
 * TokenSource.TELEGRAM_CALL.
 *
 * Authority: candidates only. No score bonus, no gate bypass, no special size —
 * every emitted mint takes the same first assessment as every other token
 * (safety, V3, lanes, FDG, sizing). Field Manual L201: social engagement is
 * easy to manufacture, so source quality is measured (forward returns per
 * channel) and used ONLY to order the sweep. Field Manual L309: a social post
 * alone is not a thesis. Field Manual L412: discovery runs off the hot loop on
 * its own coroutine and budget.
 *
 * Telegram ToS: only public web previews (t.me/s/...), no login, no bot
 * membership, no posting.
 */
object TelegramCallSweeper7830 {

    private const val CADENCE_MS_7830 = 60_000L
    private const val FIRST_RUN_DELAY_MS_7830 = 20_000L
    internal const val CHANNELS_PER_PASS_7830 = 15
    // V5.0.7864 — 8 s produced fetch:InterruptedIOException on every 5.0.7863
    // live channel (0 posts). Mobile TLS to t.me routinely needs longer.
    private const val REQUEST_TIMEOUT_S_7830 = 15L
    private const val POLITE_DELAY_MS_7830 = 450L
    internal const val MAX_REGISTRY_7830 = 300
    internal const val DEAD_AFTER_SWEEPS_7830 = 6
    private const val PROBES_PER_PASS_7830 = 3
    private const val MAX_PENDING_CHANNELS_7830 = 200
    private const val MAX_VERIFY_QUEUE_7830 = 120
    private const val VERIFY_PER_PASS_7830 = 20
    private const val EMIT_DEDUPE_TTL_MS_7830 = 30L * 60_000L
    private const val EMIT_BUDGET_MS_7830 = 20_000L
    /** A post older than this when first read is history, not a call to act on. */
    internal const val FRESH_CALL_MAX_AGE_MS_7830 = 45L * 60_000L
    private const val MAX_TRACKED_CALLS_7830 = 600
    private const val H15_MS_7830 = 15L * 60_000L
    private const val H60_MS_7830 = 60L * 60_000L
    private const val HORIZON_GRACE_MS_7830 = 10L * 60_000L
    private const val MARK_MAX_AGE_MS_7830 = 120_000L
    /** A call "hits" when its 60-minute forward return from the first-call price reaches this. */
    internal const val HIT_RETURN_PCT_7830 = 20.0
    private const val MAX_RETS_PER_CHANNEL_7830 = 25
    private const val MAX_BODY_CHARS_7830 = 1_500_000
    private const val PREFS_7830 = "telegram_call_sweeper_7830"
    private const val PREFS_KEY_CHANNELS_7830 = "channels"
    private const val FIELD_SEP_7830 = '\u001F'
    private const val ROW_SEP_7830 = '\u001E'

    private const val B58_7830 = "1-9A-HJ-NP-Za-km-z"
    private val RAW_B58_7830 = Regex("(?<![$B58_7830])[$B58_7830]{32,44}(?![$B58_7830])")
    /** Links whose address is (or may be) a POOL, not a mint: resolved via DexScreener pair lookup. */
    private val PAIR_LINK_7830 = Regex(
        "(?:dexscreener\\.com/solana/|/lp/|geckoterminal\\.com/solana/pools/)([$B58_7830]{32,44})",
        RegexOption.IGNORE_CASE,
    )
    private val TME_LINK_7830 = Regex(
        "^https?://(?:www\\.)?(?:t\\.me|telegram\\.me)/(?:s/)?([A-Za-z][A-Za-z0-9_]{4,31})(?:[/?#].*)?$",
        RegexOption.IGNORE_CASE,
    )
    private val AT_MENTION_7830 = Regex("(?<![A-Za-z0-9_@./])@([A-Za-z][A-Za-z0-9_]{4,31})(?![A-Za-z0-9_])")
    private val FORWARD_7830 = Regex(
        "forwarded_from_name[^>]*?href=\"https?://(?:www\\.)?t\\.me/([A-Za-z][A-Za-z0-9_]{4,31})",
        RegexOption.IGNORE_CASE,
    )
    private val HREF_7830 = Regex("href=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
    private val TEXT_DIV_7830 = Regex(
        "class=\"tgme_widget_message_text[^\"]*\"[^>]*>(.*?)</div>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val DATETIME_7830 = Regex("datetime=\"([^\"]+)\"")
    private val CALL_WORDS_7830 = Regex(
        "(?<![A-Za-z])(ca|call|calls|calling|aping|ape|aped|launch|launching|launched|gem|entry|100x|1000x|lfg|send it|stealth)(?![A-Za-z])",
        RegexOption.IGNORE_CASE,
    )

    /** Telegram path segments that are not channels. */
    private val RESERVED_7830 = setOf(
        "joinchat", "addstickers", "addemoji", "addlist", "share", "proxy", "socks", "login",
        "setlanguage", "contact", "boost", "iv", "confirmphone", "addtheme", "invoice", "giftcode",
    )

    /** Addresses that appear in messages but are never a called token. */
    private val NON_CALL_ADDRESSES_7830 = setOf(
        "So11111111111111111111111111111111111111112", // wSOL
        "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", // USDC
        "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB", // USDT
        "mSoLzYCxHdYgdzU16g5QSh3i5K3z3KZK7ytfqcJm7So", // mSOL
        "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA", // SPL Token program
        "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb", // Token-2022 program
        "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL", // Associated token program
        "11111111111111111111111111111111", // System program
        "ComputeBudget111111111111111111111111111111", // Compute budget
        "6EF8rrecthR5Dkzon8Nwu78hRvfCKubJ14M5uBEwF6P", // pump.fun program
        "pAMMBay6oceH9fJKBRHGP5D4bD4sWpmSwMn52FMfXEA", // PumpSwap AMM
        "675kPX9MHTjS2zt1qfr1NYHuzeLXfQM9H24wFSUt1Mp8", // Raydium AMM v4
        "CAMMCzo5YL8w4VFF8KVHrK22GGUsp5VTaW7grrKgrWqK", // Raydium CLMM
        "whirLbMiicVdio4qvUfM5KAg6Ct8VwpYzGff3uctyCc", // Orca whirlpool
        "LBUZKhRxPF3XUpBCjp4YzTKgLccjZhTSDM9YuVaPwxo", // Meteora DLMM
        "JUP6LkbZbjS1jKKwapdHNy74zcZ3tLUZoi5QNyVTaV4", // Jupiter v6
        "metaqbxxUerdq28cj1RbAWkYQm3ybzjb6a8bt518x1s", // Metaplex metadata
        "MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr", // Memo
    )

    // ── pure helpers (unit-tested) ────────────────────────────────────────

    internal data class Post7830(
        val channel: String,
        val postId: Long,
        val tsMs: Long,
        val text: String,
        val hrefs: List<String>,
        val forwardedFrom: String?,
    )

    internal data class Extracted7830(val mints: Set<String>, val pairLike: Set<String>)

    /** True for a string shaped like a Solana mint that is not a known program / stable / SOL. */
    internal fun isPlausibleMint7830(s: String): Boolean {
        if (s.length !in 32..44) return false
        if (s in NON_CALL_ADDRESSES_7830) return false
        if (!RAW_B58_7830.matches(s)) return false
        // Real 32-byte keys in base58 mix cases and characters; this drops long
        // all-lowercase words / handles and repeated-digit filler.
        if (s.none { it.isUpperCase() } || s.none { it.isLowerCase() }) return false
        return s.toSet().size >= 12
    }

    /**
     * Contract addresses in a message: raw base58 in the text plus addresses in
     * pump.fun / dexscreener / birdeye / solscan / jup.ag / photon / bullx /
     * gmgn links (the raw pattern covers all of them, since each embeds the
     * address as a base58 path or query segment). Pool links are flagged so the
     * verifier resolves pair -> base mint.
     */
    internal fun extractMints7830(text: String, hrefs: List<String>): Extracted7830 {
        val mints = LinkedHashSet<String>()
        val pairs = LinkedHashSet<String>()
        val all = ArrayList<String>(hrefs.size + 1)
        all.add(text)
        all.addAll(hrefs)
        for (chunk in all) {
            for (m in PAIR_LINK_7830.findAll(chunk)) {
                val a = m.groupValues[1]
                if (isPlausibleMint7830(a)) pairs.add(a)
            }
            for (m in RAW_B58_7830.findAll(chunk)) {
                val a = m.value
                if (isPlausibleMint7830(a)) mints.add(a)
            }
        }
        return Extracted7830(mints, pairs)
    }

    internal fun looksLikeCall7830(text: String): Boolean =
        CALL_WORDS_7830.containsMatchIn(text) || text.contains("🚀") || text.contains("💎")

    internal fun normalizeChannel7830(raw: String): String? {
        val n = raw.trim().trimStart('@').lowercase()
        if (n.length !in 5..32) return null
        if (!n[0].isLetter()) return null
        if (n.any { !(it in 'a'..'z' || it in '0'..'9' || it == '_') }) return null
        if (n in RESERVED_7830) return null
        if (n.endsWith("bot")) return null
        return n
    }

    /** Channels named inside a post: t.me links, @mentions and the forward header. */
    internal fun channelMentions7830(post: Post7830): Set<String> {
        val out = LinkedHashSet<String>()
        for (h in post.hrefs) {
            val m = TME_LINK_7830.matchEntire(h.trim()) ?: continue
            normalizeChannel7830(m.groupValues[1])?.let { out.add(it) }
        }
        for (m in AT_MENTION_7830.findAll(post.text)) normalizeChannel7830(m.groupValues[1])?.let { out.add(it) }
        post.forwardedFrom?.let { f -> normalizeChannel7830(f)?.let { out.add(it) } }
        out.remove(post.channel.lowercase())
        return out
    }

    private fun unescape7830(s: String): String = s
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<")
        .replace("&gt;", ">").replace("&nbsp;", " ").replace("&amp;", "&")

    private fun parseTs7830(s: String): Long = try {
        java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli()
    } catch (_: Throwable) {
        try { java.time.Instant.parse(s).toEpochMilli() } catch (_: Throwable) { 0L }
    }

    /** Posts in a `t.me/s/<channel>` preview, keyed by their data-post="channel/id". */
    internal fun parsePreview7830(html: String): List<Post7830> {
        val marker = "data-post=\""
        val starts = ArrayList<Int>()
        var i = html.indexOf(marker)
        while (i >= 0) {
            starts.add(i)
            i = html.indexOf(marker, i + marker.length)
        }
        val out = ArrayList<Post7830>(starts.size)
        for (k in starts.indices) {
            val from = starts[k]
            val to = if (k + 1 < starts.size) starts[k + 1] else html.length
            val seg = html.substring(from, to)
            val refEnd = seg.indexOf('"', marker.length)
            if (refEnd <= marker.length) continue
            val ref = seg.substring(marker.length, refEnd)
            val slash = ref.lastIndexOf('/')
            if (slash <= 0) continue
            val ch = ref.substring(0, slash).lowercase()
            val id = ref.substring(slash + 1).toLongOrNull() ?: continue
            val rawText = TEXT_DIV_7830.find(seg)?.groupValues?.get(1).orEmpty()
            val text = unescape7830(
                rawText.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
                    .replace(Regex("<[^>]+>"), " ")
            ).replace(Regex("[ \\t]+"), " ").trim()
            val hrefs = HREF_7830.findAll(seg).map { unescape7830(it.groupValues[1]) }.toList()
            val ts = DATETIME_7830.find(seg)?.groupValues?.get(1)?.let { parseTs7830(it) } ?: 0L
            val fwd = FORWARD_7830.find(seg)?.groupValues?.get(1)
            out.add(Post7830(ch, id, ts, text, hrefs, fwd))
        }
        return out
    }

    /** A public channel preview: served under /s/ (private channels redirect away) and carrying posts. */
    internal fun isPublicPreview7830(finalPath: String, html: String): Boolean =
        finalPath.startsWith("/s/") && html.contains("data-post=\"")

    internal fun median7830(xs: Collection<Double>): Double {
        if (xs.isEmpty()) return 0.0
        val s = xs.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    /** 0.5 until a channel has three booked calls; then hit rate and median 60m return. Ordering only. */
    internal fun channelScore7830(booked: Long, hits: Long, medianRet: Double): Double {
        if (booked < 3L) return 0.5
        val hr = hits.toDouble() / booked.toDouble()
        val med = (medianRet / 200.0).coerceIn(-0.25, 0.25)
        return (0.25 + 0.5 * hr + med).coerceIn(0.05, 1.0)
    }

    /** Better channels are swept more often, persistently bad or failing ones less. Never a gate. */
    internal fun sweepIntervalMs7830(score: Double, deadSweeps: Int): Long {
        if (deadSweeps > 0) return (5L * 60_000L) shl deadSweeps.coerceIn(1, 4)
        return when {
            score >= 0.7 -> 3L * 60_000L
            score >= 0.4 -> 8L * 60_000L
            score >= 0.2 -> 15L * 60_000L
            else -> 30L * 60_000L
        }
    }

    // ── registry ──────────────────────────────────────────────────────────

    internal class Channel7830(val name: String, val origin: String, val addedAtMs: Long) {
        var lastPostId: Long = 0L
        var deadSweeps: Int = 0
        var lastOkMs: Long = 0L
        var nextDueMs: Long = 0L
        var sweeps: Long = 0L
        var calls: Long = 0L
        var firstCalls: Long = 0L
        var booked60: Long = 0L
        var hits60: Long = 0L
        var peakMinSum: Double = 0.0
        var peakN: Long = 0L
        val rets60: ArrayList<Double> = ArrayList()

        fun score(): Double = channelScore7830(booked60, hits60, median7830(rets60))
    }

    internal fun encodeChannel7830(c: Channel7830): String = listOf(
        c.name, c.origin, c.addedAtMs, c.lastPostId, c.deadSweeps, c.lastOkMs, c.sweeps, c.calls,
        c.firstCalls, c.booked60, c.hits60, c.peakMinSum, c.peakN,
        c.rets60.joinToString(",") { "%.2f".format(java.util.Locale.ROOT, it) },
    ).joinToString(FIELD_SEP_7830.toString())

    internal fun decodeChannel7830(row: String): Channel7830? = try {
        val f = row.split(FIELD_SEP_7830)
        if (f.size < 14) null else {
            val name = normalizeChannel7830(f[0])
            if (name == null) null else {
                val c = Channel7830(name, f[1].ifBlank { "SEED" }, f[2].toLong())
                c.lastPostId = f[3].toLong()
                c.deadSweeps = f[4].toInt()
                c.lastOkMs = f[5].toLong()
                c.sweeps = f[6].toLong()
                c.calls = f[7].toLong()
                c.firstCalls = f[8].toLong()
                c.booked60 = f[9].toLong()
                c.hits60 = f[10].toLong()
                c.peakMinSum = f[11].toDouble()
                c.peakN = f[12].toLong()
                if (f[13].isNotBlank()) f[13].split(',').mapNotNullTo(c.rets60) { it.toDoubleOrNull() }
                c
            }
        }
    } catch (_: Throwable) { null }

    private val lock7830 = Any()
    private val registry = LinkedHashMap<String, Channel7830>()
    private val pendingChannels = LinkedHashMap<String, String>() // name -> origin
    private val offered = java.util.concurrent.ConcurrentLinkedQueue<String>()
    /** A channel that failed the public-preview probe (or was dropped dead) is not re-probed for this long. */
    private const val PROBE_RETRY_MS_7830 = 12L * 60L * 60_000L
    private val probeRejectedAt = ConcurrentHashMap<String, Long>()
    @Volatile private var loaded = false
    @Volatile private var dirty = false

    private class Candidate7830(val mint: String, val pairLike: Boolean, val channel: String, val postTsMs: Long, val looksLikeCall: Boolean)
    private val verifyQueue = java.util.concurrent.ConcurrentLinkedQueue<Candidate7830>()
    private val emittedAt = ConcurrentHashMap<String, Long>()

    internal class Call7830(val mint: String, val channel: String, val firstSeenMs: Long, val calledAtMs: Long, val entryPx: Double) {
        var peakPct: Double = 0.0
        var peakAtMs: Long = 0L
        var ret15: Double? = null
        var creditedFirst: Boolean = false
        val coCallers: MutableSet<String> = LinkedHashSet()
    }
    private val calls = ConcurrentHashMap<String, Call7830>()

    // ── counters ──────────────────────────────────────────────────────────

    @Volatile private var job: Job? = null
    private val inFlight = AtomicBoolean(false)
    private val sweeps = AtomicLong(0L)
    private val fetches = AtomicLong(0L)
    private val newPosts = AtomicLong(0L)
    private val callPosts = AtomicLong(0L)
    private val casExtracted = AtomicLong(0L)
    private val staleSkipped = AtomicLong(0L)
    private val verified = AtomicLong(0L)
    private val unverifiable = AtomicLong(0L)
    private val emitted = AtomicLong(0L)
    private val alreadyInPipeline = AtomicLong(0L)
    private val dedupeSkipped = AtomicLong(0L)
    private val admitted = AtomicLong(0L)
    private val probeRejected = AtomicLong(0L)
    private val dropped = AtomicLong(0L)
    private val throttled = AtomicLong(0L)
    private val booked15 = AtomicLong(0L)
    private val booked60 = AtomicLong(0L)
    private val lost = AtomicLong(0L)
    @Volatile private var backoffUntilMs = 0L
    @Volatile private var backoffLevel = 0
    @Volatile private var lastRunAtMs = 0L
    @Volatile private var lastError = ""

    private val http: OkHttpClient by lazy {
        com.lifecyclebot.network.SharedHttpClient.builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(REQUEST_TIMEOUT_S_7830, TimeUnit.SECONDS)
            .callTimeout(REQUEST_TIMEOUT_S_7830, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
    private val dex by lazy { com.lifecyclebot.network.DexscreenerApi() }

    // ── lifecycle ─────────────────────────────────────────────────────────

    /**
     * Start (or restart) on [scope]. [emitter] hands a verified mint to the
     * scanner intake (SolanaMarketScanner.emitTelegramCall7830) and returns its
     * outcome label; it is read lazily so a self-healed scanner is picked up.
     */
    fun start(context: Context, scope: CoroutineScope, emitter: suspend (String, PairInfo) -> String) {
        val app = context.applicationContext ?: context
        synchronized(this) {
            job?.cancel()
            job = scope.launch(Dispatchers.IO + CoroutineName("telegram-call-sweeper-7830")) {
                try { load7830(app) } catch (_: Throwable) {}
                delay(FIRST_RUN_DELAY_MS_7830)
                while (isActive) {
                    runOnce(app, emitter)
                    delay(CADENCE_MS_7830)
                }
            }
        }
        try { PipelineHealthCollector.labelInc("TELEGRAM_SWEEPER_STARTED_7830") } catch (_: Throwable) {}
        ErrorLogger.info("TelegramSweeper7830", "started cadence=${CADENCE_MS_7830}ms perPass=$CHANNELS_PER_PASS_7830")
    }

    fun stop(reason: String) {
        val j = synchronized(this) { val cur = job; job = null; cur }
        if (j != null) {
            j.cancel()
            try { PipelineHealthCollector.labelInc("TELEGRAM_SWEEPER_STOPPED_7830") } catch (_: Throwable) {}
            ErrorLogger.info("TelegramSweeper7830", "stopped reason=${reason.take(60)}")
        }
    }

    fun isRunning(): Boolean = job?.isActive == true

    /**
     * Hook for other discovery components (GroqTokenScout7830 or any future
     * one) to propose channels. Proposals are candidates: each must pass the
     * public-preview probe before it joins the registry.
     */
    fun offerChannels(names: Collection<String>): Int {
        var n = 0
        for (raw in names.take(100)) {
            val c = normalizeChannel7830(raw) ?: continue
            offered.add(c)
            n++
        }
        return n
    }

    // ── one pass ──────────────────────────────────────────────────────────

    private suspend fun runOnce(ctx: Context, emitter: suspend (String, PairInfo) -> String) {
        if (!inFlight.compareAndSet(false, true)) return
        try {
            val now = System.currentTimeMillis()
            if (!loaded) load7830(ctx)
            ingestProposals7830()
            if (now >= backoffUntilMs) {
                sweeps.incrementAndGet()
                sweepChannels7830(now)
                if (System.currentTimeMillis() >= backoffUntilMs) probeCandidates7830()
            }
            verifyAndEmit7830(emitter)
            tickCalls7830(System.currentTimeMillis()) { m -> markFor7830(m, System.currentTimeMillis()) }
            if (dirty) persist7830(ctx)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}:${t.message?.take(60)}"
            ErrorLogger.debug("TelegramSweeper7830", "pass failed: $lastError")
        } finally {
            lastRunAtMs = System.currentTimeMillis()
            inFlight.set(false)
        }
    }

    private fun load7830(ctx: Context) {
        if (loaded) return
        val rows = try {
            ctx.getSharedPreferences(PREFS_7830, Context.MODE_PRIVATE).getString(PREFS_KEY_CHANNELS_7830, null)
        } catch (_: Throwable) { null }
        synchronized(lock7830) {
            if (!rows.isNullOrBlank()) {
                for (r in rows.split(ROW_SEP_7830)) {
                    val c = decodeChannel7830(r) ?: continue
                    registry[c.name] = c
                }
            }
            // Seed from TelegramScraper's existing default channels (not new guesses).
            if (registry.isEmpty()) {
                val now = System.currentTimeMillis()
                for (s in com.lifecyclebot.network.TelegramScraper.DEFAULT_CHANNELS_7830) {
                    val n = normalizeChannel7830(s) ?: continue
                    registry[n] = Channel7830(n, "SEED", now)
                }
                dirty = true
            }
        }
        loaded = true
    }

    private fun persist7830(ctx: Context) {
        val enc = synchronized(lock7830) { registry.values.joinToString(ROW_SEP_7830.toString()) { encodeChannel7830(it) } }
        try {
            ctx.getSharedPreferences(PREFS_7830, Context.MODE_PRIVATE).edit().putString(PREFS_KEY_CHANNELS_7830, enc).apply()
            dirty = false
        } catch (_: Throwable) {}
    }

    /** Channel names the Groq scout saw cited as Solana call channels (hints; each still has to pass the probe). */
    private fun scoutChannels7830(): List<String> = try {
        GroqTokenScout7830.discoveredTelegramChannels()
    } catch (_: Throwable) { emptyList() }

    private fun ingestProposals7830() {
        val incoming = ArrayList<Pair<String, String>>()
        while (true) {
            val n = offered.poll() ?: break
            incoming.add(n to "OFFERED")
        }
        val nowP = System.currentTimeMillis()
        probeRejectedAt.entries.removeIf { nowP - it.value > PROBE_RETRY_MS_7830 }
        for (s in scoutChannels7830().take(100)) normalizeChannel7830(s)?.let { incoming.add(it to "SCOUT") }
        if (incoming.isEmpty()) return
        synchronized(lock7830) { for ((n, o) in incoming) addPending7830(n, o) }
    }

    /** Caller holds lock7830. */
    private fun addPending7830(name: String, origin: String) {
        if (registry.containsKey(name) || pendingChannels.containsKey(name)) return
        val rejAt = probeRejectedAt[name]
        if (rejAt != null && System.currentTimeMillis() - rejAt < PROBE_RETRY_MS_7830) return
        if (pendingChannels.size >= MAX_PENDING_CHANNELS_7830) return
        pendingChannels[name] = origin
    }

    private class Fetch7830(val kind: Int, val html: String, val code: Int)
    private const val FETCH_OK_7830 = 0
    private const val FETCH_NOT_PUBLIC_7830 = 1
    private const val FETCH_THROTTLED_7830 = 2
    private const val FETCH_FAILED_7830 = 3

    /** t.me first; on a transport failure the same public preview via telegram.me (V5.0.7864). */
    private fun fetchPreview7830(name: String): Fetch7830 {
        val f = fetchPreviewFrom7864("https://t.me/s/$name")
        if (f.kind != FETCH_FAILED_7830) return f
        try { PipelineHealthCollector.labelInc("TELEGRAM_SWEEPER_MIRROR_RETRY_7864") } catch (_: Throwable) {}
        return fetchPreviewFrom7864("https://telegram.me/s/$name")
    }

    private fun fetchPreviewFrom7864(url: String): Fetch7830 {
        fetches.incrementAndGet()
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (compatible; AATE/1.0)")
            .get().build()
        return try {
            com.lifecyclebot.engine.HealthAwareHttp.execute(http, req, host = "telegram").use { resp ->
                val code = resp.code
                if (code == 429 || code >= 500) {
                    Fetch7830(FETCH_THROTTLED_7830, "", code)
                } else if (!resp.isSuccessful) {
                    Fetch7830(FETCH_NOT_PUBLIC_7830, "", code)
                } else {
                    val body = resp.body?.string().orEmpty().take(MAX_BODY_CHARS_7830)
                    val path = resp.request.url.encodedPath
                    if (isPublicPreview7830(path, body)) Fetch7830(FETCH_OK_7830, body, code)
                    else Fetch7830(FETCH_NOT_PUBLIC_7830, "", code)
                }
            }
        } catch (t: Throwable) {
            lastError = "fetch:${t.javaClass.simpleName}"
            Fetch7830(FETCH_FAILED_7830, "", 0)
        }
    }

    private fun noteThrottled7830(code: Int) {
        throttled.incrementAndGet()
        backoffLevel = (backoffLevel + 1).coerceAtMost(5)
        backoffUntilMs = System.currentTimeMillis() + (30_000L shl backoffLevel).coerceAtMost(10L * 60_000L)
        lastError = "throttled:$code"
        try { PipelineHealthCollector.labelInc("TELEGRAM_SWEEPER_BACKOFF_7830") } catch (_: Throwable) {}
    }

    private suspend fun sweepChannels7830(now: Long) {
        val picks = synchronized(lock7830) {
            registry.values.filter { it.nextDueMs <= now }.sortedBy { it.nextDueMs }.take(CHANNELS_PER_PASS_7830)
        }
        for (c in picks) {
            val f = fetchPreview7830(c.name)
            if (f.kind == FETCH_THROTTLED_7830) { noteThrottled7830(f.code); break }
            val posts = if (f.kind == FETCH_OK_7830) parsePreview7830(f.html) else emptyList()
            val mentions = LinkedHashSet<String>()
            synchronized(lock7830) {
                c.sweeps++
                if (f.kind == FETCH_OK_7830) {
                    c.deadSweeps = 0
                    c.lastOkMs = System.currentTimeMillis()
                    backoffLevel = 0
                    val firstRead = c.lastPostId == 0L
                    val fresh = posts.filter { it.channel == c.name && it.postId > c.lastPostId }
                    for (p in fresh) {
                        newPosts.incrementAndGet()
                        mentions.addAll(channelMentions7830(p))
                        val ex = extractMints7830(p.text, p.hrefs)
                        if (ex.mints.isEmpty() && ex.pairLike.isEmpty()) continue
                        val call = looksLikeCall7830(p.text)
                        if (call) callPosts.incrementAndGet()
                        val ageMs = if (p.tsMs > 0L) now - p.tsMs else if (firstRead) Long.MAX_VALUE else 0L
                        val stale = ageMs > FRESH_CALL_MAX_AGE_MS_7830
                        for (m in ex.mints + ex.pairLike) {
                            casExtracted.incrementAndGet()
                            if (stale) { staleSkipped.incrementAndGet(); continue }
                            if (verifyQueue.size < MAX_VERIFY_QUEUE_7830) {
                                verifyQueue.add(Candidate7830(m, m in ex.pairLike, c.name, if (p.tsMs > 0L) p.tsMs else now, call))
                            }
                        }
                    }
                    val maxId = posts.filter { it.channel == c.name }.maxOfOrNull { it.postId } ?: 0L
                    if (maxId > c.lastPostId) c.lastPostId = maxId
                } else {
                    c.deadSweeps++
                }
                c.nextDueMs = System.currentTimeMillis() + sweepIntervalMs7830(c.score(), c.deadSweeps)
                for (m in mentions) addPending7830(m, "MENTION:${c.name}")
                if (c.deadSweeps >= DEAD_AFTER_SWEEPS_7830) {
                    registry.remove(c.name)
                    probeRejectedAt[c.name] = System.currentTimeMillis()
                    dropped.incrementAndGet()
                }
                dirty = true
            }
            delay(POLITE_DELAY_MS_7830)
        }
    }

    private suspend fun probeCandidates7830() {
        val batch = synchronized(lock7830) {
            val b = pendingChannels.entries.take(PROBES_PER_PASS_7830).map { it.key to it.value }
            for ((n, _) in b) pendingChannels.remove(n)
            b
        }
        for ((name, origin) in batch) {
            val f = fetchPreview7830(name)
            if (f.kind == FETCH_THROTTLED_7830) {
                noteThrottled7830(f.code)
                synchronized(lock7830) { addPending7830(name, origin) }
                break
            }
            val posts = if (f.kind == FETCH_OK_7830) parsePreview7830(f.html).filter { it.channel == name } else emptyList()
            if (posts.isEmpty()) {
                probeRejected.incrementAndGet()
                probeRejectedAt[name] = System.currentTimeMillis()
                delay(POLITE_DELAY_MS_7830)
                continue
            }
            synchronized(lock7830) {
                if (!registry.containsKey(name) && makeRoom7830()) {
                    val now = System.currentTimeMillis()
                    val c = Channel7830(name, origin.take(48), now)
                    // History is not a call: start after the newest post already visible.
                    c.lastPostId = posts.maxOf { it.postId }
                    c.lastOkMs = now
                    c.nextDueMs = now + sweepIntervalMs7830(0.5, 0)
                    registry[name] = c
                    admitted.incrementAndGet()
                    dirty = true
                } else {
                    probeRejected.incrementAndGet()
                    probeRejectedAt[name] = System.currentTimeMillis()
                }
            }
            delay(POLITE_DELAY_MS_7830)
        }
    }

    /** Caller holds lock7830. Evicts the most-failing, else a proven-bad channel, when the registry is full. */
    private fun makeRoom7830(): Boolean {
        if (registry.size < MAX_REGISTRY_7830) return true
        val victim = registry.values.filter { it.deadSweeps > 0 }.maxByOrNull { it.deadSweeps }
            ?: registry.values.filter { it.booked60 >= 5L && it.score() < 0.2 }.minByOrNull { it.score() }
            ?: return false
        registry.remove(victim.name)
        dropped.incrementAndGet()
        return true
    }

    /** Verified base mint for a candidate, with its DexScreener pair; null when unverifiable. */
    private fun verify7830(cand: Candidate7830): Pair<String, PairInfo>? {
        val direct = try { dex.getBestPair(cand.mint) } catch (_: Throwable) { null }
        if (direct != null && pairVerifies7830(cand.mint, direct)) return cand.mint to direct
        if (!cand.pairLike) return null
        val base = try { dex.pairPriceFetch7392(listOf(cand.mint))[cand.mint]?.first } catch (_: Throwable) { null }
        if (base == null || !isPlausibleMint7830(base)) return null
        val p = try { dex.getBestPair(base) } catch (_: Throwable) { null } ?: return null
        return if (pairVerifies7830(base, p)) base to p else null
    }

    private fun pairVerifies7830(mint: String, p: PairInfo): Boolean {
        val base = p.tokenAddress.ifBlank { p.baseTokenAddress }
        if (base.isNotBlank() && base != mint) return false
        if (p.chainId.isNotBlank() && !p.chainId.equals("solana", ignoreCase = true)) return false
        return p.candle.priceUsd.isFinite() && p.candle.priceUsd > 0.0 && p.liquidity > 0.0
    }

    private suspend fun verifyAndEmit7830(emitter: suspend (String, PairInfo) -> String) {
        val now = System.currentTimeMillis()
        emittedAt.entries.removeIf { now - it.value > EMIT_DEDUPE_TTL_MS_7830 }
        var budget = VERIFY_PER_PASS_7830
        val seenThisPass = HashSet<String>()
        while (budget > 0) {
            val cand = verifyQueue.poll() ?: break
            if (!seenThisPass.add(cand.mint)) continue
            val tracked = calls[cand.mint]
            if (tracked != null || emittedAt.containsKey(cand.mint)) {
                dedupeSkipped.incrementAndGet()
                if (tracked != null) noteCoCaller7830(tracked, cand.channel)
                continue
            }
            budget--
            val v = verify7830(cand)
            if (v == null) { unverifiable.incrementAndGet(); continue }
            val (mint, pair) = v
            if (mint != cand.mint) {
                val t2 = calls[mint]
                if (t2 != null || emittedAt.containsKey(mint)) {
                    dedupeSkipped.incrementAndGet()
                    if (t2 != null) noteCoCaller7830(t2, cand.channel)
                    continue
                }
            }
            verified.incrementAndGet()
            emittedAt[mint] = System.currentTimeMillis()
            recordCall7830(mint, cand, pair.candle.priceUsd)
            val outcome = withTimeoutOrNull(EMIT_BUDGET_MS_7830) {
                try { emitter(mint, pair) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) { "ERROR" }
            } ?: "TIMEOUT"
            if (outcome == "EMITTED" || outcome == "REQUEUED") emitted.incrementAndGet()
            else if (outcome == "WATCHED") alreadyInPipeline.incrementAndGet()
            try { PipelineHealthCollector.labelInc("TELEGRAM_CALL_INTAKE_${outcome}_7830") } catch (_: Throwable) {}
            ErrorLogger.info(
                "TelegramSweeper7830",
                "call mint=${mint.take(10)} sym=${pair.baseSymbol} ch=${cand.channel} call=${cand.looksLikeCall} " +
                    "liq=${pair.liquidity.toInt()} intake=$outcome",
            )
        }
    }

    private fun recordCall7830(mint: String, cand: Candidate7830, entryPx: Double) {
        if (!(entryPx.isFinite() && entryPx > 0.0)) return
        if (calls.size >= MAX_TRACKED_CALLS_7830) {
            calls.entries.minByOrNull { it.value.calledAtMs }?.let { calls.remove(it.key) }
        }
        calls[mint] = Call7830(mint, cand.channel, cand.postTsMs, System.currentTimeMillis(), entryPx)
        synchronized(lock7830) {
            registry[cand.channel]?.let { it.calls++ }
            dirty = true
        }
    }

    /** A second channel called a mint we already track: the first caller earns an "earliest" credit once. */
    private fun noteCoCaller7830(c: Call7830, channel: String) {
        if (channel == c.channel) return
        synchronized(lock7830) {
            if (!c.coCallers.add(channel)) return
            if (!c.creditedFirst) {
                c.creditedFirst = true
                registry[c.channel]?.let { it.firstCalls++ }
                dirty = true
            }
        }
    }

    /**
     * Forward-return labels for emitted calls, from marks the bot already
     * collects (loop token state, canonical mark registry). No provider call.
     */
    internal fun tickCalls7830(nowMs: Long, priceFor: (String) -> Double?) {
        for ((mint, c) in calls.entries.toList()) {
            val age = nowMs - c.calledAtMs
            if (age > H60_MS_7830 + HORIZON_GRACE_MS_7830) {
                calls.remove(mint, c)
                lost.incrementAndGet()
                continue
            }
            val px = try { priceFor(mint) } catch (_: Throwable) { null } ?: continue
            if (!(px.isFinite() && px > 0.0)) continue
            val ret = ((px / c.entryPx - 1.0) * 100.0).coerceIn(-100.0, 1000.0)
            if (ret > c.peakPct) { c.peakPct = ret; c.peakAtMs = nowMs }
            if (c.ret15 == null && age >= H15_MS_7830 && age <= H15_MS_7830 + HORIZON_GRACE_MS_7830) {
                c.ret15 = ret
                booked15.incrementAndGet()
            }
            if (age >= H60_MS_7830) {
                calls.remove(mint, c)
                book60_7830(c, ret)
            }
        }
    }

    private fun book60_7830(c: Call7830, ret60: Double) {
        booked60.incrementAndGet()
        synchronized(lock7830) {
            val ch = registry[c.channel] ?: return
            ch.booked60++
            if (ret60 >= HIT_RETURN_PCT_7830) ch.hits60++
            ch.rets60.add(ret60)
            while (ch.rets60.size > MAX_RETS_PER_CHANNEL_7830) ch.rets60.removeAt(0)
            if (c.peakAtMs > 0L && c.peakPct > 0.0) {
                ch.peakMinSum += (c.peakAtMs - c.calledAtMs) / 60_000.0
                ch.peakN++
            }
            dirty = true
        }
    }

    private fun markFor7830(mint: String, nowMs: Long): Double? {
        val ts = try { com.lifecyclebot.engine.BotService.status.tokens[mint] } catch (_: Throwable) { null }
        if (ts != null && ts.lastPrice.isFinite() && ts.lastPrice > 0.0 &&
            ts.lastPriceUpdate > 0L && nowMs - ts.lastPriceUpdate <= MARK_MAX_AGE_MS_7830
        ) return ts.lastPrice
        return try {
            val m = com.lifecyclebot.engine.truth.CanonicalPriceMarkRegistry6522.get(mint)
            if (m == null || m.timestampMs <= 0L || nowMs - m.timestampMs > MARK_MAX_AGE_MS_7830) null
            else m.priceUsd.value.toDouble().takeIf { it.isFinite() && it > 0.0 }
        } catch (_: Throwable) { null }
    }

    // ── reporting ─────────────────────────────────────────────────────────

    /** Channels ranked by sweep-priority score (ordering only, never a gate). */
    internal fun topChannels7830(n: Int): List<Pair<String, Double>> = synchronized(lock7830) {
        registry.values.filter { it.booked60 > 0L }.sortedByDescending { it.score() }.take(n).map { it.name to it.score() }
    }

    /**
     * Channels that most often called a mint FIRST among several channels —
     * input for the later "pre-call insider wallets" feature.
     */
    internal fun earliestCallers7830(n: Int): List<Pair<String, Long>> = synchronized(lock7830) {
        registry.values.filter { it.firstCalls > 0L }.sortedByDescending { it.firstCalls }.take(n).map { it.name to it.firstCalls }
    }

    fun statusLine(): String {
        val (active, dead, pend, top) = synchronized(lock7830) {
            val a = registry.values.count { it.deadSweeps == 0 }
            val d = registry.size - a
            val t = registry.values.filter { it.booked60 > 0L }.sortedByDescending { it.score() }.take(5).joinToString(",") { c ->
                val med = median7830(c.rets60)
                val ttp = if (c.peakN > 0L) "%.0f".format(java.util.Locale.ROOT, c.peakMinSum / c.peakN) else "-"
                "${c.name}:${"%.2f".format(java.util.Locale.ROOT, c.score())}(${c.hits60}/${c.booked60} med=${"%.0f".format(java.util.Locale.ROOT, med)}% ttp=${ttp}m)"
            }
            listOf(a.toString(), d.toString(), pendingChannels.size.toString(), t)
        }
        val early = earliestCallers7830(3).joinToString(",") { "${it.first}:${it.second}" }
        val age = if (lastRunAtMs > 0L) "${(System.currentTimeMillis() - lastRunAtMs) / 1000}s" else "never"
        val bo = (backoffUntilMs - System.currentTimeMillis()).coerceAtLeast(0L) / 1000
        return "TELEGRAM_SWEEPER_7830 running=${isRunning()} channels=$active/$dead(active/dead) pending=$pend " +
            "admitted=${admitted.get()} dropped=${dropped.get()} sweeps=${sweeps.get()} fetches=${fetches.get()} " +
            "newPosts=${newPosts.get()} callPosts=${callPosts.get()} casExtracted=${casExtracted.get()} " +
            "stale=${staleSkipped.get()} verified=${verified.get()} unverifiable=${unverifiable.get()} " +
            "emitted=${emitted.get()} alreadyWatched=${alreadyInPipeline.get()} dedupe=${dedupeSkipped.get()} " +
            "tracked=${calls.size} booked15=${booked15.get()} booked60=${booked60.get()} lost=${lost.get()} " +
            "throttled=${throttled.get()} backoff=${bo}s lastRun=$age " +
            "topChannels=[${top}] earliest=[$early]" +
            (if (lastError.isNotBlank()) " lastError=$lastError" else "") + " authority=candidates_only"
    }
}
