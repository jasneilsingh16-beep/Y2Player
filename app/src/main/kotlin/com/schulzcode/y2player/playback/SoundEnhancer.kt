package com.schulzcode.y2player.playback

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Enhance: one switch that makes old kirtan and katha recordings sound fuller,
 * clearer and less shut inside the head on earphones. It never adds detail a
 * file does not have; it takes away what gets in the way (mains hum, rumble,
 * harshness, a one-sided or flat mono image, levels that jump about) and places
 * the sound in front of you in a small room.
 *
 * The chain, per stereo frame at 44.1 kHz:
 *  1. hum notches at 50 and 100 Hz, and a high-pass (rumble for music, below
 *     80 Hz for speech);
 *  2. channel repair: a recording heard in one ear only is centred, and a mono
 *     recording of kirtan is opened out with a decorrelated side signal;
 *  3. tone, per kind: warmth and air for kirtan, less boxiness and more presence
 *     for speech, and a dip where the Chu III and cheap MP3s are both bright;
 *  4. a compressor that evens levels: gentle for kirtan, firmer for speech;
 *  5. crossfeed, so each ear hears a little of the other channel as it would
 *     from speakers;
 *  6. a small room: early reflections and a short warm tail;
 *  7. low-volume compensation that lifts bass and treble as the volume drops;
 *  8. a peak limiter, so none of it can clip.
 *
 * Switching on or off, or between kirtan and speech, crossfades over a fifth of
 * a second. Off costs nothing: the samples are not touched.
 *
 * Runs on the playback thread only; the setters may be called from any thread.
 */
internal class SoundEnhancer(private val sampleRate: Int = PcmFormat.SAMPLE_RATE) {
    enum class Kind { MUSIC, SPEECH }

    @Volatile var enabled: Boolean = false
    @Volatile var kind: Kind = Kind.MUSIC
    /** 0 is silent, 1 is the loudest the device goes. */
    @Volatile var volumeFraction: Float = 0.5f

    private var mix = 0f
    private var activeKind: Kind? = null
    private var activeLoudness = -1f
    private val rampStep = 1f / (RAMP_SECONDS * sampleRate)

    // 1. Clean-up.
    private val hum50 = Biquad()
    private val hum100 = Biquad()
    private val highPass = Biquad()

    // 2. Channel repair.
    private var oneSided = 0f
    private var monoWeight = 0f
    private val decorrelator = AllpassChain(intArrayOf(142, 107, 379, 277).map { it * sampleRate / 44_100 }.toIntArray(), .6f)

    // 3. Tone.
    private val toneLow = Biquad()
    private val toneMid = Biquad()
    private val toneHigh = Biquad()
    private var widthAmount = 0f

    // 4. Dynamics.
    private var compEnvelope = 0f
    private var compThresholdDb = 0f
    private var compRatio = 1f
    private var compMakeup = 1f
    private var compAttack = 0f
    private var compRelease = 0f
    private var compGain = 1f

    // 5. Crossfeed.
    private val crossDelay = IntArray(1)
    private val crossBufferL = FloatArray(CROSSFEED_DELAY_MAX)
    private val crossBufferR = FloatArray(CROSSFEED_DELAY_MAX)
    private var crossIndex = 0
    private var crossLowL = 0f
    private var crossLowR = 0f
    private var selfLowL = 0f
    private var selfLowR = 0f
    private var crossCoef = 0f
    private var crossGain = 0f

    // 6. Room.
    private val room = Room(sampleRate)
    private var roomMix = 0f
    private var roomLow = 0f
    private var roomLowCoef = 0f

    // 7. Low-volume compensation.
    private val loudLow = Biquad()
    private val loudHigh = Biquad()

    // 8. Limiter.
    private var limitGain = 1f
    private val limitRelease = coefficient(LIMITER_RELEASE_SECONDS)

    private var dryL = FloatArray(PcmFormat.BLOCK_SAMPLES)

