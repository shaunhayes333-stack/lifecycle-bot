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
 * dataset's 60,000-line window, which every learner trains on, is not spent on it. The base, agreement and lane research
 * pockets now read it through the shared evidence-quality contract; it is not the Edge/MIND training dataset. A decision whose
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
    private val quarantined9043 = AtomicLong(0L)
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
    //
    // V5.0.8992 §AN_UNPRICED_READ_IS_NOT_A_LOSS — the 23:24 capture on 5.0.8990: every lane pocket read "proven{… none}",
    // the base pocket's proof NONE, the agreement pocket's research NONE. Measured on that capture's research file, the
    // cohort the pockets are made of (off-pump, v3 >= 35, decided since 24 Nov): 74 rows the labeler priced itself, mean
    // +25.1 % (69 % up); 46 lost-mark rows Jupiter did price, mean +165.9 %, median +2.2 % (57 % up); 169 lost-mark rows
    // Jupiter could not price. Jupiter failed on about 79 % of that cohort against about 9 % elsewhere, and the lost-mark
    // rows it did price were not dead: the failure is Jupiter's coverage of young pools, and 8983 scored each one as the
    // whole stake lost, which buried every pocket. An unpriced read is therefore what it is — no reading: it is not filed
    // by any pocket. The row is still written and still counted ([unpricedSeen8992]), so the gap stays visible, and the
    // read itself is now asked of the chain first where a pool is known (ForwardReturnLabeler7731.stateMark8989).
    private const val UNPRICED_PREFIX_8983 = "NO_PRICE_JUP_8884:"
    private val unpricedSeen8992 = AtomicLong(0L)
    private val quarantinedSeen9043 = AtomicLong(0L)

    /** Pure: a research row's net for a pocket: its net, else NaN (not a reading — an unpriced or late read is unknown). */
    fun rowNet8983(o: org.json.JSONObject): Double {
        val read = ResearchEvidence9043.read9043(o)
        if (read.quality == ResearchEvidence9043.Quality.UNPRICED) unpricedSeen8992.incrementAndGet()
        if (read.quality == ResearchEvidence9043.Quality.QUARANTINED) quarantinedSeen9043.incrementAndGet()
        return read.net
    }

    /** One line: unpriced reads the pockets met and did not file (each mine pass re-reads the file, so this is a running tally). */
    fun unpricedLine8992(): String = "researchUnpriced8992[notFiled=${unpricedSeen8992.get()} quarantinedReads9043=${quarantinedSeen9043.get()} rule=UNKNOWN_NOT_MINUS_100]"

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
    // V5.0.8992 — the 23:24 capture (5.0.8990): researchSeed8990[linesRead=0]. The asset was named ".jsonl.gz"; the read
    // failed without a trace (its counter is not a report line). The asset is now a ".bin" (the same gzip bytes: no build
    // step treats that extension), the reader accepts gzip or plain lines by the stream's own magic, and the report line
    // names how the last read ended.
    const val SEED_ASSET_8990 = "research_seed_8990.bin"
    private val seedLines8990 = AtomicLong(0)
    @Volatile private var seedLast8992 = "not read yet"

    /** Pure (V5.0.8992): [raw] as text lines — gunzipped when it starts with the gzip magic (1f 8b), else as it is. */
    internal fun seedReader8992(raw: java.io.InputStream): java.io.BufferedReader {
        val pb = java.io.PushbackInputStream(java.io.BufferedInputStream(raw, 64 * 1024), 2)
        val head = ByteArray(2)
        var got = 0
        while (got < 2) { val k = pb.read(head, got, 2 - got); if (k <= 0) break; got += k }
        if (got > 0) pb.unread(head, 0, got)
        val gz = got == 2 && head[0] == 0x1f.toByte() && head[1] == 0x8b.toByte()
        return (if (gz) java.util.zip.GZIPInputStream(pb, 64 * 1024) else pb).bufferedReader(Charsets.UTF_8)
    }

    /** Each line of the shipped seed to [each]; the lines read (0 when the asset or the context is missing). */
    fun forEachSeedLine8990(open: () -> java.io.InputStream? = { com.lifecyclebot.AATEApp.appContextOrNull()?.assets?.open(SEED_ASSET_8990) },
                            each: (String) -> Unit): Int {
        var n = 0
        try {
            val raw = open()
            if (raw == null) { seedLast8992 = "no app context"; return 0 }
            seedReader8992(raw).useLines { lines ->
                lines.forEach { l -> if (l.isNotBlank()) { n++; try { each(l) } catch (_: Throwable) {} } }
            }
            seedLast8992 = "ok"
        } catch (t: Throwable) {
            seedLast8992 = "FAILED ${t.javaClass.simpleName}: ${t.message.orEmpty().take(60)} after $n lines"
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("RESEARCH_SEED_8990_READ_FAILED") } catch (_: Throwable) {}
        }
        seedLines8990.addAndGet(n.toLong())
        return n
    }
    fun seedLine8990(): String = "researchSeed8990[linesRead=${seedLines8990.get()} last=$seedLast8992 asset=$SEED_ASSET_8990 rule=the shipped hour rows are read by each pocket before the device's own file; the device's rows displace them as its windows fill]"

    fun file8877(): File? = try { com.lifecyclebot.AATEApp.appContextOrNull()?.filesDir?.let { File(it, FILE_8877) } } catch (_: Throwable) { null }

    /** Pure: one research row (JSON, NaN as null). */
    fun line8877(atMs: Long, horizonMs: Long, mint: String, lane: String, admitted: Boolean, stage: String, setup: String,
                 pred: Double, v3: Double, mcapUsd: Double, liqUsd: Double, costPct: Double, net: Double, gross: Double,
                 peakPct: Double, heldPeakPct: Double, minPct: Double, reason: String, diag: String = "", flow: String = "",
                 ageMin: Double = Double.NaN, path: String = "", priceProof9043: String = "", labelRecipe9043: String = "",
                 precursor9049: String = "", runnerPath9049: String = ""): String {
        fun n(v: Double) = if (v.isFinite()) "%.6g".format(java.util.Locale.US, v) else "null"
        fun s(v: String, max: Int) = v.replace("\\", "").replace("\"", "").take(max)
        return "{\"t\":$atMs,\"horizonMs\":$horizonMs,\"mint\":\"${s(mint, 44)}\",\"lane\":\"${s(lane, 32)}\",\"admitted\":$admitted," +
            "\"stage\":\"${s(stage, 32)}\",\"setup\":\"${s(setup, 40)}\",\"pred\":${n(pred)},\"v3\":${n(v3)},\"mcap\":${n(mcapUsd)}," +
            "\"liq\":${n(liqUsd)},\"cost\":${n(costPct)},\"net\":${n(net)},\"gross\":${n(gross)},\"peakPct\":${n(peakPct)}," +
            "\"heldPeakPct\":${n(heldPeakPct)},\"minPct\":${n(minPct)},\"reason\":\"${s(reason, 80)}\"" +
            ",\"originalReason9043\":" + org.json.JSONObject.quote(ResearchEvidence9043.originalReason9043(reason)) +
            (if (priceProof9043.startsWith("{") && priceProof9043.endsWith("}")) ",\"priceProof9043\":$priceProof9043" else "") +
            (if (labelRecipe9043.isNotBlank()) ",\"labelRecipe9043\":$labelRecipe9043,\"labelRecipeOrigin9043\":\"DECISION_FROZEN\""
             else ",\"labelRecipeOrigin9043\":" + org.json.JSONObject.quote(ForwardLabelRecipe9043.LEGACY)) +
            // V5.0.9002 — the coin's age at the decision, when it was known (LanePocket8934 mines it per lane)
            (if (ageMin.isFinite() && ageMin >= 0.0) ",\"age\":${n(ageMin)}" else "") +
            // V5.0.8891 — the hour read's path diagnostics (ForwardReturnLabeler7731.diagJson8891), when the labeler gives them
            (if (diag.startsWith("{") && diag.endsWith("}")) ",\"diag8891\":$diag" else "") +
            // V5.0.9013 — the hour's path: the low before the peak, when the peak came, the give-back after it (pathJson9013)
            (if (path.startsWith("{") && path.endsWith("}")) ",\"path9013\":$path" else "") +
            // V5.0.8924 — the buyer flow at the decision, when it was read
            (if (flow.startsWith("{") && flow.endsWith("}")) ",\"flow8924\":$flow" else "") +
            (if (precursor9049.startsWith("{") && precursor9049.endsWith("}")) ",\"precursor9049\":$precursor9049" else "") +
            (if (runnerPath9049.startsWith("{") && runnerPath9049.endsWith("}")) ",\"runnerPath9049\":$runnerPath9049" else "") + "}"
    }

    /**
     * V5.0.9013 §A_ROW_SAYS_WHICH_CAME_FIRST — owner, 25 Nov 19:57: "both" (the crypto rows carrying what their specialist
     * saw, and the rows carrying the price path). The 9012 audit could not grade a stop, a profit lock or a top-up for any
     * lane: a row held the hour's low and its peak but not their order, so "stop 20 % / take 50 %" was undecidable whenever
     * both were touched. The labeler already keeps the three facts that decide it for every observation and wrote none of
     * them: the lowest gross BEFORE the peak ([dipBeforePeakPct], ForwardReturnLabeler7731 dipBeforePeak7962), when the
     * peak was set ([peakAfterMs] after the decision) and the give-back five minutes after the peak ([giveback5Pct]). With
     * them a stop of S and a target of T resolve exactly: the stop came first when the dip before the peak reached -S; else
     * the target when the peak reached T; else the low after the peak. Pure; "" when the peak was never dated (a row
     * without a path stays as it was).
     */
    fun pathJson9013(dipBeforePeakPct: Double, peakAfterMs: Long, giveback5Pct: Double): String {
        if (peakAfterMs <= 0L) return ""
        fun n(v: Double) = if (v.isFinite()) "%.6g".format(java.util.Locale.US, v) else "null"
        return "{\"dip\":${n(dipBeforePeakPct)},\"tPeakS\":${peakAfterMs / 1000L},\"gb5\":${n(giveback5Pct)}}"
    }

    /**
     * Pure (V5.0.9013): did a stop of [stopPct] come before a target of [targetPct] on a row's path? null when the row cannot
     * say. Counted for the stop when the price reached it anywhere before its final peak (a target touched, lost and regained
     * reads as stopped: the cautious side).
     */
    fun stopFirst9013(dipBeforePeakPct: Double, peakPct: Double, minPct: Double, stopPct: Double, targetPct: Double): Boolean? {
        if (!(stopPct > 0.0) || !(targetPct > 0.0) || !peakPct.isFinite() || !minPct.isFinite()) return null
        val dip = if (dipBeforePeakPct.isFinite()) dipBeforePeakPct else return if (minPct > -stopPct) false else if (peakPct < targetPct) true else null
        return when {
            dip <= -stopPct -> true          // the stop was touched on the way to the peak
            peakPct >= targetPct -> false    // the target came with the stop untouched
            else -> minPct <= -stopPct       // neither before the peak: the stop only if the low after it reached it
        }
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
        return "{\"asOfMs\":${v.atMs},\"buyers60\":${v.distinctBuyers60s},\"new15\":${v.newBuyers15s},\"perMin\":${n(v.buyersPerMin)},\"buyShare\":${n(v.buySharePct)}," +
            "\"netSol60\":${n(v.netSol60s)},\"buyTx15\":${v.buyTx15s},\"buyTxPrev15\":${v.buyTxPrev15s},\"repeat60\":${v.repeatBuyers60s}," +
            "\"txPerMin\":${n(v.txPerMin)},\"accel\":${n(v.volumeAccelScore)},\"sustained\":${v.sustainedDemand}}"
    }

    /** Compact immutable decision features from the existing EdgeModel snapshot, never re-read at label time.
     * Full asset identity is retained here (older research rows truncated asset IDs).
     * Legacy/missing features remain absent, and the flow source time is explicit.
     */
    internal fun precursor9049(mint: String, lane: String, atMs: Long, snap: EdgeModel8537.Snap?, flow: String): String {
        val o = org.json.JSONObject().put("v", 1).put("assetId", mint).put("lane", lane).put("atMs", atMs)
        val s = snap?.takeIf { it.mint == mint && it.raw.lane == lane && it.atMs == atMs }
        o.put("rawPresent", s != null)
        if (s != null) {
            val r = s.raw
            o.put("source", r.source.take(120)).put("phase", r.phase).put("modelEpoch", s.modelEpoch)
            fun n(k: String, v: Double) { if (v.isFinite()) o.put(k, v) }
            n("age",r.ageMin); n("runup",r.runup); n("pxPeak",r.pxPeak); n("dd",r.dd)
            n("bp",r.bp); n("chg5m",r.chg5m); n("chg1h",r.chg1h); n("volAccel",r.volAccel)
            n("holderVel",r.holderVel); n("txVel",r.txVel); n("top",r.top); n("turnover",r.turnover)
            n("newBuyers15s8666",r.newBuyers15s8666)
            for (k in listOf("agePrice","ageBp","ageChg","ageLiq","ageHolders","secsSincePrint",
                "oppVolAccel","oppTxAccel","oppLiqDelta","buyTx15s","buyTxPrev15s","repeatBuyers60s"))
                n(k, LearnerColumns8798.valueOf8873(r.ext, k))
        }
        // A stale, absent or future observation is not zero demand.
        try {
            if (flow.isNotBlank()) {
                val b = org.json.JSONObject(flow)
                if (atMs - b.optLong("asOfMs") in 0L..20_000L) o.put("flow",b)
            }
        } catch (_: Exception) {}
        return o.toString().takeIf { it.length <= 2048 }.orEmpty()
    }

    internal fun validPrecursor9049(raw: String, mint: String, lane: String, atMs: Long): String {
        if (raw.isBlank() || raw.length > 2048) return ""
        return try {
            val o = org.json.JSONObject(raw)
            raw.takeIf { o.optInt("v") == 1 && o.optString("assetId") == mint &&
                o.optString("lane") == lane && o.optLong("atMs") == atMs }.orEmpty()
        } catch (_: Exception) { "" }
    }

    /** ForwardReturnLabeler7731.bookTwoForty7944: write one decision's hour read. Never blocks the labeler. */
    fun note8877(line: String, nowMs: Long = System.currentTimeMillis()) {
        // Keep the raw numbers for forensic inspection; the shared read contract makes invalid outcomes unavailable.
        // Apply this on append AND in each reader so a retained historical artifact cannot re-enter a pocket at restart.
        val safeLine = try { ResearchEvidence9043.annotate9043(line) } catch (_: Exception) { failed.incrementAndGet(); return }
        if (safeLine.contains("\"quality9043\":\"QUARANTINED\"")) quarantined9043.incrementAndGet()
        try { AuthorityTable8887.ensureStarted8887() } catch (_: Throwable) {}   // V5.0.8887 — the hourly authority table grades these rows
        try { BasePocket8904.learnLine8904(safeLine) } catch (_: Throwable) {}   // V5.0.8904 — and the base pocket files its hour reads
        try { AgreementPocket8874.learnLine8929(safeLine) } catch (_: Throwable) {}   // V5.0.8929 — and the agreement pocket's research coin record
        try { LanePocket8934.learnLine8934(safeLine) } catch (_: Throwable) {}   // V5.0.8934 — and every lane's own in-band cells
        io.execute {
            try {
                val f = file8877() ?: return@execute
                f.appendText(safeLine + "\n")
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
        "research8877[written=${written.get()} failed=${failed.get()} quarantined9043=${quarantined9043.get()} lastAgoS=${if (lastAtMs > 0L) (System.currentTimeMillis() - lastAtMs) / 1000L else -1L} " +
            "staleHour8891{${stale8891.entries.sortedByDescending { it.value.get() }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }}} " +
            "file=$FILE_8877 rule=research reads retain original refusal identity and outcome provenance; unavailable/quarantined values are not priced observations]"
}
