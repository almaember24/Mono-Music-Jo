package com.prism.music.ui.theme

import com.prism.music.R
import com.prism.music.tr
import android.graphics.Typeface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.FontChoice
import com.prism.music.data.prefs.TitleSize
import java.io.File

/**
 * The look & layout knobs every screen reads: how round things are, how much room they get,
 * how wide cards are and how big page titles are. Built from [AppSettings] by [PrismTheme].
 */
@Immutable
data class PrismUi(
    val corner: Float = 1f,
    val density: Float = 1f,
    val cardWidth: Dp = 152.dp,
    val titleSize: TitleSize = TitleSize.LARGE,
    val reduceMotion: Boolean = false,
    /** Covers' and buttons' shapes (1.7.7, Icons & shapes). */
    val songShape: com.prism.music.data.prefs.CoverShape = com.prism.music.data.prefs.CoverShape.ROUNDED,
    val albumShape: com.prism.music.data.prefs.CoverShape = com.prism.music.data.prefs.CoverShape.ROUNDED,
    val artistShape: com.prism.music.data.prefs.CoverShape = com.prism.music.data.prefs.CoverShape.CIRCLE,
    val buttonShape: com.prism.music.data.prefs.ButtonShape = com.prism.music.data.prefs.ButtonShape.PILL,
    /** Prism 1.6's switches, sliders and buttons: Material's, in the accent (1.7.8, Classic controls). */
    val classicControls: Boolean = false,
) {
    /** A corner radius designed at [base] for the default "Round" setting. */
    fun r(base: Dp): Dp = base * corner
    fun shape(base: Dp): RoundedCornerShape = RoundedCornerShape(r(base))

    /** Vertical room designed at [base] for "Comfortable". */
    fun gap(base: Dp): Dp = base * density

    // Aura × Spectrum (1.7): soft corners. The Corners setting still scales them.
    val tile: RoundedCornerShape get() = shape(16.dp)
    val card: RoundedCornerShape get() = shape(22.dp)
    val art: RoundedCornerShape get() = shape(14.dp)
    val smallArt: RoundedCornerShape get() = shape(10.dp)

    /** A song's cover in a list (was [smallArt]). */
    val songArt: androidx.compose.ui.graphics.Shape get() = CoverShapes.of(songShape, r(10.dp))
    /** An album's or playlist's cover (was [art]). */
    val albumArt: androidx.compose.ui.graphics.Shape get() = CoverShapes.of(albumShape, r(14.dp))
    /** An artist's picture (was always a circle). */
    val artistArt: androidx.compose.ui.graphics.Shape get() = CoverShapes.of(artistShape, r(14.dp))
    /** Prism's buttons: a pill, rounded, or square-cornered. */
    val button: androidx.compose.ui.graphics.Shape get() = when (buttonShape) {
        com.prism.music.data.prefs.ButtonShape.PILL -> androidx.compose.foundation.shape.CircleShape
        com.prism.music.data.prefs.ButtonShape.ROUNDED -> shape(12.dp)
        com.prism.music.data.prefs.ButtonShape.SQUARE -> RoundedCornerShape(3.dp)
    }

    /** Material's components (dialogs, sheets, menus, text fields) take these. */
    val materialShapes: Shapes
        get() = Shapes(
            extraSmall = shape(8.dp), small = shape(12.dp), medium = shape(16.dp),
            large = shape(22.dp), extraLarge = shape(28.dp),
        )

    companion object {
        fun from(s: AppSettings) = PrismUi(
            corner = s.shapes.cornerScale ?: s.corners.scale,
            density = s.uiDensity.scale,
            cardWidth = s.cardSize.widthDp.dp,
            titleSize = s.titleSize,
            reduceMotion = s.reduceMotion,
            songShape = s.shapes.songs,
            albumShape = s.shapes.albums,
            artistShape = s.shapes.artists,
            buttonShape = s.shapes.button,
            classicControls = s.shapes.classicControls,
        )
    }
}

val LocalUi = staticCompositionLocalOf { PrismUi() }

/** The font picked for lyrics (#12), or null to keep each lyric style's own. Apply with [withLyricsFont]. */
val LocalLyricsFont = staticCompositionLocalOf<FontFamily?> { null }

/** [this] style in the lyrics font, when one is picked. */
@androidx.compose.runtime.Composable
fun androidx.compose.ui.text.TextStyle.withLyricsFont(): androidx.compose.ui.text.TextStyle =
    LocalLyricsFont.current?.let { copy(fontFamily = it) } ?: this

