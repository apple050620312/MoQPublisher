package com.sangege.moqpublisher

import android.content.ClipData
import android.content.ClipboardManager
import android.os.PersistableBundle
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource

@Composable
internal fun MoqProViewingPanel(
    relayUrl: String,
    path: String,
    publishToken: String,
    subscribeToken: String,
    onSubscribeTokenChange: (String) -> Unit,
    editable: Boolean,
    verification: SubscriberVerification,
    canVerify: Boolean,
    onVerify: () -> Unit
) {
    val context = LocalContext.current
    val relay = RelayUrl.publicRelayEndpoint(relayUrl)
    val endpoint = RelayUrl.moqProSubscriptionUrl(relayUrl, publishToken, subscribeToken)
    val validPath = StreamPath.isValid(path.trim())
    var showQr by remember(endpoint) { mutableStateOf(false) }
    var guide by remember { mutableStateOf<Int?>(null) }
    val relayLabel = stringResource(R.string.copy_relay_url)
    val nameLabel = stringResource(R.string.copy_broadcast_name)
    val subscribeLabel = stringResource(R.string.copy_subscribe_url)
    fun copy(label: String, value: String, sensitive: Boolean = false) {
        val clip = ClipData.newPlainText(label, value)
        if (sensitive) clip.description.extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }

    HorizontalDivider()
    Text(stringResource(R.string.moq_pro_viewing), style = MaterialTheme.typography.titleSmall)
    SecretField(subscribeToken, onSubscribeTokenChange, stringResource(R.string.moq_pro_subscribe_token),
        stringResource(R.string.moq_pro_subscribe_hint), editable)
    if (editable && subscribeToken.isNotBlank()) {
        TextButton(onClick = { onSubscribeTokenChange("") }) {
            Text(stringResource(R.string.clear_subscribe_jwt))
        }
    }
    if (endpoint == null) Text(stringResource(R.string.moq_pro_subscribe_unavailable), style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = { relay?.let { copy(relayLabel, it) } },
        enabled = relay != null, modifier = Modifier.fillMaxWidth()) { Text(relayLabel) }
    OutlinedButton(onClick = { copy(nameLabel, path.trim()) }, enabled = validPath, modifier = Modifier.fillMaxWidth()) {
        Text(nameLabel)
    }
    OutlinedButton(onClick = { endpoint?.let { copy(subscribeLabel, it, sensitive = true) } },
        enabled = endpoint != null, modifier = Modifier.fillMaxWidth()) { Text(subscribeLabel) }
    OutlinedButton(onClick = { showQr = true }, enabled = endpoint != null, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.show_qr_code))
    }
    TextButton(onClick = { guide = R.string.moq_pro_browser_guide }) { Text(stringResource(R.string.browser_source_guide)) }
    TextButton(onClick = { guide = R.string.moq_pro_native_guide }) { Text(stringResource(R.string.native_moq_source_guide)) }
    OutlinedButton(onClick = onVerify, enabled = canVerify && endpoint != null && validPath &&
        verification !is SubscriberVerification.Running, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.verify_subscription))
    }
    SubscriptionVerificationResult(verification)

    if (showQr && endpoint != null) AlertDialog(
        onDismissRequest = { showQr = false },
        title = { Text(stringResource(R.string.qr_code_title)) },
        text = {
            val bitmap = remember(endpoint) { runCatching { qrBitmap(endpoint, 512) }.getOrNull() }
            if (bitmap != null) Image(bitmap.asImageBitmap(), stringResource(R.string.qr_code_content_description))
        },
        confirmButton = { TextButton(onClick = { showQr = false }) { Text(stringResource(R.string.close)) } }
    )
    guide?.let { resource ->
        AlertDialog(
            onDismissRequest = { guide = null },
            title = { Text(stringResource(R.string.moq_pro_viewing)) },
            text = { Text(stringResource(resource)) },
            confirmButton = { TextButton(onClick = { guide = null }) { Text(stringResource(R.string.close)) } }
        )
    }
}
