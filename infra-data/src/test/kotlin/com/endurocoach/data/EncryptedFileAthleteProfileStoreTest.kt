package com.endurocoach.data

import com.endurocoach.domain.AthleteProfile
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EncryptedFileAthleteProfileStoreTest {

    private fun tempStore(): EncryptedFileAthleteProfileStore {
        val dir = File(System.getProperty("java.io.tmpdir"), "enduro-profile-test-${System.nanoTime()}")
        dir.mkdirs()
        return EncryptedFileAthleteProfileStore(baseDir = dir)
    }

    private fun sampleProfile(stravaAthleteId: Long = 42L) = AthleteProfile(
        stravaAthleteId = stravaAthleteId,
        sportFocus = "running",
        maxHr = 185,
        restingHr = 48,
        targetEventName = "Sofia Marathon",
        targetEventDate = "2026-06-01",
        completedAt = Instant.now().toString()
    )

    @Test
    fun roundTripSaveAndLoad() {
        val store = tempStore()
        val profile = sampleProfile()
        store.save(profile)
        val loaded = store.load(profile.stravaAthleteId)
        assertEquals(profile.stravaAthleteId, loaded?.stravaAthleteId)
        assertEquals(profile.sportFocus, loaded?.sportFocus)
        assertEquals(profile.maxHr, loaded?.maxHr)
        assertEquals(profile.restingHr, loaded?.restingHr)
        assertEquals(profile.targetEventName, loaded?.targetEventName)
        assertEquals(profile.targetEventDate, loaded?.targetEventDate)
    }

    @Test
    fun loadMissingAthleteReturnsNull() {
        val store = tempStore()
        assertNull(store.load(999L))
    }

    @Test
    fun overwriteReplacesProfile() {
        val store = tempStore()
        store.save(sampleProfile(1L).copy(maxHr = 180))
        store.save(sampleProfile(1L).copy(maxHr = 195))
        assertEquals(195, store.load(1L)?.maxHr)
    }

    @Test
    fun differentAthleteIdsAreIsolated() {
        val store = tempStore()
        store.save(sampleProfile(1L).copy(sportFocus = "running"))
        store.save(sampleProfile(2L).copy(sportFocus = "cycling"))
        assertEquals("running", store.load(1L)?.sportFocus)
        assertEquals("cycling", store.load(2L)?.sportFocus)
    }

    @Test
    fun storedFileIsNotPlaintext() {
        val dir = File(System.getProperty("java.io.tmpdir"), "enduro-profile-test-${System.nanoTime()}")
        dir.mkdirs()
        val store = EncryptedFileAthleteProfileStore(baseDir = dir)
        val profile = sampleProfile()
        store.save(profile)

        val encFile = File(dir, "athlete_profile_${profile.stravaAthleteId}.enc")
        assertTrue(encFile.exists(), "Encrypted file should exist")
        val raw = encFile.readText()
        assertTrue(!raw.contains(profile.targetEventName), "File content should not contain plaintext event name")
        assertTrue(!raw.contains(profile.maxHr.toString()), "File content should not contain plaintext maxHr")
    }

    @Test
    fun deleteRemovesProfile() {
        val store = tempStore()
        val profile = sampleProfile()
        store.save(profile)
        assertEquals(profile.stravaAthleteId, store.load(profile.stravaAthleteId)?.stravaAthleteId)
        store.delete(profile.stravaAthleteId)
        assertNull(store.load(profile.stravaAthleteId))
    }

    @Test
    fun deleteNoOpForMissingAthlete() {
        val store = tempStore()
        // Should not throw
        store.delete(999L)
        assertNull(store.load(999L))
    }
}
