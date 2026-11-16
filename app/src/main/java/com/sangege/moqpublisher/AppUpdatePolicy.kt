package com.sangege.moqpublisher

/** Pure release rules shared by the updater and its JVM tests. */
internal object AppUpdatePolicy {
    const val OWNER = "apple050620312"
    const val REPOSITORY = "MoQPublisher"
    const val API_URL = "https://api.github.com/repos/$OWNER/$REPOSITORY/releases/latest"
    const val APK_PREFIX = "MoQPublisher-"
    const val APK_SUFFIX = "-compact.apk"
    private val versionPattern = Regex("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}")
    private val digestPattern = Regex("[0-9a-f]{64}")

    fun validVersion(version: String): Boolean = versionPattern.matches(version)

    fun parseVersionTag(tag: String): String? = tag.trim().removePrefix("v").takeIf(::validVersion)

    fun compare(a: String, b: String): Int {
        require(validVersion(a) && validVersion(b))
        val left = a.split('.').map(String::toInt)
        val right = b.split('.').map(String::toInt)
        return left.zip(right).firstOrNull { (l, r) -> l != r }?.let { (l, r) -> l.compareTo(r) } ?: 0
    }

    fun apkName(version: String): String {
        require(validVersion(version))
        return "$APK_PREFIX$version$APK_SUFFIX"
    }

    fun normalizeDigest(value: String?): String? = value
        ?.trim()
        ?.removePrefix("sha256:")
        ?.lowercase()
        ?.takeIf { digestPattern.matches(it) }

    fun checksumFromSidecar(value: String): String? = value
        .trim()
        .lineSequence()
        .mapNotNull { Regex("([0-9a-fA-F]{64})").find(it)?.groupValues?.get(1) }
        .firstOrNull()
        ?.lowercase()
}
