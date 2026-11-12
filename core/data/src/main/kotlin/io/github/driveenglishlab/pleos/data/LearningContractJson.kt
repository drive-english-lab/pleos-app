package io.github.driveenglishlab.pleos.data

import android.content.Context
import io.github.driveenglishlab.pleos.data.wire.learningWireSerializers
import java.net.URI
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.serialization.json.*

internal val learningJson = Json {
    explicitNulls = true
    ignoreUnknownKeys = false
    coerceInputValues = false
    isLenient = false
    encodeDefaults = false
    serializersModule = learningWireSerializers
}

internal class LearningProtocolException : IllegalArgumentException("Invalid learning contract payload.")

/** Offline validation for the exact schema keywords used in the bundled reviewed contracts. */
internal class LearningContractJson(context: Context) {
    private val documents: Map<String, JsonObject>
    init {
        val names = listOf("common.schema.json", "personal-data.openapi.json", "inference.openapi.json")
        val manifest = context.assets.open("learning-contracts/manifest.json").bufferedReader().use { learningJson.parseToJsonElement(it.readText()).jsonObject }
        documents = names.associateWith { name ->
            val source = context.assets.open("learning-contracts/$name").bufferedReader().use { it.readText() }
            require(LearningJson.hash(source) == manifest[name]?.jsonPrimitive?.content) { "Bundled contract hash mismatch." }
            learningJson.parseToJsonElement(source).jsonObject
        }
        for ((_, document) in documents) {
            val definitions = document["\$defs"]?.jsonObject ?: document["components"]!!.jsonObject["schemas"]!!.jsonObject
            definitions.values.forEach { checkKeywords(it.jsonObject) }
        }
    }

    private fun checkKeywords(schema: JsonObject) {
        val known = setOf("\$ref", "\$schema", "\$id", "title", "description", "type", "properties", "required", "additionalProperties",
            "const", "enum", "format", "allOf", "anyOf", "oneOf", "if", "then", "else", "pattern", "minLength", "maxLength", "minItems", "maxItems",
            "uniqueItems", "contains", "items", "minimum", "maximum", "minProperties", "default", "readOnly", "discriminator")
        require(schema.keys.all { it in known || it.startsWith("x-") }) { "Unsupported learning schema keyword." }
        schema["properties"]?.jsonObject?.values?.forEach { checkKeywords(it.jsonObject) }
        listOf("items", "contains", "if", "then", "else").forEach { key -> schema[key]?.let { checkKeywords(it.jsonObject) } }
        listOf("allOf", "anyOf", "oneOf").forEach { key -> schema[key]?.jsonArray?.forEach { checkKeywords(it.jsonObject) } }
    }

    fun operation(file: String, path: String, method: String): JsonObject = documents.getValue(file)["paths"]!!.jsonObject[path]!!.jsonObject[method]!!.jsonObject
    fun schema(file: String, pointer: String): JsonObject {
        var value: JsonElement = documents.getValue(file)
        for (key in pointer.removePrefix("#/").split('/')) value = value.jsonObject.getValue(key.replace("~1", "/").replace("~0", "~"))
        return value.jsonObject
    }
    fun validate(file: String, schema: JsonObject, value: JsonElement) {
        if (!matches(file, schema, value, 0)) throw LearningProtocolException()
    }

