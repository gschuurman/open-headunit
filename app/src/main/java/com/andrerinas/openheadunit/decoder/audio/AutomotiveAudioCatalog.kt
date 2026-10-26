package com.andrerinas.openheadunit.decoder.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.annotation.RequiresApi
import com.andrerinas.openheadunit.utils.AppLog

/**
 * The audio channels an Android Automotive (AAOS) head unit offers, discovered at runtime.
 *
 * AAOS does not route by stream. CarAudioService maps every [AudioAttributes] usage to a car audio
 * context (MUSIC, NAVIGATION, VOICE_COMMAND, CALL, ...), each context to a volume group, and each
 * group to an output device - normally a `TYPE_BUS` device whose address names the context it
 * carries, such as `bus1_navigation_out`. Which contexts share a bus is the OEM's
 * `car_audio_configuration.xml`, so it differs per car and cannot be written down here.
 *
 * What can be found out from an app:
 *  1. Which usages this platform accepts: [AudioAttributes.Builder.setUsage] rejects the ones it
 *     does not know, so each candidate in [AudioRoute.USAGES] is probed rather than assumed.
 *  2. Where each usage plays: from API 33 [AudioManager.getAudioDevicesForAttributes] answers
 *     directly. Below that the output buses are listed and matched to usages by the context name
 *     in their address, which is the AOSP reference naming most OEMs keep.
 *
 * The car's volume groups themselves sit behind CarAudioManager, whose calls need a signature
 * permission, so they are not used.
 */
object AutomotiveAudioCatalog {

    /** One usage the head unit accepts, and the outputs it was found to play on. */
    data class Entry(
        val route: Int,
        val usage: AudioRoute.Usage,
        /** Output addresses (or names), empty when the platform would not say. */
        val outputs: List<String>
    )

    private const val FEATURE_AUTOMOTIVE = "android.hardware.type.automotive"

    /** `AudioDeviceInfo.TYPE_BUS`, API 24. */
    private const val TYPE_BUS = 21

    /** AAOS bus address convention: `bus<N>_<context>_out`. */
    private val BUS_ADDRESS = Regex("^bus\\d+_(.+)_out$")

    /** Car audio context each usage belongs to, as CarAudioContext groups them. */
    private val CONTEXT_FOR_USAGE = mapOf(
        1 to "media",
        14 to "media",
        12 to "navigation",
        16 to "voice_command",
        11 to "voice_command",
        6 to "call_ring",
        2 to "call",
        3 to "call",
        4 to "alarm",
        5 to "notification",
        10 to "notification",
        13 to "system_sound"
    )

    @Volatile
    private var automotive: Boolean? = null

    @Volatile
    private var cached: List<Entry>? = null

    /** True on an Android Automotive head unit. */
    fun isAutomotive(context: Context): Boolean {
        automotive?.let { return it }
        val result = try {
            context.packageManager.hasSystemFeature(FEATURE_AUTOMOTIVE)
        } catch (e: RuntimeException) {
            false
        }
        automotive = result
        return result
    }

    /**
     * Every usage the head unit accepts, in [AudioRoute.USAGES] order. Computed once - the car
     * audio configuration is read at boot and does not change while the process lives.
     */
    fun available(audioManager: AudioManager): List<Entry> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val discovered = discover(audioManager)
            cached = discovered
            return discovered
        }
    }

    /** The entry for [route], or null when it is not a usage this head unit accepts. */
    fun entry(audioManager: AudioManager, route: Int): Entry? =
        available(audioManager).firstOrNull { it.route == route }

    private fun discover(audioManager: AudioManager): List<Entry> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return emptyList()

        val buses = outputBuses(audioManager)
        val entries = AudioRoute.USAGES.mapNotNull { usage ->
            if (Build.VERSION.SDK_INT < usage.sinceApi) return@mapNotNull null
            val attributes = try {
                AudioAttributes.Builder()
                    .setUsage(usage.id)
                    .setContentType(usage.contentType)
                    .build()
            } catch (e: IllegalArgumentException) {
                return@mapNotNull null
            }
            Entry(AudioRoute.ofUsage(usage.id), usage, outputsFor(audioManager, attributes, usage, buses))
        }

        AppLog.i("AutomotiveAudioCatalog: ${entries.size} usages available, " +
                "buses=${if (buses.isEmpty()) "none" else buses.joinToString()}: " +
                entries.joinToString { "${it.usage.name}->${it.outputs.joinToString("+").ifEmpty { "?" }}" })
        return entries
    }

    /** Addresses of the output buses CarAudioService plays on. Empty below API 28 or off AAOS. */
    private fun outputBuses(audioManager: AudioManager): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return emptyList()
        return try {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .filter { it.type == TYPE_BUS && it.address.isNotEmpty() }
                .map { it.address }
                .distinct()
        } catch (e: RuntimeException) {
            AppLog.w("AutomotiveAudioCatalog: output devices unavailable: ${e.message}")
            emptyList()
        }
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun outputsFor(
        audioManager: AudioManager,
        attributes: AudioAttributes,
        usage: AudioRoute.Usage,
        buses: List<String>
    ): List<String> {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                val devices = audioManager.getAudioDevicesForAttributes(attributes)
                if (devices.isNotEmpty()) return devices.map(::describe).distinct()
            } catch (e: Throwable) {
                // SecurityException on builds that keep it behind MODIFY_AUDIO_ROUTING, and a
                // vendor framework is free to throw something else. The bus names still answer.
                AppLog.w("AutomotiveAudioCatalog: routing query failed for ${usage.name}: ${e.message}")
            }
        }
        val context = CONTEXT_FOR_USAGE[usage.id] ?: return emptyList()
        return buses.filter { BUS_ADDRESS.find(it)?.groupValues?.get(1) == context }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun describe(device: AudioDeviceInfo): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && device.address.isNotEmpty()) return device.address
        return device.productName?.toString()?.takeIf { it.isNotEmpty() } ?: "type ${device.type}"
    }
}
