package com.lifecyclebot.network

import com.lifecyclebot.validator.ValidatorFeed as Wire
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class ValidatorWire8506Test {
    // Independent Yellowstone field encoding: v0, two signers, a writable tip account and read-only Token-2022.
    private val fixture = "CgttaW50X2xhdW5jaCLfBArYBApAAAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8gISIjJCUmJygpKissLS4vMDEyMzQ1Njc4OTo7PD0+PxrFARLCAQoCCAISIAEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBEiACAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAhIgAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMSIAFW4PaTZlrPRNsVaL8XW6pRicuX9dL/O2VdK7b9bRiwEiAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACIQCAMSAgIBGgjWkEzsX4sxtCgBIssCKmQIABIUCAQSAgAFGgwCAAAAoGgGAAAAAAASSggGEgECGkMUAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAMj5Qcm9ncmFtIDZFRjhycmVjdGhSNURrem9uOE53dTc4aFJ2ZkNLdWJKMTRNNXVCRXdGNlAgaW52b2tlIFsxXTIiUHJvZ3JhbSBsb2c6IEluc3RydWN0aW9uOiBDcmVhdGVWMjI7UHJvZ3JhbSA2RUY4cnJlY3RoUjVEa3pvbjhOd3U3OGhSdmZDS3ViSjE0TTV1QkV3RjZQIHN1Y2Nlc3NiIHhSHLF5zruFibVWotXslNJJhoL9+bsq9a1k5JHMQVPaaiAG3fbh7nWP3hhCXbzkbM3athr8TYO5DSf+vfko2KGL/BDAxAdaBgiApKfaBg=="

    @Test fun v0FrameUsesLoadedAddressOrderAndInnerInstructions() {
        val update = Wire.Update.parseFrom(Base64.getDecoder().decode(fixture))
        val tx = update.transaction
        val result = ValidatorTx8506.normalized(tx.transaction, tx.slot, 0)
        assertEquals(123456L, result.getLong("slot"))
        assertEquals(0L, result.getLong("blockTime"))
        val keys = result.getJSONObject("transaction").getJSONObject("message").getJSONArray("accountKeys")
        assertEquals(7,keys.length())
        assertTrue(keys.getJSONObject(1).getBoolean("signer"))
        assertFalse(keys.getJSONObject(5).getBoolean("signer"))
        val ixs = SolanaSwapEvidence8506.instructions(result)
        assertEquals(420000L, SolanaSwapEvidence8506.buyerTip(ixs,keys.getJSONObject(1).getString("pubkey"),keys.getJSONObject(0).getString("pubkey")))
        val launch = ValidatorMarket8506.launches(result,tx.transaction.meta.logMessagesList,"fixture-sig").single()
        assertEquals(keys.getJSONObject(2).getString("pubkey"),launch.mint)
        assertTrue(launch.mintInitInTx)
        assertEquals("CHAIN_LAUNCH_PUMPFUN", launch.source)
    }

    @Test fun validatorSubscriptionRetainsLaunchFiltersWhenTapeChanges() {
        val r = ValidatorFeed8506.subscription(List(20) { "mint$it" })
        assertEquals(1,r.commitment)
        assertTrue(r.getTransactionsOrThrow("mint_launch").accountIncludeList.contains(ValidatorFeed8506.MINT_AUTHORITY))
        assertTrue(r.getTransactionsOrThrow("pool_launch").accountRequiredList.contains(SolanaSwapEvidence8506.SYSTEM))
        assertEquals(10,r.getTransactionsOrThrow("tape_1").accountIncludeCount)
        assertFalse(r.getTransactionsOrThrow("mint_launch").failed)
        assertTrue(r.blocksMetaMap.containsKey("clock"))
        val early = ValidatorFeed8506.subscription(emptyList(), processed = true)
        assertEquals(0, early.commitment)
        assertTrue(early.blocksMetaMap.isEmpty())
    }

    @Test fun processedCreateOnlyWarmsItsMintAndFailedFrameCannotWarmIt() {
        val update = Wire.Update.parseFrom(Base64.getDecoder().decode(fixture))
        val hints = ArrayList<String>()
        ValidatorTx8506.accept(update, 1_800_000_000_000L, true) { hints.add(it) }
        assertEquals(1, hints.size)
        val failedInfo = update.transaction.transaction.toBuilder().setMeta(
            update.transaction.transaction.meta.toBuilder().setErr(Wire.Empty.getDefaultInstance()))
        val failed = update.toBuilder().setTransaction(update.transaction.toBuilder().setTransaction(failedInfo)).build()
        ValidatorTx8506.accept(failed, 1_800_000_000_000L, true) { hints.add(it) }
        assertEquals(1, hints.size)
        ValidatorTx8506.reset()
    }
}
