package com.sangege.moqpublisher

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PersistableBundle
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.security.auth.x500.X500Principal

enum class RemoteAccess(val label: Int) {
    MONITOR(R.string.remote_monitor),
    CONTROL(R.string.remote_control)
}

data class RemoteControlState(
    val access: RemoteAccess? = null,
    val preparing: Boolean = false,
    val address: String? = null,
    val problem: UiMessage? = null
)

internal enum class RemoteCommand { STATUS, MUTE, UNMUTE, BITRATE, VERIFY, STOP }

internal class RemoteResponse(val payload: JSONObject, val afterSend: suspend () -> Unit = {})

/** Optional LAN listener. Keys and credentials live for one grant. */
internal class RemoteControlServer(private val context: Context, private val diagnostics: AppDiagnostics) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connections = context.getSystemService(ConnectivityManager::class.java)
    private val lock = Any()
    private val random = SecureRandom()
    private val mutableState = MutableStateFlow(RemoteControlState())
    val state = mutableState.asStateFlow()
    private var grant: Grant? = null

    private class Grant(val access: RemoteAccess) {
        var listener: SSLServerSocket? = null
        var peer: SSLSocket? = null
        var pairingCode: String? = null
    }

    fun start(access: RemoteAccess, execute: suspend (RemoteCommand, Int?, () -> Boolean) -> RemoteResponse) {
        val current = synchronized(lock) {
            if (grant != null) return
            Grant(access).also {
                grant = it
                mutableState.value = RemoteControlState(access = access, preparing = true)
            }
        }
        scope.launch { serve(current, execute) }
    }

    fun revoke() {
        val previous = synchronized(lock) {
            grant.also {
                grant = null
                mutableState.value = RemoteControlState()
            }
        }
        closeSockets(previous)
    }

    fun copyPairingCode(): Boolean {
        val code = synchronized(lock) { grant?.pairingCode } ?: return false
        val clip = ClipData.newPlainText(context.getString(R.string.remote_pairing), code)
        if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        return true
    }

    private fun isCurrent(current: Grant): Boolean = synchronized(lock) { grant === current }

    private suspend fun serve(current: Grant, execute: suspend (RemoteCommand, Int?, () -> Boolean) -> RemoteResponse) {
        val alias = "moq_remote_${UUID.randomUUID()}"
        var failed: UiMessage? = null
        try {
            val address = lanAddress() ?: throw UiException(UiMessage(R.string.remote_wifi_required))
            val key = createIdentity(alias)
            val token = Base64.encodeToString(ByteArray(32).also(random::nextBytes), BASE64_FLAGS)
            val tls = SSLContext.getInstance("TLS").apply { init(arrayOf(key), null, random) }
            (tls.serverSocketFactory.createServerSocket() as SSLServerSocket).use { server ->
                server.enabledProtocols = server.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
                server.soTimeout = 2_000
                server.bind(InetSocketAddress(address, 0), 2)
                val host = checkNotNull(address.hostAddress)
                val code = JSONObject().put("host", host).put("port", server.localPort)
                    .put("sha256", key.fingerprint).put("token", token)
                synchronized(lock) {
                    if (grant !== current) return
                    current.listener = server
                    current.pairingCode = "moq1." + Base64.encodeToString(code.toString().toByteArray(), BASE64_FLAGS)
                    mutableState.value = RemoteControlState(current.access, address = "$host:${server.localPort}")
                }
                diagnostics.event("remote_control_enabled", mapOf("access" to current.access))
                while (isCurrent(current)) {
                    val peer = try {
                        server.accept() as SSLSocket
                    } catch (_: SocketTimeoutException) {
                        if (lanAddress() != address) throw UiException(UiMessage(R.string.remote_network_changed))
                        continue
                    }
                    peer.use {
                        synchronized(lock) {
                            if (grant !== current) return
                            current.peer = peer
                        }
                        try {
                            receive(current, peer, token, execute)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            // Invalid requests and disconnects are isolated from the broadcast
                            // and do not write unauthenticated input to diagnostics.
                        } finally {
                            synchronized(lock) { if (current.peer === peer) current.peer = null }
                        }
                    }
                }
            }
        } catch (error: Exception) {
            if (isCurrent(current)) {
                diagnostics.error("remote_control_failed", error)
                failed = (error as? UiException)?.uiMessage ?: UiMessage(R.string.remote_failed)
            }
        } finally {
            closeSockets(current)
            runCatching { keyStore().deleteEntry(alias) }
            synchronized(lock) {
                if (grant === current) {
                    grant = null
                    mutableState.value = RemoteControlState(problem = failed)
                }
            }
        }
    }

    private suspend fun receive(
        current: Grant, peer: SSLSocket, token: String,
        execute: suspend (RemoteCommand, Int?, () -> Boolean) -> RemoteResponse
    ) {
        if ((peer.inetAddress as? Inet4Address)?.isSiteLocalAddress != true) return
        peer.soTimeout = 3_000
        peer.startHandshake()
        val request = JSONObject(readRequest(peer))
        val receivedToken = request.opt("token") as? String ?: ""
        val authenticated = MessageDigest.isEqual(token.toByteArray(), receivedToken.toByteArray())
        val command = RemoteCommand.entries.firstOrNull { it.name.equals(request.optString("command"), ignoreCase = true) }
        val kbps = request.opt("kbps") as? Int
        val response = when {
            !authenticated -> remoteReply("unauthorized")
            command == null -> remoteReply("unknown_command")
            current.access == RemoteAccess.MONITOR && command != RemoteCommand.STATUS -> remoteReply("read_only")
            command == RemoteCommand.BITRATE && (kbps == null || kbps !in 100..50_000) -> remoteReply("invalid_bitrate")
            !isCurrent(current) -> remoteReply("revoked")
            else -> execute(command, kbps) { isCurrent(current) }
        }
        peer.getOutputStream().apply {
            write((response.payload.toString() + "\n").toByteArray())
            flush()
        }
        response.afterSend()
    }

    private fun readRequest(peer: SSLSocket): String {
        val input = peer.getInputStream()
        val deadline = SystemClock.elapsedRealtime() + 3_000
        val line = ByteArrayOutputStream(128)
        while (line.size() < 1_024) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) throw SocketTimeoutException()
            peer.soTimeout = remaining.toInt()
            val value = input.read()
            if (value == '\n'.code) return line.toString(Charsets.UTF_8.name())
            check(value >= 0)
            line.write(value)
        }
        error("Remote request too long")
    }

    @Suppress("DEPRECATION")
    private fun lanAddress(): Inet4Address? = connections.allNetworks.firstNotNullOfOrNull { network ->
        val capabilities = connections.getNetworkCapabilities(network) ?: return@firstNotNullOfOrNull null
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return@firstNotNullOfOrNull null
        connections.getLinkProperties(network)?.linkAddresses?.mapNotNull { it.address as? Inet4Address }
            ?.firstOrNull { it.isSiteLocalAddress }
    }

    private fun closeSockets(current: Grant?) {
        runCatching { current?.listener?.close() }
        runCatching { current?.peer?.close() }
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun createIdentity(alias: String): RemoteTlsKey {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        generator.initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
            .setCertificateSubject(X500Principal("CN=MoQ Publisher"))
            .build())
        val pair = generator.generateKeyPair()
        return RemoteTlsKey(pair.private, keyStore().getCertificate(alias) as X509Certificate)
    }

    private class RemoteTlsKey(private val key: PrivateKey, private val certificate: X509Certificate) : X509ExtendedKeyManager() {
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = null
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = null
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
            if (keyType?.substringBefore('_') == "EC") arrayOf("publisher") else null
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) =
            getServerAliases(keyType, issuers)?.firstOrNull()
        override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
            if (alias == "publisher") arrayOf(certificate) else null
        override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == "publisher") key else null
    }

    private companion object {
        const val BASE64_FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    }
}

internal fun remoteReply(error: String? = null): RemoteResponse = RemoteResponse(
    JSONObject().put("ok", error == null).apply { if (error != null) put("error", error) }
)
