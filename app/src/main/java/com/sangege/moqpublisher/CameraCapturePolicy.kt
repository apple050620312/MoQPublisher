package com.sangege.moqpublisher

data class CameraCaptureStatus(
    val width: Int,
    val height: Int,
    val requestedFps: Int,
    val minFps: Int,
    val maxFps: Int
)

/** Camera choices use sensor dimensions, independent of portrait/landscape display rotation. */
internal object CameraCapturePolicy {
    data class Resolution(val width: Int, val height: Int) {
        val longSide get() = maxOf(width, height)
        val shortSide get() = minOf(width, height)
        val pixels get() = width.toLong() * height
    }

    data class FrameRate(val lower: Int, val upper: Int)

    fun boundedResolutions(supported: List<Resolution>, requested: Resolution): List<Resolution> {
        val bounded = supported.filter {
            it.longSide <= requested.longSide && it.shortSide <= requested.shortSide
        }
        // Preserve CameraX's preferred aspect-ratio/resolution ordering. If the
        // requested size is below every camera mode, use only the smallest mode.
        return bounded.ifEmpty { supported.minByOrNull { it.pixels }?.let(::listOf).orEmpty() }
    }

    fun frameRate(supported: List<FrameRate>, target: Int): FrameRate {
        val covering = supported.filter { target in it.lower..it.upper }
        return covering.minWithOrNull(compareBy<FrameRate> { it.upper - target }.thenBy { target - it.lower })
            ?: supported.minWithOrNull(compareBy<FrameRate> { kotlin.math.abs(it.upper - target) }
                .thenBy { kotlin.math.abs(it.lower - target) })
            ?: FrameRate(target, target)
    }
}
