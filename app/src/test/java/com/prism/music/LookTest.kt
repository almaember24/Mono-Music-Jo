package com.prism.music

import com.prism.music.data.looks.Backgrounds
import com.prism.music.data.looks.BuiltInLooks
import com.prism.music.data.looks.Look
import com.prism.music.data.looks.LookCode
import com.prism.music.data.looks.LookIcons
import com.prism.music.data.looks.LookLabels
import com.prism.music.data.looks.SunClock
import com.prism.music.data.looks.ZonePlaces
import com.prism.music.data.prefs.LookValue
import com.prism.music.data.prefs.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/** Looks (1.7.3): what a Look holds, switching in one edit, share codes, the labels and the day / night clock. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
class LookTest {
    private val context get() = org.robolectric.RuntimeEnvironment.getApplication()
    private fun repo() = SettingsRepository(context, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    @Test
    fun everySettingIsInALookOrSaidNotToBe() {
        val r = repo()
        val inLook = r.lookKinds.keys
        val unsorted = r.allKeyNames - inLook - r.notLookNames
        assertTrue("Settings in neither a Look nor SettingsRepository.notLook: $unsorted", unsorted.isEmpty())
        assertTrue("In both: ${inLook.intersect(r.notLookNames)}", inLook.intersect(r.notLookNames).isEmpty())
        // What Reset look and Reset colors put back is all in a Look.
        assertTrue("Reset keys missing from Looks: ${r.resetNames - inLook}", (r.resetNames - inLook).isEmpty())
        // And each reads in words in the undo bar and History.
        assertTrue("Look settings without a label: ${inLook.filterNot(LookLabels::named)}", inLook.all(LookLabels::named))
    }

    @Test
    fun aLookGoesInAndComesBackWhole(): Unit = runBlocking {
        val r = repo()
        val values = r.lookKinds.mapValues { (_, kind) ->
            when (kind) { 'b' -> "b:1"; 'i' -> "i:7"; 'f' -> "f:0.5"; 'l' -> "l:7"; else -> "s:X" }
        }
        r.applyLook(values)
        val back = withTimeout(5_000) { r.lookValues.first { it == values } }
        assertEquals(values, back)
        // A preview of a Look is what the settings become with it.
        val preview = r.previewLook(values)
        withTimeout(5_000) { r.flow.first { it == preview } }
        // Applying an empty Look puts every look setting back to Prism's default.
        r.applyLook(emptyMap())
        withTimeout(5_000) { r.lookValues.first { it.isEmpty() } }
    }

    @Test
    fun aLookCanOnlySetLookSettingsWithTheRightKind(): Unit = runBlocking {
        val r = repo()
        // The store is one per process: a Look an earlier test class left (LookStoreTest's Neon) goes first.
        r.applyLook(emptyMap())
        withTimeout(5_000) { r.lookValues.first { it.isEmpty() } }
        r.applyLook(mapOf("cookie" to "s:SAPISID=stolen", "pure_black" to "s:yes", "corners" to "s:SHARP"))
        val now = withTimeout(5_000) { r.lookValues.first { it.isNotEmpty() } }
        assertEquals(mapOf("corners" to "s:SHARP"), now)
        assertEquals("", r.current.cookie)
        assertEquals(false, r.current.pureBlack)
    }

    @Test
    fun valuesKeepTheirKind() {
        assertEquals(true, LookValue.untag("b:1", 'b'))
        assertEquals(0.35f, LookValue.untag(LookValue.tag(0.35f)!!, 'f'))
        assertEquals(-12345, LookValue.untag(LookValue.tag(-12345)!!, 'i'))
        assertNull(LookValue.untag("i:7", 'b'))
        assertNull(LookValue.untag("f:NaN", 'f'))
        assertNull(LookValue.untag("nonsense", 's'))
    }

    @Test
    fun codesTravelAndAreChecked() {
        val kinds = repo().lookKinds
        val look = Look(
            "x", "  My  Look  ",
            values = mapOf(
                "corners" to "s:SHARP", "accent" to "i:-13000000", "cookie" to "s:secret",
                Look.BACKDROPS to Backgrounds.tag(mapOf("HOME" to "AURORA", "SEARCH" to "PHOTO|/data/user/0/x/secret.jpg")),
            ),
        )
        val code = LookCode.encode(look)
        assertTrue(code.startsWith(LookCode.PREFIX))
        val back = LookCode.decode("Here you go:\n$code\nenjoy", kinds)
        assertNotNull(back)
        assertEquals("My  Look", back!!.name)
        assertEquals("s:SHARP", back.values["corners"])
        assertNull("Only look settings", back.values["cookie"])
        assertEquals("No paths from someone's phone", mapOf("HOME" to "AURORA"), Backgrounds.parse(back.values[Look.BACKDROPS]))
        assertNull(LookCode.decode("PRISMLOOK1:notreally", kinds))
        assertNull(LookCode.decode("nothing here", kinds))
        // Every icon pack and fill style travels (1.7.7's Facet and Dots were once turned back into Prism's).
        for (pack in listOf("FACET", "DOTS", "CUPERTINO")) {
            val icons = LookCode.decode(LookCode.encode(look.copy(icons = LookIcons(pack = pack, fill = "DUOTONE"))), kinds)!!.icons
            assertEquals(pack, icons.pack)
            assertEquals("DUOTONE", icons.fill)
        }
        assertEquals("PRISM", LookCode.decode(LookCode.encode(look.copy(icons = LookIcons(pack = "NOPE", fill = "NOPE"))), kinds)!!.icons.pack)
        // Prism's own Looks are valid Looks, and each fits a QR code.
        BuiltInLooks.all.forEach { l ->
            assertEquals(l.id, l.values, LookCode.sanitized(l, kinds).values)
            assertTrue(l.id, LookCode.encode(l).length <= LookCode.MAX_QR_CHARS)
        }
    }

    @Test
    fun changesReadInWords() {
        assertEquals("Corners → Sharp", LookLabels.change(emptyMap(), mapOf("corners" to "s:SHARP"), emptyList()))
        assertEquals("Pure black → On", LookLabels.change(mapOf("pure_black" to "b:0"), mapOf("pure_black" to "b:1"), emptyList()))
        assertEquals("Text size → 110%", LookLabels.change(emptyMap(), mapOf("text_scale" to "f:1.1"), emptyList()))
        assertEquals(
            "Liquid Glass → On and 1 more",
            LookLabels.change(emptyMap(), mapOf("liquid_glass" to "b:1", "frosted_glass" to "b:0"), listOf("liquid_glass", "frosted_glass")),
        )
        assertNull(LookLabels.change(mapOf("a" to "b:1"), mapOf("a" to "b:1"), emptyList()))
    }

    @Test
    fun sunriseAndSunsetAreRoughlyRight() {
        // New York, midsummer: about 5:25 and 20:31 local (09:25 and 00:31 UTC).
        val (rise, set) = SunClock.sunriseSunset(LocalDate.of(2026, 6, 21), 40.71, -74.01)
        assertTrue("sunrise $rise", abs(rise!!.epochSecond - Instant.parse("2026-06-21T09:25:00Z").epochSecond) < 10 * 60)
        assertTrue("sunset $set", abs(set!!.epochSecond - Instant.parse("2026-06-22T00:31:00Z").epochSecond) < 10 * 60)
        // Tromsø in June: the sun doesn't set, so it's day all day.
        val zone = ZoneId.of("Europe/Oslo")
        val noon = LocalDate.of(2026, 6, 21).atTime(23, 30).atZone(zone).toInstant()
        assertEquals(SunClock.Part.DAY, SunClock.around(noon, zone, true, 0, 0, 69.65 to 18.96).first.to)
        // Set times: 07:00 and 20:00.
        val evening = LocalDate.of(2026, 10, 22).atTime(21, 0).atZone(zone).toInstant()
        val (last, next) = SunClock.around(evening, zone, false, 7 * 60, 20 * 60, 0.0 to 0.0)
        assertEquals(SunClock.Part.NIGHT, last.to)
        assertEquals(SunClock.Part.DAY, next.to)
        assertEquals(LocalDate.of(2026, 10, 23).atTime(7, 0).atZone(zone).toInstant(), next.at)
    }

    @Test
    fun timeZonesFindTheirPlace() {
        val table = File("src/main/assets/looks/zones.txt").useLines { ZonePlaces.load(it) }
        val ny = ZonePlaces.of(table, ZoneId.of("America/New_York"))
        assertEquals(40.71, ny.first, 0.01)
        // Old names lead to the zone they're kept for.
        assertEquals(ny, ZonePlaces.of(table, ZoneId.of("US/Eastern")))
        assertEquals(22.53, ZonePlaces.of(table, ZoneId.of("Asia/Calcutta")).first, 0.01)
        // A zone with no place: its offset's longitude.
        assertEquals(-45.0, ZonePlaces.of(table, ZoneId.of("GMT-03:00")).second, 0.01)
    }
}
