package com.sangege.moqpublisher

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.os.SystemClock
import android.util.AtomicFile
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

internal enum class OverlayPosition(val label: Int) {
    TOP_LEFT(R.string.overlay_top_left),
    TOP_RIGHT(R.string.overlay_top_right),
    BOTTOM_LEFT(R.string.overlay_bottom_left),
    BOTTOM_RIGHT(R.string.overlay_bottom_right)
}

internal data class OverlaySettings(
    val visible: Boolean = false,
    val logo: Boolean = true,
    val text: String = "",
    val watermark: String = "",
    val clock: Boolean = false,
    val timer: Boolean = false,
    val position: OverlayPosition = OverlayPosition.TOP_RIGHT,
    val scale: Int = 100,
    val opacity: Int = 100
) {
    fun normalized() = copy(
        text = normalizeText(text),
        watermark = normalizeText(watermark),
        scale = scale.coerceIn(50, 200),
        opacity = opacity.coerceIn(10, 100)
    )

    private fun normalizeText(value: String): String {
        val count = value.codePointCount(0, value.length).coerceAtMost(96)
        return value.substring(0, value.offsetByCodePoints(0, count)).replace(Regex("[\\p{Cc}]"), " ").trim()
    }
}

internal data class OverlayFrame(val bitmap: Bitmap?, val settings: OverlaySettings)

internal data class FrameViewport(val x: Int, val y: Int, val width: Int, val height: Int) {
    companion object {
        fun fit(viewWidth: Int, viewHeight: Int, sourceWidth: Int, sourceHeight: Int): FrameViewport {
            val scale = minOf(viewWidth.toDouble() / sourceWidth, viewHeight.toDouble() / sourceHeight)
            val width = (sourceWidth * scale).roundToInt().coerceAtLeast(1)
            val height = (sourceHeight * scale).roundToInt().coerceAtLeast(1)
            return FrameViewport((viewWidth - width) / 2, (viewHeight - height) / 2, width, height)
        }
    }
}

internal fun OverlaySettings.placement(frame: FrameViewport, textureWidth: Int, textureHeight: Int): FrameViewport {
    val shortEdge = minOf(frame.width, frame.height)
    val margin = (shortEdge * 0.028).roundToInt()
    val renderScale = minOf(
        shortEdge / 720.0 * scale / 100,
        (frame.width - margin * 2).coerceAtLeast(1).toDouble() / textureWidth,
        (frame.height - margin * 2).coerceAtLeast(1).toDouble() / textureHeight
    )
    val width = (textureWidth * renderScale).roundToInt().coerceAtLeast(1)
    val height = (textureHeight * renderScale).roundToInt().coerceAtLeast(1)
    val alignRight = position == OverlayPosition.TOP_RIGHT || position == OverlayPosition.BOTTOM_RIGHT
    val alignTop = position == OverlayPosition.TOP_LEFT || position == OverlayPosition.TOP_RIGHT
    val x = if (alignRight) frame.width - margin - width else margin
    val y = if (alignTop) frame.height - margin - height else margin
    return FrameViewport(frame.x + x, frame.y + y, width, height)
}

