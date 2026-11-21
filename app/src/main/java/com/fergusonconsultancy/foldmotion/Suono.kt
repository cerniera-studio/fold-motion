package com.fergusonconsultancy.foldmotion

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Suono (20 Nov 2026): a quiet layer of sound under Scatto's haptics.
 *
 * A sound landing with a vibration is felt as one event, and the pair reads heavier than the
 * motor alone, so this exists to give the fold weight, not to be heard as a sound. It is
 * rendered at the moment of the fold from the very hits the motor plays (kind, strength, start
 * time, spill-overs included), so it keeps time with every character, strength and fold speed.
 *
 * - Off by default; Sommesso or Presente in Scatto's card.
 * - Silent unless the ringer is on normal, Do Not Disturb is off and no call is in progress.
 * - Softer as the room darkens (the lamps' own reading), down to about half in the dark.
 * - Plays as a system sonification: no audio focus, so music is neither paused nor ducked.
 *
 * Phone speakers can't make real bass (little comes out below 300 Hz or so), so each low "body"
 * is built mostly from its 2nd to 10th harmonics: they come through, and the ear supplies the
 * missing low note. The first build used a saturated sine and measured 92-95% of the door's and
 * vault's energy below 200 Hz, inaudible on a phone; this is why.
 */
class Suono(private val context: Context) {

    enum class Level(val label: String, val gain: Float) {
        OFF("Off", 0f),
        SOMMESSO("Sommesso", 0.35f),
        PRESENTE("Presente", 0.7f),
    }

    /** One hit as the motor plays it: kind (Haptics' codes), strength 0..1, start in ms. */
    data class Hit(val kind: Int, val scale: Float, val at: Int)

    fun play(character: Haptics.Voice, hits: List<Hit>) {
        val lvl = level(context)
        if (lvl == Level.OFF || hits.isEmpty() || !allowed()) return
        val volume = lvl.gain * (1f - 0.5f * Ambient.darkness(context))
        val pcm = render(character, hits, volume)
        runCatching {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 4)
                .build()
            track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
            track.play()
            val ms = pcm.size * 1000L / RATE
            Handler(Looper.getMainLooper()).postDelayed({ runCatching { track.release() } }, ms + 400)
        }
    }

    /** Whether the phone's state lets a sound out right now. */
    fun allowed(): Boolean {
        val am = context.getSystemService(AudioManager::class.java) ?: return false
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return false
        if (am.mode != AudioManager.MODE_NORMAL) return false
        val f = context.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter
        return f == null || f == NotificationManager.INTERRUPTION_FILTER_ALL || f == NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }

    fun report(): String {
        val am = context.getSystemService(AudioManager::class.java)
        val ringer = when (am?.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> "ringer on"
            AudioManager.RINGER_MODE_VIBRATE -> "ringer on vibrate"
            AudioManager.RINGER_MODE_SILENT -> "ringer silent"
            else -> "ringer unknown"
        }
        return "Sound: ${level(context).label}, $ringer, " + if (allowed()) "would play" else "held silent"
    }

    /** A timbre: what the object is made of. */
    private class Voice(
        val bodyHz: Float, val bodyTau: Float, val drive: Float,
        val pings: FloatArray, val pingTau: Float, val pingLevel: Float,
        val noise: Float, val bright: Float,
    )

    companion object {
        const val RATE = 48_000
        private const val PREFS = "fold_motion"
        private const val KEY = "suono_level"

        fun level(c: Context) = runCatching {
            Level.valueOf(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)!!)
        }.getOrDefault(Level.OFF)

        fun setLevel(c: Context, v: Level) {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, v.name).apply()
        }

        private fun voice(c: Haptics.Voice) = when (c) {
            // A car door: a padded, low thump; the latch only a hint
            Haptics.Voice.PORTIERA -> Voice(72f, 0.070f, 3.5f, floatArrayOf(1650f), 0.006f, 0.12f, 0.30f, 0.2f)
            // A watch crown: almost no body, a small bright metal detent
            Haptics.Voice.CORONA -> Voice(190f, 0.010f, 1.5f, floatArrayOf(3400f, 5150f), 0.012f, 0.45f, 0.15f, 0.8f)
            // A vault door: the deepest body, and steel in the bolts
            Haptics.Voice.CAVEAU -> Voice(50f, 0.130f, 4.5f, floatArrayOf(820f, 1310f), 0.035f, 0.28f, 0.22f, 0.3f)
            // A jewel case: a soft velvet pop and a bright spring that rings
            Haptics.Voice.SCRIGNO -> Voice(140f, 0.022f, 2.0f, floatArrayOf(4200f, 6350f), 0.060f, 0.32f, 0.10f, 0.6f)
            // A membrane: an elastic body, a short dry snap, no ring
            Haptics.Voice.MEMBRANA -> Voice(110f, 0.028f, 2.5f, floatArrayOf(2600f), 0.004f, 0.15f, 0.25f, 0.4f)
        }

        /** The sound for these hits, as mono float PCM at [RATE]. Pure, so it can be exported and checked. */
        fun render(character: Haptics.Voice, hits: List<Hit>, volume: Float): FloatArray {
            val v = voice(character)
            val end = hits.maxOf { it.at } + 450
            val out = FloatArray(end * RATE / 1000)
            val rnd = java.util.Random(7)
            hits.forEach { h ->
                val a = h.scale * volume
                val at = h.at * RATE / 1000
                when (h.kind) {
                    Haptics.THUD -> {
                        body(out, at, v, a, v.bodyHz, v.bodyTau)
                        noise(out, at, a * v.noise, 0.008f, v.bright * 0.3f, rnd)
                    }
                    Haptics.CLICK -> {
                        noise(out, at, a * 0.55f, 0.002f, max(v.bright, 0.5f), rnd)
                        pings(out, at, v, a * v.pingLevel, 1f)
                        body(out, at, v, a * 0.35f, v.bodyHz * 1.2f, v.bodyTau * 0.5f)
                    }
                    Haptics.TICK -> {
                        noise(out, at, a * 0.3f, 0.0015f, max(v.bright, 0.5f), rnd)
                        pings(out, at, v, a * v.pingLevel * 0.5f, 0.6f)
                    }
                    Haptics.LOW -> {
                        body(out, at, v, a * 0.55f, v.bodyHz * 1.4f, v.bodyTau * 0.4f)
                        pings(out, at, v, a * v.pingLevel * 0.4f, 0.7f)
                    }
                    Haptics.QUICK_RISE -> swell(out, at, a * 0.25f, 0.040f, rnd)
                    Haptics.SLOW_RISE -> rise(out, at, v, a * 0.5f, 0.140f)
                    Haptics.QUICK_FALL -> fall(out, at, v, a * 0.5f, 0.090f)
                }
            }
            // Master: a gentle limiter, and a 2 ms fade at the very end
            for (i in out.indices) out[i] = tanh(out[i] * 0.9f)
            val f = min(out.size, RATE / 500)
            for (i in 0 until f) out[out.size - 1 - i] *= i / f.toFloat()
            return out
        }

        /**
         * One sample of a low note a phone can play: a quiet fundamental and harmonics 2 to 10,
         * falling off more slowly the larger [drive] (a heavier, rounder object), normalised to 1.
         */
        private fun tone(ph: Double, drive: Float): Float {
            var sum = 0.3 * sin(ph)
            var norm = 0.3
            for (k in 2..10) {
                val w = exp(-(k - 2) / drive.toDouble())
                sum += w * sin(k * ph)
                norm += w
            }
            return (sum / norm * 2.2).toFloat()
        }

        /** A low, pitch-dropping tone, built so a small speaker can carry it. */
        private fun body(out: FloatArray, at: Int, v: Voice, a: Float, hz: Float, tau: Float) {
            val n = min(out.size - at, (tau * 6 * RATE).toInt())
            var ph = 0.0
            for (i in 0 until n) {
                val t = i / RATE.toFloat()
                val f = hz * (1f + 0.6f * exp(-t / 0.015f))
                ph += 2 * PI * f / RATE
                val env = min(1f, t / 0.001f) * exp(-t / tau)
                out[at + i] += a * 0.8f * tone(ph, v.drive) * env
            }
        }

        /** The ring of the material: decaying partials. */
        private fun pings(out: FloatArray, at: Int, v: Voice, a: Float, tauScale: Float) {
            val tau = v.pingTau * tauScale
            val n = min(out.size - at, (tau * 6 * RATE).toInt())
            v.pings.forEachIndexed { k, hz ->
                val ak = a / (k + 1)
                for (i in 0 until n) {
                    val t = i / RATE.toFloat()
                    out[at + i] += ak * sin(2 * PI * hz * t).toFloat() * exp(-t / tau)
                }
            }
        }

        /** A short burst of noise; [bright] 0 keeps only the low part, 1 only the high. */
        private fun noise(out: FloatArray, at: Int, a: Float, len: Float, bright: Float, rnd: java.util.Random) {
            val n = min(out.size - at, (len * 4 * RATE).toInt())
            var lp = 0f
            for (i in 0 until n) {
                val t = i / RATE.toFloat()
                val x = rnd.nextFloat() * 2 - 1
                lp += 0.08f * (x - lp)
                val s = lp * (1 - bright) * 3f + (x - lp) * bright
                out[at + i] += a * s * exp(-t / len)
            }
        }

        /** A magnet's pull: soft low noise that swells and is cut off by the contact. */
        private fun swell(out: FloatArray, at: Int, a: Float, len: Float, rnd: java.util.Random) {
            val n = min(out.size - at, (len * RATE).toInt())
            var lp = 0f
            for (i in 0 until n) {
                val x = rnd.nextFloat() * 2 - 1
                lp += 0.04f * (x - lp)
                val k = i / n.toFloat()
                out[at + i] += a * 4f * lp * k * k
            }
        }

        /** A heavy mass beginning to move: the body tone rising out of nothing. */
        private fun rise(out: FloatArray, at: Int, v: Voice, a: Float, len: Float) {
            val n = min(out.size - at, (len * 1.2f * RATE).toInt())
            var ph = 0.0
            for (i in 0 until n) {
                val t = i / RATE.toFloat()
                ph += 2 * PI * v.bodyHz / RATE
                val env = if (t < len) (t / len) * (t / len) else exp(-(t - len) / 0.02f)
                out[at + i] += a * 0.6f * tone(ph, v.drive) * env
            }
        }

        /** Swinging free: a tone sliding down an octave as it fades. */
        private fun fall(out: FloatArray, at: Int, v: Voice, a: Float, len: Float) {
            val n = min(out.size - at, (len * 1.5f * RATE).toInt())
            var ph = 0.0
            for (i in 0 until n) {
                val t = i / RATE.toFloat()
                val f = v.bodyHz * (2f - min(1f, t / len))
                ph += 2 * PI * f / RATE
                out[at + i] += a * 0.7f * tone(ph, v.drive) * exp(-t / (len * 0.5f))
            }
        }

        /** 16-bit WAV, for the emulator walk to bring the sounds out for checking. */
        fun wav(pcm: FloatArray): ByteArray {
            val data = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            pcm.forEach { data.putShort((it.coerceIn(-1f, 1f) * 32767).roundToInt().toShort()) }
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()).putInt(36 + pcm.size * 2).put("WAVE".toByteArray())
            h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
            h.put("data".toByteArray()).putInt(pcm.size * 2)
            return ByteArrayOutputStream().apply { write(h.array()); write(data.array()) }.toByteArray()
        }
    }
}