/** The app-wide typefaces. Variable device fonts are set up at every weight the UI uses. */
@OptIn(ExperimentalTextApi::class)
object AppFonts {
    private val cache = HashMap<FontChoice, FontFamily>()
    private val weights = listOf(200, 300, 400, 500, 600, 700, 800, 900)

    /**
     * The typefaces Prism ships (res/font, each SIL Open Font License 1.1, from google/fonts), all variable: one file each,
     * set up at every weight the UI uses. Latin (and for some Cyrillic / Greek) only: other scripts fall back to the
     * phone's font, glyph by glyph. Fraunces is set at a middle optical size (its small sizes jump from light to heavy at 400, too dark beside the rest), without its wonky letters.
     */
    private fun bundled(choice: FontChoice): Pair<Int, Array<FontVariation.Setting>>? = when (choice) {
        FontChoice.PRISM -> com.prism.music.R.font.outfit to emptyArray()
        FontChoice.CLEAN -> com.prism.music.R.font.manrope to emptyArray()
        FontChoice.GEOMETRIC -> com.prism.music.R.font.plus_jakarta_sans to emptyArray()
        FontChoice.AIRY -> com.prism.music.R.font.urbanist to emptyArray()
        FontChoice.ROUNDED -> com.prism.music.R.font.nunito to emptyArray()
        FontChoice.SERIF -> com.prism.music.R.font.fraunces to arrayOf(FontVariation.Setting("opsz", 48f), FontVariation.Setting("WONK", 0f))
        FontChoice.MONO -> com.prism.music.R.font.geist_mono to emptyArray()
        FontChoice.LEGIBLE -> com.prism.music.R.font.atkinson_hyperlegible_next to emptyArray()
        // OpenDyslexic comes in two weights rather than an axis ([build]).
        FontChoice.DYSLEXIC, FontChoice.SYSTEM, FontChoice.CUSTOM -> null
    }

    /**
     * How much lighter than asked [choice] is drawn, so its weights look like the others': Fraunces's 400 is about as
     * dark as the sans fonts' 600.
     */
    private fun lighten(choice: FontChoice): Int = if (choice == FontChoice.SERIF) 100 else 0

    private fun bundledFamily(choice: FontChoice, ws: Iterable<Int>): FontFamily? = bundled(choice)?.let { (res, extra) ->
        val less = lighten(choice)
        FontFamily(ws.map { w ->
            Font(res, FontWeight(w), FontStyle.Normal, variationSettings = FontVariation.Settings(FontVariation.weight((w - less).coerceAtLeast(100)), *extra))
        })
    }

    /** Outfit, Prism's own typeface. */
    val prism: FontFamily get() = family(FontChoice.PRISM)

    private val axisCache = HashMap<FontChoice, FontFamily>()

    /**
     * [choice]'s typeface at every 20 along its weight axis, 200–700, for text whose weight moves smoothly (the lyric being
     * sung thickens as it's sung); null for System and Your font. A family picks its nearest weight, so it holds the
     * in-between ones too.
     */
    fun axis(choice: FontChoice): FontFamily? =
        synchronized(axisCache) { axisCache[choice] ?: bundledFamily(choice, 200..700 step 20)?.also { axisCache[choice] = it } }

    /** Prism's typeface on its weight axis ([axis]). */
    val prismAxis: FontFamily get() = axis(FontChoice.PRISM)!!

    private fun variable(f: File, vararg extra: FontVariation.Setting): FontFamily = FontFamily(
        weights.map { w -> Font(f, FontWeight(w), FontStyle.Normal, FontVariation.Settings(FontVariation.weight(w), *extra)) }
    )

    private fun build(choice: FontChoice): FontFamily = when (choice) {
        FontChoice.SYSTEM -> FontFamily.Default
        FontChoice.CUSTOM -> FontFamily.Default // see [family]
        FontChoice.DYSLEXIC -> FontFamily(
            Font(com.prism.music.R.font.opendyslexic_regular, FontWeight.Normal),
            Font(com.prism.music.R.font.opendyslexic_bold, FontWeight.Bold),
        )
        else -> bundledFamily(choice, weights) ?: FontFamily.Default
    }

    /**
     * Makes [choices]' typefaces now, at every weight the UI uses (and their weight axis for the lyrics' sweep), so the
     * first frame drawn in them finds them made (G37: a Look in another font froze the screen ~1 s while they were
     * built there). Compose's font resolvers share one cache, so a resolver made here fills it for the screen's. Call
     * off the main thread.
     */
    fun warm(context: android.content.Context, choices: Collection<FontChoice>) {
        val resolver = androidx.compose.ui.text.font.createFontFamilyResolver(context)
        for (choice in choices.distinct()) {
            val f = family(choice)
            for (w in weights) runCatching { resolver.resolve(f, FontWeight(w), FontStyle.Normal) }
            axis(choice)?.let { a -> for (w in 200..700 step 20) runCatching { resolver.resolve(a, FontWeight(w), FontStyle.Normal) } }
        }
    }

