package com.prism.music.data

import com.prism.music.R
import com.prism.music.tr
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.prism.music.playback.EqProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URLDecoder

/** A Bluetooth device's address, or "" before Android 9 (where it doesn't exist and calling it crashes). */
fun AudioDeviceInfo.addressOrBlank(): String = if (android.os.Build.VERSION.SDK_INT >= 28) address else ""

/**
 * Headphone correction (AutoEq): measured correction curves for thousands of headphones, from the AutoEq project's
 * results on GitHub (MIT), each kept for the headphones it was picked for. Plugging those in (or connecting them)
 * switches it on; the phone's speaker, the car and anything else never get one unless it's picked for them.
 */
class HeadphoneEq(
    private val context: Context,
    private val http: OkHttpClient,
    scope: CoroutineScope,
    /** The output picked in the player, if any (else the system's media route counts). */
    private val preferred: StateFlow<AudioDeviceInfo?>,
) {
    /** One headphone in AutoEq's index: its name, who measured it (and on what), and its folder. */
    data class Entry(val name: String, val source: String, val rig: String?, val path: List<String>) {
        val by: String get() = if (rig != null) tr(R.string.on_4, source, rig) else source
    }

    /** Where the music is going, as far as correction is concerned. */
    data class Output(val key: String, val name: String, val headphones: Boolean)

    @Serializable
    data class Binding(val deviceName: String, val profile: EqProfile, val strength: Float = 1f)

    private val prefs = context.getSharedPreferences("headphone_eq", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val _bindings = MutableStateFlow(load())
    /** Device key → the profile picked for it. */
    val bindings: StateFlow<Map<String, Binding>> = _bindings

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val redetect = Runnable { _output.value = detect() }

    /** The last output [detect] found (diagnostics log a change). */
    @Volatile private var lastDetected: Output? = null
    private val _output = MutableStateFlow(detect())
    val output: StateFlow<Output?> = _output

    /** The correction to apply now: the current output's, if it has one. */
    val active: StateFlow<Binding?> = combine(_output, _bindings) { out, b -> out?.key?.let { b[it] } }
        .stateIn(scope, SharingStarted.Eagerly, _output.value?.key?.let { _bindings.value[it] })

    init {
        // Looked at again once devices have settled for a second: earbuds add and drop entries (their microphone, ear
        // detection, the case), and the output seen mid-change could switch the correction off and on within a song.
        context.getSystemService(AudioManager::class.java)?.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = detectSoon()
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = detectSoon()
        }, null)
        scope.launchCollect(preferred) { _output.value = detect() }
    }

    private fun <T> CoroutineScope.launchCollect(flow: StateFlow<T>, block: (T) -> Unit) =
        launch(Dispatchers.Main) { flow.collect { block(it) } }

    private fun detect(): Output? {
        val d = runCatching { com.prism.music.ui.player.currentOutput(context, preferred.value) }.getOrNull() ?: return null
        return Output(keyOf(d), nameOf(d), isHeadphones(d)).also { o ->
            if (o != lastDetected) com.prism.music.playback.AudioDiagnostics.log(
                "correction", "output is now ${com.prism.music.playback.AudioDiagnostics.describe(d)}; correction ${_bindings.value[o.key]?.profile?.name ?: "none"}",
            )
            lastDetected = o
        }
    }

    fun refreshOutput() { _output.value = detect() }

    private fun detectSoon() {
        main.removeCallbacks(redetect)
        main.postDelayed(redetect, SETTLE_MS)
    }

    // ------------------------------------------------------------------ Picking

    fun bind(output: Output, profile: EqProfile, strength: Float = 1f) {
        _bindings.value = _bindings.value + (output.key to Binding(output.name, profile, strength))
        save()
    }

    fun setStrength(key: String, strength: Float) {
        val b = _bindings.value[key] ?: return
        _bindings.value = _bindings.value + (key to b.copy(strength = strength.coerceIn(0f, 1f)))
        save()
    }

    fun unbind(key: String) {
        _bindings.value = _bindings.value - key
        save()
    }

    private fun load(): Map<String, Binding> = runCatching {
        prefs.getString("bindings", null)?.let { json.decodeFromString<Map<String, Binding>>(it) }
    }.getOrNull().orEmpty()

    private fun save() = prefs.edit().putString("bindings", json.encodeToString(_bindings.value)).apply()

    // ------------------------------------------------------------------ AutoEq

    private val indexFile get() = File(context.filesDir, "autoeq/INDEX.md")
    @Volatile private var indexCache: List<Entry>? = null

    /** Every headphone AutoEq has a correction for. Downloaded once (~0.9 MB) and refreshed monthly. */
    suspend fun index(): List<Entry> = withContext(Dispatchers.IO) {
        indexCache?.let { return@withContext it }
        val file = indexFile
        val stale = !file.isFile || System.currentTimeMillis() - file.lastModified() > MONTH_MS
        if (stale) runCatching {
            val text = get("$RAW/results/INDEX.md")
            file.parentFile?.mkdirs()
            file.writeText(text)
        }.onFailure { if (!file.isFile) throw it }
        parseIndex(file.readText()).also { indexCache = it }
    }

    /** The headphones matching [query] (every word of it), best matches first; one row per measurement. */
    suspend fun search(query: String): List<Entry> {
        val words = normalize(query).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        return index().asSequence()
            .map { it to normalize(it.name) }
            .filter { (_, n) -> words.all { w -> n.contains(w) } }
            .sortedWith(compareBy({ (_, n) -> if (n.startsWith(words.first())) 0 else 1 }, { (e, _) -> SOURCE_RANK.indexOf(e.source).let { if (it < 0) 99 else it } }, { (_, n) -> n.length }))
            .map { it.first }
            .take(60)
            .toList()
    }

    /** The correction for [e], from its ParametricEQ.txt. */
    suspend fun profile(e: Entry): EqProfile = withContext(Dispatchers.IO) {
        val url = HttpUrl.Builder().scheme("https").host("raw.githubusercontent.com")
            .addPathSegments("jaakkopasanen/AutoEq/master/results")
            .apply { e.path.forEach { addPathSegment(it) } }
            .addPathSegment("${e.name} ParametricEQ.txt")
            .build()
        EqProfile.parse(e.name, get(url.toString()), e.by) ?: throw IOException(tr(R.string.autoeq_s_file_for_had_no, e.name))
    }

    private fun get(url: String): String {
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException(tr(R.string.github_answered_http, resp.code))
            return resp.body.string()
        }
    }

    companion object {
        private const val RAW = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master"
        private const val MONTH_MS = 30L * 24 * 3600 * 1000
        /** Devices settle this long before the output is looked at again. */
        private const val SETTLE_MS = 1_000L
        /** AutoEq's own recommendation order for measurement sources. */
        private val SOURCE_RANK = listOf("oratory1990", "crinacle", "Rtings", "Innerfidelity", "Headphone.com Legacy")
        private val LINE = Regex("""^- \[(.+?)]\(\./(.+?)\) by (.+?)(?: on (.+))?\s*$""")

        fun parseIndex(text: String): List<Entry> = text.lineSequence().mapNotNull { line ->
            val m = LINE.find(line) ?: return@mapNotNull null
            // The headphone's own folder (source / rig / name), which holds its "<name> ParametricEQ.txt".
            val segments = m.groupValues[2].split('/').map { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }
            Entry(m.groupValues[1], m.groupValues[3].trim(), m.groupValues[4].takeIf { it.isNotBlank() }?.trim(), segments)
        }.toList()

        private fun normalize(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

        fun isHeadphones(d: AudioDeviceInfo): Boolean = d.type in setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET,
        )

        /** Which headphones these are, across reconnects: a Bluetooth pair by address, others by kind and name. */
        fun keyOf(d: AudioDeviceInfo): String = when (d.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER ->
                "bt:" + d.addressOrBlank().ifBlank { d.productName.toString() }
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "usb:" + d.productName
            // Wired headphones don't say which they are: one choice for whatever's plugged in.
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired"
            else -> "other:${d.type}"
        }

        fun nameOf(d: AudioDeviceInfo): String = when (d.type) {
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> tr(R.string.wired_headphones)
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> tr(R.string.phone_speaker)
            else -> d.productName?.toString()?.takeIf { it.isNotBlank() && it != android.os.Build.MODEL } ?: tr(R.string.headphones)
        }
    }
}
