package com.prism.music.ui.screens

import com.prism.music.R
import com.prism.music.tr
import com.prism.music.ui.theme.PrismIcons
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val SOURCE_URL = "https://github.com/Yigder/Prism"

/** A library shipped in the APK; [text] names its license text in assets/licenses/. */
private class Library(val name: String, val by: String, val license: String, val text: String, val url: String)

private val libraries = listOf(
    Library(tr(R.string.newpipe_extractor), tr(R.string.team_newpipe), tr(R.string.gpl_3_0_or_later), "gpl-3.0", "https://github.com/TeamNewPipe/NewPipeExtractor"),
    Library("nanojson", tr(R.string.matt_mastracci_team_newpipe), tr(R.string.mit_or_apache_2_0), "apache-2.0", "https://github.com/TeamNewPipe/nanojson"),
    Library(tr(R.string.rhino), tr(R.string.mozilla), tr(R.string.mpl_2_0), "mpl-2.0", "https://github.com/mozilla/rhino"),
    Library("EJS", "yt-dlp", tr(R.string.unlicense), "ejs", "https://github.com/yt-dlp/ejs"),
    Library(tr(R.string.meriyah_inside_ejs), tr(R.string.kflash_and_others), "ISC", "meriyah", "https://github.com/meriyah/meriyah"),
    Library(tr(R.string.astring_inside_ejs), tr(R.string.david_bonnet), "MIT", "astring", "https://github.com/davidbonnet/astring"),
    Library("jsoup", tr(R.string.jonathan_hedley), "MIT", "jsoup", "https://jsoup.org"),
    Library(tr(R.string.androidx_compose_material_3_media3_room), tr(R.string.the_android_open_source_project), tr(R.string.apache_2_0), "apache-2.0", "https://developer.android.com/jetpack/androidx"),
    Library(tr(R.string.kotlin_kotlinx_coroutines_kotlinx_serialization), tr(R.string.jetbrains), tr(R.string.apache_2_0), "apache-2.0", "https://kotlinlang.org"),
    Library(tr(R.string.okhttp_okio), tr(R.string.licensor_square), tr(R.string.apache_2_0), "apache-2.0", "https://square.github.io/okhttp"),
    Library(tr(R.string.coil), tr(R.string.coil_contributors), tr(R.string.apache_2_0), "apache-2.0", "https://coil-kt.github.io/coil"),
    Library(tr(R.string.haze), tr(R.string.chris_banes), tr(R.string.apache_2_0), "apache-2.0", "https://github.com/chrisbanes/haze"),
    Library(tr(R.string.accompanist), tr(R.string.google), tr(R.string.apache_2_0), "apache-2.0", "https://github.com/google/accompanist"),
    Library(tr(R.string.guava_error_prone_annotations_j2objc_annotations), tr(R.string.google_and_others), tr(R.string.apache_2_0), "apache-2.0", "https://github.com/google/guava"),
    Library(tr(R.string.protocol_buffers_lite), tr(R.string.google), tr(R.string.bsd_3_clause), "protobuf", "https://github.com/protocolbuffers/protobuf"),
    Library(tr(R.string.checker_framework_qualifiers), tr(R.string.checker_framework_developers), "MIT", "checker-qual", "https://checkerframework.org"),
    Library(tr(R.string.kuromoji_japanese_readings_for_romanized_lyrics), tr(R.string.atilika), tr(R.string.apache_2_0), "apache-2.0", "https://github.com/atilika/kuromoji"),
    Library(tr(R.string.ipadic_downloaded_with_japanese_reading_data), tr(R.string.nara_institute_of_science_and_technology), tr(R.string.ipadic_license), "ipadic", "https://github.com/atilika/kuromoji"),
    Library(tr(R.string.material_3_adaptive_androidx_window), tr(R.string.the_android_open_source_project), tr(R.string.apache_2_0), "apache-2.0", "https://developer.android.com/jetpack/androidx"),
    // Prism TV (1.8.0): the name is the library's own, the same in every language.
    Library(tr(R.string.compose_for_tv),tr(R.string.the_android_open_source_project), tr(R.string.apache_2_0), "apache-2.0", "https://developer.android.com/jetpack/androidx/releases/tv"),
    Library(tr(R.string.songrec_song_recognition_s_audio_fingerprint), tr(R.string.marin_m), tr(R.string.gpl_3_0), "gpl-3.0", "https://github.com/marin-m/SongRec"),
    Library(tr(R.string.zxing_core_blend_s_qr_codes), tr(R.string.zxing_authors), tr(R.string.apache_2_0), "apache-2.0", "https://github.com/zxing/zxing"),
    Library(tr(R.string.camerax_scanning_a_blend_code), tr(R.string.the_android_open_source_project), tr(R.string.apache_2_0), "apache-2.0", "https://developer.android.com/jetpack/androidx/releases/camera"),
    Library(tr(R.string.onnx_runtime_sing_s_engine_downloaded), tr(R.string.microsoft), "MIT", "onnxruntime", "https://github.com/microsoft/onnxruntime"),
    Library(tr(R.string.spleeter_2_stem_model_sing_downloaded), tr(R.string.deezer_onnx_conversion_by_sherpa_onnx), "MIT", "spleeter", "https://github.com/deezer/spleeter"),
    Library(tr(R.string.outfit_prism_s_typeface), tr(R.string.the_outfit_project_authors), tr(R.string.sil_open_font_license_1_1), "outfit", "https://github.com/Outfitio/Outfit-Fonts"),
    Library(tr(R.string.manrope_the_clean_font), tr(R.string.the_manrope_project_authors), tr(R.string.sil_open_font_license_1_1), "manrope", "https://github.com/googlefonts/manrope"),
    Library(tr(R.string.plus_jakarta_sans_the_geometric_font), tr(R.string.the_plus_jakarta_sans_project_authors), tr(R.string.sil_open_font_license_1_1), "plus_jakarta_sans", "https://github.com/tokotype/PlusJakartaSans"),
    Library(tr(R.string.urbanist_the_airy_font), tr(R.string.the_urbanist_project_authors), tr(R.string.sil_open_font_license_1_1), "urbanist", "https://github.com/coreyhu/Urbanist"),
    Library(tr(R.string.nunito_the_rounded_font), tr(R.string.the_nunito_project_authors), tr(R.string.sil_open_font_license_1_1), "nunito", "https://github.com/googlefonts/nunito"),
    Library(tr(R.string.fraunces_the_editorial_font), tr(R.string.the_fraunces_project_authors), tr(R.string.sil_open_font_license_1_1), "fraunces", "https://github.com/undercasetype/Fraunces"),
    Library(tr(R.string.time_zone_places_zone1970_tab_backward), tr(R.string.the_tz_database_contributors), tr(R.string.public_domain), "tzdata", "https://www.iana.org/time-zones"),
    Library(tr(R.string.geist_mono_the_mono_font), tr(R.string.the_geist_project_authors), tr(R.string.sil_open_font_license_1_1), "geist_mono", "https://github.com/vercel/geist-font"),
    Library(tr(R.string.atkinson_hyperlegible_next_the_legible_font), tr(R.string.the_atkinson_hyperlegible_next_project_authors), tr(R.string.sil_open_font_license_1_1), "atkinson_hyperlegible_next", "https://github.com/googlefonts/atkinson-hyperlegible-next"),
    Library(tr(R.string.opendyslexic_the_dyslexia_friendly_font), tr(R.string.abbie_gonzalez), tr(R.string.sil_open_font_license_1_1), "opendyslexic", "https://github.com/antijingoist/opendyslexic"),
)

