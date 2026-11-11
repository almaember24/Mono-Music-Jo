package com.prism.music

import com.prism.music.data.looks.BuiltInLooks
import com.prism.music.data.looks.LookStore
import com.prism.music.data.prefs.Corners
import com.prism.music.data.prefs.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

/** Looks' switching and History, on a real settings store. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
class LookStoreTest {
    @Test
    fun historyNamesEachChangeForWhatItWas(): Unit = runBlocking {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val settings = SettingsRepository(app, scope)
        settings.applyLook(emptyMap())
        withTimeout(5_000) { settings.lookValues.first { it.isEmpty() } }
        val looks = LookStore(app, settings, scope)
        val neon = BuiltInLooks.of("neon")!!

        looks.apply(neon)
        withTimeout(5_000) { looks.steps.first { it.firstOrNull()?.label == "Look → Neon" } }

        // Picked again: nothing changes, so the next real change mustn't be taken for the switch.
        looks.apply(neon)
        delay(300)
        settings.setCorners(Corners.ROUND)
        val step = withTimeout(5_000) { looks.steps.first { it.size >= 2 } }.first()
        assertEquals("Corners → Round", step.label)

        // Undo puts Neon's corners back, and leaves no step for itself.
        looks.undo()
        withTimeout(5_000) { settings.lookValues.first { it["corners"] == "s:SHARP" } }
        assertEquals("Look → Neon", looks.steps.value.first().label)

        // Your own mix (Neon with round corners isn't any Look) is kept as "Before Paper" when Paper goes on.
        settings.setCorners(Corners.EXTRA)
        withTimeout(5_000) { settings.lookValues.first { it["corners"] == "s:EXTRA" } }
        val paper = BuiltInLooks.of("paper")!!
        assertEquals(true, looks.changes(paper)?.startsWith("Changes your theme, colors, type, backgrounds and layout"))
        looks.apply(paper)
        val before = looks.mine.value.single { it.id == LookStore.BEFORE_ID }
        assertEquals("Before Paper", before.name)
        assertEquals("s:EXTRA", before.values["corners"])
        assertEquals("s:MONO", before.values["font_choice"])
        // Switching from a Look (Paper) to another keeps nothing new.
        looks.apply(neon)
        assertEquals("Before Paper", looks.mine.value.single { it.id == LookStore.BEFORE_ID }.name)
        looks.delete(LookStore.BEFORE_ID)
    }

    @Test
    fun yourLookCanBeUpdatedWithLaterChanges(): Unit = runBlocking {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val settings = SettingsRepository(app, scope)
        settings.applyLook(emptyMap())
        withTimeout(5_000) { settings.lookValues.first { it.isEmpty() } }
        val looks = LookStore(app, settings, scope)

        settings.setCorners(Corners.SHARP)
        withTimeout(5_000) { settings.lookValues.first { it["corners"] == "s:SHARP" } }
        val mine = looks.saveCurrent("Mine")
        withTimeout(5_000) { looks.currentId.first { it == mine.id } }
        withTimeout(5_000) { looks.edited.first { it == null } }

        // Changed (back to how Prism comes, which is Prism's own Look: still a change to yours) after saving: Prism offers to update it.
        settings.setCorners(Corners.ROUND)
        assertEquals(mine.id, withTimeout(5_000) { looks.edited.first { it != null } }!!.id)
        assertEquals(true, looks.edits(mine)?.startsWith("You've changed its"))

        val updated = looks.update(mine)
        assertEquals("s:ROUND", updated.values["corners"])
        assertEquals(mine.created, updated.created)
        assertEquals("Mine", updated.name)
        withTimeout(5_000) { looks.currentId.first { it == mine.id } }
        withTimeout(5_000) { looks.edited.first { it == null } }
        assertEquals(1, looks.mine.value.count { it.id == mine.id })

        // Picking a Look that isn't yours means later changes aren't offered to Mine.
        looks.apply(BuiltInLooks.of("paper")!!)
        settings.setCorners(Corners.EXTRA)
        withTimeout(5_000) { settings.lookValues.first { it["corners"] == "s:EXTRA" } }
        delay(300)
        assertEquals(null, looks.edited.value)
        looks.delete(mine.id)
        looks.delete(LookStore.BEFORE_ID)
    }

    /**
     * 1.8.0: a seasonal Look goes away with its month. On it at the turn, the Look before it comes back (once: a Look
     * picked afterwards stays); your own mix comes back through "Before <Look>"; changed since, your changes stay and only
     * its background goes.
     */
    @Test
    fun theEndOfTheSeasonBringsBackTheLookBefore(): Unit = runBlocking {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        com.prism.music.data.looks.Seasons.clock = java.time.Clock.fixed(java.time.Instant.parse("2026-11-15T12:00:00Z"), java.time.ZoneId.of("UTC"))
        try {
            val settings = SettingsRepository(app, scope)
            settings.applyLook(emptyMap())
            withTimeout(5_000) { settings.lookValues.first { it.isEmpty() } }
            val looks = LookStore(app, settings, scope)
            looks.mine.value.forEach { looks.delete(it.id) }
            val harvest = BuiltInLooks.of("harvest")!!
            val paper = BuiltInLooks.of("paper")!!

            // On Paper, then Harvest in November.
            looks.apply(paper)
            withTimeout(5_000) { looks.currentId.first { it == "paper" } }
            looks.apply(harvest)
            withTimeout(5_000) { looks.currentId.first { it == "harvest" } }
            // Still November: nothing ends.
            assertEquals(false, looks.endSeasons(java.time.LocalDate.of(2026, 11, 30)))
            assertEquals("harvest", looks.currentId.value)
            // December 1st: Paper comes back.
            assertEquals(true, looks.endSeasons(java.time.LocalDate.of(2026, 12, 1)))
            withTimeout(5_000) { looks.currentId.first { it == "paper" } }
            withTimeout(5_000) { looks.steps.first { it.firstOrNull()?.label == "Harvest is over: back to Paper" } }
            // Only once a turn: put back on (by hand) in December, it's left alone the rest of that season.
            looks.apply(harvest)
            withTimeout(5_000) { looks.currentId.first { it == "harvest" } }
            assertEquals(false, looks.endSeasons(java.time.LocalDate.of(2026, 12, 2)))

            // Your own mix (round corners), then Holiday in December: January brings your mix back.
            settings.applyLook(emptyMap())
            settings.setCorners(Corners.EXTRA)
            withTimeout(5_000) { settings.flow.first { it.corners == Corners.EXTRA } }
            val mix = withTimeout(5_000) { settings.lookValues.first { it["corners"] == "s:EXTRA" && it.size == 1 } }
            delay(300)
            looks.apply(BuiltInLooks.of("holiday")!!)
            withTimeout(5_000) { looks.currentId.first { it == "holiday" } }
            assertEquals(true, looks.endSeasons(java.time.LocalDate.of(2027, 1, 1)))
            withTimeout(5_000) { settings.lookValues.first { it == mix } }

            // Changed since: Holiday with sharp corners. At the next turn only its background goes; the colors and the
            // corners are the listener's now.
            looks.apply(BuiltInLooks.of("holiday")!!)
            settings.setCorners(Corners.SHARP)
            withTimeout(5_000) { settings.lookValues.first { it["corners"] == "s:SHARP" } }
            assertEquals(true, looks.endSeasons(java.time.LocalDate.of(2028, 1, 1)))
            val after = withTimeout(5_000) { settings.lookValues.first { !(it["backdrops"] ?: "").contains("HOLIDAY") } }
            assertEquals("s:SHARP", after["corners"])
            assertEquals("s:HOLIDAY", after["spectrum_preset"])
        } finally {
            com.prism.music.data.looks.Seasons.clock = null
            looks@ run { }
        }
    }

    /** #174: Day and night switching away from your own settings keeps them, says so once, and can bring them back. */
    @Test
    fun theScheduleSaysWhereYourSettingsWent(): Unit = runBlocking {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val settings = SettingsRepository(app, scope)
        settings.applyLook(emptyMap())
        withTimeout(5_000) { settings.lookValues.first { it.isEmpty() } }
        val looks = LookStore(app, settings, scope)
        looks.dismissKept()
        // Own Looks other tests left in the shared store could match these settings, which then aren't "your own".
        looks.mine.value.forEach { looks.delete(it.id) }

        settings.setCorners(Corners.EXTRA)
        withTimeout(5_000) { settings.lookValues.first { it["corners"] == "s:EXTRA" } }
        // apply() reads the settings as Prism holds them, which can trail the store by a moment.
        withTimeout(5_000) { settings.flow.first { it.corners == Corners.EXTRA } }
        val midnight = BuiltInLooks.of("midnight")!!
        looks.apply(midnight, "Midnight for the night", scheduled = true)
        assertEquals("Midnight", looks.keptBySchedule.value)
        val before = looks.mine.value.single { it.id == LookStore.BEFORE_ID }
        assertEquals("Before Midnight", before.name)
        assertEquals(true, before.note.contains("Day and night"))

        // A switch you make yourself says nothing; the notice brings your settings back.
        looks.bringBackKept()
        assertEquals(null, looks.keptBySchedule.value)
        withTimeout(5_000) { settings.lookValues.first { it["corners"] == "s:EXTRA" } }
        looks.delete(LookStore.BEFORE_ID)
    }
}
