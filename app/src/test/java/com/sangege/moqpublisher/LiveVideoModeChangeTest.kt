package com.sangege.moqpublisher

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveVideoModeChangeTest {
    @Test
    fun lateGpuCallbackCannotApproveARejectedOrCancelledMode() {
        val change = LiveVideoModeChange()
        change.finish(false)
        change.finish(true)
        assertEquals(LiveVideoModeChange.Result.FAILED, change.result.value)
    }

    @Test
    fun anAcceptedModeCannotBeRejectedByAnObsoleteCallback() {
        val change = LiveVideoModeChange()
        change.finish(true)
        change.finish(false)
        assertEquals(LiveVideoModeChange.Result.APPLIED, change.result.value)
    }
}
