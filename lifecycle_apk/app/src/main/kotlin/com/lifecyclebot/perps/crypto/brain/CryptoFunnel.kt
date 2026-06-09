package com.lifecyclebot.perps.crypto.brain

import java.util.concurrent.atomic.AtomicLong

/**
 * V5.9.1447 — Crypto-universe per-gate funnel counters.
 *
 * Operator-reported "crypto universe isn't trading at all" — without
 * per-gate visibility there is no way to tell which step is killing
 * every candidate. This object tracks every gate the crypto pipeline
 * passes a candidate through and exposes a summary line for the Live
 * Forensics dump.
 *
 * The crypto pipeline visits these stages, in order:
 *   1. universeFilter      — symbol admitted to crypto universe
 *   2. scannedMarkets      — actually scanned this cycle
 *   3. analyzeAlt          — returned a non-null AltSignal
 *   4. thresholdPass       — score+conf passed Fluid threshold
 *   5. v3Override          — admitted via V3-OVERRIDE path
 *   6. preFdgBuy           — buildCryptoFinalBuyCandidate verdict==BUY
 *   7. execGateAllow       — TradeAuthorizer allowed execution
 *   8. opened              — position actually opened
 *
 * Every stage has an `allow` and `block` counter. Reading the dump
 * tells the operator where the funnel collapses to zero.
 */
object CryptoFunnel {

    private val universeAllow = AtomicLong(0L)
    private val universeBlock = AtomicLong(0L)
    private val analyzeReturn = AtomicLong(0L)
    private val analyzeSkip   = AtomicLong(0L)
    private val thresholdPass = AtomicLong(0L)
    private val thresholdFail = AtomicLong(0L)
    private val v3Override    = AtomicLong(0L)
    private val v3Veto        = AtomicLong(0L)
    private val preFdgBuy     = AtomicLong(0L)
    private val preFdgBlock   = AtomicLong(0L)
    private val execGateAllow = AtomicLong(0L)
    private val execGateBlock = AtomicLong(0L)
    private val opened        = AtomicLong(0L)
    private val openedFailed  = AtomicLong(0L)

    fun universe(allowed: Boolean) {
        if (allowed) universeAllow.incrementAndGet() else universeBlock.incrementAndGet()
    }
    fun analyze(returned: Boolean) {
        if (returned) analyzeReturn.incrementAndGet() else analyzeSkip.incrementAndGet()
    }
    fun threshold(passed: Boolean) {
        if (passed) thresholdPass.incrementAndGet() else thresholdFail.incrementAndGet()
    }
    fun v3(override: Boolean) {
        if (override) v3Override.incrementAndGet() else v3Veto.incrementAndGet()
    }
    fun preFdg(buy: Boolean) {
        if (buy) preFdgBuy.incrementAndGet() else preFdgBlock.incrementAndGet()
    }
    fun execGate(allowed: Boolean) {
        if (allowed) execGateAllow.incrementAndGet() else execGateBlock.incrementAndGet()
    }
    fun open(success: Boolean) {
        if (success) opened.incrementAndGet() else openedFailed.incrementAndGet()
    }

    fun summary(): String = buildString {
        appendLine("Crypto Funnel (V5.9.1447):")
        appendLine("  universe       allow=${universeAllow.get()}  block=${universeBlock.get()}")
        appendLine("  analyzeAlt     ok=${analyzeReturn.get()}  skip=${analyzeSkip.get()}")
        appendLine("  threshold      pass=${thresholdPass.get()}  fail=${thresholdFail.get()}")
        appendLine("  v3-override    yes=${v3Override.get()}  veto=${v3Veto.get()}")
        appendLine("  preFdg=BUY     yes=${preFdgBuy.get()}  block=${preFdgBlock.get()}")
        appendLine("  EXEC_GATE      allow=${execGateAllow.get()}  block=${execGateBlock.get()}")
        append    ("  opened         ok=${opened.get()}  fail=${openedFailed.get()}")
    }

    fun reset() {
        universeAllow.set(0L); universeBlock.set(0L)
        analyzeReturn.set(0L); analyzeSkip.set(0L)
        thresholdPass.set(0L); thresholdFail.set(0L)
        v3Override.set(0L); v3Veto.set(0L)
        preFdgBuy.set(0L); preFdgBlock.set(0L)
        execGateAllow.set(0L); execGateBlock.set(0L)
        opened.set(0L); openedFailed.set(0L)
    }
}
