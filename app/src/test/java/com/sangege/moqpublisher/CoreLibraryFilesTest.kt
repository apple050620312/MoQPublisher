package com.sangege.moqpublisher

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CoreLibraryFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun elf(machine: Int = 183, elfClass: Int = 2) = ByteArray(64).apply {
        this[0] = 127; this[1] = 69; this[2] = 76; this[3] = 70
        this[4] = elfClass.toByte(); this[5] = 1; this[16] = 3; this[18] = machine.toByte()
    }

    @Test fun verifiesTheCompleteDownloadedArtifactAndRejectsCorruption() {
        val file = temporary.newFile().apply { writeText("abc") }
        val digest = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        CoreLibraryFiles.verifySha256(file, digest.uppercase())
        file.appendText("corrupt")
        assertThrows(IllegalStateException::class.java) { CoreLibraryFiles.verifySha256(file, digest) }
    }

    @Test fun extractsOnlyArm64AndNeverArchivePaths() {
        val archive = temporary.newFile()
        ZipOutputStream(archive.outputStream()).use { zip ->
            for ((name, bytes) in listOf("../outside.so" to elf(), "jni/x86_64/libmoq_ffi.so" to elf(62),
                "jni/arm64-v8a/libmoq_ffi.so" to elf())) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
        val target = temporary.newFile()
        CoreLibraryFiles.extract(archive, target, CoreArchitecture.ARM64)
        assertArrayEquals(elf(), target.readBytes())
        assertFalse(java.io.File(temporary.root.parentFile, "outside.so").exists())
    }

    @Test fun rejectsTheWrongArchitectureAndIncompleteLibraries() {
        val file = temporary.newFile()
        file.writeBytes(elf(62))
        assertThrows(IllegalStateException::class.java) { CoreLibraryFiles.validate(file, CoreArchitecture.ARM64) }
        file.writeBytes(byteArrayOf(127))
        assertThrows(IllegalStateException::class.java) { CoreLibraryFiles.validate(file, CoreArchitecture.ARM64) }
    }

    @Test fun extractsAndValidatesEachOfficialArchitecture() {
        for (architecture in CoreArchitecture.entries) {
            val archive = temporary.newFile()
            val expected = elf(architecture.machine, architecture.elfClass)
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("jni/${architecture.abi}/libmoq_ffi.so"))
                zip.write(expected); zip.closeEntry()
            }
            val target = temporary.newFile()
            CoreLibraryFiles.extract(archive, target, architecture)
            assertArrayEquals(expected, target.readBytes())
            for (other in CoreArchitecture.entries.filter { it != architecture }) {
                assertThrows(IllegalStateException::class.java) { CoreLibraryFiles.validate(target, other) }
            }
        }
    }

    @Test fun boundsMetadataEvenIfTheServerOmitsAContentLength() {
        assertEquals(16, CoreLibraryFiles.readBounded(ByteArray(16).inputStream(), 16).size)
        assertThrows(IllegalStateException::class.java) { CoreLibraryFiles.readBounded(ByteArray(17).inputStream(), 16) }
    }
}
