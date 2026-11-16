package com.sangege.moqpublisher

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/** Keep resource IDs in state so an existing error also updates when the language changes. */
data class UiMessage(@param:StringRes val resource: Int, val args: List<Any> = emptyList()) {
    @Composable
    fun text(): String = stringResource(resource, *args.toTypedArray())
}

internal class UiException(val uiMessage: UiMessage, cause: Throwable? = null) :
    IllegalStateException("${uiMessage.resource}: ${uiMessage.args}", cause)
