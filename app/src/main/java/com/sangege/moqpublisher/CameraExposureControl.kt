package com.sangege.moqpublisher

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Exposure
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
internal fun CameraExposureControl(controls: CameraControlHandle, state: CameraControlState) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = state.attached) {
            Icon(Icons.Default.Exposure, contentDescription = stringResource(R.string.exposure_compensation))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Column(Modifier.width(260.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.exposure_compensation), style = MaterialTheme.typography.titleSmall)
                if (state.exposureSupported && state.minExposureIndex < state.maxExposureIndex) {
                    Text(stringResource(R.string.exposure_ev, state.exposureIndex * state.exposureStep))
                    Slider(
                        value = state.exposureIndex.toFloat(), onValueChange = { controls.setExposureIndex(it.roundToInt()) },
                        valueRange = state.minExposureIndex.toFloat()..state.maxExposureIndex.toFloat(),
                        steps = (state.maxExposureIndex - state.minExposureIndex - 1).coerceIn(0, 40)
                    )
                    Text(stringResource(R.string.exposure_hint), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { controls.setExposureIndex(0) }) { Text(stringResource(R.string.exposure_reset)) }
                } else Text(stringResource(R.string.exposure_unavailable), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
