package com.sangege.moqpublisher

/** Validates the namespace path passed to the MoQ publisher. */
internal object StreamPath {
    private const val MAX_LENGTH = 512

    fun isValid(value: String): Boolean {
        val path = value.trim()
        if (path.isEmpty() || path.length > MAX_LENGTH || path.any { it.isWhitespace() || it.isISOControl() }) return false
        val segments = path.split('/')
        return segments.all { it.isNotEmpty() && it != "." && it != ".." && it.none { c -> c in "?#\\%" } }
    }
}
