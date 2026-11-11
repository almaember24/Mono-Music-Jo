package com.prism.music.ui.theme

import com.prism.music.R
import com.prism.music.tr
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Prism's own icons, used everywhere in the app (issue #2; redrawn for the 1.7 redesign): circles, straight lines and
 * one corner radius on a 24-unit grid, 1.5-unit lines with round ends, play and skip filled. The drawings are SVG files
 * in design/icons; a selected twin (NameFilled) fills the same outline, tinted for tabs and solid for things like a
 * liked heart. Drawn for Prism rather than taken from an icon library, so there's no extra dependency or license.
 * `IconRenderTest` draws them all onto app/build/icon-previews/ for a look.
 */
/** How heavy Prism's line icons are drawn (#19). */
enum class IconWeight(val label: String, val scale: Float) { THIN(tr(R.string.thin), 0.64f), REGULAR(tr(R.string.regular), 1f), BOLD(tr(R.string.bold), 1.45f) }

/**
 * The icon style picked in Settings → Appearance → Icons (#19): line weight, and rounded or square ends and corners.
 * The icons are built once, as Prism starts, so this is read then ([load], from plain shared preferences so it's there
 * before anything draws) and a change shows after Prism restarts.
 */
/** Sets of icons to pick from (Settings → Appearance → Icons): Prism's own, or Cupertino, drawn after Apple's style. */
enum class IconPack(val label: String, val detail: String) {
    PRISM(tr(R.string.app_name), tr(R.string.prism_s_own_icons)),
    CUPERTINO(tr(R.string.cupertino), tr(R.string.in_the_style_of_apple_s)),
    /** Every icon with its curves cut into straight facets and sharp corners (1.7.7, generated from Prism's drawings). */
    FACET(tr(R.string.facet), tr(R.string.straight_lines_and_sharp_corners_every)),
    /** Every icon in dots, like a dot-matrix display (1.7.7, generated from Prism's drawings). */
    DOTS(tr(R.string.dots), tr(R.string.drawn_in_dots_like_a_dot)),
    /** Prism 1.6's hand-drawn icons, a heavier line (1.7.8, #155; [ClassicIcons]). */
    CLASSIC(tr(R.string.classic), tr(R.string.prism_1_6_s_icons_a)),
}

/** How Prism's icons are filled (1.7.7): lines, a light second tone inside, or solid. */
enum class IconFill(val label: String, val detail: String) {
    OUTLINE(tr(R.string.outline), tr(R.string.lines_filled_in_when_picked)),
    DUOTONE(tr(R.string.duotone), tr(R.string.lines_with_a_light_tone_inside)),
    FILLED(tr(R.string.filled), tr(R.string.solid_shapes)),
}

object IconStyle {
    private const val PREFS = "icon_style"
    @Volatile var weight = IconWeight.REGULAR
        private set
    @Volatile var rounded = true
        private set
    @Volatile var pack = IconPack.PRISM
        private set
    /** Likes drawn as a star instead of a heart (Reddit 1.7: "if you changed the heart to a star"). */
    @Volatile var likeStar = false
        private set
    /** Outline, duotone or filled (1.7.7). */
    @Volatile var fill = IconFill.OUTLINE
        private set

    fun load(context: android.content.Context) {
        val (w, r) = saved(context)
        weight = w; rounded = r
        pack = savedPack(context)
        likeStar = savedLikeStar(context)
        fill = savedFill(context)    }

    fun savedFill(context: android.content.Context): IconFill =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getString("fill", null)
            ?.let { n -> IconFill.entries.firstOrNull { it.name == n } } ?: IconFill.OUTLINE

