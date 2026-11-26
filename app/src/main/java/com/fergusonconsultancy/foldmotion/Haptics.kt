package com.fergusonconsultancy.foldmotion

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Scatto (19 Nov 2026): the hinge's voice.
 *
 * Fold Motion already ticked as the blades started, clicked on the climax and settled on
 * opening, always at the same strength. Scatto shapes those same moments by how fast the
 * phone was folded (Concentrato's reading), so a flick is one firm snap and a slow close is
 * a long, low settle, like a well-made car door. Opening is the lighter latch.
 *
 * - Matched to the light (default, 21 Nov): each animation style carries its own voice (see
 *   [voiceFor]), and each climax is started early enough to land exactly on the light's.
 * - Character, when Matched is off: Portiera (a car door, weighted), Corona (a watch crown, crisp detents),
 *   Caveau (a vault door, the heaviest: a slow swing and bolts throwing home) or Scrigno
 *   (a jewel case: a magnetic snap shut, a bright spring open). Caveau and Scrigno, 20 Nov.
 * - Strength: Lieve, Pieno, Deciso or Pesante. A primitive tops out at full strength, so
 *   above Pieno the force that won't fit in one hit spills into a second, lower one a few
 *   milliseconds behind it: the body of a heavier door following its latch (20 Nov).
 * - Close only: opening stays silent, to test whether the haptic turns to noise.
 *
 * Primitives are checked one by one; where THUD or LOW_TICK are missing the nearest one
 * stands in, and with no primitives at all an amplitude waveform is used. The haptics follow
 * the system's touch-vibration intensity (USAGE_TOUCH), so turning that off silences them.
 *
 * [slowness] everywhere is 0 for a flick, 1 for a slow fold, as Concentrato reads it.
 */
class Haptics(private val context: Context) {

    /**
     * What the fold feels and sounds like. Each animation style carries its own (Matched to the
     * light, the default, 21 Nov 2026); the four characters remain as choices when it is off.
     */
    enum class Voice(val note: String) {
        PORTIERA("a car door: a weighted close, a light latch as it opens"),
        CORONA("a watch crown: crisp detents"),
        CAVEAU("a vault door: a heavy landing, then the bolts"),
        SCRIGNO("a jewel case: a magnetic snap, a bright spring"),
        CATENARIA("a chain: the links running, then pulled taut"),
    }

    enum class Character(val label: String, val note: String, val voice: Voice) {
        PORTIERA("Portiera", "A car door: a weighted close, a light latch as it opens", Voice.PORTIERA),
        CORONA("Corona", "A watch crown: crisp detents, the same in both directions", Voice.CORONA),
        CAVEAU("Caveau", "A vault door: a slow, heavy swing, then the bolts throwing home", Voice.CAVEAU),
        SCRIGNO("Scrigno", "A jewel case: a magnetic snap shut, a bright spring as it opens", Voice.SCRIGNO),
    }

    enum class Strength(val label: String, val gain: Float) {
        LIEVE("Lieve", 0.6f),
        PIENO("Pieno", 1f),
        DECISO("Deciso", 1.3f),
        PESANTE("Pesante", 1.6f),
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
    private val hasQuickRise = supports(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE)
    private val hasSlowRise = supports(VibrationEffect.Composition.PRIMITIVE_SLOW_RISE)
    private val hasQuickFall = supports(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL)
    private val primitives = hasClick && hasTick

    val enabled: Boolean get() = isEnabled(context)

    private val gain get() = strength(context).gain
    private val opening get() = scope(context) == Scope.BOTH

    /** The voice for each direction: the chosen style's own, or the chosen character's. */
    private fun closingVoice() = if (matched(context)) voiceFor(MotionConfig.closing(context).effect) else character(context).voice
    private fun openingVoice() = if (matched(context)) voiceFor(MotionConfig.opening(context).effect) else character(context).voice

    // region The fold's moments (called by the service, timed to the light)

    /** Closing, as the light's first stroke appears. Only a slow close is felt here; a flick waits for contact. */
    fun tick(slowness: Float = 0.5f) {
        if (slowness < FAST) return
        val v = closingVoice()
        compose(
            when (v) {
                Voice.PORTIERA -> listOf(Step(LOW, 0.30f + 0.15f * slowness))
                Voice.CORONA -> listOf(Step(TICK, 0.45f))
                // The vault's mass beginning to swing
                Voice.CAVEAU -> listOf(Step(SLOW_RISE, 0.45f + 0.3f * slowness))
                // The lid tipping over; the rim lighting at the edges
                Voice.SCRIGNO -> listOf(Step(TICK, 0.25f))
                Voice.CATENARIA -> listOf(Step(TICK, 0.2f))
            }, v
        )
    }

    /**
     * Closing, on contact. Started [closeLead] ms early so the climax lands on the halves meeting;
     * whatever follows it must fit in [budget] ms (the light's finale), softer than the climax.
     */
    fun click(slowness: Float = 0.5f, budget: Int = SWITCH_BUDGET) {
        val v = closingVoice()
        compose(closeSteps(slowness, v), v, budget)
    }

    /** Opening, as the inner screen arrives: a soft low settle. */
    fun settle(slowness: Float = 0.5f) {
        if (!opening) return
        val v = openingVoice()
        val steps = when (v) {
            Voice.PORTIERA -> listOf(Step(LOW, 0.45f + 0.25f * slowness))
            Voice.CORONA -> if (slowness >= FAST) listOf(Step(TICK, 0.35f)) else emptyList()
            // The bolts drawing back: one for a flick, two for a slow open
            Voice.CAVEAU -> if (slowness < FAST) listOf(Step(LOW, 0.6f)) else listOf(Step(LOW, 0.5f), Step(LOW, 0.5f, 45))
            Voice.SCRIGNO -> if (slowness >= FAST) listOf(Step(TICK, 0.25f)) else emptyList()
            // A seam of light gives
            Voice.CATENARIA -> if (slowness >= FAST) listOf(Step(LOW, 0.3f)) else emptyList()
        }
        compose(steps, v)
    }

    /**
     * Opening, on the reveal's climax, started [releaseLead] ms early. Nothing may follow the
     * climax once touch is back: [budget] is the time from the climax to touch returning.
     */
    fun release(slowness: Float = 0.5f, budget: Int = SWITCH_BUDGET) {
        if (!opening) return
        val v = openingVoice()
        compose(releaseSteps(slowness, v), v, budget)
    }

    /** How long before the climax a close's sequence must start, so the climax lands on contact. */
    fun closeLead(slowness: Float): Int = lead(closeSteps(slowness, closingVoice()))

    /** The same for the opening's climax. */
    fun releaseLead(slowness: Float): Int = if (!opening) 0 else lead(releaseSteps(slowness, openingVoice()))

    private fun lead(steps: List<Step>): Int {
        val w = weigh(shape(steps, Int.MAX_VALUE), gain)
        val on = onsets(w)
        return w.indexOfFirst { it.climax }.let { if (it < 0) 0 else on[it] }
    }

    private fun releaseSteps(slowness: Float, v: Voice): List<Step> = when (v) {
        Voice.PORTIERA -> if (slowness < FAST) listOf(Step(CLICK, 0.6f))
            else listOf(Step(TICK, 0.3f), Step(CLICK, 0.5f, 50))
        Voice.CORONA -> listOf(Step(TICK, 0.6f))
        // The door swinging free of its frame
        Voice.CAVEAU -> listOf(Step(QUICK_FALL, 0.55f + 0.2f * slowness), Step(LOW, 0.3f, 30))
        // The lid's spring: a bright snap and a small ring after it
        Voice.SCRIGNO -> listOf(Step(CLICK, 0.7f), Step(TICK, 0.35f, 60))
        // The chain running out, then pulled taut
        Voice.CATENARIA -> if (slowness < FAST) listOf(Step(QUICK_RISE, 0.4f), Step(CLICK, 0.8f))
            else listOf(Step(QUICK_RISE, 0.5f), Step(CLICK, 0.7f))
    }

    private fun closeSteps(s: Float, v: Voice): List<Step> = when (v) {
        Voice.PORTIERA -> when {
            s < FAST -> listOf(Step(CLICK, 1f), Step(THUD, 0.45f, 10))
            else -> listOf(Step(THUD, 0.55f + 0.4f * s), Step(LOW, 0.25f + 0.3f * s, (40 + 50 * s).roundToInt()))
        }
        Voice.CORONA -> when {
            s < FAST -> listOf(Step(CLICK, 0.9f))
            else -> listOf(Step(TICK, 0.55f), Step(TICK, 0.55f, 45), Step(CLICK, 0.8f, 45))
        }
        // The door lands, then the bolts throw home: two for a flick, three for a slow close
        Voice.CAVEAU -> when {
            s < FAST -> listOf(Step(THUD, 1f), Step(LOW, 0.7f, 30), Step(LOW, 0.7f, 35))
            else -> listOf(Step(THUD, 0.8f + 0.2f * s), Step(LOW, 0.55f, (60 + 60 * s).roundToInt()), Step(LOW, 0.55f, 50), Step(LOW, 0.45f, 50))
        }
        // The magnet pulls the lid in, then it sits down on the velvet
        Voice.SCRIGNO -> when {
            s < FAST -> listOf(Step(QUICK_RISE, 0.5f), Step(CLICK, 0.9f))
            else -> listOf(Step(QUICK_RISE, 0.4f + 0.2f * s), Step(CLICK, 0.8f), Step(LOW, 0.3f, 30))
        }
        // The chain sagging in as the halves close, then the halves meeting: nothing after
        Voice.CATENARIA -> when {
            s < FAST -> listOf(Step(QUICK_RISE, 0.45f), Step(CLICK, 1f))
            else -> listOf(Step(QUICK_RISE, 0.35f + 0.25f * s), Step(CLICK, 0.85f))
        }
    }

    // endregion

    /** For "Feel it" in settings: the whole close (start, then contact) or the whole open, with the same limits a real fold has. */
    fun preview(close: Boolean, slowness: Float, after: (Long, () -> Unit) -> Unit) {
        if (close) {
            tick(slowness)
            val budget = MotionConfig.finaleMsFor(MotionConfig.closing(context).effect).toInt()
            after(if (slowness < FAST) 0L else (180 + 260 * slowness).toLong()) { click(slowness, budget) }
        } else {
            settle(slowness)
            val budget = MotionConfig.climaxToTouchMs(MotionConfig.opening(context).effect)
            after((240 + 200 * slowness).toLong()) { release(slowness, budget) }
        }
    }

    // region Playing

    private data class Step(val kind: Int, val scale: Float, val delay: Int = 0, val climax: Boolean = false)

    /**
     * One climax, strictly (charter rule 4): the strongest step is the climax; anything after it is
     * held to 60% of it and dropped if it would start more than [budget] ms after the climax.
     */
    private fun shape(steps: List<Step>, budget: Int): List<Step> {
        if (steps.isEmpty()) return steps
        var peak = 0
        steps.forEachIndexed { i, st -> if (st.scale > steps[peak].scale) peak = i }
        val cap = steps[peak].scale * 0.6f
        val on = onsets(steps)
        return steps.mapIndexedNotNull { i, st ->
            when {
                i < peak -> st
                i == peak -> st.copy(climax = true)
                on[i] - on[peak] > budget -> null
                else -> st.copy(scale = min(st.scale, cap))
            }
        }
    }

    private fun onsets(steps: List<Step>): IntArray {
        var t = 0
        return IntArray(steps.size) { i -> t += steps[i].delay; val at = t; t += duration(steps[i].kind); at }
    }

    private fun compose(steps: List<Step>, voice: Voice, budget: Int = Int.MAX_VALUE) {
        if (steps.isEmpty()) return
        val weighted = weigh(shape(steps, budget), gain)
        val effect = if (primitives) {
            val c = VibrationEffect.startComposition()
            weighted.forEach { st -> c.addPrimitive(resolve(st.kind), st.scale.coerceIn(0f, 1f), st.delay) }
            c.compose()
        } else waveform(weighted)
        play(effect)
        if (enabled) suono.play(voice, hits(weighted))
    }

    private val suono = Suono(context)

    /** When each step starts, in ms: its delay after the previous one ends (the composition's rule). */
    private fun hits(steps: List<Step>): List<Suono.Hit> {
        var t = 0
        return steps.map { st ->
            t += st.delay
            val h = Suono.Hit(st.kind, st.scale.coerceIn(0f, 1f), t)
            t += duration(st.kind)
            h
        }
    }

    /** How long this phone's motor takes over each primitive, so the sound lands with it. */
    private val durations: Map<Int, Int> by lazy {
        val v = vibrator
        if (v == null || !primitives) return@lazy emptyMap()
        val kinds = listOf(CLICK, TICK, LOW, THUD, QUICK_RISE, SLOW_RISE, QUICK_FALL)
        runCatching {
            val d = v.getPrimitiveDurations(*kinds.map { resolve(it) }.toIntArray())
            kinds.indices.associate { kinds[it] to d[it] }.filterValues { it > 0 }
        }.getOrDefault(emptyMap())
    }

    private fun duration(kind: Int): Int = durations[kind] ?: when (kind) {
        THUD -> 26; SLOW_RISE -> 60; QUICK_RISE, QUICK_FALL -> 30; LOW -> 18; CLICK -> 12; else -> 8
    }

    /** For the emulator walk: each character's flick, slow close and open, as WAVs at Presente by day. */
    fun exportSounds(dir: java.io.File) {
        dir.mkdirs()
        Voice.entries.forEach { v ->
            listOf("flick" to closeSteps(0f, v), "slow" to closeSteps(1f, v), "open" to releaseSteps(0.6f, v)).forEach { (name, steps) ->
                val pcm = Suono.render(v, hits(weigh(shape(steps, Int.MAX_VALUE), gain)), Suono.Level.PRESENTE.gain)
                java.io.File(dir, "${v.name.lowercase()}-$name.wav").writeBytes(Suono.wav(pcm))
            }
        }
    }

    /**
     * Applies the strength. A primitive can't go past full, so at Deciso and Pesante whatever a
     * step can't hold spills into a second, lower hit [ECHO] ms behind it (a THUD under a click
     * or thud, a LOW_TICK under a tick); the next step's gap shrinks by as much, so the rhythm holds.
     */
    private fun weigh(steps: List<Step>, g: Float): List<Step> {
        val out = ArrayList<Step>()
        var taken = 0
        steps.forEach { st ->
            val s = st.scale * g
            out += st.copy(scale = s.coerceAtMost(1f), delay = (st.delay - taken).coerceAtLeast(0))
            taken = 0
            val over = s - 1f
            if (over > 0.15f) {  // a smaller spill is too faint to feel
                out += Step(body(st.kind), over.coerceAtMost(1f), ECHO)
                taken = ECHO + 10
            }
        }
        return out
    }

    private fun body(kind: Int) = when (kind) {
        CLICK, THUD, QUICK_RISE, SLOW_RISE -> THUD
        else -> LOW
    }

    /** The nearest primitive this phone has: THUD falls back to CLICK, LOW_TICK to TICK. */
    private fun resolve(kind: Int): Int = when (kind) {
        THUD -> if (hasThud) VibrationEffect.Composition.PRIMITIVE_THUD else VibrationEffect.Composition.PRIMITIVE_CLICK
        LOW -> if (hasLowTick) VibrationEffect.Composition.PRIMITIVE_LOW_TICK else VibrationEffect.Composition.PRIMITIVE_TICK
        CLICK -> VibrationEffect.Composition.PRIMITIVE_CLICK
        QUICK_RISE -> if (hasQuickRise) VibrationEffect.Composition.PRIMITIVE_QUICK_RISE else VibrationEffect.Composition.PRIMITIVE_TICK
        SLOW_RISE -> when {
            hasSlowRise -> VibrationEffect.Composition.PRIMITIVE_SLOW_RISE
            hasLowTick -> VibrationEffect.Composition.PRIMITIVE_LOW_TICK
            else -> VibrationEffect.Composition.PRIMITIVE_TICK
        }
        QUICK_FALL -> when {
            hasQuickFall -> VibrationEffect.Composition.PRIMITIVE_QUICK_FALL
            hasLowTick -> VibrationEffect.Composition.PRIMITIVE_LOW_TICK
            else -> VibrationEffect.Composition.PRIMITIVE_TICK
        }
        else -> VibrationEffect.Composition.PRIMITIVE_TICK
    }

    /** No primitives: short pulses, longer and softer for the low ones. */
    private fun waveform(steps: List<Step>): VibrationEffect {
        val times = ArrayList<Long>()
        val amps = ArrayList<Int>()
        steps.forEach { st ->
            times += st.delay.toLong(); amps += 0
            times += when (st.kind) { THUD -> 26L; SLOW_RISE -> 60L; QUICK_RISE, QUICK_FALL -> 30L; LOW -> 18L; CLICK -> 12L; else -> 8L }
            amps += (255 * st.scale).roundToInt().coerceIn(1, 255)
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
                run {
                    val stand = listOfNotNull(
                        "THUD".takeIf { !hasThud }, "LOW_TICK".takeIf { !hasLowTick }, "QUICK_RISE".takeIf { !hasQuickRise },
                        "SLOW_RISE".takeIf { !hasSlowRise }, "QUICK_FALL".takeIf { !hasQuickFall },
                    )
                    when {
                        !primitives -> "waveforms (no primitives)"
                        stand.isEmpty() -> "primitives, in full"
                        else -> "primitives, nearest stand-ins for " + stand.joinToString(", ")
                    }
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
        private const val K_MATCHED = "scatto_matched"

        /** After the climax when no light is playing (a close felt at the switch): a short tail only. */
        const val SWITCH_BUDGET = 150

        /** Each style's own voice: what its light does, felt and heard. */
        fun voiceFor(e: MotionConfig.Effect): Voice = when (e) {
            MotionConfig.Effect.CATENARIA -> Voice.CATENARIA          // a chain running out, pulled taut
            MotionConfig.Effect.CIRCUITO -> Voice.CORONA            // light running the rings, meeting: detents
            MotionConfig.Effect.FANALE_REVERSE -> Voice.SCRIGNO     // light springing out from the hinge
            else -> Voice.PORTIERA                                  // Fanale: the car's lamps, the car's door
        }

        fun matched(c: Context) = prefs(c).getBoolean(K_MATCHED, true)
        fun setMatched(c: Context, on: Boolean) { prefs(c).edit().putBoolean(K_MATCHED, on).apply() }

        /** Below this slowness a fold counts as a flick. */
        const val FAST = 0.35f

        internal const val CLICK = 1
        internal const val TICK = 2
        internal const val LOW = 3
        internal const val THUD = 4
        internal const val QUICK_RISE = 5
        internal const val SLOW_RISE = 6
        internal const val QUICK_FALL = 7

        /** How far behind its hit the spilled-over force follows, in ms. */
        private const val ECHO = 8

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
