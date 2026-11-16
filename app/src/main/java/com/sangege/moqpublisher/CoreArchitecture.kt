package com.sangege.moqpublisher

/** Official Android core targets. Match the running process, including a 32-bit app on a 64-bit CPU. */
internal enum class CoreArchitecture(val abi: String, val elfClass: Int, val machine: Int, val is64Bit: Boolean) {
    ARM64("arm64-v8a", 2, 183, true),
    ARM32("armeabi-v7a", 1, 40, false),
    X86_64("x86_64", 2, 62, true);

    companion object {
        fun select(supportedAbis: List<String>, processIs64Bit: Boolean): CoreArchitecture? = supportedAbis.firstNotNullOfOrNull { abi ->
            entries.firstOrNull { it.abi == abi && it.is64Bit == processIs64Bit }
        }
    }
}