/** Prepares bitmaps on a worker and hands the latest image to the existing EGL renderer. */
internal class StreamOverlay(
    private val context: Context,
    private val preferences: SharedPreferences,
    private val diagnostics: AppDiagnostics,
    private val publish: (OverlayFrame) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val imageFile = AtomicFile(File(context.filesDir, "stream-overlay-logo.png"))
    private val imageLock = Mutex()
    private val image = MutableStateFlow<Bitmap?>(null)
    private val startedAt = MutableStateFlow<Long?>(null)
    private val mutableSettings = MutableStateFlow(loadSettings())
    private val mutableLogoAvailable = MutableStateFlow(false)
    private val mutableProblem = MutableStateFlow<UiMessage?>(null)
    val settings = mutableSettings.asStateFlow()
    val logoAvailable = mutableLogoAvailable.asStateFlow()
    val problem = mutableProblem.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            imageLock.withLock {
                val saved = runCatching {
                    imageFile.openRead().use { BitmapFactory.decodeStream(it) }
                }.getOrNull()
                image.value = saved
                mutableLogoAvailable.value = saved != null
            }
        }
        scope.launch {
            combine(settings, image, startedAt) { settings, logo, start -> Triple(settings, logo, start) }
                .collectLatest { (config, logo, start) ->
                    if (!config.visible) {
                        publish(OverlayFrame(null, config))
                        return@collectLatest
                    }
                    try {
                        val clock = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                        do {
                            val now = SystemClock.elapsedRealtime()
                            val timer = if (config.timer) duration((now - (start ?: now)).coerceAtLeast(0) / 1000) else ""
                            val wallTime = if (config.clock) clock.format(Date()) else ""
                            publish(OverlayFrame(rasterize(config, logo, wallTime, timer), config))
                            if (!config.clock && (!config.timer || start == null)) break
                            delay(1_000L - (now - (start ?: 0L)).coerceAtLeast(0) % 1_000L)
                        } while (true)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        diagnostics.error("overlay_raster_failed", error)
                        publish(OverlayFrame(null, config))
                        mutableProblem.value = UiMessage(R.string.overlay_render_failed)
                    }
                }
        }
    }

    fun setCapturing(active: Boolean) {
        if (active) {
            if (startedAt.value == null) startedAt.value = SystemClock.elapsedRealtime()
        } else startedAt.value = null
    }

    fun save(settings: OverlaySettings) {
        val next = settings.normalized()
        mutableSettings.value = next
        mutableProblem.value = null
        preferences.edit {
            putBoolean("overlay_visible", next.visible)
            putBoolean("overlay_logo", next.logo)
            putString("overlay_text", next.text)
            putString("overlay_watermark", next.watermark)
            putBoolean("overlay_clock", next.clock)
            putBoolean("overlay_timer", next.timer)
            putString("overlay_position", next.position.name)
            putInt("overlay_scale", next.scale)
            putInt("overlay_opacity", next.opacity)
        }
        diagnostics.event("overlay_settings_applied", mapOf(
            "visible" to next.visible, "clock" to next.clock, "timer" to next.timer,
            "logo" to next.logo, "position" to next.position, "scale" to next.scale, "opacity" to next.opacity
        ))
    }

    fun renderFailed(error: Throwable) {
        val message = UiMessage(R.string.overlay_render_failed)
        if (mutableProblem.value != message) {
            mutableProblem.value = message
            diagnostics.error("overlay_gpu_render_failed", error)
        }
    }

    fun importLogo(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            imageLock.withLock {
                try {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    checkNotNull(context.contentResolver.openInputStream(uri)).use {
                        BitmapFactory.decodeStream(it, null, bounds)
                    }
                    check(bounds.outWidth > 0 && bounds.outHeight > 0)
                    val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                    val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
                    while (longEdge / options.inSampleSize.coerceAtLeast(1) > 512) {
                        options.inSampleSize = options.inSampleSize.coerceAtLeast(1) * 2
                    }
                    val logo = checkNotNull(context.contentResolver.openInputStream(uri)).use {
                        checkNotNull(BitmapFactory.decodeStream(it, null, options))
                    }
                    val output = imageFile.startWrite()
                    try {
                        check(logo.compress(Bitmap.CompressFormat.PNG, 100, output))
                        imageFile.finishWrite(output)
                    } catch (error: Exception) {
                        imageFile.failWrite(output)
                        throw error
                    }
                    image.value = logo
                    mutableLogoAvailable.value = true
                    mutableProblem.value = null
                    diagnostics.event("overlay_logo_imported", mapOf("width" to logo.width, "height" to logo.height))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    diagnostics.error("overlay_logo_failed", error)
                    mutableProblem.value = UiMessage(R.string.overlay_logo_failed)
                }
            }
        }
    }

    fun removeLogo() {
        scope.launch(Dispatchers.IO) {
            imageLock.withLock {
                imageFile.delete()
                image.value = null
                mutableLogoAvailable.value = false
                mutableProblem.value = null
            }
        }
    }

    private fun loadSettings() = OverlaySettings(
        visible = preferences.getBoolean("overlay_visible", false),
        logo = preferences.getBoolean("overlay_logo", true),
        text = preferences.getString("overlay_text", "").orEmpty(),
        watermark = preferences.getString("overlay_watermark", "").orEmpty(),
        clock = preferences.getBoolean("overlay_clock", false),
        timer = preferences.getBoolean("overlay_timer", false),
        position = OverlayPosition.entries.firstOrNull { it.name == preferences.getString("overlay_position", null) }
            ?: OverlayPosition.TOP_RIGHT,
        scale = preferences.getInt("overlay_scale", 100),
        opacity = preferences.getInt("overlay_opacity", 100)
    ).normalized()

    private data class Line(val text: String, val size: Float, val alpha: Int, val typeface: Typeface)

    private fun rasterize(config: OverlaySettings, logo: Bitmap?, wallTime: String, timer: String): Bitmap? {
        val selectedLogo = logo.takeIf { config.logo }
        val lines = listOf(
            Line(config.text, 39f, 255, Typeface.DEFAULT_BOLD),
            Line(wallTime, 34f, 255, Typeface.MONOSPACE),
            Line(timer, 34f, 255, Typeface.MONOSPACE),
            Line(config.watermark, 34f, 154, Typeface.DEFAULT_BOLD)
        ).filter { it.text.isNotEmpty() }
        if (lines.isEmpty() && selectedLogo == null) return null
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        fun measure(line: Line): Float {
            paint.typeface = line.typeface
            paint.textSize = line.size
            val width = paint.measureText(line.text).coerceAtLeast(1f)
            paint.textSize *= minOf(1f, 570f / width)
            return paint.measureText(line.text)
        }
        val logoScale = selectedLogo?.let { minOf(180.0 / it.width, 130.0 / it.height) } ?: 0.0
        val logoWidth = selectedLogo?.let { (it.width * logoScale).roundToInt().coerceAtLeast(1) } ?: 0
        val logoHeight = selectedLogo?.let { (it.height * logoScale).roundToInt().coerceAtLeast(1) } ?: 0
        val right = config.position == OverlayPosition.TOP_RIGHT || config.position == OverlayPosition.BOTTOM_RIGHT
        val width = maxOf(logoWidth, ceil(lines.maxOfOrNull(::measure) ?: 0f).toInt()) + 16
        val bitmap = Bitmap.createBitmap(width, logoHeight + 16 + lines.size * 48, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val logoX = if (right) width - logoWidth - 8 else 8
        if (selectedLogo != null) canvas.drawBitmap(selectedLogo, null, Rect(logoX, 8, logoX + logoWidth, 8 + logoHeight), paint)
        for ((index, line) in lines.withIndex()) {
            measure(line)
            paint.textAlign = if (right) Paint.Align.RIGHT else Paint.Align.LEFT
            val x = if (right) width - 8f else 8f
            val y = 42f + logoHeight + index * 48
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4f
            paint.strokeJoin = Paint.Join.ROUND
            paint.color = Color.BLACK
            paint.alpha = line.alpha
            canvas.drawText(line.text, x, y, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            paint.alpha = line.alpha
            canvas.drawText(line.text, x, y, paint)
        }
        return bitmap
    }

    private fun duration(seconds: Long): String =
        String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3_600, seconds / 60 % 60, seconds % 60)
}
