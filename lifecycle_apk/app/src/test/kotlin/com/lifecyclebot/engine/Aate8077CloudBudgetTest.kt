package com.lifecyclebot.engine

import com.lifecyclebot.collective.CloudRequestBudget8077
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class Aate8077CloudBudgetTest {
    @Test fun replayBurstQueuesWithoutOccupyingMoreThanItsCloudBudget() = runBlocking {
        Executors.newFixedThreadPool(4).asCoroutineDispatcher().use { dispatcher ->
            val budget = CloudRequestBudget8077(2, dispatcher)
            val firstTwo = CountDownLatch(2)
            val release = CountDownLatch(1)
            val active = AtomicInteger()
            val peak = AtomicInteger()
            val jobs = (1..40).map { value ->
                async(start = CoroutineStart.UNDISPATCHED) {
                    budget.run {
                        val count = active.incrementAndGet()
                        peak.updateAndGet { maxOf(it, count) }
                        firstTwo.countDown()
                        try {
                            check(release.await(5, TimeUnit.SECONDS))
                            value
                        } finally { active.decrementAndGet() }
                    }
                }
            }
            try {
                assertTrue(firstTwo.await(5, TimeUnit.SECONDS))
                assertEquals(2, active.get())
                // An unrelated control task can run while the cloud burst waits.
                assertEquals("startup", withContext(dispatcher) { "startup" })
            } finally { release.countDown() }
            assertEquals((1..40).toList(), withTimeout(5_000) { jobs.awaitAll() })
            assertEquals(2, peak.get())
        }
    }

    @Test fun cancellingAQueuedUploadDoesNotRunItOrLeakItsPermit() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val budget = CloudRequestBudget8077(1, dispatcher)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val holder = launch { budget.run { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) } }
            yield()
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val waiting = launch(start = CoroutineStart.UNDISPATCHED) { budget.run { fail("cancelled upload ran") } }
                waiting.cancelAndJoin()
            } finally { release.countDown() }
            holder.join()
            assertEquals(7, withTimeout(5_000) { budget.run { 7 } })
        }
    }

    @Test fun cancellingActiveBlockingUploadInterruptsItAndReleasesCapacity() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val budget = CloudRequestBudget8077(1, dispatcher)
            val entered = CountDownLatch(1)
            val holder = launch { budget.run { entered.countDown(); CountDownLatch(1).await() } }
            yield()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            withTimeout(5_000) { holder.cancelAndJoin() }
            assertEquals(9, withTimeout(5_000) { budget.run { 9 } })
        }
    }

    @Test fun smokeHistoryHonorsTheExistingSharingToggleBeforeFirstLaunch() {
        val roots = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }.toList()
        val root = roots.first { File(it, "ci/runtime-test.sh").exists() }
        val main = File(root, "lifecycle_apk/app/src/main/kotlin/com/lifecyclebot")
        val sharing = File(main, "collective/CollectiveLearning.kt").readText()
        assertTrue(sharing.contains("if (!sharingEnabled8077(ctx))"))
        assertTrue(sharing.contains("val enabled = sharingEnabled8077() && isInitialized && client != null"))
        assertTrue(sharing.contains("if (!sharingEnabled8077()) return false"))
        val smoke = File(root, "ci/runtime-test.sh").readText()
        assertTrue(smoke.indexOf("name=\"collective_learning_enabled\", value=\"false\"") < smoke.indexOf("adb shell monkey"))
        val client = File(main, "collective/TursoClient.kt").readText()
        assertEquals(2, Regex("requestBudget8077.run \\{ httpPost\\(body\\) }").findAll(client).count())
        assertTrue(client.contains("private val requestBudget8077 = CloudRequestBudget8077()"))
    }
}
