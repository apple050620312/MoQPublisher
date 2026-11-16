package com.sangege.moqpublisher

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.sangege.moqpublisher.ui.theme.MoQPublisherTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AppearanceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun languageThemeAndExistingErrorsChangeWithoutRecreatingContent() {
        val language = mutableStateOf(AppLanguage.TRADITIONAL_CHINESE)
        val theme = mutableStateOf(AppTheme.DARK)
        val error = UiMessage(R.string.error_path_invalid)
        var token: Any? = null
        var background = Color.Unspecified
        compose.setContent {
            AppLocale(language.value) {
                MoQPublisherTheme(darkTheme = theme.value == AppTheme.DARK) {
                    val currentToken = remember { Any() }
                    val currentBackground = MaterialTheme.colorScheme.background
                    SideEffect { token = currentToken; background = currentBackground }
                    Column {
                        Text(stringResource(R.string.settings))
                        Text(error.text())
                    }
                }
            }
        }
        compose.onNodeWithText("設定").assertIsDisplayed()
        compose.onNodeWithText("請輸入不含空白的直播路徑。").assertIsDisplayed()
        var originalToken: Any? = null
        var originalBackground = Color.Unspecified
        compose.runOnIdle { originalToken = token; originalBackground = background; language.value = AppLanguage.ENGLISH; theme.value = AppTheme.LIGHT }
        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithText("Enter a broadcast path without spaces.").assertIsDisplayed()
        compose.runOnIdle { assertSame(originalToken, token); assertNotEquals(originalBackground, background); language.value = AppLanguage.TRADITIONAL_CHINESE }
        compose.onNodeWithText("設定").assertIsDisplayed()
        compose.runOnIdle { assertSame(originalToken, token) }
    }
}
