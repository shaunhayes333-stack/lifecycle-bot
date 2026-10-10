package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7983SellParentsTest {
    @Test fun botBuyLotsParentItsSells() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/ForensicReconciler6377.kt").readText()
        assertTrue(s.contains("FillLotLedger6344.snapshotForWallet(WalletManager.currentPubkey()).forEach { out += it.mintAddress }"))
    }
}
