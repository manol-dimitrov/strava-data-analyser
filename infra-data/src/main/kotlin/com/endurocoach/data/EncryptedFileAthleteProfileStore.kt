package com.endurocoach.data

import com.endurocoach.domain.AthleteProfile
import com.endurocoach.domain.AthleteProfileStore
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Persists [AthleteProfile] to disk using AES-256-GCM encryption.
 *
 * Each athlete is stored as `athlete_profile_<stravaAthleteId>.enc` inside
 * [baseDir] (default `~/.enduro-coach/`). The encryption key derivation reuses
 * the same logic as [EncryptedFileTokenStore] so both stores can share the same
 * `ENDURO_TOKEN_KEY` environment variable.
 */
class EncryptedFileAthleteProfileStore(
    private val baseDir: File = File(System.getenv("DATA_DIR") ?: System.getProperty("user.home"), ".enduro-coach"),
    private val envKeyName: String = "ENDURO_TOKEN_KEY"
) : AthleteProfileStore {

    private val json = Json { ignoreUnknownKeys = true }
    private val random = SecureRandom()

    override fun save(profile: AthleteProfile) {
        if (!baseDir.exists()) baseDir.mkdirs()

        val payload = json.encodeToString(profile)
        val outputFile = fileFor(profile.stravaAthleteId)
        val secretKey = secretKey()
        val iv = ByteArray(12).also { random.nextBytes(it) }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(payload.toByteArray(Charsets.UTF_8))

        val buffer = ByteBuffer.allocate(iv.size + encrypted.size)
            .put(iv)
            .put(encrypted)
            .array()

        outputFile.writeText(Base64.getEncoder().encodeToString(buffer), Charsets.UTF_8)
    }

    override fun load(stravaAthleteId: Long): AthleteProfile? {
        val inputFile = fileFor(stravaAthleteId)
        if (!inputFile.exists()) return null

        val buffer = Base64.getDecoder().decode(inputFile.readText(Charsets.UTF_8))
        if (buffer.size <= 12) return null

        val iv = buffer.copyOfRange(0, 12)
        val cipherBytes = buffer.copyOfRange(12, buffer.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        val plain = cipher.doFinal(cipherBytes)

        return runCatching { json.decodeFromString<AthleteProfile>(plain.toString(Charsets.UTF_8)) }.getOrNull()
    }

    override fun delete(stravaAthleteId: Long) {
        val file = fileFor(stravaAthleteId)
        if (file.exists()) file.delete()
    }

    private fun fileFor(stravaAthleteId: Long): File =
        File(baseDir, "athlete_profile_$stravaAthleteId.enc")

    private fun secretKey(): SecretKeySpec {
        val raw = System.getenv(envKeyName)
            ?: "${System.getProperty("user.name")}:${System.getProperty("user.home")}:enduro-coach-dev"

        val keyBytes = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .copyOf(32)

        return SecretKeySpec(keyBytes, "AES")
    }
}