    fun reset() {
        hum50.reset(); hum100.reset(); highPass.reset()
        toneLow.reset(); toneMid.reset(); toneHigh.reset()
        loudLow.reset(); loudHigh.reset()
        decorrelator.reset()
        room.reset()
        roomLow = 0f
        crossBufferL.fill(0f); crossBufferR.fill(0f)
        crossLowL = 0f; crossLowR = 0f; selfLowL = 0f; selfLowR = 0f; crossIndex = 0
        compEnvelope = 0f
        compGain = 1f
        limitGain = 1f
        oneSided = 0f
        monoWeight = 0f
    }

    /** Processes [sampleCount] interleaved stereo samples in place. */
    fun process(pcm: FloatArray, offset: Int, sampleCount: Int) {
        val target = if (enabled) 1f else 0f
        if (mix == 0f && target == 0f) return
        if (mix == 0f) reset()
        configure()
        if (dryL.size < sampleCount) dryL = FloatArray(sampleCount)
        System.arraycopy(pcm, offset, dryL, 0, sampleCount)
        analyse(pcm, offset, sampleCount)

        val frames = sampleCount / 2
        val dry = dryL
        var i = offset
        for (frame in 0 until frames) {
            var l = pcm[i] * INPUT_GAIN
            var r = pcm[i + 1] * INPUT_GAIN

            // 1. Hum and rumble.
            l = highPass.left(hum100.left(hum50.left(l)))
            r = highPass.right(hum100.right(hum50.right(r)))

            // 2. One ear only: centre it. Mono kirtan: open it out.
            if (oneSided > 0f) {
                val centred = (l + r) * SQRT_HALF
                l += (centred - l) * oneSided
                r += (centred - r) * oneSided
            }
            if (widthAmount > 0f) {
                val mid = (l + r) * .5f
                val side = decorrelator.process(mid) * widthAmount * monoWeight
                l += side
                r -= side
            }

            // 3. Tone.
            l = toneHigh.left(toneMid.left(toneLow.left(l)))
            r = toneHigh.right(toneMid.right(toneLow.right(r)))

            // 4. Levels.
            val level = max(abs(l), abs(r))
            compEnvelope += (level - compEnvelope) * (if (level > compEnvelope) compAttack else compRelease)
            // The gain curve needs a logarithm, so it is worked out every 16 frames, not every one.
            if (frame and 15 == 0) compGain = compressorGain(compEnvelope) * compMakeup
            l *= compGain
            r *= compGain

            // 5. Crossfeed: the other channel, low-passed and a third of a millisecond late,
            // less the same band of this one, so centred sound keeps its level and tone.
            val delayed = (crossIndex - crossDelay[0]) and CROSSFEED_MASK
            crossLowL += (crossBufferR[delayed] - crossLowL) * crossCoef
            crossLowR += (crossBufferL[delayed] - crossLowR) * crossCoef
            selfLowL += (l - selfLowL) * crossCoef
            selfLowR += (r - selfLowR) * crossCoef
            crossBufferL[crossIndex] = l
            crossBufferR[crossIndex] = r
            crossIndex = (crossIndex + 1) and CROSSFEED_MASK
            l += (crossLowL - selfLowL) * crossGain
            r += (crossLowR - selfLowR) * crossGain

            // 6. Room.
            if (roomMix > 0f) {
                // The room is fed no deep bass, which would only make it boom.
                val roomIn = (l + r) * .5f
                roomLow += (roomIn - roomLow) * roomLowCoef
                room.process(roomIn - roomLow)
                l += room.outL * roomMix
                r += room.outR * roomMix
            }

            // 7. Fullness at low volume.
            l = loudHigh.left(loudLow.left(l))
            r = loudHigh.right(loudLow.right(r))

            // 8. Never clip.
            val peak = max(abs(l), abs(r))
            val wanted = if (peak > LIMIT_CEILING) LIMIT_CEILING / peak else 1f
            limitGain = if (wanted < limitGain) wanted else limitGain + (wanted - limitGain) * limitRelease
            l = (l * limitGain).coerceIn(-1f, 1f)
            r = (r * limitGain).coerceIn(-1f, 1f)

            // On, off: a short crossfade with the untouched signal.
            if (mix != target) mix = if (mix < target) min(target, mix + rampStep) else max(target, mix - rampStep)
            val d = i - offset
            pcm[i] = dry[d] + (l - dry[d]) * mix
            pcm[i + 1] = dry[d + 1] + (r - dry[d + 1]) * mix
            i += 2
        }
    }