    /** Whether [choice] can be used: the bundled typefaces always; Your font once one is imported. */
    fun available(choice: FontChoice): Boolean = when (choice) {
        FontChoice.CUSTOM -> customFile(FontSlot.APP) != null
        else -> true
    }
    fun family(choice: FontChoice): FontFamily =
        if (choice == FontChoice.CUSTOM) custom(FontSlot.APP) ?: FontFamily.Default
        else synchronized(cache) { cache.getOrPut(choice) { runCatching { build(choice) }.getOrDefault(FontFamily.Default) } }

    // ---------------------------------------------------------------- The listener's own fonts (#12)

    /** Where an imported font goes: one for the whole app, one for the lyrics. */
    enum class FontSlot(val fileName: String) { APP("app.font"), LYRICS("lyrics.font") }

    @Volatile private var dir: File? = null
    private val customCache = HashMap<FontSlot, Pair<Long, FontFamily>>()

    /** Called as Prism starts. */
    fun init(context: android.content.Context) { dir = File(context.filesDir, "fonts") }

    fun customFile(slot: FontSlot): File? = dir?.let { File(it, slot.fileName) }?.takeIf { it.canRead() }

    /** The imported font for [slot] (rebuilt when the file changes), or null if there's none or it won't load. */
    fun custom(slot: FontSlot): FontFamily? {
        val f = customFile(slot) ?: return null
        synchronized(customCache) {
            customCache[slot]?.takeIf { it.first == f.lastModified() }?.let { return it.second }
            // A variable font (Inter, Roboto Flex…) gets every weight from its own axis; a single-weight file is one
            // face that Compose thickens for bold.
            val family = runCatching { if (hasWeightAxis(f)) variable(f) else FontFamily(Font(f)) }.getOrNull() ?: return null
            customCache[slot] = f.lastModified() to family
            return family
        }
    }

    /**
     * Copies a font the listener picked ([uri], a .ttf / .otf) into Prism for [slot], after checking Android can read
     * it. Returns its name for Settings, or throws with a message to show.
     */
    fun import(context: android.content.Context, uri: android.net.Uri, slot: FontSlot): String {
        copyIn(context, slot) { context.contentResolver.openInputStream(uri) }
        val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
        return name?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: tr(R.string.your_font_2)
    }

    /**
     * #318 (1.8.1): a font installed on the phone ([installed]) copied into Prism for [slot], as a picked file is.
     * Returns its name for Settings, or throws with a message to show.
     */
    fun importInstalled(context: android.content.Context, font: InstalledFont, slot: FontSlot): String {
        copyIn(context, slot) { runCatching { font.file.inputStream() }.getOrNull() }
        return font.name
    }

    /** A font the phone has installed: its family's [name] and the file of its regular face. */
    data class InstalledFont(val name: String, val file: File)

    /**
     * #318: the fonts installed on the phone that can write Latin text, one per family (its regular, upright face),
     * by name. Android lists them to apps from Android 10 (SystemFonts); a font collection (.ttc) isn't offered, as
     * Prism can only use one face of a file. Fonts some phones add through their own font stores (Samsung's) aren't
     * listed by Android: the System font is the phone's own font, whichever is picked there. Reads files: off the
     * main thread.
     */
    fun installed(): List<InstalledFont> {
        if (android.os.Build.VERSION.SDK_INT < 29) return emptyList()
        val fonts = runCatching { android.graphics.fonts.SystemFonts.getAvailableFonts() }.getOrDefault(emptySet())
        val paint = android.graphics.Paint()
        return fonts.asSequence()
            .mapNotNull { f -> f.file?.takeIf { it.canRead() && !it.name.endsWith(".ttc", true) && !it.name.endsWith(".otc", true) }?.let { f to it } }
            .filter { (f, _) -> runCatching {
                // Only that font (no fallback), so a script's own font without Latin letters doesn't pass as one.
                paint.typeface = android.graphics.Typeface.CustomFallbackBuilder(android.graphics.fonts.FontFamily.Builder(f).build()).build()
                paint.hasGlyph("A") && paint.hasGlyph("a")
            }.getOrDefault(false) }
            .map { (f, file) -> Triple(fontFamilyName(file) ?: familyFromFileName(file.name), f, file) }
            .groupBy { it.first }
            .map { (name, faces) ->
                // The face closest to regular, upright first.
                val best = faces.minBy { (_, f, _) -> kotlin.math.abs(f.style.weight - 400) + if (f.style.slant != android.graphics.fonts.FontStyle.FONT_SLANT_UPRIGHT) 1000 else 0 }
                InstalledFont(name, best.third)
            }
            .sortedBy { it.name.lowercase() }
    }

