package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731

/**
 * V5.0.8438 §THE_REPORT_SAYS_WHAT_TO_FIX. Owner (30 Oct 2026): "restructure the report in a format that delivers what we
 * need to diagnose and fix things — token and trade quality, profits, code breaks, bugs and function, pipeline failures
 * and the faults or conflicts that caused them. proper development rich data."
 *
 * The status dump is ~2,400 lines of counters in build order; diagnosing 8434 meant cross-reading a dozen of them by hand
 * (the forward record of what each door refused, the lane funnel, the sell spine, the learning stores). This brief is the
 * first block of the report and reads those same authorities directly, in diagnosis order, with a verdict on every row:
 *
 *   A RUN        build, mode, wallet, live book
 *   B MONEY      canonical live closes (chain-finalised): win rate, profit factor, expectancy -> PROFITABLE / LOSING / UNPROVEN
 *   C EDGE       the forward labels (5-minute executable net, after cost) of everything the bot SAW: admitted vs refused,
 *                per lane next to that lane's funnel (qualified -> FDG -> ticket -> exec) and its live closes
 *   D DOORS      each gate with the forward record of what IT refused -> COSTING_MONEY / PROTECTING / NEUTRAL / THIN
 *   E EXITS      emergency trigger -> broadcast, the pre-route causes, held sell quotes
 *   F LEARNING   falling-hour saves, downtime label recovery
 *   G INPUTS     birth hydration
 *   H CODE       DefectSweep8433 (exceptions by in-app frame, failure-shaped counters)
 *   I FIX QUEUE  every LEAK / COSTING / FAULT / CONFLICT above, ranked by the money it represents, each with where to look
 *
 * Read-only: nothing here decides, sizes or gates a trade. A section that throws prints SECTION_FAILED with the exception
 * (itself a defect to fix) and the rest of the brief still renders.
 */
object DiagnosisBrief8438 {
    /** V5.0.8473 — the meme lanes whose stage records the brief prints, and the stages (tag) in lifecycle order. */
    private val WHERE_LANES_8473 = listOf("SHITCOIN", "MOONSHOT", "EXPRESS", "PROJECT_SNIPER", "MANIPULATED", "DIP_HUNTER")
    private val STAGES_8473 = listOf("FRESH_LAUNCH" to "LAUNCH", "BASE_START" to "BASE", "CONTROLLED_MARKUP" to "BREAKOUT",
        "MID_ACCUMULATION" to "PULLBACK", "PEAK_EXHAUSTION" to "PEAK", "DUMPING" to "DUMP")

    const val TITLE_8438 = "===== DIAGNOSIS BRIEF (§8438) — read this first ====="

    /** One fix-queue entry: [weight] orders the queue (money-like units), [text] says what and where. */
    data class Fix8438(val weight: Double, val tag: String, val text: String)

    // ── pure verdicts ──

    /** Pure: the forward record of a gate's refusals — refusing winners costs money; refusing losers protects it. */
    fun gateVerdict8438(n: Double, mean: Double, se: Double): String = when {
        n < 20.0 || !mean.isFinite() -> "THIN"
        se.isFinite() && mean - se > 0.0 -> "COSTING_MONEY"
        se.isFinite() && mean + se < 0.0 -> "PROTECTING"
        else -> "NEUTRAL"
    }

    /** Pure: a lane's state — positive forward edge it never bought, a bleeding live book, no edge, or too thin to judge. */
    fun laneVerdict8438(fwdN: Int, fwdMean: Double, exec: Long, liveN: Int, liveExpectancySol: Double,
                        winRate: Double = Double.NaN, runnerRate: Double = Double.NaN): String = when {
        liveN >= 3 && liveExpectancySol < 0.0 -> "BLEEDING_LIVE"
        // V5.0.8439 — a positive mean whose median coin loses and whose non-runners average under a tenth of the mean is a
        // runner lottery (8438: PROJECT_SNIPER +43.8% at wr17%, DIP_HUNTER +38.7% at wr35%): money only for a book that buys
        // broadly at small size and HOLDS the runners, not an edge any single entry has.
        fwdN >= 30 && fwdMean > 0.0 && exec == 0L && tailOnly8439(fwdMean, winRate, runnerRate) -> "LEAK_TAIL_ONLY"
        fwdN >= 30 && fwdMean > 0.0 && exec == 0L -> "LEAK_EDGE_NOT_BOUGHT"
        fwdN >= 30 && fwdMean <= 0.0 -> "NO_EDGE_SEEN"
        fwdN < 30 -> "THIN"
        else -> "OK"
    }

