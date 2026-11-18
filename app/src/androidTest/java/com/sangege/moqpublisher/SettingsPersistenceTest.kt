package com.sangege.moqpublisher

import android.app.Application
import android.content.Context
import android.util.Size
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsPersistenceTest {
    @Test
    fun legacyBitrateMigratesOnceAndNewChoicesSurviveViewModelRecreation() {
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val prefs = application.getSharedPreferences("settings_persistence_test", Context.MODE_PRIVATE)
        val store = ViewModelStore()
        fun create(key: String): MainViewModel {
            lateinit var result: MainViewModel
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                result = MainViewModel(application, prefs).also { store.put(key, it) }
            }
            return result
        }
        try {
            assertTrue(prefs.edit().clear().putString("video_bitrate_mbps", "8").commit())
            val first = create("first")
            assertEquals("8000", first.videoBitrate.value.targetKbps)
            assertEquals(AppLanguage.TRADITIONAL_CHINESE, first.language.value)
            assertEquals(AppTheme.DARK, first.theme.value)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                first.applyVideoBitrate(VideoBitrateInput("750", "12345.678", "14000", adaptive = false))
                first.applyVideoBitrate(VideoBitrateInput("16000", "9000", "12000", adaptive = true))
            }
            first.setAudioChannel(AudioChannel.STEREO)
            first.setAudioGain(230)
            first.setAudioQuality(AudioQuality.HIGH)
            first.setLockLandscape(true)
            first.setBackgroundStreaming(false)
            first.setLanguage(AppLanguage.ENGLISH)
            first.setTheme(AppTheme.LIGHT)
            first.setStreamAlertMode(StreamAlertMode.VIBRATION)
            first.setConfirmStop(false)
            first.setEnableAudio(false)
            first.setTlsVerify(true)
            first.setVideoCodec("H265")
            first.setVideoResolution(Size(432, 240))
            first.setVideoFps(60)
            // Flush pending apply() operations to disk, as happens when Android stops the activity.
            assertTrue(prefs.edit().putString("video_bitrate_mbps", "40").commit())
            val second = create("second")
            assertEquals(VideoBitrateInput("750", "12345.678", "14000", adaptive = false), second.videoBitrate.value)
            assertEquals(12_345_678L, second.videoBitrate.value.configuration()?.targetBps)
            assertEquals(AudioChannel.STEREO, second.audioChannel.value)
            assertEquals(230, second.audioGain.value)
            assertEquals(AudioQuality.HIGH, second.audioQuality.value)
            assertTrue(second.lockLandscape.value)
            assertFalse(second.backgroundStreaming.value)
            assertEquals(AppLanguage.ENGLISH, second.language.value)
            assertEquals(AppTheme.LIGHT, second.theme.value)
            assertEquals(StreamAlertMode.VIBRATION, second.streamAlertMode.value)
            assertFalse(second.confirmStop.value)
            assertFalse(second.enableAudio.value)
            assertTrue(second.tlsVerify.value)
            assertEquals("H265", second.videoCodec.value)
            assertEquals(Size(432, 240), second.videoResolution.value)
            assertEquals(60, second.videoFps.value)
            assertEquals(230, prefs.getInt("audio_gain_percent", -1))
            assertEquals("ENGLISH", prefs.getString("app_language", null))
        } finally {
            store.clear()
            prefs.edit().clear().commit()
        }
    }
}
