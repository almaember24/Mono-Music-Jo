package com.prism.music.data.innertube

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Small toolkit for walking YouTube's deeply nested renderer JSON without
 * hard-coding every layout. Paths accept String keys and Int indexes
 * (negative indexes count from the end).
 */
fun JsonElement?.at(vararg path: Any): JsonElement? {
    var cur: JsonElement? = this
    for (p in path) {
        cur = when {
            cur is JsonObject && p is String -> cur[p]
            cur is JsonArray && p is Int -> {
                val i = if (p < 0) cur.size + p else p
                cur.getOrNull(i)
            }
            else -> null
        }
        if (cur == null) return null
    }
    return cur
}

fun JsonElement?.str(vararg path: Any): String? =
    (at(*path) as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content

fun JsonElement?.obj(vararg path: Any): JsonObject? = at(*path) as? JsonObject
fun JsonElement?.arr(vararg path: Any): JsonArray? = at(*path) as? JsonArray

/** Concatenated text of a `{ runs: [...] }` or `{ simpleText }` node. */
fun JsonElement?.text(): String? {
    if (this !is JsonObject) return null
    this["simpleText"]?.let { return (it as? JsonPrimitive)?.content }
    val runs = this["runs"] as? JsonArray ?: return null
    return runs.joinToString("") { it.str("text") ?: "" }
}

fun JsonElement?.runs(): List<JsonObject> =
    (this.arr("runs"))?.mapNotNull { it as? JsonObject } ?: emptyList()

/** Largest thumbnail URL in a `{ thumbnails: [...] }` list found anywhere under this node. */
fun JsonElement?.bestThumbnail(): String? {
    val list = findFirst("thumbnails") as? JsonArray ?: return null
    return list.lastOrNull().str("url")
}

/** Depth-first search for the first value stored under [key]. */
fun JsonElement?.findFirst(key: String): JsonElement? {
    when (this) {
        is JsonObject -> {
            this[key]?.let { return it }
            for (v in values) v.findFirst(key)?.let { return it }
        }
        is JsonArray -> for (v in this) v.findFirst(key)?.let { return it }
        else -> {}
    }
    return null
}

/** Collects every object stored under [key]; matches are not searched recursively. */
fun JsonElement?.findAll(key: String, out: MutableList<JsonObject> = mutableListOf()): List<JsonObject> {
    when (this) {
        is JsonObject -> for ((k, v) in this) {
            if (k == key && v is JsonObject) out.add(v) else v.findAll(key, out)
        }
        is JsonArray -> for (v in this) v.findAll(key, out)
        else -> {}
    }
    return out
}