    private fun matches(file: String, schema: JsonObject, value: JsonElement, depth: Int): Boolean {
        if (depth > 64) return false
        fun match(child: JsonElement) = matches(file, child.jsonObject, value, depth + 1)
        schema["\$ref"]?.jsonPrimitive?.content?.let { reference ->
            val parts = reference.split('#', limit = 2); val target = parts[0].ifBlank { file }
            if (target !in documents || !matches(target, schema(target, "#" + parts[1]), value, depth + 1)) return false
        }
        schema["type"]?.jsonPrimitive?.content?.let { type ->
            val primitive = value as? JsonPrimitive
            val ok = when (type) {
                "object" -> value is JsonObject
                "array" -> value is JsonArray
                "string" -> primitive != null && primitive !== JsonNull && primitive.isString
                "boolean" -> primitive != null && !primitive.isString && primitive.booleanOrNull != null
                "integer" -> primitive != null && !primitive.isString && primitive.longOrNull != null
                "null" -> value === JsonNull
                else -> false
            }
            if (!ok) return false
        }
        if (schema.containsKey("const") && schema["const"] != value) return false
        if (schema["enum"]?.jsonArray?.let { value !in it } == true) return false
        if (schema["allOf"]?.jsonArray?.any { !match(it) } == true) return false
        if (schema["anyOf"]?.jsonArray?.none(::match) == true) return false
        if (schema["oneOf"]?.jsonArray?.count(::match)?.let { it != 1 } == true) return false
        schema["if"]?.let { condition ->
            val branch = if (match(condition)) schema["then"] else schema["else"]
            if (branch != null && !match(branch)) return false
        }
        if (value is JsonObject) {
            val properties = schema["properties"]?.jsonObject ?: JsonObject(emptyMap())
            if (schema["required"]?.jsonArray?.any { it.jsonPrimitive.content !in value } == true) return false
            if (schema["additionalProperties"] == JsonPrimitive(false) && value.keys.any { it !in properties }) return false
            if (schema["minProperties"]?.jsonPrimitive?.int?.let { value.size < it } == true) return false
            for ((key, item) in value) {
                val property = properties[key] ?: continue
                if (!matches(file, property.jsonObject, item, depth + 1)) return false
            }
        }
        if (value is JsonArray) {
            if (schema["minItems"]?.jsonPrimitive?.int?.let { value.size < it } == true) return false
            if (schema["maxItems"]?.jsonPrimitive?.int?.let { value.size > it } == true) return false
            if (schema["uniqueItems"] == JsonPrimitive(true) && value.distinct().size != value.size) return false
            schema["items"]?.let { item -> if (value.any { !matches(file, item.jsonObject, it, depth + 1) }) return false }
            schema["contains"]?.let { item -> if (value.none { matches(file, item.jsonObject, it, depth + 1) }) return false }
        }
        if (value is JsonPrimitive && value !== JsonNull) {
            if (value.isString) {
                val text = value.content; val length = text.codePointCount(0, text.length)
                if (schema["minLength"]?.jsonPrimitive?.int?.let { length < it } == true) return false
                if (schema["maxLength"]?.jsonPrimitive?.int?.let { length > it } == true) return false
                if (schema["pattern"]?.jsonPrimitive?.content?.let { !Regex(it).containsMatchIn(text) } == true) return false
                val format = schema["format"]?.jsonPrimitive?.content
                try {
                    when (format) {
                        "uuid" -> if (text.length != 36 || UUID.fromString(text).toString() != text.lowercase(java.util.Locale.ROOT)) return false
                        "date-time" -> OffsetDateTime.parse(text)
                        "uri" -> if (!URI(text).isAbsolute) return false
                        null -> Unit
                        else -> return false
                    }
                } catch (_: Exception) { return false }
            } else {
                val number = value.longOrNull
                if (number != null) {
                    if (schema["minimum"]?.jsonPrimitive?.long?.let { number < it } == true) return false
                    if (schema["maximum"]?.jsonPrimitive?.long?.let { number > it } == true) return false
                }
            }
        }
        return true
    }

    fun parse(source: String): JsonElement {
        val parsed = learningJson.parseToJsonElement(source)
        DuplicateKeyGuard(source).check()
        return parsed
    }
}

/** The JSON decoder handles syntax; this pass rejects duplicate decoded object keys. */
private class DuplicateKeyGuard(private val source: String) {
    private var index = 0
    private fun space() { while (index < source.length && source[index].isWhitespace()) index++ }
    private fun stringEnd(): Int {
        val start = index++
        while (index < source.length) {
            when (source[index++]) { '\\' -> index++; '"' -> return start }
        }
        throw LearningProtocolException()
    }
    private fun value(depth: Int) {
        if (depth > 64) throw LearningProtocolException()
        space()
        when (source[index]) {
            '{' -> {
                index++; space(); val keys = mutableSetOf<String>()
                while (source[index] != '}') {
                    space(); val start = stringEnd()
                    val key = learningJson.decodeFromString<String>(source.substring(start, index))
                    if (!keys.add(key)) throw LearningProtocolException()
                    space(); if (source[index++] != ':') throw LearningProtocolException()
                    value(depth + 1); space()
                    if (source[index] != ',') break
                    index++
                }
                index++
            }
            '[' -> { index++; space(); while (source[index] != ']') { value(depth + 1); space(); if (source[index] != ',') break; index++ }; index++ }
            '"' -> stringEnd()
            else -> while (index < source.length && source[index] !in ",]} \t\r\n") index++
        }
    }
    fun check() { value(0); space(); if (index != source.length) throw LearningProtocolException() }
}
