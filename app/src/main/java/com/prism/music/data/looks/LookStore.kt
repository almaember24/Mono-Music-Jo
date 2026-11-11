package com.prism.music.data.looks

import com.prism.music.R
import com.prism.music.tr
import android.content.Context
import com.prism.music.data.prefs.FontChoice
import com.prism.music.data.prefs.LookValue
import com.prism.music.data.prefs.SettingsRepository
import com.prism.music.ui.theme.AppFonts
import com.prism.music.ui.theme.Backdrop
import com.prism.music.ui.theme.IconPack
import com.prism.music.ui.theme.IconStyle
import com.prism.music.ui.theme.IconWeight
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** One step in the look's history: what it was [before] a change, and the change as it reads ("Corners → Sharp"). */
@Serializable
data class LookStep(val label: String, val at: Long, val before: Map<String, String>, val keys: List<String> = emptyList(), val look: Boolean = false)

/** "Undo: …" for the bar that shows after a change; [id] tells two notes with the same words apart. */
data class UndoNote(val label: String, val id: Long, val undo: (() -> Unit)? = null)

/**
 * A Look for the day and one for the night (1.7.3), switched at sunrise and sunset ([SunClock], from the time zone, no
 * location) or at [dayAt] / [nightAt] (minutes after midnight).
 */
@Serializable
data class LookSchedule(
    val on: Boolean = false,
    val day: String = "prism",
    val night: String = "midnight",
    val sun: Boolean = true,
    val dayAt: Int = 7 * 60,
    val nightAt: Int = 20 * 60,
)

/**
 * Looks (1.7.3): the listener's own (files/looks/<id>.json, in backups), Prism's ([BuiltInLooks]), switching between
 * them in one settings edit, sharing them as codes and `.prismlook` files, the look's history for Undo, and the
 * day / night schedule. Photos and fonts a Look uses are kept in files/looks/assets by content hash.
 */
