package com.endurocoach.routes

import com.endurocoach.domain.AthleteProfile
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class DashboardStateStoreTest {

    private fun sampleProfile(
        stravaAthleteId: Long = 1L,
        sportFocus: String = "cycling",
        maxHr: Int = 178,
        restingHr: Int = 52,
        targetEventName: String = "Tour de Velo",
        targetEventDate: String = "2026-09-15"
    ) = AthleteProfile(
        stravaAthleteId = stravaAthleteId,
        sportFocus = sportFocus,
        maxHr = maxHr,
        restingHr = restingHr,
        targetEventName = targetEventName,
        targetEventDate = targetEventDate,
        completedAt = Instant.now().toString()
    )

    @Test
    fun hydrateFromProfileSetsMaxAndRestingHr() {
        val store = DashboardStateStore()
        store.hydrateFromProfile(sampleProfile(maxHr = 178, restingHr = 52))
        val state = store.read()
        assertEquals(178, state.maxHr)
        assertEquals(52, state.restingHr)
    }

    @Test
    fun hydrateFromProfileMarksOnboardingCompleted() {
        val store = DashboardStateStore()
        assertFalse(store.read().onboarding.completed)
        store.hydrateFromProfile(sampleProfile())
        assertTrue(store.read().onboarding.completed)
    }

    @Test
    fun hydrateFromProfileSetsSportFocus() {
        val store = DashboardStateStore()
        store.hydrateFromProfile(sampleProfile(sportFocus = "triathlon"))
        assertEquals("triathlon", store.read().onboarding.sportFocus)
    }

    @Test
    fun hydrateFromProfileSetsTargetEvent() {
        val store = DashboardStateStore()
        store.hydrateFromProfile(sampleProfile(targetEventName = "Paris Marathon", targetEventDate = "2026-04-06"))
        val onboarding = store.read().onboarding
        assertEquals("Paris Marathon", onboarding.targetEventName)
        assertEquals("2026-04-06", onboarding.targetEventDate)
    }

    @Test
    fun updateHeartRateProfileUpdatesValues() {
        val store = DashboardStateStore()
        store.updateHeartRateProfile(200, 60)
        val state = store.read()
        assertEquals(200, state.maxHr)
        assertEquals(60, state.restingHr)
    }
}
