package com.lifecyclebot.engine.truth

import java.math.BigInteger

/**
 * V5.0.6386 — STRONG AMOUNT SYSTEM (Section 4 of the LIVE EXECUTION TRUTH
 * AND COMPOUNDING FOUNDATION directive).
 *
 * OPERATOR DIRECTIVE (verbatim):
 *   "Introduce non-interchangeable amount types: Lamports, SolAmount,
 *    RawTokenAmount, MintDecimals, UiTokenAmount, UsdAmount, UsdPerToken,
 *    SolPerToken. Raw token and lamport arithmetic must use BigInteger or
 *    exact integer types. Do not use Double as the source of truth for raw
 *    quantities, decimals, lamports, cost basis or realised proceeds.
 *    Decimals: UNKNOWN is null or an explicit sealed state. Zero means
 *    proven zero-decimal mint. Never coerce unknown decimals to zero.
 *    Record decimals source and proof signature."
 *
 * DESIGN
 * ──────
 * All raw / lamport values use BigInteger. Price ratios remain Double but
 * are typed so they cannot be silently mixed with raw quantities.
 *
 * Value classes (Kotlin @JvmInline) keep zero runtime overhead — they
 * compile to their underlying representation but the compiler refuses to
 * cross-assign them at call sites. This is exactly the "non-interchangeable
 * amount types" requirement.
 *
 * `MintDecimals` is a sealed type: `Known(count)` OR `Unknown`. Explicit —
 * so no silent zero coercion is possible.
 */

// ─── Raw integer scalars (BigInteger-backed) ────────────────────────────

@JvmInline
value class Lamports(val value: BigInteger) : Comparable<Lamports> {
    operator fun plus(other: Lamports) = Lamports(value + other.value)
    operator fun minus(other: Lamports) = Lamports(value - other.value)
    override fun compareTo(other: Lamports): Int = value.compareTo(other.value)
    fun isPositive(): Boolean = value.signum() > 0
    fun isNegative(): Boolean = value.signum() < 0
    fun isZero(): Boolean = value.signum() == 0

    /**
     * Convert to SolAmount using the canonical 1 SOL = 1e9 lamports ratio.
     * Precision-lossy (Double); use ONLY for display/telemetry, never for
     * accounting math. Cost basis and proceeds MUST stay in Lamports.
     */
    fun toSolDisplay(): SolAmount = SolAmount(value.toDouble() / 1_000_000_000.0)

    companion object {
        val ZERO = Lamports(BigInteger.ZERO)
        fun of(lamports: Long): Lamports = Lamports(BigInteger.valueOf(lamports))
        fun of(lamports: String): Lamports = Lamports(BigInteger(lamports))
    }
}

@JvmInline
value class RawTokenAmount(val value: BigInteger) : Comparable<RawTokenAmount> {
    operator fun plus(other: RawTokenAmount) = RawTokenAmount(value + other.value)
    operator fun minus(other: RawTokenAmount) = RawTokenAmount(value - other.value)
    override fun compareTo(other: RawTokenAmount): Int = value.compareTo(other.value)
    fun isPositive(): Boolean = value.signum() > 0
    fun isZero(): Boolean = value.signum() == 0

    /**
     * Convert to `UiTokenAmount` using known decimals. Round-trip is exact
     * for the digits within the decimals count; `UiTokenAmount.toRaw`
     * reverses this operation deterministically.
     *
     * @throws IllegalStateException if decimals is Unknown — the caller
     *   MUST resolve decimals before touching UI representations.
     */
    fun toUi(decimals: MintDecimals): UiTokenAmount {
        val d = when (decimals) {
            is MintDecimals.Known -> decimals.count
            MintDecimals.Unknown -> throw IllegalStateException(
                "RawTokenAmount.toUi called with MintDecimals.Unknown — decimals must be resolved before UI conversion",
            )
        }
        val divisor = BigInteger.TEN.pow(d)
        val whole = value.divide(divisor)
        val frac = value.mod(divisor)
        // Compose as a Double via string to preserve precision up to Double's ~15 significant digits.
        val s = if (d == 0) whole.toString() else "${whole}.${frac.toString().padStart(d, '0')}"
        return UiTokenAmount(s.toDouble())
    }

    companion object {
        val ZERO = RawTokenAmount(BigInteger.ZERO)
        fun of(raw: Long): RawTokenAmount = RawTokenAmount(BigInteger.valueOf(raw))
        fun of(raw: String): RawTokenAmount = RawTokenAmount(BigInteger(raw))
    }
}

// ─── Decimals (sealed — no silent zero coercion) ────────────────────────

/**
 * Mint decimals is EXPLICIT — either a known non-negative integer OR
 * `Unknown`. `Unknown.toZero()` intentionally does not exist. Any call site
 * that needs to touch UI or ratios MUST branch on the sealed subclasses.
 *
 * Per directive: "Zero means proven zero-decimal mint. Never coerce
 * unknown decimals to zero. Record decimals source and proof signature."
 */
sealed class MintDecimals {
    data class Known(
        val count: Int,
        val source: String,            // e.g. "TOKEN_ACCOUNT_MINT_METADATA", "HELIUS_MINT_INFO"
        val proofSignature: String,    // tx signature or "mint_account:<pubkey>" proving decimals
    ) : MintDecimals() {
        init {
            require(count in 0..24) { "decimals out of range: $count (proof=$proofSignature)" }
        }
    }
    object Unknown : MintDecimals()

    fun isKnown(): Boolean = this is Known
}

// ─── UI (Double-backed — display and math-adjacent operations only) ─────

@JvmInline
value class UiTokenAmount(val value: Double) : Comparable<UiTokenAmount> {
    override fun compareTo(other: UiTokenAmount): Int = value.compareTo(other.value)

    /**
     * Reverse the UI representation to raw exact form. Uses BigDecimal
     * so no Double-arithmetic corruption enters the raw ledger.
     */
    fun toRaw(decimals: MintDecimals): RawTokenAmount {
        val d = when (decimals) {
            is MintDecimals.Known -> decimals.count
            MintDecimals.Unknown -> throw IllegalStateException(
                "UiTokenAmount.toRaw called with MintDecimals.Unknown",
            )
        }
        // Use BigDecimal string form to avoid Double precision loss during multiplication.
        val bd = java.math.BigDecimal(value.toString())
        val scaled = bd.movePointRight(d)
            .setScale(0, java.math.RoundingMode.DOWN)
        return RawTokenAmount(scaled.toBigInteger())
    }
}

@JvmInline
value class SolAmount(val value: Double) : Comparable<SolAmount> {
    override fun compareTo(other: SolAmount): Int = value.compareTo(other.value)
    fun toLamports(): Lamports {
        val bd = java.math.BigDecimal(value.toString()).movePointRight(9)
            .setScale(0, java.math.RoundingMode.DOWN)
        return Lamports(bd.toBigInteger())
    }
}

@JvmInline
value class UsdAmount(val value: Double) : Comparable<UsdAmount> {
    override fun compareTo(other: UsdAmount): Int = value.compareTo(other.value)
}

@JvmInline
value class UsdPerToken(val value: Double)

@JvmInline
value class SolPerToken(val value: Double)
