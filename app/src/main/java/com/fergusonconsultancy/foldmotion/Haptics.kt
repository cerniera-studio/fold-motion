package com.fergusonconsultancy.foldmotion

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.roundToInt

/**
 * Scatto (19 Nov 2026): the hinge's voice.
 *
 * Fold Motion already ticked as the blades started, clicked on the climax and settled on
 * opening, always at the same strength. Scatto shapes those same moments by how fast the
 * phone was folded (Concentrato's reading), so a flick is one firm snap and a slow close is
 * a long, low settle, like a well-made car door. Opening is the lighter latch.
 *
 * - Character: Portiera (a car door, weighted) or Corona (a watch crown, crisp detents).
 * - Strength: Lieve or Pieno.
 * - Close only: opening stays silent, to test whether the haptic turns to noise.
 *
 * Primitives are checked one by one; where THUD or LOW_TICK are missing the nearest one
 * stands in, and with no primitives at all an amplitude waveform is used. The haptics follow
 * the system's touch-vibration intensity (USAGE_TOUCH), so turning that off silences them.
 *
 * [slowness] everywhere is 0 for a flick, 1 for a slow fold, as Concentrato reads it.
 */
class Haptics(private val context: Context) {

    enum class Character(val label: String, val note: String) {
        PORTIERA("Portiera", "A car door: a weighted close, a light latch as it opens"),
        CORONA("Corona", "A watch crown: crisp detents, the same in both directions"),
    }

    enum class Strength(val label: String, val gain: Float) {
        LIEVE("Lieve", 0.6f),
        PIENO("Pieno", 1f),
    }

