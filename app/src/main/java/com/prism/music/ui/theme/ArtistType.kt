package com.prism.music.ui.theme

import com.prism.music.R
import com.prism.music.tr
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import java.io.File

/** How a signature is drawn on top of its typeface. */
enum class SignatureEffect { NONE, OUTLINE, GLOW, GRADIENT }

/**
 * How an artist's name is set on their page, their "signature". Like Apple Music, headline artists
 * get a typeface that suits their music; everyone else gets the standard bold. The listener can
 * pick any of them for any artist.
 */
data class ArtistType(
    /** Stable id, saved when the listener picks this style for an artist. */
    val key: String,
    val label: String,
    val family: FontFamily,
    val weight: FontWeight = FontWeight.Bold,
    val italic: Boolean = false,
    val caps: Boolean = false,
    /** Letter spacing in em. */
    val tracking: Float = -0.01f,
    /** Base size in sp before long names are shrunk to fit. */
    val size: Float = 40f,
    val lineHeight: Float = 1.0f,
    val effect: SignatureEffect = SignatureEffect.NONE,
)

@OptIn(ExperimentalTextApi::class)
object ArtistTypography {
    /** Fonts that ship with the phone (Pixel has a good set); each falls back if missing. */
    private fun file(vararg paths: String): File? = paths.map(::File).firstOrNull { it.canRead() }

    private fun family(fallback: FontFamily, vararg paths: String, weight: FontWeight = FontWeight.Normal, style: FontStyle = FontStyle.Normal, axes: FontVariation.Settings? = null): FontFamily {
        val f = file(*paths) ?: return fallback
        return runCatching {
            FontFamily(if (axes != null) Font(f, weight, style, axes) else Font(f, weight, style))
        }.getOrDefault(fallback)
    }

    private val flex = arrayOf("/system/fonts/RobotoFlex-Regular.ttf")
    private val sansFlex = arrayOf("/product/fonts/GoogleSansFlex-Regular.ttf", "/system/fonts/GoogleSansFlex-Regular.ttf")

    /** Tall, tightly packed black capitals (Roboto Flex at its narrowest). */
    val condensed by lazy {
        ArtistType(
            "condensed", tr(R.string.condensed),
            family(FontFamily.SansSerif, *flex, weight = FontWeight.Black, axes = FontVariation.Settings(
                FontVariation.weight(1000), FontVariation.width(25f), FontVariation.Setting("opsz", 144f),
            )),
            FontWeight.Black, caps = true, tracking = 0f, size = 58f, lineHeight = 0.92f,
        )
    }

    /** Wide, heavy capitals (Roboto Flex at its widest). */
    val extended by lazy {
        ArtistType(
            "extended", tr(R.string.extended),
            family(FontFamily.SansSerif, *flex, weight = FontWeight.Black, axes = FontVariation.Settings(
                FontVariation.weight(1000), FontVariation.width(151f), FontVariation.Setting("opsz", 144f),
            )),
            FontWeight.Black, caps = true, tracking = -0.02f, size = 34f,
        )
    }

    /** Industrial poster capitals. */
    private val shoulders by lazy {
        ArtistType(
            "poster", tr(R.string.poster),
            family(FontFamily.SansSerif, "/product/fonts/BigShouldersText-ExtraBold.ttf", "/system/fonts/BigShouldersText-ExtraBold.ttf", weight = FontWeight.ExtraBold),
            FontWeight.ExtraBold, caps = true, tracking = 0.01f, size = 56f, lineHeight = 0.92f,
        )
    }

    /** A soft, characterful display serif. */
    val serif by lazy {
        ArtistType(
            "serif", tr(R.string.display_serif),
            family(FontFamily.Serif, "/product/fonts/Fraunces-SemiBold.ttf", "/system/fonts/Fraunces-SemiBold.ttf", weight = FontWeight.SemiBold),
            FontWeight.SemiBold, tracking = -0.02f, size = 46f,
        )
    }

    /** Refined, airy serif capitals. */
    private val elegant by lazy {
        ArtistType(
            "elegant", tr(R.string.elegant),
            family(FontFamily.Serif, "/product/fonts/Lustria-Regular.ttf", "/system/fonts/Lustria-Regular.ttf"),
            FontWeight.Normal, caps = true, tracking = 0.08f, size = 34f, lineHeight = 1.05f,
        )
    }

    /** Bold italic serif. */
    private val italicSerif by lazy {
        ArtistType(
            "italic", tr(R.string.italic_serif),
            family(FontFamily.Serif, "/system/fonts/NotoSerif-BoldItalic.ttf", weight = FontWeight.Bold, style = FontStyle.Italic),
            FontWeight.Bold, italic = true, tracking = -0.02f, size = 44f,
        )
    }