    private fun compressorGain(envelope: Float): Float {
        if (envelope <= 1e-6f) return 1f
        val levelDb = 20f * log10(envelope)
        val over = levelDb - compThresholdDb
        if (over <= 0f) return 1f
        val reducedDb = over - over / compRatio
        return 10f.pow(-reducedDb / 20f)
    }

    /** Looks at the whole block once: is it one-sided, is it mono? */
    private fun analyse(pcm: FloatArray, offset: Int, sampleCount: Int) {
        var left = 0.0
        var right = 0.0
        var mid = 0.0
        var side = 0.0
        var i = offset
        val end = offset + sampleCount
        while (i < end) {
            val l = pcm[i].toDouble()
            val r = pcm[i + 1].toDouble()
            left += l * l
            right += r * r
            val m = (l + r) * .5
            val s = (l - r) * .5
            mid += m * m
            side += s * s
            i += 2
        }
        val loud = max(left, right)
        val quiet = min(left, right)
        // Silence teaches nothing; keep the last judgement.
        if (loud < SILENCE_ENERGY * sampleCount) return
        val oneSidedNow = if (quiet < loud * ONE_SIDED_RATIO) 1f else 0f
        val sideRatio = if (mid > 0.0) (side / mid).toFloat() else 0f
        val monoNow = ((MONO_SIDE_RATIO_HIGH - sideRatio) / (MONO_SIDE_RATIO_HIGH - MONO_SIDE_RATIO_LOW)).coerceIn(0f, 1f)
        oneSided += (oneSidedNow - oneSided) * BLOCK_SMOOTHING
        monoWeight += (monoNow - monoWeight) * BLOCK_SMOOTHING
    }

    private fun configure() {
        val kindNow = kind
        if (kindNow != activeKind) {
            activeKind = kindNow
            val speech = kindNow == Kind.SPEECH
            hum50.notch(sampleRate, 50.0, 18.0)
            hum100.notch(sampleRate, 100.0, 18.0)
            highPass.highPass(sampleRate, if (speech) 80.0 else 28.0, .707)
            if (speech) {
                toneLow.peaking(sampleRate, 250.0, 1.0, -3.0)
                toneMid.peaking(sampleRate, 2_200.0, .9, 3.5)
                toneHigh.highShelf(sampleRate, 7_000.0, -3.0)
                widthAmount = 0f
                compThresholdDb = -30f; compRatio = 3.5f; compMakeup = dbToGain(8.0)
                compAttack = coefficient(.008); compRelease = coefficient(.35)
                crossGain = .3f
                roomMix = .32f
                roomLowCoef = onePole(350.0)
                room.configure(feedback = .70f, damping = .45f, preDelaySeconds = .012)
            } else {
                toneLow.lowShelf(sampleRate, 110.0, 3.0)
                toneMid.peaking(sampleRate, 3_500.0, .9, -4.0)
                toneHigh.highShelf(sampleRate, 9_000.0, 1.0)
                widthAmount = .55f
                compThresholdDb = -22f; compRatio = 2f; compMakeup = dbToGain(4.0)
                compAttack = coefficient(.015); compRelease = coefficient(.25)
                crossGain = .45f
                roomMix = .55f
                roomLowCoef = onePole(200.0)
                room.configure(feedback = .82f, damping = .32f, preDelaySeconds = .022)
            }
            crossDelay[0] = (CROSSFEED_DELAY_SECONDS * sampleRate).toInt().coerceIn(1, CROSSFEED_DELAY_MAX - 1)
            crossCoef = (1.0 - exp(-2.0 * PI * CROSSFEED_CUTOFF_HZ / sampleRate)).toFloat()
        }
        // The quieter the volume, the more bass and treble the ear needs to hear balance.
        val loudness = ((LOUDNESS_FULL_BELOW - volumeFraction.coerceIn(0f, 1f)) / LOUDNESS_FULL_BELOW).coerceIn(0f, 1f)
        if (abs(loudness - activeLoudness) > .02f) {
            activeLoudness = loudness
            loudLow.lowShelf(sampleRate, 100.0, LOUDNESS_BASS_DB * loudness)
            loudHigh.highShelf(sampleRate, 9_000.0, LOUDNESS_TREBLE_DB * loudness)
        }
    }