    /**
     * Pure (V5.0.8439): the median coin loses (win rate under 50%) and the runners alone must average at least 3x the mean
     * (CellTail8303 bounds) — the mean is carried by a few runners, not by the typical entry.
     */
    fun tailOnly8439(mean: Double, winRate: Double, runnerRate: Double): Boolean {
        if (!mean.isFinite() || mean <= 0.0 || !winRate.isFinite()) return false
        val t = com.lifecyclebot.engine.truth.CellTail8303.tail8303(mean, winRate, runnerRate)
        return t.medianNonPositive && t.runnerMinMeanPct.isFinite() && t.runnerMinMeanPct >= 3.0 * mean
    }

    /** Pure (V5.0.8440): the process is degraded — heap at/over 90%, the loop over 10 s a cycle, or the last crash was OOM. */
    fun runtimeFault8440(heapPct: Double, avgCycleMs: Long, lastCrashOom: Boolean): Boolean =
        heapPct >= 90.0 || avgCycleMs > 10_000L || lastCrashOom

    /**
     * Pure (V5.0.8440): birth hydration is faulty when walks resolve under 5% AND at least half of them got no RPC answer.
     * A walk that ends because the coin is proven established is an answer, not a fault.
     */
    fun birthFault8440(launched: Long, resolved: Long, noAnswer: Long): Boolean =
        launched >= 100L && resolved * 20L < launched && noAnswer * 2L >= launched

    /** Pure (V5.0.8441): the Cortex is starved when fewer than half of at least 200 due label marks were priced. */
    fun labelsStarved8441(due: Long, priced: Long): Boolean = due >= 200L && priced * 2L < due

    /** Pure (V5.0.8441): supply reads are starved when under half of at least 200 asks resolve, or locks outnumber asks. */
    fun supplyStarved8441(requested: Long, resolved: Long, locked: Long): Boolean =
        (requested >= 200L && resolved * 2L < requested) || (locked >= 200L && locked > requested)

    /** Pure: the live book's money verdict. */
    fun moneyVerdict8438(n: Int, expectancySol: Double): String = when {
        n < 10 -> "UNPROVEN"
        expectancySol > 0.0 -> "PROFITABLE"
        else -> "LOSING"
    }

    /** Pure: the queue, largest weight first, at most [max] entries. */
    fun rank8438(fixes: List<Fix8438>, max: Int = 12): List<Fix8438> = fixes.sortedByDescending { it.weight }.take(max)

    private fun pct(v: Double): String = if (v.isFinite()) "%+.1f%%".format(v) else "-"
    private fun stat(s: ForwardReturnLabeler7731.CellStat?): String =
        if (s == null || s.n60 == 0) "n0" else "n${s.n60}/${pct(s.meanNet60Pct)}/wr${(s.winRate60 * 100).toInt()}%/run${(s.runnerRate60 * 100).toInt()}%"

    private inline fun section(sb: StringBuilder, name: String, body: () -> Unit) {
        try { body() } catch (t: Throwable) {
            sb.append("  ").append(name).append(" SECTION_FAILED ").append(t.javaClass.simpleName).append(": ").append(t.message?.take(120) ?: "-").append('\n')
        }
    }

    // ── render ──

