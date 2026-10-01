package dev.wristline.watch.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * What a vibration means, the same on every screen: felt alone, it tells what happened. One event
 * plays one of these, once. Scrolling's haptics are the library's (rotary), not these.
 */
enum class Haptic {
    /** A request arrived, or a session waits for input: two crisp taps. */
    ATTENTION,

    /** A task finished, or a Quick Ask answer arrived: a soft rise. */
    DONE,

    /** A send was refused or failed, or a question ended in an error: three short taps. */
    ERROR,

    /** A prompt or an answer went out: one confirming click. */
    CONFIRM,

    /** A request was denied: a falling tap. */
    REJECT,

    /** A switch between two choices (the Quick Ask provider): a light tick. */
    SEGMENT,
}

/** One primitive of a composition: [id] a `VibrationEffect.Composition.PRIMITIVE_*`, at [scale], [delayMs] after the one before. */
internal data class HapticPrimitive(val id: Int, val scale: Float, val delayMs: Int = 0)

/** What plays when the vibrator lacks one of a pattern's primitives. */
internal sealed interface HapticFallback {
    /** A `VibrationEffect.EFFECT_*`. */
    data class Predefined(val effect: Int) : HapticFallback

    /** Alternating off and on times in ms, starting with off, as `VibrationEffect.createWaveform` takes them. */
    data class Waveform(val timings: List<Long>) : HapticFallback
}

/** A [Haptic] as composed [primitives], or [fallback] on a vibrator that does not support all of them. */
internal data class HapticPattern(val primitives: List<HapticPrimitive>, val fallback: HapticFallback)

private const val CLICK = VibrationEffect.Composition.PRIMITIVE_CLICK
private const val TICK = VibrationEffect.Composition.PRIMITIVE_TICK
private const val QUICK_RISE = VibrationEffect.Composition.PRIMITIVE_QUICK_RISE
private const val QUICK_FALL = VibrationEffect.Composition.PRIMITIVE_QUICK_FALL
private val PRIMITIVE_NAMES = mapOf(CLICK to "click", TICK to "tick", QUICK_RISE to "quick_rise", QUICK_FALL to "quick_fall")

internal fun hapticPattern(haptic: Haptic): HapticPattern = when (haptic) {
    Haptic.ATTENTION -> HapticPattern(
        listOf(HapticPrimitive(CLICK, 1f), HapticPrimitive(CLICK, 1f, delayMs = 100)),
        HapticFallback.Predefined(VibrationEffect.EFFECT_DOUBLE_CLICK),
    )
    // The rise, then a light click at its top.
    Haptic.DONE -> HapticPattern(
        listOf(HapticPrimitive(QUICK_RISE, 0.5f), HapticPrimitive(CLICK, 0.5f)),
        HapticFallback.Predefined(VibrationEffect.EFFECT_TICK),
    )
    // No predefined effect has three pulses; plain on-off timings work on every vibrator.
    Haptic.ERROR -> HapticPattern(
        listOf(HapticPrimitive(TICK, 1f), HapticPrimitive(TICK, 1f, delayMs = 60), HapticPrimitive(TICK, 1f, delayMs = 60)),
        HapticFallback.Waveform(listOf(0, 30, 60, 30, 60, 30)),
    )
    Haptic.CONFIRM -> HapticPattern(listOf(HapticPrimitive(CLICK, 0.8f)), HapticFallback.Predefined(VibrationEffect.EFFECT_CLICK))
    Haptic.REJECT -> HapticPattern(listOf(HapticPrimitive(QUICK_FALL, 0.8f)), HapticFallback.Predefined(VibrationEffect.EFFECT_HEAVY_CLICK))
    Haptic.SEGMENT -> HapticPattern(listOf(HapticPrimitive(TICK, 0.5f)), HapticFallback.Predefined(VibrationEffect.EFFECT_TICK))
}

/** Whether the pattern plays composed on a vibrator that supports the [supported] primitives. */
internal fun HapticPattern.playsComposed(supported: Set<Int>): Boolean = primitives.all { it.id in supported }

/** Every primitive the patterns use: the vibrator is asked about these once. */
internal val HAPTIC_PRIMITIVES: List<Int> = Haptic.entries.flatMap { hapticPattern(it).primitives.map(HapticPrimitive::id) }.distinct().sorted()

/** A prompt's outcome: [Haptic.CONFIRM] when it went out, [Haptic.ERROR] otherwise. */
internal fun sentHaptic(sent: Sent): Haptic = if (sent == Sent.Ok) Haptic.CONFIRM else Haptic.ERROR

/**
 * An `ask` event's haptic: [Haptic.DONE] for the answer to a question the watch knew as running
 * ([before], its status until now), [Haptic.ERROR] when it failed instead; nothing for the user's
 * own cancel, a repeated event, or an ask the watch did not know.
 */
internal fun askHaptic(before: String?, event: ServerEvent.AskChanged): Haptic? {
    if (before != AskStatus.RUNNING) return null
    return when (event.status) {
        AskStatus.DONE -> Haptic.DONE
        AskStatus.ERROR -> Haptic.ERROR.takeIf { event.error != "cancelled" }
        else -> null
    }
}

/**
 * Plays [Haptic]s: composed from primitives where the vibrator supports them, else each pattern's
 * fallback. The vibrator is asked once which primitives it supports.
 */
object Haptics {
    private const val TAG = "Wristline"

    @Volatile
    private var supported: Set<Int>? = null

    /** For what the bridge reported (a request, an alert, an answer): follows the notification vibration setting. */
    fun event(context: Context, haptic: Haptic) = play(context, haptic, VibrationAttributes.USAGE_NOTIFICATION)

    /** For what the user just did on screen: follows the touch feedback setting. */
    fun touch(context: Context, haptic: Haptic) = play(context, haptic, VibrationAttributes.USAGE_TOUCH)

    private fun play(context: Context, haptic: Haptic, usage: Int) {
        val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
        if (!vibrator.hasVibrator()) return
        val pattern = hapticPattern(haptic)
        val effect = if (pattern.playsComposed(supported(vibrator))) {
            VibrationEffect.startComposition()
                .apply { pattern.primitives.forEach { addPrimitive(it.id, it.scale, it.delayMs) } }
                .compose()
        } else {
            when (val fallback = pattern.fallback) {
                is HapticFallback.Predefined -> VibrationEffect.createPredefined(fallback.effect)
                is HapticFallback.Waveform -> VibrationEffect.createWaveform(fallback.timings.toLongArray(), -1)
            }
        }
        vibrator.vibrate(effect, VibrationAttributes.createForUsage(usage))
    }

    // The ids are the patterns' PRIMITIVE_* constants, which lint cannot follow through the list.
    @SuppressLint("WrongConstant")
    private fun supported(vibrator: Vibrator): Set<Int> = supported ?: run {
        val ids = HAPTIC_PRIMITIVES.toIntArray()
        val flags = vibrator.arePrimitivesSupported(*ids)
        // Which watches compose them is not known yet: logged once per process, no payload.
        Log.d(TAG, "Haptic primitives: " + ids.indices.joinToString(" ") { "${PRIMITIVE_NAMES[ids[it]]}=${flags[it]}" })
        ids.filterIndexed { i, _ -> flags[i] }.toSet().also { supported = it }
    }
}
