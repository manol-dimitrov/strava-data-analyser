package com.endurocoach.data

import com.endurocoach.domain.TokenStore
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class EncryptedFileTokenStore(
    private val baseDir: File = File(System.getProperty("user.home"), ".enduro-coach"),
    private val envKeyName: String = "ENDURO_TOKEN_KEY"
) : TokenStore {
    private val random = SecureRandom()

    override fun saveToken(key: String, tokenJson: String) {
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }

        val outputFile = File(baseDir, "$key.enc")
        val secretKey = secretKey()
        val iv = ByteArray(12).also { random.nextBytes(it) }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(tokenJson.toByteArray(Charsets.UTF_8))

        val payload = ByteBuffer.allocate(iv.size + encrypted.size)
            .put(iv)
            .put(encrypted)
            .array()

        outputFile.writeText(Base64.getEncoder().encodeToString(payload), Charsets.UTF_8)
    }

    override fun loadToken(key: String): String? {
        val inputFile = File(baseDir, "$key.enc")
        if (!inputFile.exists()) {
            return null
        }

        val payload = Base64.getDecoder().decode(inputFile.readText(Charsets.UTF_8))
        if (payload.size <= 12) {
            return null
        }

        val iv = payload.copyOfRange(0, 12)
        val cipherBytes = payload.copyOfRange(12, payload.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        val plain = cipher.doFinal(cipherBytes)

        return plain.toString(Charsets.UTF_8)
    }

    private fun secretKey(): SecretKeySpec {
        val raw = System.getenv(envKeyName)
            ?: "${System.getProperty("user.name")}:${System.getProperty("user.home")}:enduro-coach-dev"

        val keyBytes = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .copyOf(32)

        return SecretKeySpec(keyBytes, "AES")
    }
}