    /** "NotoSerifDisplay-Italic.ttf" → "Noto Serif Display": a name from the file's when the font names none. */
    internal fun familyFromFileName(fileName: String): String = fileName.substringBeforeLast('.').substringBefore('-')
        .replace(Regex("(?<=[a-z])(?=[A-Z])|(?<=[A-Za-z])(?=[0-9])"), " ").replace('_', ' ').trim()

    /**
     * The family a TrueType / OpenType file names itself ('name' table: the typographic family, else the family), in
     * English where it has one; null when it can't be read.
     */
    internal fun fontFamilyName(f: File): String? = runCatching {
        java.io.RandomAccessFile(f, "r").use { raf ->
            raf.seek(4)
            val tables = raf.readUnsignedShort()
            var nameAt = -1L
            val tag = ByteArray(4)
            for (i in 0 until tables.coerceAtMost(256)) {
                raf.seek(12L + i * 16)
                raf.readFully(tag)
                if (String(tag, Charsets.US_ASCII) == "name") { raf.skipBytes(4); nameAt = raf.readInt().toLong() and 0xFFFFFFFFL; break }
            }
            if (nameAt < 0) return@use null
            raf.seek(nameAt + 2)
            val count = raf.readUnsignedShort()
            val strings = nameAt + raf.readUnsignedShort()
            // (rank, text): the typographic family (16) over the family (1); Windows English over the rest.
            var best: Pair<Int, String>? = null
            for (i in 0 until count.coerceAtMost(1024)) {
                raf.seek(nameAt + 6 + i * 12L)
                val platform = raf.readUnsignedShort(); val encoding = raf.readUnsignedShort(); val language = raf.readUnsignedShort()
                val id = raf.readUnsignedShort(); val length = raf.readUnsignedShort(); val offset = raf.readUnsignedShort()
                if (id != 1 && id != 16) continue
                val text = when {
                    platform == 3 || platform == 0 -> ByteArray(length).also { raf.seek(strings + offset); raf.readFully(it) }.toString(Charsets.UTF_16BE)
                    platform == 1 && encoding == 0 -> ByteArray(length).also { raf.seek(strings + offset); raf.readFully(it) }.toString(Charsets.ISO_8859_1)
                    else -> continue
                }.trim()
                if (text.isEmpty()) continue
                val rank = (if (id == 16) 0 else 10) + (if (platform == 3 && language == 0x409) 0 else if (platform == 3) 1 else 2)
                if (best == null || rank < best.first) best = rank to text
            }
            best?.second
        }
    }.getOrNull()

    /** [open]'s bytes into [slot]'s file, after checking Android can read them as a font; throws with a message to show. */
    private fun copyIn(context: android.content.Context, slot: FontSlot, open: () -> java.io.InputStream?) {
        val target = File(context.filesDir, "fonts").apply { mkdirs() }
        val tmp = File(target, slot.fileName + ".new")
        open()?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            ?: error(tr(R.string.couldn_t_open_that_file))
        val ok = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 29) { android.graphics.fonts.Font.Builder(tmp).build(); true }
            else Typeface.createFromFile(tmp) != null
        }.getOrDefault(false)
        if (!ok || tmp.length() < 1024) { tmp.delete(); error(tr(R.string.that_isn_t_a_font_file)) }
        val out = File(target, slot.fileName)
        out.delete()
        // A file still held open (the font check can keep it mapped on some systems) is copied instead.
        if (!tmp.renameTo(out)) {
            val copied = runCatching { tmp.copyTo(out, overwrite = true) }.isSuccess
            tmp.delete()
            if (!copied) error(tr(R.string.couldn_t_save_the_font))
        }
    }

    fun remove(slot: FontSlot) {
        customFile(slot)?.delete()
        synchronized(customCache) { customCache.remove(slot) }
    }

    /** Whether a TrueType / OpenType file has an 'fvar' table, i.e. is a variable font. */
    private fun hasWeightAxis(f: File): Boolean = runCatching {
        java.io.RandomAccessFile(f, "r").use { raf ->
            raf.seek(4)
            val tables = raf.readUnsignedShort()
            raf.seek(12)
            val tag = ByteArray(4)
            (0 until tables.coerceAtMost(256)).any {
                raf.readFully(tag)
                raf.skipBytes(12)
                String(tag, Charsets.US_ASCII) == "fvar"
            }
        }
    }.getOrDefault(false)
}
