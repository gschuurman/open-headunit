package com.andrerinas.openheadunit.decoder.audio

/**
 * Where a channel's audio is handed to the platform: a legacy stream id, or an [AudioAttributes]
 * usage.
 *
 * On Android Automotive (AAOS) the stream picker answers the wrong question. The car audio service
 * routes by usage - each usage belongs to a car audio context, each context to a volume group and
 * an output bus - and a legacy stream only reaches it through the platform's fixed stream-to-usage
 * table. Some usages are not reachable that way at all: no stream maps to navigation guidance, so a
 * guidance channel on a legacy stream can never land on the bus the car's navigation is wired to.
 *
 * Both kinds share the one stored setting per channel, so a route is an Int: stream ids as they
 * always were, usages offset by [USAGE_BASE]. Existing settings keep their meaning, and a usage
 * route can never collide with a stream id, vendor streams included.
 *
 * Plain Kotlin with the platform constants written out, so it can be shared by the output, focus
 * and settings code and checked without a device.
 *
 * [AudioAttributes]: android.media.AudioAttributes
 */
object AudioRoute {

    /** Offset of usage routes. Far above any stream id a device has ever reported. */
    const val USAGE_BASE = 0x10000

    // AudioAttributes.CONTENT_TYPE_*
    const val CONTENT_TYPE_SPEECH = 1
    const val CONTENT_TYPE_MUSIC = 2
    const val CONTENT_TYPE_SONIFICATION = 4

    private const val STREAM_VOICE_CALL = 0
    private const val STREAM_SYSTEM = 1
    private const val STREAM_RING = 2
    private const val STREAM_MUSIC = 3
    private const val STREAM_ALARM = 4
    private const val STREAM_NOTIFICATION = 5
    private const val STREAM_DTMF = 8

    /**
     * A playback usage an app may set without a system permission.
     *
     * [legacyStream] is the stream AOSP's default product strategies map the usage to. It is only
     * used where the platform has no usage-based call: the pre-Marshmallow AudioTrack constructor,
     * pre-Oreo focus requests, and reading the volume the usage follows.
     */
    data class Usage(
        val id: Int,
        /** `AudioAttributes.USAGE_*` without the prefix. */
        val name: String,
        val contentType: Int,
        val legacyStream: Int,
        val sinceApi: Int
    )

    /**
     * Every public playback usage worth offering, in the order the picker lists them.
     *
     * Left out on purpose: UNKNOWN (plays as media), the deprecated NOTIFICATION_COMMUNICATION_*
     * variants (folded into NOTIFICATION by the platform), CALL_ASSISTANT (needs a privileged
     * permission to be heard), and the AAOS system usages EMERGENCY, SAFETY, VEHICLE_STATUS and
     * ANNOUNCEMENT, which only `setSystemUsage` with MODIFY_AUDIO_ROUTING can set.
     */
    val USAGES: List<Usage> = listOf(
        Usage(1, "MEDIA", CONTENT_TYPE_MUSIC, STREAM_MUSIC, 21),
        Usage(12, "ASSISTANCE_NAVIGATION_GUIDANCE", CONTENT_TYPE_SPEECH, STREAM_MUSIC, 21),
        Usage(16, "ASSISTANT", CONTENT_TYPE_SPEECH, STREAM_MUSIC, 26),
        Usage(2, "VOICE_COMMUNICATION", CONTENT_TYPE_SPEECH, STREAM_VOICE_CALL, 21),
        Usage(3, "VOICE_COMMUNICATION_SIGNALLING", CONTENT_TYPE_SONIFICATION, STREAM_DTMF, 21),
        Usage(6, "NOTIFICATION_RINGTONE", CONTENT_TYPE_SONIFICATION, STREAM_RING, 21),
        Usage(5, "NOTIFICATION", CONTENT_TYPE_SONIFICATION, STREAM_NOTIFICATION, 21),
        Usage(10, "NOTIFICATION_EVENT", CONTENT_TYPE_SONIFICATION, STREAM_NOTIFICATION, 21),
        Usage(4, "ALARM", CONTENT_TYPE_SONIFICATION, STREAM_ALARM, 21),
        Usage(13, "ASSISTANCE_SONIFICATION", CONTENT_TYPE_SONIFICATION, STREAM_SYSTEM, 21),
        Usage(11, "ASSISTANCE_ACCESSIBILITY", CONTENT_TYPE_SPEECH, STREAM_MUSIC, 21),
        Usage(14, "GAME", CONTENT_TYPE_MUSIC, STREAM_MUSIC, 21)
    )

    private val byId = USAGES.associateBy { it.id }

    fun isUsage(route: Int): Boolean = route >= USAGE_BASE

    fun ofUsage(usage: Int): Int = USAGE_BASE + usage

    /** The `AudioAttributes.USAGE_*` value of a usage route. Only meaningful when [isUsage]. */
    fun usageOf(route: Int): Int = route - USAGE_BASE

    /** The known usage behind [route], or null for a stream route or a usage not in [USAGES]. */
    fun usage(route: Int): Usage? = if (isUsage(route)) byId[usageOf(route)] else null

    /** True when [route] is a usage this platform version can play. */
    fun isPlayableUsage(route: Int, sdk: Int): Boolean {
        val usage = usage(route) ?: return false
        return sdk >= usage.sinceApi
    }

    /** The stream to fall back on where only a stream can be named; stream routes pass through. */
    fun legacyStream(route: Int): Int =
        if (isUsage(route)) usage(route)?.legacyStream ?: STREAM_MUSIC else route

    /** The usage a stream maps to in AOSP's default product strategies, as a usage route. */
    fun usageRouteForStream(stream: Int): Int? = when (stream) {
        STREAM_MUSIC -> ofUsage(1)
        STREAM_VOICE_CALL -> ofUsage(2)
        STREAM_DTMF -> ofUsage(3)
        STREAM_ALARM -> ofUsage(4)
        STREAM_NOTIFICATION -> ofUsage(5)
        STREAM_RING -> ofUsage(6)
        STREAM_SYSTEM -> ofUsage(13)
        else -> null
    }
}
