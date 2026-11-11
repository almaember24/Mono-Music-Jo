package com.prism.music

import com.prism.music.data.HeadphoneEq
import com.prism.music.playback.Biquad
import com.prism.music.playback.EqFilter
import com.prism.music.playback.EqProfile
import com.prism.music.playback.ParametricEqProcessor
import com.prism.music.playback.responseDb
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** Headphone correction: AutoEq's files read right, filters shaped right, and the processor doing what they say. */
class HeadphoneEqTest {
    private val sample = """
        Preamp: -6.4 dB
        Filter 1: ON LSC Fc 105 Hz Gain 5.5 dB Q 0.70
        Filter 2: ON PK Fc 2400 Hz Gain -3.2 dB Q 1.41
        Filter 3: OFF PK Fc 5000 Hz Gain 9.0 dB Q 2.00
        Filter 4: ON HSC Fc 10000 Hz Gain -2.0 dB Q 0.70
    """.trimIndent()

    @Test
    fun readsParametricEq() {
        val p = EqProfile.parse("Test", sample)!!
        assertEquals(-6.4, p.preampDb, 1e-9)
        // The filter that's OFF is left out.
        assertEquals(3, p.filters.size)
        assertEquals(EqFilter(EqFilter.Type.LOW_SHELF, 105.0, 5.5, 0.70), p.filters[0])
        assertEquals(EqFilter.Type.PEAK, p.filters[1].type)
        assertEquals(EqFilter.Type.HIGH_SHELF, p.filters[2].type)
    }

    @Test
    fun filtersHaveTheGainTheySay() {
        val peak = Biquad.of(EqFilter(EqFilter.Type.PEAK, 1000.0, 6.0, 1.0), 48_000)!!
        assertEquals(6.0, peak.responseDb(1000.0, 48_000), 0.01)
        assertTrue(abs(peak.responseDb(50.0, 48_000)) < 0.1)
        val low = Biquad.of(EqFilter(EqFilter.Type.LOW_SHELF, 200.0, -4.0, 0.707), 44_100)!!
        assertEquals(-4.0, low.responseDb(20.0, 44_100), 0.1)
        assertTrue(abs(low.responseDb(5000.0, 44_100)) < 0.1)
        val high = Biquad.of(EqFilter(EqFilter.Type.HIGH_SHELF, 8000.0, 3.0, 0.707), 48_000)!!
        assertEquals(3.0, high.responseDb(20_000.0, 48_000), 0.2)
        // Strength scales the gain.
        assertEquals(3.0, Biquad.of(EqFilter(EqFilter.Type.PEAK, 1000.0, 6.0, 1.0), 48_000, 0.5)!!.responseDb(1000.0, 48_000), 0.01)
        // A band past what the sample rate can carry is left out.
        assertEquals(null, Biquad.of(EqFilter(EqFilter.Type.PEAK, 30_000.0, 6.0, 1.0), 48_000))
        // The whole curve includes the preamp.
        val p = EqProfile.parse("Test", sample)!!
        assertEquals(-6.4 + 5.5, p.responseDb(20.0), 0.5)
    }

    private fun sine(hz: Double, frames: Int, rate: Int, amp: Double = 0.25): ByteBuffer {
        val b = ByteBuffer.allocateDirect(frames * 2 * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until frames) { val v = (amp * sin(2 * PI * hz * i / rate)).toFloat(); b.putFloat(v); b.putFloat(v) }
        b.flip()
        return b
    }

    private fun rms(b: ByteBuffer, skipFrames: Int): Double {
        var sum = 0.0; var n = 0
        val f = b.asFloatBuffer()
        for (i in skipFrames * 2 until f.limit()) { sum += f[i] * f[i].toDouble(); n++ }
        return sqrt(sum / n)
    }

    private fun run(p: ParametricEqProcessor, input: ByteBuffer): ByteBuffer {
        p.queueInput(input)
        return p.output.let { out -> ByteBuffer.allocate(out.remaining()).order(ByteOrder.nativeOrder()).put(out).also { it.flip() } }
    }