    /** Friendly, fully rounded heavy sans. */
    private val rounded by lazy {
        ArtistType(
            "rounded", tr(R.string.rounded),
            family(FontFamily.SansSerif, *sansFlex, weight = FontWeight.ExtraBold, axes = FontVariation.Settings(
                FontVariation.weight(850), FontVariation.Setting("ROND", 100f),
            )),
            FontWeight.ExtraBold, tracking = -0.03f, size = 46f,
        )
    }

    /** Sturdy slab serif. */
    private val slab by lazy {
        ArtistType(
            "slab", tr(R.string.slab),
            family(FontFamily.Serif, "/product/fonts/ZillaSlab-SemiBold.ttf", "/system/fonts/ZillaSlab-SemiBold.ttf", weight = FontWeight.SemiBold),
            FontWeight.SemiBold, tracking = -0.01f, size = 48f,
        )
    }

    /** Small capitals, slightly spaced. */
    private val smallCaps by lazy {
        ArtistType(
            "smallcaps", tr(R.string.small_caps),
            family(FontFamily.SansSerif, "/system/fonts/CarroisGothicSC-Regular.ttf"),
            FontWeight.Normal, tracking = 0.04f, size = 44f,
        )
    }

    /** A handwritten autograph: Android's own script face (Dancing Script). */
    private val autograph by lazy {
        ArtistType("autograph", tr(R.string.autograph), FontFamily.Cursive, FontWeight.Bold, tracking = 0f, size = 60f, lineHeight = 1.05f)
    }

    /** Typed on a typewriter. */
    private val typewriter by lazy {
        ArtistType(
            "typewriter", tr(R.string.typewriter),
            family(FontFamily.Monospace, "/system/fonts/CutiveMono.ttf"),
            FontWeight.Normal, tracking = -0.03f, size = 40f,
        )
    }

    /** Hollow, outlined wide capitals. */
    private val outline by lazy { extended.copy(key = "outline", label = "Outline", size = 38f, effect = SignatureEffect.OUTLINE) }

    /** Rounded letters glowing in the artist's color. */
    private val neon by lazy { rounded.copy(key = "neon", label = "Neon", effect = SignatureEffect.GLOW) }

    /** The display serif, filled with a gradient of the artist's colors. */
    private val gradient by lazy { serif.copy(key = "gradient", label = "Gradient", size = 50f, effect = SignatureEffect.GRADIENT) }

    /** The everyday headline: Apple's standard heavy sans. */
    val standard by lazy {
        ArtistType(
            "standard", tr(R.string.standard),
            family(FontFamily.SansSerif, *sansFlex, weight = FontWeight.Bold, axes = FontVariation.Settings(FontVariation.weight(760))),
            FontWeight.Bold, tracking = -0.02f, size = 38f,
        )
    }

    /** Every style, as the signature picker lists them. */
    val all: List<ArtistType> by lazy {
        listOf(standard, autograph, serif, gradient, elegant, italicSerif, rounded, neon, slab, smallCaps, typewriter, condensed, shoulders, extended, outline)
    }

    fun byKey(key: String?): ArtistType? = key?.let { k -> all.firstOrNull { it.key == k } }

    private fun byGenre(genre: String?): List<ArtistType> = when (genre) {
        "Hip-hop" -> listOf(condensed, shoulders, extended, outline)
        "Pop" -> listOf(serif, rounded, extended, italicSerif, gradient, autograph)
        "R&B & soul" -> listOf(elegant, italicSerif, serif, autograph)
        "Rock" -> listOf(shoulders, condensed, slab, outline)
        "Metal" -> listOf(shoulders, condensed, outline)
        "Indie & alternative" -> listOf(smallCaps, serif, elegant, typewriter)
        "Dance & electronic" -> listOf(extended, rounded, condensed, neon)
        "Country & Americana", "Folk & acoustic", "Blues" -> listOf(slab, serif, autograph)
        "Jazz", "Classical" -> listOf(elegant, italicSerif, autograph)
        null -> listOf(serif, rounded, condensed, extended)
        else -> listOf(rounded, serif)
    }

    /** Artists this big get a signature typeface. */
    private const val HEADLINER = 2_000_000L

    /** The listener's pick for this artist ([chosen]) wins; otherwise headliners get one that suits their genre. */
    fun forArtist(id: String, genre: String?, audience: Long, chosen: String? = null): ArtistType {
        byKey(chosen)?.let { return it }
        if (audience < HEADLINER) return standard
        val options = byGenre(genre)
        return options[Math.floorMod(id.hashCode(), options.size)]
    }

    /** "444M monthly audience", "63.5M subscribers", "1.2K" -> a number. */
    /** "444M monthly audience", in any language YouTube answers in ("월간 시청자 7250만명"). */
    fun parseAudience(text: String?): Long = com.prism.music.data.innertube.SearchRank.audience(text ?: return 0)
}
