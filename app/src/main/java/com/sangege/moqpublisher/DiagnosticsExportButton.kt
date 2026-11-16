package com.sangege.moqpublisher

import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun DiagnosticsExportButton(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val clipLabel = stringResource(R.string.diagnostics_clip_label)
    val shareTitle = stringResource(R.string.diagnostics_share)
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var reportUri by rememberSaveable { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<UiMessage?>(null) }
    var failed by remember { mutableStateOf(false) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { target ->
        val source = reportUri?.toUri()
        if (target != null && source != null) {
            scope.launch {
                working = true
                try {
                    withContext(Dispatchers.IO) {
                        checkNotNull(context.contentResolver.openInputStream(source)).use { input ->
                            checkNotNull(context.contentResolver.openOutputStream(target, "w")).use { output -> input.copyTo(output) }
                        }
                    }
                    reportUri = null
                    failed = false
                    notice = UiMessage(R.string.diagnostics_saved)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    failed = true
                    notice = UiMessage(R.string.diagnostics_save_failed)
                } finally {
                    working = false
                }
            }
        }
    }

    OutlinedButton(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        enabled = !working,
        onClick = {
            scope.launch {
                working = true
                notice = null
                try {
                    reportUri = withContext(Dispatchers.IO) { viewModel.exportDiagnostics().toString() }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    failed = true
                    notice = UiMessage(R.string.diagnostics_export_failed)
                } finally {
                    working = false
                }
            }
        }
    ) {
        if (working) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        else Icon(Icons.Default.BugReport, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(if (working) R.string.diagnostics_preparing else R.string.diagnostics_export))
    }

    notice?.let { StatusNotice(it.text(), error = failed, success = !failed) }

    reportUri?.let { value ->
        val uri = value.toUri()
        AlertDialog(
            onDismissRequest = { if (!working) reportUri = null },
            title = { Text(stringResource(R.string.diagnostics_ready)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.diagnostics_description))
                    if (failed) notice?.let { StatusNotice(it.text(), error = true) }
                }
            },
            confirmButton = {
                TextButton(enabled = !working, onClick = {
                    try {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "application/zip"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            clipData = ClipData.newUri(context.contentResolver, clipLabel, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(send, shareTitle))
                    } catch (_: Exception) {
                        failed = true
                        notice = UiMessage(R.string.diagnostics_share_failed)
                    }
                }) { Text(stringResource(R.string.share)) }
            },
            dismissButton = {
                TextButton(enabled = !working, onClick = {
                    saveLauncher.launch(uri.lastPathSegment ?: "MoQPublisher-diagnostics.zip")
                }) { Text(stringResource(if (working) R.string.saving else R.string.save_file)) }
            }
        )
    }
}