    @Test
    fun processorAppliesTheCurve() {
        val rate = 48_000
        val p = ParametricEqProcessor()
        p.configure(EqProfile("Peak", 0.0, listOf(EqFilter(EqFilter.Type.PEAK, 1000.0, 6.0, 1.0))), 1f)
        p.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_FLOAT))
        p.flush()
        val input = sine(1000.0, rate, rate)
        val inRms = rms(input.duplicate().order(ByteOrder.nativeOrder()), 4800)
        val out = run(p, input)
        val gain = 20 * log10(rms(out, 4800) / inRms)
        assertEquals(6.0, gain, 0.1)
    }

    @Test
    fun noProfilePassesStraightThrough() {
        val rate = 48_000
        val p = ParametricEqProcessor()
        p.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_FLOAT))
        p.flush()
        val input = sine(440.0, 4800, rate)
        val copy = input.duplicate().order(ByteOrder.nativeOrder())
        val out = run(p, input)
        val a = copy.asFloatBuffer(); val b = out.asFloatBuffer()
        for (i in 0 until a.limit()) assertEquals(a[i], b[i], 0f)
    }

    @Test
    fun readsAutoEqsIndex() {
        val index = """
            # Index
            - [1MORE Aero (ANC Off)](./HypetheSonics/GRAS%20RA0045%20in-ear/1MORE%20Aero%20(ANC%20Off)) by HypetheSonics on GRAS RA0045
            - [Sennheiser HD 650](./oratory1990/over-ear/Sennheiser%20HD%20650) by oratory1990
        """.trimIndent()
        val e = HeadphoneEq.parseIndex(index)
        assertEquals(2, e.size)
        assertEquals("1MORE Aero (ANC Off)", e[0].name)
        assertEquals(listOf("HypetheSonics", "GRAS RA0045 in-ear", "1MORE Aero (ANC Off)"), e[0].path)
        assertEquals("HypetheSonics on GRAS RA0045", e[0].by)
        assertEquals("oratory1990", e[1].by)
    }

    @Test
    fun searchStartsWithTheModel() {
        assertEquals("AirPods Pro", com.prism.music.ui.screens.modelOf("Brandyn’s AirPods Pro"))
        assertEquals("WH-1000XM5", com.prism.music.ui.screens.modelOf("Alex's WH-1000XM5"))
        assertEquals("Galaxy Buds2 Pro", com.prism.music.ui.screens.modelOf("Galaxy Buds2 Pro (1A2B)"))
        assertEquals("", com.prism.music.ui.screens.modelOf("Wired headphones"))
    }

    @Test
    @org.junit.experimental.categories.Category(LiveNetwork::class)
    fun autoEqLive() {
        // AutoEq's index and one real profile, from GitHub.
        val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
        val text = http.newCall(Request.Builder().url("https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results/INDEX.md").build()).execute().use { it.body.string() }
        val entries = HeadphoneEq.parseIndex(text)
        println("[live] AutoEq index: ${entries.size} headphones")
        assertTrue(entries.size > 1000)
        val hd650 = entries.first { it.name == "Sennheiser HD 650" && it.source == "oratory1990" }
        val url = okhttp3.HttpUrl.Builder().scheme("https").host("raw.githubusercontent.com").addPathSegments("jaakkopasanen/AutoEq/master/results")
            .apply { hd650.path.forEach { addPathSegment(it) } }.addPathSegment("${hd650.name} ParametricEQ.txt").build()
        val body = http.newCall(Request.Builder().url(url).build()).execute().use { it.body.string() }
        println("[live] $url -> ${body.take(80)}")
        val profile = EqProfile.parse(hd650.name, body)
        assertNotNull(profile)
        println("[live] HD 650: preamp ${profile!!.preampDb} dB, ${profile.filters.size} filters")
        assertTrue(profile.filters.size >= 5)
    }
}
