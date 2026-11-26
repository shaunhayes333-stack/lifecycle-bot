package com.lifecyclebot.engine.truth

/**
 * V5.0.9037 §THE_MONEY_FIRST — the owner's specification of 27 Nov, section 13: the report's first lines are the money the
 * wallet kept, not how many coins were evaluated. Every figure here is read from what the bot already records; nothing is
 * simulated and nothing decides a trade.
 *
 *   equity   the day's opening and current marked live equity (LiveRiskPolicy7807's UTC-day record, the one the sizing and
 *            the capital creed already read), the external flows since the day began (CapitalDrawdown7948), and the growth
 *            the trading made: (now - open - flows) / open.
 *   closes   live positions closed (CanonicalFinalizedTradeBus6464.terminalEnvelopes7863("live"): one row per position, its
 *            realised SOL and return), today / 7 days / all kept: count, chain-finalised count, net SOL, profit factor,
 *            expectancy per trade, the realised return distribution in the owner's bands, and for closes whose run reached
 *            +20 % the median share of that run the close banked.
 *   missed   the runners of the window and the first stage that lost each (RunnerFunnel8297, as RunnerCapture8017 prints it).
 */
object EconomicScorecard9037 {
    /** The owner's bands of realised return (%): loss, 0-19, 20-49, 50-99, 100-199, 200-499, 500-999, 1000+. */
    val BANDS_9037 = listOf("loss", "0-19", "20-49", "50-99", "100-199", "200-499", "500-999", "1000+")
    internal const val FINAL_PROOF_9037 = "LIVE_FINALIZED"
    private const val DAY_MS_9037 = 24L * 60L * 60_000L

    class Card9037(
        val n: Int, val finalized: Int, val netSol: Double, val winSol: Double, val lossSol: Double,
        val meanPct: Double, val bands: IntArray, val runs20: Int, val medianShareOfRun: Double,
        val finalizedNetSol9040: Double = Double.NaN,   // V5.0.9040 — the chain-finalised closes' own net
    ) {
        /** Gross won over gross lost; +infinity with wins and no loss, NaN with neither. */
        val profitFactor: Double get() = if (lossSol > 0.0) winSol / lossSol else if (winSol > 0.0) Double.POSITIVE_INFINITY else Double.NaN
        val expectancySol: Double get() = if (n > 0) netSol / n else Double.NaN
    }

    /** Pure: the band of a realised return in percent. */
    fun band9037(pct: Double): Int = when {
        !pct.isFinite() || pct < 0.0 -> 0
        pct < 20.0 -> 1
        pct < 50.0 -> 2
        pct < 100.0 -> 3
        pct < 200.0 -> 4
        pct < 500.0 -> 5
        pct < 1000.0 -> 6
        else -> 7
    }

    /** Pure: one card over closes given as (realised SOL, realised %, best excursion % or NaN, proof state). */
    fun card9037(rows: List<Row9037>): Card9037 {
        val bands = IntArray(BANDS_9037.size)
        var net = 0.0; var win = 0.0; var loss = 0.0; var pctSum = 0.0; var fin = 0; var finNet = 0.0
        val shares = ArrayList<Double>()
        for (r in rows) {
            net += r.sol
            if (r.sol > 0.0) win += r.sol else loss -= r.sol
            pctSum += r.pct
            bands[band9037(r.pct)]++
            if (r.proof == FINAL_PROOF_9037) { fin++; finNet += r.sol }
            if (r.mfePct.isFinite() && r.mfePct >= 20.0) shares.add((r.pct / r.mfePct).coerceIn(-5.0, 1.0))
        }
        shares.sort()
        val median = if (shares.isEmpty()) Double.NaN else if (shares.size % 2 == 1) shares[shares.size / 2] else (shares[shares.size / 2 - 1] + shares[shares.size / 2]) / 2.0
        return Card9037(rows.size, fin, net, win, loss, if (rows.isEmpty()) Double.NaN else pctSum / rows.size, bands, shares.size, median, finNet)
    }

    data class Row9037(val atMs: Long, val sol: Double, val pct: Double, val mfePct: Double, val proof: String)

    /** Pure: the growth the trading made over the day, in percent of the opening equity; NaN without an opening. */
    fun tradingGrowthPct9037(openSol: Double, nowSol: Double, externalFlowsSol: Double): Double =
        if (!openSol.isFinite() || openSol <= 0.0 || !nowSol.isFinite()) Double.NaN
        else (nowSol - openSol - (if (externalFlowsSol.isFinite()) externalFlowsSol else 0.0)) / openSol * 100.0

