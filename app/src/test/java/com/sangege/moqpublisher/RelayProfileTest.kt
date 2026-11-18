package com.sangege.moqpublisher

import org.junit.Assert.*
import org.junit.Test
import java.net.URI

class RelayProfileTest {
    // Deliberately synthetic non-credential fixtures. Never put real tokens in tests.
    private fun profile() = RelayProfile(RelayProvider.CLOUDFLARE,
        publishToken = "fixture-publisher-" + "p".repeat(24), subscribeToken = "fixture-viewer-" + "s".repeat(24))

    @Test fun existingUsersDefaultToMoqProAndCloudflareUsesDraft18() {
        assertEquals(RelayProvider.MOQ_PRO, RelayProvider.fromPreference(null))
        assertEquals(RelayProvider.MOQ_PRO, RelayProvider.fromPreference("unknown"))
        assertEquals(18, RelayProfile().protocolDraft)
        assertEquals("twcam.hang", RelayProfile().broadcastName)
    }

    @Test fun connectionCredentialIsExactlyOnePathSegmentAndNeverAQuery() {
        val p = profile().copy(publishToken = "  fixture+plus=padding  ")
        val uri = URI(p.publishUrl())
        assertEquals("https", uri.scheme)
        assertEquals("draft-18.cloudflare.mediaoverquic.com", uri.host)
        assertEquals("/fixture+plus=padding", uri.path)
        assertNull(uri.rawQuery)
        assertNull(uri.rawFragment)
        assertFalse(uri.toString().contains("jwt"))
    }

    @Test fun viewingAndObsUseOnlyTheDistinctSubscriptionCredentialAndFullNamespace() {
        val p = profile().copy(broadcastName = "live/台灣相機.hang")
        val watch = URI(p.watchUrl())
        assertEquals("/${p.subscribeToken}/live/台灣相機.hang", watch.path)
        assertEquals("cloudflare=draft-18", watch.query)
        assertTrue(p.obsSubscription().contains(p.subscribeUrl()))
        assertTrue(p.obsSubscription().endsWith(p.broadcastName))
        for (shared in listOf(p.watchUrl(), p.obsSubscription(), p.toString(), RelayUrl.maskJwt(p.publishUrl())))
            assertFalse(shared, shared.contains(p.publishToken))
        assertFalse(p.toString().contains(p.subscribeToken))
    }

    @Test fun missingOrReusedSubscribeCredentialNeverFallsBackToPublisher() {
        for (p in listOf(profile().copy(subscribeToken = ""), profile().let { it.copy(subscribeToken = it.publishToken) })) {
            assertFalse(p.canShare())
            assertThrows(IllegalArgumentException::class.java) { p.watchUrl() }
            assertThrows(IllegalArgumentException::class.java) { p.obsSubscription() }
        }
    }

    @Test fun invalidCredentialInputCannotInjectAProjectQueryOrDifferentHost() {
        for (bad in listOf("", "Bearer fixture", "https://other.example/fixture", "fixture/extra", "fixture?jwt=x", "fixture#x", "fixture\nheader"))
            assertThrows(IllegalArgumentException::class.java) { profile().copy(publishToken = bad).publishUrl() }
        assertThrows(IllegalArgumentException::class.java) { profile().copy(protocolDraft = 16).publishUrl() }
    }

    @Test fun cloudflarePublicationAcceptsAnySafeNamespaceAndPreservesNestedNamespace() {
        assertTrue(CloudflareRelay.validBroadcast("camera"))
        assertTrue(CloudflareRelay.validBroadcast("space/camera"))
        assertTrue(CloudflareRelay.validBroadcast("space/camera.hang"))
        assertFalse(CloudflareRelay.validHangBroadcast("camera"))
        assertTrue(CloudflareRelay.validHangBroadcast("space/camera.hang"))
        assertTrue(CloudflareRelay.validPublicationName(""))
        assertTrue(CloudflareRelay.validPublicationName(" space/camera "))
        for (bad in listOf("", "/camera.hang", "../camera.hang", "a//camera.hang", "camera.hang?x=1", "a%2Fcamera.hang", "a\\camera.hang"))
            assertFalse(bad, CloudflareRelay.validBroadcast(bad))
    }

    @Test fun unnamedCloudflarePublicationKeepsAnEmptyPathForThePublisher() {
        val cloudflare = profile().copy(broadcastName = "  ")
        assertEquals("", cloudflare.publicationPath("legacy/camera"))

        val moqPro = RelayProfile(provider = RelayProvider.MOQ_PRO)
        assertEquals("legacy/camera", moqPro.publicationPath(" legacy/camera "))
    }

    @Test fun nonHangPublicationDoesNotEnableHangViewingLinks() {
        val p = profile().copy(broadcastName = "space/camera")
        assertFalse(p.canShare())
        assertThrows(IllegalArgumentException::class.java) { p.watchUrl() }
        assertThrows(IllegalArgumentException::class.java) { p.obsSubscription() }
    }

    @Test fun legacyMoqProJwtBehaviorIsUnchanged() {
        assertEquals("https://cdn.moq.pro/project?jwt=fixture", RelayUrl.withJwt("https://cdn.moq.pro/project", "fixture"))
        assertEquals("https://cdn.moq.pro/project?jwt=existing", RelayUrl.withJwt("https://cdn.moq.pro/project?jwt=existing", "fixture"))
        assertEquals("https://cdn.moq.pro/project?jwt=****", RelayUrl.maskJwt("https://cdn.moq.pro/project?jwt=fixture"))
    }
}
