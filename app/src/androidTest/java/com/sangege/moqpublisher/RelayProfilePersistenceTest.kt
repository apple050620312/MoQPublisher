package com.sangege.moqpublisher

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RelayProfilePersistenceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun profileSwitchSurvivesRecreationAndPreservesMoqProSettings() {
        val prefs = context.getSharedPreferences("relay_profile_test", Context.MODE_PRIVATE)
        val encrypted = context.getSharedPreferences("relay_profile_test_tokens", Context.MODE_PRIVATE)
        try {
            assertTrue(prefs.edit().clear().putString("relay_url", "https://cdn.moq.pro/fixture-project")
                .putString("stream_path", "live/fixture-camera").putString("auth_token", "fixture-legacy-auth").commit())
            assertTrue(encrypted.edit().clear().commit())
            val store = KeystoreConnectionTokenStore(context, "relay_profile_test_tokens")
            val repo = RelayProfileRepository(prefs, store)
            assertEquals("fixture-legacy-auth", repo.moqProToken())
            assertFalse(prefs.contains("auth_token"))
            val initial = repo.load()
            val cloudflare = initial.copy(provider = RelayProvider.CLOUDFLARE,
                publishToken = "fixture-publish-only-for-test", subscribeToken = "fixture-subscribe-only-for-test",
                broadcastName = "nested/fixture.hang")
            repo.save(initial, cloudflare)
            val reloaded = RelayProfileRepository(prefs, KeystoreConnectionTokenStore(context, "relay_profile_test_tokens"))
            assertEquals(cloudflare, reloaded.load())
            val switched = cloudflare.copy(provider = RelayProvider.MOQ_PRO)
            reloaded.save(cloudflare, switched)
            assertEquals(switched, repo.load())
            assertEquals("https://cdn.moq.pro/fixture-project", prefs.getString("relay_url", null))
            assertEquals("live/fixture-camera", prefs.getString("stream_path", null))
            assertEquals("fixture-legacy-auth", reloaded.moqProToken())
            assertFalse(prefs.all.toString().contains("fixture-publish-only-for-test"))
            assertFalse(encrypted.all.toString().contains("fixture-publish-only-for-test"))
            assertFalse(encrypted.all.toString().contains("fixture-subscribe-only-for-test"))
        } finally { prefs.edit().clear().commit(); encrypted.edit().clear().commit() }
    }

    @Test fun corruptedOrSwappedCiphertextFailsClosedInsteadOfReturningPlaintext() {
        val name = "relay_profile_tamper_test"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        try {
            val store = KeystoreConnectionTokenStore(context, name)
            store.write("cloudflare_publish", "fixture-opaque-connection-test")
            val ciphertext = prefs.getString("cloudflare_publish", null)
            assertTrue(prefs.edit().putString("cloudflare_subscribe", ciphertext).commit())
            assertThrows(Exception::class.java) { store.read("cloudflare_subscribe") }
            assertTrue(prefs.edit().putString("cloudflare_publish", "corrupted").commit())
            assertThrows(Exception::class.java) { store.read("cloudflare_publish") }
        } finally { prefs.edit().clear().commit() }
    }
}
