package com.sangege.moqpublisher

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Helpers for adding and displaying relay authentication parameters. */
object RelayUrl {

    private val secureSchemes = setOf("https", "wss")

    /** Adds a JWT query parameter when the relay URL does not already contain one. */
    fun withJwt(baseUrl: String, token: String): String {
        val base = baseUrl.trim()
        val cleanToken = normalizeToken(token)
        if (base.isEmpty() || cleanToken.isEmpty()) return base

        val parsed = runCatching { URI(base) }.getOrNull()
        if (parsed != null) {
            if (containsJwt(parsed.rawQuery)) return base
            val encodedToken = URLEncoder.encode(cleanToken, StandardCharsets.UTF_8.name())
            return appendToQuery(base, encodedToken)
        }

        // Keep a useful fallback for partially typed URLs in the settings screen.
        if (containsJwt(base.substringAfter('?', "").substringBefore('#'))) return base
        return appendToQuery(base, URLEncoder.encode(cleanToken, StandardCharsets.UTF_8.name()))
    }

    /** Masks the JWT value before a URL is shown in the UI or logs. */
    fun maskJwt(url: String): String {
        val queryStart = url.indexOf('?')
        val fragmentStart = url.indexOf('#').takeIf { it >= 0 } ?: url.length
        if (queryStart < 0 || queryStart >= fragmentStart) return DiagnosticRedactor.maskPathCredentials(url)
        val query = url.substring(queryStart + 1, fragmentStart).split('&').joinToString("&") { parameter ->
            if (isJwt(parameter)) "${parameter.substringBefore('=')}=****" else parameter
        }
        return DiagnosticRedactor.maskPathCredentials(url.substring(0, queryStart + 1) + query + url.substring(fragmentStart))
    }

    /** Split an inline JWT from an editable URL before writing it to preferences. */
    internal fun splitJwt(url: String): Pair<String, String?> {
        val queryStart = url.indexOf('?')
        val fragmentStart = url.indexOf('#').takeIf { it >= 0 } ?: url.length
        if (queryStart < 0 || queryStart >= fragmentStart) return url to null
        val parts = url.substring(queryStart + 1, fragmentStart).split('&')
        val jwt = parts.firstOrNull(::isJwt) ?: return url to null
        val rest = parts.filterNot(::isJwt)
        val effectiveQuery = rest.joinToString("&").let { if (it.isBlank()) "" else "?$it" }
        return (url.substring(0, queryStart) + effectiveQuery + url.substring(fragmentStart)) to
            decode(jwt.substringAfter('=', ""))
    }

    internal fun moqProSubscriptionUrl(baseUrl: String, publishToken: String, subscribeToken: String): String? {
        val base = baseUrl.trim()
        val relay = publicRelayEndpoint(base) ?: return null
        val publish = normalizeToken(splitJwt(base).second ?: publishToken)
        val subscribe = normalizeToken(subscribeToken)
        if (publish.isNotEmpty() && (subscribe.isEmpty() || publish == subscribe)) return null
        return withJwt(relay, subscribe)
    }

    internal fun publicRelayEndpoint(value: String): String? {
        val base = value.trim()
        val uri = runCatching { URI(base) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in secureSchemes || uri.host.isNullOrBlank() || uri.rawUserInfo != null) return null
        return base.substringBefore('?').substringBefore('#')
    }

    private fun normalizeToken(token: String): String {
        var value = token.trim().trim('"', '\'')
        if (value.startsWith("jwt=", ignoreCase = true)) value = value.substringAfter('=')
        if (value.startsWith("=")) value = value.substring(1)
        return value.trim().trim('"', '\'')
    }

    private fun containsJwt(query: String?): Boolean = query?.split('&')?.any(::isJwt) == true

    private fun isJwt(parameter: String): Boolean = decode(parameter.substringBefore('=')).equals("jwt", ignoreCase = true)

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrDefault(value)

    private fun appendToQuery(base: String, encodedToken: String): String {
        val fragmentIndex = base.indexOf('#')
        val withoutFragment = if (fragmentIndex >= 0) base.substring(0, fragmentIndex) else base
        val fragment = if (fragmentIndex >= 0) base.substring(fragmentIndex) else ""
        val separator = when {
            withoutFragment.endsWith('?') || withoutFragment.endsWith('&') -> ""
            withoutFragment.contains('?') -> "&"
            else -> "?"
        }
        return "$withoutFragment${separator}jwt=$encodedToken$fragment"
    }
}