    /** Pure: the UTC day's start for [nowMs]. */
    fun utcDayStartMs9037(nowMs: Long): Long = nowMs - Math.floorMod(nowMs, DAY_MS_9037)

    private fun f4(v: Double) = if (v.isFinite()) "%.4f".format(v) else "-"
    private fun pf(v: Double) = when { v.isNaN() -> "-"; v.isInfinite() -> "inf"; else -> "%.2f".format(v) }

    /** Pure: one card as a report line. */
    fun cardLine9037(label: String, c: Card9037): String {
        if (c.n == 0) return "$label closes=0"
        val dist = BANDS_9037.indices.joinToString(" ") { "${BANDS_9037[it]}:${c.bands[it]}" }
        return "$label closes=${c.n} (chain-finalised ${c.finalized}, net ${"%+.4f".format(c.finalizedNetSol9040)}) net=${"%+.4f".format(c.netSol)} SOL pf=${pf(c.profitFactor)} " +
            "expectancy=${"%+.4f".format(c.expectancySol)} SOL / ${"%+.1f".format(c.meanPct)}% per close | realised % {$dist}" +
            (if (c.runs20 > 0) " | runs that reached +20%: ${c.runs20}, median share banked ${"%.0f".format(c.medianShareOfRun * 100.0)}%" else "")
    }

    private fun rows9037(sinceMs: Long): List<Row9037> = try {
        CanonicalFinalizedTradeBus6464.terminalEnvelopes7863("live", sinceMs).map { Row9037(it.atMs, it.realizedPnlSol, it.realizedReturnPct, it.mfePct, it.proofState) }
    } catch (_: Throwable) { emptyList() }

    // ── V5.0.9040 §THE_GROWTH_IS_RECONCILED — the first 9039 report printed tradingGrowth=+92.31 % (open 0.1951, now 0.3752,
    //    no external flows) on a day whose live closes realised -0.0260 SOL. The line divided the change in marked equity by
    //    the opening and called it trading; nothing checked that the change was made of trades. The opening is now recorded
    //    with its parts (the open positions' unrealised value at that moment, and the time), and the change since is split:
    //    realised by closes since the opening, the change in open positions' unrealised value, external flows, and the rest,
    //    printed as UNRECONCILED (an opening read while a buy or a sale was in flight, a recovered holding, an unconfirmed
    //    event). Growth is called trading growth only when the rest is within [RECONCILE_TOL_SOL_9040] or 2 % of the opening.
    //    Nothing sizes from this line. ──
    internal const val RECONCILE_TOL_SOL_9040 = 0.005
    private const val OPEN_KEY_9040 = "money_day_open_9040"
    data class Open9040(val day: String, val equitySol: Double, val unrealisedSol: Double, val atMs: Long)
    @Volatile private var open9040: Open9040? = null

    /** Pure: "day,equity,unrealised,atMs" -> the record; null when malformed. */
    fun decodeOpen9040(raw: String?): Open9040? {
        val f = raw?.split(',') ?: return null
        if (f.size != 4) return null
        val e = f[1].toDoubleOrNull() ?: return null; val u = f[2].toDoubleOrNull() ?: return null; val t = f[3].toLongOrNull() ?: return null
        return if (f[0].isNotBlank() && e.isFinite() && u.isFinite() && t > 0L) Open9040(f[0], e, u, t) else null
    }

    /** LiveRiskPolicy7807.noteEquity8057, when a UTC day's opening equity is set. */
    fun noteDayOpen9040(day: String, equitySol: Double, nowMs: Long) {
        val upnl = try { HeroSnapshotAuthority6503.current()?.liveTotalUnrealizedSol ?: 0.0 } catch (_: Throwable) { 0.0 }
        val rec = Open9040(day, equitySol, if (upnl.isFinite()) upnl else 0.0, nowMs)
        open9040 = rec
        try { com.lifecyclebot.engine.LearningPersistence.save(OPEN_KEY_9040, "${rec.day},${rec.equitySol},${rec.unrealisedSol},${rec.atMs}") } catch (_: Throwable) {}
    }

    private fun openFor9040(day: String): Open9040? {
        open9040?.takeIf { it.day == day }?.let { return it }
        val loaded = try { decodeOpen9040(com.lifecyclebot.engine.LearningPersistence.load(OPEN_KEY_9040)) } catch (_: Throwable) { null }
        return loaded?.takeIf { it.day == day }?.also { open9040 = it }
    }

