package com.prism.music.data.prefs

import com.prism.music.R
import com.prism.music.tr
import android.content.Context
import android.util.Base64
import com.prism.music.playback.EqPresets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** An equalizer setting the listener saved under a name (1.7.8, A3). */
@Serializable
data class SavedEq(
    val name: String,
    val bands: List<Float>,
    val preampDb: Float = 0f,
    val bassBoost: Int = 0,
    val loudnessGainMb: Int = 0,
) {
    /** On the equalizer, switched on. */
    fun applyTo(eq: EqSettings) = eq.copy(enabled = true, preset = name, bands = bands, preampDb = preampDb, bassBoost = bassBoost, loudnessGainMb = loudnessGainMb)

    companion object {
        fun of(name: String, eq: EqSettings) = SavedEq(name.trim().take(MAX_NAME), eq.bands, eq.preampDb, eq.bassBoost, eq.loudnessGainMb)
        const val MAX_NAME = 40
    }
}

/**
 * Your own equalizer presets (1.7.8, A3): saved under a name, shared as a code ("PRISMEQ1:…", like a Look's), and one
 * picked for an output (the car, the earbuds, the speaker), which then plays through it whatever the equalizer is set to
 * elsewhere. Kept in the "eq_presets" prefs (in a backup's Settings).
 */
class EqPresetStore(context: Context) {
    private val prefs = context.getSharedPreferences("eq_presets", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _saved = MutableStateFlow(load<List<SavedEq>>("saved").orEmpty())
    val saved: StateFlow<List<SavedEq>> = _saved

    private val _outputs = MutableStateFlow(load<Map<String, String>>("outputs").orEmpty())
    /** Output key ([com.prism.music.data.HeadphoneEq.keyOf]) → the preset (yours or one of Prism's) it plays through. */
    val outputs: StateFlow<Map<String, String>> = _outputs

    private inline fun <reified T> load(key: String): T? =
        prefs.getString(key, null)?.let { runCatching { json.decodeFromString<T>(it) }.getOrNull() }

    /** Saves [eq] as [name] (replacing one of the same name). */
    fun save(name: String, eq: EqSettings): SavedEq {
        val preset = SavedEq.of(name, eq)
        _saved.value = _saved.value.filterNot { it.name.equals(preset.name, ignoreCase = true) } + preset
        prefs.edit().putString("saved", json.encodeToString(_saved.value)).apply()
        return preset
    }

    fun add(preset: SavedEq) = save(preset.name, preset.applyTo(EqSettings()))

    fun delete(name: String) {
        _saved.value = _saved.value.filterNot { it.name == name }
        prefs.edit().putString("saved", json.encodeToString(_saved.value)).apply()
        _outputs.value.filterValues { it == name }.keys.forEach { bind(it, null) }
    }

    /** Plays [outputKey] through [preset] from now on, or (null) through the equalizer as set. */
    fun bind(outputKey: String, preset: String?) {
        _outputs.value = if (preset == null) _outputs.value - outputKey else _outputs.value + (outputKey to preset)
        prefs.edit().putString("outputs", json.encodeToString(_outputs.value)).apply()
    }

    companion object {
        private const val PREFIX = "PRISMEQ1:"
        private val codeJson = Json { ignoreUnknownKeys = true }

        /** The equalizer to use: the output's own preset when one is picked for it, else [eq] as set. */
        fun effective(eq: EqSettings, outputKey: String?, outputs: Map<String, String>, saved: List<SavedEq>): EqSettings {
            val name = outputKey?.let { outputs[it] } ?: return eq
            saved.firstOrNull { it.name == name }?.let { return it.applyTo(eq) }
            EqPresets.presets[name]?.let { return eq.copy(enabled = true, preset = name, bands = it) }
            return eq
        }

        /** [preset] as text to send (a code anyone can paste into their Prism). */
        fun code(preset: SavedEq): String = PREFIX + Base64.encodeToString(
            codeJson.encodeToString(preset).toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )

        /** A preset from a code someone sent ([code]), checked and kept within the equalizer's range; null if it isn't one. */
        fun parse(text: String): SavedEq? {
            val at = text.indexOf(PREFIX).takeIf { it >= 0 } ?: return null
            val body = text.substring(at + PREFIX.length).takeWhile { it.isLetterOrDigit() || it == '-' || it == '_' }
            val raw = runCatching { codeJson.decodeFromString<SavedEq>(String(Base64.decode(body, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))) }
                .getOrNull() ?: return null
            if (raw.bands.size != 10 || raw.bands.any { it.isNaN() }) return null
            val name = raw.name.filter { !it.isISOControl() }.trim().take(SavedEq.MAX_NAME).ifBlank { tr(R.string.shared) }
            return SavedEq(
                name, raw.bands.map { it.coerceIn(-12f, 12f) }, raw.preampDb.takeIf { !it.isNaN() }?.coerceIn(-12f, 6f) ?: 0f,
                raw.bassBoost.coerceIn(0, 1000), raw.loudnessGainMb.coerceIn(0, 1500),
            )
        }
    }
}