    fun saveFill(context: android.content.Context, f: IconFill) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putString("fill", f.name).apply()
    }

    fun savedLikeStar(context: android.content.Context): Boolean =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getBoolean("like_star", false)

    fun saveLikeStar(context: android.content.Context, on: Boolean) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putBoolean("like_star", on).apply()
    }

    fun savedPack(context: android.content.Context): IconPack {
        val p = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        return IconPack.entries.firstOrNull { it.name == p.getString("pack", null) } ?: IconPack.PRISM
    }

    fun savePack(context: android.content.Context, pack: IconPack) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putString("pack", pack.name).apply()
    }

    /** What's saved (it may differ from what's drawn until a restart). */
    fun saved(context: android.content.Context): Pair<IconWeight, Boolean> {
        val p = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        val w = IconWeight.entries.firstOrNull { it.name == p.getString("weight", null) } ?: IconWeight.REGULAR
        return w to p.getBoolean("rounded", true)
    }

    fun save(context: android.content.Context, weight: IconWeight, rounded: Boolean) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit()
            .putString("weight", weight.name).putBoolean("rounded", rounded).apply()
    }
}

object PrismIcons {
    private const val STROKE = 1.8f

    /** [mirror]: flipped in right-to-left languages (arrows, lists). */
    private fun icon(name: String, mirror: Boolean = false, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f, autoMirror = mirror).apply(block).build()

    private fun ImageVector.Builder.line(width: Float = STROKE, d: PathBuilder.() -> Unit) = path(
        fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = width * IconStyle.weight.scale,
        strokeLineCap = if (IconStyle.rounded) StrokeCap.Round else StrokeCap.Butt,
        strokeLineJoin = if (IconStyle.rounded) StrokeJoin.Round else StrokeJoin.Miter, pathBuilder = d,
    )

    /** This icon, or its twin from the icon pack picked in Settings (only drawn when that pack is the one in use). */
    private inline fun ImageVector.or(pack: () -> ImageVector): ImageVector = if (IconStyle.pack == IconPack.CUPERTINO) pack() else this

