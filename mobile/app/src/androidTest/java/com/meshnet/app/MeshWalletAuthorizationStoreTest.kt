package com.meshnet.app

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class MeshWalletAuthorizationStoreTest {
    private lateinit var context: Context
    private lateinit var store: MeshWalletAuthorizationStore

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = MeshWalletAuthorizationStore(context)
        store.clear()
    }

    @After fun tearDown() {
        store.clear()
    }

    @Test fun encryptedAuthorizationSurvivesStoreRecreation() {
        val token = "test-auth-token-do-not-store-in-plaintext"
        val address = "11111111111111111111111111111111"

        store.save(token, address)

        val reloaded = MeshWalletAuthorizationStore(context).load()
        assertNotNull(reloaded)
        assertEquals(token, reloaded!!.authToken)
        assertEquals(address, reloaded.address)
    }

    @Test fun persistedPreferencesDoNotContainRawAuthorizationToken() {
        val token = "test-auth-token-never-write-plaintext"
        val address = "11111111111111111111111111111111"
        store.save(token, address)

        val preferences = context.getSharedPreferences("meshnet-wallet-auth", Context.MODE_PRIVATE)
        val storedValues = preferences.all.values.map { it.toString() }
        assertFalse(storedValues.any { it.contains(token) })
        assertFalse(storedValues.any { it.contains(address) })
    }

    @Test fun clearRemovesAuthorization() {
        store.save("test-auth-token", "11111111111111111111111111111111")
        store.clear()
        assertNull(store.load())
    }
}
