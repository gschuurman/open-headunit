package com.andrerinas.openheadunit.utils

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.decoder.audio.AudioRoute
import com.andrerinas.openheadunit.decoder.audio.AudioStreamCatalog
import com.andrerinas.openheadunit.decoder.audio.AudioTestTone
import com.andrerinas.openheadunit.decoder.audio.AutomotiveAudioCatalog

/**
 * Naming and audition for the output streams a channel can be played on.
 *
 * The list comes from [AudioStreamCatalog], so it is whatever this device actually has rather than
 * a fixed set - a head unit with a vendor stream gets it offered, under the name its own framework
 * reports. The whole point of the picker is that the right answer is head-unit specific and cannot
 * be reasoned about from here: on many units the amplifier only unmutes for some streams, so the
 * only way to know is to hear one. This plays a tone on the stream the user is currently looking
 * at, before the choice is saved and long before a phone is connected.
 *
 * On Android Automotive the choices are usages from [AutomotiveAudioCatalog] instead of streams:
 * the car routes by usage, so that is what decides which bus - and which amplifier channel - a
 * channel ends up on. Each one is labelled with the output the head unit reports for it.
 */
object AudioStreamTester {

    /**
     * How long the test tone sounds.
     *
     * A full second, because the failure this button exists to find is an amplifier that does not
     * unmute for a stream, and car amplifiers routinely take a few hundred milliseconds to wake
     * after audio starts. Anything shorter can be swallowed whole by an amp still coming up, which
     * reads exactly like a stream that does not work.
     */
    private const val TONE_MS = 1000

    // One tone at a time. Comparing two streams means tapping one speaker button then the next,
    // and at a full second each the second tap would otherwise land on top of the first - two
    // tones at once tells you nothing about either, and the platform hands out a limited number
    // of generators, so the second tap could fail outright. Touched only from the UI thread.
    private var activeTone: ToneGenerator? = null
    // A usage route has no ToneGenerator; its tone is a static AudioTrack built with that usage.
    private var activeTrack: AudioTrack? = null
    private val handler = Handler(Looper.getMainLooper())
    private val releaseRunnable = Runnable { stopActiveTone() }

    private fun audioManager(context: Context): AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** Silences and frees whatever is playing, if anything. */
    private fun stopActiveTone() {
        handler.removeCallbacks(releaseRunnable)
        activeTone?.let {
            try {
                it.stopTone()
                it.release()
            } catch (e: RuntimeException) {
                AppLog.e("AudioStreamTester: failed to release the tone generator", e)
            }
        }
        activeTone = null
        activeTrack?.let {
            try {
                it.stop()
            } catch (e: IllegalStateException) {
                // Already stopped; release below is what matters.
            }
            it.release()
        }
        activeTrack = null
    }

    /** True when the picker offers Android Automotive usages rather than streams. */
    fun isAutomotive(context: Context): Boolean =
        AutomotiveAudioCatalog.isAutomotive(context) &&
            AutomotiveAudioCatalog.available(audioManager(context)).isNotEmpty()

    /** Streams this device accepts, in picker order. */
    fun streams(context: Context): List<AudioStreamCatalog.Entry> =
        AudioStreamCatalog.available(audioManager(context))

    /** Every route the picker offers, in order: usages on Android Automotive, streams elsewhere. */
    private fun routes(context: Context): List<Int> =
        if (isAutomotive(context)) AutomotiveAudioCatalog.available(audioManager(context)).map { it.route }
        else streams(context).map { it.id }

    /** Picker labels, in the same order as the routes. */
    fun labels(context: Context): Array<String> =
        routes(context).map { label(context, it) }.toTypedArray()

    /**
     * Position of a stored route in the picker, or 0 when this device does not have it. On
     * Android Automotive a stream stored before usages were offered (the defaults, too) selects
     * the usage the platform maps that stream to, since that is where it plays today.
     */
    fun indexOf(context: Context, stream: Int): Int {
        val routes = routes(context)
        val index = routes.indexOf(stream)
        if (index >= 0) return index
        if (isAutomotive(context) && !AudioRoute.isUsage(stream)) {
            AudioRoute.usageRouteForStream(stream)?.let { return routes.indexOf(it).coerceAtLeast(0) }
        }
        return 0
    }

