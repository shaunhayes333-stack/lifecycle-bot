package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8771 — code audit (static 2, 3): a hard safety check that throws refuses; it never allows the buy. */
class Aate8771FailClosedTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/engine/$p").readText()

    @Test fun the_rug_ban_pause_and_lockout_checks_fail_closed() {
        val ex = src("Executor.kt")
        assertTrue(ex.contains("val rugBlacklisted4133 = try { com.lifecyclebot.engine.RugMintBlacklist.isBlacklisted(ts.mint) } catch (_: Throwable) { true }"))
        assertTrue(ex.contains("val rugBL4134 = try { com.lifecyclebot.engine.RugMintBlacklist.isBlacklisted(ts.mint) } catch (_: Throwable) { true }"))
        val bs = src("BotService.kt")
        assertFalse(bs.contains("LaneAutoPauseGuard.isPaused(l) } catch (_: Throwable) { false }"))
        assertFalse(bs.contains("BannedTokens.isBanned(mint) } catch (_: Throwable) { false }"))
        assertFalse(bs.contains("RugMintBlacklist.isBlacklisted(identity.mint) } catch (_: Throwable) { false }"))
        assertTrue(src("PreTradeHardGate.kt").contains("catch (_: Throwable) { \"REBUY_LOCKOUT_CHECK_FAILED_8771\" }"))
        assertFalse(src("QuarantineStore.kt").contains("BannedTokens.isBanned(mint) } catch (_: Throwable) { false }"))
    }

    @Test fun an_unreadable_freeze_state_holds_the_zero_balance_wait() {
        assertTrue(src("sell/FrozenHoldingGuard8136.kt").contains("WalletTokenAccountStateAuthority7253.isFrozen(mint) } catch (_: Throwable) { true }"))
        assertFalse(com.lifecyclebot.engine.sell.FrozenHoldingGuard8136.zeroFinalityAllowed8136(true))
    }
}
