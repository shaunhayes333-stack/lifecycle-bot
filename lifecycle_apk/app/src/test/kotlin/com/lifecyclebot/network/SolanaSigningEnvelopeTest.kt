package com.lifecyclebot.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SolanaSigningEnvelopeTest {
    private val signer = ByteArray(32) { (it + 1).toByte() }

    @Test
    fun accepts_single_signer_legacy_message_with_wallet_as_fee_payer() {
        val tx = transaction(versioned = false, feePayer = signer)
        val validated = SolanaSigningEnvelope.validate(tx, signer)
        assertEquals(1, validated.signatureOffset)
        assertEquals(65, validated.messageOffset)
        assertEquals(-1, validated.messageVersion)
        assertEquals(1, validated.staticAccountCount)
    }

    @Test
    fun accepts_single_signer_v0_message_with_wallet_as_fee_payer() {
        val tx = transaction(versioned = true, feePayer = signer)
        val validated = SolanaSigningEnvelope.validate(tx, signer)
        assertEquals(0, validated.messageVersion)
    }

    @Test
    fun rejects_payload_for_a_different_fee_payer() {
        val failure = runCatching {
            SolanaSigningEnvelope.validate(
                transaction(versioned = true, feePayer = ByteArray(32) { 9 }),
                signer,
            )
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("fee payer"))
    }

    @Test
    fun rejects_multi_signer_and_unknown_message_versions() {
        val multi = transaction(versioned = false, feePayer = signer).copyOf().also {
            it[65] = 2
        }
        assertTrue(runCatching { SolanaSigningEnvelope.validate(multi, signer) }.isFailure)

        val unknown = transaction(versioned = true, feePayer = signer).copyOf().also {
            it[65] = 0x81.toByte()
        }
        assertTrue(runCatching { SolanaSigningEnvelope.validate(unknown, signer) }.isFailure)
    }

    @Test
    fun rejects_truncated_or_empty_payloads() {
        assertTrue(runCatching { SolanaSigningEnvelope.validate(byteArrayOf(), signer) }.isFailure)
        assertTrue(runCatching { SolanaSigningEnvelope.validate(byteArrayOf(1, 2, 3), signer) }.isFailure)
    }

    private fun transaction(versioned: Boolean, feePayer: ByteArray): ByteArray {
        val signatureVector = byteArrayOf(1) + ByteArray(64)
        val header = byteArrayOf(1, 0, 0)
        val message = if (versioned) {
            byteArrayOf(0x80.toByte()) + header + byteArrayOf(1) + feePayer
        } else {
            header + byteArrayOf(1) + feePayer
        }
        return signatureVector + message
    }
}