    fun streamAt(context: Context, index: Int): Int =
        routes(context).getOrNull(index) ?: AudioManager.STREAM_MUSIC

    /**
     * How a stored stream reads in a row. A stream the device does not have still gets a label
     * rather than being silently shown as something else - that is what a settings backup carried
     * over from another head unit looks like, and it needs to be visible.
     */
    fun label(context: Context, stream: Int): String {
        if (AudioRoute.isUsage(stream)) return usageLabel(context, stream)
        val entry = streams(context).firstOrNull { it.id == stream }
            ?: return context.getString(R.string.audio_stream_unavailable_format, stream)
        return label(context, entry)
    }

    private fun label(context: Context, entry: AudioStreamCatalog.Entry): String {
        if (entry.isStandard) {
            standardNameRes(entry.id)?.let { return context.getString(it) }
        }
        val name = entry.constantName
            ?: return context.getString(R.string.audio_stream_unnamed_format, entry.id)
        // Vendor streams keep their platform name and carry the number, because that is how they
        // appear in logs and in whatever the head unit's own tooling calls them.
        return context.getString(R.string.audio_stream_vendor_format, name, entry.id)
    }

    /**
     * A usage, with the outputs the head unit reported for it so channels sharing a bus are
     * visible as such. A usage this platform cannot play reads as unavailable, like a stream.
     */
    private fun usageLabel(context: Context, route: Int): String {
        val usage = AudioRoute.usage(route)
        if (usage == null || !AudioRoute.isPlayableUsage(route, Build.VERSION.SDK_INT)) {
            return context.getString(R.string.audio_usage_unavailable_format, AudioRoute.usageOf(route))
        }
        val name = usageNameRes(usage.id)?.let { context.getString(it) } ?: usage.name
        val outputs = AutomotiveAudioCatalog.entry(audioManager(context), route)?.outputs.orEmpty()
        if (outputs.isEmpty()) return name
        return context.getString(R.string.audio_usage_output_format, name, outputs.joinToString())
    }

    /** How many of this device's streams are not stock Android. Streams are not offered on AAOS. */
    fun vendorStreamCount(context: Context): Int =
        if (isAutomotive(context)) 0 else streams(context).count { !it.isStandard }

    private fun usageNameRes(usage: Int): Int? = when (usage) {
        1 -> R.string.audio_usage_media
        2 -> R.string.audio_usage_voice_communication
        3 -> R.string.audio_usage_voice_communication_signalling
        4 -> R.string.audio_usage_alarm
        5 -> R.string.audio_usage_notification
        6 -> R.string.audio_usage_notification_ringtone
        10 -> R.string.audio_usage_notification_event
        11 -> R.string.audio_usage_assistance_accessibility
        12 -> R.string.audio_usage_navigation_guidance
        13 -> R.string.audio_usage_assistance_sonification
        14 -> R.string.audio_usage_game
        16 -> R.string.audio_usage_assistant
        else -> null
    }

    private fun standardNameRes(id: Int): Int? = when (id) {
        AudioManager.STREAM_VOICE_CALL -> R.string.audio_stream_voice_call
        AudioManager.STREAM_SYSTEM -> R.string.audio_stream_system
        AudioManager.STREAM_RING -> R.string.audio_stream_ring
        AudioManager.STREAM_MUSIC -> R.string.audio_stream_music
        AudioManager.STREAM_ALARM -> R.string.audio_stream_alarm
        AudioManager.STREAM_NOTIFICATION -> R.string.audio_stream_notification
        6 -> R.string.audio_stream_bluetooth_sco
        7 -> R.string.audio_stream_system_enforced
        AudioManager.STREAM_DTMF -> R.string.audio_stream_dtmf
        9 -> R.string.audio_stream_tts
        10 -> R.string.audio_stream_accessibility
        11 -> R.string.audio_stream_assistant
        else -> null
    }

