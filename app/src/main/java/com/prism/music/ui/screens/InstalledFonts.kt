package com.prism.music.ui.screens

import com.prism.music.R
import com.prism.music.tr
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prism.music.ui.components.PrismSheet
import com.prism.music.ui.components.PrismSpinner
import com.prism.music.ui.theme.AppFonts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * #318 (1.8.1): the fonts installed on the phone, each written in itself, to use for the whole app or the lyrics
 * ([slot]) as a picked file would be. [onPicked] gets the font's name once it's copied in.
 */
@Composable
fun InstalledFontsSheet(slot: AppFonts.FontSlot, onPicked: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    val fonts by produceState<List<AppFonts.InstalledFont>?>(null) {
        value = withContext(Dispatchers.IO) { runCatching { AppFonts.installed() }.getOrDefault(emptyList()) }
    }
    PrismSheet(onDismiss) { close ->
        Column(Modifier.padding(bottom = 20.dp)) {
            Text(
                if (slot == AppFonts.FontSlot.APP) tr(R.string.installed_fonts) else tr(R.string.installed_fonts_for_lyrics),
                style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp),
            )
            Text(
                tr(R.string.installed_fonts_note), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp)) }
            val list = fonts
            when {
                list == null -> PrismSpinner(Modifier.padding(20.dp).size(22.dp), stroke = 2.dp)
                list.isEmpty() -> Text(
                    tr(R.string.no_installed_fonts), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp),
                )
                else -> LazyColumn(Modifier.heightIn(max = 480.dp)) {
                    items(list, key = { it.name }) { f ->
                        // Each name in its own face (a font that won't load reads in the app's).
                        val family = remember(f.file) { runCatching { FontFamily(Font(f.file)) }.getOrNull() }
                        Text(
                            f.name, style = MaterialTheme.typography.titleMedium, fontFamily = family, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().testTag("installedFont").clickable {
                                scope.launch {
                                    val r = withContext(Dispatchers.IO) { runCatching { AppFonts.importInstalled(context, f, slot) } }
                                    r.onSuccess { name -> onPicked(name); close {} }
                                        .onFailure { error = it.message ?: tr(R.string.that_font_couldn_t_be_used) }
                                }
                            }.padding(horizontal = 20.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }
}
