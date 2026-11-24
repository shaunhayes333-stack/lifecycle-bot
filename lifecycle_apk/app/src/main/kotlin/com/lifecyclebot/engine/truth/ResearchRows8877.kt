package com.lifecyclebot.engine.truth

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8877 §EVERY_DECISION_IS_READ_AT_THE_HOUR (plan W8, first step) — owner, 20 Nov: "its just aate doesn't trade past 5
 * minutes for some reason" / "there are 60,000% profit opportunities out there".
 *
 * Every learner of the meme stack is taught at the lane's 5- or 15-minute target: the edge dataset holds that one horizon,
 * so no export could say which decisions became runners over the following hour, or whether a learner graded at the hour
 * would rank them differently. ForwardReturnLabeler7731 already reads every eligible decision at 60 minutes (the "240"
 * slot) and tracks its peak and the highest level held 20 s; those reads reached only the lane / stage tallies.
 *
 * Now each 60-minute read is also written as one research row — the decision's own facts (lane, admitted, stage, setup,
 * forecast, V3 score, cap, pool, cost, the refusal it met) beside the hour's net and gross, its peak, the highest level held
 * and the deepest dip — to its own file, exported beside the edge dataset. Its own file and its own line budget: the edge
 * dataset's 60,000-line window, which every learner trains on, is not spent on it, and no learner reads it. A decision whose
 * mark never moved (a lost mark) writes nothing here, as it books nothing; since 8983 ResearchFollow8884 reads it at the
 * hour instead and writes its row (priced, or NO_PRICE).
 */
