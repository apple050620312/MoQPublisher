package com.sangege.moqpublisher

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Helpers for adding and displaying relay authentication parameters. */
object RelayUrl {

    private val jwtQueryParameter = Regex("(?i)(?:^|&)jwt(?:=|$)")
    private val jwtForDisplay = Regex("(?i)([?&]jwt=)[^&#]*")

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
    fun maskJwt(url: String): String = DiagnosticRedactor.maskPathCredentials(
        jwtForDisplay.replace(url) { "${it.groupValues[1]}****" }
    )

    private fun normalizeToken(token: String): String {
        var value = token.trim().trim('"', '\'')
        if (value.startsWith("jwt=", ignoreCase = true)) value = value.substringAfter('=')
        if (value.startsWith("=")) value = value.substring(1)
        return value.trim().trim('"', '\'')
    }

    private fun containsJwt(query: String?): Boolean {
        if (query.isNullOrEmpty()) return false
        return query.split('&').any { part ->
            val name = part.substringBefore('=').let {
                runCatching { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }.getOrDefault(it)
            }
            name.equals("jwt", ignoreCase = true) || jwtQueryParameter.matches(part)
        }
    }

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
