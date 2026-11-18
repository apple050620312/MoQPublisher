package com.sangege.moqpublisher

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamPresetTest {
    private class MemoryPreferences {
        val values = mutableMapOf<String, Any?>()
        private val pending = mutableMapOf<String, Any?>()
        private val editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)
        ) { proxy, method, args ->
            when {
                method.name == "putString" -> { pending[args[0] as String] = args[1]; proxy }
                method.name == "remove" -> { pending[args[0] as String] = null; proxy }
                method.name == "clear" -> { values.clear(); proxy }
                method.name == "commit" -> { applyPending(); true }
                method.name == "apply" -> { applyPending(); Unit }
                else -> error("Unexpected editor method ${method.name}")
            }
        } as SharedPreferences.Editor
        val prefs = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getString" -> values[args[0] as String] ?: args[1]
                "contains" -> values.containsKey(args[0] as String)
                "edit" -> editor
                else -> error("Unexpected preferences method ${method.name}")
            }
        } as SharedPreferences

        private fun applyPending() {
            for ((key, value) in pending) if (value == null) values.remove(key) else values[key] = value
            pending.clear()
        }
    }

    @Test
    fun builtInPresetsAreValidAndCoverRequestedModes() {
        val presets = StreamPresetPolicy.builtIns()
        assertEquals(4, presets.size)
        assertTrue(presets.all { it.builtIn && StreamPresetPolicy.validate(it) == null })
        assertTrue(presets.any { it.videoCodec == "H264" && it.fps == 60 })
    }

    @Test
    fun customPresetRoundTripsAndCanBeUpdatedOrDeleted() {
        val memory = MemoryPreferences()
        val repository = StreamPresetRepository(memory.prefs)
        val saved = repository.saveCustom(StreamPresetPolicy.builtIns().first().copy(name = "我的 / mobile"))
        assertEquals(saved, repository.loadCustom().single())

        val updated = repository.saveCustom(saved.copy(name = "Updated", fps = 24), saved.id)
        assertEquals(updated, repository.loadCustom().single())
        assertTrue(repository.delete(saved.id))
        assertTrue(repository.loadCustom().isEmpty())
        assertFalse(repository.delete(saved.id))
    }

    @Test
    fun malformedOrUnsupportedCustomPresetIsIgnored() {
        val memory = MemoryPreferences()
        memory.values["stream_presets_v1"] = "[{\"id\":\"bad\",\"name\":\"bad\",\"codec\":\"AV1\",\"width\":1}]"
        assertTrue(StreamPresetRepository(memory.prefs).loadCustom().isEmpty())
        assertEquals("codec", StreamPresetPolicy.validate(StreamPresetPolicy.builtIns().first().copy(videoCodec = "AV1")))
    }
}
