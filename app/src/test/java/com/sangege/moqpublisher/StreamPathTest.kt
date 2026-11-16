package com.sangege.moqpublisher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamPathTest {
    @Test fun acceptsNestedBroadcastNames() {
        assertTrue(StreamPath.isValid("live/camera"))
        assertTrue(StreamPath.isValid("studio/main/2026"))
    }

    @Test fun rejectsNamespaceInjectionAndAmbiguousPaths() {
        listOf("", "/live", "live/", "live//camera", "live/../camera", "live?jwt=secret", "live\\camera", "live%2Fcamera", "live camera").forEach {
            assertFalse(it, StreamPath.isValid(it))
        }
    }
}
