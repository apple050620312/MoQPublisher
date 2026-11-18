package com.sangege.moqpublisher

import java.math.BigDecimal

/** Settings use decimal Kbps; MediaCodec and MoQ always receive bits per second. */
internal object VideoBitrateSettings {
    const val DEFAULT_KBPS = "8000"
    const val DEFAULT_BPS = 8_000_000L
    const val MIN_BPS = 100_000L
    const val DEFAULT_MIN_KBPS = "600"
    const val DEFAULT_MAX_KBPS = DEFAULT_KBPS

    fun parseKbps(value: String): Long? = parse(value, 1_000)

    /** Migrate the pre-1.0.6 preference once, without interpreting 8 Mbps as 8 Kbps. */
    fun migrateMbps(value: String?): String = value?.let { parse(it, 1_000_000) }
        ?.let { BigDecimal(it).divide(BigDecimal(1_000)).stripTrailingZeros().toPlainString() }
        ?: DEFAULT_KBPS

    private fun parse(value: String, multiplier: Int): Long? = runCatching {
        val clean = value.trim()
        if (!Regex("[0-9]+(?:[.,][0-9]+)?").matches(clean)) return null
        BigDecimal(clean.replace(',', '.')).multiply(BigDecimal(multiplier)).longValueExact()
            .takeIf { it in MIN_BPS..Int.MAX_VALUE.toLong() }
    }.getOrNull()
}