    /**
     * Plays a short tone on [stream] and says so when the stream is silenced, because a muted
     * stream and a stream the head unit never unmutes sound identical and only one of them is
     * worth changing the setting over.
     */
    fun play(context: Context, stream: Int) {
        val audioManager = audioManager(context)
        val volume = try {
            audioManager.getStreamVolume(volumeStream(stream))
        } catch (e: RuntimeException) {
            // The device does not have this stream - a stored value from another head unit.
            AppLog.e("AudioStreamTester: stream $stream is not available on this device", e)
            ToastUtils.showToast(context, R.string.audio_stream_test_failed, Toast.LENGTH_SHORT, force = true)
            return
        }
        if (volume == 0) {
            ToastUtils.showToast(
                context,
                context.getString(R.string.audio_stream_muted_warning, label(context, stream)),
                Toast.LENGTH_LONG,
                force = true
            )
        }

        // Whatever the last tap started stops here, so the two are heard one after the other.
        stopActiveTone()

        if (AudioRoute.isUsage(stream)) {
            playUsage(context, stream)
            return
        }

        val tone = try {
            ToneGenerator(stream, ToneGenerator.MAX_VOLUME)
        } catch (e: RuntimeException) {
            // The platform hands out a limited number of tone generators and refuses when the
            // audio hardware is busy; a failed audition must not take the settings screen down.
            AppLog.e("AudioStreamTester: tone generator unavailable for stream $stream", e)
            ToastUtils.showToast(context, R.string.audio_stream_test_failed, Toast.LENGTH_SHORT, force = true)
            return
        }

        // A continuous tone, so TONE_MS is what actually governs the length. Tones that are
        // "limited in time by definition" - TONE_PROP_BEEP2 and the rest of the UI beeps - ignore
        // the duration and stop at their own, which for BEEP2 is 70ms of sound inside 270ms.
        // 350Hz+440Hz is also low enough to carry through car speakers, where a DTMF pair is shrill.
        tone.startTone(ToneGenerator.TONE_SUP_DIAL, TONE_MS)
        activeTone = tone
        // Released on a delay rather than immediately: releasing while the tone plays cuts it off.
        handler.postDelayed(releaseRunnable, (TONE_MS + 300).toLong())
    }

    /** The stream whose volume a route follows. Usages report theirs from API 26. */
    private fun volumeStream(route: Int): Int {
        val usage = AudioRoute.usage(route) ?: return AudioRoute.legacyStream(route)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                return usageAttributes(usage).volumeControlStream
            } catch (e: RuntimeException) {
                // Fall through to the AOSP default below.
            }
        }
        return usage.legacyStream
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun usageAttributes(usage: AudioRoute.Usage): AudioAttributes =
        AudioAttributes.Builder().setUsage(usage.id).setContentType(usage.contentType).build()

    /**
     * The test tone for a usage route. ToneGenerator only takes a stream, which on Android
     * Automotive would be routed by the stream's usage rather than the one being auditioned.
     */
    private fun playUsage(context: Context, route: Int) {
        val usage = AudioRoute.usage(route)
        if (usage == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            ToastUtils.showToast(context, R.string.audio_stream_test_failed, Toast.LENGTH_SHORT, force = true)
            return
        }
        val samples = AudioTestTone.stereo(TONE_MS / 1000.0)
        var track: AudioTrack? = null
        try {
            track = AudioTrack(
                usageAttributes(usage),
                AudioFormat.Builder()
                    .setSampleRate(AudioTestTone.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
                samples.size * 2,
                AudioTrack.MODE_STATIC,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            check(track.write(samples, 0, samples.size) > 0) { "static tone was not accepted" }
            track.play()
        } catch (e: RuntimeException) {
            track?.release()
            AppLog.e("AudioStreamTester: test tone unavailable for usage ${usage.name}", e)
            ToastUtils.showToast(context, R.string.audio_stream_test_failed, Toast.LENGTH_SHORT, force = true)
            return
        }
        activeTrack = track
        handler.postDelayed(releaseRunnable, (TONE_MS + 300).toLong())
    }
}
