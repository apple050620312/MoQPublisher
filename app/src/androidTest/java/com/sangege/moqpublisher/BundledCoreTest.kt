package com.sangege.moqpublisher

import android.os.Build
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.moq.uniffiEnsureInitialized

@RunWith(AndroidJUnit4::class)
class DownloadedCoreTest {
    @Test
    fun savedCoreMatchesTheRunningArchitectureAndGeneratedBindings() {
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MoqApplication
        val state = application.core.state.value
        assumeTrue("Download the official core before running this device test", state.ready)
        val architecture = CoreArchitecture.select(Build.SUPPORTED_ABIS.toList(), Process.is64Bit())
        assertTrue(state.abi == architecture?.abi)
        // Calls the saved native core through the production updater and JNA loader.
        uniffiEnsureInitialized()
    }
}
