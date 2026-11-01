package com.schulzcode.y2player.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class SoundEnhancerTest {
    private val rate = PcmFormat.SAMPLE_RATE
    private val block = PcmFormat.BLOCK_SAMPLES

    private fun noise(seconds: Double, seed: Int = 1, left: Float = .25f, right: Float = .25f, mono: Boolean = false): FloatArray {
        val random = Random(seed)
        val frames = (seconds * rate).toInt()
        return FloatArray(frames * 2).also { pcm ->
            for (frame in 0 until frames) {
                val a = random.nextFloat() * 2 - 1
                val b = if (mono) a else random.nextFloat() * 2 - 1
                pcm[frame * 2] = a * left
                pcm[frame * 2 + 1] = b * right
            }
        }
    }

    private fun run(enhancer: SoundEnhancer, pcm: FloatArray): FloatArray {
        val out = pcm.copyOf()
        var offset = 0
        while (offset < out.size) {
            val count = minOf(block, out.size - offset)
            enhancer.process(out, offset, count)
            offset += count
        }
        return out
    }

    private fun rms(pcm: FloatArray, channel: Int, from: Int = 0): Double {
        var sum = 0.0; var n = 0
        var i = from * 2 + channel
        while (i < pcm.size) { sum += pcm[i] * pcm[i]; n++; i += 2 }
        return sqrt(sum / n)
    }

    private fun sideRatio(pcm: FloatArray, from: Int): Double {
        var mid = 0.0; var side = 0.0
        var i = from * 2
        while (i < pcm.size) {
            val m = (pcm[i] + pcm[i + 1]) / 2.0; val s = (pcm[i] - pcm[i + 1]) / 2.0
            mid += m * m; side += s * s; i += 2
        }
        return side / mid
    }

    @Test fun offLeavesEverySampleUntouched() {
        val pcm = noise(1.0)
        assertArrayEquals(pcm, run(SoundEnhancer(), pcm), 0f)
    }

    @Test fun onNeverClipsOrProducesNaNEvenFromAFullScaleSource() {
        val enhancer = SoundEnhancer().apply { enabled = true }
        val loud = noise(3.0, left = 1f, right = 1f)
        val out = run(enhancer, loud)
        assertTrue(out.all { !it.isNaN() && abs(it) <= 1f })
        enhancer.kind = SoundEnhancer.Kind.SPEECH
        enhancer.volumeFraction = 0f
        assertTrue(run(enhancer, loud).all { !it.isNaN() && abs(it) <= 1f })
    }

    /** Noise with a voice's spectrum, roughly 200 Hz to 4 kHz, which is where kirtan sits. */
    private fun voiceBand(pcm: FloatArray): FloatArray {
        val low = (1 - kotlin.math.exp(-2 * PI * 4_000.0 / rate)).toFloat()
        val high = (1 - kotlin.math.exp(-2 * PI * 200.0 / rate)).toFloat()
        val out = pcm.copyOf()
        for (channel in 0..1) {
            var lp = 0f; var hp = 0f
            var i = channel
            while (i < out.size) {
                lp += (out[i] - lp) * low
                hp += (lp - hp) * high
                out[i] = (lp - hp) * 3f
                i += 2
            }
        }
        return out
    }

    @Test fun monoKirtanIsOpenedOutByTheHallButSpeechStaysCloser() {
        val mono = voiceBand(noise(3.0, mono = true, left = .2f, right = .2f))
        val music = run(SoundEnhancer().apply { enabled = true }, mono)
        assertTrue("mono music gains width: ${sideRatio(music, rate)}", sideRatio(music, rate) > .03)
        val speech = run(SoundEnhancer().apply { enabled = true; kind = SoundEnhancer.Kind.SPEECH }, mono)
        assertTrue("speech gains less: ${sideRatio(speech, rate)}", sideRatio(speech, rate) < sideRatio(music, rate) * .6)
    }

    @Test fun enhanceIsNeverQuieterThanOff() {
        for (kind in SoundEnhancer.Kind.values()) {
            for (level in floatArrayOf(.05f, .25f, .6f)) {
                val pcm = noise(6.0, seed = 5, left = level, right = level)
                val out = run(SoundEnhancer().apply { enabled = true; this.kind = kind; volumeFraction = 1f }, pcm)
                val ratio = rms(out, 0, rate * 3) / rms(pcm, 0, rate * 3)
                assertTrue("$kind at $level: on/off = $ratio", ratio > .85)
            }
        }
    }

    @Test fun aQuietOldRecordingIsBroughtUp() {
        val quiet = noise(8.0, seed = 7, left = .03f, right = .03f)
        val out = run(SoundEnhancer().apply { enabled = true; volumeFraction = 1f }, quiet)
        val lift = rms(out, 0, rate * 5) / rms(quiet, 0, rate * 5)
        assertTrue("lifted by $lift", lift > 2.5)
    }

    @Test fun silenceStaysSilent() {
        val out = run(SoundEnhancer().apply { enabled = true }, FloatArray(rate * 4))
        assertTrue(out.all { it == 0f })
    }

    @Test fun changingKindPassesThroughTheUntouchedSoundAndComesBack() {
        val enhancer = SoundEnhancer().apply { enabled = true }
        run(enhancer, noise(1.0))
        enhancer.kind = SoundEnhancer.Kind.SPEECH
        val pcm = noise(2.0, seed = 9)
        val out = run(enhancer, pcm)
        assertTrue(out.all { !it.isNaN() && abs(it) <= 1f })
        var differs = 0
        for (i in rate * 2 until pcm.size) if (out[i] != pcm[i]) differs++
        assertTrue("speech tuning is applied after the switch", differs > rate)
    }

    @Test fun aRecordingHeardInOneEarIsCentred() {
        val oneSided = noise(3.0, left = .2f, right = 0f)
        val out = run(SoundEnhancer().apply { enabled = true; kind = SoundEnhancer.Kind.SPEECH }, oneSided)
        val left = rms(out, 0, rate)
        val right = rms(out, 1, rate)
        assertTrue("both ears now hear it: left=$left right=$right", right > left * .7)
    }

    @Test fun turningOffFadesBackToTheUntouchedSound() {
        val enhancer = SoundEnhancer().apply { enabled = true }
        run(enhancer, noise(1.0))
        enhancer.enabled = false
        val pcm = noise(1.0, seed = 3)
        val out = run(enhancer, pcm)
        val tail = rate / 2
        for (i in tail * 2 until pcm.size) assertEquals(pcm[i], out[i], 0f)
    }

    @Test fun lowVolumeAddsBass() {
        val tone = FloatArray(rate * 2 * 2).also { pcm ->
            for (frame in 0 until rate * 2) {
                val v = (sin(2 * PI * 80.0 * frame / rate) * .1).toFloat()
                pcm[frame * 2] = v; pcm[frame * 2 + 1] = v
            }
        }
        val quiet = run(SoundEnhancer().apply { enabled = true; volumeFraction = .05f }, tone)
        val loud = run(SoundEnhancer().apply { enabled = true; volumeFraction = 1f }, tone)
        assertTrue(rms(quiet, 0, rate) > rms(loud, 0, rate) * 1.3)
    }

    @Test fun aMinuteOfAudioTakesAFractionOfAMinute() {
        val enhancer = SoundEnhancer().apply { enabled = true }
        val pcm = noise(10.0)
        run(enhancer, pcm)
        val started = System.nanoTime()
        repeat(6) { run(enhancer, pcm) }
        val seconds = (System.nanoTime() - started) / 1e9
        println("SoundEnhancer: 60 s of audio in ${"%.3f".format(seconds)} s on the JVM")
        assertTrue(seconds < 6.0)
    }
}
