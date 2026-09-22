package com.lifecyclebot.network

import io.github.novacrypto.base58.Base58
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64

class HeliusSenderEnvelope7250Test {
    private fun ByteArrayOutputStream.sv(value: Int) {
        var n = value
        do {
            var b = n and 0x7f
            n = n ushr 7
            if (n != 0) b = b or 0x80
            write(b)
        } while (n != 0)
    }

    private fun fixture(withCuPrice: Boolean = true): Pair<String, String> {
        val payer = ByteArray(32) { 7 }
        val compute = Base58.base58Decode("ComputeBudget111111111111111111111111111111")
        val system = Base58.base58Decode("11111111111111111111111111111111")
        val o = ByteArrayOutputStream()
        o.sv(1); o.write(ByteArray(64))
        o.write(0x80); o.write(1); o.write(0); o.write(2)
        o.sv(3); o.write(payer); o.write(compute); o.write(system)
        o.write(ByteArray(32) { 9 })
        o.sv(2)
        // ComputeBudget.SetComputeUnitPrice. Program index 1.
        o.write(1); o.sv(0); o.sv(9)
        o.write(byteArrayOf(if (withCuPrice) 3 else 2, 1, 0, 0, 0, 0, 0, 0, 0))
        // Existing system instruction refers to first loaded lookup account (3).
        o.write(2); o.sv(1); o.write(3); o.sv(1); o.write(99)
        o.sv(1); o.write(ByteArray(32) { 11 }); o.sv(1); o.write(0); o.sv(0)
        return Base64.getEncoder().encodeToString(o.toByteArray()) to Base58.base58Encode(payer)
    }

    @Test fun appends_tip_and_remaps_static_and_lookup_indexes() {
        val (tx, payer) = fixture()
        val result = HeliusSenderEnvelope7250.build(tx, payer, 200_000L)
        assertTrue(result.hasComputeUnitPrice)
        assertEquals(200_000L, result.tipLamports)

        val bytes = Base64.getDecoder().decode(result.txBase64)
        var p = 0
        fun u8() = bytes[p++].toInt() and 0xff
        fun sv(): Int { var v=0; var s=0; while (true) { val b=u8(); v=v or ((b and 127) shl s); if (b and 128 == 0) return v; s += 7 } }
        assertEquals(1, sv()); p += 64
        assertEquals(0x80, u8()); assertEquals(1, u8()); assertEquals(0, u8()); assertEquals(2, u8())
        assertEquals(4, sv()) // tip inserted before two readonly unsigned keys
        p += 4 * 32 + 32
        assertEquals(3, sv())
        assertEquals(2, u8()) // Compute program shifted 1 -> 2
        assertEquals(0, sv()); p += sv()
        assertEquals(3, u8()) // System program shifted 2 -> 3
        assertEquals(1, sv()); assertEquals(4, u8()) // loaded account shifted 3 -> 4
        p += sv()
        assertEquals(3, u8()) // appended transfer uses System Program
        assertEquals(2, sv()); assertEquals(0, u8()); assertEquals(1, u8()) // payer, inserted tip
        assertEquals(12, sv()); assertEquals(2, u8()) // System transfer discriminator
    }

    @Test(expected = IllegalArgumentException::class)
    fun refuses_sender_compatibility_without_compute_unit_price() {
        val (tx, payer) = fixture(withCuPrice = false)
        HeliusSenderEnvelope7250.build(tx, payer, 200_000L)
    }
}
