package com.sangege.moqpublisher

import org.junit.Assert.*
import org.junit.Test

class AppSettingsTest {
    @Test
    fun cleanInstallAndUnrecognizedPreferencesUseChineseDarkAndOriginalMonoAudio() {
        for (value in listOf(null, "", "UNKNOWN")) {
            assertEquals(AppLanguage.TRADITIONAL_CHINESE, AppLanguage.fromPreference(value))
            assertEquals(AppTheme.DARK, AppTheme.fromPreference(value))
            assertEquals(AudioChannel.MONO, AudioChannel.fromPreference(value))
        }
    }

    @Test
    fun savedChoicesRoundTripIncludingSystemLanguage() {
        for (language in AppLanguage.entries) assertEquals(language, AppLanguage.fromPreference(language.name))
        for (theme in AppTheme.entries) assertEquals(theme, AppTheme.fromPreference(theme.name))
        for (channel in AudioChannel.entries) assertEquals(channel, AudioChannel.fromPreference(channel.name))
    }
}
