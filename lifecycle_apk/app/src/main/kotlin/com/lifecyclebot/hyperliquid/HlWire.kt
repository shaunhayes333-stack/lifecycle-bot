package com.lifecyclebot.hyperliquid

import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * V5.0.8795 §HYPERLIQUID — owner: "go ahead add hyperliquid please".
 *
 * The wire format Hyperliquid signs: an action is msgpack-encoded exactly as the official Python SDK's `msgpack.packb`
 * packs it (maps keep their insertion order, so the field order of every action below is part of the protocol), and
 * numbers travel as decimal strings ([floatToWire]). Prices and sizes are rounded to what the exchange accepts:
 * perps prices to 5 significant figures and at most 6 - szDecimals decimals, sizes to szDecimals.
 */
object HlMsgPack {
    fun pack(o: Any?): ByteArray {
        val out = ByteArrayOutputStream()
        write(out, o)
        return out.toByteArray()
    }

    private fun write(out: ByteArrayOutputStream, o: Any?) {
        when (o) {
            null -> out.write(0xc0)
            is Boolean -> out.write(if (o) 0xc3 else 0xc2)
            is Int -> writeInt(out, o.toLong())
            is Long -> writeInt(out, o)
            is String -> writeStr(out, o)
            is List<*> -> {
                header(out, o.size, 0x90, 0xdc, 0xdd)
                for (x in o) write(out, x)
            }
            is Map<*, *> -> {
                header(out, o.size, 0x80, 0xde, 0xdf)
                for ((k, v) in o) { write(out, k); write(out, v) }
            }
            else -> throw IllegalArgumentException("msgpack: unsupported ${o.javaClass.simpleName}")
        }
    }

    private fun be(out: ByteArrayOutputStream, v: Long, bytes: Int) {
        for (i in bytes - 1 downTo 0) out.write(((v ushr (8 * i)) and 0xff).toInt())
    }

    private fun header(out: ByteArrayOutputStream, n: Int, fix: Int, b16: Int, b32: Int) {
        when {
            n < 16 -> out.write(fix or n)
            n < 65_536 -> { out.write(b16); be(out, n.toLong(), 2) }
            else -> { out.write(b32); be(out, n.toLong(), 4) }
        }
    }

    private fun writeInt(out: ByteArrayOutputStream, v: Long) {
        when {
            v in 0L..0x7fL -> out.write(v.toInt())
            v in 0L..0xffL -> { out.write(0xcc); be(out, v, 1) }
            v in 0L..0xffffL -> { out.write(0xcd); be(out, v, 2) }
            v in 0L..0xffffffffL -> { out.write(0xce); be(out, v, 4) }
            v >= 0L -> { out.write(0xcf); be(out, v, 8) }
            v >= -32L -> out.write((v and 0xff).toInt())
            v >= -128L -> { out.write(0xd0); be(out, v, 1) }
            v >= -32_768L -> { out.write(0xd1); be(out, v, 2) }
            v >= Int.MIN_VALUE.toLong() -> { out.write(0xd2); be(out, v, 4) }
            else -> { out.write(0xd3); be(out, v, 8) }
        }
    }

    private fun writeStr(out: ByteArrayOutputStream, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        val n = b.size
        when {
            n < 32 -> out.write(0xa0 or n)
            n < 256 -> { out.write(0xd9); be(out, n.toLong(), 1) }
            n < 65_536 -> { out.write(0xda); be(out, n.toLong(), 2) }
            else -> { out.write(0xdb); be(out, n.toLong(), 4) }
        }
        out.write(b, 0, n)
    }
}

object HlWire {
    /** Pure: the SDK's float_to_wire: 8 decimals, trailing zeros dropped ("1670.1", "0.0147", "100", "0"). */
    fun floatToWire(x: Double): String {
        require(x.isFinite()) { "not a number: $x" }
        val rounded = BigDecimal(x).setScale(8, RoundingMode.HALF_EVEN)
        if (rounded.signum() == 0) return "0"
        return rounded.stripTrailingZeros().toPlainString()
    }

    /** Pure: a perp price the exchange accepts: 5 significant figures, at most 6 - [szDecimals] decimals. */
    fun roundPx(px: Double, szDecimals: Int): Double {
        if (!(px > 0.0) || !px.isFinite()) return 0.0
        val sig = BigDecimal.valueOf(px).round(MathContext(5, RoundingMode.HALF_EVEN))
        return sig.setScale(maxOf(0, 6 - szDecimals), RoundingMode.HALF_EVEN).toDouble()
    }

    /** Pure: a size rounded DOWN to [szDecimals] (never more than asked). */
    fun roundSz(sz: Double, szDecimals: Int): Double {
        if (!(sz > 0.0) || !sz.isFinite()) return 0.0
        return BigDecimal.valueOf(sz).setScale(maxOf(0, szDecimals), RoundingMode.DOWN).toDouble()
    }

    /** Pure: one order as the SDK's order_wires_to_order_action builds it (field order is part of the signature). */
    fun orderAction(asset: Int, isBuy: Boolean, px: String, sz: String, reduceOnly: Boolean, tif: String = "Ioc"): Map<String, Any?> =
        linkedMapOf(
            "type" to "order",
            "orders" to listOf(linkedMapOf(
                "a" to asset, "b" to isBuy, "p" to px, "s" to sz, "r" to reduceOnly,
                "t" to linkedMapOf("limit" to linkedMapOf("tif" to tif)),
            )),
            "grouping" to "na",
        )
}
