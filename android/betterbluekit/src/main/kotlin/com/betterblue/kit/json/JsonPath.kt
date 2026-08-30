package com.betterblue.kit.json

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Walks a dot-separated path into a JSON tree. Path segments that parse as
 * integers index into arrays (`"drvDistance.0.rangeByFuel"`); everything else
 * is an object key. Returns null when any hop is missing or mistyped.
 */
fun JsonElement.atPath(path: String): JsonElement? {
    if (path.isEmpty()) return this
    var current: JsonElement = this
    for (segment in path.split('.')) {
        val index = segment.toIntOrNull()
        current =
            when {
                index != null && current is JsonArray -> current.getOrNull(index) ?: return null
                current is JsonObject -> current[segment] ?: return null
                else -> return null
            }
    }
    return current
}

/** True when this element is a JSON boolean literal (not a number or string). */
val JsonElement?.isJsonBoolean: Boolean
    get() = this is JsonPrimitive && !isString && (content == "true" || content == "false")

/**
 * Numeric extraction accepting numbers *and* numbers-as-strings — the Kotlin
 * analog of the Swift `extractNumber` helper. Deliberately rejects JSON
 * booleans: Kia US sends `fuelLevel: false` for pure EVs, and coercing that to
 * 0.0 painted phantom empty gas tanks.
 */
fun JsonElement?.asDoubleOrNull(): Double? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive.isJsonBoolean) return null
    return primitive.content.toDoubleOrNull()
}

fun JsonElement?.asIntOrNull(): Int? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive.isJsonBoolean) return null
    return primitive.content.toDoubleOrNull()?.let {
        if (it % 1.0 == 0.0) it.toInt() else null
    } ?: primitive.content.toIntOrNull()
}

fun JsonElement?.asLongOrNull(): Long? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive.isJsonBoolean) return null
    return primitive.content.toDoubleOrNull()?.let {
        if (it % 1.0 == 0.0) it.toLong() else null
    } ?: primitive.content.toLongOrNull()
}

/** String content of a primitive; null for objects, arrays, and JSON null. */
fun JsonElement?.asStringOrNull(): String? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive is JsonNull) return null
    return primitive.content
}

/**
 * Boolean extraction accepting JSON booleans plus the APIs' many spellings:
 * "true"/"false", "1"/"0", 1/0, "Y"/"N", "on"/"off".
 */
fun JsonElement?.asBooleanOrNull(): Boolean? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive is JsonNull) return null
    return when (primitive.content.lowercase()) {
        "true", "1", "y", "yes", "on" -> true
        "false", "0", "n", "no", "off" -> false
        else -> null
    }
}

fun JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject

fun JsonElement?.asArrayOrNull(): JsonArray? = this as? JsonArray
