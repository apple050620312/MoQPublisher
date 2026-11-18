package com.sangege.moqpublisher

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal interface ConnectionTokenStore {
    fun read(name: String): String
    fun write(name: String, value: String)
}

/** AES-GCM ciphertext only; the non-exportable key stays in Android Keystore. */
@SuppressLint("UseKtx") // Check commit()'s Boolean; edit { } discards persistence failure.
internal class KeystoreConnectionTokenStore(context: Context, namespace: String = "moq_connection_tokens") : ConnectionTokenStore {
    private val prefs = context.getSharedPreferences(namespace, Context.MODE_PRIVATE)
    private val alias = "${context.packageName}.$namespace.v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        ).apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    @Synchronized override fun read(name: String): String {
        val stored = prefs.getString(name, null) ?: return ""
        val parts = stored.split(':')
        check(parts.size == 3 && parts[0] == "v1") { "Invalid credential storage format" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[1], Base64.NO_WRAP)))
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(Base64.decode(parts[2], Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }

    @Synchronized override fun write(name: String, value: String) {
        val editor = prefs.edit()
        if (value.isEmpty()) editor.remove(name)
        else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
            val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            editor.putString(name, "v1:${Base64.encodeToString(cipher.iv, Base64.NO_WRAP)}:" +
                Base64.encodeToString(encrypted, Base64.NO_WRAP))
        }
        check(editor.commit()) { "Could not persist encrypted credentials" }
    }
}

@SuppressLint("UseKtx") // Secure migration must not remove the old credential on a failed commit.
internal class RelayProfileRepository(private val prefs: SharedPreferences, private val tokens: ConnectionTokenStore) {
    fun load(): RelayProfile = RelayProfile(
        provider = RelayProvider.fromPreference(prefs.getString("relay_provider", null)),
        // Draft 18 is the requested Cloudflare profile, including upgrades from old preferences.
        protocolDraft = CloudflareRelay.DRAFT,
        publishToken = tokens.read("cloudflare_publish"), subscribeToken = tokens.read("cloudflare_subscribe"),
        broadcastName = prefs.getString("cloudflare_broadcast", "twcam.hang") ?: "twcam.hang"
    )

    fun save(previous: RelayProfile, next: RelayProfile) {
        if (previous.publishToken != next.publishToken) tokens.write("cloudflare_publish", next.publishToken)
        if (previous.subscribeToken != next.subscribeToken) tokens.write("cloudflare_subscribe", next.subscribeToken)
        check(prefs.edit().putString("relay_provider", next.provider.name)
            .putInt("cloudflare_draft", CloudflareRelay.DRAFT).putString("cloudflare_broadcast", next.broadcastName).commit())
    }

    fun moqProToken(): String {
        val legacy = prefs.getString("auth_token", null)
        if (legacy != null) {
            tokens.write("moq_pro_auth", legacy)
            check(prefs.edit().remove("auth_token").commit())
            return legacy
        }
        return tokens.read("moq_pro_auth")
    }

    fun saveMoqProToken(value: String) {
        tokens.write("moq_pro_auth", value)
        // A recovered keystore must not let a pending legacy migration overwrite new input.
        check(prefs.edit().remove("auth_token").commit())
    }

    fun moqProSubscribeToken(): String = tokens.read("moq_pro_subscribe")

    fun saveMoqProSubscribeToken(value: String) = tokens.write("moq_pro_subscribe", value)
}
