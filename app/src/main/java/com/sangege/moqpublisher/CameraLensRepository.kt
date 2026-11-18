package com.sangege.moqpublisher

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build

internal data class CameraLens(
    val logicalId: String,
    val physicalId: String?,
    val front: Boolean,
    val equivalentFocalLengthMm: Float?,
    val stabilizesVideo: Boolean
) {
    val id: String get() = if (physicalId == null) logicalId else "$logicalId/$physicalId"
    val label: Int get() {
        val focalLength = equivalentFocalLengthMm
        return when {
            front -> R.string.lens_front
            physicalId == null -> R.string.lens_logical
            focalLength != null && focalLength < 22f -> R.string.lens_ultra_wide
            focalLength != null && focalLength >= 50f -> R.string.lens_telephoto
            else -> R.string.lens_back
        }
    }
}

internal class CameraLensRepository(context: Context) {
    private val manager = context.getSystemService(CameraManager::class.java)

    fun list(): List<CameraLens> = runCatching {
        buildList {
            for (id in manager.cameraIdList) {
                val logical = characteristics(id) ?: continue
                val front = when (logical.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_FRONT -> true
                    CameraCharacteristics.LENS_FACING_BACK -> false
                    else -> continue
                }
                add(lens(logical, id, null, front))
                if (Build.VERSION.SDK_INT >= 28) {
                    for (physicalId in logical.physicalCameraIds.sorted()) {
                        characteristics(physicalId)?.let { add(lens(it, id, physicalId, front)) }
                    }
                }
            }
        }
    }.getOrDefault(emptyList())

    private fun characteristics(id: String): CameraCharacteristics? =
        runCatching { manager.getCameraCharacteristics(id) }.getOrNull()

    private fun lens(info: CameraCharacteristics, logicalId: String, physicalId: String?, front: Boolean): CameraLens {
        val width = info.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width
        val focalLength = info.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val equivalent = if (width != null && width > 0 && focalLength != null) focalLength * 36f / width else null
        return CameraLens(
            logicalId, physicalId, front, equivalent,
            CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_ON in
                (info.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: intArrayOf())
        )
    }
}