    private fun ImageVector.Builder.solid(evenOdd: Boolean = false, d: PathBuilder.() -> Unit) = path(
        fill = SolidColor(Color.Black), pathFillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero, pathBuilder = d,
    )

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx + r, cy)
        arcTo(r, r, 0f, true, true, cx - r, cy)
        arcTo(r, r, 0f, true, true, cx + r, cy)
        close()
    }

    private fun PathBuilder.roundRect(l: Float, t: Float, r: Float, b: Float, radius: Float) {
        // Square style: corners mostly squared off.
        val rad = if (IconStyle.rounded) radius else radius * 0.3f
        moveTo(l + rad, t)
        lineTo(r - rad, t); quadTo(r, t, r, t + rad)
        lineTo(r, b - rad); quadTo(r, b, r - rad, b)
        lineTo(l + rad, b); quadTo(l, b, l, b - rad)
        lineTo(l, t + rad); quadTo(l, t, l + rad, t)
        close()
    }

    // ---------------------------------------------------------------- Prism's icons
    // Drawn as SVG in design/icons (24-unit grid, 1.5-unit lines, round ends; see its README) and built from
    // PrismIconData, which the build generates from those files. To change an icon, change its drawing.

    /** Classic's line against today's drawings' (1.8 / 1.5). */
    private const val CLASSIC_LINE = 1.2f

    /** How strongly a selected twin's tinted shapes are filled. */
    private const val TINT = 0.18f

    /** Duotone's second tone (1.7.7): the inside of each shape, lighter than a picked twin's tint. */
    private const val DUO = 0.24f

    /**
     * The icon drawn in design/icons/[name].svg, or its selected twin ([filled]: tinted or solid, as the drawing says), in
     * the fill style picked in Icons & shapes ([style]; Prism's own setting unless a preview asks for another).
     */
    private fun drawn(name: String, filled: Boolean = false, style: IconFill = IconStyle.fill, pack: IconPack = IconStyle.pack): ImageVector {
        // Classic (1.7.8, #155): 1.6.9's own drawing where it had one; Lyrics stays today's (the one #155 didn't miss).
        if (pack == IconPack.CLASSIC && name != "Lyrics") ClassicIcons.find(if (filled) "${name}Filled" else name)?.let { return it }
        val s = PrismIconData.spec(name)
        val ink = SolidColor(Color.Black)
        // Icons Classic has no drawing for: today's, at its line (1.8 units against today's 1.5).
        val classicLine = if (pack == IconPack.CLASSIC) CLASSIC_LINE else 1f
        // Filled: every icon as its solid twin. Duotone: the shapes a twin fills get a light tone inside.
        val twin = filled || (style == IconFill.FILLED && s.twin != 0)
        if (pack == IconPack.DOTS) return dotted(name, if (twin) s.dotsFilled else s.dots, filled, s.mirror)
        // Facet: the same drawing with its curves cut straight, sharp corners and square ends whatever the Rounded switch.
        val facet = pack == IconPack.FACET
        val round = IconStyle.rounded && !facet
        return ImageVector.Builder(if (filled) "${name}Filled" else name, 24.dp, 24.dp, 24f, 24f, autoMirror = s.mirror).apply {
            for (p in if (facet) s.facet else s.parts) {
                val nodes = addPathNodes(p.d)
                if (p.kind == 1) { addPath(nodes, fill = ink); continue }
                val fillable = p.kind == 2 && s.twin != 0
                val fill = fillable && (twin || style == IconFill.DUOTONE)
                val alpha = when {
                    !fill -> 1f
                    twin && style == IconFill.FILLED -> 1f
                    twin -> if (s.twin == 1) TINT else 1f
                    else -> DUO
                }
                addPath(
                    nodes,
                    fill = if (fill) ink else null, fillAlpha = alpha,
                    stroke = ink, strokeLineWidth = p.width * IconStyle.weight.scale * classicLine,
                    strokeLineCap = if (round) StrokeCap.Round else StrokeCap.Butt,
                    strokeLineJoin = if (round) StrokeJoin.Round else StrokeJoin.Miter,
                )
            }
        }.build()
    }

    /**
     * The Dots pack: the icon's dots ([dots]: hex, four digits each, x and y in quarter units), one path of circles.
     * Line weight sizes the dots a little (Thin smaller, Bold fuller).
     */
    private fun dotted(name: String, dots: String, filled: Boolean, mirror: Boolean): ImageVector {
        val r = PrismIconData.DOT_SPACING * 0.36f * IconStyle.weight.scale.coerceIn(0.8f, 1.2f)
        return ImageVector.Builder(if (filled) "${name}Filled" else name, 24.dp, 24.dp, 24f, 24f, autoMirror = mirror).apply {
            addPath(
                androidx.compose.ui.graphics.vector.PathBuilder().apply {
                    for (k in 0 until dots.length / 4) {
                        val x = dots.substring(k * 4, k * 4 + 2).toInt(16) / 4f
                        val y = dots.substring(k * 4 + 2, k * 4 + 4).toInt(16) / 4f
                        circle(x, y, r)
                    }
                }.nodes,
                fill = SolidColor(Color.Black),
            )
        }.build()
    }

    /** Cupertino's Home, Search, Heart and Play, for its card in Settings (built when asked: the pack may not be in use). */
    fun cupertinoPreview(): List<ImageVector> = listOf(Cupertino.Home, Cupertino.Search, Cupertino.Heart, Cupertino.Play)

    /** [name] drawn in fill style [style] and [pack], for Settings' example cards (the app's own icons are built once, at start). */
    fun preview(name: String, style: IconFill, pack: IconPack = IconStyle.pack): ImageVector = drawn(name, style = style, pack = pack)

    val Home = drawn("Home").or { Cupertino.Home }
    val HomeFilled = drawn("Home", filled = true).or { Cupertino.HomeFilled }
    val Search = drawn("Search").or { Cupertino.Search }
    val SearchBold = drawn("SearchBold")
    val Library = drawn("Library").or { Cupertino.Library }
    val LibraryFilled = drawn("Library", filled = true).or { Cupertino.LibraryFilled }
    val Replay = drawn("Replay")
    val ReplayFilled = drawn("Replay", filled = true)
    val Settings = drawn("Settings").or { Cupertino.Settings }
    val SettingsFilled = drawn("Settings", filled = true).or { Cupertino.SettingsFilled }
    val Customize = drawn("Customize")
    val Podcasts = drawn("Podcasts")
    val Person = drawn("Person").or { Cupertino.Person }
    val Play = drawn("Play").or { Cupertino.Play }
    val Pause = drawn("Pause").or { Cupertino.Pause }
    val Forward = drawn("Forward")
    val Backward = drawn("Backward")
    val Next = drawn("Next").or { Cupertino.Next }
    val Previous = drawn("Previous").or { Cupertino.Previous }
    // The like: a heart, or a star when Settings → Appearance → Icons & shapes → "Likes as stars" is on.
    val Heart = if (IconStyle.likeStar) drawn("Star") else drawn("Heart").or { Cupertino.Heart }
    val HeartFilled = if (IconStyle.likeStar) drawn("Star", filled = true) else drawn("Heart", filled = true).or { Cupertino.HeartFilled }
    val More = drawn("More").or { Cupertino.More }
    val Lyrics = drawn("Lyrics").or { Cupertino.Lyrics }
    val LyricsFilled = drawn("Lyrics", filled = true).or { Cupertino.LyricsFilled }
    val Queue = drawn("Queue").or { Cupertino.Queue }
    val Song = drawn("Song")
    val Video = drawn("Video")
    val Shuffle = drawn("Shuffle").or { Cupertino.Shuffle }
    val Recognize = drawn("Recognize")
    val Camera = drawn("Camera")
    val Blend = drawn("Blend")
    val Block = drawn("Block")
    val SmartShuffle = drawn("SmartShuffle")
    val Repeat = drawn("Repeat").or { Cupertino.Repeat }
    val Autoplay = drawn("Autoplay")
    val ArrowBack = drawn("ArrowBack").or { Cupertino.ArrowBack }
    val ArrowForward = drawn("ArrowForward")
    val ChevronRight = drawn("ChevronRight")
    val ChevronDown = drawn("ChevronDown")
    val ChevronUp = drawn("ChevronUp")
    val FillIn = drawn("FillIn")
    val TrendingUp = drawn("TrendingUp")
    val TrendingDown = drawn("TrendingDown")
    val OpenInNew = drawn("OpenInNew")
    val SwapVertical = drawn("SwapVertical")
    val Login = drawn("Login")
    val Logout = drawn("Logout")
    val Check = drawn("Check")
    val CheckCircle = drawn("CheckCircle")
    val DoneAll = drawn("DoneAll")
    val Close = drawn("Close")
    val Add = drawn("Add")
    val Remove = drawn("Remove")
    val MoreVertical = drawn("MoreVertical")
    val DragHandle = drawn("DragHandle")
    val Edit = drawn("Edit")
    val Signature = drawn("Signature")
    val Delete = drawn("Delete")
    val Refresh = drawn("Refresh")
    val History = drawn("History")
    val Sync = drawn("Sync")
    val SyncOff = drawn("SyncOff")
    val Share = drawn("Share").or { Cupertino.Share }
    val Link = drawn("Link")
    val Copy = drawn("Copy")
    val Lock = drawn("Lock")
    val Globe = drawn("Globe")
    val Visible = drawn("Visible")
    val Hidden = drawn("Hidden")
    val Info = drawn("Info")
    val Star = drawn("Star")
    val StarFilled = drawn("Star", filled = true)
    val Mic = drawn("Mic")
    val MicFilled = drawn("Mic", filled = true)
    val Romanize = drawn("Romanize")
    val Flame = drawn("Flame")
    val FlameFilled = drawn("Flame", filled = true)
    val ThumbDown = drawn("ThumbDown")
    val ThumbDownFilled = drawn("ThumbDown", filled = true)
    val Bookmark = drawn("Bookmark")
    val BookmarkFilled = drawn("Bookmark", filled = true)
    /** The listener's tags on songs (1.8.3). */
    val Tag = drawn("Tag")
    val Folder = drawn("Folder")
    val FolderFilled = drawn("Folder", filled = true)
    val Pin = drawn("Pin")
    val PinFilled = drawn("Pin", filled = true)
    val Upload = drawn("Upload")
    val Download = drawn("Download").or { Cupertino.Download }
    val DownloadDone = drawn("DownloadDone").or { Cupertino.DownloadDone }
    val DownloadCircle = drawn("DownloadCircle")
    val Update = drawn("Update")
    val CloudOff = drawn("CloudOff")
    val PlayCircle = drawn("PlayCircle").or { Cupertino.PlayCircle }
    val Album = drawn("Album")
    val PlaylistPlay = drawn("PlaylistPlay")
    val QueueMusic = drawn("QueueMusic")
    val PlaylistAdd = drawn("PlaylistAdd")
    val PlaylistRemove = drawn("PlaylistRemove")
    val Sort = drawn("Sort")
    val LibraryAdd = drawn("LibraryAdd")
    val LibraryAdded = drawn("LibraryAdded")
    val Radio = drawn("Radio").or { Cupertino.Radio }
    val Spatial = drawn("Spatial")
    val Equalizer = drawn("Equalizer")
    /** A record and its tonearm: DJ mode, and the Vinyl player (1.7.4). */
    val Turntable = drawn("Turntable")
    val TurntableFilled = drawn("Turntable", filled = true)
    val AnimatedCover = drawn("AnimatedCover")
    val Explicit = drawn("Explicit")
    val VolumeDown = drawn("VolumeDown")
    val VolumeUp = drawn("VolumeUp")
    val VolumeOff = drawn("VolumeOff")
    val Headphones = drawn("Headphones")
    val Speaker = drawn("Speaker")
    val Phone = drawn("Phone")
    val Usb = drawn("Usb")
    val Cast = drawn("Cast")
    val Tv = drawn("Tv")
    val Handoff = drawn("Handoff")
    // Settings' Car & devices and Controls & gestures pages (1.7.6).
    val Car = drawn("Car")
    val Touch = drawn("Touch")
    val Speed = drawn("Speed")
    val Timer = drawn("Timer")
    val Moon = drawn("Moon")
    val Sun = drawn("Sun")
    val Calendar = drawn("Calendar")
    val Looks = drawn("Looks")
    val Undo = drawn("Undo")
    val Stats = drawn("Stats")
    val Translate = drawn("Translate")
    val Groups = drawn("Groups")
    val PersonAdd = drawn("PersonAdd")
    val Mood = drawn("Mood")
    val Category = drawn("Category")
    val Palette = drawn("Palette")
    val Photo = drawn("Photo")
    val Rows = drawn("Rows")
    val Carousel = drawn("Carousel")
    val Fullscreen = drawn("Fullscreen")
    val FullscreenExit = drawn("FullscreenExit")
    val AspectRatio = drawn("AspectRatio")
    val PictureInPicture = drawn("PictureInPicture")

    /**
     * The Cupertino pack: the most-seen icons redrawn in the manner of Apple's apps (Prism's own drawings, not Apple's
     * symbols): solid tab glyphs with cut-outs, a chevron for Back, skips as two touching triangles, arrows with solid
     * heads, menus and downloads in circles. Icons it doesn't redraw stay Prism's. Built only when it's picked.
     */
    private object Cupertino {
        private val house: PathBuilder.() -> Unit = {
            moveTo(10.8f, 3.9f); quadTo(12f, 2.9f, 13.2f, 3.9f)
            lineTo(20.2f, 9.9f); quadTo(20.9f, 10.5f, 20.9f, 11.5f)
            lineTo(20.9f, 18.9f); quadTo(20.9f, 20.9f, 18.9f, 20.9f)
            lineTo(5.1f, 20.9f); quadTo(3.1f, 20.9f, 3.1f, 18.9f)
            lineTo(3.1f, 11.5f); quadTo(3.1f, 10.5f, 3.8f, 9.9f); close()
        }
        val Home = icon("CupertinoHome") {
            line(d = house)
            line { moveTo(9.8f, 20.9f); lineTo(9.8f, 15.6f); quadTo(9.8f, 14.4f, 11f, 14.4f); lineTo(13f, 14.4f); quadTo(14.2f, 14.4f, 14.2f, 15.6f); lineTo(14.2f, 20.9f) }
        }
        val HomeFilled = icon("CupertinoHomeFilled") {
            solid(evenOdd = true) { house(); roundRect(9.8f, 14.4f, 14.2f, 20.9f, 1.2f) }
        }

        val Search = icon("CupertinoSearch") {
            line(2f) { circle(10.4f, 10.4f, 6.6f) }
            line(2.8f) { moveTo(15.4f, 15.4f); lineTo(20.2f, 20.2f) }
        }

        /** A stack with a note on its front card. */
        private fun PathBuilder.note() {
            circle(10.3f, 16.9f, 1.9f)
            // Stem and flag touch the head without overlapping it, so the filled card can cut the whole note out.
            moveTo(12.2f, 10.4f); lineTo(13.5f, 10.4f); lineTo(13.5f, 16.9f); lineTo(12.2f, 16.9f); close()
            moveTo(13.5f, 10.4f); quadTo(15.8f, 10.6f, 16.4f, 12.6f); quadTo(15f, 11.9f, 13.5f, 12.2f); close()
        }
        val Library = icon("CupertinoLibrary") {
            line { roundRect(3.6f, 8f, 20.4f, 21f, 3.2f) }
            line { moveTo(6.2f, 4.6f); lineTo(17.8f, 4.6f) }
            solid { note() }
        }
        val LibraryFilled = icon("CupertinoLibraryFilled") {
            solid(evenOdd = true) { roundRect(3.6f, 8f, 20.4f, 21f, 3.2f); note() }
            line { moveTo(6.2f, 4.6f); lineTo(17.8f, 4.6f) }
        }

        /** A rounder gear with twelve short teeth. */
        private val gear: PathBuilder.() -> Unit = {
            val teeth = 12
            fun pt(deg: Double, r: Double) = (12 + r * cos(deg * PI / 180)).toFloat() to (12 + r * sin(deg * PI / 180)).toFloat()
            for (k in 0 until teeth) {
                val a = k * 360.0 / teeth
                listOf(pt(a - 10, 7.9), pt(a - 6, 9.5), pt(a + 6, 9.5), pt(a + 10, 7.9))
                    .forEachIndexed { i, (x, y) -> if (k == 0 && i == 0) moveTo(x, y) else lineTo(x, y) }
            }
            close()
        }
        val Settings = icon("CupertinoSettings") {
            line(d = gear)
            line { circle(12f, 12f, 3.2f) }
        }
        val SettingsFilled = icon("CupertinoSettingsFilled") {
            solid(evenOdd = true) { gear(); circle(12f, 12f, 3.2f) }
            line(width = 1.2f, d = gear)
        }

        /** A head and shoulders in a circle. */
        val Person = icon("CupertinoPerson") {
            line { circle(12f, 12f, 9.2f) }
            solid { circle(12f, 9.6f, 3.3f) }
            solid {
                moveTo(6.1f, 18.2f); quadTo(7.6f, 14.6f, 12f, 14.6f); quadTo(16.4f, 14.6f, 17.9f, 18.2f)
                quadTo(15.4f, 20.6f, 12f, 20.6f); quadTo(8.6f, 20.6f, 6.1f, 18.2f); close()
            }
        }

        val Play = icon("CupertinoPlay") {
            solid {
                moveTo(6.8f, 5f); quadTo(6.8f, 3.4f, 8.2f, 4.2f)
                lineTo(19.6f, 10.8f); quadTo(21f, 12f, 19.6f, 13.2f)
                lineTo(8.2f, 19.8f); quadTo(6.8f, 20.6f, 6.8f, 19f); close()
            }
        }
        val Pause = icon("CupertinoPause") {
            solid { roundRect(6f, 4.2f, 9.9f, 19.8f, 1.9f); roundRect(14.1f, 4.2f, 18f, 19.8f, 1.9f) }
        }

        /** One triangle of a skip, [width] wide, pointing right from [x] (left from it when [left]). */
        private fun PathBuilder.wedge(x: Float, left: Boolean = false, width: Float = 9.6f) {
            val d = if (left) -1f else 1f
            moveTo(x, 7f); quadTo(x, 5.8f, x + d * 1f, 6.4f)
            lineTo(x + d * (width - 1.1f), 11.1f); quadTo(x + d * width, 12f, x + d * (width - 1.1f), 12.9f)
            lineTo(x + d * 1f, 17.6f); quadTo(x, 18.2f, x, 17f); close()
        }
        /** Two triangles nose to tail, as Apple's players skip. */
        val Next = icon("CupertinoNext") { solid { wedge(2.4f); wedge(11.6f) } }
        val Previous = icon("CupertinoPrevious") { solid { wedge(21.6f, left = true); wedge(12.4f, left = true) } }

        private val heart: PathBuilder.() -> Unit = {
            moveTo(12f, 20.6f)
            curveTo(11.6f, 20.6f, 2.5f, 15.4f, 2.5f, 8.9f)
            curveTo(2.5f, 5.9f, 4.8f, 3.6f, 7.7f, 3.6f)
            curveTo(9.6f, 3.6f, 11.1f, 4.6f, 12f, 6.2f)
            curveTo(12.9f, 4.6f, 14.4f, 3.6f, 16.3f, 3.6f)
            curveTo(19.2f, 3.6f, 21.5f, 5.9f, 21.5f, 8.9f)
            curveTo(21.5f, 15.4f, 12.4f, 20.6f, 12f, 20.6f)
            close()
        }
        val Heart = icon("CupertinoHeart") { line(d = heart) }
        val HeartFilled = icon("CupertinoHeartFilled") { solid(d = heart) }

        /** Three dots in a circle. */
        val More = icon("CupertinoMore") {
            line { circle(12f, 12f, 9.2f) }
            solid { circle(7.6f, 12f, 1.35f); circle(12f, 12f, 1.35f); circle(16.4f, 12f, 1.35f) }
        }

        private val bubble: PathBuilder.() -> Unit = {
            moveTo(12f, 3.6f)
            curveTo(17.2f, 3.6f, 21f, 6.9f, 21f, 11f)
            curveTo(21f, 15.1f, 17.2f, 18.4f, 12f, 18.4f)
            curveTo(11f, 18.4f, 10.1f, 18.3f, 9.2f, 18f)
            lineTo(5.6f, 20.2f); quadTo(4.8f, 20.6f, 5f, 19.7f)
            lineTo(5.6f, 16.6f)
            curveTo(3.9f, 15.2f, 3f, 13.2f, 3f, 11f)
            curveTo(3f, 6.9f, 6.8f, 3.6f, 12f, 3.6f); close()
        }
        /** Two quotation marks: a dot with a tail. */
        private fun PathBuilder.quotes() {
            for (x in listOf(9.1f, 14.3f)) {
                circle(x, 10f, 1.5f)
                moveTo(x + 1.5f, 10f); quadTo(x + 1.6f, 12.6f, x - 0.6f, 13.6f); lineTo(x - 0.9f, 13f); quadTo(x + 0.5f, 12.2f, x, 11.4f); close()
            }
        }
        val Lyrics = icon("CupertinoLyrics") { line(d = bubble); solid { quotes() } }
        val LyricsFilled = icon("CupertinoLyricsFilled") { solid(evenOdd = true) { bubble(); quotes() } }

        /** Rows, the first led by a small play triangle: what plays next. */
        val Queue = icon("CupertinoQueue") {
            solid { moveTo(3.4f, 4f); lineTo(7.6f, 6.4f); lineTo(3.4f, 8.8f); close() }
            line { moveTo(10.5f, 6.4f); lineTo(20.5f, 6.4f); moveTo(3.8f, 12.2f); lineTo(20.5f, 12.2f); moveTo(3.8f, 18f); lineTo(20.5f, 18f) }
        }

        /** A solid arrowhead with its tip at ([x], [y]), pointing right (left when [left]). */
        private fun PathBuilder.head(x: Float, y: Float, left: Boolean = false) {
            val d = if (left) 1f else -1f
            moveTo(x, y); lineTo(x + d * 3.6f, y - 2.6f); lineTo(x + d * 3.6f, y + 2.6f); close()
        }
        val Shuffle = icon("CupertinoShuffle") {
            line {
                moveTo(3f, 7f); lineTo(5.8f, 7f); curveTo(10.4f, 7f, 10.9f, 17f, 15.4f, 17f); lineTo(18f, 17f)
                moveTo(3f, 17f); lineTo(5.8f, 17f); curveTo(7.7f, 17f, 8.9f, 15.4f, 9.8f, 13.5f)
                moveTo(13.6f, 10.4f); curveTo(14.4f, 8.6f, 15.6f, 7f, 17.6f, 7f); lineTo(18f, 7f)
            }
            solid { head(21.2f, 7f); head(21.2f, 17f) }
        }
        val Repeat = icon("CupertinoRepeat") {
            line {
                moveTo(4f, 12f); lineTo(4f, 10f); quadTo(4f, 7f, 7f, 7f); lineTo(17.4f, 7f)
                moveTo(20f, 12f); lineTo(20f, 14f); quadTo(20f, 17f, 17f, 17f); lineTo(6.6f, 17f)
            }
            solid { head(20.8f, 7f); head(3.2f, 17f, left = true) }
        }

        /** Back is a chevron, without a shaft. */
        val ArrowBack = icon("CupertinoBack", mirror = true) { line(2.2f) { moveTo(15f, 4.6f); lineTo(7.6f, 12f); lineTo(15f, 19.4f) } }

        /** An open box with an arrow rising out of it. */
        val Share = icon("CupertinoShare") {
            line {
                moveTo(8.6f, 9.4f); lineTo(7f, 9.4f); quadTo(5f, 9.4f, 5f, 11.4f); lineTo(5f, 19f); quadTo(5f, 21f, 7f, 21f)
                lineTo(17f, 21f); quadTo(19f, 21f, 19f, 19f); lineTo(19f, 11.4f); quadTo(19f, 9.4f, 17f, 9.4f); lineTo(15.4f, 9.4f)
                moveTo(12f, 3.4f); lineTo(12f, 14.4f); moveTo(8.7f, 6.7f); lineTo(12f, 3.4f); lineTo(15.3f, 6.7f)
            }
        }

        val Download = icon("CupertinoDownload") {
            line { circle(12f, 12f, 9.2f) }
            line { moveTo(12f, 7f); lineTo(12f, 16.4f); moveTo(8.4f, 12.8f); lineTo(12f, 16.4f); lineTo(15.6f, 12.8f) }
        }
        /** Downloaded: a solid circle with a check cut out of it. */
        val DownloadDone = icon("CupertinoDownloadDone") {
            solid(evenOdd = true) {
                circle(12f, 12f, 9.6f)
                moveTo(7.2f, 12.3f); lineTo(8.6f, 10.9f); lineTo(10.8f, 13.1f); lineTo(15.4f, 8.5f)
                lineTo(16.8f, 9.9f); lineTo(10.8f, 15.9f); close()
            }
        }

        /** A dot sending out waves to both sides. */
        val Radio = icon("CupertinoRadio") {
            solid { circle(12f, 12f, 2f) }
            line {
                moveTo(8.46f, 8.46f); arcTo(5f, 5f, 0f, false, false, 8.46f, 15.54f)
                moveTo(15.54f, 8.46f); arcTo(5f, 5f, 0f, false, true, 15.54f, 15.54f)
                moveTo(5.64f, 5.64f); arcTo(9f, 9f, 0f, false, false, 5.64f, 18.36f)
                moveTo(18.36f, 5.64f); arcTo(9f, 9f, 0f, false, true, 18.36f, 18.36f)
            }
        }

        val PlayCircle = icon("CupertinoPlayCircle") {
            line { circle(12f, 12f, 9.2f) }
            solid { moveTo(10f, 8.4f); quadTo(10f, 7.6f, 10.7f, 8f); lineTo(15.8f, 11.3f); quadTo(16.4f, 12f, 15.8f, 12.7f); lineTo(10.7f, 16f); quadTo(10f, 16.4f, 10f, 15.6f); close() }
        }
    }
}
