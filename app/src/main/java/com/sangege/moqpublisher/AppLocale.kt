package com.sangege.moqpublisher

import android.content.res.Configuration
import android.os.LocaleList
import android.view.ContextThemeWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/** Official Android resources, scoped to the UI without restarting the activity or MoQ session. */
@Composable
internal fun AppLocale(language: AppLanguage, content: @Composable () -> Unit) {
    val baseContext = LocalContext.current
    val systemConfiguration = LocalConfiguration.current
    val configuration = remember(systemConfiguration, language) {
        Configuration(systemConfiguration).apply {
            language.tag?.let { setLocales(LocaleList.forLanguageTags(it)) }
        }
    }
    val context = remember(baseContext, configuration) {
        // Retain the Activity in the ContextWrapper chain for dialogs and ActivityResult contracts.
        ContextThemeWrapper(baseContext, R.style.Theme_MoQPublisher).apply {
            applyOverrideConfiguration(configuration)
        }
    }
    CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration, content = content)
}
