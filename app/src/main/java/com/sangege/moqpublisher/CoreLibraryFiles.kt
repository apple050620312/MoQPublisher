package com.sangege.moqpublisher

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Extract only the process ABI's official binary; never unpack archive-supplied paths. */
internal object CoreLibraryFiles {
    const val MAX_ARTIFACT_BYTES = 160L * 1024 * 1024
    const val MAX_LIBRARY_BYTES = 128L * 1024 * 1024

    fun readBounded(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            check(output.size() + read <= limit) { "Repository document is too large" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun verifySha256(file: File, expected: String) {
        require(Regex("[0-9a-fA-F]{64}").matches(expected))
        check(sha256(file).equals(expected, ignoreCase = true)) { "Core artifact checksum mismatch" }
    }

    fun extract(archive: File, destination: File, architecture: CoreArchitecture) {
        ZipFile(archive).use { zip ->
            val entry = checkNotNull(zip.getEntry("jni/${architecture.abi}/libmoq_ffi.so")) { "Official artifact has no ${architecture.abi} core" }
            check(entry.size in 64..MAX_LIBRARY_BYTES) { "Invalid core library size" }
            var total = 0L
            FileOutputStream(destination).use { output ->
                zip.getInputStream(entry).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        check(total <= MAX_LIBRARY_BYTES)
                        output.write(buffer, 0, read)
                    }
                }
                output.fd.sync()
            }
            check(total == entry.size)
        }
        validate(destination, architecture)
    }

    fun validate(library: File, architecture: CoreArchitecture) {
        val head = ByteArray(20)
        library.inputStream().use { check(it.read(head) == head.size) }
        check(head[0] == 0x7f.toByte() && head[1] == 'E'.code.toByte() && head[2] == 'L'.code.toByte() &&
            head[3] == 'F'.code.toByte() && head[4] == architecture.elfClass.toByte() && head[5] == 1.toByte() &&
            head[16] == 3.toByte() && head[17] == 0.toByte() && head[18] == architecture.machine.toByte() && head[19] == 0.toByte()) {
            "Core is not a ${architecture.abi} ELF shared library"
        }
    }
}
