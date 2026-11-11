package com.prism.music.data.prefs

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * A setting's value as text with its kind in front ("b:true", "i:3", "l:…", "f:0.5", "d:…", "s:…", "S:[…]" for a set),
 * so a backup can hold settings without the store's own file (which also holds the sign-in) and put back each with its
 * own kind.
 */
object BackupValue {
    fun tag(v: Any?): String? = when (v) {
        is Boolean -> "b:$v"
        is Int -> "i:$v"
        is Long -> "l:$v"
        is Float -> "f:$v"
        is Double -> "d:$v"
        is String -> "s:$v"
        is Set<*> -> "S:" + Json.encodeToString(ListSerializer(String.serializer()), v.filterIsInstance<String>())
        else -> null
    }

    fun put(p: MutablePreferences, name: String, tagged: String) {
        val kind = tagged.substringBefore(':', "")
        val text = tagged.substringAfter(':', "")
        runCatching {
            when (kind) {
                "b" -> p[booleanPreferencesKey(name)] = text.toBooleanStrict()
                "i" -> p[intPreferencesKey(name)] = text.toInt()
                "l" -> p[longPreferencesKey(name)] = text.toLong()
                "f" -> p[floatPreferencesKey(name)] = text.toFloat()
                "d" -> p[doublePreferencesKey(name)] = text.toDouble()
                "s" -> p[stringPreferencesKey(name)] = text
                "S" -> p[stringSetPreferencesKey(name)] = Json.decodeFromString(ListSerializer(String.serializer()), text).toSet()
            }
        }
    }
}
