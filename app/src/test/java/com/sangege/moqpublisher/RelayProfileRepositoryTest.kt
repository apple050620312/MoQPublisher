package com.sangege.moqpublisher

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class RelayProfileRepositoryTest {
    private class MemoryPreferences {
        val values = mutableMapOf<String, Any?>()
        private val changes = mutableMapOf<String, Any?>()
        private val editor: SharedPreferences.Editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)
        ) { proxy, method, args ->
            when {
                method.name.startsWith("put") -> { changes[args[0] as String] = args[1]; proxy }
                method.name == "remove" -> { changes[args[0] as String] = null; proxy }
                method.name == "commit" -> {
                    for ((key, value) in changes) if (value == null) values.remove(key) else values[key] = value
                    changes.clear(); true
                }
                else -> error("Unexpected preference editor call: ${method.name}")
            }
        } as SharedPreferences.Editor
        val prefs: SharedPreferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getString", "getInt" -> values[args[0] as String] ?: args[1]
                "contains" -> values.containsKey(args[0])
                "edit" -> editor
                else -> error("Unexpected preference call: ${method.name}")
            }
        } as SharedPreferences
    }

    private class FakeCredentials : ConnectionTokenStore {
        val values = mutableMapOf<String, String>()
        var failWrites = false
        override fun read(name: String) = values[name].orEmpty()
        override fun write(name: String, value: String) {
            check(!failWrites) { "Simulated keystore failure" }
            values[name] = value
        }
    }

    @Test fun persistsProfileAndSeparateCredentialSlotsWithoutWritingTokensToPreferences() {
        val prefs = MemoryPreferences()
        val secrets = FakeCredentials()
        val first = RelayProfileRepository(prefs.prefs, secrets)
        val initial = first.load()
        val configured = initial.copy(provider = RelayProvider.CLOUDFLARE,
            publishToken = "fixture-publisher", subscribeToken = "fixture-viewer", broadcastName = "live/camera.hang")
        first.save(initial, configured)
        assertEquals(configured, RelayProfileRepository(prefs.prefs, secrets).load())
        assertEquals(18, prefs.values["cloudflare_draft"])
        assertEquals(configured.publishToken, secrets.values["cloudflare_publish"])
        assertEquals(configured.subscribeToken, secrets.values["cloudflare_subscribe"])
        assertFalse(prefs.values.values.contains(configured.publishToken))
        assertFalse(prefs.values.values.contains(configured.subscribeToken))
    }

    @Test fun switchingProvidersDoesNotOverwriteEitherProvidersDestinationOrCredentials() {
        val prefs = MemoryPreferences()
        prefs.values["relay_url"] = "https://cdn.moq.pro/project"
        prefs.values["stream_path"] = "live/camera"
        val secrets = FakeCredentials()
        val repo = RelayProfileRepository(prefs.prefs, secrets)
        repo.saveMoqProToken("fixture-legacy-jwt")
        val cloudflare = repo.load().copy(provider = RelayProvider.CLOUDFLARE,
            publishToken = "fixture-publisher", subscribeToken = "fixture-viewer")
        repo.save(repo.load(), cloudflare)
        repo.save(cloudflare, cloudflare.copy(provider = RelayProvider.MOQ_PRO))
        assertEquals(RelayProvider.MOQ_PRO, repo.load().provider)
        assertEquals(cloudflare.publishToken, repo.load().publishToken)
        assertEquals(cloudflare.subscribeToken, repo.load().subscribeToken)
        assertEquals("fixture-legacy-jwt", repo.moqProToken())
        assertEquals("https://cdn.moq.pro/project", prefs.values["relay_url"])
        assertEquals("live/camera", prefs.values["stream_path"])
    }

    @Test fun legacyMoqProAuthenticationMigratesOnlyAfterSecureWriteSucceeds() {
        val prefs = MemoryPreferences()
        prefs.values["auth_token"] = "fixture-old-credential"
        val secrets = FakeCredentials().apply { failWrites = true }
        val repo = RelayProfileRepository(prefs.prefs, secrets)
        assertThrows(IllegalStateException::class.java) { repo.moqProToken() }
        assertEquals("fixture-old-credential", prefs.values["auth_token"])
        secrets.failWrites = false
        assertEquals("fixture-old-credential", repo.moqProToken())
        assertFalse(prefs.values.containsKey("auth_token"))
        assertEquals("fixture-old-credential", repo.moqProToken())
    }

    @Test fun secureStorageFailureCannotSilentlyPersistTheNewProviderOrPlaintextToken() {
        val prefs = MemoryPreferences()
        val secrets = FakeCredentials().apply { failWrites = true }
        val repo = RelayProfileRepository(prefs.prefs, secrets)
        val initial = repo.load()
        assertThrows(IllegalStateException::class.java) { repo.save(initial,
            initial.copy(provider = RelayProvider.CLOUDFLARE, publishToken = "fixture-publisher")) }
        assertTrue(prefs.values.isEmpty())
        assertTrue(secrets.values.isEmpty())
    }

    @Test fun replacingACredentialAfterFailedMigrationDoesNotRestoreTheOldPlaintextValue() {
        val prefs = MemoryPreferences()
        prefs.values["auth_token"] = "fixture-previous-auth"
        val secrets = FakeCredentials().apply { failWrites = true }
        val repo = RelayProfileRepository(prefs.prefs, secrets)
        assertThrows(IllegalStateException::class.java) { repo.moqProToken() }
        secrets.failWrites = false
        repo.saveMoqProToken("fixture-replacement-auth")
        assertFalse(prefs.values.containsKey("auth_token"))
        assertEquals("fixture-replacement-auth", RelayProfileRepository(prefs.prefs, secrets).moqProToken())
    }
}