/** Settings → About → Open-source licenses: Prism's own license, then everything it ships with. */
@Composable
fun LicensesPage() {
    val uri = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Group(tr(R.string.app_name)) {
            Note(tr(R.string.prism_is_free_software_under_the))
            Item(tr(R.string.source_code), SOURCE_URL.removePrefix("https://"), onClick = { uri.openUri(SOURCE_URL) }) {
                Icon(PrismIcons.OpenInNew, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LicenseRow(tr(R.string.gnu_gpl_3_0), tr(R.string.prism_s_license), "gpl-3.0")
        }
        Group(tr(R.string.built_with)) {
            Note(tr(R.string.tap_one_to_read_its_license))
            libraries.forEach { LicenseRow(it.name, "${it.license} · ${it.by}", it.text, it.url) }
        }
    }
}

/** A library or license that opens in place to show its full license text. */
@Composable
private fun LicenseRow(title: String, subtitle: String, text: String, url: String? = null) {
    var open by rememberSaveable(title) { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    Item(title, subtitle, onClick = { open = !open }) {
        Icon(if (open) PrismIcons.ChevronUp else PrismIcons.ChevronDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    AnimatedVisibility(open) {
        Column {
            if (url != null) Item(tr(R.string.project_page), url.removePrefix("https://"), onClick = { uri.openUri(url) }) {
                Icon(PrismIcons.OpenInNew, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) { LicenseText(text) }
        }
    }
}

/** License files are wrapped at ~80 columns; join each paragraph's lines so they wrap to the screen instead. */
private fun reflow(text: String): String =
    text.split(Regex("\\n\\s*\\n")).joinToString("\n\n") { para -> para.lines().joinToString(" ") { it.trim() }.trim() }.trim()

@Composable
private fun LicenseText(name: String) {
    val context = LocalContext.current
    val text by produceState<String?>(null, name) {
        value = withContext(Dispatchers.IO) {
            runCatching { reflow(context.assets.open("licenses/$name.txt").use { it.readBytes().decodeToString() }) }.getOrNull()
        }
    }
    Text(text ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
