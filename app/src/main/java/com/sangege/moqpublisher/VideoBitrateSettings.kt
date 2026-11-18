package com.sangege.moqpublisher

import java.math.BigDecimal

internal data class VideoBitrateConfiguration(
    val minimumBps: Long,
    val targetBps: Long,
    val maximumBps: Long,
    val adaptive: Boolean
) {
    init {
        require(minimumBps >= VideoBitrateSettings.MIN_BPS && targetBps in minimumBps..maximumBps &&
            maximumBps <= Int.MAX_VALUE.toLong())
    }
}

internal data class VideoBitrateInput(
    val minimumKbps: String,
    val targetKbps: String,
    val maximumKbps: String,
    val adaptive: Boolean
) {
    fun configuration(): VideoBitrateConfiguration? {
        val minimum = VideoBitrateSettings.parseKbps(minimumKbps) ?: return null
        val target = VideoBitrateSettings.parseKbps(targetKbps) ?: return null
        val maximum = VideoBitrateSettings.parseKbps(maximumKbps) ?: return null
        return if (target in minimum..maximum) VideoBitrateConfiguration(minimum, target, maximum, adaptive) else null
    }

    companion object {
        fun restore(target: String?, minimum: String?, maximum: String?, adaptive: Boolean): VideoBitrateInput {
            val targetKbps = target?.takeIf { VideoBitrateSettings.parseKbps(it) != null }
                ?: VideoBitrateSettings.DEFAULT_KBPS
            val targetBps = checkNotNull(VideoBitrateSettings.parseKbps(targetKbps))
            val defaultMinimum = minOf(VideoBitrateSettings.DEFAULT_MIN_BPS, targetBps)
            return VideoBitrateInput(
                minimum?.takeIf { (VideoBitrateSettings.parseKbps(it) ?: Long.MAX_VALUE) <= targetBps }
                    ?: VideoBitrateSettings.formatKbps(defaultMinimum),
                targetKbps,
                maximum?.takeIf { (VideoBitrateSettings.parseKbps(it) ?: 0) >= targetBps } ?: targetKbps,
                adaptive
            )
        }
    }
}

/** Settings use decimal Kbps; MediaCodec and MoQ always receive bits per second. */
internal object VideoBitrateSettings {
    const val DEFAULT_KBPS = "8000"
    const val DEFAULT_BPS = 8_000_000L
    const val MIN_BPS = 100_000L
    const val DEFAULT_MIN_BPS = 600_000L

    fun parseKbps(value: String): Long? = parse(value, 1_000)
    fun formatKbps(bitrateBps: Long): String = BigDecimal.valueOf(bitrateBps, 3).stripTrailingZeros().toPlainString()

    /** Migrate the pre-1.0.6 preference once, without interpreting 8 Mbps as 8 Kbps. */
    fun migrateMbps(value: String?): String = value?.let { parse(it, 1_000_000) }
        ?.let(::formatKbps)
        ?: DEFAULT_KBPS

    private fun parse(value: String, multiplier: Int): Long? = runCatching {
        val clean = value.trim()
        if (!Regex("[0-9]+(?:[.,][0-9]+)?").matches(clean)) return null
        BigDecimal(clean.replace(',', '.')).multiply(BigDecimal(multiplier)).longValueExact()
            .takeIf { it in MIN_BPS..Int.MAX_VALUE.toLong() }
    }.getOrNull()
}
