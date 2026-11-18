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
    /** Returns the exact publication path. An empty Cloudflare name stays empty for createBroadcast(""). */
    fun publicationPath(moqProPath: String): String = when (provider) {
        RelayProvider.CLOUDFLARE -> broadcastName.trim()
        RelayProvider.MOQ_PRO -> moqProPath.trim()
    }

    fun publishUrl(): String = CloudflareRelay.url(protocolDraft, publishToken)
    fun subscribeUrl(): String = CloudflareRelay.url(protocolDraft, subscribeToken)

    fun canSubscribe(): Boolean = provider == RelayProvider.CLOUDFLARE && protocolDraft == CloudflareRelay.DRAFT &&
        CloudflareRelay.validToken(subscribeToken) && subscribeToken.trim() != publishToken.trim() &&
        CloudflareRelay.validPublicationName(broadcastName)

    fun canShare(): Boolean = canSubscribe() && CloudflareRelay.validHangBroadcast(broadcastName)

    /** Never fall back to a publish credential, even when no subscription token was supplied. */
    fun watchUrl(): String {
        require(canShare()) { "A separate subscribe-only token and Hang name are required" }
        return "https://moq.watch/${CloudflareRelay.segment(subscribeToken.trim())}/" +
            "${CloudflareRelay.broadcastPath(broadcastName)}?cloudflare=draft-$protocolDraft"
    }

    fun obsSubscription(): String {
        require(canSubscribe()) { "A separate subscribe-only token is required" }
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

    /** Cloudflare accepts any safe namespace path for publication. */
    fun validBroadcast(value: String): Boolean {
        val path = value.trim()
        return path.split('/').all {
            it.isNotEmpty() && it != "." && it != ".." && it.none { c -> c.isWhitespace() || c.isISOControl() || c in "?#\\%" }
        }
    }

    /** The Hang viewers and the in-app remote decoder use the Hang suffix. */
    fun validHangBroadcast(value: String): Boolean = validBroadcast(value) && value.trim().endsWith(".hang")

    /** Cloudflare's publish and subscribe examples allow an unnamed broadcast. */
    fun validPublicationName(value: String): Boolean = value.trim().isEmpty() || validBroadcast(value)

    fun url(draft: Int, token: String): String {
        require(draft == DRAFT) { "Only Cloudflare Draft 18 is enabled" }
        require(validToken(token)) { "Invalid connection token" }
        return "https://draft-$draft.cloudflare.mediaoverquic.com/${segment(token.trim())}"
    }

    fun segment(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    fun broadcastPath(value: String): String = value.trim().split('/').joinToString("/", transform = ::segment)
}
