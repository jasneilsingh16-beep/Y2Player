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
import kotlin.math.tanh

/**
 * Enhance: one switch that puts you in the sangat. Kirtan sounds as if you sat a
 * few rows back in a warm darbar hall, with the ragis in front of you, not inside
 * your head; katha sounds close and dry, so every word is clear. Old, quiet
 * recordings come up to the same steady level as new ones, and Enhance is never
 * quieter than Off. It adds no detail a file does not have; it takes away what
 * gets in the way (hum, rumble, harshness, a one-sided image, levels that jump).
 *
 * The chain, per stereo frame at 44.1 kHz:
 *  1. clean-up: hum notches at 50 and 100 Hz and a rumble filter (80 Hz for speech);
 *  2. channel repair: a recording heard in one ear only is centred;
 *  3. tone, per kind, and a dip that acts only while the harsh band stands out
 *     (harmonium reeds and MP3 sizzle for kirtan, sibilance for katha);
 *  4. level: a gain that brings each recording to one loudness, measured as
 *     ITU-R BS.1770 does (K-weighted, over three seconds, ignoring silence). It
 *     lifts up to 12 dB and turns down by at most 1, so Enhance is never quieter;
 *  5. a gentle RMS compressor, firmer for speech;
 *  6. the hall: two loudspeakers in front (crossfeed with head shadow), early
 *     reflections that differ in each ear, and for kirtan a warm late tail;
 *  7. fullness at low volume;
 *  8. a fast peak limiter with a soft knee, ceiling -1 dBFS.
 *
 * Switching on or off crossfades over a fifth of a second; so does changing
 * between kirtan and speech, through the untouched sound. Off costs nothing: the
 * samples are not touched.
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

    // 3. Tone and the dynamic dip.
    private val toneLow = Biquad()
    private val toneMid = Biquad()
    private val toneHigh = Biquad()
    private val harshBand = Biquad()
    private var harshEnvelope = 0f
    private var fullEnvelope = 0f
    private var harshThreshold = 0f
    private var harshMaxCut = 0f
    private val envelopeAttack = coefficient(.002)
    private val envelopeRelease = coefficient(.08)

    // 4. Level.
    private val kShelf = Biquad()
    private val kHighPass = Biquad()
    private var meter = 0.0
    private var meterFrames = 0L
    private var levelDb = 0f
    private var levelGain = 1f
    private var targetLufs = 0f
    private var maxLiftDb = 0f
    private val meterSlow = coefficient(METER_SECONDS)
    private val meterFast = coefficient(METER_SETTLE_SECONDS)

    // 5. Compressor.
    private var compPower = 0f
    private var compThresholdDb = 0f
    private var compSlope = 0f
    /** Gives back what the compressor takes at the recording's usual level, so it evens without quietening. */
    private var compMakeup = 1f
    private var compCoef = 0f
    private var compTarget = 1f
    private var compGain = 1f
    private val compSmooth = coefficient(.002)

    // 6. Hall: crossfeed, early reflections, tail.
    private val crossBufferL = FloatArray(CROSSFEED_DELAY_MAX)
    private val crossBufferR = FloatArray(CROSSFEED_DELAY_MAX)
    private var crossIndex = 0
    private val crossDelay = (CROSSFEED_DELAY_SECONDS * sampleRate).toInt().coerceIn(1, CROSSFEED_DELAY_MAX - 1)
    private val crossCoef = onePole(CROSSFEED_CUTOFF_HZ)
    private var crossLowL = 0f
    private var crossLowR = 0f
    private var selfLowL = 0f
    private var selfLowR = 0f
    private var crossGain = 0f
    private val hall = Hall(sampleRate)

    // 7. Low-volume compensation.
    private val loudLow = Biquad()
    private val loudHigh = Biquad()

    // 8. Limiter.
    private var limitGain = 1f
    private val limitAttack = coefficient(LIMITER_ATTACK_SECONDS)
    private val limitRelease = coefficient(LIMITER_RELEASE_SECONDS)

    private var dry = FloatArray(PcmFormat.BLOCK_SAMPLES)

    fun reset() {
        hum50.reset(); hum100.reset(); highPass.reset()
        toneLow.reset(); toneMid.reset(); toneHigh.reset(); harshBand.reset()
        kShelf.reset(); kHighPass.reset()
        loudLow.reset(); loudHigh.reset()
        harshEnvelope = 0f; fullEnvelope = 0f
        meter = 0.0; meterFrames = 0L
        compPower = 0f; compTarget = 1f; compGain = 1f
        crossBufferL.fill(0f); crossBufferR.fill(0f); crossIndex = 0
        crossLowL = 0f; crossLowR = 0f; selfLowL = 0f; selfLowR = 0f
        hall.reset()
        limitGain = 1f
        oneSided = 0f
    }

    /** Processes [sampleCount] interleaved stereo samples in place. */
    fun process(pcm: FloatArray, offset: Int, sampleCount: Int) {
        val on = enabled
        if (mix == 0f && !on) return
        if (mix == 0f) {
            // Silent switch-over: from the untouched sound, nothing carries across.
            if (activeKind != kind) configureKind(kind)
            reset()
        }
        configureLoudness()
        // A change of kind fades to the untouched sound, retunes there, and fades back.
        val target = if (on && kind == activeKind) 1f else 0f
        if (dry.size < sampleCount) dry = FloatArray(sampleCount)
        System.arraycopy(pcm, offset, dry, 0, sampleCount)
        analyse(pcm, offset, sampleCount)

        val frames = sampleCount / 2
        val frameSeconds = 1f / sampleRate
        var i = offset
        for (frame in 0 until frames) {
            var l = pcm[i]
            var r = pcm[i + 1]

            // 1. Hum and rumble.
            l = highPass.left(hum100.left(hum50.left(l)))
            r = highPass.right(hum100.right(hum50.right(r)))

            // 2. One ear only: centre it.
            if (oneSided > 0f) {
                val centred = (l + r) * SQRT_HALF
                l += (centred - l) * oneSided
                r += (centred - r) * oneSided
            }

            // 4a. Measure the recording as it is, before anything here changes its level.
            val kl = kShelf.left(kHighPass.left(l))
            val kr = kShelf.right(kHighPass.right(r))
            val power = (kl * kl + kr * kr).toDouble()
            meter += (power - meter) * (if (meterFrames < METER_SETTLE_FRAMES) meterFast else meterSlow)
            meterFrames++
            if (frame and 63 == 0) updateLevel(64 * frameSeconds)

            // 3. Tone, then the dip where the harsh band stands out.
            l = toneHigh.left(toneMid.left(toneLow.left(l)))
            r = toneHigh.right(toneMid.right(toneLow.right(r)))
            val bandL = harshBand.left(l)
            val bandR = harshBand.right(r)
            val band = max(abs(bandL), abs(bandR))
            val full = max(abs(l), abs(r))
            harshEnvelope += (band - harshEnvelope) * (if (band > harshEnvelope) envelopeAttack else envelopeRelease)
            fullEnvelope += (full - fullEnvelope) * (if (full > fullEnvelope) envelopeAttack else envelopeRelease)
            if (fullEnvelope > 1e-5f) {
                val ratio = harshEnvelope / fullEnvelope
                if (ratio > harshThreshold) {
                    val cut = min(harshMaxCut, 1f - harshThreshold / ratio)
                    l -= bandL * cut
                    r -= bandR * cut
                }
            }

            // 4b. One loudness for every recording.
            l *= levelGain
            r *= levelGain

            // 5. Gentle compression on the RMS level, worked out every 16 frames.
            compPower += ((l * l + r * r) * .5f - compPower) * compCoef
            if (frame and 15 == 0) compTarget = compressorGain(compPower) * compMakeup
            compGain += (compTarget - compGain) * compSmooth
            l *= compGain
            r *= compGain

            // 6. Two loudspeakers in front: each ear hears the other a third of a
            // millisecond late and shadowed by the head, less the same band of its own,
            // so centred sound keeps its level and tone.
            val delayed = (crossIndex - crossDelay) and CROSSFEED_MASK
            crossLowL += (crossBufferR[delayed] - crossLowL) * crossCoef
            crossLowR += (crossBufferL[delayed] - crossLowR) * crossCoef
            selfLowL += (l - selfLowL) * crossCoef
            selfLowR += (r - selfLowR) * crossCoef
            crossBufferL[crossIndex] = l
            crossBufferR[crossIndex] = r
            crossIndex = (crossIndex + 1) and CROSSFEED_MASK
            l += (crossLowL - selfLowL) * crossGain
            r += (crossLowR - selfLowR) * crossGain

            // The room around them.
            hall.process(l, r)
            l += hall.outL
            r += hall.outR

            // 7. Fullness at low volume.
            l = loudHigh.left(loudLow.left(l))
            r = loudHigh.right(loudLow.right(r))

            // 8. Never clip: a fast limiter, then a soft knee for what slips past it.
            val peak = max(abs(l), abs(r))
            val wanted = if (peak > LIMIT_CEILING) LIMIT_CEILING / peak else 1f
            limitGain += (wanted - limitGain) * (if (wanted < limitGain) limitAttack else limitRelease)
            l = softClip(l * limitGain)
            r = softClip(r * limitGain)

            // On, off: a short crossfade with the untouched signal.
            if (mix != target) mix = if (mix < target) min(target, mix + rampStep) else max(target, mix - rampStep)
            val d = i - offset
            pcm[i] = dry[d] + (l - dry[d]) * mix
            pcm[i + 1] = dry[d + 1] + (r - dry[d + 1]) * mix
            i += 2
        }
    }

    /** Moves the level towards the target loudness, a few dB a second, and never through silence. */
    private fun updateLevel(seconds: Float) {
        if (meter <= 1e-12) return
        val lufs = (-0.691 + 10 * log10(meter)).toFloat()
        if (lufs < GATE_LUFS) return
        val wanted = (targetLufs - lufs).coerceIn(-MAX_CUT_DB, maxLiftDb)
        val settling = meterFrames < METER_SETTLE_FRAMES * 2
        val rise = (if (settling) SETTLE_DB_PER_SECOND else RISE_DB_PER_SECOND) * seconds
        val fall = (if (settling) SETTLE_DB_PER_SECOND else FALL_DB_PER_SECOND) * seconds
        levelDb += (wanted - levelDb).coerceIn(-fall, rise)
        levelGain = dbToGain(levelDb.toDouble())
        // Loudness and RMS level are close enough for broadband sound to set the make-up.
        val usual = lufs + levelDb
        compMakeup = dbToGain((compSlope * max(0f, usual - compThresholdDb)).toDouble())
    }

    private fun compressorGain(power: Float): Float {
        if (power <= 1e-10f) return 1f
        val over = 10f * log10(power) - compThresholdDb
        val reducedDb = when {
            over <= -KNEE_DB / 2 -> return 1f
            over < KNEE_DB / 2 -> compSlope * (over + KNEE_DB / 2).let { it * it } / (2 * KNEE_DB)
            else -> compSlope * over
        }
        return 10f.pow(-reducedDb / 20f)
    }

    /** Looks at the whole block once: is it heard in one ear only? */
    private fun analyse(pcm: FloatArray, offset: Int, sampleCount: Int) {
        var left = 0.0
        var right = 0.0
        var i = offset
        val end = offset + sampleCount
        while (i < end) {
            val l = pcm[i].toDouble()
            val r = pcm[i + 1].toDouble()
            left += l * l
            right += r * r
            i += 2
        }
        val loud = max(left, right)
        // Silence teaches nothing; keep the last judgement.
        if (loud < SILENCE_ENERGY * sampleCount) return
        val oneSidedNow = if (min(left, right) < loud * ONE_SIDED_RATIO) 1f else 0f
        oneSided += (oneSidedNow - oneSided) * BLOCK_SMOOTHING
    }

    private fun configureKind(kindNow: Kind) {
        activeKind = kindNow
        val speech = kindNow == Kind.SPEECH
        hum50.notch(sampleRate, 50.0, 18.0)
        hum100.notch(sampleRate, 100.0, 18.0)
        highPass.highPass(sampleRate, if (speech) 80.0 else 30.0, .707)
        // BS.1770's K-weighting: the head's high-frequency lift and a low cut.
        kShelf.highShelf(sampleRate, 1_681.0, 4.0)
        kHighPass.highPass(sampleRate, 38.0, .5)
        if (speech) {
            // Katha: less boxiness, words forward, sibilance kept in check.
            toneLow.peaking(sampleRate, 300.0, 1.0, -2.5)
            toneMid.peaking(sampleRate, 2_500.0, .9, 3.0)
            toneHigh.highShelf(sampleRate, 9_000.0, -1.0)
            harshBand.bandPass(sampleRate, 6_500.0, 1.2)
            harshThreshold = .30f; harshMaxCut = 1f - dbToGain(-6.0)
            targetLufs = -13f; maxLiftDb = 14f
            compThresholdDb = -24f; compSlope = 1f - 1f / 2.2f
            compCoef = coefficient(.015)
            crossGain = .25f
        } else {
            // Kirtan: warmth under the voice, a little air over the harmonium.
            toneLow.lowShelf(sampleRate, 150.0, 2.0)
            toneMid.peaking(sampleRate, 900.0, .8, 0.0)
            toneHigh.highShelf(sampleRate, 10_000.0, 1.5)
            harshBand.bandPass(sampleRate, 3_200.0, 1.0)
            harshThreshold = .38f; harshMaxCut = 1f - dbToGain(-5.0)
            targetLufs = -13f; maxLiftDb = 12f
            compThresholdDb = -20f; compSlope = 1f - 1f / 1.5f
            compCoef = coefficient(.025)
            crossGain = .35f
        }
        hall.configure(speech)
    }

    private fun configureLoudness() {
        // The volume curve is linear in dB, so the fraction says how far below full
        // volume the ear is; the quieter, the more bass and treble it needs.
        val belowFullDb = -VolumeCurve.RANGE_DB.toFloat() * (1f - volumeFraction.coerceIn(0f, 1f))
        val loudness = ((belowFullDb - LOUDNESS_FROM_DB) / (LOUDNESS_FULL_DB - LOUDNESS_FROM_DB)).coerceIn(0f, 1f)
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

        /** Unity gain at the centre: the band itself, to be taken away in part. */
        fun bandPass(rate: Int, hz: Double, q: Double) {
            val w = 2 * PI * hz / rate; val alpha = sin(w) / (2 * q); val c = cos(w)
            set(alpha, 0.0, -alpha, 1 + alpha, -2 * c, 1 - alpha)
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

    /**
     * The room. Early reflections come off the side walls, floor and ceiling at
     * times that differ in each ear, which is what places sound outside the head;
     * for kirtan a warm tail follows, from four delay lines mixed so their echoes
     * never line up (a feedback delay network). Only the band a hall carries goes
     * in: no deep bass, which would boom, and no top, which a hall soaks up.
     * Katha gets a small, close room and no tail, so words stay clear.
     */
    private class Hall(private val rate: Int) {
        private val send = FloatArray(SEND_SIZE)
        private var sendIndex = 0
        private var sendLow = 0f
        private var sendHigh = 0f
        private val sendLowCoef = coef(250.0)
        private val sendHighCoef = coef(6_500.0)
        private var tapsL = IntArray(0); private var gainsL = FloatArray(0)
        private var tapsR = IntArray(0); private var gainsR = FloatArray(0)
        private var earlyMix = 0f
        private var tailMix = 0f
        private var tailInputDelay = 1
        private val lines = Array(TAIL_DELAYS.size) { FloatArray(TAIL_DELAYS[it] * rate / 44_100) }
        private val lineIndex = IntArray(TAIL_DELAYS.size)
        private val lineFeedback = FloatArray(TAIL_DELAYS.size)
        private val lineDamp = FloatArray(TAIL_DELAYS.size)
        private var damping = .3f
        var outL = 0f; private set
        var outR = 0f; private set

        fun configure(speech: Boolean) {
            val scale = if (speech) .55 else 1.0
            tapsL = IntArray(EARLY_L.size) { (EARLY_L[it] * scale * rate / 1000).toInt().coerceIn(1, SEND_SIZE - 1) }
            tapsR = IntArray(EARLY_R.size) { (EARLY_R[it] * scale * rate / 1000).toInt().coerceIn(1, SEND_SIZE - 1) }
            gainsL = EARLY_GAINS_L.copyOf()
            gainsR = EARLY_GAINS_R.copyOf()
            earlyMix = if (speech) .18f else .45f
            tailMix = if (speech) 0f else .24f
            damping = .35f
            tailInputDelay = (.024 * rate).toInt()
            val rt60 = 1.6
            for (index in lines.indices) {
                lineFeedback[index] = 10.0.pow(-3.0 * lines[index].size / (rt60 * rate)).toFloat()
            }
        }

        fun reset() {
            send.fill(0f); sendIndex = 0; sendLow = 0f; sendHigh = 0f
            for (line in lines) line.fill(0f)
            lineIndex.fill(0); lineDamp.fill(0f)
            outL = 0f; outR = 0f
        }

        fun process(l: Float, r: Float) {
            // What reaches the walls: both speakers, the hall's band only.
            val mid = (l + r) * .5f
            sendHigh += (mid - sendHigh) * sendHighCoef
            sendLow += (sendHigh - sendLow) * sendLowCoef
            send[sendIndex] = sendHigh - sendLow
            var earlyL = 0f
            var earlyR = 0f
            for (tap in tapsL.indices) earlyL += send[(sendIndex - tapsL[tap]) and SEND_MASK] * gainsL[tap]
            for (tap in tapsR.indices) earlyR += send[(sendIndex - tapsR[tap]) and SEND_MASK] * gainsR[tap]
            var tailL = 0f
            var tailR = 0f
            if (tailMix > 0f) {
                val input = send[(sendIndex - tailInputDelay) and SEND_MASK] * TAIL_INPUT
                // Read the four lines, damp them, mix them so no echo repeats in step.
                val o0 = read(0); val o1 = read(1); val o2 = read(2); val o3 = read(3)
                val half = (o0 + o1 + o2 + o3) * .5f
                write(0, o0 - half + input)
                write(1, o1 - half - input)
                write(2, o2 - half + input)
                write(3, o3 - half - input)
                tailL = o0 + o2
                tailR = o1 + o3
            }
            sendIndex = (sendIndex + 1) and SEND_MASK
            outL = earlyL * earlyMix + tailL * tailMix
            outR = earlyR * earlyMix + tailR * tailMix
        }

        private fun read(index: Int): Float {
            val value = lines[index][lineIndex[index]]
            lineDamp[index] += (value - lineDamp[index]) * (1f - damping)
            return lineDamp[index] * lineFeedback[index]
        }

        private fun write(index: Int, value: Float) {
            val line = lines[index]
            line[lineIndex[index]] = value
            lineIndex[index] = if (lineIndex[index] + 1 == line.size) 0 else lineIndex[index] + 1
        }

        private fun coef(hz: Double): Float = (1.0 - exp(-2.0 * PI * hz / rate)).toFloat()

        companion object {
            private const val SEND_SIZE = 4_096
            private const val SEND_MASK = SEND_SIZE - 1
            private const val TAIL_INPUT = .35f
            // Milliseconds; each ear's walls are at different distances.
            private val EARLY_L = doubleArrayOf(11.3, 17.9, 23.4, 31.7, 38.9, 47.5, 56.2, 64.1)
            private val EARLY_R = doubleArrayOf(12.7, 16.1, 26.3, 29.8, 41.2, 44.9, 59.3, 69.7)
            // Alternating signs keep the reflections from colouring the tone.
            private val EARLY_GAINS_L = floatArrayOf(.42f, -.35f, .30f, -.25f, .21f, -.17f, .14f, -.11f)
            private val EARLY_GAINS_R = floatArrayOf(-.40f, .36f, -.29f, .26f, -.20f, .18f, -.13f, .10f)
            private val TAIL_DELAYS = intArrayOf(1_557, 1_867, 2_251, 2_671)
        }
    }

    private companion object {
        const val RAMP_SECONDS = .2f
        const val SQRT_HALF = .70710677f
        const val SILENCE_ENERGY = 1e-8
        const val ONE_SIDED_RATIO = .01
        const val BLOCK_SMOOTHING = .3f
        const val METER_SECONDS = 3.0
        const val METER_SETTLE_SECONDS = .4
        const val METER_SETTLE_FRAMES = 44_100L
        const val GATE_LUFS = -50f
        const val MAX_CUT_DB = 1f
        const val RISE_DB_PER_SECOND = 3f
        const val FALL_DB_PER_SECOND = 8f
        const val SETTLE_DB_PER_SECOND = 24f
        const val KNEE_DB = 8f
        const val CROSSFEED_DELAY_SECONDS = .0003
        const val CROSSFEED_DELAY_MAX = 64
        const val CROSSFEED_MASK = CROSSFEED_DELAY_MAX - 1
        const val CROSSFEED_CUTOFF_HZ = 700.0
        const val LOUDNESS_FROM_DB = 12f
        const val LOUDNESS_FULL_DB = 40f
        const val LOUDNESS_BASS_DB = 6.0
        const val LOUDNESS_TREBLE_DB = 2.5
        const val LIMIT_CEILING = .891f
        const val LIMITER_ATTACK_SECONDS = .0003
        const val LIMITER_RELEASE_SECONDS = .12
        const val SOFT_KNEE = .9f

        fun dbToGain(db: Double): Float = 10.0.pow(db / 20).toFloat()

        /** Straight up to the knee, then bends smoothly towards full scale, never past it. */
        fun softClip(x: Float): Float {
            val a = abs(x)
            if (a <= SOFT_KNEE) return x
            val bent = SOFT_KNEE + (1f - SOFT_KNEE) * tanh(((a - SOFT_KNEE) / (1f - SOFT_KNEE)).toDouble()).toFloat()
            return if (x < 0f) -bent else bent
        }
    }
}
