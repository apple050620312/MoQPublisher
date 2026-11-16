package com.sangege.moqpublisher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource

/** Show measured output separately from the user's target and camera's supported mode. */
@Composable
internal fun VideoPerformanceInfo(viewModel: MainViewModel) {
    val state by viewModel.streamState.collectAsState()
    val targetFps by viewModel.videoFps.collectAsState()
    val camera by viewModel.cameraCaptureStatus.collectAsState()
    val locale = LocalConfiguration.current.locales[0]
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        (state as? StreamState.Live)?.let { live ->
            val fps = live.publishedFps?.let { String.format(locale, "%.1f", it) } ?: stringResource(R.string.stat_unavailable)
            val bitrate = live.videoBitrateBps?.let { String.format(locale, "%.1f", it / 1_000_000.0) } ?: stringResource(R.string.stat_unavailable)
            Text(stringResource(R.string.performance_summary, fps, targetFps, bitrate), style = MaterialTheme.typography.bodySmall)
        }
        camera?.let { capture ->
            val range = if (capture.minFps == capture.maxFps) "${capture.maxFps}" else "${capture.minFps}–${capture.maxFps}"
            Text(
                stringResource(R.string.camera_capture_summary, capture.width, capture.height, range),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (capture.maxFps < targetFps) {
                Text(
                    stringResource(R.string.camera_fps_limited, capture.maxFps),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
