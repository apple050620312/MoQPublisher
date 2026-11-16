package com.sangege.moqpublisher

import org.junit.Assert.*
import org.junit.Test

class DiagnosticRedactorTest {
    @Test fun stripsRegisteredOpaqueCredentialsEvenWhenNativeErrorsOmitTheUrl() {
        val opaque = "fixture-opaque-in-native-debug-message"
        DiagnosticRedactor.protectSecrets(opaque)
        assertFalse(DiagnosticRedactor.redact("remote rejected credential <$opaque>").contains(opaque))
    }
    @Test fun removesOpaqueCloudflareAndViewingPathTokensAndNamedCredentials() {
        val input = "https://draft-18.cloudflare.mediaoverquic.com/fixture-opaque-connection " +
            "https://draft-16.cloudflare.mediaoverquic.com/fixture%2Bencoded " +
            "https://moq.watch/fixture-viewer/live/camera.hang?cloudflare=draft-18 " +
            "publishToken=fixture-publish subscribe_token=fixture-subscribe"
        val result = DiagnosticRedactor.redact(input)
        assertFalse(result, result.contains("fixture"))
        assertTrue(result.contains("draft-18.cloudflare.mediaoverquic.com/[redacted]"))
        assertEquals(result, DiagnosticRedactor.redact(result))
    }
    @Test
    fun removesAllUrlQueryValuesIncludingEncodedOrUnknownCredentialNames() {
        for (scheme in listOf("wss", "https", "quic")) {
            val result = DiagnosticRedactor.redact("connect $scheme://user:password@relay.example/live?%6a%77%74=secret1&custom=secret2#secret3")
            assertEquals("connect $scheme://[redacted]@relay.example/live?[redacted]#[redacted]", result)
        }
    }

    @Test
    fun removesHeaderJsonAndStandaloneJwtCredentialsFromNativeErrors() {
        val input = "Authorization: Bearer headerSecret\n{\"jwt\":\"jsonSecret\",\"password\":\"passwordSecret\"}\n" +
            "received eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.signature123"
        val result = DiagnosticRedactor.redact(input)
        for (secret in listOf("headerSecret", "jsonSecret", "passwordSecret", "eyJhbGci", "signature123")) {
            assertFalse(result, result.contains(secret))
        }
    }

    @Test
    fun preservesDiagnosticMeasurementsAndRedactionIsIdempotent() {
        val input = "codec=H265 fps=30 rtt_ms=125 video_budget_bps=2500000 " +
            "relay=https://relay.example/live?jwt=secret auth_configured=true"
        val result = DiagnosticRedactor.redact(input)
        assertTrue(result.startsWith("codec=H265 fps=30 rtt_ms=125 video_budget_bps=2500000"))
        assertTrue(result.contains("auth_configured=true"))
        assertEquals(result, DiagnosticRedactor.redact(result))
    }
}
