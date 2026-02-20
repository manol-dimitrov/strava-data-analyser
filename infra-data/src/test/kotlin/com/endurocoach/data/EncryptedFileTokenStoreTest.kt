package com.endurocoach.data

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EncryptedFileTokenStoreTest {

    private fun tempStore(): EncryptedFileTokenStore {
        val dir = File(System.getProperty("java.io.tmpdir"), "enduro-test-${System.nanoTime()}")
        dir.mkdirs()
        return EncryptedFileTokenStore(baseDir = dir)
    }

    @Test
    fun roundTripSaveAndLoad() {
        val store = tempStore()
        val original = """{"access_token":"abc123","refresh_token":"xyz","expires_at":9999999}"""
        store.saveToken("test_token", original)
        val loaded = store.loadToken("test_token")
        assertEquals(original, loaded)
    }

    @Test
    fun loadMissingKeyReturnsNull() {
        val store = tempStore()
        assertNull(store.loadToken("nonexistent"))
    }

    @Test
    fun overwriteReplacesValue() {
        val store = tempStore()
        store.saveToken("key", "first")
        store.saveToken("key", "second")
        assertEquals("second", store.loadToken("key"))
    }

    @Test
    fun differentKeysAreIsolated() {
        val store = tempStore()
        store.saveToken("a", "value-a")
        store.saveToken("b", "value-b")
        assertEquals("value-a", store.loadToken("a"))
        assertEquals("value-b", store.loadToken("b"))
    }

    @Test
    fun storedFileIsNotPlaintext() {
        val dir = File(System.getProperty("java.io.tmpdir"), "enduro-test-${System.nanoTime()}")
        dir.mkdirs()
        val store = EncryptedFileTokenStore(baseDir = dir)
        val secret = "super-secret-token-data"
        store.saveToken("enc_test", secret)

        val encFile = File(dir, "enc_test.enc")
        assertTrue(encFile.exists(), "Encrypted file should exist")
        val raw = encFile.readText()
        assertTrue(!raw.contains(secret), "File content should not contain plaintext token")
    }

    @Test
    fun handlesUnicodeContent() {
        val store = tempStore()
        val unicode = """{"name":"Ünïcödé tëst 🏃‍♂️","token":"äöü"}"""
        store.saveToken("unicode", unicode)
        assertEquals(unicode, store.loadToken("unicode"))
    }
    @Test
    fun deleteTokenRemovesFile() {
        val store = tempStore()
        store.saveToken("to_delete", "some-token-data")
        assertEquals("some-token-data", store.loadToken("to_delete"))
        store.deleteToken("to_delete")
        assertNull(store.loadToken("to_delete"))
    }

    @Test
    fun deleteTokenNoOpForMissingKey() {
        val store = tempStore()
        // Should not throw
        store.deleteToken("nonexistent_key")
        assertNull(store.loadToken("nonexistent_key"))
    }}
