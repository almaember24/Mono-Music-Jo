package com.prism.music.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.prism.music.R
import com.prism.music.tr
import com.prism.music.ui.components.frostFill
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.PrismIcons

/** A description in two parts: what it says, and the credit YouTube puts at its end (null when there's none). */
internal data class AboutText(val body: String, val credit: List<CreditPart>?)

/** A piece of the credit line: its words, and where they lead (null for the words between the links). */
internal data class CreditPart(val text: String, val url: String?)

/** A link as YouTube writes it in a description: "(https://…)", or bare. A Wikipedia page's own name can hold brackets. */
private val LINK = Regex("""\((https?://\S+?)\)(?=\s|$)|（(https?://[^\s）]+)）|(https?://[^\s（）]+)""")

/** What makes a line the credit: a link to Wikipedia or to the license. */
private val CREDIT_LINK = Regex("""https?://\S*(wikipedia\.org|creativecommons\.org)""", RegexOption.IGNORE_CASE)

/**
 * YouTube ends an album's description with its source in its own language: "From Wikipedia (https://…) under Creative
 * Commons Attribution CC-BY-SA 3.0 (https://…)". The license needs it kept, so it's split off into its own small line with
 * the links under their words; the raw addresses aren't read out. Goes by the links, not the words, so any language works.
 */
internal fun splitCredit(text: String): AboutText {
    val first = CREDIT_LINK.find(text) ?: return AboutText(text.trim(), null)
    val lineStart = text.lastIndexOf('\n', first.range.first) + 1
    // On the body's own last line, the credit starts after its last sentence.
    val sentence = text.substring(lineStart, first.range.first).let { before ->
        Regex("""[.!?。！？]["”»)]?\s+""").findAll(before).lastOrNull()?.range?.last?.plus(1) ?: 0
    }
    val start = lineStart + sentence
    val credit = text.substring(start).trim()
    // YouTube's credit is the last line. A link to Wikipedia in the middle of someone's own description isn't one.
    if (credit.isEmpty() || '\n' in credit) return AboutText(text.trim(), null)
    val parts = ArrayList<CreditPart>()
    var at = 0
    for (m in LINK.findAll(credit)) {
        val url = m.groupValues.drop(1).first { it.isNotEmpty() }
        val words = credit.substring(at, m.range.first)
        // "under Creative Commons…": the link goes on the license's name, in whichever language it's said around it.
        val name = words.indexOf("Creative Commons").takeIf { it > 0 }
        if (name != null) {
            parts += CreditPart(words.substring(0, name), null)
            parts += CreditPart(words.substring(name).trim(), url)
        } else {
            val lead = words.length - words.trimStart().length
            if (lead > 0) parts += CreditPart(words.substring(0, lead), null)
            parts += CreditPart(words.trim().ifEmpty { url.substringAfter("://").substringBefore('/') }, url)
        }
        at = m.range.last + 1
    }
    credit.substring(at).takeIf { it.isNotBlank() }?.let { parts += CreditPart(it, null) }
    return AboutText(text.substring(0, start).trim(), parts)
}

/**
 * An album's or playlist's description, in its own quiet box on the page's frosted fill: left-aligned (centered, a long
 * one was a wall of text), a few lines until it's opened, and the source's credit on its own small line.
 */
@Composable
internal fun AboutBox(text: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val about = remember(text) { splitCredit(text) }
    var open by remember(text) { mutableStateOf(false) }
    // Whether there's more than the closed box shows.
    var more by remember(text) { mutableStateOf(false) }
    val fill = if (LocalAppSettings.current.frostedPages) frostFill(1.4f) else scheme.onSurface.copy(alpha = 0.06f)
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(fill).animateContentSize()) {
        if (about.body.isNotEmpty()) Column(
            Modifier.fillMaxWidth().clickable(enabled = more || open) { open = !open }
                .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = if (about.credit == null) 12.dp else 6.dp),
        ) {
            Text(
                about.body, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, textAlign = TextAlign.Start,
                maxLines = if (open) Int.MAX_VALUE else CLOSED_LINES, overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!open) more = it.hasVisualOverflow },
            )
            if (more || open) Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (open) tr(R.string.show_less) else tr(R.string.show_more), style = MaterialTheme.typography.labelLarge, color = scheme.primary)
                Spacer(Modifier.width(2.dp))
                Icon(if (open) PrismIcons.ChevronUp else PrismIcons.ChevronDown, null, Modifier.size(18.dp), tint = scheme.primary)
            }
        }
        about.credit?.let { CreditLine(it, Modifier.padding(start = 14.dp, end = 14.dp, top = if (about.body.isEmpty()) 12.dp else 0.dp, bottom = 12.dp)) }
    }
}

@Composable
private fun CreditLine(parts: List<CreditPart>, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val uri = LocalUriHandler.current
    val link = TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline))
    Text(
        buildAnnotatedString {
            for (p in parts) {
                if (p.url == null) append(p.text)
                else withLink(LinkAnnotation.Url(p.url, link) { runCatching { uri.openUri(p.url) } }) { append(p.text) }
            }
        },
        style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant.copy(alpha = 0.85f), modifier = modifier,
    )
}

/** How many lines the closed box shows. */
private const val CLOSED_LINES = 4
