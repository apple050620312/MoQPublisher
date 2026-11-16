package com.sangege.moqpublisher

import org.junit.Assert.*
import org.junit.Test

class CoreUpdatePolicyTest {
    @Test fun selectsStableReleasesInNumericOrderAndRejectsOldOrUnsafeVersions() {
        val xml = "<versions>" + listOf("0.4.26", "0.4.9", "0.4.100", "0.5.0", "0.5.1-SNAPSHOT", "../../evil", "0.4.26")
            .joinToString("") { "<version>$it</version>" } + "</versions>"
        assertEquals(listOf("0.5.0", "0.4.100", "0.4.26"), CoreUpdatePolicy.versions(xml))
    }

    @Test fun addedFunctionsAreCompatibleButChangedOrMissingFunctionsAreNot() {
        val expected = mapOf("contract" to 30, "write" to 123)
        assertTrue(CoreUpdatePolicy.compatible(expected, expected + ("newFunction" to 456)))
        assertFalse(CoreUpdatePolicy.compatible(expected, expected + ("write" to 999)))
        assertFalse(CoreUpdatePolicy.compatible(expected, expected - "write"))
        assertFalse(CoreUpdatePolicy.compatible(expected, expected + ("contract" to 31)))
        assertFalse(CoreUpdatePolicy.compatible(emptyMap(), emptyMap()))
    }

    @Test fun readsTheActualUniFfiChecksumFormat() {
        val bindings = """
            val bindings_contract_version = 30
            if ((lib.uniffi_moq_ffi_checksum_method_moqaudioproducer_write() and 0xFFFF) != 42822) { }
        """.trimIndent()
        assertEquals(mapOf("ffi_moq_ffi_uniffi_contract_version" to 30,
            "uniffi_moq_ffi_checksum_method_moqaudioproducer_write" to 42822), CoreUpdatePolicy.bindingChecksums(bindings))
        assertTrue(CoreUpdatePolicy.bindingChecksums("invalid source archive").isEmpty())
    }

    @Test fun artifactUrlsStayAtTheOfficialVersionDirectory() {
        assertEquals("${CoreUpdatePolicy.REPOSITORY}/0.4.26/moq-ffi-android-0.4.26.aar",
            CoreUpdatePolicy.artifactUrl("0.4.26", "moq-ffi-android-0.4.26.aar"))
        for (file in listOf("../evil.aar", "https://example.com/core.aar", "/core.aar", "a?token=x", "..")) {
            assertThrows(IllegalArgumentException::class.java) { CoreUpdatePolicy.artifactUrl("0.4.26", file) }
        }
        assertThrows(IllegalArgumentException::class.java) { CoreUpdatePolicy.artifactUrl("../0.4.26", "core.aar") }
    }
}
