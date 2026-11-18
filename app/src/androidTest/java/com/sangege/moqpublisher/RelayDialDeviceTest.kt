package com.sangege.moqpublisher

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RelayDialDeviceTest {
    @Test
    fun announcesAfterABoundedTransientDialFailure() = runBlocking {
        val endpoint = InstrumentationRegistry.getArguments().getString("moqTestRelayUrl")
        assumeTrue("Run this test with the controlled relay fixture URL", endpoint != null)
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MoqApplication
        withTimeout(15_000L) { app.core.state.first { it.ready } }

        val streamer = MoqStreamer(app, AppDiagnostics(app))
        try {
            streamer.startBroadcast(
                relayUrl = checkNotNull(endpoint),
                streamPath = "",
                tlsVerify = true,
                enableAudio = false,
                videoBitrate = VideoBitrateConfiguration(1_500_000L, 2_000_000L, 4_000_000L, false),
                provider = RelayProvider.CLOUDFLARE,
                protocolDraft = 18,
                screenOnly = true,
            )
            withTimeout(90_000L) {
                streamer.lifecycleState.first { it.phase == BroadcastPhase.RECONNECTING }
                val result = streamer.lifecycleState.first {
                    it.phase == BroadcastPhase.WAITING_FOR_SUBSCRIBER || it.phase == BroadcastPhase.FAILED
                }
                assertEquals(BroadcastPhase.WAITING_FOR_SUBSCRIBER, result.phase)
            }
        } finally {
            streamer.stopBroadcast()
            try {
                withTimeout(10_000L) { streamer.streamState.first { it is StreamState.Idle || it is StreamState.Error } }
            } finally {
                streamer.close()
            }
        }
    }
}
