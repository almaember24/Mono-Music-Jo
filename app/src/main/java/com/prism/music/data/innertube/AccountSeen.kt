package com.prism.music.data.innertube

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * How YouTube itself took a request, from the answer's own `responseContext` (#372, #373, #377: was a listener's request
 * counted as their account?). Only YouTube's yes or no; nothing of the sign-in is in it. Null where the answer doesn't say.
 */
data class YouTubeSeen(val signedIn: Boolean?, val premium: String?) {
    /** For the diagnostics: "signed in yes, membership member". */
    val text: String get() = "signed in " + when (signedIn) { true -> "yes"; false -> "NO"; null -> "not said" } +
        (premium?.let { ", membership $it" } ?: "")

    companion object {
        fun of(answer: JsonElement?): YouTubeSeen {
            val context = answer.obj("responseContext")
            val params = HashMap<String, String>()
            context.arr("serviceTrackingParams")?.forEach { service ->
                service.arr("params")?.forEach { p ->
                    val k = p.str("key")
                    val v = p.str("value")
                    if (k != null && v != null && k in KEYS) params[k] = v
                }
            }
            val signedIn = context.str("mainAppWebResponseContext", "loggedOut")?.let { it != "true" }
                ?: (params["logged_in"] ?: params["yt_li"])?.let { it == "1" }
            return YouTubeSeen(signedIn, params["premium_membership"])
        }

        private val KEYS = setOf("logged_in", "yt_li", "premium_membership")
    }
}

/**
 * The account as a line for the diagnostics (#372, #373, #377), with no secrets: whether it's signed in, which Google
 * account of the session, whether a brand channel is in use, which cookies Prism holds (their NAMES, never a value) and
 * how old the sign-in is. Prism keeps the cookies it got at sign-in and never renews them, so their age may matter.
 */
object AccountSummary {
    fun of(cookie: String, authUser: Int, dataSyncId: String, visitorData: String, savedAt: Long, now: Long = System.currentTimeMillis()): String {
        if (!cookie.contains("SAPISID")) return "signed out"
        val parts = dataSyncId.split("||")
        val channel = when {
            dataSyncId.isBlank() -> "channel id not known"
            parts.size > 1 && parts[0].isNotBlank() && parts[1].isNotBlank() -> "brand channel in use"
            else -> "own channel"
        }
        val age = if (savedAt <= 0) "signed in before 1.8.2.1" else "signed in ${((now - savedAt) / 3_600_000L).coerceAtLeast(0)} h ago"
        return "signed in, Google account #$authUser, $channel, visitor id ${if (visitorData.isBlank()) "not set" else "set"}, $age, cookies: ${cookieNames(cookie).joinToString(" ")}"
    }

    /** Only names: what's before each '='. Anything that doesn't look like a cookie's name (a mis-pasted cookie) is left out. */
    internal fun cookieNames(cookie: String): List<String> = cookie.split(';')
        .filter { '=' in it }
        .map { it.substringBefore('=').trim() }
        .filter { it.length in 1..40 && NAME.matches(it) }
        .distinct().sorted()

    private val NAME = Regex("[A-Za-z0-9_.-]+")
}

/** What a player answer holds for the Premium question, with no links: which audio formats came, and how. */
internal fun describePlayerAnswer(res: JsonObject): String {
    val audio = res.obj("streamingData").arr("adaptiveFormats").orEmpty().mapNotNull { it as? JsonObject }
        .filter { it.str("mimeType")?.startsWith("audio/") == true }
    fun itags(list: List<JsonObject>) = list.mapNotNull { it.str("itag") }.joinToString(",").ifEmpty { "none" }
    val protected = audio.filter { it.arr("drmFamilies")?.isNotEmpty() == true }
    val bare = audio.filter { it !in protected && it.str("url") == null && it.str("signatureCipher") == null && it.str("cipher") == null }
    val links = audio - protected.toSet() - bare.toSet()
    val type = res.str("videoDetails", "musicVideoType")?.removePrefix("MUSIC_VIDEO_TYPE_")
    return "audio ${itags(links)}" +
        (if (bare.isNotEmpty()) ", without links ${itags(bare)}" else "") +
        (if (protected.isNotEmpty()) ", copy-protected ${itags(protected)}" else "") +
        (type?.let { ", type $it" } ?: "") + ", " + YouTubeSeen.of(res).text
}
