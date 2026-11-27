package com.meshnet.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MeshSecretStoreTest {
    private lateinit var context: Context
    private lateinit var preferences: android.content.SharedPreferences
    private lateinit var store: MeshSecretStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        preferences = context.getSharedPreferences("meshnet-secrets", Context.MODE_PRIVATE)
        store = MeshSecretStore(context)
        store.clear()
    }

    @After
    fun tearDown() {
        runCatching { store.clear() }
    }

    @Test
    fun persistsOnlyEncryptedPassphraseAndLoadsItFromANewStoreInstance() {
        val passphrase = "meshnet-test-passphrase-with-more-than-24-chars"
        store.save(passphrase)

        val ciphertext = preferences.getString("ciphertext", null)
        val iv = preferences.getString("iv", null)
        assertNotNull(ciphertext)
        assertNotNull(iv)
        assertFalse(ciphertext!!.contains(passphrase))
        assertFalse(iv!!.contains(passphrase))
        assertEquals(passphrase, MeshSecretStore(context).load())
    }

    @Test
    fun clearRemovesEncryptedCredentialAndKeystoreKey() {
        store.save("meshnet-test-passphrase-with-more-than-24-chars")
        store.clear()

        assertTrue(preferences.all.isEmpty())
        assertNull(store.load())
    }

    @Test
    fun rejectsMissingIvInsteadOfTreatingCredentialAsAbsent() {
        store.save("meshnet-test-passphrase-with-more-than-24-chars")
        assertTrue(preferences.edit().remove("iv").commit())

        var rejected = false
        try {
            store.load()
        } catch (_: IllegalStateException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun rejectsInvalidIvLength() {
        store.save("meshnet-test-passphrase-with-more-than-24-chars")
        assertTrue(preferences.edit().putString("iv", "AQID").commit())

        var rejected = false
        try {
            store.load()
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun doesNotSilentlyGenerateAReplacementKeyWhenSavedCredentialKeyIsMissing() {
        store.save("meshnet-test-passphrase-with-more-than-24-chars")
        val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keyStore.deleteEntry("meshnet-group-passphrase-v1")

        var rejected = false
        try {
            store.load()
        } catch (_: IllegalStateException) {
            rejected = true
        }
        assertTrue(rejected)
    }
}