class LookStore(private val app: Context, private val settings: SettingsRepository, private val scope: CoroutineScope) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val dir = File(app.filesDir, "looks")
    private val assetDir = File(dir, "assets")
    private val prefs get() = app.getSharedPreferences("looks", Context.MODE_PRIVATE)

    private val _mine = MutableStateFlow(loadMine())
    /** The listener's own Looks, oldest first. */
    val mine: StateFlow<List<Look>> = _mine.asStateFlow()
    val all: List<Look> get() = BuiltInLooks.all + _mine.value

    fun find(id: String): Look? = BuiltInLooks.of(id) ?: _mine.value.firstOrNull { it.id == id } ?: _other.value?.takeIf { it.id == id }

    /** The last Look put on that's neither Prism's nor yours (from the gallery or a friend), so the page can name it. */
    private val _other = MutableStateFlow<Look?>(null)

    /** A Look shared into Prism (a code from a message or a QR code, or a file), shown with Apply and Save first. */
    val pending = MutableStateFlow<Look?>(null)

    /**
     * The Look Day and night last switched to while the settings were the listener's own (kept as "Before <Look>"):
     * Prism says so once ([LookUndoBar]'s neighbour in MainActivity), then clears it. Null when there's nothing to say.
     */
    val keptBySchedule = MutableStateFlow(prefs.getString(KEPT, null))

    private fun noteKept(name: String) {
        keptBySchedule.value = name
        prefs.edit().putString(KEPT, name).apply()
    }

    fun dismissKept() {
        keptBySchedule.value = null
        prefs.edit().remove(KEPT).apply()
    }

    /** The listener's own settings that Day and night put aside ("Before <Look>") go back on. */
    suspend fun bringBackKept() {
        dismissKept()
        find(BEFORE_ID)?.let { apply(it, tr(R.string.your_own_settings_back)) }
    }

    private val iconsVersion = MutableStateFlow(0)

    /** The Look the app is in, when its settings and icons are exactly one's. */
    val currentId: StateFlow<String?> = combine(settings.flow, _mine, iconsVersion, _other) { s, _, _, _ -> lookNow(s)?.id }
        .flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, null)

    /** Your own Look last put on or saved, so changes made to it afterwards can be saved into it ([edited], [update]). */
    private val basis = MutableStateFlow(prefs.getString(BASIS, null))

    private fun setBasis(id: String?) {
        basis.value = id
        prefs.edit().putString(BASIS, id).apply()
    }

    /**
     * Your own Look you're on with changes since it was saved (even ones that happen to make it another Look), if you
     * are; null when you're on it as saved, or picked a Look that isn't yours.
     */
    val edited: StateFlow<Look?> = combine(currentId, basis, _mine) { now, b, mine -> if (b != null && now != b) mine.firstOrNull { it.id == b } else null }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** The Look whose settings and icons [s] are exactly; your own Looks first (one saved from Paper as it is is yours). */
    private fun lookNow(s: com.prism.music.data.prefs.AppSettings): Look? {
        val icons = currentIcons()
        return (_mine.value + listOfNotNull(_other.value) + BuiltInLooks.all).firstOrNull { l -> l.icons == icons && settings.previewLook(resolved(l)) == s }
    }

    // ---------------------------------------------------------------- Keeping Looks

    private fun loadMine(): List<Look> = (dir.listFiles { f -> f.isFile && f.name.endsWith(".json") && f.name.startsWith("my-") } ?: emptyArray())
        .mapNotNull { f -> runCatching { json.decodeFromString(Look.serializer(), f.readText()).let { photosRepaired(it, f) } }.getOrNull() }
        .map { LookCode.sanitized(it, settings.lookKinds).copy(builtIn = false) }
        .sortedBy { it.created }

    /**
     * #378: a Look saved in one of the 8 languages holds its photo as "FOTO|/…/all-1.jpg", which wasn't seen as a photo, so
     * it wasn't kept with the Look and [LookCode.sanitized] would leave it out. Kept with it now, and saved, if the file's
     * still there.
     */
    private fun photosRepaired(look: Look, file: File): Look {
        val tagged = look.values[Look.BACKDROPS] ?: return look
        val saved = Backgrounds.parse(tagged)
        val fixed = saved.mapNotNull { (screen, v) ->
            val right = Backgrounds.photoKeyFixed(v)
            if (right == v) screen to v
            else keep(File(right.substringAfter('|')))?.let { h -> screen to "PHOTO|${Backgrounds.ASSET}$h" }
        }.toMap()
        if (fixed == saved) return look
        val repaired = look.copy(values = if (fixed.isEmpty()) look.values - Look.BACKDROPS else look.values + (Look.BACKDROPS to Backgrounds.tag(fixed)))
        runCatching { file.writeText(json.encodeToString(Look.serializer(), repaired)) }
        return repaired
    }

    private fun write(look: Look) {
        dir.mkdirs()
        File(dir, "${look.id}.json").writeText(json.encodeToString(Look.serializer(), look))
        _mine.value = (_mine.value.filterNot { it.id == look.id } + look).sortedBy { it.created }
    }

    private fun newId() = "my-" + java.lang.Long.toString(System.currentTimeMillis(), 36) + "-" + (1000..9999).random()

    /**
     * The app's look as it is now, as a Look named [name]: its photos and own font are kept with it, and the
     * backgrounds then point at the kept photos, so picking another photo later can't take this one away.
     */
    suspend fun saveCurrent(
        name: String, id: String = newId(), note: String = "", repoint: Boolean = true, created: Long = System.currentTimeMillis(),
    ): Look = withContext(Dispatchers.IO) {
        val v = settings.lookValues.value.toMutableMap()
        v[Look.BACKDROPS]?.let { tagged ->
            val kept = Backgrounds.parse(tagged).mapNotNull { (screen, value) ->
                if (!value.startsWith("PHOTO|")) screen to value
                else keep(File(value.substringAfter('|')))?.let { h -> screen to "PHOTO|${Backgrounds.ASSET}$h" }
            }.toMap()
            if (kept.isEmpty()) v.remove(Look.BACKDROPS) else v[Look.BACKDROPS] = Backgrounds.tag(kept)
        }
        val custom = LookValue.text(v[Look.FONT_CHOICE]) == "CUSTOM"
        val font = if (custom) AppFonts.customFile(AppFonts.FontSlot.APP)?.let(::keep) else null
        if (font == null) { v.remove(Look.APP_FONT_NAME); if (custom) v.remove(Look.FONT_CHOICE) }
        // The lyrics' own font file stays outside Looks.
        if (LookValue.text(v[Look.LYRICS_FONT]) == "CUSTOM") v.remove(Look.LYRICS_FONT)
        val look = Look(id, name.trim().take(Look.MAX_NAME).ifBlank { tr(R.string.my_look) }, v, currentIcons(), font, created, note = note)
        write(look)
        if (repoint) setBasis(id)
        // The backgrounds now point at the kept photos (not a change to the look: no Undo for it).
        val photos = Backgrounds.parse(look.values[Look.BACKDROPS]).filterValues { Backgrounds.assetOf(it) != null }
        if (repoint && photos.isNotEmpty()) {
            val repointed = settings.lookValues.value + (Look.BACKDROPS to resolved(look).getValue(Look.BACKDROPS))
            expect = Expect(settings.lookAfter(repointed), silent = true)
            settings.applyLook(repointed)
        }
        look
    }

    /** Keeps [look] (a shared one, or a copy of Prism's own) as one of the listener's, under a new id. */
    suspend fun keep(look: Look, name: String = look.name): Look = withContext(Dispatchers.IO) {
        look.copy(id = newId(), name = name.trim().take(Look.MAX_NAME).ifBlank { look.name }, builtIn = false, note = "", created = System.currentTimeMillis())
            .also(::write)
    }

    /**
     * Saves the app's look as it is now into your own [look] (same name, note and place in the list). The Undo bar
     * then offers the Look as it was saved before.
     */
    suspend fun update(look: Look): Look {
        val before = _mine.value.firstOrNull { it.id == look.id } ?: look
        // "Before <Look>"'s note ("from before you picked …") stops being true once it's updated.
        val saved = saveCurrent(before.name, before.id, if (before.id == BEFORE_ID) "" else before.note, created = before.created)
        _notes.tryEmit(UndoNote(tr(R.string.updated_2, before.name), ++noteId, undo = { scope.launch(Dispatchers.IO) { write(before) } }))
        return saved
    }

    /** What you've changed since [look] was saved ("You've changed its colors and type"); null for nothing. */
    fun edits(look: Look): String? {
        val now = settings.lookValues.value
        val saved = settings.lookAfter(resolved(look))
        return LookLabels.summary((now.keys + saved.keys).filter { now[it] != saved[it] }, look.icons != currentIcons(), lead = R.string.you_ve_changed_its)
    }

    fun rename(id: String, name: String) {
        val look = _mine.value.firstOrNull { it.id == id } ?: return
        scope.launch(Dispatchers.IO) { write(look.copy(name = name.trim().take(Look.MAX_NAME).ifBlank { look.name })) }
    }

    fun delete(id: String) {
        _mine.value = _mine.value.filterNot { it.id == id }
        if (basis.value == id) setBasis(null)
        scope.launch(Dispatchers.IO) {
            File(dir, "$id.json").delete()
            if (_schedule.value.day == id || _schedule.value.night == id) setSchedule(_schedule.value.copy(on = false))
            sweepAssets()
        }
    }

    /** Photos and fonts no Look, background or undo step uses any more. */
    private fun sweepAssets() {
        val used = HashSet<String>()
        _mine.value.forEach { l -> used += l.photoAssets; l.font?.let(used::add) }
        val paths = (listOf(settings.lookValues.value) + _steps.value.map { it.before })
            .flatMap { Backgrounds.parse(it[Look.BACKDROPS]).values }.map { it.substringAfter('|') }
        assetDir.listFiles()?.forEach { f -> if (f.name !in used && paths.none { p -> p == f.absolutePath }) f.delete() }
    }

    /** A copy of [file] kept by its content's hash (only Prism's own files); its hash, or null if it isn't there. */
    private fun keep(file: File): String? = runCatching {
        if (!file.canonicalPath.startsWith(app.filesDir.canonicalPath) || !file.isFile) return null
        val h = sha1(file.readBytes())
        val out = File(assetDir, h)
        if (!out.exists()) { assetDir.mkdirs(); file.copyTo(out, overwrite = true) }
        h
    }.getOrNull()

    private fun sha1(bytes: ByteArray) = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    // ---------------------------------------------------------------- Switching

    /** [look]'s settings as they're stored: kept photos as their files' paths, its own font only when it has the file. */
    fun resolved(look: Look): Map<String, String> {
        val v = look.values.toMutableMap()
        v[Look.BACKDROPS]?.let { tagged ->
            val map = Backgrounds.parse(tagged).mapNotNull { (screen, value) ->
                val h = Backgrounds.assetOf(value)
                when {
                    h != null -> File(assetDir, h).takeIf { it.isFile }?.let { screen to "PHOTO|${it.absolutePath}" }
                    value.startsWith("PHOTO|") -> null
                    else -> screen to value
                }
            }.toMap()
            if (map.isEmpty()) v.remove(Look.BACKDROPS) else v[Look.BACKDROPS] = Backgrounds.tag(map)
        }
        if (LookValue.text(v[Look.FONT_CHOICE]) == "CUSTOM" && look.font?.let { File(assetDir, it).isFile } != true) {
            v.remove(Look.FONT_CHOICE); v.remove(Look.APP_FONT_NAME)
        }
        return v
    }

    /** The settings Prism would have with [look] (for its card's preview). */
    fun preview(look: Look) = settings.previewLook(resolved(look))

    /** What switching to [look] would change, in a line ("Changes your theme, colors and type"); null for nothing. */
    fun changes(look: Look): String? {
        val now = settings.lookValues.value
        val after = settings.lookAfter(resolved(look))
        return LookLabels.summary((now.keys + after.keys).filter { now[it] != after[it] }, look.icons != currentIcons())
    }

    /**
     * Switches to [look] in one edit; it shows in the undo bar as [label]. Settings that aren't any Look are kept first,
     * as "Before <Look>" in Your Looks ([BEFORE_ID], one, replaced each time), so they're a tap away however many changes
     * later: History only goes back ten.
     */
    suspend fun apply(look: Look, label: String = tr(R.string.look_2, look.name), scheduled: Boolean = false) = withContext(Dispatchers.IO) {
        val from = lookNow(settings.current)
        // A seasonal Look remembers the Look the listener had, to go back to when its month ends ([endSeasons]).
        if (Seasons.of(look.id) != null && Seasons.of(from?.id) == null) prefs.edit().putString(SEASON_FROM, from?.id ?: BEFORE_ID).apply()
        if (look.id != BEFORE_ID && from == null) runCatching {
            val note = if (scheduled) tr(R.string.your_own_settings_from_before_day, look.name)
            else tr(R.string.your_own_settings_from_before_you, look.name)
            saveCurrent(tr(R.string.before, look.name), BEFORE_ID, note, repoint = false)
            // GitHub #174: the schedule's turn put customizations out of sight, and it looked as if Prism had reset
            // them. They're kept (as above); the next time Prism is open, it says so and where.
            if (scheduled) noteKept(look.name)
        }
        val values = resolved(look)
        var fontChanged = false
        if (LookValue.text(values[Look.FONT_CHOICE]) == "CUSTOM") look.font?.let { h ->
            val dst = File(app.filesDir, "fonts/${AppFonts.FontSlot.APP.fileName}")
            if (!dst.isFile || sha1(dst.readBytes()) != h) {
                dst.parentFile?.mkdirs()
                File(assetDir, h).copyTo(dst, overwrite = true)
                fontChanged = true
            }
        }
        expect = Expect(settings.lookAfter(values), label = label)
        val yours = _mine.value.any { it.id == look.id }
        if (BuiltInLooks.of(look.id) == null && !yours) _other.value = look.copy(id = look.id.ifBlank { "shared" })
        setBasis(if (yours) look.id else null)
        // Its fonts made here first, off the main thread (G37: a Look in another font froze the screen ~1 s while its
        // weights were built for the first frame drawn in it).
        listOf(Look.FONT_CHOICE, "title_font", "lyrics_font").mapNotNull { k -> LookValue.text(values[k])?.let(FontChoice::upgrade) }
            .mapNotNull { n -> FontChoice.entries.firstOrNull { it.name == n } }
            .takeIf { it.isNotEmpty() }?.let { runCatching { AppFonts.warm(app, it) } }
        settings.applyLook(values, fontChanged)
        applyIcons(look.icons)
    }

    fun currentIcons(): LookIcons {
        val (w, r) = IconStyle.saved(app)
        return LookIcons(IconStyle.savedPack(app).name, w.name, r, IconStyle.savedLikeStar(app), IconStyle.savedFill(app).name)
    }

    private fun applyIcons(i: LookIcons) {
        if (i == currentIcons()) return
        IconStyle.save(app, IconWeight.entries.firstOrNull { it.name == i.weight } ?: IconWeight.REGULAR, i.rounded)
        IconStyle.savePack(app, IconPack.entries.firstOrNull { it.name == i.pack } ?: IconPack.PRISM)
        IconStyle.saveLikeStar(app, i.likeStar)
        IconStyle.saveFill(app, com.prism.music.ui.theme.IconFill.entries.firstOrNull { it.name == i.fill } ?: com.prism.music.ui.theme.IconFill.OUTLINE)
        iconsChanged()
    }

    /** The icon style was changed in Settings (it's kept outside the settings, so Looks are told). */
    fun iconsChanged() { iconsVersion.value++ }

    /** Whether the saved icon style differs from the one drawn (icons are built as Prism starts). */
    fun iconsNeedRestart(): Boolean {
        val (w, r) = IconStyle.saved(app)
        return w != IconStyle.weight || r != IconStyle.rounded || IconStyle.savedPack(app) != IconStyle.pack || IconStyle.savedLikeStar(app) != IconStyle.likeStar ||
            IconStyle.savedFill(app) != IconStyle.fill
    }

    // ---------------------------------------------------------------- Sharing

    private fun withoutPhotos(look: Look): Look {
        val b = look.values[Look.BACKDROPS] ?: return look
        val designs = Backgrounds.parse(b).filterValues { !it.startsWith("PHOTO|") }
        return look.copy(values = if (designs.isEmpty()) look.values - Look.BACKDROPS else look.values + (Look.BACKDROPS to Backgrounds.tag(designs)))
    }

    private fun withoutFont(look: Look): Look = look.copy(
        font = null,
        values = if (LookValue.text(look.values[Look.FONT_CHOICE]) == "CUSTOM") look.values - Look.FONT_CHOICE - Look.APP_FONT_NAME else look.values,
    )

    /** [look] without what can't go in a code: photos and its own font. */
    private fun forCode(look: Look): Look = withoutFont(withoutPhotos(look))

    /** [look] as a code to send or show as a QR code (no photos or own font: those need a file, [writeFile]). */
    fun code(look: Look): String = LookCode.encode(forCode(look))

    /** Whether [look] has photos or a font a code would leave out. */
    fun hasFiles(look: Look): Boolean = look.photoAssets.isNotEmpty() || look.font != null

    /** A shared Look in [text] (a code), checked, or null. */
    fun decode(text: String?): Look? = LookCode.decode(text, settings.lookKinds)

    /**
     * [look] as a `.prismlook` file: a zip of its code and, when ticked, its photos ([withPhotos]) and own font
     * ([withFont]). Throws when it would be over [MAX_FILE] bytes.
     */
    suspend fun writeFile(look: Look, out: OutputStream, withPhotos: Boolean, withFont: Boolean) = withContext(Dispatchers.IO) {
        var l = look
        if (!withPhotos) l = withoutPhotos(l)
        if (!withFont) l = withoutFont(l)
        val assets = (l.photoAssets + listOfNotNull(l.font)).distinct().map { File(assetDir, it) }.filter { it.isFile }
        if (assets.sumOf { it.length() } > MAX_FILE) error("Its photos and font come to more than 8 MB. Untick one and try again.")
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(FILE_CODE))
            zip.write(LookCode.encode(l).toByteArray())
            zip.closeEntry()
            assets.forEach { f ->
                zip.putNextEntry(ZipEntry("assets/${f.name}"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /** The Look in a `.prismlook` file; its photos and font are kept (checked against their hashes). Throws with a message. */
    suspend fun readFile(input: InputStream): Look = withContext(Dispatchers.IO) {
        var look: Look? = null
        var total = 0L
        val files = HashMap<String, ByteArray>()
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                val bytes = readCapped(zip, MAX_FILE + 64 * 1024 - total) ?: error(tr(R.string.that_look_file_is_too_big))
                total += bytes.size
                when {
                    e.name == FILE_CODE -> look = decode(bytes.decodeToString())
                    e.name.startsWith("assets/") -> e.name.removePrefix("assets/").takeIf(Backgrounds::isHash)?.let { files[it] = bytes }
                }
            }
        }
        val l = look ?: error(tr(R.string.that_file_isn_t_a_prism))
        val wanted = (l.photoAssets + listOfNotNull(l.font)).toSet()
        files.filterKeys { it in wanted }.forEach { (h, bytes) ->
            if (sha1(bytes) == h) { assetDir.mkdirs(); File(assetDir, h).writeBytes(bytes) }
        }
        l
    }

    private fun readCapped(input: InputStream, max: Long): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) return null
        }
        return out.toByteArray()
    }

    // ---------------------------------------------------------------- Undo and history

    /**
     * A change Prism is about to make itself: when the look lands exactly on [target] (within a few seconds), it reads as
     * [label] ([silent]: not kept at all; [undo]: a step taken back). Anything else is the listener's own change, so a
     * Look picked again with nothing to change can't label the next real change as a switch.
     */
    private class Expect(
        val target: Map<String, String>,
        val label: String? = null,
        val silent: Boolean = false,
        val undo: Boolean = false,
        val until: Long = System.currentTimeMillis() + 5_000,
    ) {
        fun matches(now: Map<String, String>) = now == target && System.currentTimeMillis() < until
    }
    @Volatile private var expect: Expect? = null

    private val _steps = MutableStateFlow(loadSteps())
    /** The last [MAX_STEPS] changes to the look, newest first. */
    val steps: StateFlow<List<LookStep>> = _steps.asStateFlow()
    private val _notes = MutableSharedFlow<UndoNote>(extraBufferCapacity = 4)
    /** One for each change to the look, for the "Undo" bar. */
    val notes: SharedFlow<UndoNote> = _notes
    private var noteId = 0L

    private fun loadSteps(): List<LookStep> = runCatching {
        json.decodeFromString(ListSerializer(LookStep.serializer()), File(dir, HISTORY).readText())
    }.getOrDefault(emptyList()).take(MAX_STEPS)

    private fun saveSteps(steps: List<LookStep>) {
        _steps.value = steps
        scope.launch(Dispatchers.IO) { runCatching { dir.mkdirs(); File(dir, HISTORY).writeText(json.encodeToString(ListSerializer(LookStep.serializer()), steps)) } }
    }

    private fun changedKeys(before: Map<String, String>, now: Map<String, String>) =
        (before.keys + now.keys).filter { before[it] != now[it] }.sorted()

    private val order: List<String> by lazy { settings.lookKinds.keys.toList() }

    private fun onChange(before: Map<String, String>, now: Map<String, String>) {
        val pending = expect
        val e = pending?.takeIf { it.matches(now) }
        // Kept for the change it's waiting for until it lands or runs out (a change of the listener's may come first).
        if (e != null || (pending != null && System.currentTimeMillis() >= pending.until)) expect = null
        if (e?.silent == true || e?.undo == true) return
        val keys = changedKeys(before, now)
        if (keys.isEmpty()) return
        val label = e?.label ?: LookLabels.change(before, now, order) ?: return
        val steps = _steps.value
        val top = steps.firstOrNull()
        val at = System.currentTimeMillis()
        // Dragging a slider or flicking one switch back and forth is one step.
        val merged = e == null && top != null && !top.look && top.keys == keys && at - top.at < MERGE_MS
        saveSteps(if (merged) listOf(top!!.copy(label = label, at = at)) + steps.drop(1) else (listOf(LookStep(label, at, before, keys, look = e != null)) + steps).take(MAX_STEPS))
        _notes.tryEmit(UndoNote(label, ++noteId))
    }

    /** Takes back the last change. */
    fun undo() = goBack(0)

    /** Forgets every step (#152); the look stays as it is now. */
    fun clearHistory() = saveSteps(emptyList())

    /** Puts the look back as it was before step [index] (and everything after it). */
    fun goBack(index: Int) {
        val steps = _steps.value
        val step = steps.getOrNull(index) ?: return
        saveSteps(steps.drop(index + 1))
        expect = Expect(settings.lookAfter(step.before), undo = true)
        scope.launch { settings.applyLook(step.before) }
    }

    // ---------------------------------------------------------------- Schedule

    private val _schedule = MutableStateFlow(runCatching { json.decodeFromString(LookSchedule.serializer(), File(dir, SCHEDULE).readText()) }.getOrDefault(LookSchedule()))
    val schedule: StateFlow<LookSchedule> = _schedule.asStateFlow()

    fun setSchedule(s: LookSchedule) {
        _schedule.value = s
        scope.launch(Dispatchers.IO) { runCatching { dir.mkdirs(); File(dir, SCHEDULE).writeText(json.encodeToString(LookSchedule.serializer(), s)) } }
    }

    /** When the schedule next switches, and to which part ([SunClock.Turn]), for Settings to say. */
    fun nextTurn(s: LookSchedule = _schedule.value): SunClock.Turn {
        val zone = ZoneId.systemDefault()
        return SunClock.around(Instant.now(), zone, s.sun, s.dayAt, s.nightAt, ZonePlaces.of(app, zone)).second
    }

    /**
     * Keeps the schedule: at each sunrise and sunset (or set time) the part's Look goes on, once, so a Look picked by
     * hand in between stays until the next turn. Turning the schedule on puts the part's Look on at once.
     */
    private fun runSchedule() = scope.launch {
        _schedule.collectLatest { s ->
            if (!s.on) return@collectLatest
            while (true) {
                val zone = ZoneId.systemDefault()
                val now = Instant.now()
                val (last, next) = SunClock.around(now, zone, s.sun, s.dayAt, s.nightAt, ZonePlaces.of(app, zone))
                val id = if (last.to == SunClock.Part.DAY) s.day else s.night
                val key = "${last.to}@${last.at.epochSecond / 60}|$id"
                if (key != prefs.getString(LAST_TURN, null)) {
                    // A seasonal Look picked for day or night stays put away outside its month.
                    find(id)?.takeIf { Seasons.shown(it.id) }?.let { apply(it, tr(if (last.to == SunClock.Part.DAY) R.string.look_for_the_day else R.string.look_for_the_night, it.name), scheduled = true) }
                    prefs.edit().putString(LAST_TURN, key).apply()
                }
                // Looked at again by the turn, and at least every 15 minutes (the clock or time zone may change).
                delay(Duration.between(now, next.at).toMillis().plus(1_000).coerceIn(5_000, 15 * 60_000))
            }
        }
    }

    // ---------------------------------------------------------------- Seasons

    /** Whether the look is [look] exactly (its settings and icons), as stored: Performance mode doesn't hide it. */
    fun isOn(look: Look): Boolean = look.icons == currentIcons() && settings.lookValues.value == settings.lookAfter(resolved(look))

    /**
     * When a seasonal Look's month is over (once for each turn, so a Look picked afterwards is left alone): a listener on
     * it goes back to the Look they had before it ([SEASON_FROM], or "Before <Look>" for their own mix), and a listener who
     * changed it since keeps their changes but loses its background (the screens go back to theirs, or the default).
     * Returns whether it changed anything.
     */
    suspend fun endSeasons(today: java.time.LocalDate = Seasons.today()): Boolean {
        var changed = false
        for ((season, key) in Seasons.ended(today)) {
            val done = prefs.getStringSet(SEASONS_ENDED, emptySet()).orEmpty()
            if (key in done) continue
            val look = BuiltInLooks.of(season.lookId)
            val backdrops = Backgrounds.parse(settings.lookValues.value[Look.BACKDROPS])
            if (look != null && isOn(look)) {
                val back = prefs.getString(SEASON_FROM, null)?.let(::find)?.takeIf { Seasons.of(it.id) == null } ?: BuiltInLooks.all.first()
                apply(back, tr(R.string.season_over_back_to, look.name, back.name))
                changed = true
            } else if (backdrops.values.any { Backdrop.parse(it).first.season == season }) {
                val before = prefs.getString(SEASON_FROM, null)?.let(::find)?.let { Backgrounds.parse(resolved(it)[Look.BACKDROPS]) }.orEmpty()
                val kept = backdrops.mapNotNull { (screen, v) ->
                    if (Backdrop.parse(v).first.season != season) screen to v else before[screen]?.let { screen to it }
                }.toMap()
                val values = settings.lookValues.value.toMutableMap()
                if (kept.isEmpty()) values.remove(Look.BACKDROPS) else values[Look.BACKDROPS] = Backgrounds.tag(kept)
                expect = Expect(settings.lookAfter(values), label = tr(R.string.season_over_backgrounds, look?.name ?: season.name))
                settings.applyLook(values)
                changed = true
            }
            prefs.edit().putStringSet(SEASONS_ENDED, done + key).apply()
        }
        return changed
    }

    /** Looks at the seasons as Prism starts, and again at each midnight (and at least hourly: the clock or zone may change). */
    private fun runSeasons() = scope.launch {
        while (true) {
            runCatching { endSeasons() }
            val zone = java.time.ZoneId.systemDefault()
            val now = java.time.ZonedDateTime.now(zone)
            val midnight = now.toLocalDate().plusDays(1).atStartOfDay(zone)
            delay(Duration.between(now, midnight).toMillis().plus(1_000).coerceIn(5_000, 60 * 60_000))
        }
    }

    init {
        runSeasons()
        scope.launch {
            var last = settings.lookValues.value
            settings.lookValues.collect { now ->
                val before = last
                last = now
                if (before != now) onChange(before, now)
            }
        }
        runSchedule()
    }

    companion object {
        const val MAX_STEPS = 10
        /** The one "Before <Look>" kept by [apply]. */
        const val BEFORE_ID = "my-before"
        /** Bigger files' photos and fonts aren't taken (8 MB). */
        const val MAX_FILE = 8L * 1024 * 1024
        const val FILE_EXTENSION = "prismlook"
        private const val FILE_CODE = "look.txt"
        private const val HISTORY = "history.json"
        private const val BASIS = "basis"
        private const val SCHEDULE = "schedule.json"
        private const val LAST_TURN = "schedule_last"
        private const val KEPT = "schedule_kept"
        /** The Look the listener had before a seasonal one. */
        private const val SEASON_FROM = "season_from"
        /** The seasons' turns already seen to ("HARVEST:2026"). */
        private const val SEASONS_ENDED = "seasons_ended"
        private const val MERGE_MS = 4_000L
    }
}
