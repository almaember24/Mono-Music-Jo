package com.prism.music

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * The language Prism speaks (1.7.9): the phone's, or one picked in Settings → Language. On Android 13+ that's Android's
 * own per-app language (so it also shows in the phone's Settings → Apps → Prism → Language); older phones keep the pick
 * here and every Prism context is made in it ([wrap]). Either way Prism restarts on a change, so text read once (a
 * menu's labels, the car's folders) is never left in the old language.
 */
object AppLanguage {
    /** Follow the phone. */
    const val PHONE = ""

    /**
     * The languages Prism has (each one's values-xx folder; TranslationsTest checks the two agree), English first.
     * Translated by Claude; corrections are welcome as pull requests on that language's strings.xml.
     */
    val available: List<String> = listOf(
        "en", "es", "pt-BR", "pt-PT", "fr", "de", "it", "nl", "sv", "pl", "cs", "ro", "hu", "el", "ru", "uk", "tr",
        "ar", "fa", "he", "hi", "bn", "ur", "id", "ms", "vi", "th", "fil", "ja", "ko", "zh-CN", "zh-TW",
    )

    private fun prefs(c: Context) = c.getSharedPreferences("language", Context.MODE_PRIVATE)

    /** The picked language's tag ("es", "pt-BR"), or [PHONE]. */
    fun picked(c: Context): String =
        if (Build.VERSION.SDK_INT >= 33) {
            c.getSystemService(android.app.LocaleManager::class.java)?.applicationLocales?.toLanguageTags().orEmpty()
        } else prefs(c).getString("tag", PHONE).orEmpty()

    /** Switches to [tag] ([PHONE] to follow the phone) and restarts Prism in it. */
    fun pick(c: Context, tag: String) {
        if (tag == picked(c)) return
        if (Build.VERSION.SDK_INT >= 33) {
            c.getSystemService(android.app.LocaleManager::class.java)?.applicationLocales = LocaleList.forLanguageTags(tag)
        } else prefs(c).edit().putString("tag", tag).commit()
        restart(c)
    }

    /** Prism started again in the language it's in now (text read once, like menus' labels, is read again). */
    fun restart(c: Context) {
        val launch = c.packageManager.getLaunchIntentForPackage(c.packageName) ?: return
        c.startActivity(Intent.makeRestartActivityTask(launch.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Runtime.getRuntime().exit(0)
    }

    /**
     * The language Prism's text is in, as the configuration has it: Prism's own on Android 13+ (Android applies the
     * per-app language), the phone's below (a language picked in Prism is applied by [wrap], not here).
     */
    fun shown(c: Context): String =
        if (Build.VERSION.SDK_INT >= 33) c.resources.configuration.locales[0].toLanguageTag()
        else android.content.res.Resources.getSystem().configuration.locales[0].toLanguageTag()

    /**
     * Whether a configuration change brings another language than [was] (Prism's language changed outside Prism:
     * the phone's Settings → Apps → Prism → Language, or the phone's own language while Prism follows it). Below
     * Android 13 a language picked in Prism isn't affected by the phone's.
     */
    fun changed(c: Context, was: String, now: Configuration): Boolean {
        if (Build.VERSION.SDK_INT < 33 && picked(c) != PHONE) return false
        val tag = now.locales[0].toLanguageTag()
        return was.isNotEmpty() && tag != was
    }

    /** [base] in the picked language (below Android 13; above it, Android has already done this). */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = prefs(base).getString("tag", PHONE).orEmpty().ifEmpty { return base }
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration).apply { setLocale(locale); setLayoutDirection(locale) }
        return base.createConfigurationContext(config)
    }

    /** A language's name in itself ("Español", "日本語"), for the picker. */
    fun nativeName(tag: String): String {
        // Chinese by its script, as phones list it (简体中文 / 繁體中文), not by country.
        val l = Locale.forLanguageTag(when (tag) { "zh-CN" -> "zh-Hans"; "zh-TW" -> "zh-Hant"; else -> tag })
        return l.getDisplayName(l).replaceFirstChar { if (it.isLowerCase()) it.titlecase(l) else it.toString() }
    }
}
