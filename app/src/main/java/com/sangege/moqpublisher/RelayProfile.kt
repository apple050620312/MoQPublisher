package com.sangege.moqpublisher

import java.net.URLEncoder

enum class RelayProvider {
    MOQ_PRO, CLOUDFLARE;

    companion object {
        fun fromPreference(value: String?): RelayProvider = entries.firstOrNull { it.name == value } ?: MOQ_PRO
    }
}

/** Provider preferences are independent of the existing moq.pro URL, JWT and broadcast path. */
data class RelayProfile(
    val provider: RelayProvider = RelayProvider.MOQ_PRO,
    val protocolDraft: Int = 18,
    val publishToken: String = "",
    val subscribeToken: String = "",
    val broadcastName: String = "twcam.hang"
) {
    fun publishUrl(): String = CloudflareRelay.url(protocolDraft, publishToken)
    fun subscribeUrl(): String = CloudflareRelay.url(protocolDraft, subscribeToken)

    fun canShare(): Boolean = CloudflareRelay.validToken(subscribeToken) &&
        subscribeToken.trim() != publishToken.trim() && CloudflareRelay.validBroadcast(broadcastName)

    /** Never fall back to a publish credential, even when no subscription token was supplied. */
    fun watchUrl(): String {
        require(provider == RelayProvider.CLOUDFLARE && canShare()) { "A separate subscribe-only token is required" }
        return "https://moq.watch/${CloudflareRelay.segment(subscribeToken.trim())}/" +
            "${CloudflareRelay.broadcastPath(broadcastName)}?cloudflare=draft-$protocolDraft"
    }

    fun obsSubscription(): String {
        require(provider == RelayProvider.CLOUDFLARE && canShare()) { "A separate subscribe-only token is required" }
        return "Relay:\n${subscribeUrl()}\n\nBroadcast:\n${broadcastName.trim()}"
    }

    override fun toString(): String = "RelayProfile(provider=$provider, protocolDraft=$protocolDraft, " +
        "publishConfigured=${publishToken.isNotBlank()}, subscribeConfigured=${subscribeToken.isNotBlank()}, " +
        "broadcastName=$broadcastName)"
}

internal object CloudflareRelay {
    const val DRAFT = 18
    private val tokenCharacters = Regex("[A-Za-z0-9._~+=-]+")

    // Connection tokens are opaque. No Management API credential is requested or embedded.
    fun validToken(value: String): Boolean = value.trim().let { it.isNotEmpty() && tokenCharacters.matches(it) }

    fun validBroadcast(value: String): Boolean {
        val path = value.trim()
        return path.endsWith(".hang") && path.split('/').all {
            it.isNotEmpty() && it != "." && it != ".." && it.none { c -> c.isWhitespace() || c.isISOControl() || c in "?#\\%" }
        }
    }

    fun url(draft: Int, token: String): String {
        require(draft == DRAFT) { "Only Cloudflare Draft 18 is enabled" }
        require(validToken(token)) { "Invalid connection token" }
        return "https://draft-$draft.cloudflare.mediaoverquic.com/${segment(token.trim())}"
    }

    fun segment(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    fun broadcastPath(value: String): String = value.trim().split('/').joinToString("/", transform = ::segment)
}
