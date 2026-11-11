package com.prism.music

import com.prism.music.data.looks.Backgrounds
import com.prism.music.data.looks.Look
import com.prism.music.data.looks.LookCode
import com.prism.music.data.looks.LookIcons
import com.prism.music.data.looks.LookLabels
import com.prism.music.data.prefs.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random

/**
 * Not a check: the release check's random Looks (`.claude/release-check-plan.md` §5.6). With PRISM_RANDOM_LOOKS=<file>,
 * writes PRISM_LOOKS_COUNT (default 5) Look codes, one per line as `name|code`, each a different mix of every visual
 * setting a Look holds (theme, colors and the light, glass and backgrounds, fonts and text, shapes, icons, tabs and mini
 * player, Home and Library, the player, lyrics), plus `<file>.txt` naming what each one sets. Each run gets new ones:
 * the seed is the time, and a mix already in PRISM_LOOKS_USED (one fingerprint per line) is made again; the new
 * fingerprints are added to it. How much each one changes varies (a quarter of the settings up to nearly all), so some
 * are near Prism's own and some far from it.
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
class ReleaseCheckLooks {
    private val context get() = org.robolectric.RuntimeEnvironment.getApplication()

    /** Sliders' own ranges (Settings), so a value is one a listener could pick. */
    private val floats = mapOf(
        "text_scale" to 0.85f..1.25f, "lyrics_scale" to 0.75f..1.4f, "backdrop_dim" to 0f..0.8f, "player_backdrop_show" to 0f..1f,
        "surface_tint_strength" to 0.2f..2f, "mesh_speed" to 0f..1f, "mesh_intensity" to 0.4f..1.6f, "player_blur" to 0.5f..1.5f,
        "scrubber_thickness" to 0.6f..2f, "lyrics_blur_amount" to 0f..2f, "lyrics_spacing" to 0.6f..1.8f, "lyrics_others_alpha" to 0.2f..1f,
        "lyrics_backing_size" to 0.4f..0.9f, "lyrics_extra_size" to 0.7f..1.4f, "corner_scale" to 0f..2f,
    )
    private val colors = setOf("accent", "accent_light", "surface_color", "lyrics_sung_custom", "lyrics_other_custom", "lyrics_bg_color", "lyrics_bg_color2")
    /** Text settings a random value would break or that need a file: left at their defaults. */
    private val skip = setOf("app_font_name", "tab_names", "library_views", "spectrum_stops", "replay_card")
    private val screens = listOf("HOME", "SEARCH", "LIBRARY", "REPLAY", "SETTINGS", "PLAYER")

    @Test
    fun writeRandomLooks() {
        val out = System.getenv("PRISM_RANDOM_LOOKS") ?: return
        val count = System.getenv("PRISM_LOOKS_COUNT")?.toIntOrNull() ?: 5
        val usedFile = System.getenv("PRISM_LOOKS_USED")?.let(::File)
        val used = usedFile?.takeIf { it.exists() }?.readLines()?.map { it.substringBefore(' ') }?.toMutableSet() ?: mutableSetOf()
        val kinds = SettingsRepository(context, CoroutineScope(SupervisorJob() + Dispatchers.Default)).lookKinds
        val seed = System.currentTimeMillis()
        val rnd = Random(seed)
        val looks = mutableListOf<Look>()
        val shares = listOf(0.25f, 0.5f, 0.75f, 0.95f)
        var made = 0
        while (looks.size < count && made < count * 50) {
            made++
            val share = shares[looks.size % shares.size]
            val look = randomLook("Check ${looks.size + 1}", kinds, rnd, share)
            val fp = fingerprint(look)
            if (fp in used) continue
            used += fp
            looks += look
        }
        File(out).writeText(looks.joinToString("\n") { "${it.name.replace(' ', '-').lowercase()}|${LookCode.encode(it)}" } + "\n")
        File("$out.txt").writeText(
            "Random Looks, seed $seed\n\n" + looks.joinToString("\n\n") { l ->
                "${l.name} (${l.values.size} settings; icons ${l.icons.pack} ${l.icons.weight} ${l.icons.fill}${if (l.icons.rounded) "" else " square ends"}):\n" +
                    l.values.entries.sortedBy { it.key }.joinToString("\n") { (k, v) -> "  ${LookLabels.title(k)}: ${LookLabels.value(k, v)}" }
            } + "\n",
        )
        usedFile?.appendText(looks.joinToString("") { "${fingerprint(it)} ${it.name} $seed\n" })
        // Prism's own look (a Look that sets nothing puts every look setting back to its default), to end with.
        File(File(out).parentFile, "prism-look.txt").writeText(LookCode.encode(Look("prism", "Prism")))
        println("[looks] ${looks.size} random Looks (seed $seed) -> $out")
    }

    private fun randomLook(name: String, kinds: Map<String, Char>, rnd: Random, share: Float): Look {
        val values = linkedMapOf<String, String>()
        for ((key, kind) in kinds) {
            if (key in skip || rnd.nextFloat() > share) continue
            val v: String? = when {
                key == Look.BACKDROPS -> backdrops(rnd)
                key in colors -> "i:${(0xFF000000 or rnd.nextLong(0x1000000)).toInt()}"
                key == "player_art_corners" -> "i:${rnd.nextInt(0, 33)}"
                kind == 'b' -> if (rnd.nextBoolean()) "b:1" else "b:0"
                kind == 'f' -> floats[key]?.let { r -> "f:%.2f".format(java.util.Locale.ROOT, r.start + rnd.nextFloat() * (r.endInclusive - r.start)) }
                kind == 's' -> choice(key, rnd)
                else -> null
            }
            if (v != null) values[key] = v
        }
        // The light's own colors only come with "Your own" stops, which this leaves out.
        if (values["spectrum_preset"] == "s:CUSTOM") values.remove("spectrum_preset")
        // The start tab is one of the tabs shown.
        values["nav_tabs"]?.let { tabs -> values["start_tab"] = "s:" + tabs.removePrefix("s:").split(',').random(rnd) }
        val icons = LookIcons(
            pack = com.prism.music.ui.theme.IconPack.entries.random(rnd).name,
            weight = com.prism.music.ui.theme.IconWeight.entries.random(rnd).name,
            rounded = rnd.nextBoolean(), likeStar = rnd.nextBoolean(),
            fill = com.prism.music.ui.theme.IconFill.entries.random(rnd).name,
        ).takeIf { rnd.nextFloat() <= share } ?: LookIcons()
        return Look("check-${name.substringAfterLast(' ')}", name, values = values, icons = icons)
    }

    /** Every choice setting's stored names, from the labels Looks already keep for them (read here, not added to the app). */
    @Suppress("UNCHECKED_CAST")
    private val choices: Map<String, Map<String, String>> by lazy {
        val f = LookLabels::class.java.getDeclaredField("choices\$delegate").apply { isAccessible = true }
        (f.get(LookLabels) as Lazy<Map<String, Map<String, String>>>).value
    }

    private fun choice(key: String, rnd: Random): String? {
        val options = choices[key]?.keys?.filter { it.isNotEmpty() && it != "CUSTOM" } ?: return null
        if (options.isEmpty()) return null
        return when (key) {
            // Lists: a few of the choices, in some order.
            "nav_tabs" -> "s:" + options.shuffled(rnd).take(rnd.nextInt(3, minOf(6, options.size) + 1)).joinToString(",")
            "player_buttons" -> "s:" + options.shuffled(rnd).take(rnd.nextInt(3, minOf(6, options.size) + 1)).joinToString(",")
            "player_deck" -> "s:" + options.shuffled(rnd).joinToString(",")
            else -> "s:" + options.random(rnd)
        }
    }

    /** A background for some screens (the rest keep theirs); never a photo, which needs a file. */
    private fun backdrops(rnd: Random): String {
        val styles = com.prism.music.ui.theme.Backdrop.entries.map { it.name }.filterNot { it.contains("PHOTO") }
        return Backgrounds.tag(screens.filter { rnd.nextBoolean() }.ifEmpty { listOf("HOME") }.associateWith { styles.random(rnd) })
    }

    private fun fingerprint(look: Look): String {
        val text = look.values.toSortedMap().entries.joinToString(";") { "${it.key}=${it.value}" } + "|" + look.icons
        return MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    }
}