    private fun onePole(hz: Double): Float = (1.0 - exp(-2.0 * PI * hz / sampleRate)).toFloat()

    private fun coefficient(seconds: Double): Float = (1.0 - exp(-1.0 / (seconds * sampleRate))).toFloat()

    /** Two-pole filter with separate state for each channel (RBJ cookbook). */
    private class Biquad {
        private var b0 = 1f; private var b1 = 0f; private var b2 = 0f; private var a1 = 0f; private var a2 = 0f
        private var l1 = 0f; private var l2 = 0f; private var r1 = 0f; private var r2 = 0f

        fun reset() { l1 = 0f; l2 = 0f; r1 = 0f; r2 = 0f }

        fun left(x: Float): Float {
            val y = b0 * x + l1
            l1 = b1 * x - a1 * y + l2
            l2 = b2 * x - a2 * y
            return y
        }

        fun right(x: Float): Float {
            val y = b0 * x + r1
            r1 = b1 * x - a1 * y + r2
            r2 = b2 * x - a2 * y
            return y
        }

        private fun set(c0: Double, c1: Double, c2: Double, d0: Double, d1: Double, d2: Double) {
            b0 = (c0 / d0).toFloat(); b1 = (c1 / d0).toFloat(); b2 = (c2 / d0).toFloat()
            a1 = (d1 / d0).toFloat(); a2 = (d2 / d0).toFloat()
        }

        fun notch(rate: Int, hz: Double, q: Double) {
            val w = 2 * PI * hz / rate; val alpha = sin(w) / (2 * q); val c = cos(w)
            set(1.0, -2 * c, 1.0, 1 + alpha, -2 * c, 1 - alpha)
        }

        fun highPass(rate: Int, hz: Double, q: Double) {
            val w = 2 * PI * hz / rate; val alpha = sin(w) / (2 * q); val c = cos(w)
            set((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha)
        }

        fun peaking(rate: Int, hz: Double, q: Double, db: Double) {
            val a = 10.0.pow(db / 40); val w = 2 * PI * hz / rate; val alpha = sin(w) / (2 * q); val c = cos(w)
            set(1 + alpha * a, -2 * c, 1 - alpha * a, 1 + alpha / a, -2 * c, 1 - alpha / a)
        }

        fun lowShelf(rate: Int, hz: Double, db: Double) {
            val a = 10.0.pow(db / 40); val w = 2 * PI * hz / rate; val c = cos(w)
            val alpha = sin(w) / 2 * sqrt(2.0); val k = 2 * sqrt(a) * alpha
            set(a * ((a + 1) - (a - 1) * c + k), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - k),
                (a + 1) + (a - 1) * c + k, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - k)
        }