    fun render8438(nowMs: Long = System.currentTimeMillis()): String {
        val sb = StringBuilder(8 * 1024)
        val fixes = ArrayList<Fix8438>()
        sb.append(TITLE_8438).append('\n')

        section(sb, "A RUN") {
            val ver = try { com.lifecyclebot.BuildConfig.VERSION_NAME } catch (_: Throwable) { "?" }
            val live = try { RuntimeModeAuthority.isLive() } catch (_: Throwable) { false }
            val wallet = try { BotService.status.walletSol } catch (_: Throwable) { Double.NaN }
            val open = try { com.lifecyclebot.engine.truth.LiveConcentrationDoctrine7697.liveOpenCount() } catch (_: Throwable) { -1 }
            sb.append("  A RUN        build=").append(ver).append(" mode=").append(if (live) "LIVE" else "PAPER")
                .append(" wallet=").append(if (wallet.isFinite()) "%.4f".format(wallet) else "?").append(" SOL liveOpen=")
                .append(open).append('/').append(com.lifecyclebot.engine.truth.LiveConcentrationDoctrine7697.LIVE_OPEN_LIMIT_8435).append('\n')
            // V5.0.8440 — 8439 (3.2 h): heap 506/512 MB (CRITICAL, 483 hard trims that freed nothing), the bot loop at 24 s a cycle
            // (3 s fresh), one OutOfMemoryError, zero buys — and the brief's first line said nothing of it.
            val (used, max, pressure) = MemoryGuard7977.heap8440()
            val cycle = PipelineHealthCollector.avgCycleMs8440()
            val heapPct = if (max > 0L) used * 100.0 / max else 0.0
            val crash = try { com.lifecyclebot.engine.truth.StartupCrashGuard7717.lastCrashSummary() } catch (_: Throwable) { "" }
            val oom = crash.contains("OutOfMemoryError")
            val sick = runtimeFault8440(heapPct, cycle, oom)
            sb.append("               runtime heap=").append(used).append('/').append(max).append("MB (").append("%.0f".format(heapPct))
                .append("%) pressure=").append(pressure).append(" avgCycle=").append(cycle).append("ms")
                .append(if (oom) " lastCrash=OutOfMemoryError" else "").append(if (sick) " [FAULT runtime degraded]" else "").append('\n')
            if (sick) fixes += Fix8438(10_000.0, "FAULT",
                "runtime degraded (heap ${"%.0f".format(heapPct)}%, cycle ${cycle}ms${if (oom) ", OutOfMemoryError" else ""}): every price, label and entry waits on the loop -> Memory guard (§7977) blobs8440 / census unattributedMb, the largest restored states")
        }

        val laneLive = try { LiveEntrySafetyHold.LiveConfidenceStats.byLane8280(0L) } catch (_: Throwable) { emptyMap() }
        section(sb, "B MONEY") {
            val s = LiveEntrySafetyHold.LiveConfidenceStats.load()
            val v = moneyVerdict8438(s.canonicalN, s.expectancySol)
            sb.append("  B MONEY      [").append(v).append("] live closes n=").append(s.canonicalN).append(" W/L=").append(s.wins).append('/').append(s.losses)
                .append(" wr=").append("%.0f".format(s.winRatePct)).append("% pf=").append(com.lifecyclebot.engine.truth.ReturnMagnitude8419.pfText8419(s.profitFactor))
                .append(" expectancy=").append("%+.4f".format(s.expectancySol)).append(" SOL/trade (canonical chain-finalised, this governor window)\n")
            if (v == "LOSING") fixes += Fix8438(-s.expectancySol * s.canonicalN * 1000.0, "LOSING",
                "live book losing: n=${s.canonicalN} exp=${"%+.4f".format(s.expectancySol)} SOL -> per-lane rows in C (BLEEDING_LIVE) and the exits in E")
            // V5.0.8441 — trade quality: how runner-style entries ended (bought the top / gave the gain back / won).
            sb.append("               entry quality ").append(com.lifecyclebot.engine.truth.RunnerFunnel8297.outcomesLine8297()).append('\n')
        }

        section(sb, "C EDGE") {
            val adm = ForwardReturnLabeler7731.aggregate8438(true)
            val ref = ForwardReturnLabeler7731.aggregate8438(false)
            val separates = adm != null && ref != null && adm.n60 >= 30 && ref.n60 >= 30 && adm.meanNet60Pct > ref.meanNet60Pct
            // V5.0.8439 — 8438: admitted +38.3% run16% vs refused -57.5% run35%: the refused side held MORE runners, which a
            // mean comparison hides (and a -57.5% mean with 35% runners is below what its own runners allow: a mixed book).
            val runnerConflict = separates && adm!!.runnerRate60 + 0.05 < ref!!.runnerRate60
            sb.append("  C EDGE       forward 5-min executable net of what the bot saw: admitted[").append(stat(adm)).append("] refused[").append(stat(ref))
                .append("] -> ").append(when {
                    runnerConflict -> "CONFLICT means separate but the REFUSED side holds more runners"
                    separates -> "doors SEPARATE winners from losers"
                    else -> "doors do NOT separate (or thin)"
                }).append('\n')
            if (runnerConflict) fixes += Fix8438((ref!!.runnerRate60 - adm!!.runnerRate60) * 1000.0, "CONFLICT",
                "refused coins run more often than admitted ones (run ${(ref.runnerRate60 * 100).toInt()}% vs ${(adm.runnerRate60 * 100).toInt()}%) -> the doors refuse runners at entry; D DOORS + Runner capture (§8017) funnel8297 name which")
            sb.append("               lane            fwd(n/mean/wr/run)          qualified fdg(allow/block) ticket exec | live closes | verdict\n")
            for (lane in com.lifecyclebot.engine.market.SpecialistCandidateBooks7803.LANES.sorted()) {
                val f = try { ForwardReturnLabeler7731.laneStatFor7737(lane) } catch (_: Throwable) { null }
                val st = try { OwnershipChain8364.steps8438(lane) } catch (_: Throwable) { emptyMap() }
                val ls = laneLive[lane]
                val exec = st["EXEC"] ?: 0L
                val v = laneVerdict8438(f?.n60 ?: 0, f?.meanNet60Pct ?: Double.NaN, exec, ls?.canonicalN ?: 0, ls?.expectancySol ?: 0.0,
                    f?.winRate60 ?: Double.NaN, f?.runnerRate60 ?: Double.NaN)
                sb.append("               ").append(lane.padEnd(15)).append(' ').append(stat(f).padEnd(27))
                    .append(' ').append((st["QUALIFIED"] ?: 0L).toString().padStart(9)).append(' ')
                    .append("${st["FDG_ALLOW"] ?: 0L}/${st["FDG_BLOCK"] ?: 0L}".padStart(16)).append(' ')
                    .append((st["TICKET"] ?: 0L).toString().padStart(6)).append(' ').append(exec.toString().padStart(4))
                    .append(" | ").append(if (ls == null || ls.canonicalN == 0) "-" else "n${ls.canonicalN} exp${"%+.4f".format(ls.expectancySol)}")
                    .append(" | ").append(v).append('\n')
                if (v == "LEAK_TAIL_ONLY" && f != null) {
                    val t = com.lifecyclebot.engine.truth.CellTail8303.tail8303(f.meanNet60Pct, f.winRate60, f.runnerRate60)
                    fixes += Fix8438(f.meanNet60Pct * f.n60 / 4.0, "LEAK_TAIL",
                        "$lane ${pct(f.meanNet60Pct)} on n${f.n60} is runner-carried (wr${(f.winRate60 * 100).toInt()}% run${(f.runnerRate60 * 100).toInt()}%, non-runners <=${pct(t.exRunnerMaxPct)}, runners >=${pct(t.runnerMinMeanPct)}) and bought 0 -> pays only as many small entries held to the runner exit (RunnerTicket8073 budget, RunnerGrab7967); a single-entry door should keep refusing its median coin")
                }
                if (v == "LEAK_EDGE_NOT_BOUGHT" && f != null) fixes += Fix8438(f.meanNet60Pct * f.n60, "LEAK",
                    "$lane sees ${pct(f.meanNet60Pct)} forward on n${f.n60} and bought 0 (qualified ${st["QUALIFIED"] ?: 0L}, FDG ${st["FDG_ALLOW"] ?: 0L}/${st["FDG_BLOCK"] ?: 0L}) -> its ownerWhy8330 / nativeWhy8364 rows; BotService.laneEvaluationAllowed7951; LiveEdgeGate7877.canonicalRefusal8392")
                if (v == "BLEEDING_LIVE" && ls != null) fixes += Fix8438(-ls.expectancySol * ls.canonicalN * 1000.0, "BLEEDING",
                    "$lane live closes n${ls.canonicalN} exp ${"%+.4f".format(ls.expectancySol)} SOL -> its exits (ExitProfile7955 / laneHolds8418) and entry setups (LanePlaybook7907)")
            }
            // V5.0.8473 — WHERE in the coin's life each lane's buys pay, and WHICH discovery sources find coins that pay.
            sb.append("               where it pays — stage records (5-min net n/mean/run):\n")
            for (lane in WHERE_LANES_8473) {
                val cellsLine = STAGES_8473.joinToString("  ") { (stage, tag) ->
                    val st = try { com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.stageStatFor7928(lane, stage) } catch (_: Throwable) { null }
                    if (st == null || st.n60 == 0) "$tag -" else "$tag n${st.n60}/${pct(st.meanNet60Pct)}/r${(st.runnerRate60 * 100).toInt()}%"
                }
                sb.append("                 ").append(lane.padEnd(15)).append(cellsLine).append('\n')
            }
            val sources = try { com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.sourceStats8473(10) } catch (_: Throwable) { emptyList() }
            sb.append("               discovery sources (5-min net n/mean/wr/run): ")
                .append(sources.joinToString(" · ") { "${it.key.removePrefix("SRC|").take(22)} n${it.n60}/${pct(it.meanNet60Pct)}/wr${(it.winRate60 * 100).toInt()}%/r${(it.runnerRate60 * 100).toInt()}%" }.ifBlank { "-" })
                .append('\n')
        }

        section(sb, "D DOORS") {
            val ledger = com.lifecyclebot.engine.truth.GateOutcome8418.ledger8438()
            sb.append("  D DOORS      forward record of what each gate refused (n/mean/wr/run) -> verdict\n")
            if (ledger.isEmpty()) sb.append("               none graded yet\n")
            for ((gate, st) in ledger.take(14)) {
                val v = gateVerdict8438(st.n, st.mean(), st.se())
                sb.append("               ").append(gate.take(48).padEnd(48)).append(" n${st.n.toInt()}/${pct(st.mean())}/wr${(st.wins * 100.0 / st.n.coerceAtLeast(1.0)).toInt()}%/run${(st.runners * 100.0 / st.n.coerceAtLeast(1.0)).toInt()}%")
                    .append(" -> ").append(v).append('\n')
                if (v == "COSTING_MONEY") fixes += Fix8438(st.mean() * st.n, "COSTING",
                    "door $gate refuses winners: n${st.n.toInt()} ${pct(st.mean())} -> FdgGate8365.gateKey8392 '$gate' and the refusal that emits it")
            }
        }

        section(sb, "E EXITS") {
            val t2b = com.lifecyclebot.engine.sell.SellRouteSpine8144.emergencyT2bMax8438()
            val pre = com.lifecyclebot.engine.sell.SellRouteSpine8144.preRouteTop8438()
            val (asked, ok) = com.lifecyclebot.engine.truth.HeldExitQuote8189.askedOk8438()
            sb.append("  E EXITS      emergency trigger->broadcast max=").append(if (t2b > 0L) "${t2b}ms" else "-")
                .append(if (t2b > 5_000L) " [FAULT >5 s]" else "").append(" preRoute{")
                .append(pre.joinToString(",") { "${it.first}=${it.second}" }.ifBlank { "-" }).append("} heldQuote asked=").append(asked).append(" ok=").append(ok)
                .append(if (asked >= 10L && ok == 0L) " [CONFLICT route READY with no executable sell quote]" else "").append('\n')
            if (t2b > 5_000L) fixes += Fix8438(t2b / 100.0, "FAULT",
                "emergency exit ${t2b} ms trigger->broadcast; top pre-route cause ${pre.firstOrNull()?.first ?: "-"} -> SellRouteSpine8144 preRoute8435 names the sell row; Executor.doSell / liveSellInScope7314 early returns")
            if (asked >= 10L && ok == 0L) fixes += Fix8438(asked.toDouble(), "CONFLICT",
                "held positions have no executable sell quote (asked=$asked ok=0) -> heldQuote8189 missBy8435; HeldHotMarkAuthority7419.quoteSellEstimate")
        }

        section(sb, "F LEARNING") {
            val (saved, failed) = com.lifecyclebot.engine.truth.FallingHourCohort8418.saves8438()
            val d = com.lifecyclebot.engine.truth.DowntimeRecovery8430.counts8438()
            // V5.0.8439 — the recovery now yields the GeckoTerminal budget (10/min) to live chart reads: a queue that waits
            // because live reads hold the budget is not a fault; one that never asks, or whose asks all come back empty, is.
            val stalled = d[0] > 0L && d[1] == 0L && d[5] == 0L
            sb.append("  F LEARNING   fallingHour saves ok=").append(saved).append(" failed=").append(failed)
                .append(" | downtime labels queued=").append(d[0]).append(" fetched=").append(d[1]).append(" empty=").append(d[4])
                .append(" recovered=").append(d[2]).append(" gaveUp=").append(d[3]).append(" yieldedToLive=").append(d[5])
                .append(if (stalled) " [FAULT queue not draining]" else "").append('\n')
            if (failed > 0L && saved == 0L) fixes += Fix8438(failed.toDouble(), "FAULT",
                "falling-hour record not being saved (failed=$failed ok=0) -> FallingHourCohort8418.persist / LearningPersistence.saveTogetherVerified8386")
            if (stalled) fixes += Fix8438(d[0].toDouble(), "FAULT",
                "downtime label recovery queued ${d[0]} fetched 0 -> DowntimeRecovery8430.drain8430 / SolanaOhlcvFeed6916.providerSlotWait7809")
            // V5.0.8441 — the Cortex grades only labels that got a mark. 8439 (3.2 h): assessed 18571, graded 24; label marks
            // due 9657, priced 1254 (13%), lost noSource 4168 / late 2231. A lost label is not a loss: the forward means in C
            // count priced (surviving) coins only, and nothing reaches the proven-setup bar the live door asks for.
            val g = com.lifecyclebot.engine.cortex.Cortex7885.grading8441()
            val lm = ForwardReturnLabeler7731.labelMarks8441()
            val started = PipelineHealthCollector.sessionStartedAtMs7250()
            val hours = ((if (started > 0L) nowMs - started else 0L) / 3_600_000.0).coerceAtLeast(1.0 / 60.0)
            val starved = labelsStarved8441(lm[0], lm[1])
            sb.append("               cortex assessed=").append(g[0]).append(" graded=").append(g[1]).append(" (").append("%.1f".format(g[1] / hours))
                .append("/h) pendingLabels=").append(g[2]).append(" lost=").append(g[3])
                .append(" | label marks due=").append(lm[0]).append(" priced=").append(lm[1])
                .append(if (lm[0] > 0L) " (${lm[1] * 100 / lm[0]}%)" else "").append(" noSource=").append(lm[2]).append(" late=").append(lm[3])
                .append(if (starved) " [FAULT labels unpriced]" else "").append('\n')
            if (starved) fixes += Fix8438((lm[0] - lm[1]) / 10.0, "FAULT",
                "Cortex grading starved: ${lm[0] - lm[1]} of ${lm[0]} due label marks unpriced (noSource=${lm[2]} late=${lm[3]}), graded ${g[1]} of ${g[0]} assessed -> ForwardReturnLabeler7731.fetchOffWatchMarks7737 / KeylessMarks8359 lane; token states go stale when the loop is slow (A RUN)")
        }

        section(sb, "G INPUTS") {
            val (launched, resolved) = com.lifecyclebot.engine.truth.TokenBirthHydrator7441.launchedResolved8438()
            val noAnswer = com.lifecyclebot.engine.truth.TokenBirthHydrator7441.noAnswer8440()
            val sc = try { com.lifecyclebot.engine.truth.LaunchBirth8169.scope8440() } catch (_: Throwable) { longArrayOf(0L, 0L, 0L) }
            val birthFault = birthFault8440(launched, resolved, noAnswer)
            sb.append("  G INPUTS     birth walks launched=").append(launched).append(" resolved=").append(resolved)
                .append(" noRpcAnswer=").append(noAnswer)
                .append(" | sniper pastWindow=").append(sc[0]).append(" inWindow=").append(sc[1]).append(" undated=").append(sc[2])
                .append(if (birthFault) " [FAULT walks unanswered]" else "").append('\n')
            // V5.0.8440 — 8439: "PROJECT_SNIPER cannot judge a launch" was wrong; its in-window launches (725) are dated by
            // create frames. Most walks are established coins (JUPITER_STRICT / MARKET_HUNT) with no launch to date. The
            // fault is the walks no rung answered (3379 of 5335), named by rpcRoute8359's per-host fails[].
            if (birthFault) fixes += Fix8438(noAnswer / 10.0, "FAULT",
                "birth walks unanswered by every RPC rung ($noAnswer of $launched): ${sc[2]} sniper-scoped coins stay undated -> rpcRoute8359 keyless fails[] / TokenBirthHydrator7441.fetchPage")
            // V5.0.8441 — discovery: how many intakes were new coins, and whether the mint supply (the market cap's other half)
            // could be read for them. 8439: supply resolved 767 of 5622, all rungs locked 11669, declined locally 4260.
            val (newN, repeatN) = com.lifecyclebot.engine.truth.DiscoveryRate7984.session8441()
            val sup = com.lifecyclebot.engine.truth.OnChainSupplyAuthority7075.counts8441()
            val supplyStarved = supplyStarved8441(sup[0], sup[1], sup[2])
            sb.append("               discovery new=").append(newN).append(" repeat=").append(repeatN)
                .append(" (newShare ").append(com.lifecyclebot.engine.truth.DiscoveryRate7984.newSharePct7984(newN, repeatN)).append("%)")
                .append(" | supply resolved=").append(sup[1]).append('/').append(sup[0]).append(" allRungsLocked=").append(sup[2])
                .append(" declinedLocally=").append(sup[3]).append(" noSupply=").append(sup[4])
                .append(if (supplyStarved) " [FAULT supply starved]" else "").append('\n')
            if (supplyStarved) fixes += Fix8438((sup[0] - sup[1]) / 10.0, "FAULT",
                "mint supply unread for discovered coins (resolved ${sup[1]} of ${sup[0]}, locked ${sup[2]}): no market cap, so mcap-banded lanes cannot place them -> rpcRoute8359 unsupported8441 / OnChainSupplyAuthority7075 status")
            if (try { com.lifecyclebot.network.PumpFunWS.keyedStreamDenied8439() } catch (_: Throwable) { false }) {
                sb.append("               PumpPortal: keyed trade stream DENIED (the wallet linked to the API key holds < 0.02 SOL) — launch creates still arrive free; per-coin trade tapes do not\n")
                fixes += Fix8438(150.0, "OWNER_ACTION",
                    "fund the wallet linked to the PumpPortal API key with >= 0.02 SOL (it is the key's own wallet, not the trading wallet): the per-coin trade tape (buy pressure, shape) for pump launches comes from it")
            }
            // V5.0.8471 — RugCheck: reports with findings, fresh mints it has not indexed, and its number beside its findings.
            sb.append("               ").append(try { RugcheckScore8399.statusLine8471() } catch (_: Throwable) { "rugcheck8471[-]" }).append('\n')
            val gas = try { com.lifecyclebot.perps.crypto.MultiChainMemeLane7962.gasBlockedPositive8438() } catch (_: Throwable) { emptyList() }
            if (gas.isNotEmpty()) {
                sb.append("               crypto: positive EVM lanes held in SHADOW for want of gas: ").append(gas.joinToString(", ")).append('\n')
                fixes += Fix8438(100.0 * gas.size, "OWNER_ACTION",
                    "fund the EVM signer with gas on ${gas.joinToString(", ") { it.substringBefore(' ') }} — those lanes' own labels are positive after cost (Multi-chain lane row)")
            }
        }

        section(sb, "H CODE") {
            sb.append("  H CODE       ").append(DefectSweep8433.section8433(nowMs).lines().joinToString("\n               ")).append('\n')
        }

        section(sb, "I FIX QUEUE") {
            val q = rank8438(fixes)
            sb.append("  I FIX QUEUE  ").append(if (q.isEmpty()) "nothing flagged" else "${q.size} item(s), most money first").append('\n')
            q.forEachIndexed { i, f -> sb.append("               ").append(i + 1).append(". [").append(f.tag).append("] ").append(f.text).append('\n') }
        }
        sb.append('\n')
        return sb.toString()
    }
}