    enum class Scope(val label: String) {
        BOTH("Close and open"),
        CLOSE("Close only"),
    }

    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
    }.getOrNull()

    private fun supports(p: Int): Boolean = runCatching {
        vibrator?.areAllPrimitivesSupported(p) == true
    }.getOrDefault(false)

    private val hasClick = supports(VibrationEffect.Composition.PRIMITIVE_CLICK)
    private val hasTick = supports(VibrationEffect.Composition.PRIMITIVE_TICK)
    private val hasLowTick = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && supports(VibrationEffect.Composition.PRIMITIVE_LOW_TICK)
    private val hasThud = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && supports(VibrationEffect.Composition.PRIMITIVE_THUD)
    private val primitives = hasClick && hasTick

    val enabled: Boolean get() = isEnabled(context)

    private val character get() = character(context)
    private val gain get() = strength(context).gain
    private val opening get() = scope(context) == Scope.BOTH

    // region The fold's moments (called by the service, timed to the light)

    /** Closing, as the blades start. Only a slow close is felt here; a flick waits for the snap. */
    fun tick(slowness: Float = 0.5f) {
        if (slowness < FAST) return
        when (character) {
            Character.PORTIERA -> compose(listOf(Step(LOW, 0.30f + 0.15f * slowness)))
            Character.CORONA -> compose(listOf(Step(TICK, 0.45f)))
        }
    }

    /** Closing, on the climax: the door shutting. A flick snaps; a slow close settles. */
    fun click(slowness: Float = 0.5f) = compose(closeSteps(slowness))

    /** Opening, as the inner screen arrives: a soft low settle. */
    fun settle(slowness: Float = 0.5f) {
        if (!opening) return
        when (character) {
            Character.PORTIERA -> compose(listOf(Step(LOW, 0.45f + 0.25f * slowness)))
            Character.CORONA -> if (slowness >= FAST) compose(listOf(Step(TICK, 0.35f)))
        }
    }

    /** Opening, on the reveal's climax: the latch letting go, lighter than the close. */
    fun release(slowness: Float = 0.5f) {
        if (!opening) return
        compose(
            when (character) {
                Character.PORTIERA -> if (slowness < FAST) listOf(Step(CLICK, 0.6f))
                    else listOf(Step(TICK, 0.3f), Step(CLICK, 0.5f, 50))
                Character.CORONA -> listOf(Step(TICK, 0.6f))
            }
        )
    }

    // endregion

    /** For "Feel it" in settings: the whole close (start, then contact) or the whole open. */
    fun preview(close: Boolean, slowness: Float, after: (Long, () -> Unit) -> Unit) {
        if (close) {
            tick(slowness)
            after(if (slowness < FAST) 0L else (180 + 260 * slowness).toLong()) { click(slowness) }
        } else {
            settle(slowness)
            after((240 + 200 * slowness).toLong()) { release(slowness) }
        }
    }

    private fun closeSteps(s: Float): List<Step> = when (character) {
        Character.PORTIERA -> when {
            s < FAST -> listOf(Step(CLICK, 1f), Step(THUD, 0.45f, 10))
            else -> listOf(Step(THUD, 0.55f + 0.4f * s), Step(LOW, 0.25f + 0.3f * s, (40 + 50 * s).roundToInt()))
        }
        Character.CORONA -> when {
            s < FAST -> listOf(Step(CLICK, 0.9f))
            else -> listOf(Step(TICK, 0.55f), Step(TICK, 0.55f, 45), Step(CLICK, 0.8f, 45))
        }
    }

    // region Playing

    private data class Step(val kind: Int, val scale: Float, val delay: Int = 0)

    private fun compose(steps: List<Step>) {
        if (steps.isEmpty()) return
        val g = gain
        val effect = if (primitives) {
            val c = VibrationEffect.startComposition()
            steps.forEach { st -> c.addPrimitive(resolve(st.kind), (st.scale * g).coerceIn(0f, 1f), st.delay) }
            c.compose()
        } else waveform(steps, g)
        play(effect)
    }

    /** The nearest primitive this phone has: THUD falls back to CLICK, LOW_TICK to TICK. */
    private fun resolve(kind: Int): Int = when (kind) {
        THUD -> if (hasThud) VibrationEffect.Composition.PRIMITIVE_THUD else VibrationEffect.Composition.PRIMITIVE_CLICK
        LOW -> if (hasLowTick) VibrationEffect.Composition.PRIMITIVE_LOW_TICK else VibrationEffect.Composition.PRIMITIVE_TICK
        CLICK -> VibrationEffect.Composition.PRIMITIVE_CLICK
        else -> VibrationEffect.Composition.PRIMITIVE_TICK
    }

    /** No primitives: short pulses, longer and softer for the low ones. */
    private fun waveform(steps: List<Step>, g: Float): VibrationEffect {
        val times = ArrayList<Long>()
        val amps = ArrayList<Int>()
        steps.forEach { st ->
            times += st.delay.toLong(); amps += 0
            times += when (st.kind) { THUD -> 26L; LOW -> 18L; CLICK -> 12L; else -> 8L }
            amps += (255 * st.scale * g).roundToInt().coerceIn(1, 255)
        }
        return VibrationEffect.createWaveform(times.toLongArray(), amps.toIntArray(), -1)
    }

    private fun play(effect: VibrationEffect) {
        val v = vibrator ?: return
        if (!enabled || !v.hasVibrator()) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
            } else {
                v.vibrate(effect)
            }
        }
    }

    // endregion

    /** What this phone's motor offers, for Settings and Diagnostics (the page's first step). */
    fun report(): String {
        val v = vibrator ?: return "No vibrator found"
        if (!v.hasVibrator()) return "This phone reports no vibrator"
        val names = listOf(
            "CLICK" to VibrationEffect.Composition.PRIMITIVE_CLICK,
            "TICK" to VibrationEffect.Composition.PRIMITIVE_TICK,
            "LOW_TICK" to VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
            "THUD" to VibrationEffect.Composition.PRIMITIVE_THUD,
            "SPIN" to VibrationEffect.Composition.PRIMITIVE_SPIN,
            "QUICK_RISE" to VibrationEffect.Composition.PRIMITIVE_QUICK_RISE,
            "SLOW_RISE" to VibrationEffect.Composition.PRIMITIVE_SLOW_RISE,
            "QUICK_FALL" to VibrationEffect.Composition.PRIMITIVE_QUICK_FALL,
        )
        val have = names.filter { supports(it.second) }.map { it.first }
        val miss = names.map { it.first } - have.toSet()
        return buildString {
            append("Primitives: ").append(if (have.isEmpty()) "none" else have.joinToString(" "))
            if (miss.isNotEmpty()) append("\nMissing: ").append(miss.joinToString(" "))
            append("\nAmplitude control: ").append(if (v.hasAmplitudeControl()) "yes" else "no")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val f = v.resonantFrequency
                if (!f.isNaN()) append(", resonance ${f.roundToInt()} Hz")
            }
            append("\nPlaying as: ").append(
                when {
                    !primitives -> "waveforms (no primitives)"
                    hasThud && hasLowTick -> "primitives, in full"
                    else -> "primitives, nearest stand-ins for " + listOfNotNull(if (!hasThud) "THUD" else null, if (!hasLowTick) "LOW_TICK" else null).joinToString(", ")
                }
            )
        }
    }

    companion object {
        private const val PREFS = "fold_motion"
        private const val KEY = "haptics"
        private const val K_CHAR = "scatto_character"
        private const val K_STRENGTH = "scatto_strength"
        private const val K_SCOPE = "scatto_scope"

        /** Below this slowness a fold counts as a flick. */
        const val FAST = 0.35f

        private const val CLICK = 1
        private const val TICK = 2
        private const val LOW = 3
        private const val THUD = 4

        private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun isEnabled(context: Context) = prefs(context).getBoolean(KEY, true)
        fun setEnabled(context: Context, on: Boolean) { prefs(context).edit().putBoolean(KEY, on).apply() }

        fun character(c: Context) = runCatching { Character.valueOf(prefs(c).getString(K_CHAR, null)!!) }.getOrDefault(Character.PORTIERA)
        fun setCharacter(c: Context, v: Character) { prefs(c).edit().putString(K_CHAR, v.name).apply() }
        fun strength(c: Context) = runCatching { Strength.valueOf(prefs(c).getString(K_STRENGTH, null)!!) }.getOrDefault(Strength.PIENO)
        fun setStrength(c: Context, v: Strength) { prefs(c).edit().putString(K_STRENGTH, v.name).apply() }
        fun scope(c: Context) = runCatching { Scope.valueOf(prefs(c).getString(K_SCOPE, null)!!) }.getOrDefault(Scope.BOTH)
        fun setScope(c: Context, v: Scope) { prefs(c).edit().putString(K_SCOPE, v.name).apply() }
    }
}
