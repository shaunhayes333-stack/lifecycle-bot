package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.AdmissionBirth8144
import com.lifecyclebot.engine.truth.LearnedAdmissionInputs6909
import org.junit.Assert.*
import org.junit.Test

class Aate8527AgeEvidenceTest {
    @Test fun unknownAgeNeverReceivesMaximumEstablishedSafetyPoints() {
        assertEquals(0, SecondScorer.ageScore8527(null))
        assertEquals(0, SecondScorer.ageScore8527(-1L))
        assertEquals(0, SecondScorer.ageScore8527(Long.MAX_VALUE))
        // Blank mint has no birth and cannot enqueue a network hydration request.
        val unknown = TokenState(mint = "", symbol = "UNKNOWN")
        assertEquals(0, SecondScorer.score(unknown).components.getValue("age"))
    }

    @Test fun observedAgesKeepTheirExistingSafetyBins() {
        val ages = listOf(0L, 59_999L, 60_000L, 299_999L, 300_000L, 1_799_999L, 1_800_000L, 7_199_999L, 7_200_000L)
        val expected = listOf(0, 0, 5, 5, 10, 10, 15, 15, 20)
        ages.zip(expected).forEach { (age, score) -> assertEquals(score, SecondScorer.ageScore8527(age)) }
        val mint = "8527KnownOldAge".padEnd(44, 'A')
        AdmissionBirth8144.noteCreateFrame8144(mint, System.currentTimeMillis() - 3L * 60L * 60_000L)
        assertEquals(20, SecondScorer.score(TokenState(mint = mint, symbol = "KNOWN")).components.getValue("age"))
    }

    @Test fun learnedAgeDistinguishesNoObservationFromARealZeroMinuteBirth() {
        assertEquals(-1, LearnedAdmissionInputs6909.observedAgeMinutes8527(null))
        assertEquals(-1, LearnedAdmissionInputs6909.observedAgeMinutes8527(-1L))
        assertEquals(-1, LearnedAdmissionInputs6909.observedAgeMinutes8527(Long.MAX_VALUE))
        assertEquals(0, LearnedAdmissionInputs6909.observedAgeMinutes8527(0L))
        assertEquals(0, LearnedAdmissionInputs6909.observedAgeMinutes8527(59_999L))
        assertEquals(1, LearnedAdmissionInputs6909.observedAgeMinutes8527(60_000L))
        assertEquals(120, LearnedAdmissionInputs6909.observedAgeMinutes8527(7_200_000L))
    }
}
