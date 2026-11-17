package com.sangege.moqpublisher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdatePolicyTest {
    @Test
    fun releaseTagsAndVersionsAreComparedNumerically() {
        assertEquals("1.0.16", AppUpdatePolicy.parseVersionTag("v1.0.16"))
        assertEquals("1.0.16", AppUpdatePolicy.parseVersionTag("1.0.16"))
        assertNull(AppUpdatePolicy.parseVersionTag("latest"))
        assertTrue(AppUpdatePolicy.compare("1.0.16", "1.0.15") > 0)
        assertEquals(0, AppUpdatePolicy.compare("1.12.0", "1.12.0"))
        assertTrue(AppUpdatePolicy.compare("2.0.0", "10.0.0") < 0)
    }

    @Test
    fun checksumAndAssetNamesAreStrict() {
        assertEquals("MoQPublisher-1.0.18-release.apk", AppUpdatePolicy.apkName("1.0.18"))
        assertEquals("a".repeat(64), AppUpdatePolicy.normalizeDigest("sha256:${"A".repeat(64)}"))
        assertEquals("b".repeat(64), AppUpdatePolicy.checksumFromSidecar("${"b".repeat(64)}  app.apk\n"))
        assertNull(AppUpdatePolicy.normalizeDigest("not-a-digest"))
        assertFalse(AppUpdatePolicy.validVersion("v1.0.16"))
    }
}