object ResearchRows8877 {
    const val FILE_8877 = "edge_research_8877.jsonl"
    // V5.0.8991 — about a week (was 60,000 / 45,000, set when a decision wrote one row; with its 4 h and 24 h rows the
    // file spanned 2.3 days: the 24 Nov export held 64,519 lines for decisions 22 Nov 04:38 - 24 Nov). ~28,000 lines a day.
    private const val MAX_LINES_8877 = 210_000
    private const val KEEP_LINES_8877 = 180_000
    private const val CHECK_EVERY_8877 = 5_000L
    private val written = AtomicLong(0L)
    private val failed = AtomicLong(0L)
    @Volatile private var lastAtMs = 0L
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "research8877").apply { isDaemon = true } }

    // ── V5.0.8983 §A_DEAD_COIN_IS_A_ROW ──
    // Owner, 24 Nov 12:11: "nope. much worse". The 11:52 capture: the authority table's 1 h cells held 0 unpriced reads in
    // every lane (MOONSHOT n1158, EXPRESS n901, CORE n1264) against 9 % at 4 h and 15 % at 24 h, while the labeler's own
    // hour tallies lost 26-28 % of meme decisions (lanes60 MOONSHOT lost=340 of 1300, EXPRESS 356, CORE 353, MANIPULATED 369):
    // a decision whose mark was lost wrote no hour row (ForwardReturnLabeler7731 returned before note8877) and no 4 h / 24 h
    // wait. Every pocket was therefore mined on the coins that were still priced an hour later. Since 8983 such a decision is
    // read at the hour by ResearchFollow8884 (Jupiter); a coin Jupiter cannot price writes a NO_PRICE row, and a pocket counts
    // that row as the authority table already does for its PAYS verdict: everything lost ([UNPRICED_NET_8983]).
    private const val UNPRICED_NET_8983 = -100.0
    private const val UNPRICED_PREFIX_8983 = "NO_PRICE_JUP_8884:"

    /** Pure: a research row's net for a pocket: its net, [UNPRICED_NET_8983] for an unpriced read, else NaN (not a reading). */
    fun rowNet8983(o: org.json.JSONObject): Double {
        val net = if (o.isNull("net")) Double.NaN else o.optDouble("net", Double.NaN)
        if (net.isFinite()) return net
        return if (o.optString("reason").startsWith(UNPRICED_PREFIX_8983)) UNPRICED_NET_8983 else Double.NaN
    }

    /**
     * V5.0.8990 §THE_PROVEN_POCKETS_SHIP_WITH_THE_BUILD — owner, 24 Nov 22:14: "the proven pockets. shouldn't they become from
     * trade one spec logic once nailed down?? remember we want a bot that starts super super smart and very good at
     * trading then just gets better and better over time." The pockets (lane, base, agreement) are mined from this file's
     * hour rows; the file lives on the device, so an install with no file — a new phone, cleared data — starts with no
     * pocket and needs a day of its own rows before one proves. The owner's own rows (13,022 hour reads of the meme and
     * hour lanes, 22-24 Nov 2026, the diagnostics dropped) now ship in the build ([SEED_ASSET_8990], gzip line JSON, the
     * same row format). Each pocket reads them first, then the device's file: the same miners, the same proof bars, and
     * every window keeps its newest coins, so the device's own rows join and then displace the shipped ones. A shipped
     * pocket is evidence like any other: every gate still decides, and its door answers for its live closes (8985).
     */
    const val SEED_ASSET_8990 = "research_seed_8990.jsonl.gz"
    private val seedLines8990 = AtomicLong(0)

    /** Each line of the shipped seed to [each]; the lines read (0 when the asset or the context is missing). */
    fun forEachSeedLine8990(open: () -> java.io.InputStream? = { com.lifecyclebot.AATEApp.appContextOrNull()?.assets?.open(SEED_ASSET_8990) },
                            each: (String) -> Unit): Int {
        var n = 0
        try {
            val raw = open() ?: return 0
            java.util.zip.GZIPInputStream(raw, 64 * 1024).bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { l -> if (l.isNotBlank()) { n++; try { each(l) } catch (_: Throwable) {} } }
            }
        } catch (_: Throwable) { try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("RESEARCH_SEED_8990_READ_FAILED") } catch (_: Throwable) {} }
        seedLines8990.addAndGet(n.toLong())
        return n
    }
    fun seedLine8990(): String = "researchSeed8990[linesRead=${seedLines8990.get()} asset=$SEED_ASSET_8990 rule=the shipped hour rows are read by each pocket before the device's own file; the device's rows displace them as its windows fill]"

    fun file8877(): File? = try { com.lifecyclebot.AATEApp.appContextOrNull()?.filesDir?.let { File(it, FILE_8877) } } catch (_: Throwable) { null }

    /** Pure: one research row (JSON, NaN as null). */
    fun line8877(atMs: Long, horizonMs: Long, mint: String, lane: String, admitted: Boolean, stage: String, setup: String,
                 pred: Double, v3: Double, mcapUsd: Double, liqUsd: Double, costPct: Double, net: Double, gross: Double,
                 peakPct: Double, heldPeakPct: Double, minPct: Double, reason: String, diag: String = "", flow: String = ""): String {
        fun n(v: Double) = if (v.isFinite()) "%.6g".format(java.util.Locale.US, v) else "null"
        fun s(v: String, max: Int) = v.replace("\\", "").replace("\"", "").take(max)
        return "{\"t\":$atMs,\"horizonMs\":$horizonMs,\"mint\":\"${s(mint, 44)}\",\"lane\":\"${s(lane, 32)}\",\"admitted\":$admitted," +
            "\"stage\":\"${s(stage, 32)}\",\"setup\":\"${s(setup, 40)}\",\"pred\":${n(pred)},\"v3\":${n(v3)},\"mcap\":${n(mcapUsd)}," +
            "\"liq\":${n(liqUsd)},\"cost\":${n(costPct)},\"net\":${n(net)},\"gross\":${n(gross)},\"peakPct\":${n(peakPct)}," +
            "\"heldPeakPct\":${n(heldPeakPct)},\"minPct\":${n(minPct)},\"reason\":\"${s(reason, 80)}\"" +
            // V5.0.8891 — the hour read's path diagnostics (ForwardReturnLabeler7731.diagJson8891), when the labeler gives them
            (if (diag.startsWith("{") && diag.endsWith("}")) ",\"diag8891\":$diag" else "") +
            // V5.0.8924 — the buyer flow at the decision, when it was read
            (if (flow.startsWith("{") && flow.endsWith("}")) ",\"flow8924\":$flow}" else "}")
    }

    /**
     * V5.0.8924 §THE_RUNNER_STUDY_NEEDS_THE_BUYERS — owner, 22 Nov 00:39: "do all 5 now". The 8923 study found nothing in the
     * research rows that tells a launch runner (12 of 495 fresh pump.fun decisions ran 4x-100x) from the rest — the rows had no
     * buyer flow. Each hour row now carries the coin's buyer flow AT THE DECISION (BuyerFlow8666's 60 s window: distinct buyers,
     * new buyers in 15 s, buyers a minute, buy share, net SOL, buy-count acceleration, repeat buyers, sustained demand), so the
     * runner study and the authority table can grade them. Pure; "" when the flow was not read.
     */
    fun flowJson8924(v: com.lifecyclebot.engine.market.BuyerFlow8666.View?): String {
        if (v == null) return ""
        fun n(x: Double) = if (x.isFinite()) "%.4g".format(java.util.Locale.US, x) else "null"
        return "{\"buyers60\":${v.distinctBuyers60s},\"new15\":${v.newBuyers15s},\"perMin\":${n(v.buyersPerMin)},\"buyShare\":${n(v.buySharePct)}," +
            "\"netSol60\":${n(v.netSol60s)},\"buyTx15\":${v.buyTx15s},\"buyTxPrev15\":${v.buyTxPrev15s},\"repeat60\":${v.repeatBuyers60s}," +
            "\"txPerMin\":${n(v.txPerMin)},\"accel\":${n(v.volumeAccelScore)},\"sustained\":${v.sustainedDemand}}"
    }

    /** ForwardReturnLabeler7731.bookTwoForty7944: write one decision's hour read. Never blocks the labeler. */
    fun note8877(line: String, nowMs: Long = System.currentTimeMillis()) {
        try { AuthorityTable8887.ensureStarted8887() } catch (_: Throwable) {}   // V5.0.8887 — the hourly authority table grades these rows
        try { BasePocket8904.learnLine8904(line) } catch (_: Throwable) {}   // V5.0.8904 — and the base pocket files its hour reads
        try { AgreementPocket8874.learnLine8929(line) } catch (_: Throwable) {}   // V5.0.8929 — and the agreement pocket's research coin record
        try { LanePocket8934.learnLine8934(line) } catch (_: Throwable) {}   // V5.0.8934 — and every lane's own in-band cells
        io.execute {
            try {
                val f = file8877() ?: return@execute
                f.appendText(line + "\n")
                lastAtMs = nowMs
                if (written.incrementAndGet() % CHECK_EVERY_8877 == 0L) trim8877(f)
            } catch (_: Throwable) { failed.incrementAndGet() }
        }
    }

    /** Keep the newest [KEEP_LINES_8877] lines once the file passes [MAX_LINES_8877] (streamed, never read whole). */
    private fun trim8877(file: File) {
        var lines = 0
        file.forEachLine { lines++ }
        if (lines <= MAX_LINES_8877) return
        val skip = lines - KEEP_LINES_8877
        val tmp = File(file.parentFile, "$FILE_8877.trim")
        var idx = 0
        tmp.bufferedWriter().use { out -> file.forEachLine { l -> if (idx++ >= skip) { out.write(l); out.write("\n") } } }
        if (!tmp.renameTo(file)) tmp.delete()
    }

    // V5.0.8891 — hour reads booked on a stale last mark, by the check that refused the marks after it (measurement only)
    private val stale8891 = java.util.concurrent.ConcurrentHashMap<String, AtomicLong>()

    /** ForwardReturnLabeler7731: one hour read whose last accepted mark was older than the labeler's price age bar. */
    fun noteStale8891(lastRefusal: String) {
        val k = lastRefusal.take(48)
        (stale8891[k] ?: if (stale8891.size >= 24) stale8891.computeIfAbsent("OTHER") { AtomicLong() } else stale8891.computeIfAbsent(k) { AtomicLong() }).incrementAndGet()
    }

    /** The brief: rows written this run and how long ago the last one landed. */
    fun statusLine8877(): String =
        "research8877[written=${written.get()} failed=${failed.get()} lastAgoS=${if (lastAtMs > 0L) (System.currentTimeMillis() - lastAtMs) / 1000L else -1L} " +
            "staleHour8891{${stale8891.entries.sortedByDescending { it.value.get() }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }}} " +
            "file=$FILE_8877 rule=every decision's 60-minute read with its peak, held peak and dip; its own file, no learner reads it]"
}
