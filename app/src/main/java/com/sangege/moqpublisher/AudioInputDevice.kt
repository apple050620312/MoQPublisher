package com.sangege.moqpublisher

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

const val DEFAULT_AUDIO_INPUT_DEVICE_ID = -1

data class AudioInputDevice(
    val id: Int,
    val type: Int,
    val name: String
)

/** Android audio input types that can be selected by the user. */
internal object AudioInputDevicePolicy {
    fun isSupportedType(type: Int): Boolean = type in setOf(
        AudioDeviceInfo.TYPE_BUILTIN_MIC,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET
    )

    fun sortKey(device: AudioInputDevice): String = device.name.lowercase()
}

internal class AudioInputDeviceRepository(context: Context) {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)

    fun list(): List<AudioInputDevice> = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        .filter { AudioInputDevicePolicy.isSupportedType(it.type) }
        .map { info ->
            AudioInputDevice(
                id = info.id,
                type = info.type,
                name = info.productName?.toString()?.takeIf { it.isNotBlank() } ?: typeName(info.type)
            )
        }
        .distinctBy { it.id }
        .sortedWith(compareBy<AudioInputDevice> { it.type != AudioDeviceInfo.TYPE_BUILTIN_MIC }
            .thenBy { AudioInputDevicePolicy.sortKey(it) })

    fun find(id: Int): AudioDeviceInfo? = if (id == DEFAULT_AUDIO_INPUT_DEVICE_ID) null else
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
            it.id == id && AudioInputDevicePolicy.isSupportedType(it.type)
        }

    private fun typeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in microphone"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB microphone"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth microphone"
        else -> "Audio input"
    }
}