    /** The parts of an equity change: realised, unrealised change, flows, and what none of them explain. */
    data class Split9040(val changeSol: Double, val realisedSol: Double, val unrealisedChangeSol: Double, val flowsSol: Double) {
        val unreconciledSol: Double get() = changeSol - realisedSol - unrealisedChangeSol - flowsSol
        val tradingSol: Double get() = realisedSol + unrealisedChangeSol
    }

    /** Pure: is the change explained by trades and flows, within the tolerance? */
    fun reconciled9040(split: Split9040, openSol: Double): Boolean =
        split.unreconciledSol.isFinite() && kotlin.math.abs(split.unreconciledSol) <= maxOf(RECONCILE_TOL_SOL_9040, 0.02 * openSol)

    /** Pure: the reconciliation line. */
    fun splitLine9040(split: Split9040, openSol: Double): String {
        val ok = reconciled9040(split, openSol)
        val trading = if (openSol > 0.0) split.tradingSol / openSol * 100.0 else Double.NaN
        return "change=${"%+.4f".format(split.changeSol)} = realised ${"%+.4f".format(split.realisedSol)} + open positions ${"%+.4f".format(split.unrealisedChangeSol)} " +
            "+ flows ${"%+.4f".format(split.flowsSol)} + UNRECONCILED ${"%+.4f".format(split.unreconciledSol)} SOL | " +
            (if (ok) "tradingGrowth=${if (trading.isFinite()) "%+.2f%%".format(trading) else "-"} (reconciled)"
             else "tradingGrowth=${if (trading.isFinite()) "%+.2f%%".format(trading) else "-"} from trades only; the equity change is NOT reconciled: the opening or a balance moved without a recorded trade or flow")
    }

    /** The report's money block (DiagnosisBrief8438, section A). */
    fun block9037(nowMs: Long = System.currentTimeMillis()): String {
        val sb = StringBuilder(1024)
        val (day, open, now) = try { LiveRiskPolicy7807.dayEquity9037() } catch (_: Throwable) { Triple("", 0.0, 0.0) }
        val dayStart = utcDayStartMs9037(nowMs)
        val flows = try { CapitalDrawdown7948.flowsSince8136(dayStart) } catch (_: Throwable) { Double.NaN }
        sb.append("               money9037 equity: UTC day ").append(day.ifBlank { "-" }).append(" open=").append(f4(open))
            .append(" now=").append(f4(now)).append(" SOL (marked live equity)\n")
        val all = rows9037(0L)
        // V5.0.9040 — the change, split; growth is not printed as trading growth unless the parts add up
        val rec = openFor9040(day)
        sb.append("               money9040 ")
        if (rec == null || !(open > 0.0) || !(now > 0.0)) {
            val growth = tradingGrowthPct9037(open, now, flows)
            sb.append("equity change ").append(if (growth.isFinite()) "%+.2f%%".format(growth) else "-")
                .append(" NOT reconciled: this day's opening was recorded before 9040 without its parts (the next UTC day's is)\n")
        } else {
            val upnlNow = try { HeroSnapshotAuthority6503.current()?.liveTotalUnrealizedSol ?: 0.0 } catch (_: Throwable) { 0.0 }
            val realised = all.filter { it.atMs >= rec.atMs }.sumOf { it.sol }
            val flowsSince = try { CapitalDrawdown7948.flowsSince8136(rec.atMs) } catch (_: Throwable) { 0.0 }
            val split = Split9040(now - rec.equitySol, realised, (if (upnlNow.isFinite()) upnlNow else 0.0) - rec.unrealisedSol, if (flowsSince.isFinite()) flowsSince else 0.0)
            sb.append(splitLine9040(split, rec.equitySol)).append('\n')
        }
        sb.append("               money9037 ").append(cardLine9037("today", card9037(all.filter { it.atMs >= dayStart }))).append('\n')
        sb.append("               money9037 ").append(cardLine9037("7 days", card9037(all.filter { it.atMs >= nowMs - 7L * DAY_MS_9037 }))).append('\n')
        sb.append("               money9037 ").append(cardLine9037("all kept", card9037(all))).append('\n')
        val missed = try { Regex("funnel8297\\{[^}]*\\}").find(RunnerCapture8017.statusLine())?.value } catch (_: Throwable) { null }
        sb.append("               money9037 missed runners by the stage that lost them: ").append(missed ?: "-").append('\n')
        return sb.toString()
    }
}
