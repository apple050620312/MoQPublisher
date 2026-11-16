package com.sangege.moqpublisher

internal object CoreUpdatePolicy {
    const val BASE_VERSION = "0.4.26"
    const val REPOSITORY = "https://repo.maven.apache.org/maven2/dev/moq/moq-ffi-android"
    private val versionPattern = Regex("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}")
    private val checksums = Regex("lib\\.(uniffi_moq_ffi_checksum_\\w+)\\(\\) and 0xFFFF\\) != (\\d+)")

    fun validVersion(version: String) = versionPattern.matches(version)

    fun versions(metadata: String): List<String> = Regex("<version>([^<]+)</version>").findAll(metadata)
        .map { it.groupValues[1].trim() }.filter(::validVersion).distinct()
        .filter { compare(it, BASE_VERSION) >= 0 }
        .sortedWith { a, b -> compare(b, a) }.toList()

    fun compare(a: String, b: String): Int {
        require(validVersion(a) && validVersion(b))
        for ((left, right) in a.split('.').zip(b.split('.'))) {
            val result = left.toInt().compareTo(right.toInt())
            if (result != 0) return result
        }
        return 0
    }

    fun bindingChecksums(source: String): Map<String, Int> {
        val contract = Regex("bindings_contract_version = (\\d+)").find(source)?.groupValues?.get(1)?.toInt()
            ?: return emptyMap()
        return checksums.findAll(source).associate { it.groupValues[1] to it.groupValues[2].toInt() } +
            ("ffi_moq_ffi_uniffi_contract_version" to contract)
    }

    fun compatible(expected: Map<String, Int>, candidate: Map<String, Int>) =
        expected.isNotEmpty() && expected.all { (name, checksum) -> candidate[name] == checksum }

    fun artifactUrl(version: String, name: String): String {
        require(validVersion(version))
        require(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,180}").matches(name) && !name.contains(".."))
        return "$REPOSITORY/$version/$name"
    }
}
