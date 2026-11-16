package com.sangege.moqpublisher

import android.content.ClipData
import android.content.ClipboardManager
import android.os.PersistableBundle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
internal fun CloudflareProfilePanel(
    profile: RelayProfile, onChange: (RelayProfile) -> Unit, editable: Boolean,
    verification: SubscriberVerification, onVerify: () -> Unit, canVerify: Boolean
) {
    val context = LocalContext.current
    val watchClipboardLabel = stringResource(R.string.copy_watch_url)
    val obsClipboardLabel = stringResource(R.string.copy_obs_info)
    var pendingAction by remember { mutableStateOf<String?>(null) }
    var copied by remember(profile) { mutableStateOf(false) }
    Text(stringResource(R.string.cloudflare_draft_18), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.cloudflare_compatibility), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    SecretField(profile.publishToken, { onChange(profile.copy(publishToken = it)) },
        stringResource(R.string.cloudflare_publish_token), stringResource(R.string.cloudflare_publish_hint), editable)
    SecretField(profile.subscribeToken, { onChange(profile.copy(subscribeToken = it)) },
        stringResource(R.string.cloudflare_subscribe_token), stringResource(R.string.cloudflare_subscribe_hint), editable)
    OutlinedTextField(profile.broadcastName, { onChange(profile.copy(broadcastName = it)) },
        label = { Text(stringResource(R.string.cloudflare_broadcast_name)) },
        supportingText = { Text(stringResource(R.string.cloudflare_broadcast_hint)) },
        enabled = editable, singleLine = true, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth())
    Text(stringResource(R.string.cloudflare_no_management_token), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    HorizontalDivider()
    Text(stringResource(R.string.cloudflare_viewing), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.cloudflare_share_warning), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (!profile.canShare()) Text(stringResource(R.string.cloudflare_subscribe_required),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(stringResource(R.string.cloudflare_obs_info,
        "https://draft-${profile.protocolDraft}.cloudflare.mediaoverquic.com/[redacted]", profile.broadcastName.trim()),
        style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    OutlinedButton(onClick = { pendingAction = "watch" }, enabled = profile.canShare(), modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.copy_watch_url))
    }
    OutlinedButton(onClick = { pendingAction = "obs" }, enabled = profile.canShare(), modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.copy_obs_info))
    }
    if (copied) Text(stringResource(R.string.subscription_copied), color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = { pendingAction = "verify" },
        enabled = canVerify && profile.canShare() && verification !is SubscriberVerification.Running,
        modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Verified, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.verify_subscription))
    }
    Text(stringResource(R.string.verify_hint), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    when (verification) {
        SubscriberVerification.Idle -> Unit
        is SubscriberVerification.Running -> {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(verification.stage.text(), style = MaterialTheme.typography.bodySmall)
        }
        is SubscriberVerification.Passed -> Text(stringResource(R.string.verify_passed, verification.codec,
            verification.width, verification.height, verification.frames,
            stringResource(if (verification.audio) R.string.verify_audio_passed else R.string.verify_audio_disabled)),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        is SubscriberVerification.Failed -> Text(verification.message.text(), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error)
    }
    if (pendingAction != null) AlertDialog(onDismissRequest = { pendingAction = null },
        title = { Text(stringResource(R.string.confirm_subscribe_only)) },
        text = { Text(stringResource(R.string.confirm_subscribe_only_hint)) },
        confirmButton = {
            TextButton(onClick = {
                val action = pendingAction
                pendingAction = null
                if (profile.canShare()) {
                    if (action == "verify") onVerify()
                    else {
                        val label = if (action == "obs") obsClipboardLabel else watchClipboardLabel
                        val clip = ClipData.newPlainText(label, if (action == "obs") profile.obsSubscription() else profile.watchUrl())
                        // Android 13+ hides the sensitive clipboard preview; older versions ignore this flag.
                        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                        copied = true
                    }
                }
            }) { Text(stringResource(R.string.confirm_subscribe_only_action)) }
        }, dismissButton = { TextButton(onClick = { pendingAction = null }) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun SecretField(value: String, onChange: (String) -> Unit, title: String, hint: String, editable: Boolean) {
    OutlinedTextField(value, onChange, label = { Text(title) }, supportingText = { Text(hint) },
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        singleLine = true, enabled = editable, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth())
}