        fun highShelf(rate: Int, hz: Double, db: Double) {
            val a = 10.0.pow(db / 40); val w = 2 * PI * hz / rate; val c = cos(w)
            val alpha = sin(w) / 2 * sqrt(2.0); val k = 2 * sqrt(a) * alpha
            set(a * ((a + 1) + (a - 1) * c + k), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - k),
                (a + 1) - (a - 1) * c + k, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - k)
        }
    }

    /** Schroeder allpasses in series: changes phase, not tone, so its output is uncorrelated with its input. */
    private class AllpassChain(delays: IntArray, private val gain: Float) {
        private val buffers = Array(delays.size) { FloatArray(delays[it]) }
        private val indices = IntArray(delays.size)

        fun reset() { for (buffer in buffers) buffer.fill(0f); indices.fill(0) }

        fun process(input: Float): Float {
            var x = input
            for (stage in buffers.indices) {
                val buffer = buffers[stage]
                val index = indices[stage]
                val delayed = buffer[index]
                val y = -gain * x + delayed
                buffer[index] = x + gain * y
                indices[stage] = if (index + 1 == buffer.size) 0 else index + 1
                x = y
            }
            return x
        }
    }

    /**
     * A small, warm room (after Freeverb): a pre-delay, four damped combs and two
     * allpasses per ear, with the ears' delays a little apart so it surrounds you.
     */
    private class Room(rate: Int) {
        private val scale = rate / 44_100.0
        private val preDelay = FloatArray((.05 * rate).toInt())
        private var preIndex = 0
        private var preLength = 1
        private val combsL = Array(COMB_TUNING.size) { Comb((COMB_TUNING[it] * scale).toInt()) }
        private val combsR = Array(COMB_TUNING.size) { Comb(((COMB_TUNING[it] + SPREAD) * scale).toInt()) }
        private val allpassL = AllpassChain(IntArray(ALLPASS_TUNING.size) { (ALLPASS_TUNING[it] * scale).toInt() }, .5f)
        private val allpassR = AllpassChain(IntArray(ALLPASS_TUNING.size) { ((ALLPASS_TUNING[it] + SPREAD) * scale).toInt() }, .5f)
        private val sampleRate = rate
        var outL = 0f; private set
        var outR = 0f; private set

        fun configure(feedback: Float, damping: Float, preDelaySeconds: Double) {
            for (comb in combsL) { comb.feedback = feedback; comb.damping = damping }
            for (comb in combsR) { comb.feedback = feedback; comb.damping = damping }
            preLength = (preDelaySeconds * sampleRate).toInt().coerceIn(1, preDelay.size)
        }

        fun reset() {
            preDelay.fill(0f); preIndex = 0
            for (comb in combsL) comb.reset()
            for (comb in combsR) comb.reset()
            allpassL.reset(); allpassR.reset()
            outL = 0f; outR = 0f
        }

        fun process(input: Float) {
            var read = preIndex - preLength
            if (read < 0) read += preDelay.size
            val x = preDelay[read] * INPUT_SCALE
            preDelay[preIndex] = input
            preIndex = if (preIndex + 1 == preDelay.size) 0 else preIndex + 1
            var l = 0f
            var r = 0f
            for (index in combsL.indices) {
                l += combsL[index].process(x)
                r += combsR[index].process(x)
            }
            l = allpassL.process(l)
            r = allpassR.process(r)
            outL = l
            outR = r
        }

        private class Comb(size: Int) {
            private val buffer = FloatArray(size)
            private var index = 0
            private var store = 0f
            var feedback = .8f
            var damping = .3f

            fun reset() { buffer.fill(0f); index = 0; store = 0f }

            fun process(input: Float): Float {
                val output = buffer[index]
                store = output * (1f - damping) + store * damping
                buffer[index] = input + store * feedback
                index = if (index + 1 == buffer.size) 0 else index + 1
                return output
            }
        }

        companion object {
            private val COMB_TUNING = intArrayOf(1116, 1188, 1277, 1356)
            private val ALLPASS_TUNING = intArrayOf(556, 441)
            private const val SPREAD = 23
            /** Sets the room's level so that a mix of 1 is about as loud as the dry sound. */
            const val INPUT_SCALE = .05f
        }
    }

    private companion object {
        const val RAMP_SECONDS = .2f
        const val INPUT_GAIN = .84f
        const val SQRT_HALF = .70710677f
        const val SILENCE_ENERGY = 1e-8
        const val ONE_SIDED_RATIO = .01
        const val MONO_SIDE_RATIO_LOW = .002f
        const val MONO_SIDE_RATIO_HIGH = .03f
        const val BLOCK_SMOOTHING = .3f
        const val CROSSFEED_DELAY_SECONDS = .0003
        const val CROSSFEED_DELAY_MAX = 64
        const val CROSSFEED_MASK = CROSSFEED_DELAY_MAX - 1
        const val CROSSFEED_CUTOFF_HZ = 700.0
        const val LOUDNESS_FULL_BELOW = .6f
        const val LOUDNESS_BASS_DB = 6.0
        const val LOUDNESS_TREBLE_DB = 3.0
        const val LIMIT_CEILING = .95f
        const val LIMITER_RELEASE_SECONDS = .08

        fun dbToGain(db: Double): Float = 10.0.pow(db / 20).toFloat()
    }
}
